package com.assessment.agentic.orchestration.policy;

/** A guardrail evaluated at a stage gate. Rules are declarative and independent of agent logic. */
public interface PolicyRule {

    String id();

    /** SECURITY, COMPLIANCE or CHANGE_CONTROL. */
    String category();

    boolean appliesTo(PolicyCheck check);

    PolicyDecision evaluate(PolicyCheck check);
}
