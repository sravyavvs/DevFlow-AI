package com.assessment.agentic.orchestration.policy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Evaluates every applicable rule; one denial blocks the gate. */
public final class PolicyEngine {

    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<PolicyDecision> evaluate(PolicyCheck check) {
        return rules.stream().filter(rule -> rule.appliesTo(check)).map(rule -> rule.evaluate(check)).toList();
    }

    public List<PolicyRule> rules() {
        return rules;
    }

    /** Default guardrails for the SDLC pipeline. */
    public static PolicyEngine defaults(Path artifactRoot, Set<String> releaseStages, List<String> allowedChangePaths) {
        return new PolicyEngine(List.of(
                new SecretLeakRule(),
                new ChangeScopeRule(allowedChangePaths),
                new ArtifactLocationRule(artifactRoot),
                new VerifiedEvidenceRule(releaseStages)));
    }
}
