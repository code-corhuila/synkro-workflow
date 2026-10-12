REVOKE workflow_writer FROM workflow_app;
REVOKE ALL ON ALL TABLES IN SCHEMA workflow_schema FROM workflow_reader,workflow_writer;
REVOKE ALL ON SCHEMA workflow_schema FROM workflow_reader,workflow_writer;
