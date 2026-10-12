# HU-WKF-03 local execution evidence

Responsible: Ángel / AsolanoT. Published story: synkro-docs#243 (verified open); infrastructure handoff: open #239. Feature: feat/persistent-sale-orchestration. No PR, issue closure or permanent-branch promotion is included.

Starting Workflow develop SHA: 32caf8dc6b4b8b0460b85ab9c0829a3fc974ed37.
Documentation baseline: 61056c93e6e0783a9199bf56778e9494739bc458.
Production HTTP/store/lifecycle implementation: beee27dc183bc4cd9f4b9a2ffb2ee980dcb80966. This scoped continuation starts at efe06565f93e6e56b7010dfd9bf5451f14a03d26 and resolves only the internal WKF-09/WKF-13 protocol gaps. Latest production correction and locally verified candidate: 6f947778bfd795b163ef34cf7f900c12ed3aa864, following committed/executed failing regressions in 0f4e005. Final branch SHAs, chronological full commits and matching remote CI results are supplied in the separate post-push report to avoid a self-referential Git SHA.

## Architecture and execution

workflow-core owns Saga, normalized SaleInput, Reservation and the explicit register-sale use case, with ports for store, participants, identifiers, clock/delay, scheduling and ownership. Its dependency tree contains only test libraries, and no Spring, JDBC or HTTP dependency. workflow-adapters owns RS256/security, HTTP projections/errors, owning participant contracts and JDBC. workflow-app composes Hikari, JDK HTTP, bounded workers/scanning and shutdown.

The documented session advisory policy commits intent before effects and results before advancing, using the same dedicated connection under ownership. Concurrent creation converges through the documented type/key unique constraint. Terminated old owners cannot reconnect or overwrite successors. The original correlation and stable sagaId:step keys survive recovery. Confirmed Sales rejection persists compensation intent and releases stock; ambiguous Sales outcomes retain stock and remain durable RUNNING. Exhausted compensation ends FAILED, with no unsafe automatic repair.

POST owner/input conflict rules are the approved generic 403/400 policy, after authentication/authorization. UUIDs and line ordering are normalized; equivalent JSON formatting is not compared as raw input. GET access remains the published ADMIN/SALESPERSON policy. Private recovery metadata is approved and appears only in step_results; no schema/status/public endpoint was added for it.

## Tests and TDD

Test-first commits precede their production fixes: core compile-red then green; JDBC compile-red then real-store green; owning HTTP-client red then green; JWT/HTTP context and behavior red then green; missing deployment runner red then green. Cleanup-recorder import-red is followed by safe implementation and two passing tests. New concurrency tests reject conflicting calls without mutation or participant invocation. Infrastructure preparation startup has its own failing regression before the fix.

Final local Maven verify: 43 tests, zero failures/errors/skips (16 core, 19 adapter including six real PostgreSQL, eight signed-JWT HTTP). The 13 affected HTTP participant tests passed after six failing regressions on the previous production implementation. The controlled real-process regression also failed before the fix at the new committed-Sales invalid-envelope window, with disposable cleanup, then passed after the fix.
Published aggregate LINE gates: domain 100.00% (34/34; required 90%), application 98.11% (52/53; required 80%), infrastructure 89.00% (267/300; required 60%), global 91.21% (353/387; required 80%). Existing architecture and UTF-8 checks pass. Deployment contract and cleanup registration have two passing tests each in the earlier cut; automatic final-SHA CI independently executes them again.

Real PostgreSQL demonstrates five owning migrations, first apply, zero-change replay, workflow_schema history, missing-login rejection, copied-file checksum rejection, descending disposable rollback/rebuild, real application permissions and backend termination/stale-owner rejection. Six concurrent JDBC connections and two independent Workflow JVMs prove unique resource creation and exclusive effects.

Controlled process evidence uses actual Workflow JVMs and PostgreSQL with controlled HTTP participants: normal completion; definitive failure/release; committed Sales response loss; JVM death after reservation/sale/release commits; local reservation-result write failure; startup recovery; durable ambiguous-sale suspension; store-only health and PostgreSQL stop/start; no human/SERVICE tokens in stored JSON. Owned containers, networks, volumes and test images are removed. Protected CODEOWNERS/historical blobs match the infrastructure baseline.

## Participant evidence — separated

| Scope | Result |
|---|---|
| Controlled | Complete normal/compensation/failure/crash scenarios pass against real Workflow/store processes. |
| Actual Products | At b6b379b314716e201833be0d854953a69452559f, create/read/release reservations pass against owning Products DB migrations. |
| Actual Sales | At 70bdbaba3783bfb974c338b8a460e6e9b7e5c8dd, idempotent registration passes against the real owning Liquibase runner and authenticated MongoDB replica set. |
| Hybrid | Controlled Customers + actual Products/Sales completes registration with frozen prices; actual Sales arithmetic rejection causes actual Products release and restores stock. |
| Fully real | BLOCKED: built Customers at 5dddfc84f885db8932e4e3a567ed83c83612cfe9 still returns structural empty 501 for GET /api/v1/customers/{id}. |

