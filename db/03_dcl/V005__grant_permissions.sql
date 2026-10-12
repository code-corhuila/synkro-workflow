REVOKE ALL ON SCHEMA workflow_schema FROM PUBLIC,workflow_app,workflow_reader,workflow_writer;
REVOKE ALL ON ALL TABLES IN SCHEMA workflow_schema FROM PUBLIC,workflow_app,workflow_reader,workflow_writer;
GRANT USAGE ON SCHEMA workflow_schema TO workflow_reader,workflow_writer;
GRANT SELECT ON workflow_schema.saga_instance TO workflow_reader;
GRANT SELECT,INSERT,UPDATE ON workflow_schema.saga_instance TO workflow_writer;
GRANT workflow_writer TO workflow_app;
