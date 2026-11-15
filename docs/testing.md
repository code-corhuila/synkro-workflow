# Testing

Detailed procedures and delivery notes moved from the repository README at
[source 59e3107](https://github.com/code-corhuila/synkro-workflow/blob/59e3107ceff9c76bf2bacb993db556fb5a0e157f/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Validation

```bash
mvn -B verify
python tests/check_coverage.py
python -m unittest discover -s tests -p test_deployment.py
python -m pip install cryptography==46.0.3
python tests/process_acceptance.py
# Real services: see tests/real_services_acceptance.py for the six exact --pin inputs.
```

The owner CI needs no private sibling checkout. The process suite uses disposable real PostgreSQL and the actual Workflow JVM with controlled HTTP participants, including interruption after effects commit and failed local result writes. It removes its owned resources.

For local hybrid evidence, use clean immutable participant snapshots and run python tests/hybrid_acceptance.py --workspace /path/to/snapshots. Exact pins are checked before execution. The historical Customers 501 on GET /api/v1/customers/{id} does not describe Customers v2.0.0, which serves that operation; tests/hybrid_acceptance.py still asserts the old 501 and is kept only as historical controlled/hybrid evidence, not as a check against current Customers. tests/real_services_acceptance.py checks exact clean Customers/Products/Sales API and DB revisions without a controlled participant. Its latest executed scope and limits are in docs/execution-report.md; HU closure remains separate from a successful scoped run.
