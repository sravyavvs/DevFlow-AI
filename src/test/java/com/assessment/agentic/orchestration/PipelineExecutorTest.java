package com.assessment.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.assessment.agentic.orchestration.policy.PolicyEngine;
import com.assessment.agentic.orchestration.policy.SecretLeakRule;

/** Engine behaviour with fake agents: A -> (B || C) -> D[approval] -> E (side effect). */
class PipelineExecutorTest {

    private final CountDownLatch bothPlanningStagesRunning = new CountDownLatch(2);
    private final AtomicBoolean ranInParallel = new AtomicBoolean(true);
    private final AtomicInteger compensations = new AtomicInteger();
    private final Map<String, StageHandler> handlers = new HashMap<>();
    private PipelineExecutor executor;

    private static final GraphPlanner PLANNER = artifacts -> new PipelineGraph(List.of(
            StageDefinition.of("A", "analyse", GateType.NONE, 1),
            StageDefinition.of("B", "design", GateType.NONE, 2, "A"),
            StageDefinition.of("C", "plan tests", GateType.NONE, 2, "A"),
            StageDefinition.of("D", "high impact", GateType.HUMAN_APPROVAL, 1, "B", "C"),
            StageDefinition.of("E", "side effect", GateType.NONE, 3, "D")));

    private PipelineExecutor executor(StageHandler e) {
        handlers.put("A", ctx -> StageResult.passed("analysed", Map.of("requirement", ctx.requirement())));
        StageHandler parallel = ctx -> {
            bothPlanningStagesRunning.countDown();
            if (!bothPlanningStagesRunning.await(2, TimeUnit.SECONDS)) ranInParallel.set(false);
            return StageResult.passed(ctx.stage().id() + " done", Map.of("from", ctx.input("A").get("requirement")));
        };
        handlers.put("B", parallel);
        handlers.put("C", parallel);
        handlers.put("D", ctx -> StageResult.passed("approved work", Map.of("inputs", ctx.inputs().keySet().toString())));
        handlers.put("E", e);
        executor = new PipelineExecutor(handlers, new PolicyEngine(List.of(new SecretLeakRule())), PLANNER, 4, Duration.ofSeconds(5), 1, 40);
        return executor;
    }

    private StageHandler sideEffect() {
        return new StageHandler() {
            @Override public StageResult execute(StageContext context) { return StageResult.passed("wrote", Map.of("file", "x")); }
            @Override public boolean hasSideEffects() { return true; }
            @Override public void compensate(String pipelineId, ArtifactVersion artifact) { compensations.incrementAndGet(); }
        };
    }

    @AfterEach
    void close() {
        if (executor != null) executor.close();
    }

    private static long count(Pipeline p, String type) {
        return p.events().stream().filter(e -> type.equals(e.type())).count();
    }

