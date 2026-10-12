# DevFlow AI — Controlled Agentic Engineering Workbench

Java 21 / Spring Boot prototype demonstrating requirement-derived planning, a DAG with parallel planning and a join, human approval, policy-bounded execution, requirement-linked validation, durable audit artifacts, clarification/replanning, and H2-backed workflow state.

## Scope and honest capability boundary

This is an assessment prototype, not a general autonomous coding agent. It does **not** generate or edit source code, run shell commands, deploy applications, or execute arbitrary tools. Its only approved execution action is a bounded in-process check of the existing URL-shortener service. The checks create one isolated H2 mapping and perform a redirect lookup that increments click analytics. The executor compensates by deleting its isolated mapping when validation fails.

Reviewer identity is captured for decision lineage but is **not authenticated** by this local prototype. Do not expose it on a network or treat it as an authorization system. H2 persists workflow snapshots, events, and artifacts across application restarts; use a managed database, migrations, authentication, authorization, optimistic locking, and operational controls before production use.

## Prerequisites

- JDK 21
- Maven 3.9+
- Port 8080 available

```bash
java -version
mvn -version
```

## Setup

From the extracted `DevFlow-AI` directory:

```bash
mvn clean test
mvn spring-boot:run
```

Open [Workbench](http://localhost:8080/), [Swagger UI](http://localhost:8080/swagger-ui/index.html), or [Health](http://localhost:8080/api/health).

The application uses the local H2 file database at `./data/urldb`. H2 console is disabled by default.

## End-to-end workflow

### 1. Create a workflow

`POST /api/runs`

```json
{
  "requirement": "Build a URL shortener API that creates short links, redirects users, and tracks click analytics."
}
```

The service normalizes the requirement and builds a scenario-specific task graph. A greenfield/brownfield graph has two low-risk planning tasks (`DESIGN` and `ACCEPTANCE`) that depend on `ANALYZE` and can run in parallel. `SERVICE_CHECKS` depends on both planning tasks; `EVIDENCE` depends on service checks. Each task has a semantic `action`, ID, dependencies, and status; orchestration does not rely on list indices.

### 2. Run planning before approval

`POST /api/runs/{runId}/execute`

For a normal greenfield or brownfield run, this executes only low-risk planning. The two planning tasks run concurrently and the workflow joins their results. It creates requirement-derived design/impact and acceptance-criteria artifacts, then transitions to `PENDING_APPROVAL`. It does not call the URL-shortener service at this stage.

### 3. Approve the exact bounded scope

`POST /api/runs/{runId}/approval`

```json
{
  "decision": "approve",
  "reviewerId": "reviewer-alias",
  "reviewerNote": "Approved allowlisted local URL-shortener checks only"
}
```

`reviewerId` is required and recorded with the decision and approved scope. In this prototype it is caller-supplied, not authenticated. Rejection is terminal; an execution attempt after rejection returns conflict and does not start tasks.

### 4. Execute and validate

Call `POST /api/runs/{runId}/execute` again after approval. The executor runs only the allowlisted URL-shortener checks. It verifies create, resolve, click analytics, and invalid destination rejection. Brownfield/404 requirements also verify the unknown-code not-found exception contract and regression behavior. Unsupported performance/security requirements produce `BLOCKED` validation and a `BLOCKED_VALIDATION` workflow outcome rather than a false pass.

The retry policy retries only `TransientDataAccessException` for the read-only analytics query, at most three attempts with a short bounded delay. Permanent validation failures are not retried. If a test fails after a mapping was created, the workflow attempts a compensating delete and audits the outcome.

### 5. Inspect durable evidence

- `GET /api/runs/{runId}` — run state, DAG tasks, approval, validation
- `GET /api/runs/{runId}/events` — ordered audit/decision lineage
- `GET /api/runs/{runId}/artifacts` — requirement brief, design/impact, acceptance criteria, validation report, engineering summary
- `GET /api/runs/{runId}/metrics` — latency, observed retries/rollbacks, failure-to-stop duration, persistence mode

`mttrMs` is intentionally not measured; the response explains why. `failureToStopDurationMs` is a different measurement and is not mislabeled as MTTR.

## Ambiguous scenario and re-planning

Create a run with:

```json
{ "requirement": "Make the URL shortener faster and more secure for users." }
```

The workflow enters `NEEDS_CLARIFICATION`; approval and execution are blocked. Submit clarification using `POST /api/runs/{runId}/clarification`:

```json
{
  "clarification": "Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements."
}
```

A new scenario-specific DAG is created, a new requirement artifact is recorded, the old decision lineage remains auditable, and fresh approval is required. If the clarification remains ambiguous, the API rejects it rather than pretending the scope is resolved.

## Test scenarios

- Greenfield: URL creation, resolution, analytics, and invalid destination rejection.
- Brownfield: existing-module impact plan, unknown-code 404 contract, and regression behavior.
- Ambiguous: clarification gate, no approval before clarification, graph regeneration, fresh approval.
- Governance: rejected run cannot execute; reviewer identity and scope are recorded.
- Failure: transient-only retries, bounded attempts, compensating rollback, dependent-task skip, safe stop.
- Durability: workflow run state, events, and artifacts are stored in H2 and restored at application startup.

Run the happy path on Windows after starting the application:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\demo.ps1
```

## Failure injection check

For a separate approved run, call `POST /api/runs/{runId}/tasks/SERVICE_CHECKS/fail`:

```json
{ "reason": "Injected failure for safe-stop verification" }
```

Expected: run `FAILED`, stage `STOPPED`, `SERVICE_CHECKS` is `FAILED`, `EVIDENCE` is `SKIPPED`, and validation remains `NOT_RUN`. Use a new run for the happy path after a failure injection.

## URL shortener API

Create: `POST /api/urls`

```json
{ "originalUrl": "https://example.com" }
```

Open the returned `shortUrl` to redirect, then read `GET /api/urls/{shortCode}/analytics`.

## Limitations and trade-offs

- No generated code, patch application, sandbox, deployment, or arbitrary agent tool execution.
- Deterministic requirement analysis is heuristic and should be reviewed; optional LLM analysis may be used if configured, but it does not grant execution authority.
- Reviewer identity is logged but not authenticated.
- H2 durability is suitable for local demonstration, not multi-instance production operation. There is no distributed lock or optimistic concurrency control yet.
- Performance/security requirements are not marked passed without dedicated thresholds and test tooling.
- The local smoke checks do not replace full unit, integration, security, load, or deployment testing.
