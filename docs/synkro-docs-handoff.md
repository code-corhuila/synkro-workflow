# Prepared documentation handoff — HU-WKF-03 / HU-INF-02

This file is a proposal for the documentation owners; synkro-docs was not modified. Source baseline: 61056c93e6e0783a9199bf56778e9494739bc458. HU-WKF-03 is published as open issue #243; HU-INF-02 remains open #239. Responsible: Ángel (AsolanoT).

## Decisions requiring published review

1. Record the user-approved POST replay clarification: authenticate/authorize first; another caller's key yields generic 403 FORBIDDEN; same caller/conflicting validated input yields generic 400 VALIDATION_ERROR; equivalent normalized input yields 200 existing saga. UUIDs are canonicalized and line order normalized; duplicate products are rejected. Rejected replays neither mutate rows nor invoke participants, and expose no stored owner/input/saga ID. No 409 and no GET authorization change.
2. Record approved private step_results recovery metadata: original correlationId, pendingStep, attempts. No columns, statuses or public fields added.
3. Register Workflow's two-integer advisory key namespace separately from Auth's documented bigint lock. Review conservative collision serialization and the dedicated-connection recovery policy.
4. Record bounded durable attempts and operational suspension of ambiguous outcomes. RUNNING/OUTCOME_UNRESOLVED is private diagnostic state, not a new status; no unsafe compensation or automatic FAILED repair. Approve an operational procedure only after participant evidence exists.
5. Publish the owning runner/application credential boundary, workflow_schema grants and restricted NOLOGIN roles under current shared-instance ADR-012 topology. No workflow-db instance.
6. Record the WKF-09/WKF-13 protocol correction: only an operation-allowed status plus its valid shared Error envelope confirms rejection. Validate JSON media type, a single unambiguous object, status/error correspondence, original traceId and optional ErrorDetail shapes. Invalid rejection envelopes never authorize compensation. Restrict retries to permitted HTTP transient states, request timeout and transport I/O; classify protocol/response-limit and local configuration/serialization/programming failures without retries. LOCAL_FAILURE is private port behavior, not a new public or database status.

## Requirement → HU → test → code

| Requirement | HU | Executable evidence | Owning implementation |
|---|---|---|---|
| Persist before effects and after results | WKF-03 | RegisterSaleTest; JdbcSagaStoreTest; process store-write fault | RegisterSale; JdbcSagaStore |
| Unique normalized replay and owner/input policy | WKF-03 | core replay/concurrent starts; JDBC concurrent creation; WorkflowHttpTest | SaleInput; RegisterSale.start; SagaController |
| Exclusive execution and stale owner rejection | WKF-03 | JdbcSagaStoreTest concurrent connections/backend termination | JdbcSagaStore session Lease |
| Customer → reservation → sale, frozen prices | WKF-03 | core normal; HttpSaleParticipantsTest; controlled/hybrid scripts | RegisterSale; HttpSaleParticipants |
| Lost response and interruption | WKF-03 | core lost response/write failures; process kill after reserve/sale/release commit | pending intent, stable key and startup scanner |
| Compensation intent and failed compensation | WKF-03 | core confirmed rejection/exhaustion; interrupted release process | RegisterSale compensation branch |
| WKF-09 valid rejection evidence; ambiguous Sales retains stock | WKF-03 | HttpSaleParticipantsTest malformed/contradictory envelopes; process committed sale + invalid 422 and restart | HttpSaleParticipants.validateRejection; RegisterSale unresolved branch |
| WKF-13 precise retry classification and safe local failures | WKF-03 | HttpSaleParticipantsTest local serialization/configuration, TLS/protocol, oversized response, bounded transport attempts | request preparation; asynchronousFailure; ParticipantFailure.Kind |
| RS256, required claims, roles, safe errors | WKF-03 | WorkflowHttpTest real signed JWTs and UTF-8 envelopes | SecurityConfiguration; HttpErrors; ErrorAdvice |
| HTTP deadline with durable continuation | WKF-03 | WorkflowHttpTest and process startup recovery | SagaController; BackgroundExecution |
| Store-only public health | WKF-03 | HTTP health; real PostgreSQL stop/start | StoreHealth; JdbcSagaStore.healthy |
| Owning migration/no-op/checksum/rollback | WKF-03 + INF-02 | JDBC migrations; process owning runner; root PostgreSQL suite | db/; deploy/compose.yml |
| App permissions and shared history isolation | WKF-03 + INF-02 | real application privilege denials; root coexistence | V001–V005; root composition |
| Immutable candidate integration and six pins | INF-02 | root test_workflow_integration; local verifier | dependencies.lock.json; verify_local.py |
| Fully real acceptance | WKF-03 + INF-02 | actual Customers GET readiness probe | BLOCKED until Customers implementation and real rerun |

Review does not close either HU. Fully real acceptance, owner review and documentation publication remain separate requirements. The Workflow feature revision is a candidate, never a permanent-branch promotion.

Continuation starts at efe06565f93e6e56b7010dfd9bf5451f14a03d26. The two internal protocol gaps are resolved by 6f947778bfd795b163ef34cf7f900c12ed3aa864 after the committed/executed red regressions in 0f4e005. Maven has 43 passing tests and global LINE coverage 91.21%; the new controlled real-process case preserves the reservation and recoverable RUNNING after the committed Sales invalid rejection and restart. Infrastructure must not label this candidate as an approved delivery or change its lock in this continuation. WKF-16 remains BLOCKED by the previously verified actual Customers 501; its fully real acceptance was not repeated.
