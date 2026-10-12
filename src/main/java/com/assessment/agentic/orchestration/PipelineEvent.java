package com.assessment.agentic.orchestration;

import java.time.Instant;
import java.util.Map;

/** Append-only audit event. {@code seq} gives a total order even when timestamps collide. */
public record PipelineEvent(
        long seq,
        String pipelineId,
        String type,
        String stageId,
        String actor,
        Map<String, Object> details,
        Instant timestamp) {
}
