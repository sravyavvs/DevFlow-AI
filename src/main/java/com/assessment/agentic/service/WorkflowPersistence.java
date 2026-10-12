package com.assessment.agentic.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.assessment.agentic.model.AuditEvent;
import com.assessment.agentic.model.WorkflowArtifact;
import com.assessment.agentic.model.WorkflowRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** H2-backed durable snapshots for workflow state, audit events, and artifacts. */
@Repository
public class WorkflowPersistence {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkflowPersistence(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_runs (run_id VARCHAR(36) PRIMARY KEY, payload CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_events (event_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, event_time VARCHAR(40) NOT NULL, payload CLOB NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_events_run_time ON workflow_events(run_id, event_time)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS workflow_artifacts (artifact_id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, created_time VARCHAR(40) NOT NULL, payload CLOB NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_workflow_artifacts_run_time ON workflow_artifacts(run_id, created_time)");
    }

    public synchronized Map<String, WorkflowRun> loadRuns() {
        Map<String, WorkflowRun> result = new TreeMap<>();
        jdbc.query("SELECT run_id, payload FROM workflow_runs", rs -> {
            try { result.put(rs.getString(1), mapper.readValue(rs.getString(2), WorkflowRun.class)); }
            catch (Exception ex) { throw new IllegalStateException("Could not restore workflow run", ex); }
        });
        return result;
    }

    public synchronized Map<String, List<AuditEvent>> loadEvents() {
        Map<String, List<AuditEvent>> result = new TreeMap<>();
        jdbc.query("SELECT run_id, payload FROM workflow_events ORDER BY event_time", rs -> {
            try {
                AuditEvent event = mapper.readValue(rs.getString(2), AuditEvent.class);
                result.computeIfAbsent(rs.getString(1), ignored -> new java.util.ArrayList<>()).add(event);
            } catch (Exception ex) { throw new IllegalStateException("Could not restore audit event", ex); }
        });
        return result;
    }

    public synchronized Map<String, List<WorkflowArtifact>> loadArtifacts() {
        Map<String, List<WorkflowArtifact>> result = new TreeMap<>();
        jdbc.query("SELECT run_id, payload FROM workflow_artifacts ORDER BY created_time", rs -> {
            try {
                WorkflowArtifact artifact = mapper.readValue(rs.getString(2), WorkflowArtifact.class);
                result.computeIfAbsent(rs.getString(1), ignored -> new java.util.ArrayList<>()).add(artifact);
            } catch (Exception ex) { throw new IllegalStateException("Could not restore artifact", ex); }
        });
        return result;
    }

    public synchronized void saveRun(WorkflowRun run) {
        String payload = write(run);
        int updated = jdbc.update("UPDATE workflow_runs SET payload=? WHERE run_id=?", payload, run.runId());
        if (updated == 0) jdbc.update("INSERT INTO workflow_runs(run_id,payload) VALUES (?,?)", run.runId(), payload);
    }

    public synchronized void saveEvent(AuditEvent event) {
        jdbc.update("INSERT INTO workflow_events(event_id,run_id,event_time,payload) VALUES (?,?,?,?)",
                event.eventId(), event.runId(), orderKey(event.timestamp()), write(event));
    }

    public synchronized void saveArtifact(WorkflowArtifact artifact) {
        jdbc.update("INSERT INTO workflow_artifacts(artifact_id,run_id,created_time,payload) VALUES (?,?,?,?)",
                artifact.artifactId(), artifact.runId(), orderKey(artifact.createdAt()), write(artifact));
    }

    private String orderKey(java.time.Instant instant) {
        return String.format("%020d.%09d", instant.getEpochSecond(), instant.getNano());
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not persist workflow data", ex); }
    }
}
