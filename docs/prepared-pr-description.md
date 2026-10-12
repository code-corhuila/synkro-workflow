# Prepared PR description (no PR created)

Title: feat(workflow): persist and recover register-sale orchestration

Workflow's placeholder saga endpoints become a durable register-sale orchestration: validate active customer, reserve all lines at frozen prices, and register an idempotent sale using the initiating caller. PostgreSQL stores intent and progress, and session ownership prevents concurrent/stale execution. Startup resumes forward work or compensation; ambiguous Sales outcomes retain stock and durable RUNNING state.

The owning migration runner uses shared PostgreSQL workflow_schema and restricted application grants. HTTP verifies Auth RS256 claims/roles locally, exposes only SagaResponse fields and safe errors, and returns durable RUNNING at the response deadline.

Participant rejection requires both the operation's allowed status and a valid, matching shared error envelope. A malformed Sales rejection cannot release potentially committed stock. Transport retries exclude contract violations and local configuration, serialization and programming failures; these preserve durable suspended progress without inappropriate retry.

Validation: Maven reactor/architecture/UTF-8 tests; published coverage gates; real PostgreSQL migrations, concurrent ownership, permissions, checksum and rollback/rebuild; controlled HTTP/process failures and interrupted JVM effect windows. Local hybrid evidence uses actual Products/Sales and controlled Customers. Actual Customers remains 501, so fully real acceptance is blocked and HU #243 stays open. See execution-report.md for exact revisions/results and synkro-docs-handoff.md for unpublished clarifications.

Required reviewers: Workflow owner and architecture/security/data owners for replay semantics, private recovery metadata and advisory ownership policy. No permanent branch promotion is included.
