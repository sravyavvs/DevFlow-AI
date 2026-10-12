# Architecture and control model

## Components

- `WorkflowController`: REST interface for run creation, clarification, approval, execution, events, artifacts, and metrics.
- `RequirementAnalysisService`: optional OpenAI-compatible analysis with deterministic fallback. Output is advisory; policy enforcement remains in `WorkflowService`.
- `WorkflowService`: scenario-specific DAG creation, parallel low-risk planning, dependency join, approval gate, allowlisted execution, validation evidence, retries, compensation, and decision lineage.
- `WorkflowPersistence`: H2-backed workflow snapshots, audit events, and artifacts; state is rehydrated on startup.
- `UrlShortenerService`: the only service available to the approved execution runner.

## DAG and lifecycle

Normal runs use `ANALYZE -> (DESIGN || ACCEPTANCE) -> SERVICE_CHECKS -> EVIDENCE`. Design/acceptance are independent after analysis and run concurrently. The join only completes when both artifacts are created and both tasks complete. The service checks task is gated by human approval. `EVIDENCE` cannot proceed unless service checks pass. Brownfield plans use `CODEBASE_IMPACT` and `REGRESSION_PLAN`; greenfield plans use `API_DESIGN` and `ACCEPTANCE_PLAN`.

Ambiguous requirements use `ANALYZE -> CLARIFY -> SERVICE_CHECKS -> EVIDENCE`, with execution blocked until clarification produces a non-ambiguous plan. Clarification generates a new task graph and a new approval decision is required.

## Guardrails

- Only known task actions can execute; no shell or arbitrary code execution.
- Planning is read-only and may execute before approval.
- Service checks require `decision=approve` and record reviewer ID, note, approved scope, and decision-lineage event.
- Rejection is terminal for that run.
- Only transient data-access exceptions are retried, with a maximum of three attempts and bounded delay.
- On failed checks, the executor attempts compensation by deleting the isolated test mapping; rollback outcome is audited.
- Unsupported performance/security criteria are `NOT_RUN`; overall validation becomes `BLOCKED`, not `PASSED`.
- Task dependencies are resolved by task ID/action, not list index.

## Persistence and observability

`workflow_runs`, `workflow_events`, and `workflow_artifacts` tables persist serialized snapshots in H2. Audit events capture requirement analysis, graph decomposition, planning starts/completions, join completion, clarification/replanning, policy decision, approval/rejection, execution, retries, rollback, validation, and final status. Metrics distinguish failure-to-stop duration from MTTR; MTTR is not measured without a recovery-completed event.

## Explicit non-goals

This prototype does not autonomously author patches, modify source files, launch shell commands, deploy, or authenticate reviewer identities. It is a bounded orchestration prototype, not a production authorization platform.
