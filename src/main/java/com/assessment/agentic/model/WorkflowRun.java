package com.assessment.agentic.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowRun(
        String runId,
        String status,
        String requirement,
        String currentStage,
        List<String> assumptions,
        List<TaskItem> tasks,
        Map<String, Object> approval,
        Map<String, Object> validation,
        Instant createdAt
) {}
