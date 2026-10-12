"""Disposable test participants only; never packaged into the application."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import json
import threading
import time
import uuid

config=json.load(open('/fixtures/config.json'))
lock=threading.Lock()
state={'mode':'normal','reservations':{},'sales':{},'counts':{'reserve':0,'sale':0,'release':0},'paused':False}
now='2026-10-12T12:00:00Z'

def response_body(response):
    data=response.read()
    if not data:return {}
    try:return json.loads(data)
    except (ValueError,UnicodeError):return {}

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def reply(self,code,body):
        data=json.dumps(body).encode();self.send_response(code);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers()
        try:self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError):pass
    def body(self):return json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or b'{}')
    def do_GET(self):
        if self.path=='/health':return self.reply(200,{'status':'ok'})
        if self.path=='/state':
            with lock:return self.reply(200,{'counts':dict(state['counts']),'paused':state['paused'],'mode':state['mode']})
        if self.headers.get('Authorization')!='Bearer '+config['service']:return self.reply(403,{'error':'FORBIDDEN'})
        if self.path.startswith('/api/v1/customers/'):
            if state['mode']=='customer-unavailable':return self.reply(501,{})
            return self.reply(200,{'customerId':config['customer'],'name':'Controlled customer','identityDocument':'controlled','registrationDate':now,'active':True})
        if self.path.startswith('/api/v1/stock-reservations/'):
            identity=self.path.split('/')[-1]
            with lock:
                reservation=next((r for r in state['reservations'].values() if r['reservationId']==identity),None)
                return self.reply(200 if reservation else 404,reservation or {})
        self.reply(404,{})
    def do_POST(self):
        if self.path=='/mode':
            mode=self.body()['mode']
            with lock:state.update(mode=mode,reservations={},sales={},counts={'reserve':0,'sale':0,'release':0},paused=False)
            return self.reply(200,{})
        if self.path=='/probe':
            data=self.body();headers=data.get('headers',{});body=data.get('body');request=Request(data['url'],data=None if body is None else json.dumps(body).encode(),headers=headers,method=data.get('method','GET'))
            try:
                with urlopen(request,timeout=10) as response:return self.reply(200,{'status':response.status,'headers':dict(response.headers),'body':response_body(response)})
            except HTTPError as error:return self.reply(200,{'status':error.code,'headers':dict(error.headers),'body':response_body(error)})
        if self.headers.get('Authorization')!='Bearer '+config['service']:return self.reply(403,{'error':'FORBIDDEN'})
        key=self.headers.get('Idempotency-Key');pause=False;lost=False
        with lock:
            mode=state['mode']
            if self.path=='/api/v1/stock-reservations':
                body=self.body()
                if key not in state['reservations']:
                    state['reservations'][key]={'reservationId':str(uuid.uuid4()),'status':'RESERVED','createdAt':now,'lines':[dict(line,lineId=str(uuid.uuid4()),unitPriceCents=125) for line in body['lines']]}
                    state['counts']['reserve']+=1;pause=mode=='reserve-pause'
                result=state['reservations'][key];status=201
            elif self.path=='/api/v1/sales':
                body=self.body()
                if mode in ('sale-reject','release-pause') or (mode=='sale-ambiguous' and key in state['sales']):return self.reply(422,{'error':'BUSINESS_RULE_VIOLATION','message':'Controlled rejection','traceId':self.headers.get('X-Correlation-Id')})
                if key not in state['sales']:
                    details=[dict(line,detailId=str(uuid.uuid4()),subtotalCents=line['quantity']*line['unitPriceCents'],active=True) for line in body['details']]
                    state['sales'][key]=dict(saleId=str(uuid.uuid4()),customerId=body['customerId'],createdBy=body['createdBy'],date=now,totalCents=sum(line['subtotalCents'] for line in details),active=True,details=details)
                    state['counts']['sale']+=1;pause=mode=='sale-pause';lost=mode in ('sale-lost','sale-ambiguous')
                result=state['sales'][key];status=201
            elif self.path.startswith('/api/v1/stock-reservations/') and self.path.endswith('/release'):
                identity=self.path.split('/')[-2]
                result=next((r for r in state['reservations'].values() if r['reservationId']==identity),None)
                if not result:return self.reply(404,{})
                if result['status']=='RESERVED':
                    result['status']='RELEASED';result['releasedAt']=now;state['counts']['release']+=1;pause=mode=='release-pause'
                status=200
            else:return self.reply(404,{})
            if pause:state['paused']=True
        if pause:time.sleep(10)
        if lost:self.close_connection=True;return
        self.reply(status,result)

ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
