package com.assessment.agentic.orchestration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.assessment.agentic.orchestration.policy.GatePoint;
import com.assessment.agentic.orchestration.policy.PolicyCheck;
import com.assessment.agentic.orchestration.policy.PolicyDecision;
import com.assessment.agentic.orchestration.policy.PolicyEngine;

/**
 * Generic DAG orchestrator. It knows nothing about URL shorteners or SDLC phases: stage behaviour
 * comes from {@link StageHandler}s, graph shape from a {@link GraphPlanner}, and guardrails from a
 * {@link PolicyEngine}.
 *
 * <p>Execution model: repeatedly compute the ready set (PENDING stages whose dependencies are all
 * COMPLETED), apply entry policies and human gates, run the remaining stages of the wave in
 * parallel, join, apply exit policies, publish versioned artifacts, then ask the planner whether the
 * graph should change. A failure stops new work, skips dependents and compensates side effects.
 */
public final class PipelineExecutor implements AutoCloseable {

    public static final Set<String> RESERVED_IDENTITIES = Set.of("agent", "system", "orchestrator", "devflow", "devflow-agent");

    private final Map<String, StageHandler> handlers;
    private final PolicyEngine policies;
    private final GraphPlanner planner;
    private final ExecutorService pool;
    private final Duration stageTimeout;
    private final long retryBackoffMs;
    private final int maxStageExecutions;

