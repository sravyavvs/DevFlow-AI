package com.assessment.agentic.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.FaultMode;
import com.assessment.agentic.orchestration.Pipeline;
import com.assessment.agentic.orchestration.PipelineEvent;
import com.assessment.agentic.orchestration.PipelineExecutor;
import com.assessment.agentic.orchestration.PipelineMetrics;
import com.assessment.agentic.orchestration.PipelineView;
import com.assessment.agentic.orchestration.StageHandler;
import com.assessment.agentic.orchestration.policy.PolicyEngine;
import com.assessment.agentic.orchestration.sdlc.ArchitectureAgent;
import com.assessment.agentic.orchestration.sdlc.DocumentationAgent;
import com.assessment.agentic.orchestration.sdlc.ImplementationAgent;
import com.assessment.agentic.orchestration.sdlc.NfrVerificationAgent;
import com.assessment.agentic.orchestration.sdlc.ReleaseReadinessAgent;
import com.assessment.agentic.orchestration.sdlc.RequirementAnalyzer;
import com.assessment.agentic.orchestration.sdlc.RequirementsAgent;
import com.assessment.agentic.orchestration.sdlc.SdlcPlanner;
import com.assessment.agentic.orchestration.sdlc.SdlcStages;
import com.assessment.agentic.orchestration.sdlc.TestPlanAgent;
import com.assessment.agentic.orchestration.sdlc.TestingAgent;
import com.assessment.agentic.url.UrlShortenerService;

import jakarta.annotation.PreDestroy;

/**
 * Application service for SDLC pipelines. Every human action (approve, clarify, revise, resume)
 * is followed by an automatic advance, so agents keep working until the next gate.
 */
@Service
public class PipelineService {

    private final PipelineExecutor executor;
    private final Map<String, Pipeline> pipelines = new ConcurrentHashMap<>();

    public PipelineService(UrlShortenerService urlService,
                           @Value("${devflow.artifact-dir:build/devflow-artifacts}") String artifactDir) {
        Path root = Path.of(artifactDir).toAbsolutePath().normalize();
        Map<String, StageHandler> agents = Map.of(
                SdlcStages.REQUIREMENTS, new RequirementsAgent(new RequirementAnalyzer()),
                SdlcStages.ARCHITECTURE, new ArchitectureAgent(),
                SdlcStages.TEST_PLAN, new TestPlanAgent(),
                SdlcStages.IMPLEMENTATION, new ImplementationAgent(),
                SdlcStages.TESTING, new TestingAgent(urlService),
                SdlcStages.NFR_VERIFICATION, new NfrVerificationAgent(urlService),
                SdlcStages.DOCUMENTATION, new DocumentationAgent(root),
                SdlcStages.RELEASE_READINESS, new ReleaseReadinessAgent());
        PolicyEngine policies = PolicyEngine.defaults(root, Set.of(SdlcStages.RELEASE_READINESS), List.of(
                "src/main/java/com/assessment/agentic/url/", "src/test/java/com/assessment/agentic/url/", "docs/"));
        this.executor = new PipelineExecutor(agents, policies, new SdlcPlanner(), 4, Duration.ofSeconds(30), 50, 40);
    }

    public PipelineView create(String requirement) {
        Pipeline pipeline = executor.create(requirement);
        pipelines.put(pipeline.id(), pipeline);
        executor.advance(pipeline);
        return PipelineView.of(pipeline);
    }

    public List<PipelineView> list() {
        return pipelines.values().stream().sorted(Comparator.comparing(Pipeline::createdAt).reversed()).map(PipelineView::of).toList();
    }

    public PipelineView get(String id) {
        return PipelineView.of(find(id));
    }

    public PipelineView advance(String id) {
        Pipeline pipeline = find(id);
        executor.advance(pipeline);
        return PipelineView.of(pipeline);
    }

    public PipelineView decide(String id, String stageId, String decision, String reviewerId, String note) {
        Pipeline pipeline = find(id);
        executor.decide(pipeline, stageId, decision, reviewerId, note);
        return advanceIfActive(pipeline);
    }

    public PipelineView clarify(String id, String clarification, String requesterId) {
        Pipeline pipeline = find(id);
        executor.clarify(pipeline, clarification, requesterId == null || requesterId.isBlank() ? "requester" : requesterId);
        return advanceIfActive(pipeline);
    }

    public PipelineView revise(String id, String stageId, Map<String, Object> content, String reviewerId, String reason) {
        Pipeline pipeline = find(id);
        executor.revise(pipeline, stageId, content, reviewerId, reason);
        return advanceIfActive(pipeline);
    }

    public PipelineView resume(String id, String operatorId, String reason) {
        Pipeline pipeline = find(id);
        executor.resume(pipeline, operatorId, reason);
        return advanceIfActive(pipeline);
    }

    public PipelineView stop(String id, String operatorId, String reason) {
        Pipeline pipeline = find(id);
        executor.safeStop(pipeline, operatorId, reason);
        return PipelineView.of(pipeline);
    }

    public PipelineView injectFault(String id, String stageId, FaultMode mode) {
        Pipeline pipeline = find(id);
        executor.injectFault(pipeline, stageId, mode);
        return PipelineView.of(pipeline);
    }

    public List<PipelineEvent> events(String id) {
        return find(id).events();
    }

    public List<ArtifactVersion> artifacts(String id) {
        Pipeline pipeline = find(id);
        synchronized (pipeline) {
            return pipeline.artifactHistory();
        }
    }

    public Map<String, Object> metrics(String id) {
        Pipeline pipeline = find(id);
        synchronized (pipeline) {
            return PipelineMetrics.of(pipeline);
        }
    }

    public String mermaid(String id) {
        return PipelineView.mermaid(find(id));
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }

    private PipelineView advanceIfActive(Pipeline pipeline) {
        switch (pipeline.status()) {
            case RUNNING, BLOCKED, AWAITING_APPROVAL -> executor.advance(pipeline);
            default -> { }
        }
        return PipelineView.of(pipeline);
    }

    private Pipeline find(String id) {
        Pipeline pipeline = pipelines.get(id);
        if (pipeline == null) throw new NoSuchElementException("Pipeline not found: " + id);
        return pipeline;
    }
}
