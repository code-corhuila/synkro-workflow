"""Owner deployment contract; no sibling source or private CI credentials."""
import json
import os
from pathlib import Path
import subprocess
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[1]

class DeploymentTest(unittest.TestCase):
    def test_owner_runner_and_separate_application_credentials(self):
        env = dict(os.environ, FLYWAY_URL='jdbc:postgresql://synkro-db:5432/synkro?connectTimeout=3&socketTimeout=10',
                   FLYWAY_USER='disposable_admin', FLYWAY_PASSWORD='dummy-runner-only',
                   WORKFLOW_DATABASE_URL='jdbc:postgresql://synkro-db:5432/synkro',
                   WORKFLOW_APP_PASSWORD_PATH=str(ROOT / '.env.example'),
                   JWT_PUBLIC_KEY_PATH=str(ROOT / '.env.example'), WORKFLOW_SERVICE_TOKEN='dummy-service')
        model = json.loads(subprocess.run(['docker','compose','-f',str(ROOT / 'deploy/compose.yml'),'--profile','tooling','config','--format','json'],
                          capture_output=True, check=True, timeout=30, env=env).stdout)
        services = model['services']
        self.assertEqual(set(services), {'synkro-workflow','workflow-db-migrate'})
        runner = services['workflow-db-migrate']
        self.assertEqual(runner['image'], 'flyway/flyway:11.20.3-alpine')
        self.assertEqual(runner['profiles'], ['tooling'])
        self.assertTrue(all(v['read_only'] for v in runner['volumes']))
        self.assertFalse(runner.get('ports'))
        app = services['synkro-workflow']
        self.assertFalse(app.get('ports'))
        self.assertEqual(app['expose'], ['8080'])
        self.assertNotIn('FLYWAY_PASSWORD', app['environment'])
        self.assertNotIn('DB_ADMIN_PASSWORD', app['environment'])
        self.assertEqual(app['environment']['WORKFLOW_APP_PASSWORD_FILE'], '/run/secrets/workflow_app_password')
        self.assertTrue(model['networks']['platform']['external'])
        config = tomllib.loads((ROOT / 'db/flyway.toml').read_text())
        self.assertEqual(config['environments']['default']['schemas'], ['workflow_schema'])
        self.assertEqual(config['flyway']['defaultSchema'], 'workflow_schema')
        self.assertTrue(config['flyway']['cleanDisabled'])
        self.assertLessEqual(config['environments']['default']['connectRetries'], 5)
        self.assertNotIn('workflow-db:', (ROOT / 'deploy/compose.yml').read_text())
    def test_no_automatic_migration_and_readonly_sql(self):
        properties = (ROOT / 'workflow-app/src/main/resources/application.properties').read_text()
        self.assertIn('spring.flyway.enabled=false', properties)
        self.assertIn('spring.sql.init.mode=never', properties)
        self.assertFalse(list((ROOT/'db/01_ddl').rglob('U*.sql')))
        self.assertFalse(list((ROOT/'db/03_dcl').rglob('U*.sql')))
        self.assertEqual(len(list((ROOT/'db/05_rollbacks').rglob('U*.sql'))),5)

if __name__ == '__main__':
    unittest.main()
