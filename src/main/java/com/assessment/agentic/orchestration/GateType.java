package com.assessment.agentic.orchestration;

/** Human checkpoint required before a stage may run. */
public enum GateType {
    NONE,
    /** High-impact stage: a named reviewer must approve, bound to the exact upstream artifact versions. */
    HUMAN_APPROVAL,
    /** The stage cannot be executed by an agent; a human must supply missing intent. */
    HUMAN_CLARIFICATION
}
