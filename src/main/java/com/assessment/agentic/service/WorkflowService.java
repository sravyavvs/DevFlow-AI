package com.assessment.agentic.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.assessment.agentic.model.AuditEvent;
import com.assessment.agentic.model.TaskItem;
import com.assessment.agentic.model.TaskStatus;
import com.assessment.agentic.model.WorkflowArtifact;
import com.assessment.agentic.model.WorkflowRun;
import com.assessment.agentic.url.UrlMapping;
import com.assessment.agentic.url.UrlNotFoundException;
import com.assessment.agentic.url.UrlShortenerService;

/**
 * Controlled workflow orchestrator. Analysis/planning is low-risk and may run
 * before approval. The only executable change-capable action is an allowlisted
 * local URL-shortener test; source files, shell commands, and deployments are
 * outside the autonomy boundary.
 */
@Service
public class WorkflowService {
    private final ConcurrentMap<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<AuditEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<WorkflowArtifact>> artifacts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final RequirementAnalysisService analyzer = new RequirementAnalysisService();
    private final UrlShortenerService urlService;
    private final WorkflowPersistence persistence;

    @Autowired
    public WorkflowService(UrlShortenerService urlService, WorkflowPersistence persistence) {
        this.urlService = urlService;
        this.persistence = persistence;
        if (persistence != null) {
            runs.putAll(persistence.loadRuns());
            events.putAll(persistence.loadEvents());
            artifacts.putAll(persistence.loadArtifacts());
            runs.keySet().forEach(id -> { locks.put(id, new Object()); events.computeIfAbsent(id, ignored -> new ArrayList<>()); artifacts.computeIfAbsent(id, ignored -> new ArrayList<>()); });
            recoverInterruptedRuns();
        }
    }

    /** Convenience constructor for isolated unit tests. */
    public WorkflowService(UrlShortenerService urlService) { this(urlService, null); }

    public WorkflowRun createRun(String rawRequirement) {
        String requirement = rawRequirement == null ? "" : rawRequirement.trim();
        if (requirement.length() < 10) throw new IllegalArgumentException("Requirement must contain at least 10 characters.");
        synchronized (this) {
            String id = UUID.randomUUID().toString();
            RequirementAnalysisService.Analysis analysis = analyzer.analyze(requirement);
            boolean ambiguous = "AMBIGUOUS".equals(analysis.scenario());
            List<String> assumptions = new ArrayList<>(analysis.assumptions());
            assumptions.add("Analysis source: " + analysis.source());
            assumptions.add("Autonomy boundary: local URL-shortener service checks only; no source edits, shell commands, deployments, or external writes.");
            assumptions.add("Human approval is required before checks create a URL mapping or increment analytics.");

            List<TaskItem> tasks = new ArrayList<>();
            tasks.add(task("ANALYZE", "NORMALIZE_REQUIREMENT", "Normalize requirement and identify assumptions", List.of(), TaskStatus.COMPLETED));
            if (ambiguous) {
                tasks.add(task("CLARIFY", "CLARIFICATION_GATE", "Resolve ambiguous acceptance criteria", List.of("ANALYZE"), TaskStatus.NEEDS_CLARIFICATION));
                tasks.add(task("SERVICE_CHECKS", "RUN_VALIDATION", "Run only supported, approved checks", List.of("CLARIFY"), TaskStatus.BLOCKED_PENDING_APPROVAL));
                tasks.add(task("EVIDENCE", "VALIDATE_EVIDENCE", "Validate evidence and prepare summary", List.of("SERVICE_CHECKS"), TaskStatus.BLOCKED));
            } else {
                String designAction = "BROWNFIELD".equals(analysis.scenario()) ? "CODEBASE_IMPACT" : "API_DESIGN";
                String designTitle = "BROWNFIELD".equals(analysis.scenario()) ? "Inspect existing modules, API contracts, and regression surface" : "Design API and data model from the requirement";
                String acceptanceAction = "BROWNFIELD".equals(analysis.scenario()) ? "REGRESSION_PLAN" : "ACCEPTANCE_PLAN";
                String acceptanceTitle = "BROWNFIELD".equals(analysis.scenario()) ? "Define regression checks and expected error behavior" : "Derive acceptance criteria and edge cases";
                tasks.add(task("DESIGN", designAction, designTitle, List.of("ANALYZE"), TaskStatus.READY));
                tasks.add(task("ACCEPTANCE", acceptanceAction, acceptanceTitle, List.of("ANALYZE"), TaskStatus.READY));
                List<String> planningDependencies = new ArrayList<>(List.of("DESIGN", "ACCEPTANCE"));
                if ("BROWNFIELD".equals(analysis.scenario())) {
                    tasks.add(task("BASELINE", "REGRESSION_BASELINE", "Inventory existing regression coverage and impacted contracts", List.of("ANALYZE"), TaskStatus.READY));
                    planningDependencies.add("BASELINE");
                }
                tasks.add(task("SERVICE_CHECKS", "RUN_VALIDATION", "Run supported checks after human approval", List.copyOf(planningDependencies), TaskStatus.BLOCKED_PENDING_APPROVAL));
                tasks.add(task("EVIDENCE", "VALIDATE_EVIDENCE", "Validate requirement-linked evidence and prepare summary", List.of("SERVICE_CHECKS"), TaskStatus.BLOCKED));
            }

            Map<String, Object> approval = new LinkedHashMap<>();
            approval.put("required", true);
            approval.put("decision", null);
            approval.put("reviewerId", null);
            approval.put("scope", "Execute allowlisted local URL-shortener checks only; no code changes or deployment.");
            approval.put("reason", "The approved checks create a local URL mapping and increment click analytics.");
            Map<String, Object> validation = new LinkedHashMap<>();
            validation.put("status", ambiguous ? "BLOCKED" : "NOT_RUN");
            validation.put("checks", List.of());
            validation.put("note", ambiguous ? "Clarification is required before acceptance criteria can be approved." : "Validation has not run.");

            WorkflowRun run = new WorkflowRun(id, ambiguous ? "NEEDS_CLARIFICATION" : "PLANNING", requirement,
                    ambiguous ? "CLARIFICATION" : "PLANNING", List.copyOf(assumptions), List.copyOf(tasks), approval, validation, Instant.now());
            runs.put(id, run); events.put(id, new ArrayList<>()); artifacts.put(id, new ArrayList<>()); locks.put(id, new Object());
            persist(run);
            log(id, "RUN_CREATED", Map.of("requirement", requirement));
            log(id, "REQUIREMENT_NORMALIZED", Map.of("scenario", analysis.scenario(), "analysisSource", analysis.source(), "assumptions", assumptions));
            createArtifact(id, "ANALYZE", "REQUIREMENT_BRIEF", "Normalized requirement", Map.of("requirement", requirement, "scenario", analysis.scenario(), "analysisSource", analysis.source(), "assumptions", assumptions));
            log(id, "TASKS_DECOMPOSED", Map.of("taskIds", tasks.stream().map(TaskItem::id).toList(), "dependencyModel", "DAG", "parallelReadyTasks", ambiguous ? List.of() : List.of("DESIGN", "ACCEPTANCE")));
            log(id, "POLICY_EVALUATED", Map.of("decision", ambiguous ? "BLOCKED_NEEDS_CLARIFICATION" : "ALLOW_PLANNING_ONLY", "highImpactExecutionRequiresApproval", true));
            if (ambiguous) log(id, "CLARIFICATION_REQUIRED", Map.of("reason", "The requirement uses broad quality goals without measurable acceptance criteria."));
            else log(id, "APPROVAL_REQUIRED", Map.of("reason", approval.get("reason")));
            return run;
        }
    }

