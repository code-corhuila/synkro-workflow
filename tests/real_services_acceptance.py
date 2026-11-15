"""FULLY REAL acceptance harness (HU-WKF-04): actual Customers, Products and Sales services.

Scope label: REAL-SERVICES. Nothing here is controlled/hybrid: no fixture participant is started, and the
scenario labels below must never be copied into controlled-HTTP or hybrid evidence. tests/hybrid_acceptance.py
(controlled Customers) and tests/controlled_participants.py stay historical and separately labeled.

Requires Docker, cryptography 46.0.3 and clean read-only owner snapshots at the exact revisions passed with --pin.
It only reads those snapshots (never modifies them) and removes every container/network/image it owns.
Customers runtime wiring belongs to the Customers owner: pass its environment with --customers-env KEY=VALUE
(repeatable; JWT public key text is available as {PUBLIC_KEY}, the PostgreSQL URL host is synkro-db) and, when its
owner contract needs a different seed payload, --customer-json. Run (example):

  python tests/real_services_acceptance.py --workspace <dir with the six snapshots> \
    --pin synkro-customers-api=<sha> --pin synkro-customers-db=<sha> --pin synkro-products-api=<sha> \
    --pin synkro-products-db=<sha> --pin synkro-sales-api=<sha> --pin synkro-sales-db=<sha> \
    --customers-env SPRING_DATASOURCE_URL=jdbc:postgresql://synkro-db:5432/synkro \
    --customers-env SPRING_DATASOURCE_USERNAME=customers_app \
    --customers-env SPRING_DATASOURCE_PASSWORD=<disposable-customers-password> \
    --customers-env JWT_PUBLIC_KEY={PUBLIC_KEY}
"""
import argparse, json, shutil, tempfile, uuid
from pathlib import Path
from process_acceptance import run, check, wait, token, execution_id, ROOT, PG, PYTHON
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

REQUIRED=('synkro-customers-api','synkro-customers-db','synkro-products-api','synkro-products-db','synkro-sales-api','synkro-sales-db')
SCOPE='REAL-SERVICES'

def verify_pins(workspace,pins):
    """Exact source SHAs on clean snapshots; returns the evidence lines to record."""
    missing=[name for name in REQUIRED if name not in pins]
    if missing:raise ValueError('Missing --pin for '+', '.join(missing))
    lines=[]
    for name in REQUIRED:
        source=workspace/name
        head=run(['git','-C',str(source),'rev-parse','HEAD']).stdout.decode().strip()
        dirty=run(['git','-C',str(source),'status','--porcelain']).stdout.strip()
        check(head==pins[name] and not dirty,SCOPE+' exact clean source '+name+' '+head)
        lines.append(name+'='+head)
    return lines

def parse_pairs(values,option):
    pairs={}
    for value in values:
        if '=' not in value:raise ValueError(option+' expects KEY=VALUE')
        key,_,text=value.partition('=');pairs[key]=text
    return pairs

