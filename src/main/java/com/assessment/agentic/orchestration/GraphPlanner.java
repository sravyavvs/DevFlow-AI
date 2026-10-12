package com.assessment.agentic.orchestration;

import java.util.Map;

/**
 * Derives the desired dependency graph from the artifacts produced so far. The executor calls the
 * planner after every wave, so a changed upstream output (for example a clarified requirement)
 * re-shapes the downstream plan.
 */
public interface GraphPlanner {

    PipelineGraph plan(Map<String, ArtifactVersion> currentArtifacts);

    default String name() {
        return getClass().getSimpleName();
    }
}
