package com.assessment.agentic.orchestration.policy;

public record PolicyDecision(String ruleId, String category, boolean allowed, String reason) {

    public static PolicyDecision allow(PolicyRule rule, String reason) {
        return new PolicyDecision(rule.id(), rule.category(), true, reason);
    }

    public static PolicyDecision deny(PolicyRule rule, String reason) {
        return new PolicyDecision(rule.id(), rule.category(), false, reason);
    }
}
