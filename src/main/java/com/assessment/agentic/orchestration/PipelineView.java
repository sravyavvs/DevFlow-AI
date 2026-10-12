package com.assessment.agentic.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable API representation of a pipeline; internal mutable state is never exposed. */
public record PipelineView(
        String pipelineId,
        PipelineStatus status,
        String requirement,
        List<String> clarifications,
        Instant createdAt,
        Instant completedAt,
        List<StageView> stages,
        List<String> nextHumanActions) {

    public record StageView(String id, String title, List<String> dependsOn, GateType gate, StageStatus status,
                            int attempts, Integer artifactVersion, String verdict, String summary,
                            ApprovalRecord approval, String lastError, Long durationMs) {
    }

    public static PipelineView of(Pipeline pipeline) {
        synchronized (pipeline) {
            List<StageView> stages = pipeline.stages().stream().map(r -> new StageView(
                    r.id(), r.definition().title(), r.definition().dependsOn(), r.definition().gate(), r.status(), r.attempts(),
                    r.artifact() == null ? null : r.artifact().version(),
                    r.artifact() == null ? null : r.artifact().verdict().name(),
                    r.artifact() == null ? null : r.artifact().summary(),
                    r.approval(), r.lastError(), r.durationMs())).toList();
            List<String> actions = new ArrayList<>();
            String base = "/api/pipelines/" + pipeline.id();
            for (StageRecord r : pipeline.stages()) {
                if (r.status() == StageStatus.AWAITING_APPROVAL) actions.add("POST " + base + "/stages/" + r.id() + "/approval");
                if (r.status() == StageStatus.AWAITING_INPUT) actions.add("POST " + base + "/clarification");
                if (r.status() == StageStatus.BLOCKED) actions.add("Resolve block on " + r.id() + ": " + r.lastError());
            }
            if (pipeline.status() == PipelineStatus.FAILED) actions.add("POST " + base + "/resume  or  POST " + base + "/stop");
            return new PipelineView(pipeline.id(), pipeline.status(), pipeline.requirement(), pipeline.clarifications(),
                    pipeline.createdAt(), pipeline.completedAt(), stages, actions);
        }
    }

    public static String mermaid(Pipeline pipeline) {
        synchronized (pipeline) {
            Map<String, StageStatus> statuses = new java.util.HashMap<>();
            pipeline.stages().forEach(r -> statuses.put(r.id(), r.status()));
            return pipeline.graph().toMermaid(id -> String.valueOf(statuses.get(id)));
        }
    }
}
