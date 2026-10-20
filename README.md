# synkro-workflow

> Business process orchestration (saga)

Part of the **SynkroTech SAS Sales Management System** — organization `code-corhuila`.
Governance and documentation live in [`synkro-docs`](https://github.com/code-corhuila/synkro-docs).

## Register-sale Workflow

The Maven modules retain hexagonal ownership: workflow-core contains domain/use cases/ports; workflow-adapters contains HTTP/security, participant clients and PostgreSQL persistence; workflow-app composes dependencies and the bounded background lifecycle.

POST /api/v1/sagas/register-sale authenticates ADMIN/SALESPERSON with local Auth RS256 verification, validates 1�100 UUID product lines and positive integer quantities, and creates a durable resource before any external effect. First creation returns 201 with Location; equivalent caller/input replay returns 200. The approved conflicting replay policy is generic 403 for another caller and 400 for different normalized input. GET /api/v1/sagas/{id} returns only the documented public projection.

Read [execution design](docs/persistent-register-sale-design.md), [deployment and recovery](docs/deployment-and-recovery.md), and the [prepared documentation handoff](docs/synkro-docs-handoff.md). Populate protected placeholders from .env.example. Bootstrap workflow_app through infrastructure, run the owning workflow-db-migrate tooling service against shared synkro-db, then start Workflow on internal 8080. Public /health checks the actual saga store.

## Validation

```bash
mvn -B verify
python tests/check_coverage.py
python -m unittest discover -s tests -p test_deployment.py
python -m pip install cryptography==46.0.3
python tests/process_acceptance.py
```

The owner CI needs no private sibling checkout. The process suite uses disposable real PostgreSQL and the actual Workflow JVM with controlled HTTP participants, including interruption after effects commit and failed local result writes. It removes its owned resources.

For local hybrid evidence, use clean immutable participant snapshots and run python tests/hybrid_acceptance.py --workspace /path/to/snapshots. Exact pins are checked before execution. The historical Customers 501 on GET /api/v1/customers/{id} does not describe Customers v2.0.0, which serves that operation; tests/hybrid_acceptance.py still asserts the old 501 and is kept only as historical controlled/hybrid evidence, not as a check against current Customers. Fully real acceptance (WKF-16) must run against exact participant revisions and is recorded in the release pull request, not in this file; HU closure remains a separate documentation decision.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `synkro-docs`.
