package com.assessment.agentic.orchestration.policy;

import java.util.List;
import java.util.Set;

import com.assessment.agentic.orchestration.StageResult;

/**
 * COMPLIANCE: a release stage may only start when every upstream artifact has a PASSED verdict.
 * A BLOCKED verdict (for example an unmeasurable performance goal) can never be released silently.
 */
public final class VerifiedEvidenceRule implements PolicyRule {

    private final Set<String> releaseStages;

    public VerifiedEvidenceRule(Set<String> releaseStages) {
        this.releaseStages = Set.copyOf(releaseStages);
    }

    @Override public String id() { return "CMP-001-release-requires-verified-evidence"; }
    @Override public String category() { return "COMPLIANCE"; }
    @Override public boolean appliesTo(PolicyCheck check) { return check.point() == GatePoint.ENTRY && releaseStages.contains(check.stage().id()); }

    @Override
    public PolicyDecision evaluate(PolicyCheck check) {
        List<String> notPassed = check.upstream().values().stream()
                .filter(artifact -> artifact.verdict() != StageResult.Verdict.PASSED)
                .map(artifact -> artifact.stageId() + " (" + artifact.verdict() + ": " + artifact.summary() + ")")
                .toList();
        return notPassed.isEmpty()
                ? PolicyDecision.allow(this, "All " + check.upstream().size() + " upstream artifacts have PASSED verdicts.")
                : PolicyDecision.deny(this, "Release blocked; unverified upstream evidence: " + notPassed);
    }
}