    public WorkflowRun getRun(String id) {
        WorkflowRun run = runs.get(id);
        if (run == null) throw new NoSuchElementException("Run not found: " + id);
        return run;
    }
    public List<AuditEvent> getEvents(String id) { getRun(id); return List.copyOf(events.get(id)); }
    public List<WorkflowArtifact> getArtifacts(String id) { getRun(id); return List.copyOf(artifacts.get(id)); }

    /** Clarification creates a new decision-lineage event and replans from the clarified scope. */
    public WorkflowRun clarify(String id, String clarification) {
        synchronized (lock(id)) {
            WorkflowRun old = getRun(id);
            if (!"NEEDS_CLARIFICATION".equals(old.status())) throw new IllegalStateException("Workflow is not waiting for clarification.");
            if (clarification == null || clarification.trim().length() < 5) throw new IllegalArgumentException("A meaningful clarification is required.");
            String updatedRequirement = old.requirement() + "\nClarification: " + clarification.trim();
            RequirementAnalysisService.Analysis analysis = analyzer.analyze(clarification.trim());
            if ("AMBIGUOUS".equals(analysis.scenario())) throw new IllegalArgumentException("Clarification is still ambiguous. Specify observable behavior and measurable acceptance criteria.");
            List<String> assumptions = new ArrayList<>(old.assumptions());
            assumptions.add("Reviewer clarification: " + clarification.trim());
            List<TaskItem> tasks = new ArrayList<>();
            tasks.add(task("ANALYZE", "NORMALIZE_REQUIREMENT", "Normalize requirement and clarification", List.of(), TaskStatus.COMPLETED));
            boolean brownfield = "BROWNFIELD".equals(analysis.scenario());
            tasks.add(task("DESIGN", brownfield ? "CODEBASE_IMPACT" : "API_DESIGN", brownfield ? "Inspect existing modules, contracts, and regression surface" : "Design API and data model from clarified scope", List.of("ANALYZE"), TaskStatus.READY));
            tasks.add(task("ACCEPTANCE", brownfield ? "REGRESSION_PLAN" : "ACCEPTANCE_PLAN", brownfield ? "Define regression checks and error behavior" : "Derive measurable acceptance criteria", List.of("ANALYZE"), TaskStatus.READY));
            List<String> planningDependencies = new ArrayList<>(List.of("DESIGN", "ACCEPTANCE"));
            if (brownfield) { tasks.add(task("BASELINE", "REGRESSION_BASELINE", "Inventory existing regression coverage and impacted contracts", List.of("ANALYZE"), TaskStatus.READY)); planningDependencies.add("BASELINE"); }
            tasks.add(task("SERVICE_CHECKS", "RUN_VALIDATION", "Run supported checks after human approval", List.copyOf(planningDependencies), TaskStatus.BLOCKED_PENDING_APPROVAL));
            tasks.add(task("EVIDENCE", "VALIDATE_EVIDENCE", "Validate evidence and prepare summary", List.of("SERVICE_CHECKS"), TaskStatus.BLOCKED));
            Map<String,Object> approval = new LinkedHashMap<>(old.approval()); approval.put("decision", null); approval.put("reviewerId", null);
            WorkflowRun updated = new WorkflowRun(id, "PLANNING", updatedRequirement, "PLANNING", List.copyOf(assumptions), List.copyOf(tasks), approval,
                    Map.of("status", "NOT_RUN", "checks", List.of(), "note", "Clarified scope replanned; awaiting approval."), old.createdAt());
            putRun(updated);
            log(id, "REQUIREMENT_CLARIFIED", Map.of("clarification", clarification.trim(), "scenario", analysis.scenario(), "analysisSource", analysis.source()));
            log(id, "WORKFLOW_REPLANNED", Map.of("reason", "Clarification changed the task graph", "taskIds", tasks.stream().map(TaskItem::id).toList()));
            createArtifact(id, "ANALYZE", "REVISED_REQUIREMENT_BRIEF", "Clarified requirement", Map.of("requirement", updatedRequirement, "scenario", analysis.scenario(), "clarification", clarification.trim()));
            log(id, "APPROVAL_REQUIRED", Map.of("reason", "Replanned scope requires fresh human approval."));
            return updated;
        }
    }

