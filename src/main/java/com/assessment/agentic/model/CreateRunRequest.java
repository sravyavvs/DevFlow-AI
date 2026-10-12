package com.assessment.agentic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRunRequest(
        @NotBlank @Size(min = 10, max = 5000) String requirement
) {}