    @Test
    void runsIndependentStagesInParallelJoinsAndStopsAtApprovalGate() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline p = ex.create("requirement text");
        ex.advance(p);
        assertTrue(ranInParallel.get(), "B and C must execute concurrently");
        assertEquals(1, count(p, "PARALLEL_JOIN"));
        assertEquals(PipelineStatus.AWAITING_APPROVAL, p.status());
        assertEquals(StageStatus.AWAITING_APPROVAL, p.stage("D").status());
        assertEquals(StageStatus.PENDING, p.stage("E").status());
        assertEquals(Map.of("A", 1, "B", 1, "C", 1), p.ancestorVersions("D"));
    }

    @Test
    void approvalIsBoundToInputVersionsAndAgentsCannotApprove() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline p = ex.create("requirement text");
        ex.advance(p);
        assertThrows(IllegalArgumentException.class, () -> ex.decide(p, "D", "approve", "agent", "self approval"));
        assertThrows(IllegalStateException.class, () -> ex.decide(p, "E", "approve", "alice", "not awaiting"));
        ex.decide(p, "D", "approve", "alice", "looks good");
        ex.advance(p);
        assertEquals(PipelineStatus.COMPLETED, p.status());
        assertEquals("alice", p.stage("D").approval().reviewerId());
        assertNotNull(PipelineMetrics.of(p).get("endToEndLatencyMs"));
    }

    @Test
    void revisingUpstreamArtifactInvalidatesDownstreamWorkAndApproval() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline p = ex.create("requirement text");
        ex.advance(p);
        ex.decide(p, "D", "approve", "alice", "ok");
        ex.advance(p);
        assertEquals(PipelineStatus.COMPLETED, p.status());

        ex.revise(p, "B", Map.of("design", "v2"), "bob", "switch storage design");
        assertEquals(1, compensations.get(), "side effect of E must be compensated when its inputs change");
        assertEquals(StageStatus.PENDING, p.stage("D").status());
        assertNull(p.stage("D").approval());
        ex.advance(p);
        assertEquals(PipelineStatus.AWAITING_APPROVAL, p.status(), "fresh approval required for the changed inputs");
        assertEquals(Integer.valueOf(2), p.ancestorVersions("D").get("B"));
        assertTrue(count(p, "APPROVAL_INVALIDATED") >= 1);
    }

    @Test
    void transientFailuresAreRetriedWithinBudget() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline p = ex.create("requirement text");
        ex.injectFault(p, "B", FaultMode.TRANSIENT_ONCE);
        ex.advance(p);
        assertEquals(StageStatus.COMPLETED, p.stage("B").status());
        assertEquals(2, p.stage("B").attempts());
        assertEquals(1L, PipelineMetrics.of(p).get("retryCount"));
    }

    @Test
    void exhaustedRetriesFailSafelyThenResumeRecoversAndMeasuresMttr() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline p = ex.create("requirement text");
        ex.injectFault(p, "C", FaultMode.TRANSIENT_ALWAYS);
        ex.advance(p);
        assertEquals(PipelineStatus.FAILED, p.status());
        assertEquals(StageStatus.FAILED, p.stage("C").status());
        assertEquals(2, p.stage("C").attempts());
        assertEquals(StageStatus.SKIPPED, p.stage("D").status());
        assertThrows(IllegalStateException.class, () -> ex.advance(p), "failed pipeline must not continue without an operator");

        ex.resume(p, "operator-1", "fault cleared");
        ex.advance(p);
        ex.decide(p, "D", "approve", "alice", "ok");
        ex.advance(p);
        assertEquals(PipelineStatus.COMPLETED, p.status());
        Map<String, Object> metrics = PipelineMetrics.of(p);
        assertNotNull(metrics.get("mttrMs"));
        assertEquals(1, metrics.get("incidents"));
    }

    @Test
    void permanentFailureRollsBackCompletedSideEffects() {
        StageHandler failing = new StageHandler() {
            private final AtomicInteger calls = new AtomicInteger();
            @Override public StageResult execute(StageContext context) {
                if (calls.incrementAndGet() == 1) return StageResult.passed("wrote", Map.of("file", "x"));
                throw new StageFailureException("boom");
            }
            @Override public boolean hasSideEffects() { return true; }
            @Override public void compensate(String pipelineId, ArtifactVersion artifact) { compensations.incrementAndGet(); }
        };
        PipelineExecutor ex = executor(failing);
        Pipeline p = ex.create("requirement text");
        ex.advance(p);
        ex.decide(p, "D", "approve", "alice", "ok");
        ex.advance(p);
        assertEquals(PipelineStatus.COMPLETED, p.status());
        // Upstream change re-runs E, which now fails permanently: no retries, pipeline halts.
        ex.revise(p, "C", Map.of("plan", "v2"), "bob", "new test plan");
        ex.advance(p);
        ex.decide(p, "D", "approve", "alice", "re-approve");
        ex.advance(p);
        assertEquals(PipelineStatus.FAILED, p.status());
        assertEquals(1, p.stage("E").attempts(), "permanent failures are not retried");
        assertTrue(compensations.get() >= 1);
    }

    @Test
    void rejectionAndSafeStopAreTerminal() {
        PipelineExecutor ex = executor(sideEffect());
        Pipeline rejected = ex.create("requirement text");
        ex.advance(rejected);
        ex.decide(rejected, "D", "reject", "alice", "scope too broad");
        assertEquals(PipelineStatus.REJECTED, rejected.status());
        assertEquals(StageStatus.SKIPPED, rejected.stage("E").status());
        assertThrows(IllegalStateException.class, () -> ex.advance(rejected));

        Pipeline stopped = ex.create("requirement text");
        ex.advance(stopped);
        ex.safeStop(stopped, "operator-1", "incident in progress");
        assertEquals(PipelineStatus.STOPPED, stopped.status());
        assertThrows(IllegalStateException.class, () -> ex.advance(stopped));
    }

    @Test
    void exitPolicyRejectsSecretBearingOutput() {
        PipelineExecutor ex = executor(ctx -> StageResult.passed("leaky", Map.of("config", "api_key=abcdefgh12345678")));
        Pipeline p = ex.create("requirement text");
        ex.advance(p);
        ex.decide(p, "D", "approve", "alice", "ok");
        ex.advance(p);
        assertEquals(PipelineStatus.FAILED, p.status());
        assertTrue(p.stage("E").lastError().contains("SEC-001"));
        assertFalse(p.currentArtifacts().containsKey("E"));
    }
}