def execute(workspace,pins,customers_env,customer_payload):
    evidence=verify_pins(workspace,pins)
    owner=execution_id();prefix='synkro-real-'+owner;network=prefix+'-network';containers=[];images=[]
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    caller,product,category=(str(uuid.uuid4()) for _ in range(3))
    human=token(key,caller,['SALESPERSON'],['sales:create'])
    admin=token(key,str(uuid.uuid4()),['ADMIN'],['customers:create','customers:read'])
    service=token(key,'synkro-workflow',['SERVICE'],['customers:read','stock:reserve','stock:release','sales:register'])
    with tempfile.TemporaryDirectory(prefix='synkro-real-owned-') as temporary:
        folder=Path(temporary);public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
        (folder/'public.pem').write_bytes(public);(folder/'password').write_text('disposable-workflow-app')
        def launch(suffix,image,args=(),command=()):
            name=prefix+'-'+suffix;containers.append(name)
            run(['docker','run','-d','--name',name,'--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,*args,image,*command],timeout=180)
            return name
        def build(name,source,dockerfile):
            print('RUN '+SCOPE+' build actual '+name,flush=True)
            image=prefix+'-'+name+':candidate';images.append(image)
            run(['docker','build','--label','synkro.execution='+owner,'-t',image,'-f',str(dockerfile),str(source)],timeout=1200);return image
        def sql(query):
            return run(['docker','exec','-i',database,'psql','-X','-U','postgres','-d','synkro','-qAt','-v','ON_ERROR_STOP=1'],data=query.encode()).stdout.decode().strip()
        def mongo(query,authenticated=True):
            args=['--username','admin','--password','disposable-admin','--authenticationDatabase','admin'] if authenticated else []
            try:return run(['docker','exec',mongodb,'mongosh','--quiet',*args,'--eval',query],timeout=30).stdout.decode().strip()
            except Exception:raise RuntimeError(SCOPE+' Mongo bootstrap command failed') from None
        def probe(url,method='GET',body=None,idem=None,credential=service,trace='real-correlation'):
            """HTTP from inside the owned network through a throwaway python container; no fixture participant is involved."""
            headers={'Content-Type':'application/json','Authorization':'Bearer '+credential,'X-Correlation-Id':trace}
            if idem:headers['Idempotency-Key']=idem
            code=("import json,urllib.request,urllib.error\n"
                  "r=urllib.request.Request("+repr(url)+",data="+repr(json.dumps(body).encode() if body is not None else None)+",method="+repr(method)+",headers="+repr(headers)+")\n"
                  "try: x=urllib.request.urlopen(r,timeout=15)\nexcept urllib.error.HTTPError as e: x=e\n"
                  "b=x.read().decode()\nprint(json.dumps(dict(status=x.status if hasattr(x,'status') else x.code,headers=dict(x.headers),body=json.loads(b) if b.startswith('{') else b)))")
            try:return json.loads(run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,PYTHON,'python','-c',code],timeout=60).stdout)
            except Exception:raise RuntimeError(SCOPE+' HTTP probe failed for '+url.split(':8080')[-1]) from None
        try:
            run(['docker','network','create','--label','synkro.execution='+owner,'--label','synkro.disposable=true',network])
            database=launch('pg',PG,['--network-alias','synkro-db','-e','POSTGRES_DB=synkro','-e','POSTGRES_PASSWORD=disposable-admin'])
            wait(lambda:run(['docker','exec',database,'pg_isready','-h','127.0.0.1','-U','postgres','-d','synkro'],success=False).returncode==0)
            sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE workflow_app LOGIN PASSWORD 'disposable-workflow-app'; CREATE ROLE products_app LOGIN PASSWORD 'disposable-products-app'; CREATE ROLE customers_app LOGIN PASSWORD 'disposable-customers-app'")
            for source, image in [(ROOT/'db','flyway/flyway:11.20.3-alpine'),(workspace/'synkro-products-db','flyway/flyway:10.20.1')]:
                run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,'-v',str(source)+':/flyway/owner:ro','-e','FLYWAY_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','FLYWAY_USER=postgres','-e','FLYWAY_PASSWORD=disposable-admin',image,'-workingDirectory=/flyway/owner','-configFiles=flyway.toml','migrate'],timeout=180)
            # Keep Customers' actual runner guards and pinned image, rather than bypassing its wrapper.
            source=workspace/'synkro-customers-db'
            run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,'-v',str(source)+':/workspace:ro','-w','/workspace','-e','FLYWAY_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','FLYWAY_USER=postgres','-e','FLYWAY_PASSWORD=disposable-admin','--entrypoint','/bin/sh','flyway/flyway:10.20.1','/workspace/scripts/migrate.sh','migrate'],timeout=180)
            sql(f"INSERT INTO products_schema.category(category_id,name) VALUES ('{category}','Real category'); INSERT INTO products_schema.product(product_id,name,price_cents,stock,category_id) VALUES ('{product}','Real product',125,20,'{category}')")
            mongodb=launch('mongo','mongo:7.0',['--tmpfs','/data/db','--tmpfs','/data/configdb','--entrypoint','bash'],['-c','openssl rand -base64 48 > /tmp/keyfile && chmod 400 /tmp/keyfile && chown mongodb /tmp/keyfile && exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /tmp/keyfile'])
            wait(lambda:run(['docker','exec',mongodb,'mongosh','--quiet','--eval','db.adminCommand({ping:1})'],success=False).returncode==0)
            mongo('rs.initiate({_id:"rs0",members:[{_id:0,host:"'+mongodb+':27017"}]})',False)
            wait(lambda:'true' in mongo('db.hello().isWritablePrimary',False))
            mongo('db.getSiblingDB("admin").createUser({user:"admin",pwd:"disposable-admin",roles:["root"]})',False)
            mongo('db.getSiblingDB("admin").createUser({user:"sales_app",pwd:"disposable-sales-app",roles:[]})')
            source=workspace/'synkro-sales-db';runner=build('sales-runner',source/'deploy',source/'deploy/liquibase.Dockerfile')
            run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,'-v',str(source)+':/liquibase/changelog-root:ro','-w','/liquibase/changelog-root','-e','LIQUIBASE_COMMAND_URL=mongodb://admin:disposable-admin@'+mongodb+':27017/sales?replicaSet=rs0&authSource=admin','-e','LIQUIBASE_COMMAND_CHANGELOG_FILE=changelog/changelog-master.yaml',runner,'update'],timeout=300)
            for name in ['customers','products','sales']:
                source=workspace/('synkro-'+name+'-api');image=build(name,source,source/'deploy/Dockerfile')
                args=['--network-alias',name,'-v',str(folder/'public.pem')+':/run/public.pem:ro']
                if name=='customers':
                    for k,v in customers_env.items():args+=['-e',k+'='+v.replace('{PUBLIC_KEY}',public.decode())]
                if name=='products':args+=['-e','JWT_PUBLIC_KEY='+public.decode(),'-e','DATABASE_URL=postgres://products_app:disposable-products-app@synkro-db:5432/synkro?sslmode=disable']
                if name=='sales':args+=['-e','JWT_PUBLIC_KEY_FILE=/run/public.pem','-e','MONGODB_URI=mongodb://sales_app:disposable-sales-app@'+mongodb+':27017/sales?replicaSet=rs0&authSource=admin&retryWrites=false','-e','MONGODB_TIMEOUT=5s']
                launch(name,image,args)
            for name in ['customers','products','sales']:wait(lambda n=name:probe('http://'+n+':8080/health')['status']==200,120)
            # Actual Customers must now serve the lookup (the historical 501 blocker is NOT assumed or accepted).
            created=probe('http://customers:8080/api/v1/customers','POST',customer_payload,'real-customer',admin)
            check(created['status'] in (200,201),SCOPE+' actual Customers creates the seed customer (HTTP '+str(created['status'])+')')
            customer=created['body']['customerId']
            found=probe('http://customers:8080/api/v1/customers/'+customer)
            check(found['status']==200 and found['body']['active'] is True,SCOPE+' actual Customers authenticated lookup of an active customer')
            check(probe('http://customers:8080/api/v1/customers/'+str(uuid.uuid4()))['status']==404,SCOPE+' actual Customers lookup of a missing customer is 404')
            image=build('workflow',ROOT,ROOT/'deploy/Dockerfile')
            launch('workflow',image,['--network-alias','workflow','-v',str(folder/'public.pem')+':/run/public.pem:ro','-v',str(folder/'password')+':/run/password:ro','-e','WORKFLOW_DATABASE_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','WORKFLOW_APP_PASSWORD_FILE=/run/password','-e','JWT_PUBLIC_KEY_FILE=/run/public.pem','-e','SERVICE_TOKEN='+service,'-e','CUSTOMERS_BASE_URL=http://customers:8080','-e','PRODUCTS_BASE_URL=http://products:8080','-e','SALES_BASE_URL=http://sales:8080'])
            wait(lambda:probe('http://workflow:8080/health')['status']==200,120)
            body=dict(customerId=customer,lines=[dict(productId=product,quantity=2)])
            # Protected tokens: no credential / a SERVICE credential cannot create a human saga.
            check(probe('http://workflow:8080/api/v1/sagas/register-sale','POST',body,'real-denied',service)['status'] in (401,403),SCOPE+' SERVICE token cannot start a human saga')
            saga=probe('http://workflow:8080/api/v1/sagas/register-sale','POST',body,'real-happy-key',human,'real-trace-happy')
            check(saga['status']==201,SCOPE+' Workflow resource created');location=saga['headers']['Location']
            result=wait(lambda:(r if (r:=probe('http://workflow:8080'+location,credential=human))['body'].get('status')=='COMPLETED' else None),90)
            check(sql(f"SELECT stock FROM products_schema.product WHERE product_id='{product}'")=='18',SCOPE+' normal happy path COMPLETED with real stock effect')
            replay=probe('http://workflow:8080/api/v1/sagas/register-sale','POST',body,'real-happy-key',human,'real-trace-other')
            check(replay['status']==200 and replay['body']['sagaId']==result['body']['sagaId'],SCOPE+' stable key replay returns the same saga without a new sale')
            check(sql(f"SELECT stock FROM products_schema.product WHERE product_id='{product}'")=='18',SCOPE+' replay did not reserve again')
            check(sql("SELECT count(*) FROM workflow_schema.saga_instance WHERE step_results::text ~* '(Bearer|eyJ)'")=='0',SCOPE+' no token in stored saga JSON')
            check(sql("SELECT count(*) FROM workflow_schema.saga_instance WHERE step_results->'execution'->>'correlationId'='real-trace-happy'")=='1',SCOPE+' original correlation retained, replay trace ignored')
            # Confirmed rejection by the actual Sales (integer overflow before commit) -> actual Products compensation.
            sql(f"UPDATE products_schema.product SET price_cents=9223372036854775807 WHERE product_id='{product}'")
            rejected=probe('http://workflow:8080/api/v1/sagas/register-sale','POST',body,'real-reject-key',human,'real-trace-reject')
            check(rejected['status']==201,SCOPE+' confirmed-rejection saga created')
            result=wait(lambda:(r if (r:=probe('http://workflow:8080'+rejected['headers']['Location'],credential=human))['body'].get('status')=='COMPENSATED' else None),90)
            check(result['body']['failedStep']=='register-sale' and sql(f"SELECT stock FROM products_schema.product WHERE product_id='{product}'")=='18',SCOPE+' confirmed rejection compensated and stock restored')
            check(sql("SELECT count(*) FROM products_schema.stock_reservation WHERE status='RELEASED'")=='1',SCOPE+' actual Products released exactly one reservation')
            for line in evidence:print('SOURCE '+line,flush=True)
            print('PASSED '+SCOPE+' Customers/Products/Sales happy, confirmed-rejection compensation, protected tokens, stable keys and traces',flush=True)
        finally:
            for name in reversed(containers):
                result=run(['docker','inspect',name],success=False)
                if not result.returncode:
                    if json.loads(result.stdout)[0]['Config']['Labels'].get('synkro.execution')!=owner:raise RuntimeError('Unowned cleanup refused')
                    run(['docker','rm','-fv',name])
            result=run(['docker','network','inspect',network],success=False)
            if not result.returncode:
                if json.loads(result.stdout)[0]['Labels'].get('synkro.execution')!=owner:raise RuntimeError('Unowned network cleanup refused')
                run(['docker','network','rm',network])
            for image in images:run(['docker','image','rm',image],success=False)
            print('PASSED '+SCOPE+' owned disposable cleanup',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--pin',action='append',default=[],help='repo=sha for each of the six required snapshots')
    parser.add_argument('--customers-env',action='append',default=[])
    parser.add_argument('--customer-json',type=Path,help='JSON body used to seed a customer through the actual Customers API')
    args=parser.parse_args()
    try:
        payload=json.loads(args.customer_json.read_text()) if args.customer_json else dict(name='Real Customer',identityDocument='RS-'+uuid.uuid4().hex[:10],email='workflow-acceptance@example.invalid',phone='3001234567',address='Owned disposable verification fixture')
        execute(args.workspace.resolve(),parse_pairs(args.pin,'--pin'),parse_pairs(args.customers_env,'--customers-env'),payload)
    except Exception as error:
        print('FAILED '+str(error));raise SystemExit(1)
