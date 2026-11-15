# Resilience and rollout

Detailed procedures and delivery notes moved from the repository README at
[source 59e3107](https://github.com/code-corhuila/synkro-workflow/blob/59e3107ceff9c76bf2bacb993db556fb5a0e157f/README.md).
Recorded execution results retain their original scope; moving them does not
constitute a new execution or certify a different revision.

Run commands from the repository root unless a step explicitly changes directory.

## Workflow resilience correction (HU-WKF-04)

The [test-first design](tdd-remediation-design.md) and [execution report](execution-report.md) identify the new remedial delivery separately from original PR17. Workflow uses the WFLK advisory namespace, approved retry categories with full jitter, and one circuit per participant (Products reserve/release share state). Open circuits preserve uncertain reservations. The [stop-all rollout procedure](deployment-and-recovery.md#coordinated-lock-algorithm-switch--not-executed) must precede an actual algorithm switch.
