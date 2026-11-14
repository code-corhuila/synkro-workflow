# Prepared corrective PR description

Title: fix(workflow): deliver resilience corrections with verified TDD

Workflow's lock namespace, retry policy and participant protection must match the accepted design. This new HU-WKF-04 delivery reserves WFLK keys, limits retries to approved transient categories with full jitter, and wraps Customers/Products/Sales with per-participant circuits while retaining durable budgets and uncertain reservations.

The original PR17 stays unchanged. Its process deviation is retained in HU-WKF-04. This candidate starts from develop2aa2f25, has an explicit tests/scaffold checkpoint before implementation commits, and supplies observed functional RED→GREEN for six groups. It is a replacement candidate for review, not a waiver or retrospective proof for PR17. No merge or automatic HU closure is requested.

Validation:121 Java tests, zero failures/errors/skips;93.92% global LINE; four Python guards and architecture check;44 real-Workflow/PostgreSQL controlled checks;21 separate actual Customers/Products/Sales checks with exact source pins. See execution-report.md for commits, fingerprints, initial baseline error/compile gap and scope limits. New TDD test sources were unchanged from functional RED through GREEN; reused real acceptance is attributed separately.

Pending: independent review of the corrective head, authorized QA/main promotion, coordinated quiescent rollout/scenario3 and downstream DOCS-98 publication. No Accepted ADR, schema, domain lifecycle, participant repository or public contract was changed. TD-012 stays excluded and open.

Refs code-corhuila/synkro-docs#316
Refs code-corhuila/synkro-docs#266
