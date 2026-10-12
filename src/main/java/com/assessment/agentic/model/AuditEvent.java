package com.assessment.agentic.model;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String eventId,
        String runId,
        String type,
        Map<String, Object> details,
        Instant timestamp
) {}
