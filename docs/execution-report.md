# HU-WKF-03 local execution evidence

> The original HU-WKF-03 records below are historical. Current HU-WKF-04 local results and limits are in
> [Verified local implementation](#hu-wkf-04--verified-local-implementation-2026-11-14); older open/blocked statements retain their original source scope.

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

## HU-WKF-04 — Verified local implementation (2026-11-14)

Owner: SantiagoPlazas2005. Branch: `feat/workflow-resilience-alignment`, based on develop
`2aa2f25f4411f0c2bbdbf7cbfb57701fcee7c7d7`. Claude Code prepared the implementation and tests;
Codex reviewed the changes, corrected two defects demonstrated by regressions and executed the checks below.
This execution record was captured against the verified working tree before publication. The feature PR
records the resulting commit and review status; no merge, tag, permanent-environment rollout, independent human
approval or HU/debt closure is claimed by these execution results.

| Scope | Command / evidence | Actual result |
|---|---|---|
| Maven reactor, including actual PostgreSQL | `mvn -B verify`, JDK 21.0.12 / Maven 3.9.16 | 88 tests: core 20, adapters 60 (including ten real-PostgreSQL tests), app 8; zero failures/errors/skips |
| Coverage | `python tests/check_coverage.py` | Domain 100% (34/34), application 98.18% (54/55), infrastructure 91.85% (338/368), global 93.22% (426/457); all published gates pass |
| Deployment and cleanup guards | `python -m unittest discover -s tests -p 'test_*.py'` | 4/4 passed |
| Hexagonal dependencies | `mvn -q -pl workflow-core dependency:tree` and forbidden-dependency check | No Spring, PostgreSQL, Hikari or HTTP-client dependency in core |
| Controlled process recovery | `python tests/process_acceptance.py` | 44 checks passed; actual Workflow JVMs/PostgreSQL with explicitly controlled HTTP participants; exit 0 and owned cleanup passed |
| Actual participants | `python tests/real_services_acceptance.py --workspace <clean snapshots> --pin repo=sha ...` | 21 checks passed, including six provenance checks, actual Customers creation/lookup, sale completion/replay, confirmed rejection and actual Products compensation; exit 0 and owned cleanup passed |

The REAL-SERVICES result is one Workflow acceptance harness, **not** a new global 21-suite platform acceptance.
It uses disposable signed test identities and direct service-to-service HTTP; it does not certify an Auth login,
Gateway/browser flow or persistent deployment. The old Customers-501 blocker below belongs to the older pinned
HU-WKF-03 execution and does not describe these new snapshots.

### Changes and exact traceability

| Item | Tests | Owning production behavior |
|---|---|---|
| TD-014 | `AdvisoryKeysTest`, `JdbcSagaStoreTest` (held/released namespace, collisions, concurrent holders, stale connection) | `AdvisoryKeys`, `JdbcSagaStore.tryOwn`: fixed WFLK first key and signed XOR of four UUID words |
| TD-016 | `FullJitterDelayTest`, `HttpSaleParticipantsTest`, actual process lost-response/408 cases | `FullJitterDelay`, `RetryClassification`, `HttpSaleParticipants`, composition: approved statuses, classified transport including plain IOException wrappers, full jitter and unchanged durable three-attempt budget |
| TD-015 | `CircuitBreakerTest`, `CircuitBreakingParticipantsTest`, `RegisterSaleTest` | One breaker per participant, shared Products reserve/release, single probe, generation-aware settlement, safe CIRCUIT_OPEN handling |

Auth source inspection confirms refresh namespace `0x52544F4B` and initialization's separate bigint overload.
Auth was not changed. Quiescent rollout and reverse rollout are documented in `deployment-and-recovery.md`;
the persistent fleet switch remains **planned and unexecuted**. TD-012 and Products implementation are outside this change.

### TDD provenance and retained failed attempts

Claude's original session wrote tests and code without executing Maven because its invocation required unavailable
interactive approval. Its original red-before-production sequence therefore remains **unverified**; later green
checks are not retroactively relabeled as original TDD evidence.

Codex added and executed regressions **before** its production corrections:

- Circuit generations: twelve CircuitBreaker tests first produced **two assertion failures**, zero errors;
  delayed pre-opening failures reopened a recovered circuit, and delayed success reset its new failure count.
  Generation-aware permit settlement then passed all twelve tests.
- Nested transport: nineteen participant tests first produced **one assertion failure**, zero errors, for a known
  EOF/connect/timeout cause wrapped by plain IOException. Transparent wrapper traversal then passed all nineteen;
  TLS/programming/protocol and unclassified I/O stay non-retryable.

The first process run failed while waiting for a lost Sales response to recover; it retained its failure and cleaned
its resources. After the transport correction and replacement of the obsolete 408-retry expectation, the second
run passed 44 checks. The first REAL-SERVICES run rejected an oversized synthetic customer document; that was a
fixture-preparation failure, not a TDD red or a Customers implementation defect. The bounded valid fixture passed
the second run. Failed-attempt records remain separate from final results.

### Real-service source selection

| Repository | Executed commit |
|---|---|
| synkro-customers-api | `8f39ac8f105772a60ba8c4cc6cd90e101f44db6e` |
| synkro-customers-db | `a5aebc5dfb2408ce3a2550f604d135bceacf4207` |
| synkro-products-api | `af06bb056fedb7b9f83d518436c3370a0f1373d1` |
| synkro-products-db | `0beae79920d9af83865bdebaf6b316a2b2a57592` |
| synkro-sales-api | `6ef40cfa8761231cfba80c2183d2fb0b1a06e895` |
| synkro-sales-db | `e2168fb52f693892b8bbec4c60597b330abeb845` |

The harness checked each detached snapshot's exact SHA and clean status. Only Workflow source was modified.
Customers uses its owning guarded migration runner; all application connections use their domain credentials.
Actual sales and reservations are created only in the owned disposable environment.

### Cleanup and remaining gates

All 27 legacy root logs were backed up outside the repository and SHA-256 checked. A complete scan including
ignored evidence found ten cited by `.evidence/hu-wkf-03-final.md` and `.evidence/hu-wkf-03-protocol-final.md`;
those ten were preserved byte-for-byte. The other **17 unreferenced logs were removed**. New root evidence logs
are ignored. The temporary Claude task context was removed after its archive hash matched. Existing useful
JSON/Markdown evidence, fixtures and helper directories were retained.

The implementation is locally verified; HU-WKF-04 and TD-014/015/016 remain open for actual review, publication,
the original TDD-evidence limitation, and any required deployment/documentation gates. HU-DOCS-98 owns the
downstream record update. No successful local run supplies an approval or permanent-branch promotion.

## HU-WKF-04 changed files

Claude prepared these files except `tests/process_acceptance.py`, which Codex updated for the accepted 408 policy. Codex also corrected and verified the reviewed implementation, tests, real-service wiring and documentation.

| File | Scope |
|---|---|
| [.gitignore](../.gitignore) | Documentation / housekeeping |
| [README.md](../README.md) | Documentation / housekeeping |
| [docs/deployment-and-recovery.md](../docs/deployment-and-recovery.md) | Documentation / housekeeping |
| [docs/execution-report.md](../docs/execution-report.md) | Documentation / housekeeping |
| [docs/persistent-register-sale-design.md](../docs/persistent-register-sale-design.md) | Documentation / housekeeping |
| [docs/synkro-docs-handoff.md](../docs/synkro-docs-handoff.md) | Documentation / housekeeping |
| [tests/process_acceptance.py](../tests/process_acceptance.py) | Tests / acceptance |
| [tests/real_services_acceptance.py](../tests/real_services_acceptance.py) | Tests / acceptance |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreaker.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreaker.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakingParticipants.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakingParticipants.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterDelay.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterDelay.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipants.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipants.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/RetryClassification.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/RetryClassification.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/AdvisoryKeys.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/AdvisoryKeys.java) | Production |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStore.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStore.java) | Production |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakerTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakerTest.java) | Tests / acceptance |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakingParticipantsTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitBreakingParticipantsTest.java) | Tests / acceptance |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterDelayTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterDelayTest.java) | Tests / acceptance |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipantsTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipantsTest.java) | Tests / acceptance |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/AdvisoryKeysTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/AdvisoryKeysTest.java) | Tests / acceptance |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStoreTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStoreTest.java) | Tests / acceptance |
| [workflow-app/src/main/java/co/edu/corhuila/synkro/workflow/app/WorkflowComposition.java](../workflow-app/src/main/java/co/edu/corhuila/synkro/workflow/app/WorkflowComposition.java) | Production |
| [workflow-core/src/main/java/co/edu/corhuila/synkro/workflow/application/port/out/ParticipantFailure.java](../workflow-core/src/main/java/co/edu/corhuila/synkro/workflow/application/port/out/ParticipantFailure.java) | Production |
| [workflow-core/src/test/java/co/edu/corhuila/synkro/workflow/RegisterSaleTest.java](../workflow-core/src/test/java/co/edu/corhuila/synkro/workflow/RegisterSaleTest.java) | Tests / acceptance |

