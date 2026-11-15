# HU-WKF-04 remedial delivery: test-first design

Design and tests were authored before feature implementation. The initial draft's execution placeholders are now reconciled with observed RED/GREEN runs in [execution-report.md](execution-report.md). This is a new corrective delivery; the original PR17 TDD deviation remains recorded in [HU-WKF-04](https://github.com/code-corhuila/synkro-docs/issues/316).

## 1. Context and ground rules

- The owner explicitly chose a **new delivery with verifiable TDD**. The original PR17 (`a84b2da`) stays unchanged.
  This worktree starts from `develop` at `2aa2f25`.
- The original process deviation is **retained, not retrospectively corrected or waived**. This is new work derived from
  the approved requirements and from the earlier review findings; it is *not* an independent blind implementation.
- No PR17 production code or new TDD unit tests are copied or cherry-picked. The existing PR17 real-service acceptance harness is reused as an attributed acceptance asset after implementation; it supplies no new test-first evidence. Existing `develop` regression
  fixtures (for example the fault transport and in-memory store of `HttpSaleParticipantsTest`) may be reused as ideas.
- Scope: Workflow technical debts **TD-014, TD-015, TD-016** only. TD-012 and every Products change are excluded.
  No ADR, schema, contract, timeout, domain-model or public-status change. No persistent rollout, promotion or merge.
- Design sources (already approved): ADR-015 *sale-saga-execution-safety*, the pattern-guide Circuit Breaker and
  retry sections, domain entities and rules, the testing strategy and the TDD guide in `synkro-docs`.
  Requirements: FR-005, FR-007; NFR-002, NFR-004, NFR-005, NFR-006, NFR-009.
- Architecture: the domain stays pure, ports remain interfaces, I/O lives only in adapters.
- Baseline note: the previously executed base reported 43 passing tests; its first run had one pre-existing
  asynchronous connection-termination test error, which is preserved separately (see section 6).

## 2. Agreed minimal new API (the tests govern the implementation)

| # | Type (package) | Shape the tests rely on |
|---|---|---|
| 1 | `WorkflowLockKey` (`adapter.out.persistence`, public final) | `static final int NAMESPACE = 0x57464C4B`; `static int value(UUID)`; used by `JdbcSagaStore.tryOwn`. No new port, no schema. |
| 2 | `FullJitterRetryDelay` (`adapter.out.http`, public final, implements `RetryDelay`) | `FullJitterRetryDelay(LongUnaryOperator draw, Sleeper sleep)`; nested functional `Sleeper { void sleep(long) throws InterruptedException }`; `static long bound(int attempt)`; `static FullJitterRetryDelay system()`. |
| 3 | `ParticipantCircuit` (`adapter.out.http`, package-private final) | `ParticipantCircuit(Clock, int threshold, Duration cooldown)`; `Token tryEnter()` (null when denied); nested `Token` with `success()`, `failure()`, `neutral()`. |
| 4 | `CircuitProtectedParticipants` (`adapter.out.http`, public final, implements `SaleParticipants`) | `CircuitProtectedParticipants(SaleParticipants, Clock)`; new `ParticipantFailure.Kind.CIRCUIT_OPEN`. |
| 5 | No new helper | Classification is tested and corrected through the existing public operations of `HttpSaleParticipants`. |

Behavior pinned by the tests where the brief left room:

- `value(UUID)` is the XOR of the four 32-bit words of the UUID (hand-computed literals in the tests, not a mirrored
  formula). The advisory lock is the pair `(NAMESPACE, value)` taken with `pg_try_advisory_lock(int,int)`.
- `bound(attempt)`: `attempt <= 0` is normalized to 1; `100 * 2^(attempt-1)` capped at 2000 ms; huge attempts saturate.
  `pause` hands the draw an **inclusive** upper bound and sleeps exactly the drawn value; an out-of-range draw raises
  `WorkflowError("INTERNAL_ERROR")` without sleeping; an interrupt restores the flag and raises the same error.
