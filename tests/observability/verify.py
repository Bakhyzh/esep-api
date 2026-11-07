#!/usr/bin/env python3
"""Read-only proof of actual local telemetry after real Keycloak/platform E2E.
No credentials or financial records are printed. Does not synthesize measurements.
"""
import base64
import argparse
import http.client
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import time
import urllib.parse
import urllib.request
import urllib.error

ROOT=Path(__file__).resolve().parents[2]
def get(base,path,params=None,headers=None):
    url=base+path+('?' + urllib.parse.urlencode(params) if params else '')
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=20) as r:
        data=r.read().decode()
    # Jaeger streaming RPC can return multiple newline-delimited JSON envelopes.
    try:return json.loads(data)
    except json.JSONDecodeError:return [json.loads(line) for line in data.splitlines() if line.strip()]
def query(expr):return get('http://localhost:19090','/api/v1/query',{'query':expr})['data']['result']
def attrs(items):return {v['key']:v.get('value',{}).get('stringValue',v.get('value',{}).get('intValue')) for v in items}
def trace_resources(data):
    if isinstance(data,list):return [r for d in data for r in trace_resources(d)]
    return data.get('result',data).get('resourceSpans',[])
def proof(compact=False):
    targets=get('http://localhost:19090','/api/v1/targets')['data']['activeTargets']
    expected=9 if compact else 10
    assert len(targets)==expected and all(t['health']=='up' for t in targets),'Expected '+str(expected)+' actual healthy targets'
    required=['http_server_requests_seconds_count','http_server_requests_seconds_bucket','process_cpu_usage','jvm_memory_used_bytes',
              'hikaricp_connections_active','pg_stat_database_numbackends','pg_locks_count','pg_stat_user_tables_n_dead_tup',
              'pg_wal_size_bytes','kafka_consumergroup_lag','esep_outbox_backlog','esep_outbox_metrics_up','redis_up','esep_analytics_cache_requests_total']
    counts={name:len(query(name)) for name in required}
    assert all(counts.values()),'Required real metric missing: '+str([k for k,v in counts.items() if not v])
    assert all(float(v['value'][1])==1 for v in query('esep_outbox_metrics_up')),'Outbox polling unavailable'
    for item in query('{job="esep",__name__=~"http_.*"}'):
        assert not set(item['metric']).intersection({'user','username','user_id','account_id','subject','email','authorization','url','path','principal'})
        assert '?' not in item['metric'].get('uri','')
    env=dict(line.split('=',1) for line in (ROOT/'.env.platform').read_text().splitlines() if '=' in line)
    auth='Basic '+base64.b64encode(('platform-operator:'+env['GRAFANA_ADMIN_PASSWORD']).encode()).decode()
    headers={'Authorization':auth}
    assert get('http://localhost:13000','/api/health')['database']=='ok'
    dashboard=get('http://localhost:13000','/api/dashboards/uid/esep-platform',headers=headers)
    assert len(dashboard['dashboard']['panels'])>=19
    for uid in ['prometheus','loki']:
        assert get('http://localhost:13000','/api/datasources/uid/'+uid+'/health',headers=headers)['status']=='OK'
    datasource=get('http://localhost:13000','/api/datasources/uid/loki',headers=headers)
    derived=datasource['jsonData']['derivedFields'][0]
    assert derived['matcherType']=='label' and derived['url']=='http://localhost:16686/trace/${__value.raw}'
    logs=get('http://localhost:13100','/loki/api/v1/query_range',{'query':'{service_name=~".*service|api-gateway"} |= "request completed"','limit':'1000','since':'15m'})
    streams=logs['data']['result'];assert streams,'No real OTLP logs reached Loki'
    assert any('HTTP request completed' in text or 'Gateway request completed' in text for s in streams for _,text,*_ in s['values'])
    # Loki exposes structured metadata as virtual query-result labels by default.
    log_trace_ids=[stream['stream']['trace_id'] for stream in streams if stream['stream'].get('trace_id')]
    log_trace_ids += [v[2].get('trace_id') for stream in streams for v in stream['values'] if len(v)>2 and v[2].get('trace_id')]
    assert log_trace_ids,'No actual log/trace correlation metadata'
    def resolves(identifier):
        try:return bool(trace_resources(get('http://localhost:16686','/api/v3/traces/'+identifier)))
        except urllib.error.HTTPError as error:
            if error.code==404:return False
            raise
    assert any(resolves(identifier) for identifier in log_trace_ids[:20]),'Log trace link did not resolve'
    services=get('http://localhost:16686','/api/v3/services')['services']
    expected={'user-service','ledger-service','analytics-service','notification-service','api-gateway'}
    assert expected.issubset(services),'Missing service trace export'
    now=datetime.now(timezone.utc)
    traces=get('http://localhost:16686','/api/v3/traces',{'query.service_name':'ledger-service','query.operation_name':'outbox.publish','query.raw_traces':'true',
      'query.start_time_min':(now-timedelta(minutes=15)).isoformat(),'query.start_time_max':now.isoformat(),'query.search_depth':'100'})
    resources=trace_resources(traces);by_trace={}
    for r in resources:
        service=attrs(r.get('resource',{}).get('attributes',[])).get('service.name')
        for scope in r.get('scopeSpans',[]):
            for span in scope.get('spans',[]):
                group=by_trace.setdefault(span['traceId'],{'services':set(),'names':set(),'spans':[]})
                group['services'].add(service);group['names'].add(span['name']);group['spans'].append(span)
                for attribute in span.get('attributes',[]):
                    assert attribute['key'] in {'http.route','http.request.method','http.response.status_code','http.method','http.status_code','error.type','messaging.system','messaging.destination.name','messaging.operation.name','messaging.operation.type','messaging.kafka.consumer.group','spring.kafka.listener.id'}
    linked=[v for v in by_trace.values() if {'api-gateway','ledger-service','analytics-service','notification-service'}.issubset(v['services']) and 'outbox.publish' in v['names']]
    assert linked,'No continuous Gateway → Ledger → durable outbox → Kafka → Analytics/Notification trace found'
    all_logs=get('http://localhost:13100','/loki/api/v1/query_range',{'query':'{service_name=~".*service|api-gateway"}','limit':'1000','since':'15m'})
    serialized=json.dumps({'logs':logs,'other_logs':all_logs,'traces':traces})
    assert 'esep-private-telemetry-sentinel' not in serialized
    assert not re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.',serialized),'Possible JWT in exported telemetry'
    assert 'Bearer ' not in serialized
    for key,value in env.items():
        if 'PASSWORD' in key:assert value not in serialized,'Credential in telemetry'
    print(json.dumps({'result':'PASS','healthy_scrape_targets':len(targets),'metric_series':counts,'grafana_panels':len(dashboard['dashboard']['panels']),
      'loki_streams':len(streams),'continuous_money_traces':len(linked),'linked_services':sorted(linked[0]['services']),
      'linked_spans':len(linked[0]['spans']),'privacy_checks':'PASS'},indent=2))
if __name__=='__main__':
    # Retry only eventual telemetry collection; every final assertion remains mandatory.
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compact',action='store_true',help='Explicit six-app/three-infrastructure M1 mode; two Gateways, one Ledger')
    options=parser.parse_args()
    deadline=time.monotonic()+90
    while True:
        try:proof(options.compact);break
        except (AssertionError,urllib.error.URLError,http.client.HTTPException,TimeoutError) as error:
            if time.monotonic()>deadline:raise
            time.sleep(3)
