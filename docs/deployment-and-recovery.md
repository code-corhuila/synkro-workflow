# Deployment and recovery contract

Workflow owns db/ and deploy/compose.yml. PostgreSQL bootstrap belongs to HU-INF-02 and must create a restricted LOGIN workflow_app. Workflow never creates a database instance or an application login. Shared PostgreSQL DNS is synkro-db on the external platform network; HTTP remains internal port 8080.

## Preparation

Provide protected absolute paths WORKFLOW_APP_PASSWORD_PATH and JWT_PUBLIC_KEY_PATH, WORKFLOW_SERVICE_TOKEN, and PLATFORM_NETWORK. The token is issued by Auth with SERVICE role and customers:read, stock:reserve, stock:release, sales:register permissions. Never use a human token for participant calls. The mounted key is Auth's public key only. Application credentials are workflow_app and its password file. FLYWAY_URL, FLYWAY_USER and FLYWAY_PASSWORD are administrator inputs used only by the tooling runner.

Run the owning Compose workflow-db-migrate service under the tooling profile, then start synkro-workflow. Runner mounts db/ read-only, uses Flyway 11.20.3, sets only/default/history schema workflow_schema, validates checksums and has five bounded connection retries. No database port is published. Automatic Spring/Flyway/SQL migrations are disabled. Compose evaluates both services, so migration-only callers must still supply valid placeholder application paths and a nonempty placeholder service token; these inputs are not passed to the runner.

The application requires a valid RSA public key (at least 2048 bits) at startup. Health queries the owning saga table with application permissions; it returns 503 when the store is unavailable. Participant health does not determine store health.

## Execution limits

Four workers and 64 queued IDs are defaults. Configuration allows 1–16 workers, 1–1000 queued IDs, and a 100–60000 ms scan interval. Hikari has 12 connections; JDBC acquisition is bounded to 3 seconds. Every participant connection is bounded to 2 seconds and each full response to 3 seconds with a 1 MiB maximum body. Only classified transient transport failures and HTTP 429/500/502/503/504 receive at most three persisted attempts. Full jitter draws the wait from 0..min(2000, 100 * 2^(attempt-1)) ms, inclusive. HTTP 408 is INVALID_RESPONSE and is not retried. Business rejection and authorization rejection are not retried as transient failures.

The HTTP response deadline defaults to 1000 ms and allows 1–10000 ms. Deadline expiry returns the stored RUNNING resource; it does not cancel execution. A full queue leaves the created row durable for the independent scanner. GET and duplicate POST do not launch competing workers. Shutdown stops scanning, drains workers for five seconds, then interrupts and waits at most three more seconds. Startup scans durable RUNNING rows immediately.

## Ownership and crash recovery

A dedicated PostgreSQL connection holds the two-integer session advisory lock (0x57464C4B, XOR of the UUID four 32-bit words) across committed intent/result writes. Auth refresh retains its distinct 0x52544F4B namespace. A same-namespace hash collision serializes unrelated sagas; database rows still use their full saga UUID. The same connection performs every update. It never reconnects a lease. Successful commits preserve the lock; explicit unlock occurs before pooled return. Failed unlock aborts the connection. PostgreSQL releases ownership after process/connection death or the 15-second idle session bound. A terminated old connection cannot overwrite a successor.

RUNNING with no failed_step resumes forward execution; RUNNING with failed_step resumes compensation. The private step_results execution object retains the first correlation ID, pending effect and attempt count. Persisted pending effects replay the exact stable sagaId:step participant idempotency key. Successful results commit before the next external step. Release reads the original reservation first and accepts an already released result.

A successful external effect followed by failed local persistence is recovered by replaying the owning operation, never by guessing that it did not happen. A lost Sales response must not release stock. An ambiguous operation that exhausts its attempt budget remains RUNNING with private OUTCOME_UNRESOLVED and is excluded from automated scanning. This is durable operational suspension, not a terminal success. It requires participant evidence and a reviewed recovery decision. Do not reset its budget or change rows ad hoc. FAILED compensation is terminal and has no automatic repair endpoint.

## Operational evidence and rollback

Use read-only administrator inspection in a protected session to identify saga_id, failed_step, completed_steps and sanitized internal error codes. Do not export input, step_results, SQL connection diagnostics or credentials to API clients or ordinary logs. Retain the original correlation ID for participant-owner investigation.

For unresolved Sales, clearing evidence is the participant's idempotent result for the original key, including customer, frozen lines and createdBy. If that result cannot be established, keep the reservation and escalate to Sales/architecture review. Changing recovery policy, resetting attempts or adding a repair interface needs a published design review. Exhausted compensation similarly requires an owner-reviewed operational decision.

U scripts are a controlled rollback family outside forward discovery. Destructive rollback/rebuild is demonstrated only on disposable databases: execute U005 through U002 in reverse order, deliberately remove that disposable schema's Flyway history, then U001; recreate with the owning runner. Never change an applied V migration, run clean, repair checksum drift automatically, or run these disposable steps against a shared environment. Shared rollback requires backup and explicit review.

## Evidence separation

mvn verify, tests/check_coverage.py and tests/test_deployment.py need only this repository. tests/process_acceptance.py requires Docker and cryptography 46.0.3 and runs real PostgreSQL/Workflow processes with controlled participants. tests/hybrid_acceptance.py is local-only and requires clean immutable snapshots of the exact participant pins embedded in that script. It builds real Customers, Products and Sales plus owning Products/Sales migration runners; Customers remains 501. Controlled and hybrid evidence never constitute fully real acceptance.

