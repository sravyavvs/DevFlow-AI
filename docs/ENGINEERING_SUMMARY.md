# DevFlow AI: Engineering Summary

Controlled agentic SDLC orchestration for a URL shortener. This document records what was built, why it was built that way, how it was verified, and what was deliberately left for future work.

| | |
|---|---|
| **Stack** | Java 21, Spring Boot 3.4, H2, JUnit 5, vanilla HTML/JS |
| **Tests** | 31 automated (22 orchestration, 9 URL shortener) |
| **Run** | mvn clean test, then mvn spring-boot:run and open http://localhost:8080/ |

## 1. Problem and approach

The assignment asks for a prototype that turns a requirement into a reviewable engineering outcome through agentic execution, with orchestration as the critical differentiator: an explicit dependency graph, parallel paths with synchronisation, human approval for high-impact actions, bounded retries, rollback, safe stop, policy guardrails, audit-grade traceability, reliability metrics and dynamic re-planning.

The design separates three concerns so each can change independently:

- A generic orchestration engine that knows nothing about URL shorteners: graph, scheduling, gates, retries, rollback, re-planning and audit.
- SDLC stage agents that do the domain work for one phase each.
- Declarative policy rules evaluated at stage entry and exit gates.

## 2. How the project evolved

The first version was a fixed five-task workflow (analyse, design and acceptance planning in parallel, approved service checks, evidence) with H2 persistence. Reviewing it against the requirements showed four gaps: the graph was hard-coded rather than a reusable engine, implementation, documentation and release stages were missing, there was no re-planning when upstream outputs changed, and MTTR was not measured.

Rather than patch the fixed workflow, a generic DAG engine was extracted and the SDLC was rebuilt on top of it. Once the new engine covered every scenario, the first version was removed so the submission has a single orchestrator, a single UI and a single demo path.

## 3. What was built

### 3.1 Orchestration engine (orchestration/)

- PipelineGraph: validated DAG with cycle, unknown-dependency and duplicate detection, topological ordering, and ancestor and descendant queries.
- PipelineExecutor: the control loop. It re-plans, computes the ready set, applies entry policies and human gates, runs independent stages in parallel on a bounded pool, joins them, applies exit policies, and publishes immutable versioned artifacts.
- StageHandler: the agent contract. Agents receive only the requirement and versioned upstream artifacts and return a result, so they are pure, testable and safe to parallelise. Side-effecting agents must supply a compensating action.
- Human operations: approve or reject, clarify, revise an artifact, resume after failure, safe stop, and fault injection for drills.

### 3.2 SDLC stages (orchestration/sdlc/)

| Stage | Responsibility |
|---|---|
| Requirements | Classifies greenfield, brownfield or ambiguous; extracts capabilities, measurable targets and deferrals; derives acceptance criteria. |
| Clarification | Human-only gate used when intent is missing. No agent runs. |
| Architecture | API contract, data model, design decisions and risks; impact analysis for brownfield changes. |
| Test plan | Traces every acceptance criterion to a test case and to existing automated tests. |
| Implementation | Codebase reasoning by reflection over the url module; marks each capability implemented or a gap; emits a reviewable change set. Requires human approval. Never edits source. |
| Testing | Runs requirement-linked checks against the real URL shortener service with an isolated, always-deleted fixture. |
| NFR verification | Added only when quality goals exist. Measures latency and probes hostile destinations, or returns BLOCKED when a goal has no measurable target. |
| Documentation | Writes delivery notes (API reference, change set, traceability, risks). Its file is the side effect used to demonstrate rollback. |
| Release readiness | Go/no-go checklist from upstream evidence. Requires human approval. Does not deploy. |

### 3.3 Governance

- Dynamic planning: SdlcPlanner derives the graph from the requirements artifact after every wave, so clarification and upstream changes re-shape the plan through one mechanism.
- Digest-bound approvals: an approval stores SHA-256 fingerprints of the inputs the reviewer saw. A material change withdraws it; byte-identical regenerated evidence keeps it.
- Segregation of duties: agent identities cannot approve, resume or stop.
- Policy rules: SEC-001 no secrets in artifacts; SEC-002 writes only inside the artifact directory; CHG-001 change set limited to the url package and docs; CMP-001 release requires every upstream verdict to be PASSED.
- BLOCKED as a first-class verdict, distinct from FAILED, so unverifiable goals are never reported as passed.

### 3.4 Reliability and observability

- Bounded retries for transient errors only, with backoff; permanent failures are never retried.
- Per-stage timeout, saga-style rollback in reverse topological order, automatic halt on failure, operator safe stop, and an execution budget against runaway re-planning.
- Append-only, sequenced audit log recording the actor (agent, orchestrator, planner, policy engine or named human) for every event.
- Metrics computed only from the audit log: stage success rate, retries, rollbacks, MTTR, end-to-end latency, human wait time versus agent time, policy denials and re-plans.

### 3.5 Interface, scripts and documentation

- Single-screen workbench at /: pipeline map, one next-step action at a time, and tabs for activity, stage output, metrics and failure drills.
- REST API at /api/pipelines, with Swagger UI.
- scripts/demo.ps1 and scripts/demo.sh run all five scenarios end to end and save evidence JSON to docs/runs/.
- README with a requirement-to-implementation map, plus architecture, scenarios and testing documents.

## 4. Requirement coverage

