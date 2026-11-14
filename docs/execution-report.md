# Workflow execution evidence

## HU-WKF-04 QA promotion — current status

Source promotion of the corrective delivery below; no new behavior. This subsection records status only and does not replace the evidence that follows.

- Source: corrective PR18 merged into develop on 2026-11-14 (23:39:04 UTC) as [59e3107](https://github.com/code-corhuila/synkro-workflow/commit/59e3107ceff9c76bf2bacb993db556fb5a0e157f); feature head [e2fe763](https://github.com/code-corhuila/synkro-workflow/commit/e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8), merge tree equal to feature tree. The API review list was empty: this is the owner's merge with no independent review observed, and approval is not inferred.
- Original PR17 closed without merge; its confirmed TDD deviation and the attribution below are unchanged and not cured by this promotion.
- Baselines: QA `569a9cb1194e0b76c94c9993f5fe32fc7899f7b8`, main `09a193866f05f3714a0bd1ad6d7559a9f00b3611`; their trees match each other and the former develop baseline `2aa2f25`, with different histories.
- Child `qa-workflow-resilience` (`qa-<description>` naming, since `refs/heads/qa` blocks `qa/<child>`) was cut from `origin/qa`. Seven `cherry-pick -x` applications had no conflicts; before the documentation edits its file tree equals develop `59e3107`.

| Develop source | QA reapplied |
|---|---|
| [3b6f50a](https://github.com/code-corhuila/synkro-workflow/commit/3b6f50a99dc87f1bdc98646555fa6e2db5ec23db) | [2af9436](https://github.com/code-corhuila/synkro-workflow/commit/2af943609e97c17cf220fc2a9bd6f70b526502c0) |
| [4f9c4c6](https://github.com/code-corhuila/synkro-workflow/commit/4f9c4c63fc4b62dafc04c3d6c7aeb096e28ee16d) | [1fd9193](https://github.com/code-corhuila/synkro-workflow/commit/1fd9193bc302968f01fa47961de6703d1ae38e08) |
| [d8101c2](https://github.com/code-corhuila/synkro-workflow/commit/d8101c273c2ded4826e93d1b6cf7510ffb57faff) | [da2c01c](https://github.com/code-corhuila/synkro-workflow/commit/da2c01c9e10dde392125ed589c56ffcaf46f38d6) |
| [26586b3](https://github.com/code-corhuila/synkro-workflow/commit/26586b3dbcb57565a9e00a8d4fc22e2cf24613fe) | [f0de210](https://github.com/code-corhuila/synkro-workflow/commit/f0de2103b8b2224629d63f1672ab69f17883ba55) |
| [89b6a19](https://github.com/code-corhuila/synkro-workflow/commit/89b6a19f4ea22cd93d214e25c5d77a01e36f26b5) | [0bdbb1b](https://github.com/code-corhuila/synkro-workflow/commit/0bdbb1b35e437bfa21ce49bce3277cce7f692e86) |
| [321f85f](https://github.com/code-corhuila/synkro-workflow/commit/321f85ffa5580119636923eafcc4ce39eb556ed8) | [dcc6db0](https://github.com/code-corhuila/synkro-workflow/commit/dcc6db04cd4b1a861e077ff6ab11bfa2a0903798) |
| [e2fe763](https://github.com/code-corhuila/synkro-workflow/commit/e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8) | [39fcf1c](https://github.com/code-corhuila/synkro-workflow/commit/39fcf1cf1842a42ac6a92077fe6ee5250d76615c) |

| Item | Status |
|---|---|
| Fresh QA tests on the child |121 Java tests,4 Python guards,93.92% global LINE;44 controlled and21 actual-service checks passed |
| QA child publication and merge | Pending (owner merges) |
| Main proposal (`release/<description>` from `origin/main`) | Pending; needs actual `ariel5253` approval on the delivered head plus codeowner/conversation gates |
| Lock-algorithm rollout / scenario 3 | Unexecuted; source promotion is not a deployment |

### Fresh QA execution record

Fresh QA executions on the child passed: **121 Java tests** (core16/adapters93/app12), zero failures/errors/skips; LINE global93.92%, domain100%, application98.25%, infrastructure92.75%; four Python guards and core architecture/protected-file checks. Separate controlled real Workflow/PostgreSQL acceptance **44/44** and actual Customers/Products/Sales acceptance **21/21** passed, with exact clean snapshots and two owned disposable cleanups. These are fresh QA-candidate executions; no QA merge, persistent deployment or new global21-suite acceptance is inferred.

Verified source head before the promotion-only documentation commit: `39fcf1cf1842a42ac6a92077fe6ee5250d76615c`. Runtime/test/config inputs remained unchanged during each execution, match the merged develop tree and match the final candidate after documentation-only edits. Seven provenance footers were checked against actual develop ancestors. Publication/head status belongs to the associated PR and HU; no self-referential SHA is invented.

| Actual participant snapshot | Commit |
|---|---|
| synkro-customers-api | `8f39ac8f105772a60ba8c4cc6cd90e101f44db6e` |
| synkro-customers-db | `a5aebc5dfb2408ce3a2550f604d135bceacf4207` |
| synkro-products-api | `af06bb056fedb7b9f83d518436c3370a0f1373d1` |
| synkro-products-db | `0beae79920d9af83865bdebaf6b316a2b2a57592` |
| synkro-sales-api | `6ef40cfa8761231cfba80c2183d2fb0b1a06e895` |
| synkro-sales-db | `e2168fb52f693892b8bbec4c60597b330abeb845` |

| Execution | Log SHA-256 |
|---|---|
| `qa-reactor` | `e0b34921dae07a9242732e5e9cab46a015c0934b9554d2f0c501b6ebb273ed9a` |
| `process-qa` | `539f29483d0f85eb5effecfdddc0bbf1703505f3f21b750e6e6a75fbaf5fb526` |
| `real-qa` | `c1cd4370286b4f3e7790f93184a07ea799abd6c1e4cda516500debee429a0df5` |

Claude Code changed only this report, the prepared PR description and the existing documentation handoff; Codex reviewed those diffs, executed fresh verification and recorded the outcomes. No production/test/CI/Accepted-ADR change was introduced by the promotion record. Original test-first chronology remains attributed to the corrective implementation, not replayed as new TDD.

Pact: Tier 3b of the testing strategy requires no new or regenerated Pact when no HTTP call is added or altered. The diff `569a9cb..39fcf1c` leaves request construction in `HttpSaleParticipants.java` unchanged and only reclassifies responses/failures; attempts may be fewer. No Pact was run or waived. The runs below are controlled-fixture and real-service acceptance, not Pact.

## HU-WKF-04 corrective delivery — 2026-11-14

The owner chose a new corrective delivery with verifiable TDD after the original session audit confirmed code-before-test work in PR17. This branch, `feat/workflow-tdd-remediation`, starts at develop `2aa2f25f4411f0c2bbdbf7cbfb57701fcee7c7d7`. Original PR17/head `a84b2da25b3d51ae2ef289e2f260e18538a59f7b` is preserved; it is not an ancestor of this delivery. The [original deviation and owner decision](https://github.com/code-corhuila/synkro-docs/issues/316) remain recorded. No exception or retrospective TDD compliance is claimed.

This is a new implementation informed by approved requirements and the earlier review, not a blind independent exercise. Claude Code authored the design and seven new test classes; Codex reviewed the test design, wrote production code after observed RED, executed verification and prepared publication. Agent checks do not supply independent human approval. No PR17 production/new TDD unit tests were copied; its real-service acceptance harness is reused with explicit provenance, not counted as new test-first evidence.

### Observed test-first sequence

Baseline: the first unchanged run executed 35 tests before a pre-existing connection-termination timing error stopped the reactor (zero assertion failures, one error); an unchanged repeat passed 43/43. The test was then made to await actual lease acquisition with bounded polling, retaining all fencing assertions. The initial remedial compile run had zero executed tests and failed on absent APIs; it is not functional RED.

After the tests existed, inert compilation scaffolds were added (zero lock keys, no-op delay, always-admit circuit and pass-through decorator). They provide signatures, not working features. The actual assertion-failing RED ran before feature implementation: **74 adapter tests, 55 failures, zero errors/skips**; composition **4 tests, 3 failures, zero errors/skips**. Twenty characterization cases already passed; they are not relabeled as failures. All new test sources stayed unchanged from observed functional RED through final GREEN.

Test contract/scaffold checkpoint: [3b6f50a](https://github.com/code-corhuila/synkro-workflow/commit/3b6f50a99dc87f1bdc98646555fa6e2db5ec23db). It precedes all feature implementation commits below. UTC times, before/after source archives, XML results, command outcomes and SHA-256 records are retained outside the repository in the local `workflow-wkf04-tdd-remediation` evidence bundle. Raw snapshot line endings are preserved; equivalence to Git source is checked after CRLF→LF normalization.

| Scope | Observed RED assertion failures | Subsequent GREEN cases for the new classes | Implementation commit |
|---|---|---|---|
| TD-014 | 9 | 14 | [4f9c4c6](https://github.com/code-corhuila/synkro-workflow/commit/4f9c4c63fc4b62dafc04c3d6c7aeb096e28ee16d) |
| TD-016 classifier | 6 | 12 | [d8101c2](https://github.com/code-corhuila/synkro-workflow/commit/d8101c273c2ded4826e93d1b6cf7510ffb57faff) |
| TD-016 jitter | 9 | 11 | [26586b3](https://github.com/code-corhuila/synkro-workflow/commit/26586b3dbcb57565a9e00a8d4fc22e2cf24613fe) |
| TD-015 state | 16 | 17 | [89b6a19](https://github.com/code-corhuila/synkro-workflow/commit/89b6a19f4ea22cd93d214e25c5d77a01e36f26b5) |
| TD-015 decorator | 15 | 20 | [321f85f](https://github.com/code-corhuila/synkro-workflow/commit/321f85ffa5580119636923eafcc4ce39eb556ed8) |
| Composition | 3 | 4 | [321f85f](https://github.com/code-corhuila/synkro-workflow/commit/321f85ffa5580119636923eafcc4ce39eb556ed8) |

Each GREEN includes its corresponding tests; lock and classifier focused runs additionally retain baseline regressions. The decorator and composition GREEN results are part of the final complete reactor. Refactor review kept the small key function, RetryDelay strategy, isolated admission state machine and port decorator; no further structural rewrite was required. Domain/use-case I/O boundaries remain unchanged.

### Final verification and limits

| Scope | Observed result |
|---|---|
| JDK21.0.12 / Maven3.9.16, full `mvn -B verify` | **121 passed**: core16, adapters93 (including 13 real-PostgreSQL tests), app12; zero failures/errors/skips |
| LINE coverage | Global **93.92% (448/477)**; domain100% (34/34), application98.25% (56/57), infrastructure92.75% (358/386) |
| Python guards and architecture | Four guards passed; no Spring/JDBC/HTTP/Jackson imports in core; migrations and CODEOWNERS unchanged |
| Real Workflow/PostgreSQL, controlled HTTP participants | Initial corrected acceptance43/43; strengthened acceptance44/44, including SQL verification that HTTP408 leaves exactly one durable attempt |
| Actual Customers/Products/Sales | **21 checks passed**, including six exact clean-source checks, sale/replay, frozen results, confirmed-rejection compensation, protected credentials and owned cleanup |
| Artifact integrity | Ten Maven execution records/archives verified; six RED→GREEN groups; 48 source/config files match the verified production commit; new test sources unchanged after RED |

Production/test-source commit: [321f85f](https://github.com/code-corhuila/synkro-workflow/commit/321f85ffa5580119636923eafcc4ce39eb556ed8). Java source and test equivalence to the executed reactor was checked before publication; subsequent changes concern documentation, ignore rules and acceptance assets. The real-service run also confirmed its input source files stayed unchanged during execution. Publication/head status is recorded on the HU and associated corrective PR, avoiding a self-referential commit hash.

This is scoped local evidence, not a new 21-suite platform acceptance, Auth/Gateway/browser certification, deployed target inventory, independent review or persistent rollout. The controlled harness's warning that fixtures cannot prove fully real acceptance applies to that controlled run only; the separate real-services run above uses no controlled participant. Both successful controlled runs are retained, as are the initial baseline error and compile gap. No failed attempt is relabeled as a functional TDD RED.

### Exact real-service snapshots

| Repository | Commit |
|---|---|
| synkro-customers-api | `8f39ac8f105772a60ba8c4cc6cd90e101f44db6e` |
| synkro-customers-db | `a5aebc5dfb2408ce3a2550f604d135bceacf4207` |
| synkro-products-api | `af06bb056fedb7b9f83d518436c3370a0f1373d1` |
| synkro-products-db | `0beae79920d9af83865bdebaf6b316a2b2a57592` |
| synkro-sales-api | `6ef40cfa8761231cfba80c2183d2fb0b1a06e895` |
| synkro-sales-db | `e2168fb52f693892b8bbec4c60597b330abeb845` |

### Execution fingerprints

| Record | Log SHA-256 |
|---|---|
| `adapters-functional-red` | `3cd4a61e0b7311861c6b70ff76dff7bfba5f0a4d428c917ace2e8653f985699e` |
| `composition-functional-red` | `586f4bd4ec33a02307a05fb8f53e8a446af80f55efb214454fb1a8e129a2b0ec` |
| `lock-green` | `86164c477267e1080a4b1b223d26730fe2c65d1d03ed8521958b364d67ba83b4` |
| `retry-green` | `80dff51dda0bf6a488c45770405f30d7dd9893f37a0d5d0369d110cfc6d07bf6` |
| `jitter-green` | `1aecb846ca6a29bfc88d96f1f8261a9ccb31f7b991681895c1b85d2fe05685f7` |
| `circuit-green` | `5de4e3083592dfcef9109df8c596feb50a5e251c5c4946bb4185eaffd1fc76da` |
| `reactor-green` | `e159fbc7a61afdd6dbe50977fd72357ab71f0662428842ececd60a8af863d583` |
| `process-durable-count` | `539f29483d0f85eb5effecfdddc0bbf1703505f3f21b750e6e6a75fbaf5fb526` |
| `real-corrective` | `c1cd4370286b4f3e7790f93184a07ea799abd6c1e4cda516500debee429a0df5` |

### Remaining gates

- Independent review must apply to this corrective delivery; review of original PR17 is not transferred. The owner decides how to replace the original proposal; no automatic closure or integration of either PR is performed.
- Destination-child QA/main promotions use `cherry-pick -x`. No permanent branches are merged into each other; actual main approval remains separate.
- The coordinated stop-all lock switch in [deployment-and-recovery.md](deployment-and-recovery.md#coordinated-lock-algorithm-switch--not-executed) is documented but unexecuted. Scenario3, actual environment selection/inventory and persistent rollout remain pending.
- [DOCS-98/#266](https://github.com/code-corhuila/synkro-docs/issues/266) must reconcile the existing owning documents, traceability and debt status after actual delivery evidence. No synkro-docs file, Accepted ADR, database migration or participant implementation was changed here. TD-012 has no implementation or test plan in this delivery.
- The original historical TDD deviation stays recorded. The new delivery supplies its own observed sequence; it does not erase that record or automatically close HU-WKF-04/TD-014/015/016.

Three exact disposable execution identities (two controlled, one real) were checked: no owned container/network/volume leftovers. Referenced historic root logs and the existing PR17 worktree were untouched. Execution logs/archives stay outside the repository; future root evidence logs are ignored.

### Changed files and attribution

| File | Attribution / scope |
|---|---|
| [.gitignore](../.gitignore) | Codex implementation, acceptance alignment or documentation |
| [README.md](../README.md) | Codex implementation, acceptance alignment or documentation |
| [docs/deployment-and-recovery.md](../docs/deployment-and-recovery.md) | Codex implementation, acceptance alignment or documentation |
| [docs/persistent-register-sale-design.md](../docs/persistent-register-sale-design.md) | Codex implementation, acceptance alignment or documentation |
| [docs/prepared-pr-description.md](../docs/prepared-pr-description.md) | Codex implementation, acceptance alignment or documentation |
| [docs/synkro-docs-handoff.md](../docs/synkro-docs-handoff.md) | Codex implementation, acceptance alignment or documentation |
| [docs/tdd-remediation-design.md](../docs/tdd-remediation-design.md) | Claude design; Codex review/current evidence reconciliation |
| [tests/process_acceptance.py](../tests/process_acceptance.py) | Codex implementation, acceptance alignment or documentation |
| [tests/real_services_acceptance.py](../tests/real_services_acceptance.py) | Existing Claude PR17 acceptance asset reused by Codex; example formatting fixed |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitProtectedParticipants.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitProtectedParticipants.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterRetryDelay.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterRetryDelay.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipants.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipants.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/ParticipantCircuit.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/http/ParticipantCircuit.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStore.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStore.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockKey.java](../workflow-adapters/src/main/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockKey.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitProtectedParticipantsRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/CircuitProtectedParticipantsRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterRetryDelayRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/FullJitterRetryDelayRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipantsTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/HttpSaleParticipantsTest.java) | Claude baseline-test maintenance; Codex verified |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/ParticipantCircuitRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/ParticipantCircuitRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/RetryPolicyRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/http/RetryPolicyRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStoreTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/JdbcSagaStoreTest.java) | Claude baseline-test maintenance; Codex verified |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockKeyRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockKeyRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockPostgresRemediationTest.java](../workflow-adapters/src/test/java/co/edu/corhuila/synkro/workflow/adapter/out/persistence/WorkflowLockPostgresRemediationTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-app/src/main/java/co/edu/corhuila/synkro/workflow/app/WorkflowComposition.java](../workflow-app/src/main/java/co/edu/corhuila/synkro/workflow/app/WorkflowComposition.java) | Codex implementation, acceptance alignment or documentation |
| [workflow-app/src/test/java/co/edu/corhuila/synkro/workflow/app/WorkflowResilienceCompositionTest.java](../workflow-app/src/test/java/co/edu/corhuila/synkro/workflow/app/WorkflowResilienceCompositionTest.java) | Claude tests; Codex pre-RED review (fixtures, allow-list and durable-count assertions where applicable) |
| [workflow-core/src/main/java/co/edu/corhuila/synkro/workflow/application/port/out/ParticipantFailure.java](../workflow-core/src/main/java/co/edu/corhuila/synkro/workflow/application/port/out/ParticipantFailure.java) | Codex implementation, acceptance alignment or documentation |

---

## Historical HU-WKF-03 local report (preserved attribution; not new verification)

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