    public WorkflowRun decide(String id, String decision, String reviewerId, String reviewerNote) {
        synchronized (lock(id)) {
            WorkflowRun old = getRun(id);
            if (!"PLANNING".equals(old.status()) && !"PENDING_APPROVAL".equals(old.status())) throw new IllegalStateException("Run is not awaiting approval. Clarify ambiguous requirements first.");
            if (old.tasks().stream().anyMatch(t -> t.status() == TaskStatus.NEEDS_CLARIFICATION)) throw new IllegalStateException("Workflow must be clarified before approval.");
            if (old.tasks().stream().anyMatch(t -> ("API_DESIGN".equals(t.action()) || "CODEBASE_IMPACT".equals(t.action()) || "ACCEPTANCE_PLAN".equals(t.action()) || "REGRESSION_PLAN".equals(t.action()) || "REGRESSION_BASELINE".equals(t.action())) && t.status() != TaskStatus.COMPLETED)) throw new IllegalStateException("Run POST /api/runs/{runId}/execute once to finish low-risk planning before approval.");
            boolean approved = "approve".equalsIgnoreCase(decision);
            if (!approved && !"reject".equalsIgnoreCase(decision)) throw new IllegalArgumentException("Decision must be approve or reject.");
            if (reviewerId == null || reviewerId.isBlank()) throw new IllegalArgumentException("Reviewer identity is required.");
            Map<String,Object> approval = new LinkedHashMap<>(old.approval());
            approval.put("decision", approved ? "approve" : "reject"); approval.put("reviewerId", reviewerId.trim()); approval.put("reviewerNote", Objects.toString(reviewerNote, ""));
            List<TaskItem> tasks = new ArrayList<>(old.tasks());
            String actionTaskId = findTask(tasks, "RUN_VALIDATION").id();
            for (int i=0; i<tasks.size(); i++) if (tasks.get(i).id().equals(actionTaskId)) tasks.set(i, withStatus(tasks.get(i), approved ? TaskStatus.READY : TaskStatus.REJECTED));
            WorkflowRun updated = new WorkflowRun(id, approved ? "APPROVED" : "REJECTED", old.requirement(), approved ? "EXECUTION" : "STOPPED", old.assumptions(), List.copyOf(tasks), approval, old.validation(), old.createdAt());
            putRun(updated);
            log(id, approved ? "APPROVAL_GRANTED" : "APPROVAL_REJECTED", Map.of("reviewerId", reviewerId.trim(), "reviewerNote", Objects.toString(reviewerNote, ""), "approvedScope", approval.get("scope")));
            log(id, "DECISION_LINEAGE", Map.of("reviewerId", reviewerId.trim(), "decision", approved ? "approve" : "reject", "requirement", old.requirement(), "taskId", actionTaskId));
            log(id, "RUN_STATUS_CHANGED", Map.of("status", updated.status()));
            return updated;
        }
    }

    /** Executes low-risk planning in parallel before approval, then only approved allowlisted checks. */
    public WorkflowRun executeReadyTasks(String id) {
        synchronized (lock(id)) {
            WorkflowRun current = getRun(id);
            if ("NEEDS_CLARIFICATION".equals(current.status())) throw new IllegalStateException("Workflow must be clarified before planning or execution.");
            if ("PLANNING".equals(current.status())) {
                try {
                    runParallelPlanning(id);
                    current = getRun(id);
                    log(id, "PLANNING_JOIN_COMPLETED", Map.of("joinedTasks", current.tasks().stream().filter(t -> List.of("API_DESIGN", "CODEBASE_IMPACT", "ACCEPTANCE_PLAN", "REGRESSION_PLAN", "REGRESSION_BASELINE").contains(t.action())).map(TaskItem::id).toList(), "joinCondition", "all scenario-specific planning tasks completed with artifacts"));
                    Map<String,Object> approval = new LinkedHashMap<>(current.approval()); approval.put("planningCompleted", true);
                    current = new WorkflowRun(id, "PENDING_APPROVAL", current.requirement(), "APPROVAL", current.assumptions(), current.tasks(), approval, current.validation(), current.createdAt());
                    putRun(current);
                    log(id, "APPROVAL_REQUIRED", Map.of("reason", "Planning artifacts are ready; execution still requires reviewer approval."));
                    return current;
                } catch (RuntimeException planningFailure) {
                    WorkflowRun latest = getRun(id);
                    List<TaskItem> failedTasks = new ArrayList<>(latest.tasks());
                    for (int i = 0; i < failedTasks.size(); i++) {
                        TaskItem task = failedTasks.get(i);
                        if (List.of("API_DESIGN", "CODEBASE_IMPACT", "ACCEPTANCE_PLAN", "REGRESSION_PLAN", "REGRESSION_BASELINE").contains(task.action()) && task.status() != TaskStatus.COMPLETED) {
                            failedTasks.set(i, withStatus(task, TaskStatus.FAILED));
                        }
                    }
                    skipDependents(id, failedTasks, "PLANNING");
                    WorkflowRun failed = new WorkflowRun(id, "FAILED", latest.requirement(), "STOPPED", latest.assumptions(), List.copyOf(failedTasks), latest.approval(), Map.of("status", "NOT_RUN", "checks", List.of(), "note", "Planning failed before approval: " + safeMessage(planningFailure)), latest.createdAt());
                    putRun(failed);
                    log(id, "PLANNING_FAILED", Map.of("reason", safeMessage(planningFailure)));
                    log(id, "WORKFLOW_STOPPED", Map.of("reason", "Planning failed; approval and execution remain blocked."));
                    return failed;
                }
            }
            if (!"APPROVED".equals(current.status()) && !"EXECUTING".equals(current.status())) throw new IllegalStateException("Workflow must be approved before execution.");
            if ("COMPLETED".equals(current.status()) || "BLOCKED_VALIDATION".equals(current.status()) || "FAILED".equals(current.status())) return current;
            return executeApproved(id);
        }
    }