    public PipelineExecutor(Map<String, StageHandler> handlersByPhase, PolicyEngine policies, GraphPlanner planner,
                            int parallelism, Duration stageTimeout, long retryBackoffMs, int maxStageExecutions) {
        this.handlers = Map.copyOf(handlersByPhase);
        this.policies = Objects.requireNonNull(policies);
        this.planner = Objects.requireNonNull(planner);
        this.stageTimeout = stageTimeout;
        this.retryBackoffMs = retryBackoffMs;
        this.maxStageExecutions = maxStageExecutions;
        AtomicInteger threads = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "devflow-stage-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ lifecycle commands

    public Pipeline create(String requirement) {
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("Requirement is required.");
        PipelineGraph initial = planner.plan(Map.of());
        Pipeline pipeline = new Pipeline(requirement.trim(), initial, Instant.now());
        pipeline.record("PIPELINE_CREATED", null, "requester", Map.of("requirement", requirement.trim()));
        pipeline.record("GRAPH_PLANNED", null, "orchestrator", Map.of("planner", planner.name(), "stages", initial.topologicalOrder()));
        return pipeline;
    }

    /** Run every stage that is allowed to run until a human gate, a policy block, a failure, or completion. */
    public void advance(Pipeline pipeline) {
        synchronized (pipeline) {
            PipelineStatus status = pipeline.status();
            if (status.isTerminal()) throw new IllegalStateException("Pipeline is " + status + "; no further execution is allowed.");
            if (status == PipelineStatus.FAILED) throw new IllegalStateException("Pipeline FAILED; an operator must resume it explicitly.");
            if (status == PipelineStatus.NEEDS_CLARIFICATION) throw new IllegalStateException("Pipeline needs clarification before execution.");
            setStatus(pipeline, PipelineStatus.RUNNING, "orchestrator");
            // Policy blocks are re-evaluated on every advance, because upstream artifacts may have changed.
            pipeline.stages().stream().filter(r -> r.status() == StageStatus.BLOCKED).forEach(StageRecord::reset);

            while (true) {
                replanIfNeeded(pipeline);
                List<StageRecord> runnable = new ArrayList<>();
                for (StageRecord record : readyStages(pipeline)) {
                    if (!passesGatePolicies(pipeline, record, GatePoint.ENTRY, null)) continue;
                    if (holdAtHumanGate(pipeline, record)) continue;
                    runnable.add(record);
                }
                if (runnable.isEmpty()) break;
                if (pipeline.stageExecutions() + runnable.size() > maxStageExecutions) {
                    safeStopLocked(pipeline, "orchestrator", "Execution budget of " + maxStageExecutions + " stage runs exhausted.");
                    return;
                }
                if (runWave(pipeline, runnable)) return;
            }
            refreshStatus(pipeline);
        }
    }

    public void decide(Pipeline pipeline, String stageId, String decision, String reviewerId, String note) {
        synchronized (pipeline) {
            requireActive(pipeline);
            String normalized = decision == null ? "" : decision.trim().toLowerCase();
            if (!normalized.equals("approve") && !normalized.equals("reject")) throw new IllegalArgumentException("Decision must be approve or reject.");
            validateHuman(reviewerId);
            StageRecord record = pipeline.stage(stageId);
            if (record.status() != StageStatus.AWAITING_APPROVAL) {
                throw new IllegalStateException("Stage " + stageId + " is " + record.status() + ", not AWAITING_APPROVAL.");
            }
            ApprovalRecord approval = new ApprovalRecord(stageId, normalized, reviewerId.trim(), note,
                    pipeline.ancestorVersions(stageId), pipeline.ancestorDigests(stageId), Instant.now());
            record.approval(approval);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("decision", normalized);
            details.put("note", note == null ? "" : note);
            details.put("boundInputVersions", approval.boundInputVersions());
            if (approval.approved()) {
                record.status(StageStatus.PENDING);
                pipeline.record("APPROVAL_GRANTED", stageId, reviewerId, details);
            } else {
                record.status(StageStatus.REJECTED);
                pipeline.record("APPROVAL_REJECTED", stageId, reviewerId, details);
                skipDownstream(pipeline, stageId, "Upstream stage rejected by reviewer");
                rollback(pipeline, "Rejected by reviewer " + reviewerId);
                setStatus(pipeline, PipelineStatus.REJECTED, reviewerId);
            }
        }
    }

    /** Supply missing intent. The stages feeding the clarification gate are re-run, which re-plans the graph. */
    public void clarify(Pipeline pipeline, String clarification, String actor) {
        synchronized (pipeline) {
            if (pipeline.status() != PipelineStatus.NEEDS_CLARIFICATION) {
                throw new IllegalStateException("Pipeline is " + pipeline.status() + ", not NEEDS_CLARIFICATION.");
            }
            if (clarification == null || clarification.isBlank()) throw new IllegalArgumentException("Clarification text is required.");
            StageRecord gate = pipeline.stages().stream()
                    .filter(r -> r.definition().gate() == GateType.HUMAN_CLARIFICATION && r.status() == StageStatus.AWAITING_INPUT)
                    .findFirst().orElseThrow(() -> new IllegalStateException("No clarification gate is waiting for input."));
            pipeline.addClarification(clarification.trim());
            pipeline.record("CLARIFICATION_RECEIVED", gate.id(), actor, Map.of("clarification", clarification.trim()));
            for (String dependency : gate.definition().dependsOn()) {
                invalidate(pipeline, dependency, true, actor, "Requirement clarified");
            }
            setStatus(pipeline, PipelineStatus.RUNNING, actor);
        }
    }

    /**
     * A human edits a completed stage's output (for example the architecture). Downstream work and
     * approvals bound to the old version are invalidated and will be re-planned on the next advance.
     */
    public ArtifactVersion revise(Pipeline pipeline, String stageId, Map<String, Object> content, String reviewerId, String reason) {
        synchronized (pipeline) {
            if (pipeline.status() == PipelineStatus.REJECTED || pipeline.status() == PipelineStatus.STOPPED) {
                throw new IllegalStateException("Pipeline is " + pipeline.status() + "; artifacts can no longer be revised.");
            }
            validateHuman(reviewerId);
            if (content == null || content.isEmpty()) throw new IllegalArgumentException("Revised content is required.");
            StageRecord record = pipeline.stage(stageId);
            if (record.status() != StageStatus.COMPLETED || record.artifact() == null) {
                throw new IllegalStateException("Only COMPLETED stages can be revised; " + stageId + " is " + record.status());
            }
            ArtifactVersion previous = record.artifact();
            StageResult revised = new StageResult(previous.verdict(), "Revised by " + reviewerId + ": " + reason, content);
            if (!passesGatePolicies(pipeline, record, GatePoint.EXIT, revised)) {
                String reasonDenied = record.lastError();
                record.complete(previous, record.attempts(), record.finishedAt());
                throw new IllegalArgumentException("Revision rejected by policy: " + reasonDenied);
            }
            ArtifactVersion artifact = pipeline.publishArtifact(record.definition(), revised, previous.inputVersions(),
                    reviewerId, reason, Instant.now());
            record.artifact(artifact);
            pipeline.record("ARTIFACT_REVISED", stageId, reviewerId,
                    Map.of("fromVersion", previous.version(), "toVersion", artifact.version(), "reason", reason == null ? "" : reason));
            invalidate(pipeline, stageId, false, reviewerId, "Upstream artifact " + artifact.artifactId() + " changed");
            pipeline.completedAt(null);
            setStatus(pipeline, PipelineStatus.RUNNING, reviewerId);
            return artifact;
        }
    }

    /** Recover a FAILED pipeline: clear injected faults and re-queue failed, skipped and rolled-back stages. */
    public void resume(Pipeline pipeline, String operatorId, String reason) {
        synchronized (pipeline) {
            if (pipeline.status() != PipelineStatus.FAILED) throw new IllegalStateException("Only FAILED pipelines can be resumed; current status " + pipeline.status());
            validateHuman(operatorId);
            pipeline.faults().clear();
            List<String> requeued = new ArrayList<>();
            for (StageRecord record : pipeline.stages()) {
                if (record.status() == StageStatus.FAILED || record.status() == StageStatus.SKIPPED || record.status() == StageStatus.ROLLED_BACK) {
                    record.reset();
                    requeued.add(record.id());
                }
            }
            pipeline.record("RECOVERY_STARTED", null, operatorId, Map.of("reason", reason == null ? "" : reason, "requeuedStages", requeued));
            setStatus(pipeline, PipelineStatus.RUNNING, operatorId);
        }
    }

    /** Operator kill switch: no further stages start, side effects are compensated, the run is terminal. */
    public void safeStop(Pipeline pipeline, String operatorId, String reason) {
        synchronized (pipeline) {
            if (pipeline.status().isTerminal()) throw new IllegalStateException("Pipeline is already " + pipeline.status());
            validateHuman(operatorId);
            safeStopLocked(pipeline, operatorId, reason);
        }
    }

    public void injectFault(Pipeline pipeline, String stageId, FaultMode mode) {
        synchronized (pipeline) {
            requireActive(pipeline);
            StageRecord record = pipeline.stage(stageId);
            if (record.status() == StageStatus.COMPLETED) throw new IllegalStateException("Stage " + stageId + " already completed.");
            pipeline.faults().put(stageId, Objects.requireNonNull(mode));
            pipeline.record("FAULT_INJECTED", stageId, "operator", Map.of("mode", mode.name()));
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ scheduling

    private List<StageRecord> readyStages(Pipeline pipeline) {
        List<StageRecord> ready = new ArrayList<>();
        for (StageRecord record : pipeline.stages()) {
            if (record.status() != StageStatus.PENDING) continue;
            boolean depsDone = record.definition().dependsOn().stream()
                    .allMatch(dep -> pipeline.stage(dep).status() == StageStatus.COMPLETED);
            if (depsDone) ready.add(record);
        }
        return ready;
    }

    private boolean holdAtHumanGate(Pipeline pipeline, StageRecord record) {
        GateType gate = record.definition().gate();
        if (gate == GateType.HUMAN_CLARIFICATION) {
            record.status(StageStatus.AWAITING_INPUT);
            pipeline.record("CLARIFICATION_REQUESTED", record.id(), "orchestrator",
                    Map.of("reason", "Agent cannot resolve missing intent; human input required."));
            return true;
        }
        if (gate != GateType.HUMAN_APPROVAL) return false;
        Map<String, Integer> current = pipeline.ancestorVersions(record.id());
        ApprovalRecord approval = record.approval();
        if (approval != null && approval.approved() && approval.boundInputDigests().equals(pipeline.ancestorDigests(record.id()))) return false;
        if (approval != null && approval.approved()) {
            pipeline.record("APPROVAL_INVALIDATED", record.id(), "orchestrator",
                    Map.of("approvedBy", approval.reviewerId(), "boundInputVersions", approval.boundInputVersions(), "currentInputVersions", current,
                            "reason", "Content of reviewed inputs changed since approval"));
            record.approval(null);
        }
        record.status(StageStatus.AWAITING_APPROVAL);
        pipeline.record("APPROVAL_REQUESTED", record.id(), "orchestrator",
                Map.of("inputVersionsUnderReview", current, "title", record.definition().title()));
        return true;
    }

    private boolean passesGatePolicies(Pipeline pipeline, StageRecord record, GatePoint point, StageResult result) {
        PolicyCheck check = new PolicyCheck(point, pipeline.id(), record.definition(), pipeline.ancestorArtifacts(record.id()), result);
        List<PolicyDecision> decisions = policies.evaluate(check);
        for (PolicyDecision decision : decisions) {
            pipeline.record(decision.allowed() ? "POLICY_ALLOWED" : "POLICY_DENIED", record.id(), "policy-engine",
                    Map.of("gate", point.name(), "rule", decision.ruleId(), "category", decision.category(), "reason", decision.reason()));
        }
        PolicyDecision denied = decisions.stream().filter(d -> !d.allowed()).findFirst().orElse(null);
        if (denied == null) return true;
        record.block(denied.ruleId() + ": " + denied.reason());
        if (point == GatePoint.ENTRY) pipeline.record("STAGE_BLOCKED", record.id(), "policy-engine", Map.of("reason", record.lastError()));
        return false;
    }

    /** @return true when the wave failed and the pipeline was halted. */
    private boolean runWave(Pipeline pipeline, List<StageRecord> wave) {
        List<String> ids = wave.stream().map(StageRecord::id).toList();
        String mode = wave.size() > 1 ? "PARALLEL" : "SEQUENTIAL";
        pipeline.countExecutions(wave.size());
        Map<String, Future<Outcome>> futures = new LinkedHashMap<>();
        Map<String, StageContext> contexts = new LinkedHashMap<>();
        for (StageRecord record : wave) {
            StageDefinition definition = record.definition();
            StageHandler handler = handlerFor(definition);
            StageContext context = new StageContext(pipeline.id(), pipeline.requirement(), definition, 1, pipeline.ancestorArtifacts(definition.id()));
            contexts.put(definition.id(), context);
            FaultMode fault = consumeFault(pipeline, definition.id());
            record.markRunning(Instant.now());
            pipeline.record("STAGE_STARTED", definition.id(), "agent:" + definition.phase(),
                    Map.of("executionMode", mode, "wave", ids, "maxAttempts", definition.maxAttempts(),
                            "inputVersions", pipeline.ancestorVersions(definition.id())));
            futures.put(definition.id(), pool.submit(() -> attemptWithRetries(pipeline, handler, context, fault)));
        }

        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, Future<Outcome>> entry : futures.entrySet()) {
            outcomes.put(entry.getKey(), await(entry.getValue()));
        }
        if (wave.size() > 1) pipeline.record("PARALLEL_JOIN", null, "orchestrator", Map.of("joinedStages", ids));

        List<String> failed = new ArrayList<>();
        for (StageRecord record : wave) {
            Outcome outcome = outcomes.get(record.id());
            String failure = outcome.error();
            if (failure == null && !passesGatePolicies(pipeline, record, GatePoint.EXIT, outcome.result())) {
                failure = "Exit gate denied: " + record.lastError();
                compensateQuietly(pipeline, record, outcome.result());
            }
            if (failure == null) {
                ArtifactVersion artifact = pipeline.publishArtifact(record.definition(), outcome.result(),
                        contexts.get(record.id()).inputs().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().version())),
                        "agent:" + record.definition().phase(), "stage execution", Instant.now());
                record.complete(artifact, outcome.attempts(), Instant.now());
                pipeline.record("STAGE_COMPLETED", record.id(), "agent:" + record.definition().phase(),
                        Map.of("verdict", artifact.verdict().name(), "artifactId", artifact.artifactId(), "summary", artifact.summary(),
                                "attempts", outcome.attempts(), "durationMs", record.durationMs()));
            } else {
                record.fail(failure, outcome.attempts(), Instant.now());
                pipeline.openIncident(record.id(), failure, Instant.now());
                pipeline.record("STAGE_FAILED", record.id(), "agent:" + record.definition().phase(),
                        Map.of("reason", failure, "attempts", outcome.attempts(), "retryable", outcome.retryable()));
                failed.add(record.id());
            }
        }
        if (failed.isEmpty()) return false;

        for (String id : failed) skipDownstream(pipeline, id, "Upstream stage " + id + " failed");
        rollback(pipeline, "Failure in " + failed);
        setStatus(pipeline, PipelineStatus.FAILED, "orchestrator");
        pipeline.record("PIPELINE_HALTED", null, "orchestrator",
                Map.of("failedStages", failed, "policy", "No new stages start after a failure; operator must resume or stop."));
        return true;
    }

