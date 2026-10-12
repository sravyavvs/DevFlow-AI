package com.assessment.agentic.orchestration;

/** Fault-injection modes used to demonstrate retry, rollback, safe-stop and recovery. */
public enum FaultMode {
    /** Fails the first attempt with a retryable error; the retry policy should recover. */
    TRANSIENT_ONCE,
    /** Fails every attempt with a retryable error until cleared by resume; exhausts the retry budget. */
    TRANSIENT_ALWAYS,
    /** Fails once with a non-retryable error; triggers rollback and safe stop. */
    PERMANENT
}
