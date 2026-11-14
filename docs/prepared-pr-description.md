# Prepared QA promotion description

Title: feat(workflow): promote resilience corrections to qa

Promotes the already implemented HU-WKF-04 corrective delivery into the QA integration line. Workflow's lock namespace, retry policy and participant protection are carried over unchanged: WFLK-reserved advisory keys, retries limited to approved transient categories with full jitter, and per-participant circuits around Customers/Products/Sales that retain durable attempt budgets and uncertain reservations. This is a source promotion only; no new behavior is introduced.

## Source and baseline

- Source: corrective PR18, merged into develop on 2026-11-14 at 23:39:04 UTC as [59e3107](https://github.com/code-corhuila/synkro-workflow/commit/59e3107ceff9c76bf2bacb993db556fb5a0e157f). Feature head [e2fe763](https://github.com/code-corhuila/synkro-workflow/commit/e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8); the merge tree equals the feature tree.
- Review record: the API review list for PR18 was empty. The merge is the owner's actual action; no independent review was observed and none is inferred from the merge.
- Child branch: `qa-workflow-resilience`, cut from actual `origin/qa` at `569a9cb1194e0b76c94c9993f5fe32fc7899f7b8`. Child branches are named `qa-<description>` because `refs/heads/qa` prevents `refs/heads/qa/<child>`.
- Seven `cherry-pick -x` applications succeeded without conflicts. Before the documentation edits in this promotion, the child's complete file tree equals develop `59e3107`. The QA and develop histories differ.

| Develop source | QA reapplied commit |
|---|---|
| [3b6f50a](https://github.com/code-corhuila/synkro-workflow/commit/3b6f50a99dc87f1bdc98646555fa6e2db5ec23db) | [2af9436](https://github.com/code-corhuila/synkro-workflow/commit/2af943609e97c17cf220fc2a9bd6f70b526502c0) |
| [4f9c4c6](https://github.com/code-corhuila/synkro-workflow/commit/4f9c4c63fc4b62dafc04c3d6c7aeb096e28ee16d) | [1fd9193](https://github.com/code-corhuila/synkro-workflow/commit/1fd9193bc302968f01fa47961de6703d1ae38e08) |
| [d8101c2](https://github.com/code-corhuila/synkro-workflow/commit/d8101c273c2ded4826e93d1b6cf7510ffb57faff) | [da2c01c](https://github.com/code-corhuila/synkro-workflow/commit/da2c01c9e10dde392125ed589c56ffcaf46f38d6) |
| [26586b3](https://github.com/code-corhuila/synkro-workflow/commit/26586b3dbcb57565a9e00a8d4fc22e2cf24613fe) | [f0de210](https://github.com/code-corhuila/synkro-workflow/commit/f0de2103b8b2224629d63f1672ab69f17883ba55) |
| [89b6a19](https://github.com/code-corhuila/synkro-workflow/commit/89b6a19f4ea22cd93d214e25c5d77a01e36f26b5) | [0bdbb1b](https://github.com/code-corhuila/synkro-workflow/commit/0bdbb1b35e437bfa21ce49bce3277cce7f692e86) |
| [321f85f](https://github.com/code-corhuila/synkro-workflow/commit/321f85ffa5580119636923eafcc4ce39eb556ed8) | [dcc6db0](https://github.com/code-corhuila/synkro-workflow/commit/dcc6db04cd4b1a861e077ff6ab11bfa2a0903798) |
| [e2fe763](https://github.com/code-corhuila/synkro-workflow/commit/e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8) | [39fcf1c](https://github.com/code-corhuila/synkro-workflow/commit/39fcf1cf1842a42ac6a92077fe6ee5250d76615c) |

## Unchanged behavior

Same behavior as develop `59e3107`. No Accepted ADR, schema or migration, domain lifecycle, participant repository, public request/response schema, CODEOWNERS, branch protection or CI was changed. TD-012 and Products stay out of scope. HTTP request methods, paths, headers and body construction are unchanged (see the Pact note below).

The original PR17 was closed without merge. Its confirmed TDD process deviation stays recorded in HU-WKF-04 and is not retroactively cured by this promotion or by PR18.

## Pact applicability

Testing-strategy Tier 3b states that a change that does not add or alter an HTTP call does not require a new or regenerated Pact, and its bounded Pact matrix covers the CLI-05/VEN-20 portals, not Workflow. The change from QA baseline `569a9cb` to `39fcf1c` touches lock keys, retry classification and backoff, and a port decorator. In `HttpSaleParticipants.java` the diff changes only response/failure classification (HTTP 408 is no longer in the transient set; transport-exception classification is stricter); request construction is untouched. Behaviorally, the number of attempts may be lower and a circuit may refuse dispatch locally, so fewer calls can reach a participant; no call is added or reshaped. Under that rule a new or regenerated Pact is not required. No Pact was run or waived, no Pact artifact exists in this repository, and no exception is claimed. The controlled fixtures and real-service acceptance listed below are not Pact verification.

## Verification

Fresh QA executions on the child passed: **121 Java tests** (core16/adapters93/app12), zero failures/errors/skips; LINE global93.92%, domain100%, application98.25%, infrastructure92.75%; four Python guards and core architecture/protected-file checks. Separate controlled real Workflow/PostgreSQL acceptance **44/44** and actual Customers/Products/Sales acceptance **21/21** passed, with exact clean snapshots and two owned disposable cleanups. These are fresh QA-candidate executions; no QA merge, persistent deployment or new global21-suite acceptance is inferred.

Prior observed execution, on the develop corrective delivery and not a QA run: 121 Java tests (core 16, adapters 93, app 12) with zero failures/errors/skips; global LINE 93.92%, domain 100%, application 98.25%, infrastructure 92.75%; four Python guards; six observed RED/GREEN groups with 58 original assertion failures, preserved as the original chronology and not rerun; 44 controlled real-Workflow/PostgreSQL checks; 21 separate actual Customers/Products/Sales checks with six exact clean source pins; three owned disposable cleanups. Details and scope limits are in [execution-report.md](execution-report.md).

## Remaining dependencies

- QA child PR review and merge (performed by the owner; no automatic merge is requested). Fresh local QA verification is complete.
- Main promotion: a `release/<description>` branch cut from actual `origin/main` (baseline `09a193866f05f3714a0bd1ad6d7559a9f00b3611`), taking the actual QA-integrated commits by `cherry-pick -x`. Main requires actual `ariel5253` approval on the delivered head plus codeowner and conversation gates; no earlier approval transfers. QA integration and main promotion are not yet done.
- Coordinated quiescent lock-algorithm rollout and scenario 3 remain unexecuted; this source promotion is not a persistent deployment.
- Independent review of the corrective code has not been observed.
- DOCS-98 downstream reconciliation in synkro-docs.

Refs code-corhuila/synkro-docs#316
Refs code-corhuila/synkro-docs#266
