package com.assessment.agentic.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import com.assessment.agentic.model.TaskStatus;
import com.assessment.agentic.model.WorkflowRun;
import com.assessment.agentic.url.UrlMapping;
import com.assessment.agentic.url.UrlNotFoundException;
import com.assessment.agentic.url.UrlShortenerService;

class WorkflowServiceTest {
    private UrlShortenerService urlService;
    private WorkflowService service;
    private AtomicReference<String> target;

    @BeforeEach
    void setUp() {
        urlService = mock(UrlShortenerService.class);
        target = new AtomicReference<>();
        when(urlService.createShortUrl(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("A valid HTTP or HTTPS URL is required");
            target.set(url);
            return mapping(url, 0L);
        });
        when(urlService.getOriginalUrl("a1b2c3d4")).thenAnswer(invocation -> { return target.get(); });
        when(urlService.getOriginalUrl("zzzzzzzz")).thenThrow(new UrlNotFoundException("not found"));
        when(urlService.getAnalytics("a1b2c3d4")).thenAnswer(invocation -> mapping(target.get(), 1L));
        service = new WorkflowService(urlService);
    }

    private UrlMapping mapping(String url, long clicks) {
        UrlMapping mapping = mock(UrlMapping.class);
        when(mapping.getShortCode()).thenReturn("a1b2c3d4");
        when(mapping.getClickCount()).thenReturn(clicks);
        when(mapping.getOriginalUrl()).thenReturn(url);
        return mapping;
    }

    private WorkflowRun plannedAndApproved(String requirement) {
        WorkflowRun run = service.createRun(requirement);
        run = service.executeReadyTasks(run.runId());
        assertEquals("PENDING_APPROVAL", run.status());
        return service.decide(run.runId(), "approve", "reviewer-123", "Approved bounded local checks");
    }

    @Test
    void createsRequirementDerivedGraphAndArtifactsOnlyWhenPlanningRuns() {
        WorkflowRun run = service.createRun("Build a URL shortener with click analytics.");
        assertEquals("PLANNING", run.status());
        assertEquals(TaskStatus.READY, run.tasks().stream().filter(t -> t.action().equals("API_DESIGN")).findFirst().orElseThrow().status());
        assertEquals(1, service.getArtifacts(run.runId()).size());
        run = service.executeReadyTasks(run.runId());
        assertEquals("PENDING_APPROVAL", run.status());
        assertEquals(3, service.getArtifacts(run.runId()).size());
        assertTrue(service.getEvents(run.runId()).stream().anyMatch(e -> e.type().equals("PLANNING_JOIN_COMPLETED")));
    }

    @Test
    void rejectionPreventsExecutionAndDoesNotCallUrlService() {
        WorkflowRun run = service.createRun("Build a URL shortener with click analytics.");
        service.executeReadyTasks(run.runId());
        service.decide(run.runId(), "reject", "reviewer-123", "Reject negative-path test");
        assertThrows(IllegalStateException.class, () -> service.executeReadyTasks(run.runId()));
        verifyNoInteractions(urlService);
        assertEquals("REJECTED", service.getRun(run.runId()).status());
    }

    @Test
    void approvalRunsRequirementLinkedChecksAndProducesEvidence() {
        WorkflowRun run = plannedAndApproved("Build a URL shortener with click analytics.");
        WorkflowRun result = service.executeReadyTasks(run.runId());
        assertEquals("COMPLETED", result.status());
        assertEquals("PASSED", result.validation().get("status"));
        assertEquals(5, service.getArtifacts(run.runId()).size());
        // One call creates a valid mapping; the second verifies rejection of an invalid destination.
        verify(urlService, org.mockito.Mockito.times(2)).createShortUrl(anyString());
        verify(urlService).getOriginalUrl("a1b2c3d4");
        verify(urlService).getAnalytics("a1b2c3d4");
    }

    @Test
    void ambiguousRequirementBlocksUntilClarificationAndReplans() {
        WorkflowRun run = service.createRun("Make the URL shortener faster and more secure.");
        assertEquals("NEEDS_CLARIFICATION", run.status());
        assertThrows(IllegalStateException.class, () -> service.decide(run.runId(), "approve", "reviewer-123", "approve"));
        WorkflowRun clarified = service.clarify(run.runId(), "Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements.");
        assertEquals("PLANNING", clarified.status());
        assertTrue(service.getEvents(run.runId()).stream().anyMatch(e -> e.type().equals("WORKFLOW_REPLANNED")));
    }

    @Test
    void brownfieldCriteriaInclude404AndRegressionEvidence() {
        WorkflowRun run = service.createRun("Fix the existing URL shortener: invalid codes return 404 and add regression tests.");
        run = service.executeReadyTasks(run.runId());
        var artifact = service.getArtifacts(run.runId()).stream().filter(a -> a.type().equals("ACCEPTANCE_CRITERIA")).findFirst().orElseThrow();
        assertTrue(artifact.content().toString().contains("UNKNOWN_CODE_404"));
        assertTrue(artifact.content().toString().contains("REGRESSION_BEHAVIOR"));
        assertTrue(run.tasks().stream().anyMatch(task -> task.action().equals("REGRESSION_BASELINE")));
    }

    @Test
    void unsupportedPerformanceOrSecurityCriteriaCannotPassSmokeValidation() {
        WorkflowRun run = plannedAndApproved("Improve the existing URL shortener to be faster and more secure.");
        WorkflowRun result = service.executeReadyTasks(run.runId());
        assertEquals("BLOCKED_VALIDATION", result.status());
        assertEquals("BLOCKED", result.validation().get("status"));
        assertEquals(TaskStatus.BLOCKED, result.tasks().stream().filter(task -> task.action().equals("RUN_VALIDATION")).findFirst().orElseThrow().status());
    }

    @Test
    void retriesOnlyClassifiedTransientFailuresAndRollsBackOnExhaustion() {
        WorkflowRun run = plannedAndApproved("Build a URL shortener with click analytics.");
        when(urlService.getAnalytics(anyString()))
                .thenThrow(new TransientDataAccessResourceException("temporary"))
                .thenThrow(new TransientDataAccessResourceException("temporary"))
                .thenThrow(new TransientDataAccessResourceException("temporary"));
        WorkflowRun failed = service.executeReadyTasks(run.runId());
        assertEquals("FAILED", failed.status());
        assertEquals("NOT_RUN", failed.validation().get("status"));
        verify(urlService, times(3)).getAnalytics("a1b2c3d4");
        verify(urlService).deleteShortUrl("a1b2c3d4");
        assertEquals(2L, service.getMetrics(run.runId()).get("retryCount"));
        assertEquals(1L, service.getMetrics(run.runId()).get("rollbackCount"));
    }

    @Test
    void metricsDoNotMislabelFailureToStopAsMttr() {
        var run = service.createRun("Build a URL shortener with click analytics.");
        var metrics = service.getMetrics(run.runId());
        assertEquals(0L, metrics.get("retryCount"));
        assertEquals(0L, metrics.get("rollbackCount"));
        assertTrue(metrics.containsKey("failureToStopDurationMs"));
        assertTrue(metrics.get("mttrNote").toString().contains("Not measured"));
    }
}
