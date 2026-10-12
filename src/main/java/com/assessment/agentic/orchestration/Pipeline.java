package com.assessment.agentic.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateful pipeline run. Stage state is mutated only by {@link PipelineExecutor} while holding this
 * object's monitor. Audit events use a separate lock because stage workers append retry events
 * while the orchestrator thread holds the pipeline monitor.
 */
public final class Pipeline {

    private final String id;
    private final String originalRequirement;
    private final List<String> clarifications = new ArrayList<>();
    private final Instant createdAt;
    private final Map<String, StageRecord> stages = new LinkedHashMap<>();
    private final List<ArtifactVersion> artifactHistory = new ArrayList<>();
    private final List<Incident> incidents = new ArrayList<>();
    private final Map<String, FaultMode> faults = new ConcurrentHashMap<>();
    private final Object eventLock = new Object();
    private final List<PipelineEvent> events = new ArrayList<>();
    private long eventSeq;

    private PipelineGraph graph;
    private PipelineStatus status = PipelineStatus.RUNNING;
    private Instant completedAt;
    private int stageExecutions;

    Pipeline(String requirement, PipelineGraph graph, Instant createdAt) {
        this.id = UUID.randomUUID().toString();
        this.originalRequirement = requirement;
        this.createdAt = createdAt;
        replaceGraph(graph, Map.of());
    }

    public String id() { return id; }
    public String originalRequirement() { return originalRequirement; }
    public List<String> clarifications() { return List.copyOf(clarifications); }
    public Instant createdAt() { return createdAt; }
    public Instant completedAt() { return completedAt; }
    public PipelineStatus status() { return status; }
    public PipelineGraph graph() { return graph; }
    public int stageExecutions() { return stageExecutions; }

    /** Effective requirement: the original text plus every clarification, in order. */
    public String requirement() {
        if (clarifications.isEmpty()) return originalRequirement;
        StringBuilder out = new StringBuilder(originalRequirement);
        for (String clarification : clarifications) out.append("\nClarification: ").append(clarification);
        return out.toString();
    }

    public List<StageRecord> stages() {
        return graph.topologicalOrder().stream().map(stages::get).toList();
    }

    public StageRecord stage(String stageId) {
        StageRecord record = stages.get(stageId);
        if (record == null) throw new NoSuchElementException("Stage not found: " + stageId);
        return record;
    }

    public List<ArtifactVersion> artifactHistory() { return List.copyOf(artifactHistory); }
    public List<Incident> incidents() { return List.copyOf(incidents); }

    public List<PipelineEvent> events() {
        synchronized (eventLock) {
            return List.copyOf(events);
        }
    }

    /** Latest artifact of every COMPLETED stage. */
    public Map<String, ArtifactVersion> currentArtifacts() {
        Map<String, ArtifactVersion> current = new LinkedHashMap<>();
        for (StageRecord record : stages()) {
            if (record.status() == StageStatus.COMPLETED && record.artifact() != null) current.put(record.id(), record.artifact());
        }
        return Collections.unmodifiableMap(current);
    }

    public Map<String, ArtifactVersion> ancestorArtifacts(String stageId) {
        Map<String, ArtifactVersion> current = currentArtifacts();
        Map<String, ArtifactVersion> result = new LinkedHashMap<>();
        for (String ancestor : graph.ancestorsOf(stageId)) {
            ArtifactVersion artifact = current.get(ancestor);
            if (artifact != null) result.put(ancestor, artifact);
        }
        return result;
    }

    public Map<String, Integer> ancestorVersions(String stageId) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        ancestorArtifacts(stageId).forEach((key, value) -> versions.put(key, value.version()));
        return versions;
    }

    public Map<String, String> ancestorDigests(String stageId) {
        Map<String, String> digests = new LinkedHashMap<>();
        ancestorArtifacts(stageId).forEach((key, value) -> digests.put(key, value.digest()));
        return digests;
    }

    /** SHA-256 over a canonical rendering (map keys sorted) so equal content always has an equal digest. */
    static String digest(Map<String, Object> content) {
        StringBuilder canonical = new StringBuilder();
        canonicalize(content, canonical);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void canonicalize(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            map.entrySet().stream().sorted(java.util.Comparator.comparing(e -> String.valueOf(e.getKey())))
                    .forEach(e -> { out.append(e.getKey()).append('='); canonicalize(e.getValue(), out); out.append(','); });
            out.append('}');
        } else if (value instanceof java.util.Collection<?> collection) {
            out.append('[');
            collection.forEach(v -> { canonicalize(v, out); out.append(','); });
            out.append(']');
        } else {
            out.append(value);
        }
    }

    // ---- mutation (package-private; called by PipelineExecutor under the pipeline monitor) ----

    void status(PipelineStatus newStatus) { this.status = newStatus; }
    void completedAt(Instant at) { this.completedAt = at; }
    void addClarification(String clarification) { clarifications.add(clarification); }
    void countExecutions(int count) { stageExecutions += count; }

    void replaceGraph(PipelineGraph newGraph, Map<String, StageRecord> retained) {
        Map<String, StageRecord> next = new LinkedHashMap<>();
        for (StageDefinition definition : newGraph.stages()) {
            StageRecord kept = retained.get(definition.id());
            next.put(definition.id(), kept != null ? kept : new StageRecord(definition));
        }
        stages.clear();
        stages.putAll(next);
        graph = newGraph;
    }

    ArtifactVersion publishArtifact(StageDefinition stage, StageResult result, Map<String, Integer> inputVersions,
                                    String producedBy, String reason, Instant at) {
        int version = (int) artifactHistory.stream().filter(a -> a.stageId().equals(stage.id())).count() + 1;
        ArtifactVersion artifact = new ArtifactVersion(stage.id() + "-v" + version, stage.id(), version, result.verdict(),
                result.summary(), result.output(), Map.copyOf(inputVersions), producedBy, reason, at, digest(result.output()));
        artifactHistory.add(artifact);
        return artifact;
    }

    void openIncident(String stageId, String reason, Instant at) {
        boolean open = incidents.stream().anyMatch(i -> i.recoveredAt() == null);
        if (!open) incidents.add(new Incident(stageId, reason, at, null));
    }

    Incident closeOpenIncident(Instant at) {
        for (int i = 0; i < incidents.size(); i++) {
            if (incidents.get(i).recoveredAt() == null) {
                Incident recovered = incidents.get(i).recovered(at);
                incidents.set(i, recovered);
                return recovered;
            }
        }
        return null;
    }

    Map<String, FaultMode> faults() { return faults; }

    PipelineEvent record(String type, String stageId, String actor, Map<String, Object> details) {
        synchronized (eventLock) {
            PipelineEvent event = new PipelineEvent(++eventSeq, id, type, stageId, actor,
                    details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details)), Instant.now());
            events.add(event);
            return event;
        }
    }
}
