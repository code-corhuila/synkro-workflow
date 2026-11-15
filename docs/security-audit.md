# Sale security audit

The application sends audit events through the `AuditTrail` output port. The core
depends on this interface; the SLF4J adapter serializes its explicit fields as JSON.
The dedicated console appender emits each event as one JSON line. Logging failures
do not change a saga's outcome.

The allowed fields are `timestamp`, `service`, `correlationId`, `sagaId`, `step`,
`actorId`, `action` and `result`. Unknown optional values are omitted. Actor and
saga identifiers are UUIDs; steps, actions and results use fixed vocabularies.
Bodies, credentials, customer names, identity documents and exception details are
never accepted by the audit port. The opaque correlation value is JSON encoded,
preserved as required by the HTTP contract, and restored from persisted execution
state when background work resumes. It is operational metadata, not a place for
customer data or credentials.

The authenticated human JWT subject remains the saga initiator and the `createdBy`
sent to Sales, regardless of body-supplied authorship fields. Tests cover starts,
replays, refusals, every completed step, compensation, unresolved outcomes and
background recovery. The HTTP participant capture uses controlled participants;
the cross-service harness in Sales additionally runs actual Workflow and Sales
processes against migrated PostgreSQL and MongoDB, with controlled Customers and
Products. These scopes are distinct.

Related requirements: FR-005/FR-007 and NFR-004/NFR-009; issue
[286](https://github.com/code-corhuila/synkro-docs/issues/286). Canonical design
documentation reconciliation is deferred to a separate change.
