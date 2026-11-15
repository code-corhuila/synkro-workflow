# Prepared main promotion description

Title: feat(workflow): promote resilience corrections to main

Promotes the already implemented HU-WKF-04 corrective delivery from the integrated QA line into main. Workflow's lock namespace, retry policy and participant protection are carried over unchanged: WFLK-reserved advisory keys, retries limited to approved transient categories with full jitter, and per-participant circuits around Customers/Products/Sales that retain durable attempt budgets and uncertain reservations. This is a source promotion only; no new behavior, version bump or functionality is introduced.

## Source and baseline

- QA source: PR19 was merged into `qa` on 2026-11-14 at 19:07:38 America/Bogota (2026-11-15 00:07:38 UTC) as [269a687](https://github.com/code-corhuila/synkro-workflow/commit/269a687dd10dc94d7a3043fe63075884f0da30c9). The delivered QA head was [487d196](https://github.com/code-corhuila/synkro-workflow/commit/487d1964d6879b8cf3048e255b37b4af7880df48); the merge tree equals the delivered QA tree.
- Review record: the API review list for PR19 was empty. The merge is the owner's integration action; no independent approval was observed and none is inferred from the merge.
- Origin of the QA content: corrective PR18, merged into develop as [59e3107](https://github.com/code-corhuila/synkro-workflow/commit/59e3107ceff9c76bf2bacb993db556fb5a0e157f) (feature head [e2fe763](https://github.com/code-corhuila/synkro-workflow/commit/e2fe763fde2ccd55d00c8dc4b7b6e46165f9aca8)), with the same owner-merge and empty-review caveat. The original PR17 was closed without merge.
- Main baseline: `09a193866f05f3714a0bd1ad6d7559a9f00b3611`. Branch `release/workflow-resilience` was cut from `origin/main` and carries eight `cherry-pick -x` applications of commits actually reachable from QA merge `269a687`. They applied without conflicts. The branch tree at [f1540a5](https://github.com/code-corhuila/synkro-workflow/commit/f1540a5051d887bb20c8d433f2385c2d413186d4) matches the integrated QA tree before this promotion's documentation-only changes. Histories differ; no permanent branch is merged into another.

| Actual QA source | Main release reapplication |
|---|---|
| [2af9436](https://github.com/code-corhuila/synkro-workflow/commit/2af943609e97c17cf220fc2a9bd6f70b526502c0) | [a856fa1](https://github.com/code-corhuila/synkro-workflow/commit/a856fa124bfe6a667fdd0d2513a8b0ba340d3ba6) |
| [1fd9193](https://github.com/code-corhuila/synkro-workflow/commit/1fd9193bc302968f01fa47961de6703d1ae38e08) | [80bedae](https://github.com/code-corhuila/synkro-workflow/commit/80bedae02c8de42c8b314e29c3d34e545baacf40) |
| [da2c01c](https://github.com/code-corhuila/synkro-workflow/commit/da2c01c9e10dde392125ed589c56ffcaf46f38d6) | [c43e9bd](https://github.com/code-corhuila/synkro-workflow/commit/c43e9bdefc2cdf8f54e4300bd78319a18a6c1563) |
| [f0de210](https://github.com/code-corhuila/synkro-workflow/commit/f0de2103b8b2224629d63f1672ab69f17883ba55) | [5f9cd51](https://github.com/code-corhuila/synkro-workflow/commit/5f9cd515e83b39b009e85db01e3bebd4aa154ff4) |
| [0bdbb1b](https://github.com/code-corhuila/synkro-workflow/commit/0bdbb1b35e437bfa21ce49bce3277cce7f692e86) | [6186b90](https://github.com/code-corhuila/synkro-workflow/commit/6186b90bcd8d8b593825b0f79ac72cef9ce4d518) |
| [dcc6db0](https://github.com/code-corhuila/synkro-workflow/commit/dcc6db04cd4b1a861e077ff6ab11bfa2a0903798) | [4143ace](https://github.com/code-corhuila/synkro-workflow/commit/4143ace9a88501e1fe3257b706969b87e452d049) |
| [39fcf1c](https://github.com/code-corhuila/synkro-workflow/commit/39fcf1cf1842a42ac6a92077fe6ee5250d76615c) | [7c6763a](https://github.com/code-corhuila/synkro-workflow/commit/7c6763aa30f78ae7406a4c25ae9f4f4f3c7592c5) |
| [487d196](https://github.com/code-corhuila/synkro-workflow/commit/487d1964d6879b8cf3048e255b37b4af7880df48) | [f1540a5](https://github.com/code-corhuila/synkro-workflow/commit/f1540a5051d887bb20c8d433f2385c2d413186d4) |

## Unchanged behavior

Same behavior as develop `59e3107` and the integrated QA line. No Accepted ADR, schema or migration, domain lifecycle, participant repository, public request/response schema, CODEOWNERS, branch protection or CI was changed. TD-012 and Products stay out of scope. HTTP request methods, paths, headers and body construction are unchanged (see the Pact note below).

The original PR17 was closed without merge. Its confirmed TDD process deviation stays recorded in HU-WKF-04 and is not retroactively cured by this promotion, by PR18 or by PR19. The original RED/GREEN sequence belongs to the implementation and is not replayed during promotion.

## Pact applicability

Testing-strategy Tier 3b states that a change that does not add or alter an HTTP call does not require a new or regenerated Pact, and its bounded Pact matrix covers the CLI-05/VEN-20 portals, not Workflow. This change touches lock keys, retry classification and backoff, local circuit admission and a port decorator; methods, paths, headers, payloads and public schemas are intact. In `HttpSaleParticipants.java` the diff changes only response/failure classification; request construction is untouched. Fewer calls may reach a participant (fewer attempts, local circuit refusal); no call is added or reshaped. Under that rule a new or regenerated Pact is not required. No Pact was run or waived, no Pact artifact exists in this repository, and no exception is claimed. The controlled fixtures and real-service acceptance listed below are not Pact verification.

## Verification

Prior fresh QA evidence (not executions of this release branch): 121 Java tests (core 16, adapters 93, app 12) with zero failures/errors/skips; LINE global 93.92%, domain 100%, application 98.25%, infrastructure 92.75%; four Python guards; 44 controlled Workflow/PostgreSQL checks and 21 actual Customers/Products/Sales checks; six exact source pins; two owned disposable cleanups. Details and scope limits are in [execution-report.md](execution-report.md).

Fresh release-candidate execution passed **121 Java tests** (core16/adapters93/app12), zero failures/errors/skips; LINE global93.92%, domain100%, application98.25%, infrastructure92.75%; four Python guards and core architecture/protected-file checks. Controlled real Workflow/PostgreSQL acceptance **44/44** and separate actual Customers/Products/Sales acceptance **21/21** passed, with six exact clean snapshots and two owned disposable cleanups. These are executions of this release candidate, distinct from the earlier QA runs. They do not establish main integration, a tag, persistent deployment or a new global21-suite acceptance.

## Remaining dependencies

- Release verification is complete; actual main review/approval and integration remain pending.
- Actual publication/head status is recorded on the associated PR; required approval and owner main merge remain separate gates.
- Main requires actual `ariel5253` approval on the delivered main head, plus CODEOWNERS, conversation and team-review evidence. No earlier approval transfers; none is claimed. The user performs the merge; no automatic main merge, tag or persistent deployment is performed or claimed.
- Coordinated quiescent lock-algorithm rollout and scenario 3 remain unexecuted; this source promotion is not a persistent deployment.
- Independent review of the corrective code has not been observed.
- DOCS-98 downstream reconciliation in synkro-docs; no debt is closed by this promotion.

Refs code-corhuila/synkro-docs#316
Refs code-corhuila/synkro-docs#266
