"""Real PostgreSQL/Workflow processes with explicitly controlled HTTP participants.
No host database port. Commands discard raw diagnostics and never print credentials.
"""
import argparse
import base64
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from cryptography.hazmat.primitives.asymmetric import rsa,padding
from cryptography.hazmat.primitives import hashes,serialization

ROOT=Path(__file__).resolve().parents[1]
PG='postgres:16.15-alpine3.23'
PYTHON='python:3.13.7-alpine3.22'

def execution_id():
    identifier=uuid.uuid4().hex
    destination=os.environ.get('SYNKRO_LOCAL_OWNER_FILE')
    if destination:
        file=Path(destination);parent=file.parent.resolve()
        try:
            valid=file.name=='.execution-ids' and re.fullmatch(r'run-[0-9a-f]{32}',parent.name) and (parent/'.local-owner').read_text()==parent.name
        except OSError:valid=False
        if not valid:raise RuntimeError('Local cleanup registration refuses unrelated storage')
        with file.open('a',encoding='utf-8') as output:output.write(identifier+'\n')
    return identifier

def run(args,*,data=None,env=None,timeout=180,success=True):
    result=subprocess.run(args,input=data,capture_output=True,env=env,timeout=timeout)
    if success and result.returncode:raise RuntimeError('Required command failed; command family '+args[1]+'; raw diagnostics suppressed')
    return result
def check(condition,label):
    if not condition:raise RuntimeError(label)
    print('PASSED '+label,flush=True)
def wait(checker,timeout=45):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=checker()
            if result:return result
        except Exception:pass
        time.sleep(.2)
    raise RuntimeError('Bounded readiness/recovery timeout')
def token(key,subject,roles,permissions):
    def enc(value):return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).rstrip(b'=').decode()
    value=enc({'alg':'RS256','typ':'JWT'})+'.'+enc({'sub':subject,'roles':roles,'permissions':permissions,'iat':int(time.time())-5,'exp':int(time.time())+3600,'jti':str(uuid.uuid4())})
    signature=key.sign(value.encode(),padding.PKCS1v15(),hashes.SHA256())
    return value+'.'+base64.urlsafe_b64encode(signature).rstrip(b'=').decode()
