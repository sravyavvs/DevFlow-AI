package com.assessment.agentic.orchestration.sdlc;

import java.util.function.Supplier;

import org.springframework.dao.TransientDataAccessException;

import com.assessment.agentic.orchestration.TransientStageException;

/** Classifies infrastructure errors: only transient data-access failures are retryable. */
final class ServiceProbe {

    private ServiceProbe() {
    }

    static <T> T call(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException transientFailure) {
            throw new TransientStageException(operation + " hit a transient data-access error: " + transientFailure.getMessage(), transientFailure);
        }
    }
}
