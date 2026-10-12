package com.assessment.agentic.orchestration;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reliability metrics derived purely from the audit log, so they are reproducible from evidence. */
public final class PipelineMetrics {

    private PipelineMetrics() {
    }

    public static Map<String, Object> of(Pipeline pipeline) {
        List<PipelineEvent> events = pipeline.events();
        long started = count(events, "STAGE_STARTED");
        long completed = count(events, "STAGE_COMPLETED");
        long failed = count(events, "STAGE_FAILED");
        long finished = completed + failed;

        Map<String, Instant> requested = new HashMap<>();
        long humanWaitMs = 0;
        for (PipelineEvent event : events) {
            if ("APPROVAL_REQUESTED".equals(event.type()) || "CLARIFICATION_REQUESTED".equals(event.type())) {
                requested.put(event.stageId(), event.timestamp());
            } else if (("APPROVAL_GRANTED".equals(event.type()) || "APPROVAL_REJECTED".equals(event.type())
                    || "CLARIFICATION_RECEIVED".equals(event.type())) && requested.containsKey(event.stageId())) {
                humanWaitMs += Duration.between(requested.remove(event.stageId()), event.timestamp()).toMillis();
            }
        }
        List<Long> recoveries = pipeline.incidents().stream().map(Incident::timeToRecoverMs).filter(Objects::nonNull).toList();
        Long endToEnd = pipeline.completedAt() == null ? null : Duration.between(pipeline.createdAt(), pipeline.completedAt()).toMillis();

        Map<String, Object> stageDurations = new LinkedHashMap<>();
        pipeline.stages().forEach(r -> stageDurations.put(r.id(), r.durationMs()));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("status", pipeline.status().name());
        metrics.put("stageExecutions", started);
        metrics.put("stageSuccesses", completed);
        metrics.put("stageFailures", failed);
        metrics.put("stageSuccessRatePercent", finished == 0 ? null : Math.round(completed * 10000.0 / finished) / 100.0);
        metrics.put("retryCount", count(events, "STAGE_RETRY"));
        metrics.put("rollbackCount", count(events, "ROLLBACK_COMPLETED"));
        metrics.put("rollbackFailureCount", count(events, "ROLLBACK_FAILED"));
        metrics.put("policyDenials", count(events, "POLICY_DENIED"));
        metrics.put("replanCount", count(events, "GRAPH_REPLANNED") + count(events, "STAGES_INVALIDATED"));
        metrics.put("approvalInvalidations", count(events, "APPROVAL_INVALIDATED"));
        metrics.put("incidents", pipeline.incidents().size());
        metrics.put("mttrMs", recoveries.isEmpty() ? null : Math.round(recoveries.stream().mapToLong(Long::longValue).average().orElse(0)));
        metrics.put("mttrDefinition", "Mean time from STAGE_FAILED to the pipeline completing again (RECOVERY_COMPLETED), across incidents.");
        metrics.put("endToEndLatencyMs", endToEnd);
        metrics.put("humanWaitMs", humanWaitMs);
        metrics.put("agentLatencyMs", endToEnd == null ? null : Math.max(0, endToEnd - humanWaitMs));
        metrics.put("stageDurationsMs", stageDurations);
        return metrics;
    }

    private static long count(List<PipelineEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.type())).count();
    }
}
