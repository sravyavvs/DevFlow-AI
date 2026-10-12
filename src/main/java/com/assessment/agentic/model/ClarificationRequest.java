package com.assessment.agentic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClarificationRequest(@NotBlank @Size(min = 5, max = 2000) String clarification) {}