Confirmed rejection requires the owning operation's published status AND a valid shared Error envelope: Customers lookup 404; reservation creation and Sales registration 400/422; reservation read/release 404. The response must be application/json, a single JSON object without duplicate fields/trailing JSON, with textual error/message/traceId, the original request correlation, and valid field/message details when supplied. Status 400 accepts VALIDATION_ERROR, 404 NOT_FOUND, and 422 BUSINESS_RULE_VIOLATION or INVALID_STATUS_TRANSITION. A missing, malformed or contradictory envelope is INVALID_RESPONSE, never evidence that Sales did not commit; retain its reservation and RUNNING/OUTCOME_UNRESOLVED without retrying a contract failure.

Only HTTP 429/500/502/503/504, a bounded request wait timeout, and recognized EOF/connect/socket timeout or reset/closed-channel failures permit bounded retry, including plain IOException and Completion/Execution wrappers. Arbitrary I/O and negative nested TLS/DNS/address/programming causes do not permit retry; cause cycles terminate. Protocol violations and the 1 MiB response limit are INVALID_RESPONSE. TLS/hostname configuration failures, invalid URI/header configuration, serialization, unexpected programming failures, cancellation and interruption are non-retryable LOCAL_FAILURE; interruption preserves the thread flag. Serialization/configuration errors occur before sending and cannot be mistaken for transport failures. LOCAL_FAILURE is an internal port classification, with no new stored/public status, schema or endpoint. Neither local failures nor authorization failures confirm a rejected sale. No exception message, response body, URL or credential is copied to diagnostics or the public error envelope.

Structural 501 and undocumented statuses are not transient retries or proof of a failed sale. Unknown Sales 4xx, including HTTP 408, preserves RUNNING/OUTCOME_UNRESOLVED without automatic retry or release. Fixing a local configuration or participant contract does not automatically reset suspended attempts or release stock; follow the owner-reviewed investigation procedure above.

## Participant circuits

CircuitProtectedParticipants decorates the existing participant port with Customers, Products and Sales circuits. Products reserve and release share one circuit. Three transient failures open it; after 15 seconds only one probe is admitted. Validated success resets/closes; transient probe failure reopens for another cooldown. Rejection, authorization, contract/local failures and unexpected exceptions are neutral and free the probe without claiming availability. Generation-scoped single-settlement admissions prevent stale outcomes changing a recovered circuit. No monitor spans participant I/O, and circuit state is neither persisted nor projected.

CIRCUIT_OPEN is a private non-rejection failure. The use case has already persisted its intent, so refusal consumes one local attempt and defers the forward step (see below); it never authorizes stock release. Refused release follows the existing non-transient compensation path to FAILED with the reservation retained. Call/connect timeouts remain 3s/2s. These conservative outcomes remain subject to independent review; they introduce no recovery endpoint or row-reset permission.

## Deferred retry of unavailable participants

A forward step whose participant stays unavailable (three transient failures, or an open circuit) is not suspended for an operator. The saga stays RUNNING with the private marker RETRY_DEFERRED, keeps its pending step, reservation and step key, and records the next due time in step_results.execution (deferrals, retryAtEpochMilli). The background scan only selects it once that time has passed; each due round gets a fresh budget of three attempts and replays the same `<sagaId>:<step>` Idempotency-Key, so a sale or reservation committed before the outage is returned, never duplicated.

Waits double from 30 seconds up to a 15-minute ceiling for at most 10 deferred rounds per step (about 1.5 hours). After the last round the step moves to RUNNING/OUTCOME_UNRESOLVED for operator review. Untrustworthy outcomes (HTTP 408, undocumented statuses, invalid envelopes, authorization or local failures, or a rejection after an ambiguous attempt) still go straight to operator review and are never resumed or compensated automatically.

GET /api/v1/sagas/{id} exposes the public field `recovery` on RUNNING sagas: AUTOMATIC_RETRY while a retry is scheduled and OPERATOR_REQUIRED for operator review. The private markers, attempts and due time are not projected. Rows written before this change load with zero deferrals; existing OUTCOME_UNRESOLVED rows remain under operator review. Decision record: [ADR-025](https://github.com/code-corhuila/synkro-docs/blob/main/05-architecture/decisions/records/ADR-025-deferred-retry-of-unavailable-saga-participants.md).

## Coordinated lock-algorithm switch — not executed

1. Stop admission of new sale work and stop every legacy Workflow executor and scanner. Identify all running instances, not just the local Compose service.
2. Wait for shutdown and verify no legacy process or in-flight ownership remains. The idle-session bound alone is not proof that all executors stopped. If quiescence cannot be established, stop the procedure.
3. Start only the new-algorithm instances. Verify one owner for a saga across committed transitions, preserved stored state and no overlapping legacy instance.
4. Rollback uses the same stop-all and prove-quiescence boundary before starting only legacy instances. Never run both algorithms against active sagas. Do not reset attempts, alter rows or release reservations as part of rollback.

Record the actual operator, environment, selected revisions, inventory, observations and outcome when this procedure is authorized and performed. Disposable tests establish lock behavior, not an executed fleet switch or persistent deployment. Scenario3 remains pending.
