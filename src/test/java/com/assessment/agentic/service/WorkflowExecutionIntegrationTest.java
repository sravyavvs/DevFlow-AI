package com.assessment.agentic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.mockito.Mockito.mock;
import com.assessment.agentic.url.UrlShortenerService;

import com.assessment.agentic.model.WorkflowRun;

@SpringBootTest
class WorkflowExecutionIntegrationTest {
    @Autowired private WorkflowService workflowService;
    @Autowired private WorkflowPersistence workflowPersistence;

    @Test
    void approvalGatesRealChecksAndArtifactsAreProduced() {
        WorkflowRun created = workflowService.createRun("Build a URL shortener API with redirects and click analytics.");
        WorkflowRun planned = workflowService.executeReadyTasks(created.runId());
        assertEquals("PENDING_APPROVAL", planned.status());
        WorkflowRun rejected = workflowService.decide(created.runId(), "reject", "integration-reviewer", "negative-path test");
        assertEquals("REJECTED", rejected.status());
        assertThrows(IllegalStateException.class, () -> workflowService.executeReadyTasks(created.runId()));

        WorkflowRun second = workflowService.createRun("Build a URL shortener API with redirects and click analytics.");
        workflowService.executeReadyTasks(second.runId());
        WorkflowRun approved = workflowService.decide(second.runId(), "approve", "integration-reviewer", "approved bounded checks");
        WorkflowRun completed = workflowService.executeReadyTasks(approved.runId());
        assertEquals("COMPLETED", completed.status());
        assertEquals("PASSED", completed.validation().get("status"));
        assertEquals(5, workflowService.getArtifacts(second.runId()).size());
        assertTrue(workflowService.getEvents(second.runId()).stream().anyMatch(event -> "WORKFLOW_COMPLETED".equals(event.type())));
    }

    @Test
    void workflowSnapshotSurvivesServiceReconstruction() {
        WorkflowRun created = workflowService.createRun("Build a URL shortener with click analytics.");
        WorkflowService reconstructed = new WorkflowService(mock(UrlShortenerService.class), workflowPersistence);
        assertEquals(created.runId(), reconstructed.getRun(created.runId()).runId());
        assertTrue(reconstructed.getEvents(created.runId()).stream().anyMatch(event -> "RUN_CREATED".equals(event.type())));
        assertTrue(reconstructed.getArtifacts(created.runId()).stream().anyMatch(artifact -> "REQUIREMENT_BRIEF".equals(artifact.type())));
    }

}
