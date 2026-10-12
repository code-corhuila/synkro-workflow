# HU-WKF-03 local execution evidence

Responsible: Ángel / AsolanoT. Published story: synkro-docs#243 (verified open); infrastructure handoff: open #239. Feature: feat/persistent-sale-orchestration. No PR, issue closure or permanent-branch promotion is included.

Starting Workflow develop SHA: 32caf8dc6b4b8b0460b85ab9c0829a3fc974ed37.
Documentation baseline: 61056c93e6e0783a9199bf56778e9494739bc458.
Production HTTP/store/lifecycle implementation: beee27dc183bc4cd9f4b9a2ffb2ee980dcb80966. Latest production correction and locally verified Java candidate: 462644e0e79bbfffdefdcdfb8ae6e25ac0122358, following failing status/rejection regressions in 287cb84 and 81b75a5. This delivery cut changes documentation only. Final branch SHAs, chronological full commits and matching remote CI results are supplied in the separate post-push report to avoid a self-referential Git SHA.

## Architecture and execution

workflow-core owns Saga, normalized SaleInput, Reservation and the explicit register-sale use case, with ports for store, participants, identifiers, clock/delay, scheduling and ownership. Its dependency tree contains only test libraries, and no Spring, JDBC or HTTP dependency. workflow-adapters owns RS256/security, HTTP projections/errors, owning participant contracts and JDBC. workflow-app composes Hikari, JDK HTTP, bounded workers/scanning and shutdown.

The documented session advisory policy commits intent before effects and results before advancing, using the same dedicated connection under ownership. Concurrent creation converges through the documented type/key unique constraint. Terminated old owners cannot reconnect or overwrite successors. The original correlation and stable sagaId:step keys survive recovery. Confirmed Sales rejection persists compensation intent and releases stock; ambiguous Sales outcomes retain stock and remain durable RUNNING. Exhausted compensation ends FAILED, with no unsafe automatic repair.

POST owner/input conflict rules are the approved generic 403/400 policy, after authentication/authorization. UUIDs and line ordering are normalized; equivalent JSON formatting is not compared as raw input. GET access remains the published ADMIN/SALESPERSON policy. Private recovery metadata is approved and appears only in step_results; no schema/status/public endpoint was added for it.

## Tests and TDD

Test-first commits precede their production fixes: core compile-red then green; JDBC compile-red then real-store green; owning HTTP-client red then green; JWT/HTTP context and behavior red then green; missing deployment runner red then green. Cleanup-recorder import-red is followed by safe implementation and two passing tests. New concurrency tests reject conflicting calls without mutation or participant invocation. Infrastructure preparation startup has its own failing regression before the fix.

Final local Maven verify: 36 tests, zero failures/errors/skips (16 core, 12 adapter including six real PostgreSQL, eight signed-JWT HTTP).
Published aggregate LINE gates: domain 100.00% (34/34; required 90%), application 98.25% (56/57; required 80%), infrastructure 87.45% (230/263; required 60%), global 90.40% (320/354; required 80%). Existing architecture and UTF-8 checks pass. Deployment contract: two tests pass. Local cleanup registration: two tests pass.

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
| WKF-09 Ambiguous outcome | BLOCKED | Tested ambiguity preserves stock; malformed rejection-envelope safety remains an internal gap |
| WKF-10 Restart | PASSED | Real JVM forward/release recovery |
| WKF-11 Persistence failure | PASSED | Core and real result-write fault |
| WKF-12 Security | PASSED | Real signed JWT, SERVICE clients, hybrid permissions |
| WKF-13 HTTP contract | BLOCKED | Public HTTP/security tests pass; participant error-envelope validation and exception classification remain internal gaps |
| WKF-14 Health | PASSED | Actual store outage 503; participant outage independent |
| WKF-15 Migrations/permissions | PASSED | Owning runner and shared-instance suite |
| WKF-16 Real integration | BLOCKED | Actual Customers 501 |

## Review and remaining evidence

Delivery cut requested by the owner: no further full local validation and no repetition of tests requiring the missing Customers operation. The final production correction passed all 36 Maven tests and the published coverage gates. Prior controlled real-process and hybrid evidence above remains separately scoped; newly added process assertions for HTTP 408/409, continuation without GET polling and graceful shutdown have not been rerun locally at this cut. Publication CI may execute these assertions automatically, and its exact-SHA result must be reported separately.

Internal Workflow follow-ups identified by static inspection, with no new failing test claimed: (1) confirmed participant rejection currently uses the operation's documented HTTP status without validating its required error envelope; malformed or contradictory rejection responses must not authorize unsafe compensation; (2) the broad transport catch classifies unexpected configuration, serialization and response-limit exceptions as transient, and must be narrowed to permitted transient failures. Each follow-up requires a failing regression before its fix. This candidate is not READY FOR MANUAL REVIEW while these protocol gaps remain.

Infrastructure handoff: preserve local integration commit a65837d0eabf9fcf4b3491c516723f6152a0be5f and its concurrent ancestor commits. The owning deploy/compose.yml supplies the Workflow service and tooling migration runner on the external platform network, with separate application/administrator credentials and no additional database instance. The infrastructure lock still needs the final published Workflow SHA labeled as a candidate; preserve all six approved complementary pins. Final candidate pinning and matching immutable-snapshot acceptance remain outstanding infrastructure work, not a completed final-SHA validation.

Owner CI is independently executable: Maven, coverage, architecture, deployment and controlled real-process recovery require no private infrastructure checkout or GitHub organization secret. Automatic push CI must be reported on its exact publication SHA; hybrid testing is local-only. Infrastructure integrates the candidate runner/service and keeps all six approved complementary pins.

Clear WKF-16 with a reviewed Customers revision implementing authenticated customers:read lookup of active/inactive/missing customers, then rerun normal and compensation against all three actual services with recorded SHAs and real database effects. Review replay/recovery clarifications and advisory namespace through the prepared synkro-docs handoff. Do not close #243 or claim complete functional end-to-end acceptance before this evidence, review and documentation publication exist. Whole HU-INF-02 additionally awaits Customers DB, Auth/Customers portals, full-platform acceptance and validation-policy review.