- Circuit: a probe is admitted at **exactly** `cooldown` after the opening (not before); a failed probe reopens and the
  cooldown restarts from the failure instant; a neutral probe frees the probe slot but does **not** close the circuit;
  invalid construction (threshold or cooldown not positive) raises `IllegalArgumentException`.
- Decorator: three independent circuits (Customers, Products shared by reserve/release, Sales), threshold 3,
  cooldown 15 s. Only `TRANSIENT` counts as outage; a validated success resets; every other `ParticipantFailure`
  kind, and any `RuntimeException`/`Error`, is neutral (frees the probe, propagates the same instance).
- Retry classification (HTTP): retried statuses are exactly 429, 500, 502, 503, 504 (**408 is no longer retried** and is
  classified like any other unlisted status, `INVALID_RESPONSE`). Retried transport causes are EOF, connect, socket reset/timeout, `HttpTimeoutException`, `ClosedChannelException`, directly or inside a plain `IOException` /
  `CompletionException` / `ExecutionException`. Arbitrary `IOException` or subclasses, TLS, DNS/unresolved,
  bind/no-route/port-unreachable, protocol, response-size and programming errors are never retried, and a negative nested
  cause dominates an outer cause that looks transient. Cause cycles must terminate.

## 3. Chosen behavior: fail fast, durable attempt budget

`CIRCUIT_OPEN` is a **private port failure**, never a business rejection and never a public status. The use case is not
changed. Consequences the tests pin down:

- An open Sales circuit is observed *after* the use case has durably recorded one attempt, so it consumes **exactly one
  local attempt** and then suspends with the private `OUTCOME_UNRESOLVED` marker; the reservation is kept and no
  release is called. After a prior ambiguous sale the pending step is kept.
- An open Products circuit during compensation ends the saga `FAILED` (`COMPENSATION_EXHAUSTED`), never
  `COMPENSATED`, and the participant is never asked to release.
- An open Customers circuit at the first step never becomes `PARTICIPANT_REJECTED`.
- A `408` response, or any unclassified transport failure, consumes **one** durable attempt and is not retried; a
  transient failure consumes at most three attempts and two pauses; a restarted object continuing the same POST gets no fresh
  budget.
- The four public statuses (`RUNNING`, `COMPLETED`, `COMPENSATED`, `FAILED`), the projection, and the absence of any
  circuit text or secret in persisted rows are unchanged.

## 4. Requirement to test map

