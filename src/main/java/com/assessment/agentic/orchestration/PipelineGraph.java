package com.assessment.agentic.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;

/** Validated, immutable directed acyclic graph of stages. */
public final class PipelineGraph {

    private final Map<String, StageDefinition> stages;
    private final List<String> topologicalOrder;

    public PipelineGraph(List<StageDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) throw new IllegalArgumentException("A pipeline needs at least one stage.");
        Map<String, StageDefinition> byId = new LinkedHashMap<>();
        for (StageDefinition definition : definitions) {
            if (byId.put(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate stage id: " + definition.id());
            }
        }
        for (StageDefinition definition : byId.values()) {
            for (String dependency : definition.dependsOn()) {
                if (dependency.equals(definition.id())) throw new IllegalArgumentException("Stage depends on itself: " + dependency);
                if (!byId.containsKey(dependency)) {
                    throw new IllegalArgumentException("Stage " + definition.id() + " depends on unknown stage " + dependency);
                }
            }
        }
        this.stages = Collections.unmodifiableMap(byId);
        this.topologicalOrder = List.copyOf(sort(byId));
    }

    /** Kahn's algorithm; deterministic because ties are broken by declaration order. */
    private static List<String> sort(Map<String, StageDefinition> byId) {
        Map<String, Integer> inDegree = new HashMap<>();
        byId.values().forEach(d -> inDegree.put(d.id(), d.dependsOn().size()));
        Deque<String> queue = new ArrayDeque<>();
        byId.keySet().stream().filter(id -> inDegree.get(id) == 0).forEach(queue::add);
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (StageDefinition candidate : byId.values()) {
                if (candidate.dependsOn().contains(id) && inDegree.merge(candidate.id(), -1, Integer::sum) == 0) {
                    queue.add(candidate.id());
                }
            }
        }
        if (order.size() != byId.size()) throw new IllegalArgumentException("Pipeline graph contains a cycle.");
        return order;
    }

    public List<StageDefinition> stages() {
        return topologicalOrder.stream().map(stages::get).toList();
    }

    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    public boolean contains(String id) {
        return stages.containsKey(id);
    }

    public StageDefinition stage(String id) {
        StageDefinition definition = stages.get(id);
        if (definition == null) throw new NoSuchElementException("Stage not found: " + id);
        return definition;
    }

    /** Every stage that transitively depends on {@code id}, in topological order. */
    public Set<String> downstreamOf(String id) {
        stage(id);
        Set<String> result = new LinkedHashSet<>();
        for (String candidate : topologicalOrder) {
            StageDefinition definition = stages.get(candidate);
            if (definition.dependsOn().contains(id) || definition.dependsOn().stream().anyMatch(result::contains)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /** Every stage that {@code id} transitively depends on, in topological order. */
    public Set<String> ancestorsOf(String id) {
        Set<String> found = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>(stage(id).dependsOn());
        while (!work.isEmpty()) {
            String next = work.pop();
            if (found.add(next)) work.addAll(stages.get(next).dependsOn());
        }
        Set<String> ordered = new LinkedHashSet<>();
        topologicalOrder.stream().filter(found::contains).forEach(ordered::add);
        return ordered;
    }

    public String toMermaid(Function<String, String> statusOf) {
        StringBuilder out = new StringBuilder("flowchart LR\n");
        for (StageDefinition definition : stages()) {
            String gate = definition.gate() == GateType.NONE ? "" : "<br/>gate: " + definition.gate();
            out.append("  ").append(definition.id()).append("[\"").append(definition.id())
                    .append("<br/>").append(statusOf.apply(definition.id())).append(gate).append("\"]\n");
            for (String dependency : definition.dependsOn()) {
                out.append("  ").append(dependency).append(" --> ").append(definition.id()).append('\n');
            }
        }
        return out.toString();
    }
}
