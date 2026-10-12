package com.assessment.agentic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FailTaskRequest(
        @NotBlank @Size(max = 1000) String reason
) {}