| Requirement / debt | Test class (module) | Test names |
|---|---|---|
| TD-014 lock key pure function | `WorkflowLockKeyRemediationTest` (adapters) | `namespaceIsTheWorkflowConstantAndNeverTheAuthOne`, `zeroAndAllOnesFoldToZero`, `signedBoundariesAreReachedWithoutOverflowOrExceptions`, `everyOneOfTheFourWordsContributes`, `arbitraryUuidsMatchPrecomputedConstants`, `valueIsStableAcrossCalls`, `differentUuidsMayCollideOnPurposeAndThatIsADocumentedPossibility` |
| TD-014 real lock behavior (FR-005, NFR-004) | `WorkflowLockPostgresRemediationTest` (adapters, own PostgreSQL 16.15-alpine3.23 + current migrations + least-privilege role) | `sessionHoldsTheNamespacedPairAndKeepsItAfterCommittedProgress`, `negativeFoldedValueIsLockedAsItsUnsignedObjectId`, `workflowNamespaceNeverBlocksNorIsBlockedByTheAuthPair`, `differentUuidsWithTheSameFoldedValueSerializeWithoutAliasingTheirRows`, `aLegacyPairThatWouldCollideWithAuthIsNowIsolated`, `concurrentAttemptsOnOneSagaYieldExactlyOneOwner`, `killedOwnerCannotSaveAfterItsSuccessorOwns` |
| TD-014 baseline maintenance | `JdbcSagaStoreTest` (adapters) | `killedConnectionCannotOverwriteSuccessor` (now awaits real lock release by bounded polling) |
| TD-016 retry classification (NFR-005, NFR-009) | `RetryPolicyRemediationTest` (adapters) | `approvedStatusesAreTransient`, `requestTimeoutAndEveryOtherUnlistedStatusAreNotTransient`, `recognizedTransportFailuresAreTransientDirectOrWrapped`, `aCallTimeoutIsTransientAndCancelsThePendingRequest`, `arbitraryAndUnclassifiedFailuresAreNeverTransient`, `classificationOfTheDocumentedNonTransientCategoriesStaysExact`, `negativeNestedCausesDominateEvenWhenTheOuterFailureLooksTransient`, `causeCyclesTerminateAndKeepTheirClassification` |
| TD-016 durable budget (FR-007) | `RetryPolicyRemediationTest` | `requestTimeoutStatusConsumesOneDurableAttemptAndKeepsTheReservationFrozen`, `unclassifiedAndNegativeTransportFailuresSuspendAfterASingleAttempt`, `transientFailuresConsumeAtMostThreeAttemptsAndTwoPauses`, `aRestartedObjectContinuingTheSamePostGetsNoFreshBudget` |
| TD-016 baseline maintenance | `HttpSaleParticipantsTest` (adapters) | `statusClassificationAndSafeErrors` (expectation for 408 changed; nothing else) |
| TD-016 jitter strategy (NFR-006) | `FullJitterRetryDelayRemediationTest` (adapters) | `boundsDoubleFromOneHundredAndSaturateAtTwoThousandMilliseconds`, `nonPositiveAttemptsAreNormalizedToTheFirst`, `hugeAttemptsSaturateWithoutOverflow`, `supportedBudgetOfThreeAttemptsDrawsInclusiveBoundsOneHundredAndTwoHundred`, `zeroAndInteriorDrawsAreSleptExactly`, `explicitBoundsAreWhatTheDrawReceivesForEveryStep`, `pauseNormalizesAndSaturatesBeforeDrawing`, `aDrawOutsideTheInclusiveRangeIsRejectedWithoutSleeping`, `interruptionKeepsTheFlagAndFailsWithAFixedClassification`, `normalCompletionDoesNotRaiseTheInterruptFlag`, `productionFactoryIsAPlainRetryDelayStrategy` |
| TD-015 circuit state machine (NFR-002) | `ParticipantCircuitRemediationTest` (adapters) | `invalidConfigurationIsRejected`, `staysClosedBelowTheThresholdAndAllowsConcurrentCalls`, `opensAfterExactlyThresholdConsecutiveFailures`, `aSuccessResetsTheConsecutiveCount`, `neutralOutcomesNeitherCountNorReset`, `staysOpenUntilTheExactCooldownBoundary`, `onlyOneSimultaneousProbeIsAdmitted`, `aFailedProbeReopensAndTheCooldownRestartsFromTheFailure`, `aSuccessfulProbeClosesTheCircuitWithAFreshCount`, `aNeutralProbeFreesItAndDoesNotClaimTheParticipantIsAvailable`, `settlingTwiceCountsOnlyOnce`, `aSettledProbeCannotBeSettledAgain`, `aTokenFromBeforeOpeningCannotMutateTheRecoveredGeneration`, `staleTokensCannotCloseOrFreeAnOpenOrHalfOpenCircuit`, `aStaleFailureDuringHalfOpenDoesNotReopenBeforeTheProbeSettles`, `aStaleFailureWhileOpenDoesNotRestartTheCooldown`, `exactlyOneOfManyConcurrentCallersWinsTheProbe` |
| TD-015 decorator (NFR-002, FR-007) | `CircuitProtectedParticipantsRemediationTest` (adapters) | `healthyCallsPassTheOriginalArgumentsAndReceiptsThrough`, `participantFailuresPropagateUnchangedWhileTheCircuitIsClosed`, `customersOutageDoesNotAffectProductsOrSales`, `salesOutageDoesNotAffectCustomersOrProducts`, `reserveAndReleaseShareOneProductsCircuit`, `releaseFailuresAlsoOpenTheSharedProductsCircuit`, `circuitOpenIsAFixedPrivateFailureWithoutDriverText`, `onlyTransientFailuresCountAsOutage`, `neutralFailuresNeitherCountNorResetAPartialOutage`, `aValidatedSuccessResetsThePartialOutage`, `runtimeFailuresAreNeutralInAClosedCircuitAndPropagateSameInstance`, `refusesUntilTheCooldownThenAdmitsOneProbeAndAValidatedSuccessCloses`, `aTransientProbeFailureReopensAndWaitsAnewFromTheFailure`, `aNeutralProbeFreesItWithoutClosingTheCircuit`, `runtimeExceptionsAndErrorsFreeTheProbeAndPropagateUnchanged` |
| TD-015 through the use case (FR-007) | `CircuitProtectedParticipantsRemediationTest` | `openSalesBeforeDispatchSuspendsWithAtMostOneLocalAttemptAndKeepsTheReservation`, `openSalesAfterAPriorAmbiguousSaleKeepsTheMarkerAndConsumesAtMostOneMoreAttempt`, `openCustomersCircuitIsNeverAParticipantRejection`, `openProductsDuringCompensationEndsFailedAndNeverClaimsTheRelease`, `successfulProbeOfAnOpenCircuitLetsTheUseCaseCompleteAfterTheCooldown` |
| Composition (all three) | `WorkflowResilienceCompositionTest` (app) | `theParticipantsPortIsTheCircuitDecoratorOverTheRealHttpAdapter`, `theHttpAdapterKeepsTheApprovedTimeouts`, `theUseCaseUsesTheFullJitterStrategyBehindTheRetryDelayPort`, `theUseCaseKeepsTheThreeAttemptBudget` |

