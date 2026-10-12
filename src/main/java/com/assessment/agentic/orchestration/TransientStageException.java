package com.assessment.agentic.orchestration;

/** Signals a retryable failure. Only this exception type consumes the stage's retry budget. */
public class TransientStageException extends RuntimeException {
    public TransientStageException(String message) { super(message); }
    public TransientStageException(String message, Throwable cause) { super(message, cause); }
}