def execute(image=None):
    identity=execution_id();project='synkro-wkf-'+identity;network=project+'-network'
    containers=[];owns_image=image is None;image=image or project+':candidate';records=[]
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    caller,customer,product=(str(uuid.uuid4()) for _ in range(3))
    human=token(key,caller,['SALESPERSON'],['sales:create'])
    service=token(key,'synkro-workflow',['SERVICE'],['customers:read','stock:reserve','stock:release','sales:register'])
    with tempfile.TemporaryDirectory(prefix='synkro-wkf-disposable-') as folder:
        folder=Path(folder);(folder/'public.pem').write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        (folder/'password').write_text('disposable-workflow-app')
        (folder/'config.json').write_text(json.dumps(dict(human=human,service=service,customer=customer,product=product)))
        shutil.copyfile(ROOT/'tests/controlled_participants.py',folder/'participants.py')
        env=dict(os.environ,COMPOSE_PROJECT_NAME=project,PLATFORM_NETWORK=network,FLYWAY_URL='jdbc:postgresql://synkro-db:5432/synkro?connectTimeout=3&socketTimeout=10',FLYWAY_USER='postgres',FLYWAY_PASSWORD='disposable-admin',WORKFLOW_APP_PASSWORD_PATH=str(folder/'password'),JWT_PUBLIC_KEY_PATH=str(folder/'public.pem'),WORKFLOW_SERVICE_TOKEN=service)
        override=folder/'runner-labels.json'
        override.write_text(json.dumps({'services':{'workflow-db-migrate':{'labels':{'synkro.execution':identity,'synkro.disposable':'true'}}}}))
        compose=['docker','compose','-p',project,'-f',str(ROOT/'deploy/compose.yml'),'-f',str(override),'--profile','tooling']
        def container(suffix,image,args):
            name=project+'-'+suffix
            run(['docker','run','-d','--name',name,'--label','synkro.execution='+identity,'--label','synkro.disposable=true','--network',network]+args+[image])
            containers.append(name);return name
        def sql(text,user='postgres',accepted=True):
            result=run(['docker','exec','-i','-e','PGPASSWORD='+('disposable-admin' if user=='postgres' else 'disposable-workflow-app'),database,'psql','-X','-h','127.0.0.1','-U',user,'-d','synkro','-qAt','-v','ON_ERROR_STOP=1'],data=text.encode(),success=False,timeout=30)
            if accepted and result.returncode:raise RuntimeError('Required database operation failed; raw diagnostics suppressed')
            if not accepted and result.returncode==0:raise RuntimeError('Forbidden database operation succeeded')
            return result.stdout.decode().strip()
        def fixture(path,body=None):
            code="import json,urllib.request; r=urllib.request.Request('http://localhost:8080"+path+"',data=None if "+repr(body is None)+" else "+repr(json.dumps(body).encode() if body is not None else b'')+",headers={'Content-Type':'application/json'}); print(urllib.request.urlopen(r,timeout=15).read().decode())"
            return json.loads(run(['docker','exec',participants,'python','-c',code],timeout=30).stdout)
        def request(path,method='GET',body=None,idempotency=None,auth=True,host='workflow'):
            headers={'Content-Type':'application/json','X-Correlation-Id':'process-correlation'}
            if auth:headers['Authorization']='Bearer '+human
            if idempotency:headers['Idempotency-Key']=idempotency
            return fixture('/probe',dict(url='http://'+host+':8080'+path,method=method,body=body,headers=headers))
        def completed(location,status):
            response=request(location);return response if response['status']==200 and response['body']['status']==status else None
        def start(mode):
            fixture('/mode',dict(mode=mode))
            response=request('/api/v1/sagas/register-sale','POST',dict(customerId=customer,lines=[dict(productId=product,quantity=2)]),'process-'+str(uuid.uuid4()))
            check(response['status']==201,'HTTP first creation '+mode)
            return response['headers']['Location']
        try:
            run(['docker','network','create','--label','synkro.execution='+identity,'--label','synkro.disposable=true',network])
            database=container('db',PG,['--network-alias','synkro-db','-e','POSTGRES_DB=synkro','-e','POSTGRES_PASSWORD=disposable-admin'])
            wait(lambda:run(['docker','exec',database,'pg_isready','-h','127.0.0.1','-U','postgres','-d','synkro'],success=False,timeout=5).returncode==0)
            missing=run(compose+['run','--rm','--no-deps','workflow-db-migrate'],env=env,success=False,timeout=90)
            check(missing.returncode!=0,'missing bootstrap login fails safely')
            sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE workflow_app LOGIN PASSWORD 'disposable-workflow-app'; CREATE ROLE customers_app LOGIN PASSWORD 'disposable-other'; CREATE SCHEMA customers_schema; CREATE TABLE customers_schema.private(id int)")
            run(compose+['run','--rm','--no-deps','workflow-db-migrate'],env=env,timeout=120)
            check(sql("SELECT count(*) FROM workflow_schema.flyway_schema_history WHERE success AND version IS NOT NULL")=='5','real owning runner first migration')
            run(compose+['run','--rm','--no-deps','workflow-db-migrate'],env=env,timeout=120)
            check(sql("SELECT count(*) FROM workflow_schema.flyway_schema_history WHERE success AND version IS NOT NULL")=='5','real owning runner zero-change replay')
            check(sql("SELECT to_regclass('public.flyway_schema_history') IS NULL")=='t','isolated migration history')
            for denied in ('DELETE FROM workflow_schema.saga_instance','TRUNCATE workflow_schema.saga_instance','CREATE TABLE workflow_schema.illegal(id int)','SELECT * FROM customers_schema.private','SELECT * FROM workflow_schema.flyway_schema_history'):
                sql(denied,'workflow_app',False)
            check(True,'real application DELETE TRUNCATE DDL cross-schema and history denial')
            tampered=folder/'tampered';shutil.copytree(ROOT/'db',tampered)
            with (tampered/'01_ddl/03_tables/V002__create_saga_instance.sql').open('a') as changed:changed.write('\n-- changed checksum\n')
            failure=run(['docker','run','--rm','--label','synkro.execution='+identity,'--label','synkro.disposable=true','--network',network,'-v',str(tampered)+':/flyway/workflow:ro','-e','FLYWAY_URL='+env['FLYWAY_URL'],'-e','FLYWAY_USER=postgres','-e','FLYWAY_PASSWORD=disposable-admin','flyway/flyway:11.20.3-alpine','-workingDirectory=/flyway/workflow','-configFiles=flyway.toml','validate'],success=False,timeout=90)
            check(failure.returncode!=0,'real owning Flyway checksum protection')
            scripts=sorted((ROOT/'db/05_rollbacks').rglob('U*.sql'),key=lambda p:p.name,reverse=True)
            for script in scripts:
                if script.name.startswith('U001'):sql('DROP TABLE workflow_schema.flyway_schema_history')
                sql(script.read_text())
            run(compose+['run','--rm','--no-deps','workflow-db-migrate'],env=env,timeout=120)
            check(sql("SELECT count(*) FROM workflow_schema.flyway_schema_history WHERE success AND version IS NOT NULL")=='5','disposable rollback and rebuild')
            if owns_image:run(['docker','build','--label','synkro.execution='+identity,'-t',image,'-f',str(ROOT/'deploy/Dockerfile'),str(ROOT)],timeout=900)
            participants=project+'-participants'
            run(['docker','run','-d','--name',participants,'--label','synkro.execution='+identity,'--label','synkro.disposable=true','--network',network,'--network-alias','participants','-v',str(folder)+':/fixtures:ro',PYTHON,'python','/fixtures/participants.py']);containers.append(participants)
            wait(lambda:fixture('/health')['status']=='ok')
            app=container('workflow',image,['--network-alias','workflow','-v',str(folder/'public.pem')+':/run/public.pem:ro','-v',str(folder/'password')+':/run/password:ro',
                '-e','WORKFLOW_DATABASE_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','WORKFLOW_APP_PASSWORD_FILE=/run/password','-e','JWT_PUBLIC_KEY_FILE=/run/public.pem',
                '-e','SERVICE_TOKEN='+service,'-e','CUSTOMERS_BASE_URL=http://participants:8080','-e','PRODUCTS_BASE_URL=http://participants:8080','-e','SALES_BASE_URL=http://participants:8080',
                '-e','WORKFLOW_RESPONSE_TIMEOUT_MS=100','-e','WORKFLOW_SCAN_INTERVAL_MS=60000'])
            wait(lambda:request('/health',auth=False)['status']==200,90)
            location=start('normal');wait(lambda:completed(location,'COMPLETED'))
            check(fixture('/state')['counts']==dict(reserve=1,sale=1,release=0),'controlled complete register-sale against real store/process')
            # Two independent JVMs race the same unique key and store ownership.
            metadata=json.loads(run(['docker','inspect',app]).stdout)[0]
            peer_args=['--network-alias','workflow-peer','-v',str(folder/'public.pem')+':/run/public.pem:ro','-v',str(folder/'password')+':/run/password:ro']
            for value in metadata['Config']['Env']:peer_args+=['-e',value]
            peer=container('workflow-peer',image,peer_args)
            wait(lambda:request('/health',auth=False,host='workflow-peer')['status']==200,90)
            fixture('/mode',dict(mode='normal'));race_key='concurrent-'+str(uuid.uuid4())
            payload=dict(customerId=customer,lines=[dict(productId=product,quantity=2)])
            with ThreadPoolExecutor(max_workers=8) as pool:
                responses=list(pool.map(lambda i:request('/api/v1/sagas/register-sale','POST',payload,race_key,host='workflow' if i%2 else 'workflow-peer'),range(8)))
            check(sum(r['status']==201 for r in responses)==1 and sum(r['status']==200 for r in responses)==7 and len({r['body']['sagaId'] for r in responses})==1,'two real JVMs converge concurrent POST on one stored resource')
            location='/api/v1/sagas/'+responses[0]['body']['sagaId'];wait(lambda:completed(location,'COMPLETED'))
            check(fixture('/state')['counts']==dict(reserve=1,sale=1,release=0),'concurrent real executors invoke each effect once')
            run(['docker','rm','-fv',peer]);containers.remove(peer)
            location=start('sale-reject');wait(lambda:completed(location,'COMPENSATED'))
            check(fixture('/state')['counts']==dict(reserve=1,sale=0,release=1),'controlled confirmed failure compensates exactly once')
            location=start('sale-lost');wait(lambda:completed(location,'COMPLETED'))
            check(fixture('/state')['counts']==dict(reserve=1,sale=1,release=0),'lost committed Sales response replays without duplicate or release')
            location=start('sale-timeout-status');wait(lambda:completed(location,'COMPLETED'))
            check(fixture('/state')['counts']==dict(reserve=1,sale=1,release=0),'committed sale followed by HTTP 408 resolves idempotently without release')
            location=start('sale-undocumented-conflict')
            wait(lambda:sql("SELECT error_detail FROM workflow_schema.saga_instance WHERE saga_id='"+location.split('/')[-1]+"'")=='OUTCOME_UNRESOLVED')
            check(request(location)['body']['status']=='RUNNING' and fixture('/state')['counts']['release']==0,'undocumented Sales response preserves ambiguous committed outcome and stock')
            for mode,status in [('reserve-pause','COMPLETED'),('sale-pause','COMPLETED'),('release-pause','COMPENSATED')]:
                location=start(mode);wait(lambda:fixture('/state')['paused'])
                run(['docker','kill',app]);run(['docker','start',app]);wait(lambda:request('/health',auth=False)['status']==200,90)
                wait(lambda:sql("SELECT status FROM workflow_schema.saga_instance WHERE saga_id='"+location.split('/')[-1]+"'")==status,45)
                check(request(location)['body']['status']==status,'startup resumption completes without saga polling '+mode)
                expected=dict(reserve=1,sale=0 if status=='COMPENSATED' else 1,release=1 if status=='COMPENSATED' else 0)
                check(fixture('/state')['counts']==expected,'real JVM interruption after external commit '+mode)
            # Disposable fault injection rejects exactly the local reservation-result
            # write, after Products commits. It is never a production migration.
            sql("CREATE FUNCTION workflow_schema.test_fail_result() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.completed_steps @> ARRAY['reserve-stock']::text[] THEN RAISE EXCEPTION 'Disposable result write fault'; END IF; RETURN NEW; END $$; CREATE TRIGGER test_fail_result BEFORE UPDATE ON workflow_schema.saga_instance FOR EACH ROW EXECUTE FUNCTION workflow_schema.test_fail_result()")
            location=start('normal');wait(lambda:fixture('/state')['counts']['reserve']==1)
            check(fixture('/state')['counts']['sale']==0,'store result write failure prevents next external step')
            sql('DROP TRIGGER test_fail_result ON workflow_schema.saga_instance; DROP FUNCTION workflow_schema.test_fail_result()');run(['docker','kill',app]);run(['docker','start',app])
            wait(lambda:completed(location,'COMPLETED'),90);check(fixture('/state')['counts']['reserve']==1,'restart after store write failure replays reservation')
            location=start('sale-ambiguous');wait(lambda:sql("SELECT error_detail FROM workflow_schema.saga_instance WHERE saga_id='"+location.split('/')[-1]+"'")=='OUTCOME_UNRESOLVED')
            check(request(location)['body']['status']=='RUNNING' and fixture('/state')['counts']['release']==0,'ambiguous sale remains recoverable RUNNING without release')
            fixture('/mode',dict(mode='customer-unavailable'));check(request('/health',auth=False)['status']==200,'participant outage does not affect store health')
            check(human not in sql('SELECT input::text||step_results::text FROM workflow_schema.saga_instance') and service not in sql('SELECT input::text||step_results::text FROM workflow_schema.saga_instance'),'human and SERVICE tokens absent from persisted state')
            run(['docker','stop',database]);check(request('/health',auth=False)['status']==503,'actual store outage returns 503')
            run(['docker','start',database]);wait(lambda:request('/health',auth=False)['status']==200,60)
            check(request(location)['body']['status']=='RUNNING','durable state survives PostgreSQL stop/start')
            started=time.monotonic();run(['docker','stop','--time','12',app],timeout=20)
            check(time.monotonic()-started<15,'bounded graceful Workflow shutdown')
            print('BLOCKED fully real acceptance: these participant fixtures are controlled',flush=True)
        finally:
            for name in reversed(containers):
                inspected=run(['docker','inspect',name],success=False)
                if inspected.returncode==0:
                    metadata=json.loads(inspected.stdout)[0]
                    if metadata['Config']['Labels'].get('synkro.execution')!=identity:raise RuntimeError('Cleanup ownership mismatch')
                    run(['docker','rm','-fv',name],timeout=60)
            # Only leftover runner containers belonging to this unique Compose project.
            left=run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project]).stdout.decode().split()
            for name in left:run(['docker','rm','-fv',name])
            metadata=run(['docker','network','inspect',network],success=False)
            if metadata.returncode==0:
                check(json.loads(metadata.stdout)[0]['Labels'].get('synkro.execution')==identity,'cleanup network ownership')
                run(['docker','network','rm',network])
            if owns_image:run(['docker','image','rm',image],success=False)
            print('PASSED disposable cleanup',flush=True)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--image');args=parser.parse_args()
    try:execute(args.image)
    except Exception as error:
        print('FAILED '+str(error),flush=True)
        raise SystemExit(1)
