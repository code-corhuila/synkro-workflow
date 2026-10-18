CREATE INDEX idx_saga_instance_running ON workflow_schema.saga_instance (created_at) WHERE status='RUNNING';