Existing regression suites are kept untouched apart from the two maintenance edits above: `RegisterSaleTest`,
`WorkflowHttpTest`, and the remaining tests of `HttpSaleParticipantsTest` and `JdbcSagaStoreTest`.

## 5. Patterns and reasons

- **Session lease through `ExecutionCoordinator`** (TD-014): ownership stays an advisory lock held by a dedicated session;
  the key derivation is extracted into a tiny pure class so it can be tested exhaustively and shared with `tryOwn`.
  The namespace keeps the workflow pair disjoint from Auth's `RTOK` pair. Folding 128 bits into one `int` can collide;
  a collision only serializes two sagas and cannot alias rows because rows are addressed by `saga_id`.
- **Strategy `RetryDelay`** (TD-016): the jitter policy becomes a named, injectable strategy with an explicit inclusive
  contract, so time and randomness are never part of a test. The use case keeps depending only on the port.
- **Circuit Breaker as decorator of `SaleParticipants`** (TD-015): the use case and the domain stay unaware of it. The
  state machine (`ParticipantCircuit`) is pure memory with synchronized transitions and never holds a monitor over I/O;
  tokens settle once and carry a generation so late outcomes of a previous era cannot mutate a recovered circuit.
- **Fail fast, durable budget**: refusal is a private `CIRCUIT_OPEN` failure that the unchanged use case treats as an
  ambiguous non-transient failure. This favors never claiming a rollback that was not performed (FR-007) over spending the
  retry budget against a participant known to be failing.

## 6. Test-design decisions and limits

- Expected lock values are precomputed literals; the jitter and circuit tests use injected draws, sleeps and a manual
  clock; the PostgreSQL tests use bounded polling (10 s deadline, 25 ms step) for backend termination and lock release
  instead of timing guesses. No fake PostgreSQL, no migration change.
