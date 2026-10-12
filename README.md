# DevFlow AI: Controlled Agentic SDLC Orchestration

DevFlow AI turns a natural-language requirement for a URL shortener into a reviewable engineering outcome. Agents analyse the requirement, design, plan tests, reason over the codebase, verify, document, and assemble release evidence. Humans own clarification, approvals of high-impact steps, and final sign-off.

The core is a generic DAG orchestration engine (`orchestration/`). It has no knowledge of URL shorteners. SDLC behaviour is plugged in as stage agents (`orchestration/sdlc/`), and guardrails are plugged in as policy rules (`orchestration/policy/`).

## Quick start

Prerequisites: JDK 21, Maven 3.9+, and port 8080 free.

```bash
mvn clean test          # unit + Spring integration tests
mvn spring-boot:run     # http://localhost:8080
```

Open **http://localhost:8080/** for the interactive workbench. Pick a scenario, create a pipeline, then approve, clarify, inject faults, resume or stop, and watch stages, metrics, the live DAG and the audit log update.

To run all five scenarios end to end from the command line (evidence JSON is saved to `docs/runs/`):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1     # Windows
```
```bash
./scripts/demo.sh                                               # macOS/Linux (curl + jq)
```

Swagger UI is at `http://localhost:8080/swagger-ui/index.html`. `GET /api/pipelines/{id}/graph` returns a live Mermaid diagram of the DAG with stage statuses; paste it into any Mermaid viewer.

## How the assignment requirements map to the implementation

| Requirement (assignment §4) | Where it lives |
|---|---|
| Requirement understanding and ambiguity detection | `RequirementAnalyzer`: scenario classification, capability extraction, measurable-target detection, deferral handling |
| Task decomposition with dependencies | `SdlcPlanner` builds the DAG **from the REQUIREMENTS artifact**, not at creation time |
| Codebase reasoning (brownfield) | `ArchitectureAgent` impact analysis; `ImplementationAgent` inspects the compiled `url` module to decide IMPLEMENTED vs GAP per capability |
| Explicit dependency graph | `PipelineGraph`: validated DAG (cycle, unknown-dependency and duplicate detection), topological order, ancestor/descendant queries |
| Entry and exit gates | `PolicyEngine` evaluated at `GatePoint.ENTRY` and `GatePoint.EXIT`; human gates via `GateType` |
| Sequential + parallel paths with synchronization | Ready stages in a wave run concurrently on a bounded pool and join (`PARALLEL_JOIN` event): ARCHITECTURE ‖ TEST_PLAN, then TESTING ‖ DOCUMENTATION ‖ NFR_VERIFICATION |
| Cross-stage context and decision lineage | Agents receive versioned ancestor artifacts; every artifact records the input versions it consumed; every approval binds to input content digests |
| Human approval for high-impact actions | IMPLEMENTATION (code change) and RELEASE_READINESS (release) require a named reviewer; agent identities are refused (segregation of duties) |
| Bounded retries | Per-stage `maxAttempts` (1–5); only `TransientStageException` consumes retries, with linear backoff |
| Fallback | Goals that cannot be measured fall back to an explicit BLOCKED verdict, never a pass. A stage that times out (30 s) is treated as failed |
| Rollback | Saga-style compensation of side-effecting stages in reverse topological order (`ROLLBACK_*` events) |
| Safe stop | Failure halts new work and skips dependents; operator kill switch `POST /stop`; global execution budget (40 stage runs) |
| Policy guardrails | SEC-001 secrets in artifacts, SEC-002 write location, CHG-001 change-set scope, CMP-001 release requires verified evidence |
| Audit-grade observability | Append-only, sequenced event log per pipeline (`GET /events`); immutable artifact history (`GET /artifacts`) |
| Reliability metrics | `GET /metrics`: success rate, retry count, rollback count, MTTR, end-to-end latency, human wait vs agent latency, per-stage durations |
| Dynamic re-planning | The planner runs after every wave. A clarified requirement reshapes the graph (`GRAPH_REPLANNED`); a revised artifact invalidates downstream work and approvals (`STAGES_INVALIDATED`, `APPROVAL_INVALIDATED`) |
| Controlled autonomy | Agents run until a gate, then stop. They never write source, run shell commands, or deploy. Humans approve, clarify, revise, resume, or stop |

## Pipeline API (`/api/pipelines`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/pipelines` | Create from `{"requirement": "..."}`; agents run until the first gate |
| GET | `/api/pipelines/{id}` | State, stages, verdicts, approvals, next human actions |
| POST | `/{id}/stages/{stageId}/approval` | `{"decision":"approve\|reject","reviewerId":"...","reviewerNote":"..."}` |
| POST | `/{id}/clarification` | `{"clarification":"...","requesterId":"..."}`; re-runs requirements and re-plans |
| PUT | `/{id}/stages/{stageId}/artifact` | Human revision of a stage output; downstream invalidated |
| POST | `/{id}/resume` | Operator recovery of a FAILED pipeline |
| POST | `/{id}/stop` | Operator safe stop (terminal, compensates side effects) |
| POST | `/{id}/faults` | Fault injection: `TRANSIENT_ONCE`, `TRANSIENT_ALWAYS`, `PERMANENT` |
| POST | `/{id}/advance` | Re-evaluate gates (e.g. after an external change) |
| GET | `/{id}/events` · `/artifacts` · `/metrics` · `/graph` | Audit log, artifact lineage, reliability metrics, Mermaid DAG |

URL shortener API: `POST /api/urls`, `GET /{shortCode}` (302), `GET /api/urls/{shortCode}/analytics`. Health: `GET /api/health`.

## Project layout

```
src/main/java/com/assessment/agentic/
  orchestration/          generic DAG engine (no URL-shortener knowledge)
    policy/               guardrail rules evaluated at stage gates
    sdlc/                 SDLC stage agents + planner
  service/PipelineService application service (auto-advance after human actions)
  controller/             REST API
  url/                    the URL shortener being engineered
src/main/resources/static/index.html   workbench UI
scripts/demo.ps1 | demo.sh             end-to-end scenario runner
```

## Documentation

- [Architecture and control model](docs/architecture.md)
- [Scenarios: greenfield, brownfield, ambiguous, plus failure and blocked cases](docs/scenarios.md)
- [Testing approach, limitations and trade-offs](docs/testing-and-limitations.md)

## Honest capability boundary

- The agents propose change sets; they do not edit source. The URL shortener service was implemented by the engineer. The IMPLEMENTATION stage verifies that the codebase satisfies each capability and emits a reviewable change set. Gaps block release.
- Requirement analysis is deterministic and keyword-based. It is conservative by design: when in doubt it asks for clarification or returns BLOCKED instead of guessing. An LLM analyser can replace it behind the same output contract.
- Pipeline state (stages, events, artifacts) is held in memory, so restarting the app clears pipelines. URL mappings themselves persist in H2. Production would need a durable event-sourced pipeline store, authenticated reviewer identities, and multi-instance coordination.
