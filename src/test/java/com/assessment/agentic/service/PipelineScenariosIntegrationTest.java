package com.assessment.agentic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.assessment.agentic.orchestration.FaultMode;
import com.assessment.agentic.orchestration.PipelineStatus;
import com.assessment.agentic.orchestration.PipelineView;
import com.assessment.agentic.orchestration.StageStatus;

/** The three assessment scenarios plus failure/recovery, end-to-end through the real Spring context. */
@SpringBootTest(properties = "devflow.artifact-dir=target/devflow-test-artifacts")
class PipelineScenariosIntegrationTest {

    @Autowired
    private PipelineService service;

    private static PipelineView.StageView stage(PipelineView view, String id) {
        return view.stages().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static List<String> stageIds(PipelineView view) {
        return view.stages().stream().map(PipelineView.StageView::id).toList();
    }

    private long events(String id, String type) {
        return service.events(id).stream().filter(e -> type.equals(e.type())).count();
    }

    @Test
    void greenfieldRunsFullLifecycleWithTwoApprovalGates() throws Exception {
        PipelineView view = service.create("Build a URL shortener API that creates short links, redirects users, and tracks click analytics.");
        String id = view.pipelineId();
        assertEquals(PipelineStatus.AWAITING_APPROVAL, view.status());
        assertEquals(StageStatus.AWAITING_APPROVAL, stage(view, "IMPLEMENTATION").status());
        assertEquals(List.of("REQUIREMENTS", "ARCHITECTURE", "TEST_PLAN", "IMPLEMENTATION", "TESTING", "DOCUMENTATION", "RELEASE_READINESS"), stageIds(view));
        assertTrue(events(id, "GRAPH_REPLANNED") >= 1, "graph is expanded from the requirements artifact");
        assertTrue(events(id, "PARALLEL_JOIN") >= 1, "architecture and test planning run in parallel");

        view = service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "design and test plan reviewed");
        assertEquals(StageStatus.AWAITING_APPROVAL, stage(view, "RELEASE_READINESS").status());
        assertEquals("PASSED", stage(view, "TESTING").verdict());

        view = service.decide(id, "RELEASE_READINESS", "approve", "release-manager", "go");
        assertEquals(PipelineStatus.COMPLETED, view.status());
        String notes = service.artifacts(id).stream().filter(a -> a.stageId().equals("DOCUMENTATION")).findFirst()
                .orElseThrow().content().get("writtenFile").toString();
        assertTrue(Files.exists(Path.of(notes)));
        assertNotNull(service.metrics(id).get("endToEndLatencyMs"));
        assertTrue(service.mermaid(id).contains("TESTING --> RELEASE_READINESS"));
    }

