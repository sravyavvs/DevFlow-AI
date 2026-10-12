package com.assessment.agentic.orchestration;

/** Lifecycle of a single stage inside a pipeline run. */
public enum StageStatus {
    PENDING, RUNNING, AWAITING_APPROVAL, AWAITING_INPUT, COMPLETED, BLOCKED, FAILED, SKIPPED, REJECTED, ROLLED_BACK
}
