# Saga operations

Detailed procedures and delivery notes moved from the repository README at
[source 59e3107](https://github.com/code-corhuila/synkro-workflow/blob/59e3107ceff9c76bf2bacb993db556fb5a0e157f/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Register-sale Workflow

The Maven modules retain hexagonal ownership: workflow-core contains domain/use cases/ports; workflow-adapters contains HTTP/security, participant clients and PostgreSQL persistence; workflow-app composes dependencies and the bounded background lifecycle.

POST /api/v1/sagas/register-sale authenticates ADMIN/SALESPERSON with local Auth RS256 verification, validates 1–100 UUID product lines and positive integer quantities, and creates a durable resource before any external effect. First creation returns 201 with Location; equivalent caller/input replay returns 200. The approved conflicting replay policy is generic 403 for another caller and 400 for different normalized input. GET /api/v1/sagas/{id} returns only the documented public projection.

Read [execution design](persistent-register-sale-design.md), [deployment and recovery](deployment-and-recovery.md), and the [prepared documentation handoff](synkro-docs-handoff.md). Populate protected placeholders from .env.example. Bootstrap workflow_app through infrastructure, run the owning workflow-db-migrate tooling service against shared synkro-db, then start Workflow on internal 8080. Public /health checks the actual saga store.