    private void runParallelPlanning(String id) {
        WorkflowRun run = getRun(id);
        TaskItem design = findTask(run.tasks(), "API_DESIGN", "CODEBASE_IMPACT");
        TaskItem acceptance = findTask(run.tasks(), "ACCEPTANCE_PLAN", "REGRESSION_PLAN");
        TaskItem baseline = findTask(run.tasks(), "REGRESSION_BASELINE");
        if (design == null || acceptance == null) throw new IllegalStateException("Planning graph is incomplete.");
        log(id, "TASK_STARTED", Map.of("taskId", design.id(), "executionMode", "PARALLEL_ANALYSIS"));
        log(id, "TASK_STARTED", Map.of("taskId", acceptance.id(), "executionMode", "PARALLEL_ANALYSIS"));
        if (baseline != null) log(id, "TASK_STARTED", Map.of("taskId", baseline.id(), "executionMode", "PARALLEL_ANALYSIS"));
        String requirement = run.requirement();
        CompletableFuture<Map<String,Object>> designResult = CompletableFuture.supplyAsync(() -> buildDesign(requirement, design.action()));
        CompletableFuture<List<Map<String,Object>>> acceptanceResult = CompletableFuture.supplyAsync(() -> buildAcceptanceCriteria(requirement, acceptance.action()));
        CompletableFuture<Map<String,Object>> baselineResult = baseline == null ? null : CompletableFuture.supplyAsync(() -> buildRegressionBaseline());
        Map<String,Object> designArtifact = designResult.join();
        List<Map<String,Object>> criteria = acceptanceResult.join();
        Map<String,Object> baselineArtifact = baselineResult == null ? null : baselineResult.join();
        createArtifact(id, design.id(), "DESIGN", "Requirement-derived design / impact analysis", designArtifact);
        createArtifact(id, acceptance.id(), "ACCEPTANCE_CRITERIA", "Requirement-derived acceptance criteria", criteria);
        if (baseline != null) createArtifact(id, baseline.id(), "CODEBASE_BASELINE", "Existing codebase and regression baseline", baselineArtifact);
        List<TaskItem> tasks = new ArrayList<>(getRun(id).tasks());
        for (int i=0; i<tasks.size(); i++) {
            if (tasks.get(i).id().equals(design.id()) || tasks.get(i).id().equals(acceptance.id()) || (baseline != null && tasks.get(i).id().equals(baseline.id()))) {
                tasks.set(i, withStatus(tasks.get(i), TaskStatus.COMPLETED));
                log(id, "TASK_COMPLETED", Map.of("taskId", tasks.get(i).id(), "artifactBacked", true));
            }
        }
        WorkflowRun updated = new WorkflowRun(id, "PLANNING", requirement, "PLANNING", run.assumptions(), List.copyOf(tasks), run.approval(), run.validation(), run.createdAt());
        putRun(updated);
    }

    private Map<String,Object> buildDesign(String requirement, String action) {
        Map<String,Object> design = new LinkedHashMap<>();
        design.put("action", action);
        design.put("requirementSignals", signals(requirement));
        design.put("apiSurface", List.of("POST /api/urls", "GET /{shortCode}", "GET /api/urls/{shortCode}/analytics"));
        design.put("storage", "H2-backed URL mapping with atomic click-count increment");
        if ("CODEBASE_IMPACT".equals(action)) {
            design.put("impactedModules", List.of("UrlShortenerController", "UrlShortenerService", "UrlMappingRepository", "UrlExceptionHandler", "UrlShortenerIntegrationTest"));
            design.put("changeBoundary", "Analysis only; no source file changes are made by this prototype.");
        }
        return design;
    }

    private Map<String,Object> buildRegressionBaseline() {
        return Map.of("existingModules", List.of("UrlShortenerController", "UrlShortenerService", "UrlMappingRepository", "UrlExceptionHandler"),
                "existingTests", List.of("UrlShortenerIntegrationTest", "UrlShortenerServiceTest"),
                "baselineChecks", List.of("valid URL create", "known-code redirect", "click count increments", "invalid destination rejected", "unknown code returns not-found contract"),
                "mode", "repository-informed inventory; no source files modified");
    }

