package com.assessment.agentic.orchestration;

import java.util.List;

/**
 * Immutable node in the pipeline dependency graph.
 *
 * @param id          unique stage id within the graph
 * @param phase       handler key (which agent executes the stage)
 * @param title       human-readable purpose
 * @param dependsOn   ids of stages whose completed outputs are this stage's entry condition
 * @param gate        human checkpoint enforced before execution
 * @param maxAttempts bounded retry budget for transient failures (1..5)
 */
public record StageDefinition(String id, String phase, String title, List<String> dependsOn, GateType gate, int maxAttempts) {

    public StageDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Stage id is required.");
        if (phase == null || phase.isBlank()) throw new IllegalArgumentException("Stage phase is required.");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        gate = gate == null ? GateType.NONE : gate;
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("maxAttempts must be between 1 and 5.");
    }

    public static StageDefinition of(String id, String title, GateType gate, int maxAttempts, String... dependsOn) {
        return new StageDefinition(id, id, title, List.of(dependsOn), gate, maxAttempts);
    }
}
