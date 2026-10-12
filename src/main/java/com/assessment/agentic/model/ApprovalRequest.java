package com.assessment.agentic.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ApprovalRequest(
        @NotBlank @Pattern(regexp = "(?i)approve|reject") String decision,
        @NotBlank @Size(max = 120) String reviewerId,
        @Size(max = 1000) String reviewerNote
) {}