## Review and remaining evidence

The previous delivery cut efe06565f93e6e56b7010dfd9bf5451f14a03d26 passed its three exact-SHA CI jobs and retained two statically identified protocol gaps. This continuation is explicitly authorized only to resolve those gaps. It revalidated affected tests, all 43 Maven tests, coverage and controlled real PostgreSQL/JVM recovery, including HTTP 408/409, continuation without GET polling, graceful shutdown and the new invalid-rejection interruption case. No actual Customers or fully real acceptance was repeated. Hybrid evidence remains historical and separately scoped.

Both internal follow-ups are resolved with executed red-to-green regressions. Rejection now requires the operation's allowed status, JSON media type, required typed fields, matching correlation, status/error correspondence and valid optional details; duplicate/trailing JSON is invalid. Invalid envelopes do not confirm that Sales rejected a committed operation. Request preparation isolates credential/configuration/serialization failures before transport. Asynchronous protocol/response-limit failures and TLS/hostname/programming failures are non-retryable; only permitted transient HTTP states, wait timeout and transport I/O retry within the existing bound. These failures preserve recoverable RUNNING and reservation, without unsafe compensation or new schema/public policies. Applicable local checks pass; READY FOR MANUAL REVIEW of the implemented scope additionally requires successful CI on the final published SHA. WKF-16 remains explicitly BLOCKED.

