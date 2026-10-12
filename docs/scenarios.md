# Scenarios

All five are automated in `PipelineScenariosIntegrationTest` and in `scripts/demo.(ps1|sh)` and the workbench UI at `/`, which save real evidence to `docs/runs/`.

## 1. Greenfield: "Build a URL shortener API that creates short links, redirects users, and tracks click analytics."

**Decomposition.** REQUIREMENTS classifies the request as GREENFIELD and extracts the capabilities CREATE, REDIRECT, ANALYTICS, plus the VALIDATION safety baseline. It produces acceptance criteria AC-CREATE, AC-REDIRECT, AC-ANALYTICS and AC-VALIDATION. The planner then expands the graph into the full SDLC DAG.

**Orchestration.** The event sequence is:

`PIPELINE_CREATED` → `STAGE_COMPLETED REQUIREMENTS` → `GRAPH_REPLANNED` → ARCHITECTURE ‖ TEST_PLAN → `PARALLEL_JOIN` → `APPROVAL_REQUESTED IMPLEMENTATION` → *(human approves)* → IMPLEMENTATION → TESTING ‖ DOCUMENTATION → `PARALLEL_JOIN` → `POLICY_ALLOWED CMP-001` → `APPROVAL_REQUESTED RELEASE_READINESS` → *(human approves)* → `PIPELINE_COMPLETED`.

**Validation.** TESTING runs every runtime criterion against the real `UrlShortenerService` bean using an isolated fixture that is deleted afterwards. ARCHITECTURE produces the API contract and data model. DOCUMENTATION writes delivery notes with an API table and a traceability matrix.

## 2. Brownfield: "Fix the existing URL shortener so unknown short codes return 404 and add regression tests."

**Decomposition.** The request is classified BROWNFIELD. The capabilities are UNKNOWN_CODE_404 and REGRESSION_SAFETY, and the existing create, redirect and analytics behaviour is pulled in as a regression baseline.

**Codebase reasoning.** ARCHITECTURE emits an impact analysis covering the controller, service, repository, `UrlNotFoundException` and `UrlExceptionHandler`, each with its layer and reason. IMPLEMENTATION inspects the compiled `url` module per capability and marks each IMPLEMENTED or GAP. The change set is checked by CHG-001.

**Validation.** AC-404 confirms that an unknown code raises `UrlNotFoundException` and that a handler maps it. AC-REGRESSION confirms the create → resolve → analytics chain is still intact. Both are traced to existing MockMvc tests.

## 3. Ambiguous: "Make the URL shortener faster and more secure for users."

**Understanding.** There is no concrete capability and no measurable target, so the request is AMBIGUOUS with explicit reasons. The planner produces only `REQUIREMENTS → CLARIFICATION`, and the pipeline enters NEEDS_CLARIFICATION. `advance` is refused.

**Clarification.** The clarification text is: "Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements."

**Re-planning.** The clarification creates REQUIREMENTS v2, which is BROWNFIELD with performance and security recorded as deferred. `GRAPH_REPLANNED` removes CLARIFICATION and adds the full SDLC graph. A fresh approval is required. Both requirement versions remain in the artifact history.

## 4. Blocked quality goal: "Build a secure URL shortener that creates short links and redirects users."

The functional scope is concrete, but "secure" has no measurable meaning. The planner therefore adds NFR_VERIFICATION, which returns a **BLOCKED** verdict: it refuses to report a pass it did not measure. CMP-001 then denies entry to RELEASE_READINESS and the pipeline ends in BLOCKED. A requirement that states measurable goals is verified instead, for example "Make redirects faster: p95 under 200 ms, and reject javascript and data URLs." In that case NFR_VERIFICATION runs an in-process microbenchmark and hostile-destination probes and records the measured evidence.

## 5. Failure and recovery (fault injection)

1. Inject a TRANSIENT_ONCE fault into TESTING. TESTING retries once (`STAGE_RETRY`) and succeeds on attempt 2.
2. Inject a PERMANENT fault into RELEASE_READINESS, then approve the release.
3. RELEASE_READINESS fails without retrying. The pipeline halts (`PIPELINE_HALTED`), and DOCUMENTATION's generated file is compensated (`ROLLBACK_COMPLETED`, status ROLLED_BACK).
4. An operator resumes the pipeline. DOCUMENTATION re-runs and produces identical content, so the release approval bound to that digest stays valid. The pipeline completes, and the metrics report retryCount=1, rollbackCount=1, and a measured `mttrMs`.
