#!/usr/bin/env python3
"""Send a synthetic privacy canary through real private OTLP, assert stored output.
The canary is not a benchmark or a financial event. No Docker socket is given to telemetry.
"""
import json,secrets,subprocess,time,urllib.request,urllib.parse,urllib.error
SENTINEL='esep-private-telemetry-sentinel'
trace=secrets.token_hex(16);span=secrets.token_hex(8);now=str(time.time_ns())
def attribute(key,value):return {'key':key,'value':{'stringValue':value}}
resource={'attributes':[attribute('service.name','esep-privacy-probe'),attribute('enduser.id',SENTINEL)]}
base={'timeUnixNano':now,'severityText':'INFO','body':{'stringValue':SENTINEL},'attributes':[attribute('authorization','Bearer '+SENTINEL)],'traceId':trace,'spanId':span}
payloads={'logs':{'resourceLogs':[{'resource':resource,'scopeLogs':[{'logRecords':[base]}]}]},
 'traces':{'resourceSpans':[{'resource':resource,'scopeSpans':[{'spans':[{'traceId':trace,'spanId':span,'name':SENTINEL,'kind':2,'startTimeUnixNano':now,'endTimeUnixNano':str(int(now)+1000),'attributes':[attribute('db.query.text',SENTINEL),attribute('http.request.method','GET'),attribute('url.full','https://example.invalid/?token='+SENTINEL)],'status':{'code':2,'message':SENTINEL}}]}]}]}}
# Exact project network and a read-only tooling container, with no financial credentials.
for signal,payload in payloads.items():
 cmd=['docker','run','--rm','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--network','esep-platform-local_default','--tmpfs','/tmp:size=64k,mode=1777','-i','--entrypoint','/bin/sh','prom/prometheus:v3.16.0@sha256:e04987641b57704f408e31f6373ba4138ecf27cb4a165d24aabb6c38fddd0f06','-c',"cat > /tmp/canary.json; exec wget -qO- --header='Content-Type: application/json' --post-file=/tmp/canary.json http://otel-collector:4318/v1/"+signal]
 subprocess.run(cmd,input=json.dumps(payload).encode(),stdout=subprocess.DEVNULL,check=True,timeout=30)
for attempt in range(30):
 try:
  with urllib.request.urlopen('http://localhost:13100/loki/api/v1/query_range?'+urllib.parse.urlencode({'query':'{service_name="esep-privacy-probe"}','since':'5m'}),timeout=10) as r:logs=json.load(r)
  with urllib.request.urlopen('http://localhost:16686/api/v3/traces/'+trace,timeout=10) as r:traces=r.read().decode()
  assert logs['data']['result']
  assert SENTINEL not in json.dumps(logs) and SENTINEL not in traces
  assert 'Application event' in json.dumps(logs) and 'http.request' in traces
  print('Real OTLP → Loki/Jaeger privacy canary: PASS');break
 except (AssertionError,urllib.error.URLError):
  if attempt==29:raise
  time.sleep(2)