| Assignment requirement | Implementation |
|---|---|
| Requirement understanding | RequirementAnalyzer: scenario, capabilities, ambiguity reasons, measurable targets, deferrals |
| Task decomposition | SdlcPlanner builds the dependency graph from the requirements output |
| Codebase reasoning | Architecture impact analysis; Implementation capability coverage over compiled code |
| Dependency graph, entry/exit gates | PipelineGraph; PolicyEngine at ENTRY and EXIT; human gates |
| Parallel paths with synchronisation | Architecture with test plan; testing with documentation and NFR; PARALLEL_JOIN events |
| Context and decision lineage | Versioned artifacts record consumed input versions; approvals bind to input digests |
| Human approval for high-impact actions | Implementation and release gates with named reviewers |
| Retries, fallback, rollback, safe stop | Bounded transient retry, BLOCKED fallback, compensation, halt, stop, budget |
| Policy guardrails | Security, change-control and compliance rules |
| Observability and metrics | Audit log; success rate, retries, rollbacks, MTTR, latency |
| Dynamic re-planning | GRAPH_REPLANNED on clarification; STAGES_INVALIDATED and APPROVAL_INVALIDATED on revision |
| Controlled autonomy | Agents stop at gates; never edit source, run shell commands or deploy |

## 5. Key decisions and trade-offs

| Decision | Benefit | Cost |
|---|---|---|
| Derive the plan from artifacts | One mechanism for planning and re-planning | Planner must be deterministic and fast |
| Agents as pure functions | Unit-testable and parallel-safe | Side effects must be declared and compensable |
| BLOCKED separate from FAILED | No false passes on vague goals | Some requirements end blocked until clarified |
| Digest-bound approvals | Strict change control without needless re-approval | Reviewers must re-approve after material edits |
| Agents propose, humans apply code | Keeps the highest-risk action human-owned | No autonomous code generation in this prototype |
| Deterministic requirement analysis | Reproducible and testable | Lower recall on unusual phrasing |
| In-memory pipeline state | Kept scope to the deadline | Pipelines are lost on restart |

## 6. Verification

- Unit tests for graph rules, engine behaviour with fake agents (including a latch that proves parallel stages run concurrently), and requirement analysis.
- Spring Boot integration tests that run all five scenarios end to end against the real service and H2: greenfield, brownfield, ambiguous with re-planning, blocked quality goal, and failure with retry, rollback, resume and MTTR.
- Existing URL shortener unit and MockMvc tests covering 201, 302, 404, 400 and click analytics.
- Demo scripts acting as acceptance tests against a running instance.
- Browser walkthrough of every UI state on desktop and mobile.

## 7. Known limitations

- Agents do not write code; implementation is a reviewed change-set proposal.
- Pipeline state is in memory; URL mappings persist in H2.
- Requirement analysis is keyword-based and conservative.
- Reviewer and operator identities are audit labels, not authenticated users.
- Performance evidence is an in-process microbenchmark, not a load test.
- Locking is per pipeline within one JVM; there is no multi-instance coordination.

## 8. Planned but not implemented

These items were identified during design but left out to meet the one-day deadline. Each lists why it matters and the intended approach.

| Item | Priority | Why it matters | Intended approach |
|---|---|---|---|
| Durable pipeline persistence | High | Pipelines and their audit trail must survive restarts | Event-sourced store: persist events and artifacts, rebuild state by replay on startup; Flyway migrations |
| Authenticated reviewers and role-based gates | High | Approvals are only meaningful if identity is verified | OIDC login; roles mapped per gate (tech lead approves implementation, release manager approves release) |
| Sandboxed code generation | High | Completes the implementation stage autonomously while keeping humans in control | Agent generates a patch in an isolated workspace, opens a pull request; CI becomes the testing stage; human approval still gates merge |
| LLM-backed requirement analysis | Medium | Better recall on unusual phrasing | Same output contract as the rule-based analyser, with a labelled evaluation set and rule-based fallback |
| Real load and security testing | Medium | Microbenchmarks do not prove production behaviour | Load tests (k6 or Gatling) and DAST/SAST and dependency scanning as dedicated stages |
| Multi-instance coordination | Medium | Horizontal scaling and failover | Distributed locks or a work queue; idempotent stage execution |
| Telemetry export and alerting | Medium | Operations visibility beyond one pipeline | OpenTelemetry traces and metrics from the event stream; dashboards and alerts on failure and MTTR |
| Artifact revision and resume in the UI | Low | Re-planning on revision is available only through the API | Inline editor for stage outputs with diff view |
| Externalised policies | Low | Change rules without redeploying | Policy-as-code engine such as OPA, versioned with the repository |
| End-to-end UI tests | Low | Protects the workbench from regressions | Playwright tests for each scenario, run in CI |
| Approval SLAs and notifications | Low | Reduces human wait time, the largest share of lead time | Notify reviewers by chat or email; escalate after a time limit |
| URL shortener features | Low | Production readiness of the product itself | Link expiry, custom aliases, rate limiting, duplicate detection, referrer and time-series analytics, malicious-URL reputation checks |

## 9. How to run

- Prerequisites: JDK 21, Maven 3.9+, port 8080 free.
- mvn clean test runs all 31 tests.
- mvn spring-boot:run starts the app; open http://localhost:8080/ for the workbench or /swagger-ui/index.html for the API.
- scripts/demo.ps1 (Windows) or scripts/demo.sh (macOS/Linux) runs every scenario and saves evidence to docs/runs/.

## Development note

AI assistance was used during development, as the assignment permits. Design decisions, scope choices and the final review are the author’s.
