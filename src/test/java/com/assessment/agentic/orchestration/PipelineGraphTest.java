package com.assessment.agentic.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PipelineGraphTest {

    private static StageDefinition stage(String id, String... deps) {
        return StageDefinition.of(id, id, GateType.NONE, 1, deps);
    }

    @Test
    void ordersStagesTopologicallyAndComputesLineage() {
        PipelineGraph graph = new PipelineGraph(List.of(stage("D", "B", "C"), stage("A"), stage("B", "A"), stage("C", "A")));
        assertEquals(List.of("A", "B", "C", "D"), graph.topologicalOrder());
        assertEquals(Set.of("B", "C", "D"), graph.downstreamOf("A"));
        assertEquals(Set.of("A", "B", "C"), graph.ancestorsOf("D"));
    }

    @Test
    void rejectsCyclesUnknownDependenciesAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineGraph(List.of(stage("A", "B"), stage("B", "A"))));
        assertThrows(IllegalArgumentException.class, () -> new PipelineGraph(List.of(stage("A", "MISSING"))));
        assertThrows(IllegalArgumentException.class, () -> new PipelineGraph(List.of(stage("A"), stage("A"))));
        assertThrows(IllegalArgumentException.class, () -> StageDefinition.of("A", "A", GateType.NONE, 9));
    }
}