    private List<Map<String,Object>> buildAcceptanceCriteria(String requirement, String action) {
        String text = effectiveScope(requirement).toLowerCase();
        List<Map<String,Object>> criteria = new ArrayList<>();
        criteria.add(criterion("URL_CREATE", "A valid HTTP/HTTPS URL produces a short code", true, "URL_SHORTENER_SERVICE"));
        criteria.add(criterion("URL_RESOLVE", "Resolving a known short code returns the original URL", true, "URL_SHORTENER_SERVICE"));
        criteria.add(criterion("CLICK_ANALYTICS", "Resolving a short code increments click analytics", true, "URL_SHORTENER_SERVICE"));
        criteria.add(criterion("INVALID_DESTINATION", "Invalid destination URLs are rejected", true, "URL_SHORTENER_SERVICE"));
        if ("REGRESSION_PLAN".equals(action) || text.contains("invalid code") || text.contains("404") || text.contains("regression")) {
            criteria.add(criterion("UNKNOWN_CODE_404", "Unknown short codes return the not-found contract", true, "URL_SHORTENER_SERVICE_EXCEPTION_CONTRACT"));
            criteria.add(criterion("REGRESSION_BEHAVIOR", "Existing create/resolve/analytics behavior remains intact", true, "URL_SHORTENER_SERVICE"));
        }
        if (requiresNonFunctionalValidation(text)) {
            criteria.add(criterion("NON_FUNCTIONAL_REQUIREMENTS", "Performance/security target needs measurable threshold, threat model, and dedicated test tooling", false, "UNSUPPORTED_BY_BOUNDED_EXECUTOR"));
        }
        return criteria;
    }

    private Map<String,Object> criterion(String id, String description, boolean supported, String evidenceType) {
        return Map.of("id", id, "description", description, "supportedByExecutor", supported, "evidenceType", evidenceType);
    }

    private WorkflowRun executeApproved(String id) {
        WorkflowRun current = getRun(id);
        List<TaskItem> tasks = new ArrayList<>(current.tasks());
        TaskItem action = findTask(tasks, "RUN_VALIDATION");
        if (action == null || action.status() != TaskStatus.READY) throw new IllegalStateException("Approved validation task is not ready.");
        if (!"approve".equals(current.approval().get("decision"))) throw new IllegalStateException("Policy guardrail denied execution: explicit approval is missing.");
        updateTaskAndRun(id, tasks, action.id(), TaskStatus.RUNNING, "EXECUTING", "EXECUTION");
        log(id, "TASK_STARTED", Map.of("taskId", action.id(), "autonomyBoundary", "ALLOWLISTED_SERVICE_CALLS_ONLY", "reviewerId", current.approval().get("reviewerId")));
        try {
            Map<String,Object> report = runRequirementChecks(id, current.requirement(), action.action());
            createArtifact(id, action.id(), "VALIDATION_REPORT", "Requirement-linked validation report", report);
            boolean allPassed = "PASSED".equals(report.get("status"));
            List<TaskItem> latestTasks = new ArrayList<>(getRun(id).tasks());
            replaceTaskStatus(latestTasks, action.id(), allPassed ? TaskStatus.COMPLETED : ("BLOCKED".equals(report.get("status")) ? TaskStatus.BLOCKED : TaskStatus.FAILED));
            if (!allPassed) {
                skipDependents(id, latestTasks, action.id());
                String status = "BLOCKED".equals(report.get("status")) ? "BLOCKED_VALIDATION" : "FAILED";
                Map<String,Object> validation = Map.of("status", report.get("status"), "checks", report.get("checks"), "note", report.get("note"), "evidenceArtifactId", latestArtifactId(id, "VALIDATION_REPORT"));
                WorkflowRun ended = new WorkflowRun(id, status, current.requirement(), status.equals("FAILED") ? "STOPPED" : "REVIEW_REQUIRED", current.assumptions(), List.copyOf(latestTasks), current.approval(), validation, current.createdAt());
                putRun(ended);
                log(id, "VALIDATION_NOT_PASSED", Map.of("status", report.get("status"), "reason", report.get("note")));
                return ended;
            }
            log(id, "TASK_COMPLETED", Map.of("taskId", action.id(), "result", "PASSED", "artifactType", "VALIDATION_REPORT"));
            TaskItem evidence = findTask(latestTasks, "VALIDATE_EVIDENCE");
            if (evidence == null) throw new IllegalStateException("Evidence validation task is missing.");
            Map<String,Object> summary = new LinkedHashMap<>();
            summary.put("status", "PASSED"); summary.put("requirement", current.requirement()); summary.put("acceptanceEvidence", report.get("checks"));
            summary.put("limitations", List.of("This is bounded in-process service validation, not generated-code implementation, security testing, load testing, or deployment."));
            createArtifact(id, evidence.id(), "ENGINEERING_SUMMARY", "Final engineering summary", summary);
            replaceTaskStatus(latestTasks, evidence.id(), TaskStatus.COMPLETED);
            Map<String,Object> validation = Map.of("status", "PASSED", "checks", report.get("checks"), "note", "Every supported requirement-linked check passed. Unsupported requirements are never silently treated as passed.", "evidenceArtifactId", latestArtifactId(id, "VALIDATION_REPORT"));
            WorkflowRun completed = new WorkflowRun(id, "COMPLETED", current.requirement(), "COMPLETED", current.assumptions(), List.copyOf(latestTasks), current.approval(), validation, current.createdAt());
            putRun(completed);
            log(id, "TASK_COMPLETED", Map.of("taskId", evidence.id(), "result", "PASSED", "artifactType", "ENGINEERING_SUMMARY"));
            log(id, "WORKFLOW_COMPLETED", Map.of("validation", "PASSED", "evidenceArtifactId", latestArtifactId(id, "VALIDATION_REPORT")));
            return completed;
        } catch (RuntimeException ex) {
            List<TaskItem> latest = new ArrayList<>(getRun(id).tasks());
            replaceTaskStatus(latest, action.id(), TaskStatus.FAILED); skipDependents(id, latest, action.id());
            WorkflowRun failed = new WorkflowRun(id, "FAILED", current.requirement(), "STOPPED", current.assumptions(), List.copyOf(latest), current.approval(), Map.of("status", "NOT_RUN", "checks", List.of(), "note", "Execution failed before evidence could be validated: " + safeMessage(ex)), current.createdAt());
            putRun(failed); log(id, "TASK_FAILED", Map.of("taskId", action.id(), "reason", safeMessage(ex))); log(id, "WORKFLOW_STOPPED", Map.of("reason", "Task failure; dependent tasks were skipped."));
            return failed;
        }
    }

