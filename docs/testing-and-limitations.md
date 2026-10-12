# Testing, failure behavior, and limitations

Run `mvn clean test` before the demo. Tests cover planning join, approval/rejection, requirement-derived brownfield criteria, ambiguity/clarification, unsupported non-functional requirements, bounded transient retries, compensation, metrics semantics, and integration execution.

The workflow's validation report is generated from the checks actually executed. A requirement containing performance/security goals cannot be `PASSED` merely because URL-shortener smoke checks succeeded. It becomes `BLOCKED` unless those goals are explicitly deferred or dedicated verification is available. A failed supported check yields `FAILED`; checks not run remain `NOT_RUN`.

The retry mechanism only retries `TransientDataAccessException` on a read-only analytics query. It does not retry validation assertions or broad `RuntimeException`s. Rollback is compensating deletion of the isolated mapping created by the smoke check, not a database-wide transaction rollback.

H2 stores workflow snapshots, events, and artifacts across restarts. Local H2 and process-level synchronization do not provide multi-instance coordination, authenticated reviewer identity, or production-grade access control. Reviewer IDs are audit labels supplied by the API caller. No code generation, patching, sandbox, security scanner, load testing, or deployment is implemented.
