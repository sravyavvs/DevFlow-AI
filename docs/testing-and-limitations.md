# Testing, limitations and trade-offs

## Test pyramid

| Suite | Level | What it proves |
|---|---|---|
| `PipelineGraphTest` | Unit | Topological order, ancestors and descendants; rejects cycles, unknown dependencies, duplicates and an invalid retry budget |
| `PipelineExecutorTest` | Unit (fake agents) | Real concurrency of independent stages (latch-proven), joins, approval gates, digest-bound approvals, segregation of duties, bounded transient retry, no retry on permanent failure, rollback of side effects, safe stop, rejection, recovery with MTTR, exit-gate secret blocking, invalidation on upstream revision |
| `RequirementAnalyzerTest` | Unit | Greenfield, brownfield, ambiguous, clarified-with-deferral, measurable targets, vague-but-functional |
| `PipelineScenariosIntegrationTest` | Spring Boot + H2 | All five scenarios end to end through the real service bean and file system |
| `UrlShortenerServiceTest`, `UrlShortenerIntegrationTest` | Unit / MockMvc | URL shortener HTTP contract: 201, 302, 404, 400, click analytics |

Run `mvn clean test` (31 tests: 22 orchestration + 9 URL shortener). The demo scripts are acceptance tests against a running instance; they fail loudly on any unexpected status.

## Validation philosophy

- A verdict is PASSED only when evidence was actually produced. When evidence is missing, the verdict is BLOCKED; when evidence contradicts the criterion, the stage fails.
- Every acceptance criterion is traced to a test case and, where one exists, an automated test (see TEST_PLAN and the delivery notes).
- Policies are evaluated on human revisions as well as agent output.

## Failure scenarios considered

| Scenario | Handling |
|---|---|
| Transient database error | Retried within the stage budget, then the stage fails |
| Assertion failure in TESTING | Permanent; no retry; fixture deleted; pipeline halts |
| Agent hangs | 30 s timeout, treated as failure |
| Agent emits secrets or out-of-scope changes | Exit gate denies; output is discarded and compensated |
| Upstream artifact changes after approval | Approval invalidated; downstream work re-runs |
| Compensation itself fails | `ROLLBACK_FAILED` logged with "manual intervention required" |
| Runaway re-planning | Execution budget triggers a safe stop |
| Reviewer impersonating an agent | Reserved identities rejected |

## Limitations

- **No autonomous code writing.** IMPLEMENTATION reasons over the compiled codebase and proposes change sets. Applying them is a human pull request; this is a deliberate autonomy boundary.
- **Keyword-based analysis.** Unusual phrasing may be misclassified. The design fails safe toward clarification or BLOCKED rather than a false pass.
- **In-memory pipeline state.** Restarting the application loses pipelines (URL mappings persist in H2). The event log is append-only and artifacts are immutable, so event-sourced persistence is a straightforward extension.
- **Unauthenticated identities.** Reviewer and operator ids are audit labels, not authenticated principals.
- **Performance evidence is a microbenchmark,** not a load test: single-threaded, in-process, local H2.
- **Single-instance concurrency.** Locking is per pipeline within one JVM.

## What production would add

Durable event-sourced pipeline store; OIDC-authenticated reviewers with role-based approval rights; an LLM analyser behind the same contract, with evaluation sets; agents that open pull requests in a sandbox, followed by CI as the TESTING stage; distributed locking; and OpenTelemetry export of the event stream.
