DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='workflow_app' AND rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole) THEN
    RAISE EXCEPTION 'Required restricted workflow_app login is absent';
  END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS workflow_schema;
REVOKE ALL ON SCHEMA workflow_schema FROM PUBLIC;
