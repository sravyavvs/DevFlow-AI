package com.assessment.agentic.orchestration.policy;

import java.nio.file.Path;

/** SECURITY: agents may only write files inside the configured artifact directory. */
public final class ArtifactLocationRule implements PolicyRule {

    private final Path root;

    public ArtifactLocationRule(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override public String id() { return "SEC-002-artifact-write-location"; }
    @Override public String category() { return "SECURITY"; }

    @Override
    public boolean appliesTo(PolicyCheck check) {
        return check.point() == GatePoint.EXIT && check.result() != null && check.result().output().get("writtenFile") != null;
    }

    @Override
    public PolicyDecision evaluate(PolicyCheck check) {
        Path written = Path.of(check.result().output().get("writtenFile").toString()).toAbsolutePath().normalize();
        return written.startsWith(root)
                ? PolicyDecision.allow(this, "Written file is inside " + root)
                : PolicyDecision.deny(this, "Stage wrote outside the artifact directory: " + written);
    }
}
