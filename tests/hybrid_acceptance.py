"""Local-only immutable owner snapshots; controlled Customers is explicitly hybrid."""
import argparse, json, os, shutil, tempfile, uuid
from pathlib import Path
from process_acceptance import run, check, wait, token, execution_id, ROOT, PG, PYTHON
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

PINS={'synkro-customers-api':'5dddfc84f885db8932e4e3a567ed83c83612cfe9',
      'synkro-products-api':'b6b379b314716e201833be0d854953a69452559f',
      'synkro-sales-api':'70bdbaba3783bfb974c338b8a460e6e9b7e5c8dd',
      'synkro-products-db':'0beae79920d9af83865bdebaf6b316a2b2a57592',
      'synkro-sales-db':'9cfbde7db4bea7bacad70370c60f91f8eb917c04'}

def execute(workspace):
    for name,sha in PINS.items():
        source=workspace/name
        check(run(['git','-C',str(source),'rev-parse','HEAD']).stdout.decode().strip()==sha and not run(['git','-C',str(source),'status','--porcelain']).stdout.strip(),'immutable participant '+name+' '+sha)
    owner=execution_id(); prefix='synkro-hybrid-'+owner; network=prefix+'-network'; containers=[]; images=[]
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    caller,customer,product,category=(str(uuid.uuid4()) for _ in range(4))
    human=token(key,caller,['SALESPERSON'],['sales:create'])
    service=token(key,'synkro-workflow',['SERVICE'],['customers:read','stock:reserve','stock:release','sales:register'])
    with tempfile.TemporaryDirectory(prefix='synkro-hybrid-owned-') as temporary:
        folder=Path(temporary); public=key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
        (folder/'public.pem').write_bytes(public); (folder/'password').write_text('disposable-workflow-app')
        (folder/'config.json').write_text(json.dumps(dict(human=human,service=service,customer=customer,product=product)))
        shutil.copyfile(ROOT/'tests/controlled_participants.py',folder/'participants.py')
        def launch(suffix,image,args=(),command=()):
            name=prefix+'-'+suffix;containers.append(name)
            run(['docker','run','-d','--name',name,'--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,*args,image,*command],timeout=180)
            return name
        def build(name,source,dockerfile):
            print('RUN build actual '+name,flush=True)
            image=prefix+'-'+name+':candidate';images.append(image)
            run(['docker','build','--label','synkro.execution='+owner,'-t',image,'-f',str(dockerfile),str(source)],timeout=1200);return image
        def sql(query):
            return run(['docker','exec','-i',database,'psql','-X','-U','postgres','-d','synkro','-qAt','-v','ON_ERROR_STOP=1'],data=query.encode()).stdout.decode().strip()
        def mongo(query,authenticated=True):
            args=['--username','admin','--password','disposable-admin','--authenticationDatabase','admin'] if authenticated else []
            try: return run(['docker','exec',mongodb,'mongosh','--quiet',*args,'--eval',query],timeout=30).stdout.decode().strip()
            except Exception: raise RuntimeError('Hybrid Mongo bootstrap command failed') from None
        def probe(url,method='GET',body=None,key=None,credential=service):
            headers={'Content-Type':'application/json','Authorization':'Bearer '+credential,'X-Correlation-Id':'hybrid-correlation'}
            if key:headers['Idempotency-Key']=key
            payload=dict(url=url,method=method,body=body,headers=headers)
            code="import json,urllib.request; print(urllib.request.urlopen(urllib.request.Request('http://localhost:8080/probe',data="+repr(json.dumps(payload).encode())+",headers={'Content-Type':'application/json'}),timeout=15).read().decode())"
            try: return json.loads(run(['docker','exec',fixture,'python','-c',code],timeout=30).stdout)
            except Exception: raise RuntimeError('Hybrid HTTP probe failed for '+url.split(':8080')[-1]) from None
        try:
            run(['docker','network','create','--label','synkro.execution='+owner,'--label','synkro.disposable=true',network])
            database=launch('pg',PG,['--network-alias','synkro-db','-e','POSTGRES_DB=synkro','-e','POSTGRES_PASSWORD=disposable-admin'])
            wait(lambda:run(['docker','exec',database,'pg_isready','-h','127.0.0.1','-U','postgres','-d','synkro'],success=False).returncode==0)
            sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE workflow_app LOGIN PASSWORD 'disposable-workflow-app'; CREATE ROLE products_app LOGIN PASSWORD 'disposable-products-app'")
            for source in [ROOT/'db',workspace/'synkro-products-db']:
                run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,'-v',str(source)+':/flyway/owner:ro','-e','FLYWAY_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','FLYWAY_USER=postgres','-e','FLYWAY_PASSWORD=disposable-admin','flyway/flyway:11.20.3-alpine','-workingDirectory=/flyway/owner','-configFiles=flyway.toml','migrate'],timeout=180)
            sql(f"INSERT INTO products_schema.category(category_id,name) VALUES ('{category}','Hybrid category'); INSERT INTO products_schema.product(product_id,name,price_cents,stock,category_id) VALUES ('{product}','Hybrid product',125,20,'{category}')")
            mongodb=launch('mongo','mongo:7.0',['--tmpfs','/data/db','--tmpfs','/data/configdb','--entrypoint','bash'],['-c','openssl rand -base64 48 > /tmp/keyfile && chmod 400 /tmp/keyfile && chown mongodb /tmp/keyfile && exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /tmp/keyfile'])
            wait(lambda:run(['docker','exec',mongodb,'mongosh','--quiet','--eval','db.adminCommand({ping:1})'],success=False).returncode==0)
            mongo('rs.initiate({_id:"rs0",members:[{_id:0,host:"'+mongodb+':27017"}]})',False)
            wait(lambda:'true' in mongo('db.hello().isWritablePrimary',False))
            mongo('db.getSiblingDB("admin").createUser({user:"admin",pwd:"disposable-admin",roles:["root"]})',False)
            mongo('db.getSiblingDB("admin").createUser({user:"sales_app",pwd:"disposable-sales-app",roles:[]})')
            source=workspace/'synkro-sales-db';runner=build('sales-runner',source/'deploy',source/'deploy/liquibase.Dockerfile')
            run(['docker','run','--rm','--label','synkro.execution='+owner,'--label','synkro.disposable=true','--network',network,'-v',str(source)+':/liquibase/changelog-root:ro','-w','/liquibase/changelog-root','-e','LIQUIBASE_COMMAND_URL=mongodb://admin:disposable-admin@'+mongodb+':27017/sales?replicaSet=rs0&authSource=admin','-e','LIQUIBASE_COMMAND_CHANGELOG_FILE=changelog/changelog-master.yaml',runner,'update'],timeout=300)
            fixture=launch('fixture',PYTHON,['-v',str(folder)+':/fixtures:ro','--network-alias','participants'],['python','/fixtures/participants.py'])
            for name in ['customers','products','sales']:
                source=workspace/('synkro-'+name+'-api');image=build(name,source,source/'deploy/Dockerfile')
                args=['--network-alias',name,'-v',str(folder/'public.pem')+':/run/public.pem:ro']
                if name=='products':args+=['-e','JWT_PUBLIC_KEY='+public.decode(),'-e','DATABASE_URL=postgres://products_app:disposable-products-app@synkro-db:5432/synkro?sslmode=disable']
                if name=='sales':args+=['-e','JWT_PUBLIC_KEY_FILE=/run/public.pem','-e','MONGODB_URI=mongodb://sales_app:disposable-sales-app@'+mongodb+':27017/sales?replicaSet=rs0&authSource=admin&retryWrites=false','-e','MONGODB_TIMEOUT=5s']
                launch(name,image,args)
            wait(lambda:probe('http://customers:8080/health')['status']==200,120)
            check(probe('http://customers:8080/api/v1/customers/'+customer)['status']==501,'actual current Customers operation remains 501; fully real acceptance BLOCKED')
            wait(lambda:probe('http://products:8080/health')['status']==200,60);wait(lambda:probe('http://sales:8080/health')['status']==200,60)
            reservation=probe('http://products:8080/api/v1/stock-reservations','POST',dict(lines=[dict(productId=product,quantity=2)]),'hybrid-reservation')
            check(reservation['status']==201,'actual Products creates reservation');rid=reservation['body']['reservationId']
            check(probe('http://products:8080/api/v1/stock-reservations/'+rid)['status']==200,'actual Products reads reservation')
            released=probe('http://products:8080/api/v1/stock-reservations/'+rid+'/release','POST',key='hybrid-release')
            check(released['status']==200 and released['body']['status']=='RELEASED','actual Products releases reservation')
            payload=dict(customerId=customer,createdBy=caller,details=[dict(productId=product,quantity=2,unitPriceCents=125)])
            sale=probe('http://sales:8080/api/v1/sales','POST',payload,'hybrid-sale');again=probe('http://sales:8080/api/v1/sales','POST',payload,'hybrid-sale')
            check(sale['status']==201 and again['status']==200 and sale['body']['saleId']==again['body']['saleId'],'actual Sales idempotent registration')
            image=build('workflow',ROOT,ROOT/'deploy/Dockerfile')
            launch('workflow',image,['--network-alias','workflow','-v',str(folder/'public.pem')+':/run/public.pem:ro','-v',str(folder/'password')+':/run/password:ro','-e','WORKFLOW_DATABASE_URL=jdbc:postgresql://synkro-db:5432/synkro','-e','WORKFLOW_APP_PASSWORD_FILE=/run/password','-e','JWT_PUBLIC_KEY_FILE=/run/public.pem','-e','SERVICE_TOKEN='+service,'-e','CUSTOMERS_BASE_URL=http://participants:8080','-e','PRODUCTS_BASE_URL=http://products:8080','-e','SALES_BASE_URL=http://sales:8080'])
            wait(lambda:probe('http://workflow:8080/health')['status']==200,120)
            saga=probe('http://workflow:8080/api/v1/sagas/register-sale','POST',dict(customerId=customer,lines=[dict(productId=product,quantity=2)]),'hybrid-workflow',human)
            check(saga['status']==201,'hybrid Workflow resource created')
            location=saga['headers']['Location']
            result=wait(lambda:(r if (r:=probe('http://workflow:8080'+location,credential=human))['body'].get('status')=='COMPLETED' else None),60)
            check(result['body']['saleId']!=sale['body']['saleId'] and sql(f"SELECT stock FROM products_schema.product WHERE product_id='{product}'")=='18','hybrid controlled Customers + actual Products/Sales completes with frozen prices')
            # Actual Sales rejects overflow before committing; actual Products
            # must release the entire reservation using its owning operation.
            sql(f"UPDATE products_schema.product SET price_cents=9223372036854775807 WHERE product_id='{product}'")
            rejected=probe('http://workflow:8080/api/v1/sagas/register-sale','POST',dict(customerId=customer,lines=[dict(productId=product,quantity=2)]),'hybrid-compensation',human)
            check(rejected['status']==201,'hybrid confirmed-failure saga created')
            location=rejected['headers']['Location']
            result=wait(lambda:(r if (r:=probe('http://workflow:8080'+location,credential=human))['body'].get('status')=='COMPENSATED' else None),60)
            check(result['body']['failedStep']=='register-sale' and sql(f"SELECT stock FROM products_schema.product WHERE product_id='{product}'")=='18' and sql("SELECT count(*) FROM products_schema.stock_reservation WHERE status='RELEASED'")=='2','hybrid actual Sales confirmed rejection and actual Products compensation restore stock')
            print('BLOCKED fully real Customers / Products / Sales: actual Customers GET returns 501',flush=True)
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
            print('PASSED hybrid owned disposable cleanup',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--workspace',type=Path,required=True);args=parser.parse_args()
    try:execute(args.workspace.resolve())
    except Exception as error:
        print('FAILED '+str(error));raise SystemExit(1)
