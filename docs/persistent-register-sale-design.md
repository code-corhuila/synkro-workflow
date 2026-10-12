# HU-WKF-03 execution and recovery design

Prepared before production changes, 2026-10-12. Sources: synkro-docs main
`61056c93e6e0783a9199bf56778e9494739bc458`, ADR-002/006/007/009/012,
06-data/models.md, owning OpenAPI contracts, authentication, testing strategy,
TDD guide and DoD. HU-WKF-03 is published as synkro-docs#243 and remains open.
ADR-009 supersedes ADR-007's separate instance: only workflow_schema in the
shared instance is permitted. No payment, broker, outbox or sale deletion.

## Concurrency alternatives

1. A lease plus monotonically increasing fencing version allows short database
   transactions. It needs reviewed schema changes, lease renewal, and participant
   fencing support. A local timestamp alone cannot stop a stale external effect.
2. PostgreSQL row locking on the existing saga row permits serialization without
   new columns. Each execution slice uses a fresh READ COMMITTED transaction,
   SELECT FOR UPDATE SKIP LOCKED, rereads progress, performs at most one external
   call, writes its result and commits. Other executors skip the locked row.
   Locks disappear on connection/process failure. Transactions, connections and
   participant requests must have bounded timeouts; a stale connection cannot
   commit over a successor. Stable participant idempotency keys protect external
   effects after a database connection fails while an HTTP request is in flight.

3. A PostgreSQL session advisory lock keeps exclusive ownership across separate
   intent/result commits without schema changes. Use the two-integer overload,
   derived from the UUID, which has a separate key space from Auth's reserved
   single-bigint key. Hash collisions only serialize unrelated sagas. One
   dedicated connection owns the lock and all writes; never reconnect a lease.

Choose option 3 after refining the ambiguous-effect boundary. Row locks release
at commit, so they cannot safely bridge a committed intent and the next call
without a second protocol. Session ownership survives commits but disappears
on connection/process failure. Bounded HTTP/connect/socket/idle-session limits
prevent abandoned sessions persisting indefinitely. A stale connection cannot
save after lock loss; idempotent participant operations protect stale external
requests. An in-memory deduplication set only optimizes capacity. SELECT/UPDATE
permissions suffice. Reserve the two-integer key derivation in the docs handoff.

## Boundaries and recovery

Creation commits before any external call. INSERT ON CONFLICT followed by a
separate SELECT converges concurrent starts under the documented unique
(saga_type,idempotency_key) constraint. Store only customer/lines and validated
initiated_by; never Bearer credentials. Forward steps are explicitly
validate-customer, reserve-stock, register-sale. Successful results commit
before advancing. Reservation prices pass unchanged to Sales; Sales computes
amounts. No compensation exists for customer validation or sale registration.

Before a mutating external attempt, commit an unresolved-effect marker in the
existing private step_results JSON. This is essential: a crash after participant
commit but before local commit is indistinguishable from a lost HTTP response.
Repeat the same operation using <sagaId>:<step>. A valid replay resolves the
marker. A later 4xx cannot prove an earlier ambiguous operation never committed;
leave RUNNING and require participant/operational evidence rather than release
possibly sold stock. Malformed successful responses are ambiguous too.

A definitive rejection on the first unambiguous attempt records failed_step
and compensation intent in a committed slice. RUNNING plus failed_step resumes
compensation. If no reservation exists, compensation is vacuous and ends in
COMPENSATED. Release is repeatable by reservation ID, including after response
loss. Exhausted compensation ends FAILED; preserve original forward failed_step
and safe private compensation classification. FAILED is never auto-resumed.

Retries are limited to network failures, 429 and 5xx, with exponential backoff
and jitter. Forward ambiguous outcomes remain recoverable RUNNING after a
bounded attempt burst; later recovery bursts use the same keys and frozen input.
There is no manufactured terminal success. Authorization failures are not
transient retries. Diagnostics use fixed classifications, never exception text,
participant bodies, connection strings or credentials.

## Scheduling and lifecycle

HTTP response deadline is independent of durable execution. Creation schedules
work; the response waits only its configured deadline and then reads current
state. A timeout does not cancel the worker. Replayed POST and GET do not create
executors. Startup and periodic bounded scans schedule RUNNING rows independently
of polling. Use bounded worker count, queue, scan pages and scheduled tasks.
Shutdown stops admission/scans, waits a bounded interval, interrupts remaining
work and closes connections. Startup recovers both forward and compensation.
Test real process death during unresolved effects and release, not only between
completed steps. Participant idempotency is required even under row locking.

## Review dependencies

User-approved clarification, 2026-10-12: authenticate and authorize before POST
replay resolution; 403 FORBIDDEN for another caller and 400 VALIDATION_ERROR for
conflicting validated input. Generic errors expose no input/owner/saga identifier;
rejected replays perform no writes or participant calls. Equivalent input is
customer UUID plus lines sorted by product UUID with positive integer quantity;
JSON property order and line order do not change identity. Reject repeated product
lines, consistent with the owning reservation contract. Same caller/equivalent
input receives the existing saga, 200, without effects. No 409. GET authorization
remains the published ADMIN/SALESPERSON access, unchanged by this clarification.

User approved private recovery metadata, 2026-10-12. Namespace:
step_results.execution (correlationId, pendingStep, attempts).
Persist pendingStep before each mutating call; a preexisting pendingStep means
the prior effect is ambiguous. A definitive rejection from a fresh attempt can
compensate; a rejection after a preexisting pendingStep cannot. Attempts persist
before the call so process death cannot reset the retry budget. After exhaustion,
forward ambiguity stays RUNNING with OUTCOME_UNRESOLVED; automatic loops do not
reset its budget. This changes no schema/public fields and goes to docs review.
Do not change published input semantics or add schema columns to hide this gap.

## Verification boundaries

Controlled HTTP participants cover all effect windows deterministically. Real
PostgreSQL proves locking, unique creation, commit failures, permissions,
isolated Flyway history, checksum protection and disposable rebuild. Hybrid
evidence uses real Products/Sales with controlled Customers and is labeled so.
Fully real acceptance requires actual Customers GET and real registration and
compensation; a 501 never counts as a pass. Review and merge remain separate.

Starting Workflow: 32caf8dc6b4b8b0460b85ab9c0829a3fc974ed37.
Starting infrastructure: 8e441d2e43359df3a2f92a06d4764fde2572dc83.
Infrastructure's existing test-first commit and six approved complementary pins
must be preserved; any Workflow integration pin is candidate until reviewed.
