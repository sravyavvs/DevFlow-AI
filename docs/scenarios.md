# Assessment scenarios

## Greenfield

Requirement: build a URL shortener API with redirects and click analytics.

- Analyze requirement and assumptions.
- Run API design and acceptance planning in parallel.
- Join after both artifacts are produced.
- Require reviewer approval before in-process service checks.
- Validate URL creation, resolution, click analytics, and invalid destination rejection.

## Brownfield

Requirement: fix the existing URL shortener so unknown short codes return 404 and add regression tests.

- Impact artifact identifies the existing controller, service, repository, exception handler, and integration tests.
- Regression acceptance criteria explicitly include unknown-code not-found behavior and existing create/resolve/analytics behavior.
- Runtime checks exercise the service behavior and its exception contract. This is not a claim that a full test suite or source patch was generated.

## Ambiguous

Requirement: make the URL shortener faster and more secure.

- Initial state is `NEEDS_CLARIFICATION`.
- Approval and execution are blocked.
- Reviewer supplies observable behavior and measurable acceptance criteria via the clarification endpoint.
- The system creates a revised graph and artifact, logs replanning, and requires a fresh approval.
- Performance/security criteria remain `NOT_RUN` unless explicitly scoped out or a dedicated benchmark/security test is implemented; no false `PASSED` status is emitted.
