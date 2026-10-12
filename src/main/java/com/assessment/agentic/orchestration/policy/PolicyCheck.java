package com.assessment.agentic.orchestration.policy;

import java.util.Map;

import com.assessment.agentic.orchestration.ArtifactVersion;
import com.assessment.agentic.orchestration.StageDefinition;
import com.assessment.agentic.orchestration.StageResult;

/**
 * Input to a policy rule. {@code result} is null at ENTRY. {@code upstream} holds the current
 * artifacts of the stage's ancestors.
 */
public record PolicyCheck(GatePoint point, String pipelineId, StageDefinition stage,
                          Map<String, ArtifactVersion> upstream, StageResult result) {
}