    @Test
    void brownfieldProducesImpactAnalysisAndRegressionEvidence() {
        PipelineView view = service.create("Fix the existing URL shortener so unknown short codes return 404 and add regression tests.");
        String id = view.pipelineId();
        Map<String, Object> design = service.artifacts(id).stream().filter(a -> a.stageId().equals("ARCHITECTURE")).findFirst().orElseThrow().content();
        assertTrue(design.containsKey("impactAnalysis"));
        view = service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "ok");
        Map<String, Object> testing = service.artifacts(id).stream().filter(a -> a.stageId().equals("TESTING")).findFirst().orElseThrow().content();
        assertTrue(testing.get("checks").toString().contains("AC-404"));
        assertTrue(testing.get("checks").toString().contains("AC-REGRESSION"));
        assertEquals("PASSED", stage(view, "TESTING").verdict());
    }

    @Test
    void ambiguousRequirementIsClarifiedAndReplanned() {
        PipelineView view = service.create("Make the URL shortener faster and more secure for users.");
        String id = view.pipelineId();
        assertEquals(PipelineStatus.NEEDS_CLARIFICATION, view.status());
        assertEquals(List.of("REQUIREMENTS", "CLARIFICATION"), stageIds(view));
        assertThrows(IllegalStateException.class, () -> service.advance(id));

        view = service.clarify(id, "Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements.", "product-owner");
        assertFalse(stageIds(view).contains("CLARIFICATION"));
        assertTrue(stageIds(view).contains("RELEASE_READINESS"));
        assertEquals(PipelineStatus.AWAITING_APPROVAL, view.status());
        assertEquals(2, service.artifacts(id).stream().filter(a -> a.stageId().equals("REQUIREMENTS")).count(), "requirement lineage keeps both versions");
    }

    @Test
    void unmeasurableQualityGoalBlocksReleaseInsteadOfFalsePass() {
        PipelineView view = service.create("Build a secure URL shortener that creates short links and redirects users.");
        String id = view.pipelineId();
        assertTrue(stageIds(view).contains("NFR_VERIFICATION"));
        view = service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "ok");
        assertEquals("BLOCKED", stage(view, "NFR_VERIFICATION").verdict());
        assertEquals(StageStatus.BLOCKED, stage(view, "RELEASE_READINESS").status());
        assertEquals(PipelineStatus.BLOCKED, view.status());
        assertTrue(events(id, "POLICY_DENIED") >= 1);
    }

    @Test
    void measurableQualityGoalsAreVerifiedWithEvidence() {
        PipelineView view = service.create("Make redirects faster: p95 under 200 ms, and reject javascript and data URLs.");
        String id = view.pipelineId();
        view = service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "ok");
        assertEquals("PASSED", stage(view, "NFR_VERIFICATION").verdict());
    }

    @Test
    void releaseFailureRollsBackDocsThenResumeRecoversWithMttr() throws Exception {
        PipelineView view = service.create("Build a URL shortener API that creates short links, redirects users, and tracks click analytics.");
        String id = view.pipelineId();
        service.injectFault(id, "TESTING", FaultMode.TRANSIENT_ONCE);
        service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "ok");
        assertEquals(2, stage(service.get(id), "TESTING").attempts(), "transient fault retried once");

        service.injectFault(id, "RELEASE_READINESS", FaultMode.PERMANENT);
        view = service.decide(id, "RELEASE_READINESS", "approve", "release-manager", "go");
        assertEquals(PipelineStatus.FAILED, view.status());
        assertEquals(StageStatus.ROLLED_BACK, stage(view, "DOCUMENTATION").status());
        String notes = service.artifacts(id).stream().filter(a -> a.stageId().equals("DOCUMENTATION")).findFirst()
                .orElseThrow().content().get("writtenFile").toString();
        assertFalse(Files.exists(Path.of(notes)), "compensation deleted the generated file");

        view = service.resume(id, "on-call-engineer", "fault cleared");
        assertEquals(PipelineStatus.COMPLETED, view.status());
        Map<String, Object> metrics = service.metrics(id);
        assertNotNull(metrics.get("mttrMs"));
        assertEquals(1L, metrics.get("retryCount"));
        assertEquals(1L, metrics.get("rollbackCount"));
    }

    @Test
    void revisedArchitectureInvalidatesApprovalAndDownstream() {
        PipelineView view = service.create("Build a URL shortener API that creates short links, redirects users, and tracks click analytics.");
        String id = view.pipelineId();
        service.decide(id, "IMPLEMENTATION", "approve", "reviewer-1", "ok");
        view = service.revise(id, "ARCHITECTURE", Map.of("apiContract", List.of(), "note", "move analytics to async pipeline"), "architect-1", "design change");
        assertEquals(StageStatus.AWAITING_APPROVAL, stage(view, "IMPLEMENTATION").status());
        assertEquals(Integer.valueOf(2), stage(view, "ARCHITECTURE").artifactVersion());
        assertTrue(events(id, "APPROVAL_INVALIDATED") >= 1);
        assertThrows(IllegalArgumentException.class,
                () -> service.revise(id, "ARCHITECTURE", Map.of("secret", "password=hunter2hunter2"), "architect-1", "leak"));
    }
}
