package com.assessment.agentic.orchestration;

import java.time.Duration;
import java.time.Instant;

/** A failure window used to measure MTTR: from stage failure to the pipeline completing again. */
public record Incident(String stageId, String reason, Instant failedAt, Instant recoveredAt) {

    public Long timeToRecoverMs() {
        return recoveredAt == null ? null : Duration.between(failedAt, recoveredAt).toMillis();
    }

    Incident recovered(Instant at) {
        return new Incident(stageId, reason, failedAt, at);
    }
}
