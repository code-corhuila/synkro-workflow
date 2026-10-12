CREATE TABLE workflow_schema.saga_instance (
  saga_id uuid NOT NULL DEFAULT gen_random_uuid(),
  saga_type text NOT NULL,
  idempotency_key text NOT NULL,
  status text NOT NULL DEFAULT 'RUNNING',
  initiated_by uuid NOT NULL,
  input jsonb NOT NULL,
  step_results jsonb NOT NULL DEFAULT '{}'::jsonb,
  completed_steps text[] NOT NULL DEFAULT '{}',
  failed_step text,
  error_detail text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_saga_instance PRIMARY KEY (saga_id),
  CONSTRAINT uq_saga_instance_idempotency_key UNIQUE (saga_type,idempotency_key),
  CONSTRAINT chk_saga_instance_type CHECK (saga_type IN ('register-sale')),
  CONSTRAINT chk_saga_instance_status CHECK (status IN ('RUNNING','COMPLETED','COMPENSATED','FAILED')),
  CONSTRAINT chk_saga_instance_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 8 AND 128)
);
