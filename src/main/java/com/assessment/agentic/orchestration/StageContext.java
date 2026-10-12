package com.assessment.agentic.orchestration;

import java.util.Map;
import java.util.Optional;

/**
 * Everything an agent may see when executing a stage: the requirement and the versioned outputs of
 * all upstream (ancestor) stages. Agents cannot read or mutate pipeline state directly.
 */
public record StageContext(String pipelineId, String requirement, StageDefinition stage, int attempt,
                           Map<String, ArtifactVersion> inputs) {

    public StageContext {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    public StageContext withAttempt(int newAttempt) {
        return new StageContext(pipelineId, requirement, stage, newAttempt, inputs);
    }

    public Optional<Map<String, Object>> findInput(String stageId) {
        ArtifactVersion artifact = inputs.get(stageId);
        return artifact == null ? Optional.empty() : Optional.of(artifact.content());
    }

    public Map<String, Object> input(String stageId) {
        return findInput(stageId).orElseThrow(() ->
                new IllegalStateException("Stage " + stage.id() + " requires an artifact from " + stageId));
    }
}
