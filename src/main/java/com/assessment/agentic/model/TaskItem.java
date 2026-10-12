package com.assessment.agentic.model;

import java.util.List;

/** A DAG node with a stable semantic action, not a positional list identity. */
public record TaskItem(
        String id,
        String action,
        String title,
        List<String> dependsOn,
        TaskStatus status
) {}
