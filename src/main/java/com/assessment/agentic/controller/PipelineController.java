package com.assessment.agentic.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.assessment.agentic.model.ApprovalRequest;
import com.assessment.agentic.model.CreatePipelineRequest;
import com.assessment.agentic.model.PipelineRequests;
import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.PipelineEvent;
import com.assessment.agentic.orchestration.PipelineView;
import com.assessment.agentic.service.PipelineService;

import jakarta.validation.Valid;

/** Full-lifecycle SDLC orchestration API (generic DAG engine). */
@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineService service;

    public PipelineController(PipelineService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PipelineView create(@Valid @RequestBody CreatePipelineRequest request) {
        return service.create(request.requirement());
    }

    @GetMapping
    public List<PipelineView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PipelineView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/advance")
    public PipelineView advance(@PathVariable String id) {
        return service.advance(id);
    }

    @PostMapping("/{id}/stages/{stageId}/approval")
    public PipelineView decide(@PathVariable String id, @PathVariable String stageId, @Valid @RequestBody ApprovalRequest request) {
        return service.decide(id, stageId, request.decision(), request.reviewerId(), request.reviewerNote());
    }

    @PostMapping("/{id}/clarification")
    public PipelineView clarify(@PathVariable String id, @Valid @RequestBody PipelineRequests.Clarify request) {
        return service.clarify(id, request.clarification(), request.requesterId());
    }

    @PutMapping("/{id}/stages/{stageId}/artifact")
    public PipelineView revise(@PathVariable String id, @PathVariable String stageId, @Valid @RequestBody PipelineRequests.Revise request) {
        return service.revise(id, stageId, request.content(), request.reviewerId(), request.reason());
    }

    @PostMapping("/{id}/resume")
    public PipelineView resume(@PathVariable String id, @Valid @RequestBody PipelineRequests.OperatorAction request) {
        return service.resume(id, request.operatorId(), request.reason());
    }

    @PostMapping("/{id}/stop")
    public PipelineView stop(@PathVariable String id, @Valid @RequestBody PipelineRequests.OperatorAction request) {
        return service.stop(id, request.operatorId(), request.reason());
    }

    @PostMapping("/{id}/faults")
    public PipelineView injectFault(@PathVariable String id, @Valid @RequestBody PipelineRequests.InjectFault request) {
        return service.injectFault(id, request.stageId(), request.mode());
    }

    @GetMapping("/{id}/events")
    public List<PipelineEvent> events(@PathVariable String id) {
        return service.events(id);
    }

    @GetMapping("/{id}/artifacts")
    public List<ArtifactVersion> artifacts(@PathVariable String id) {
        return service.artifacts(id);
    }

    @GetMapping("/{id}/metrics")
    public Map<String, Object> metrics(@PathVariable String id) {
        return service.metrics(id);
    }

    @GetMapping("/{id}/graph")
    public Map<String, String> graph(@PathVariable String id) {
        return Map.of("format", "mermaid", "diagram", service.mermaid(id));
    }
}
