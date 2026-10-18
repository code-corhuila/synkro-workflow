DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='workflow_reader') THEN CREATE ROLE workflow_reader NOLOGIN; END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='workflow_writer') THEN CREATE ROLE workflow_writer NOLOGIN; END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('workflow_reader','workflow_writer') AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole)) THEN
    RAISE EXCEPTION 'Workflow roles must be restricted NOLOGIN roles';
  END IF;
END $$;
