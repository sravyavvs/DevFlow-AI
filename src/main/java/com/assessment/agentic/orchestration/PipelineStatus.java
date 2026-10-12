package com.assessment.agentic.orchestration;

/** Aggregate state of a pipeline run, derived from its stage states. */
public enum PipelineStatus {
    RUNNING, AWAITING_APPROVAL, NEEDS_CLARIFICATION, BLOCKED, COMPLETED, FAILED, REJECTED, STOPPED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == STOPPED;
    }
}