- The retry tests use the existing public operations with a scripted `HttpClient` (no socket) and a local HTTP server
  only for status codes. `408` is classified as `INVALID_RESPONSE` exactly like other unlisted statuses; this is
  transport classification, not participant acceptance.
- Reflection appears only in `WorkflowResilienceCompositionTest`, to locate private collaborators by *type* and the
  `maxAttempts` field of `RegisterSale`; there is no source-text assertion anywhere.
- `openSales...` tests require exactly one newly persisted local attempt; no retry loop or budget replenishment is allowed.
- A restart of a `408` or unclassified failure is deliberately **not** asserted: the scanner skips rows carrying
  `OUTCOME_UNRESOLVED`, and changing use-case recovery is out of scope.
- Baseline maintenance (separate from the new behavior): the `408` expectation in `HttpSaleParticipantsTest`, and
  `killedConnectionCannotOverwriteSuccessor`, which now polls for the dead session's lock release with all fencing
  assertions preserved. This addresses the pre-existing asynchronous connection-termination test error noted above.
- Observed verification is listed in the execution report, with compile gaps and functional assertion failures separated. Agent verification is not independent human approval. A generic unclassified IOException is LOCAL_FAILURE; protocol/response-limit failures are INVALID_RESPONSE.

## 7. Compile-time API required before the first run

These are the only members the tests reference that do not exist in `develop`. A scaffold may add them **empty or
inert** (no working feature) strictly to compile; compilation errors are not functional RED.

1. `adapter.out.persistence.WorkflowLockKey`: `public static final int NAMESPACE`, `public static int value(UUID)`.
2. `adapter.out.http.FullJitterRetryDelay` implementing `RetryDelay`: constructor `(LongUnaryOperator, FullJitterRetryDelay.Sleeper)`,
   nested functional `Sleeper.sleep(long) throws InterruptedException`, `static long bound(int)`, `static FullJitterRetryDelay system()`.
3. `adapter.out.http.ParticipantCircuit` (package-private): constructor `(Clock, int, Duration)`, `Token tryEnter()`,
   nested `Token` with `success()`, `failure()`, `neutral()`.
4. `adapter.out.http.CircuitProtectedParticipants` implementing `SaleParticipants`: constructor `(SaleParticipants, Clock)`.
5. `ParticipantFailure.Kind.CIRCUIT_OPEN` (structural enum member, added only after the tests exist).

No existing signature changes. `WorkflowComposition.participants(...)` and `usecase(...)` keep their signatures.

## 8. Attribution and task status

| Work | Owner | Status |
|---|---|---|
| Requirements, design and seven new test classes | Claude Code; Codex reviewed the draft | Authored before feature implementation |
| Minimal inert compilation scaffold | Codex | Added after test authoring and recorded compilation failure; not a working feature |
| Functional RED | Codex | Adapters: 74 tests, 55 assertion failures, zero errors/skips; composition: 4 tests, 3 assertion failures, zero errors/skips |
| Production implementation and GREEN | Codex | 121 tests passed; exact code and records linked in execution-report.md |
| Acceptance, publication and remaining gates | Codex / actual reviewers | See execution-report.md; no human approval or persistent rollout inferred |

Raw source snapshots, logs and hash manifests stay outside the repository. The first test contract commit precedes the corrective implementation commits. The initial compile-only gap is not claimed as functional RED.

## Codex test-design review before first execution

The collision fixtures now use UUIDs in opposite 64-bit halves, so their legacy pairs differ but their accepted XOR values collide. Unexpected leases are closed before asserting refusal, preventing an intentional RED from leaking a connection into teardown. An explicit RTOK/1 legacy-collision fixture verifies actual namespace separation. Recognized socket reset failures remain retryable; bind, route and port-configuration subclasses remain excluded. This corrects the draft test's unsupported generic SocketException exclusion. Fail-fast assertions require exactly one persisted local attempt, preserving the existing pre-dispatch budget rule rather than allowing zero. These edits precede all remedial production work and functional RED executions.
