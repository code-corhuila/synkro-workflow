# Workflow documentation handoff

## HU-WKF-04 main promotion — current handoff

Fresh release-candidate verification now passed121 Java tests, four guards,93.92% global LINE,44 controlled process checks and21 separate actual-participant checks, with unchanged source inputs and two cleanups. Main approval/merge, persistent rollout/scenario3 and downstream documentation closure remain pending; these source checks do not complete them.

Reuse [DOCS-98/#266](https://github.com/code-corhuila/synkro-docs/issues/266) and [HU-WKF-04/#316](https://github.com/code-corhuila/synkro-docs/issues/316); open no new issue and add no synkro-docs file. The existing-file list in the QA addendum below applies unchanged; Accepted ADR-015 stays unchanged.

- Main baseline `09a193866f05f3714a0bd1ad6d7559a9f00b3611`; branch `release/workflow-resilience` carries eight `cherry-pick -x` reapplications of commits reachable from QA merge `269a687dd10dc94d7a3043fe63075884f0da30c9`, ending at `f1540a5051d887bb20c8d433f2385c2d413186d4` (full QA → main mapping in [execution-report.md](execution-report.md)). No conflicts; the tree matches the integrated QA tree before the documentation-only changes.
- Behavior is unchanged: no version bump, new functionality, schema, migration or public contract change. Pact position is as recorded in the QA addendum (no Pact run, artifact or waiver).
- Release verification passed as recorded above. Still to record when they actually exist: publication of the main PR and its head, actual `ariel5253` approval on the delivered main head with CODEOWNERS/conversation/team-review evidence, and the user's main merge. No earlier approval transfers; no tag is requested.
- Keep separate: debt closure (TD-014/015/016 stay open until their own gates; TD-012 remains open and excluded), and the persistent deployment / coordinated lock-algorithm rollout / scenario 3, which are unexecuted. A source promotion establishes none of them.

---

## HU-WKF-04 QA promotion — handoff addendum

Reuse [DOCS-98/#266](https://github.com/code-corhuila/synkro-docs/issues/266) and [HU-WKF-04/#316](https://github.com/code-corhuila/synkro-docs/issues/316); open no new issue and add no synkro-docs file. The corrective PR18 was merged into develop as `59e3107ceff9c76bf2bacb993db556fb5a0e157f` (feature head `e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8`) by the owner, with an empty API review list; record it as an owner merge, not an accepted review. Original PR17 was closed without merge and its confirmed TDD deviation stays recorded.

QA promotion: child `qa-workflow-resilience` carried the seven `cherry-pick -x` reapplications of the corrective commits (develop → QA mapping in [execution-report.md](execution-report.md)), ending at `39fcf1cf1842a42ac6a92077fe6ee5250d76615c`, plus the documentation-only head `487d1964d6879b8cf3048e255b37b4af7880df48`. QA PR19 was merged into `qa` as `269a687dd10dc94d7a3043fe63075884f0da30c9` on 2026-11-14 19:07:38 America/Bogota (2026-11-15 00:07:38 UTC); the merge tree equals the delivered QA tree. The API review list was empty: record it as an owner integration action, not an independent approval. Fresh QA-candidate verification passed 121 Java tests, four Python guards, 93.92% global LINE, 44 controlled process checks and 21 separate actual-participant checks; the older counts below retain their original develop-delivery attribution. These runs do not establish a persistent deployment.

Pact: record that Tier 3b of `11-quality/testing-strategy.md` requires no new or regenerated Pact for a change that adds or alters no HTTP call; its Pact matrix covers the CLI-05/VEN-20 portals, not Workflow. The promoted change leaves request construction unchanged and affects lock keys, retry classification/backoff and a port decorator. No Pact run or exception exists; do not describe controlled-fixture or real-service acceptance as Pact.

Do not record a persistent deployment, closed TD-012/TD-014/TD-015/TD-016, or accepted independent review. The lock-algorithm rollout and scenario 3 remain unexecuted. The existing synkro-docs files to reconcile, unchanged from the current handoff below, are `06-data/models.md` (advisory-key section), `05-architecture/pattern-guide.md`, `05-architecture/cross-cutting.md`, `11-quality/testing-strategy.md`, `15-project-control/technical-backlog.md`, `04-requirements/traceability-matrix.md` and `15-project-control/documentation-verification.md`; Accepted ADR-015 stays unchanged.

---

## HU-WKF-04 corrective delivery — current handoff

Trace: FR-005/007; NFR-002/004/005/006/009 → [HU-WKF-04/#316](https://github.com/code-corhuila/synkro-docs/issues/316) → the concrete test-to-code map in [tdd-remediation-design.md](tdd-remediation-design.md) → the observed evidence in [execution-report.md](execution-report.md). The new test-first contract and implementation commits are separate from original PR17; its confirmed process deviation remains recorded.

Reuse [DOCS-98/#266](https://github.com/code-corhuila/synkro-docs/issues/266); create no new synkro-docs files. Reconcile the existing `06-data/models.md` advisory-key section, `05-architecture/pattern-guide.md`, `05-architecture/cross-cutting.md`, `11-quality/testing-strategy.md`, `15-project-control/technical-backlog.md`, `04-requirements/traceability-matrix.md` and `15-project-control/documentation-verification.md`. Keep Accepted ADR-015 unchanged. Record the actual corrective PR/head and actual integration status, not a planned promotion or an old review.

Current delivered behavior: WFLK namespace/XOR keys; transient transport allow-list and 429/500/502/503/504; full jitter; per-participant circuits with shared Products reserve/release. Refusal consumes one pre-persisted local attempt and retains unresolved forward reservations; refused compensation remains FAILED. Call/connect limits remain3s/2s. Do not present that conservative refusal policy as a new approved ADR or a supported recovery operation.

Executed:121 Maven tests, four Python guards,93.92% global LINE,44 controlled-process checks and21 actual-participant checks with the six immutable revisions listed in the execution report. The original43-check corrected process run is retained; the strengthened44-check run adds the real SQL attempt-count assertion. These are separate scopes, not a global21-suite run. Scenario3/fleet switch and persistent deployment remain unexecuted. TD-014/015/016 stay open until their own remaining gates are evidenced. TD-012 remains open and excluded; FR-006/NFR-009 completeness is not inferred.

---

## Historical HU-WKF-03 handoff (prior baseline; current claims above supersede stale implementation/Customers-readiness statements)

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
