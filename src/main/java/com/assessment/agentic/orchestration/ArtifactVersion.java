package com.assessment.agentic.orchestration;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable, versioned output of a stage. {@code inputVersions} records exactly which upstream
 * artifact versions were consumed, which is the decision-lineage link between stages. {@code digest}
 * is a SHA-256 over canonicalised content; approvals bind to digests so identical regenerated
 * evidence keeps an approval while any material change invalidates it.
 */
public record ArtifactVersion(
        String artifactId,
        String stageId,
        int version,
        StageResult.Verdict verdict,
        String summary,
        Map<String, Object> content,
        Map<String, Integer> inputVersions,
        String producedBy,
        String reason,
        Instant createdAt,
        String digest) {
}