Infrastructure handoff: local integration commit a65837d0eabf9fcf4b3491c516723f6152a0be5f and its concurrent ancestor commits remain preserved and untouched by this continuation. The owning deploy/compose.yml supplies the Workflow service and tooling migration runner on the external platform network, with separate application/administrator credentials and no additional database instance. This continuation does not pin or approve a Workflow delivery in infrastructure; preserve all six approved complementary pins. Any later reviewed candidate pinning and matching immutable-snapshot acceptance remain separate infrastructure work, not a completed final-SHA validation.

Owner CI is independently executable: Maven, coverage, architecture, deployment and controlled real-process recovery require no private infrastructure checkout or GitHub organization secret. Automatic push CI must be reported on its exact publication SHA; hybrid testing is local-only. Infrastructure integrates the candidate runner/service and keeps all six approved complementary pins.

Clear WKF-16 with a reviewed Customers revision implementing authenticated customers:read lookup of active/inactive/missing customers, then rerun normal and compensation against all three actual services with recorded SHAs and real database effects. Review replay/recovery clarifications and advisory namespace through the prepared synkro-docs handoff. Do not close #243 or claim complete functional end-to-end acceptance before this evidence, review and documentation publication exist. Whole HU-INF-02 additionally awaits Customers DB, Auth/Customers portals, full-platform acceptance and validation-policy review.
