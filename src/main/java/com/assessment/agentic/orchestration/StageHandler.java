package com.assessment.agentic.orchestration;

/**
 * An agent that executes one kind of stage. Handlers are pure with respect to pipeline state: they
 * receive a {@link StageContext} and return a {@link StageResult}. Handlers with external side
 * effects must provide a compensating action used for rollback.
 */
public interface StageHandler {

    StageResult execute(StageContext context) throws Exception;

    default boolean hasSideEffects() {
        return false;
    }

    /** Undo the side effects described by a previously produced artifact. */
    default void compensate(String pipelineId, ArtifactVersion artifact) throws Exception {
    }
}
