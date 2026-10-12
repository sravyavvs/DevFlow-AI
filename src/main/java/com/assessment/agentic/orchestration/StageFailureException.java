package com.assessment.agentic.orchestration;

import java.util.Map;

/** Non-retryable stage failure carrying structured evidence for the audit trail. */
public class StageFailureException extends RuntimeException {
    private final transient Map<String, Object> details;

    public StageFailureException(String message) { this(message, Map.of()); }

    public StageFailureException(String message, Map<String, Object> details) {
        super(message);
        this.details = details == null ? Map.of() : details;
    }

    public Map<String, Object> details() { return details; }
}
