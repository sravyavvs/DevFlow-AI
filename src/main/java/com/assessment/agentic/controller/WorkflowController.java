package com.assessment.agentic.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.assessment.agentic.model.ApprovalRequest;
import com.assessment.agentic.model.CreateRunRequest;
import com.assessment.agentic.model.ClarificationRequest;
import com.assessment.agentic.model.FailTaskRequest;
import com.assessment.agentic.model.WorkflowRun;
import com.assessment.agentic.model.WorkflowArtifact;
import com.assessment.agentic.service.WorkflowService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowRun createRun(@Valid @RequestBody CreateRunRequest request) {
        return workflowService.createRun(request.requirement());
    }

    @GetMapping("/runs/{runId}")
    public WorkflowRun getRun(@PathVariable String runId) {
        return workflowService.getRun(runId);
    }

    @GetMapping("/runs/{runId}/events")
    public List<?> getEvents(@PathVariable String runId) {
        return workflowService.getEvents(runId);
    }

    @GetMapping("/runs/{runId}/artifacts")
    public List<WorkflowArtifact> getArtifacts(@PathVariable String runId) {
        return workflowService.getArtifacts(runId);
    }

    @PostMapping("/runs/{runId}/approval")
    public WorkflowRun decide(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request) {
        return workflowService.decide(runId, request.decision().toLowerCase(), request.reviewerId(),
                request.reviewerNote());
    }

    @PostMapping("/runs/{runId}/clarification")
    public WorkflowRun clarify(@PathVariable String runId, @Valid @RequestBody ClarificationRequest request) {
        return workflowService.clarify(runId, request.clarification());
    }

    @PostMapping("/runs/{runId}/execute")
    public WorkflowRun executeRun(@PathVariable String runId) {
        return workflowService.executeReadyTasks(runId);
    }

    @GetMapping("/runs/{runId}/metrics")
    public Map<String, Object> getMetrics(@PathVariable String runId) {
        return workflowService.getMetrics(runId);
    }

    @PostMapping("/runs/{runId}/tasks/{taskId}/fail")
    public WorkflowRun failTask(
            @PathVariable String runId,
            @PathVariable String taskId,
            @Valid @RequestBody FailTaskRequest request) {

        return workflowService.failTask(runId, taskId, request.reason());
    }

}