    private Outcome attemptWithRetries(Pipeline pipeline, StageHandler handler, StageContext base, FaultMode fault) {
        int max = base.stage().maxAttempts();
        for (int attempt = 1; ; attempt++) {
            try {
                injectFault(fault, attempt);
                StageResult result = handler.execute(base.withAttempt(attempt));
                if (result == null) throw new IllegalStateException("Handler returned no result.");
                return new Outcome(result, null, attempt, false);
            } catch (TransientStageException transientFailure) {
                if (attempt >= max) {
                    return new Outcome(null, "Transient failure persisted after " + attempt + " attempts: " + message(transientFailure), attempt, true);
                }
                long backoff = retryBackoffMs * attempt;
                pipeline.record("STAGE_RETRY", base.stage().id(), "orchestrator",
                        Map.of("nextAttempt", attempt + 1, "maxAttempts", max, "backoffMs", backoff, "reason", message(transientFailure)));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Outcome(null, "Interrupted during retry backoff", attempt, true);
                }
            } catch (Exception permanent) {
                return new Outcome(null, message(permanent), attempt, false);
            }
        }
    }

    private Outcome await(Future<Outcome> future) {
        try {
            return future.get(stageTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new Outcome(null, "Stage exceeded timeout of " + stageTimeout.toMillis() + " ms", 1, false);
        } catch (ExecutionException execution) {
            return new Outcome(null, message(execution.getCause()), 1, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Outcome(null, "Orchestrator interrupted", 1, false);
        }
    }

    private static void injectFault(FaultMode fault, int attempt) {
        if (fault == null) return;
        switch (fault) {
            case TRANSIENT_ONCE -> { if (attempt == 1) throw new TransientStageException("Injected transient fault"); }
            case TRANSIENT_ALWAYS -> throw new TransientStageException("Injected persistent transient fault");
            case PERMANENT -> throw new StageFailureException("Injected permanent fault");
        }
    }

    private FaultMode consumeFault(Pipeline pipeline, String stageId) {
        FaultMode fault = pipeline.faults().get(stageId);
        if (fault == FaultMode.TRANSIENT_ONCE || fault == FaultMode.PERMANENT) pipeline.faults().remove(stageId);
        return fault;
    }

    // ------------------------------------------------------------------ re-planning and invalidation

    private void replanIfNeeded(Pipeline pipeline) {
        PipelineGraph desired = planner.plan(pipeline.currentArtifacts());
        PipelineGraph current = pipeline.graph();
        if (sameGraph(current, desired)) return;
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> changed = new LinkedHashSet<>();
        Map<String, StageRecord> retained = new LinkedHashMap<>();
        for (StageDefinition definition : desired.stages()) {
            if (!current.contains(definition.id())) added.add(definition.id());
            else if (!current.stage(definition.id()).equals(definition)) changed.add(definition.id());
            else retained.put(definition.id(), pipeline.stage(definition.id()));
        }
        current.topologicalOrder().stream().filter(id -> !desired.contains(id)).forEach(removed::add);
        for (String id : concat(removed, changed)) {
            StageRecord record = pipeline.stage(id);
            if (record.status() == StageStatus.COMPLETED && record.artifact() != null) compensateIfNeeded(pipeline, record, "Stage removed or redefined by re-plan");
        }
        pipeline.replaceGraph(desired, retained);
        Map<String, Integer> trigger = new LinkedHashMap<>();
        pipeline.currentArtifacts().forEach((key, value) -> trigger.put(key, value.version()));
        pipeline.record("GRAPH_REPLANNED", null, "planner:" + planner.name(),
                Map.of("added", List.copyOf(added), "removed", List.copyOf(removed), "redefined", List.copyOf(changed),
                        "triggeringArtifactVersions", trigger, "stages", desired.topologicalOrder()));
    }

    private void invalidate(Pipeline pipeline, String stageId, boolean includeSelf, String actor, String reason) {
        List<String> targets = new ArrayList<>();
        if (includeSelf) targets.add(stageId);
        targets.addAll(pipeline.graph().downstreamOf(stageId));
        List<String> reversed = new ArrayList<>(targets);
        java.util.Collections.reverse(reversed);
        for (String id : reversed) {
            StageRecord record = pipeline.stage(id);
            if (record.status() == StageStatus.COMPLETED) compensateIfNeeded(pipeline, record, reason);
        }
        for (String id : targets) {
            StageRecord record = pipeline.stage(id);
            if (record.approval() != null) {
                pipeline.record("APPROVAL_INVALIDATED", id, "orchestrator",
                        Map.of("approvedBy", record.approval().reviewerId(), "reason", reason));
                record.approval(null);
            }
            record.reset();
        }
        pipeline.record("STAGES_INVALIDATED", stageId, actor, Map.of("reason", reason, "stages", targets));
    }

    private void skipDownstream(Pipeline pipeline, String stageId, String reason) {
        for (String id : pipeline.graph().downstreamOf(stageId)) {
            StageRecord record = pipeline.stage(id);
            if (record.status() == StageStatus.COMPLETED || record.status() == StageStatus.FAILED) continue;
            record.status(StageStatus.SKIPPED);
            pipeline.record("STAGE_SKIPPED", id, "orchestrator", Map.of("reason", reason));
        }
    }

    /** Saga-style compensation of completed side-effecting stages, in reverse topological order. */
    private void rollback(Pipeline pipeline, String reason) {
        List<StageRecord> completed = new ArrayList<>(pipeline.stages());
        java.util.Collections.reverse(completed);
        for (StageRecord record : completed) {
            if (record.status() == StageStatus.COMPLETED && compensateIfNeeded(pipeline, record, reason)) {
                record.status(StageStatus.ROLLED_BACK);
            }
        }
    }

    private boolean compensateIfNeeded(Pipeline pipeline, StageRecord record, String reason) {
        StageHandler handler = handlerFor(record.definition());
        if (!handler.hasSideEffects() || record.artifact() == null) return false;
        pipeline.record("ROLLBACK_STARTED", record.id(), "orchestrator", Map.of("artifactId", record.artifact().artifactId(), "reason", reason));
        try {
            handler.compensate(pipeline.id(), record.artifact());
            pipeline.record("ROLLBACK_COMPLETED", record.id(), "orchestrator", Map.of("artifactId", record.artifact().artifactId()));
            return true;
        } catch (Exception failure) {
            pipeline.record("ROLLBACK_FAILED", record.id(), "orchestrator",
                    Map.of("artifactId", record.artifact().artifactId(), "reason", message(failure), "action", "Manual intervention required"));
            return false;
        }
    }

    private void compensateQuietly(Pipeline pipeline, StageRecord record, StageResult rejectedOutput) {
        StageHandler handler = handlerFor(record.definition());
        if (!handler.hasSideEffects()) return;
        ArtifactVersion provisional = new ArtifactVersion(record.id() + "-rejected", record.id(), 0, rejectedOutput.verdict(),
                rejectedOutput.summary(), rejectedOutput.output(), Map.of(), "agent", "rejected by exit gate", Instant.now(), "");
        try {
            handler.compensate(pipeline.id(), provisional);
            pipeline.record("ROLLBACK_COMPLETED", record.id(), "orchestrator", Map.of("artifactId", provisional.artifactId()));
        } catch (Exception failure) {
            pipeline.record("ROLLBACK_FAILED", record.id(), "orchestrator", Map.of("reason", message(failure)));
        }
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus(Pipeline pipeline) {
        List<StageStatus> statuses = pipeline.stages().stream().map(StageRecord::status).toList();
        PipelineStatus next;
        if (statuses.stream().allMatch(s -> s == StageStatus.COMPLETED)) next = PipelineStatus.COMPLETED;
        else if (statuses.contains(StageStatus.AWAITING_INPUT)) next = PipelineStatus.NEEDS_CLARIFICATION;
        else if (statuses.contains(StageStatus.AWAITING_APPROVAL)) next = PipelineStatus.AWAITING_APPROVAL;
        else next = PipelineStatus.BLOCKED;

        if (next == PipelineStatus.COMPLETED && pipeline.completedAt() == null) {
            Instant now = Instant.now();
            pipeline.completedAt(now);
            Incident recovered = pipeline.closeOpenIncident(now);
            if (recovered != null) {
                pipeline.record("RECOVERY_COMPLETED", recovered.stageId(), "orchestrator",
                        Map.of("timeToRecoverMs", recovered.timeToRecoverMs(), "failureReason", recovered.reason()));
            }
            pipeline.record("PIPELINE_COMPLETED", null, "orchestrator",
                    Map.of("endToEndLatencyMs", Duration.between(pipeline.createdAt(), now).toMillis()));
        }
        setStatus(pipeline, next, "orchestrator");
    }

    private void setStatus(Pipeline pipeline, PipelineStatus next, String actor) {
        PipelineStatus previous = pipeline.status();
        if (previous == next) return;
        pipeline.status(next);
        pipeline.record("PIPELINE_STATUS_CHANGED", null, actor, Map.of("from", previous.name(), "to", next.name()));
    }

    private void safeStopLocked(Pipeline pipeline, String actor, String reason) {
        for (StageRecord record : pipeline.stages()) {
            StageStatus s = record.status();
            if (s == StageStatus.PENDING || s == StageStatus.AWAITING_APPROVAL || s == StageStatus.AWAITING_INPUT || s == StageStatus.BLOCKED) {
                record.status(StageStatus.SKIPPED);
            }
        }
        rollback(pipeline, "Safe stop: " + reason);
        pipeline.record("SAFE_STOP", null, actor, Map.of("reason", reason == null ? "" : reason));
        setStatus(pipeline, PipelineStatus.STOPPED, actor);
    }

    // ------------------------------------------------------------------ helpers

    private StageHandler handlerFor(StageDefinition definition) {
        StageHandler handler = handlers.get(definition.phase());
        if (handler == null && definition.gate() != GateType.HUMAN_CLARIFICATION) {
            throw new IllegalStateException("No agent registered for phase " + definition.phase() + " (allow-list violation).");
        }
        return handler == null ? context -> { throw new IllegalStateException("Human-only stage"); } : handler;
    }

    private static void requireActive(Pipeline pipeline) {
        if (pipeline.status().isTerminal()) throw new IllegalStateException("Pipeline is " + pipeline.status());
    }

    private static void validateHuman(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("A reviewer/operator id is required.");
        if (RESERVED_IDENTITIES.contains(actorId.trim().toLowerCase())) {
            throw new IllegalArgumentException("Segregation of duties: agent identities cannot approve or operate pipelines.");
        }
    }

    private static boolean sameGraph(PipelineGraph a, PipelineGraph b) {
        return a.topologicalOrder().equals(b.topologicalOrder()) && a.stages().equals(b.stages());
    }

    private static List<String> concat(Set<String> a, Set<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static String message(Throwable throwable) {
        if (throwable == null) return "Unknown failure";
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record Outcome(StageResult result, String error, int attempts, boolean retryable) {
    }
}