    private Map<String,Object> runRequirementChecks(String runId, String requirement, String action) {
        List<Map<String,Object>> checks = new ArrayList<>();
        String effectiveRequirement = effectiveScope(requirement);
        String target = "https://example.com/agentic-check/" + runId;
        UrlMapping mapping = null;
        try {
            mapping = urlService.createShortUrl(target);
            String resolved = urlService.getOriginalUrl(mapping.getShortCode());
            UrlMapping analytics = readAnalyticsWithBoundedTransientRetry(runId, mapping.getShortCode());
            checks.add(check("URL_CREATE", mapping.getShortCode() != null && !mapping.getShortCode().isBlank(), "A short code was created by the real service"));
            checks.add(check("URL_RESOLVE", target.equals(resolved), "Resolved destination matches the requested URL"));
            checks.add(check("CLICK_ANALYTICS", analytics != null && analytics.getClickCount() >= 1, "Click count reflects the redirect lookup"));
            boolean invalidRejected = false;
            try { urlService.createShortUrl("ftp://invalid.example"); } catch (IllegalArgumentException expected) { invalidRejected = true; }
            checks.add(check("INVALID_DESTINATION", invalidRejected, "Non-HTTP(S) destinations are rejected"));
            boolean unknownCodeNotFound = false;
            try { urlService.getOriginalUrl("zzzzzzzz"); } catch (UrlNotFoundException expected) { unknownCodeNotFound = true; }
            ResponseStatus notFoundStatus = UrlNotFoundException.class.getAnnotation(ResponseStatus.class);
            boolean unknownCode404 = unknownCodeNotFound && notFoundStatus != null && notFoundStatus.value() == HttpStatus.NOT_FOUND;
            if (effectiveRequirement.toLowerCase().contains("404") || effectiveRequirement.toLowerCase().contains("invalid code") || isBrownfieldScope(effectiveRequirement)) {
                checks.add(check("UNKNOWN_CODE_404", unknownCode404, "Unknown code throws the not-found exception configured for HTTP 404"));
                checks.add(check("REGRESSION_BEHAVIOR", target.equals(resolved) && analytics.getClickCount() >= 1, "Create/resolve/analytics behavior remains intact"));
            }
            boolean nonFunctional = requiresNonFunctionalValidation(effectiveRequirement.toLowerCase());
            if (nonFunctional) checks.add(Map.of("id", "NON_FUNCTIONAL_REQUIREMENTS", "status", "NOT_RUN", "passed", false, "detail", "The requirement includes performance/security goals, but this executor has no benchmark target or security test suite."));
            boolean unsupported = checks.stream().anyMatch(c -> "NOT_RUN".equals(c.get("status")));
            boolean passed = !unsupported && checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("status", passed ? "PASSED" : unsupported ? "BLOCKED" : "FAILED");
            report.put("executionMode", "REAL_IN_PROCESS_SERVICE_TEST");
            report.put("scenario", action.contains("REGRESSION") ? "BROWNFIELD" : "REQUIREMENT_DERIVED");
            report.put("checks", checks); report.put("checkCount", checks.size());
            report.put("passedCount", checks.stream().filter(c -> Boolean.TRUE.equals(c.get("passed"))).count());
            report.put("notRunCount", checks.stream().filter(c -> "NOT_RUN".equals(c.get("status"))).count());
            report.put("sideEffects", List.of("Created one isolated H2 mapping", "Recorded one redirect lookup"));
            report.put("note", passed ? "All supported acceptance checks passed." : unsupported ? "At least one requirement is not verifiable by the configured executor; workflow is blocked from claiming success." : "One or more requirement-linked checks failed.");
            if (!passed && mapping != null) rollbackMapping(runId, mapping.getShortCode(), "Validation did not pass.");
            return report;
        } catch (RuntimeException failure) {
            if (mapping != null) rollbackMapping(runId, mapping.getShortCode(), safeMessage(failure));
            throw failure;
        }
    }

    private UrlMapping readAnalyticsWithBoundedTransientRetry(String runId, String shortCode) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try { return urlService.getAnalytics(shortCode); }
            catch (TransientDataAccessException ex) {
                if (attempt == maxAttempts) throw ex;
                log(runId, "TASK_RETRY", Map.of("taskId", "SERVICE_CHECKS", "operation", "READ_ANALYTICS", "attempt", attempt + 1, "maxAttempts", maxAttempts, "classification", "TRANSIENT_DATA_ACCESS"));
                try { Thread.sleep(25L * attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Retry interrupted", interrupted); }
            }
        }
        throw new IllegalStateException("Analytics read exhausted retry policy");
    }