Products DB pin: 0beae79920d9af83865bdebaf6b316a2b2a57592. Sales DB pin: 9cfbde7db4bea7bacad70370c60f91f8eb917c04. Immutable participants are clean independent Git snapshots; their owning repositories were not modified.

## Acceptance mapping

| Criterion | Status | Evidence scope |
|---|---|---|
| WKF-01 Happy path | PASSED | Core, controlled, hybrid |
| WKF-02 Idempotent startup | PASSED | Core, HTTP, JDBC/process |
| WKF-03 Concurrency | PASSED | Core, PostgreSQL, two JVMs |
| WKF-04 Customer rejected | PASSED | Controlled/core; no effects |
| WKF-05 Reservation rejected | PASSED | Controlled/core; no sale |
| WKF-06 Compensation | PASSED | Controlled/process and hybrid |
| WKF-07 Compensation failure | PASSED | Bounded core failure, FAILED |
| WKF-08 Lost response | PASSED | Core and interrupted processes |
| WKF-09 Ambiguous outcome | PASSED | Validated rejection envelope; malformed committed-Sales rejection retains reservation/RUNNING across real restart |
| WKF-10 Restart | PASSED | Real JVM forward/release recovery |
| WKF-11 Persistence failure | PASSED | Core and real result-write fault |
| WKF-12 Security | PASSED | Real signed JWT, SERVICE clients, hybrid permissions |
| WKF-13 HTTP contract | PASSED | Public HTTP/security plus strict participant envelope validation and non-retryable local/contract failures |
| WKF-14 Health | PASSED | Actual store outage 503; participant outage independent |
| WKF-15 Migrations/permissions | PASSED | Owning runner and shared-instance suite |
| WKF-16 Real integration | BLOCKED | Actual Customers 501 |

## Review and remaining evidence

The previous delivery cut efe06565f93e6e56b7010dfd9bf5451f14a03d26 passed its three exact-SHA CI jobs and retained two statically identified protocol gaps. This continuation is explicitly authorized only to resolve those gaps. It revalidated affected tests, all 43 Maven tests, coverage and controlled real PostgreSQL/JVM recovery, including HTTP 408/409, continuation without GET polling, graceful shutdown and the new invalid-rejection interruption case. No actual Customers or fully real acceptance was repeated. Hybrid evidence remains historical and separately scoped.

Both internal follow-ups are resolved with executed red-to-green regressions. Rejection now requires the operation's allowed status, JSON media type, required typed fields, matching correlation, status/error correspondence and valid optional details; duplicate/trailing JSON is invalid. Invalid envelopes do not confirm that Sales rejected a committed operation. Request preparation isolates credential/configuration/serialization failures before transport. Asynchronous protocol/response-limit failures and TLS/hostname/programming failures are non-retryable; only permitted transient HTTP states, wait timeout and transport I/O retry within the existing bound. These failures preserve recoverable RUNNING and reservation, without unsafe compensation or new schema/public policies. Applicable local checks pass; READY FOR MANUAL REVIEW of the implemented scope additionally requires successful CI on the final published SHA. WKF-16 remains explicitly BLOCKED.

Infrastructure handoff: local integration commit a65837d0eabf9fcf4b3491c516723f6152a0be5f and its concurrent ancestor commits remain preserved and untouched by this continuation. The owning deploy/compose.yml supplies the Workflow service and tooling migration runner on the external platform network, with separate application/administrator credentials and no additional database instance. This continuation does not pin or approve a Workflow delivery in infrastructure; preserve all six approved complementary pins. Any later reviewed candidate pinning and matching immutable-snapshot acceptance remain separate infrastructure work, not a completed final-SHA validation.

Owner CI is independently executable: Maven, coverage, architecture, deployment and controlled real-process recovery require no private infrastructure checkout or GitHub organization secret. Automatic push CI must be reported on its exact publication SHA; hybrid testing is local-only. Infrastructure integrates the candidate runner/service and keeps all six approved complementary pins.

Clear WKF-16 with a reviewed Customers revision implementing authenticated customers:read lookup of active/inactive/missing customers, then rerun normal and compensation against all three actual services with recorded SHAs and real database effects. Review replay/recovery clarifications and advisory namespace through the prepared synkro-docs handoff. Do not close #243 or claim complete functional end-to-end acceptance before this evidence, review and documentation publication exist. Whole HU-INF-02 additionally awaits Customers DB, Auth/Customers portals, full-platform acceptance and validation-policy review.
