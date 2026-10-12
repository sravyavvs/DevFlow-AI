package com.assessment.agentic.orchestration;

import java.time.Instant;

/** Mutable runtime state of one stage. Mutated only by {@link PipelineExecutor} under the pipeline lock. */
public final class StageRecord {

    private final StageDefinition definition;
    private StageStatus status = StageStatus.PENDING;
    private int attempts;
    private String lastError;
    private Instant startedAt;
    private Instant finishedAt;
    private ApprovalRecord approval;
    private ArtifactVersion artifact;

    StageRecord(StageDefinition definition) {
        this.definition = definition;
    }

    public StageDefinition definition() { return definition; }
    public String id() { return definition.id(); }
    public StageStatus status() { return status; }
    public int attempts() { return attempts; }
    public String lastError() { return lastError; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public ApprovalRecord approval() { return approval; }
    public ArtifactVersion artifact() { return artifact; }

    public Long durationMs() {
        return startedAt == null || finishedAt == null ? null : finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    void status(StageStatus newStatus) { this.status = newStatus; }
    void approval(ApprovalRecord newApproval) { this.approval = newApproval; }
    void artifact(ArtifactVersion newArtifact) { this.artifact = newArtifact; }

    void markRunning(Instant at) {
        status = StageStatus.RUNNING;
        startedAt = at;
        finishedAt = null;
        lastError = null;
    }

    void complete(ArtifactVersion produced, int usedAttempts, Instant at) {
        status = StageStatus.COMPLETED;
        artifact = produced;
        attempts = usedAttempts;
        finishedAt = at;
        lastError = null;
    }

    void fail(String reason, int usedAttempts, Instant at) {
        status = StageStatus.FAILED;
        attempts = usedAttempts;
        lastError = reason;
        finishedAt = at;
    }

    void block(String reason) {
        status = StageStatus.BLOCKED;
        lastError = reason;
    }

    /** Return to PENDING so the stage is re-planned; the approval is kept and re-validated against input versions. */
    void reset() {
        status = StageStatus.PENDING;
        artifact = null;
        lastError = null;
        startedAt = null;
        finishedAt = null;
    }
}