    private void rollbackMapping(String runId, String code, String reason) {
        try { urlService.deleteShortUrl(code); log(runId, "ROLLBACK_COMPLETED", Map.of("taskId", "SERVICE_CHECKS", "shortCode", code, "reason", reason)); }
        catch (RuntimeException ex) { log(runId, "ROLLBACK_FAILED", Map.of("taskId", "SERVICE_CHECKS", "shortCode", code, "reason", safeMessage(ex))); }
    }

    public WorkflowRun failTask(String id, String taskId, String reason) {
        synchronized (lock(id)) {
            WorkflowRun old = getRun(id);
            if (!"APPROVED".equals(old.status()) && !"EXECUTING".equals(old.status())) throw new IllegalStateException("Only an approved workflow can fail during execution.");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A failure reason is required.");
            List<TaskItem> tasks = new ArrayList<>(old.tasks()); TaskItem task = findTaskById(tasks, taskId);
            if (task.status() != TaskStatus.READY && task.status() != TaskStatus.RUNNING) throw new IllegalStateException("Only READY or RUNNING tasks can be marked as failed.");
            replaceTaskStatus(tasks, taskId, TaskStatus.FAILED); skipDependents(id, tasks, taskId);
            WorkflowRun failed = new WorkflowRun(id, "FAILED", old.requirement(), "STOPPED", old.assumptions(), List.copyOf(tasks), old.approval(), Map.of("status", "NOT_RUN", "checks", List.of(), "note", "Workflow stopped after task failure; automated validation was not run."), old.createdAt());
            putRun(failed); log(id, "TASK_FAILED", Map.of("taskId", taskId, "reason", reason)); log(id, "WORKFLOW_STOPPED", Map.of("reason", "Task failed: " + taskId)); return failed;
        }
    }

    public Map<String,Object> getMetrics(String id) {
        WorkflowRun run = getRun(id); List<AuditEvent> runEvents = getEvents(id);
        long completed = run.tasks().stream().filter(t -> t.status() == TaskStatus.COMPLETED).count();
        long failedTasks = run.tasks().stream().filter(t -> t.status() == TaskStatus.FAILED).count();
        long attemptedTasks = completed + failedTasks;
        long retries = runEvents.stream().filter(e -> "TASK_RETRY".equals(e.type())).count();
        long rollbacks = runEvents.stream().filter(e -> "ROLLBACK_COMPLETED".equals(e.type())).count();
        long elapsed = Duration.between(run.createdAt(), Instant.now()).toMillis();
        boolean terminal = List.of("COMPLETED", "BLOCKED_VALIDATION", "FAILED", "REJECTED").contains(run.status());
        Long endToEndLatency = terminal ? elapsed : null;
        Long failureToStop = null; Instant failedAt = null;
        for (AuditEvent event : runEvents) {
            if ("TASK_FAILED".equals(event.type())) failedAt = event.timestamp();
            if (failedAt != null && "WORKFLOW_STOPPED".equals(event.type())) { failureToStop = Duration.between(failedAt, event.timestamp()).toMillis(); break; }
        }
        Map<String,Object> metrics = new LinkedHashMap<>();
        metrics.put("taskCount", run.tasks().size()); metrics.put("completedTasks", completed);
        metrics.put("failedTasks", failedTasks); metrics.put("attemptedTasks", attemptedTasks);
        metrics.put("taskSuccessRatePercent", attemptedTasks == 0 ? 0.0 : Math.round(completed * 10000.0 / attemptedTasks) / 100.0);
        metrics.put("successRateDenominator", "completedTasks + failedTasks; blocked/skipped tasks are not attempted");
        metrics.put("elapsedSinceCreationMs", elapsed); metrics.put("endToEndLatencyMs", endToEndLatency);
        metrics.put("retryCount", retries); metrics.put("rollbackCount", rollbacks);
        metrics.put("failureToStopDurationMs", failureToStop);
        metrics.put("mttrMs", null); metrics.put("mttrNote", "Not measured: this prototype has no explicit recovery-completed event or service restoration measurement.");
        metrics.put("persistence", persistence == null ? "IN_MEMORY_TEST_MODE" : "H2_DURABLE_WORKFLOW_STORE");
        return metrics;
    }

    private boolean requiresNonFunctionalValidation(String text) {
        boolean mentionsPerformance = text.matches("(?s).*(faster|performance|latency).*" );
        boolean mentionsSecurity = text.matches("(?s).*(secure|security).*" );
        boolean deferBoth = text.contains("defer performance and security") || text.contains("performance and security are out of scope");
        boolean deferPerformance = deferBoth || text.contains("defer performance") || text.contains("performance out of scope");
        boolean deferSecurity = deferBoth || text.contains("defer security") || text.contains("security out of scope");
        return (mentionsPerformance && !deferPerformance) || (mentionsSecurity && !deferSecurity);
    }

