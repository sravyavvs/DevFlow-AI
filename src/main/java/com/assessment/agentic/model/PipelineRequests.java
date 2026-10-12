package com.assessment.agentic.model;

import java.util.Map;

import com.assessment.agentic.orchestration.FaultMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies for the pipeline API. */
public final class PipelineRequests {

    private PipelineRequests() {
    }

    public record Clarify(@NotBlank @Size(min = 5, max = 2000) String clarification, @Size(max = 120) String requesterId) {}

    public record Revise(@NotBlank @Size(max = 120) String reviewerId, @NotBlank @Size(max = 1000) String reason,
                         @NotEmpty Map<String, Object> content) {}

    public record OperatorAction(@NotBlank @Size(max = 120) String operatorId, @Size(max = 1000) String reason) {}

    public record InjectFault(@NotBlank String stageId, @NotNull FaultMode mode) {}
}
