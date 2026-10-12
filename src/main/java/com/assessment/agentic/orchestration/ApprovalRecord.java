package com.assessment.agentic.orchestration;

import java.time.Instant;
import java.util.Map;

/**
 * A human decision bound to the upstream artifact versions the reviewer saw.
 * If any of those versions change, the approval is invalidated automatically (change control).
 */
public record ApprovalRecord(
        String stageId,
        String decision,
        String reviewerId,
        String note,
        Map<String, Integer> boundInputVersions,
        Map<String, String> boundInputDigests,
        Instant decidedAt) {

    public boolean approved() {
        return "approve".equals(decision);
    }
}