    private boolean isBrownfieldScope(String text) {
        String lower = text.toLowerCase();
        return lower.contains("existing") || lower.contains("regression") || lower.contains("bug")
                || lower.contains("fix ") || lower.contains("refactor") || lower.contains("legacy");
    }
    private String effectiveScope(String requirement) {
        int marker = requirement.lastIndexOf("Clarification:");
        return marker >= 0 ? requirement.substring(marker + "Clarification:".length()).trim() : requirement;
    }
    private void recoverInterruptedRuns() {
        for (WorkflowRun run : new ArrayList<>(runs.values())) {
            boolean hasRunningTask = run.tasks().stream().anyMatch(task -> task.status() == TaskStatus.RUNNING);
            if (!"EXECUTING".equals(run.status()) && !hasRunningTask) continue;
            List<TaskItem> recoveredTasks = new ArrayList<>(run.tasks());
            for (int i = 0; i < recoveredTasks.size(); i++) {
                if (recoveredTasks.get(i).status() == TaskStatus.RUNNING) {
                    String taskId = recoveredTasks.get(i).id();
                    recoveredTasks.set(i, withStatus(recoveredTasks.get(i), TaskStatus.FAILED));
                    log(run.runId(), "TASK_FAILED", Map.of("taskId", taskId, "reason", "Process restarted while task was running; automatic resume is unsafe."));
                    skipDependents(run.runId(), recoveredTasks, taskId);
                }
            }
            WorkflowRun failed = new WorkflowRun(run.runId(), "FAILED", run.requirement(), "STOPPED", run.assumptions(),
                    List.copyOf(recoveredTasks), run.approval(), Map.of("status", "NOT_RUN", "checks", List.of(), "note", "Process restarted during execution; workflow stopped safely and requires a new run."), run.createdAt());
            putRun(failed);
            log(run.runId(), "WORKFLOW_RECOVERED_AS_STOPPED", Map.of("reason", "Interrupted execution is not automatically replayed."));
        }
    }

    private Map<String,Object> signals(String requirement) {
        String lower = requirement.toLowerCase(); Map<String,Object> signals = new LinkedHashMap<>();
        signals.put("mentionsInvalidCodes", lower.contains("invalid code") || lower.contains("404"));
        signals.put("mentionsRegression", lower.contains("regression") || lower.contains("existing") || lower.contains("bug") || lower.contains("fix"));
        signals.put("mentionsPerformance", lower.contains("faster") || lower.contains("performance") || lower.contains("latency"));
        signals.put("mentionsSecurity", lower.contains("secure") || lower.contains("security"));
        return signals;
    }
    private Map<String,Object> check(String id, boolean passed, String detail) { return Map.of("id", id, "passed", passed, "status", passed ? "PASSED" : "FAILED", "detail", detail); }
    private TaskItem task(String id, String action, String title, List<String> deps, TaskStatus status) { return new TaskItem(id, action, title, deps, status); }
    private TaskItem withStatus(TaskItem task, TaskStatus status) { return new TaskItem(task.id(), task.action(), task.title(), task.dependsOn(), status); }
    private TaskItem findTask(List<TaskItem> tasks, String... actions) { return tasks.stream().filter(t -> java.util.Arrays.asList(actions).contains(t.action())).findFirst().orElse(null); }
    private TaskItem findTaskById(List<TaskItem> tasks, String id) { return tasks.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("Task not found: " + id)); }
    private void replaceTaskStatus(List<TaskItem> tasks, String id, TaskStatus status) { for (int i=0;i<tasks.size();i++) if (tasks.get(i).id().equals(id)) { tasks.set(i, withStatus(tasks.get(i), status)); return; } throw new NoSuchElementException("Task not found: " + id); }
    private void updateTaskAndRun(String id, List<TaskItem> tasks, String taskId, TaskStatus status, String runStatus, String stage) { replaceTaskStatus(tasks, taskId, status); WorkflowRun old = getRun(id); putRun(new WorkflowRun(id, runStatus, old.requirement(), stage, old.assumptions(), List.copyOf(tasks), old.approval(), old.validation(), old.createdAt())); }
    private void skipDependents(String id, List<TaskItem> tasks, String failedId) {
        boolean changed;
        do { changed = false; for (int i=0;i<tasks.size();i++) { TaskItem task = tasks.get(i); if (List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.SKIPPED, TaskStatus.REJECTED).contains(task.status())) continue;
            boolean dependencyFailed = task.dependsOn().stream().anyMatch(dep -> tasks.stream().anyMatch(candidate -> candidate.id().equals(dep) && List.of(TaskStatus.FAILED, TaskStatus.SKIPPED).contains(candidate.status())));
            if (dependencyFailed) { tasks.set(i, withStatus(task, TaskStatus.SKIPPED)); log(id, "TASK_SKIPPED", Map.of("taskId", task.id(), "reason", "A prerequisite failed.")); changed = true; }
        }} while (changed);
    }
    private String latestArtifactId(String id, String type) { return artifacts.get(id).stream().filter(a -> type.equals(a.type())).reduce((a,b)->b).map(WorkflowArtifact::artifactId).orElse(""); }
    private Object lock(String id) { getRun(id); return locks.computeIfAbsent(id, ignored -> new Object()); }
    private void putRun(WorkflowRun run) { runs.put(run.runId(), run); persist(run); }
    private void persist(WorkflowRun run) { if (persistence != null) persistence.saveRun(run); }
    private void createArtifact(String runId, String taskId, String type, String title, Object content) {
        WorkflowArtifact artifact = new WorkflowArtifact(UUID.randomUUID().toString(), runId, taskId, type, title, content, Instant.now());
        artifacts.get(runId).add(artifact); if (persistence != null) persistence.saveArtifact(artifact);
        log(runId, "ARTIFACT_CREATED", Map.of("artifactId", artifact.artifactId(), "taskId", taskId, "type", type, "title", title));
    }
    private void log(String runId, String type, Map<String,Object> details) {
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString(), runId, type, Map.copyOf(details), Instant.now());
        List<AuditEvent> list = events.get(runId); if (list == null) throw new NoSuchElementException("Events not found for run: " + runId);
        list.add(event); if (persistence != null) persistence.saveEvent(event);
    }
    private String safeMessage(RuntimeException ex) { return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage(); }
}
