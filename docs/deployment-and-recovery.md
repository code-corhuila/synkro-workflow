# Deployment and recovery contract

Workflow owns db/ and deploy/compose.yml. PostgreSQL bootstrap belongs to HU-INF-02 and must create a restricted LOGIN workflow_app. Workflow never creates a database instance or an application login. Shared PostgreSQL DNS is synkro-db on the external platform network; HTTP remains internal port 8080.

## Preparation

Provide protected absolute paths WORKFLOW_APP_PASSWORD_PATH and JWT_PUBLIC_KEY_PATH, WORKFLOW_SERVICE_TOKEN, and PLATFORM_NETWORK. The token is issued by Auth with SERVICE role and customers:read, stock:reserve, stock:release, sales:register permissions. Never use a human token for participant calls. The mounted key is Auth's public key only. Application credentials are workflow_app and its password file. FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD are administrator inputs used only by the tooling runner.

Run the owning Compose workflow-db-migrate service under the tooling profile, then start synkro-workflow. Runner mounts db/ read-only, uses Flyway 11.20.3, sets only/default/history schema workflow_schema, validates checksums and has five bounded connection retries. No database port is published. Automatic Spring/Flyway/SQL migrations are disabled. Compose evaluates both services, so migration-only callers must still supply valid placeholder application paths and a nonempty placeholder service token; these inputs are not passed to the runner.

The application requires a valid RSA public key (at least 2048 bits) at startup. Health queries the owning saga table with application permissions; it returns 503 when the store is unavailable. Participant health does not determine store health.

## Execution limits

Four workers and 64 queued IDs are defaults. Configuration allows 1–16 workers, 1–1000 queued IDs, and a 100–60000 ms scan interval. Hikari has 12 connections; JDBC acquisition is bounded to 3 seconds. Every participant connection is bounded to 2 seconds and each full response to 3 seconds with a 1 MiB maximum body. Only transient transport/429/5xx failures receive at most three persisted attempts, exponential backoff and jitter. Business rejection and authorization rejection are not retried as transient failures.

The HTTP response deadline defaults to 1000 ms and allows 1–10000 ms. Deadline expiry returns the stored RUNNING resource; it does not cancel execution. A full queue leaves the created row durable for the independent scanner. GET and duplicate POST do not launch competing workers. Shutdown stops scanning, drains workers for five seconds, then interrupts and waits at most three more seconds. Startup scans durable RUNNING rows immediately.

## Ownership and crash recovery

A dedicated PostgreSQL connection holds a two-integer session advisory lock for the saga UUID across committed intent/result writes. The same connection performs every update. It never reconnects a lease. Successful commits preserve the lock; explicit unlock occurs before pooled return. Failed unlock aborts the connection. PostgreSQL releases ownership after process/connection death or the 15-second idle session bound. A terminated old connection cannot overwrite a successor.

RUNNING with no failed_step resumes forward execution; RUNNING with failed_step resumes compensation. The private step_results execution object retains the first correlation ID, pending effect and attempt count. Persisted pending effects replay the exact stable sagaId:step participant idempotency key. Successful results commit before the next external step. Release reads the original reservation first and accepts an already released result.

A successful external effect followed by failed local persistence is recovered by replaying the owning operation, never by guessing that it did not happen. A lost Sales response must not release stock. An ambiguous operation that exhausts its attempt budget remains RUNNING with private OUTCOME_UNRESOLVED and is excluded from automated scanning. This is durable operational suspension, not a terminal success. It requires participant evidence and a reviewed recovery decision. Do not reset its budget or change rows ad hoc. FAILED compensation is terminal and has no automatic repair endpoint.

## Operational evidence and rollback

Use read-only administrator inspection in a protected session to identify saga_id, failed_step, completed_steps and sanitized internal error codes. Do not export input, step_results, SQL connection diagnostics or credentials to API clients or ordinary logs. Retain the original correlation ID for participant-owner investigation.

For unresolved Sales, clearing evidence is the participant's idempotent result for the original key, including customer, frozen lines and createdBy. If that result cannot be established, keep the reservation and escalate to Sales/architecture review. Changing recovery policy, resetting attempts or adding a repair interface needs a published design review. Exhausted compensation similarly requires an owner-reviewed operational decision.

U scripts are a controlled rollback family outside forward discovery. Destructive rollback/rebuild is demonstrated only on disposable databases: execute U005 through U002 in reverse order, deliberately remove that disposable schema's Flyway history, then U001; recreate with the owning runner. Never change an applied V migration, run clean, repair checksum drift automatically, or run these disposable steps against a shared environment. Shared rollback requires backup and explicit review.

## Evidence separation

mvn verify, tests/check_coverage.py and tests/test_deployment.py need only this repository. tests/process_acceptance.py requires Docker and cryptography 46.0.3 and runs real PostgreSQL/Workflow processes with controlled participants. tests/hybrid_acceptance.py is local-only and requires clean immutable snapshots of the exact participant pins embedded in that script. It builds real Customers, Products and Sales plus owning Products/Sales migration runners; Customers remains 501. Controlled and hybrid evidence never constitute fully real acceptance.
