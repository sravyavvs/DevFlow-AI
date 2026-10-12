package com.assessment.agentic.model;

import java.time.Instant;

/** A concrete, traceable output owned by a workflow task. */
public record WorkflowArtifact(
        String artifactId,
        String runId,
        String taskId,
        String type,
        String title,
        Object content,
        Instant createdAt) {
}
