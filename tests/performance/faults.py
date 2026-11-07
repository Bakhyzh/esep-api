"""Controlled faults for the labelled synthetic platform only. Never touches host/other projects."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import ipaddress
import json
import http.client
from pathlib import Path
import runpy
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import ops_common as ops


def run(root,actors,credentials,login,api,wait,require,private,invariants,reconcile,output):
    records=[];edge='http://localhost:18081'
    def model(service):return ops.container('esep-platform-local',service)
    def identifier(service):return model(service)['Id']
    def command(*args):return subprocess.run(['docker',*args],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,check=True)
    def healthy(service,seconds=120):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            state=model(service)['State']
            if state.get('Health',{}).get('Status')=='healthy':return
            if not state['Running']:raise RuntimeError('Protected component exited during recovery')
            time.sleep(1)
        raise RuntimeError('Protected component readiness recovery timed out')
    def people():return [(login(credentials[i+1])['access_token'],actors[i]['userId']) for i in (0,1)]
    def balances(alice,bob):
        return [api(edge,'/api/accounts/'+str(a['accountId']),p[0])[1]['balance'] for a,p in zip(actors[:2],(alice,bob))]
    def settle(alice,body,key):
        saved=[]
        def check():
            try:
                status,value=api(edge,'/api/transfers',alice[0],body,key)
                if status in (200,201):saved.append(value['id']);return True
                return False
            except (urllib.error.URLError,TimeoutError,http.client.HTTPException):return False
        wait(check,'Original-key financial recovery failed',60);return saved[0]
    def finish(name,details):
        invariants();reconcile()
        records.append({'fault':name,'result':'PASS','details':details,'financial_invariants':'PASS','actual_kafka_reconciliation':'PASS'})
        (output/'faults.json').write_text(json.dumps(records,indent=2)+'\n')
        print(name+': recovery, financial invariants and Kafka reconciliation PASS',flush=True)
    body={'fromAccountId':actors[0]['accountId'],'toAccountId':actors[1]['accountId'],'amount':'0.0001'}
    nonce=output.name.rsplit('-',1)[-1]
    full=['docker','compose','--env-file',str(root/'.env.platform'),'-f',str(root/'compose.platform.yaml'),'-f',str(root/'compose.monitoring.yaml'),'--profile','monitoring']
    compact=full[:-2]+['-f',str(root/'compose.monitoring-compact.yaml'),'--profile','monitoring']
    second_was_running=model('ledger-2')['State']['Running']
    try:
        subprocess.run(full+['up','-d','--no-build','--wait','--wait-timeout','180','ledger-2'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        healthy('ledger-2',180)
        subprocess.run(full+['up','-d','--no-build','--no-deps','prometheus'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        alice,bob=people()
        replica=runpy.run_path(str(root/'tests/e2e/replica-failures.py'))
        replica_events=[]
        replica['run'](root,edge,alice,bob,{'id':actors[0]['accountId']},{'id':actors[1]['accountId']},api,wait,require,replica_events.append)
        finish('gateway-and-ledger-replica-kills',{'observations':replica_events})

        alice,bob=people();before=balances(alice,bob);redis=identifier('redis');key='fault-'+nonce+'-redis'
        try:
            command('stop','--time','15',redis)
            status,_=api(edge,'/api/analytics/spending?currency=KZT',alice[0]);require(status==200,'Redis outage did not fall back to SQL reads')
            status,_=api(edge,'/api/transfers',alice[0],body,key);require(status==503,'Redis outage did not reject write before forwarding')
        finally:command('start',redis);healthy('redis')
        require(balances(alice,bob)==before,'Rejected Redis-outage request moved money')
        count=ops.sql('ledger_db',"SELECT count(*) FROM transactions WHERE idempotency_key='"+key+"';",'platform_backup',private['PLATFORM_BACKUP_PASSWORD'])
        require(count=='0','Rejected write created a transaction')
        finish('redis-outage',{'analytics_read_status':200,'write_status':503,'committed_rejected_requests':0})

        alice,bob=people();before=balances(alice,bob);broker=identifier('kafka');key='fault-'+nonce+'-kafka'
        prior=api(edge,'/api/notifications/unread-count',alice[0])[1]['count']
        try:
            command('stop','--time','20',broker)
            status,transaction=api(edge,'/api/transfers',alice[0],body,key);require(status==201,'Kafka outage rolled back financial commit')
            pending=int(ops.sql('ledger_db',"SELECT count(*) FROM outbox_events WHERE aggregate_type='transaction' AND aggregate_id="+str(int(transaction['id']))+' AND published_at IS NULL;', 'platform_backup',private['PLATFORM_BACKUP_PASSWORD']))
            require(pending>=2,'Financial and notification outbox records were not retained')
            require(api(edge,'/api/notifications/unread-count',alice[0])[1]['count']==prior,'Notification appeared before broker delivery')
        finally:command('start',broker);healthy('kafka')
        wait(lambda:api(edge,'/api/notifications/unread-count',alice[0])[1]['count']==prior+1,'Kafka notification recovery failed',90)
        require(balances(alice,bob)==[before[0]-Decimal('0.0001'),before[1]+Decimal('0.0001')],'Kafka recovery balance mismatch')
        finish('kafka-outage',{'committed_while_offline':1,'pending_outbox_observed':pending,'new_notifications':1})

        alice,bob=people();before=balances(alice,bob);pg=identifier('postgres')
        keys=['fault-'+nonce+'-pg-'+str(i) for i in range(8)]
        def transfer(i):
            try:return api(edge,'/api/transfers',alice[0],body,keys[i%8])[0]
            except (urllib.error.URLError,TimeoutError,http.client.HTTPException):return 0
        with ThreadPoolExecutor(max_workers=4) as workers:
            futures=[workers.submit(transfer,i) for i in range(40)]
            try:command('stop','--time','15',pg);time.sleep(1)
            finally:command('start',pg);healthy('postgres')
            statuses=[f.result(timeout=60) for f in futures]
        ids=[settle(alice,body,key) for key in keys]
        require(len(set(ids))==8,'Database restart duplicated or lost logical requests')
        require(balances(alice,bob)==[before[0]-Decimal('0.0008'),before[1]+Decimal('0.0008')],'Database restart violated conservation')
        finish('postgres-restart',{'http_statuses':dict(Counter(statuses)),'original_keys_settled':8,'conserved_delta':'0.0008'})

        alice,bob=people();key='fault-'+nonce+'-pool';before=balances(alice,bob)
        limit=int(ops.sql('ledger_db',"SELECT rolconnlimit FROM pg_roles WHERE rolname='ledger_runtime';",'platform_ops',private['PLATFORM_OPS_PASSWORD']))
        require(1<=limit<=32,'Unbounded connection-exhaustion test refused')
        processes=[]
        probe_command=ops.db_command('ledger_db','ledger_runtime',extra=('-X','-Atq','-v','ON_ERROR_STOP=1'))
        try:
            for _ in range(limit+4):
                process=subprocess.Popen(probe_command,
                    env=ops.password_env(private['LEDGER_DB_PASSWORD']),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                process.stdin.write(b'SELECT pg_sleep(4);\n');process.stdin.close();processes.append(process)
            time.sleep(1)
            try:status=api(edge,'/api/transfers',alice[0],body,key)[0]
            except (urllib.error.URLError,TimeoutError,http.client.HTTPException):status=0
            outcomes=[]
            for process in processes:
                process.wait(timeout=30);error=process.stderr.read()
                outcomes.append('role_limit' if b'too many connections for role' in error else 'accepted' if process.returncode==0 else 'other_failure')
            (output/'connection-probe.json').write_text(json.dumps(dict(Counter(outcomes)),indent=2)+'\n')
            require('role_limit' in outcomes,'Did not reach actual restricted-role connection budget')
            require('other_failure' not in outcomes,'Unexpected failure in connection budget probe')
        finally:
            for process in processes:
                if process.poll() is None:process.terminate();process.wait(timeout=10)
        settle(alice,body,key)
        require(balances(alice,bob)==[before[0]-Decimal('0.0001'),before[1]+Decimal('0.0001')],'Connection exhaustion duplicated financial write')
        finish('runtime-connection-exhaustion',{'role_connection_limit':limit,'connection_results':dict(Counter(outcomes)),
            'initial_http_status':status,'original_key_settled_once':True})

        # Only the verified Ledger namespace is modified; no host networking or Docker socket is exposed.
        alice,bob=people();target=identifier('ledger');postgres=model('postgres')
        pg_ip=postgres['NetworkSettings']['Networks']['esep-platform-local_default']['IPAddress'];ipaddress.ip_address(pg_ip)
        image=(root/'tests/performance/netshoot-image.txt').read_text().strip()
        require(image.startswith('ghcr.io/nicolaka/netshoot:v0.16@sha256:'),'Unverified network tooling image')
        prefix=['docker','run','--rm','--network','container:'+target,'--cap-drop','ALL','--cap-add','NET_ADMIN','--read-only','--security-opt','no-new-privileges:true',image]
        route=json.loads(subprocess.check_output(prefix+['ip','-j','route','get',pg_ip]))[0];device=route['dev']
        require(device.startswith('eth') and device[3:].isdigit(),'Unexpected isolated interface')
        qdiscs=json.loads(subprocess.check_output(prefix+['tc','-j','qdisc','show','dev',device]))
        require(all(q['kind']=='noqueue' for q in qdiscs),'Existing traffic control would be overwritten')
        def timings():
            durations=[]
            for _ in range(6):
                request=urllib.request.Request('http://localhost:18082/api/accounts',headers={'Authorization':'Bearer '+alice[0]})
                started=time.monotonic()
                with urllib.request.urlopen(request,timeout=10) as response:require(response.status==200,'Delayed request failed');response.read()
                durations.append((time.monotonic()-started)*1000)
            return durations
        baseline=timings();added=False
        try:
            subprocess.run(prefix+['tc','qdisc','add','dev',device,'root','netem','delay','50ms'],check=True,stdout=subprocess.DEVNULL)
            added=True;delayed=timings()
        finally:
            if added:subprocess.run(prefix+['tc','qdisc','del','dev',device,'root'],check=True,stdout=subprocess.DEVNULL)
        recovered=timings()
        require(statistics.median(delayed)>statistics.median(baseline)+25,'Actual network delay not observed')
        finish('ledger-namespace-network-latency',{'configured_egress_delay_ms':50,'baseline_ms':baseline,'delayed_ms':delayed,'recovered_ms':recovered})
    finally:
        if not second_was_running:
            command('stop','--time','45',identifier('ledger-2'))
            subprocess.run(compact+['up','-d','--no-build','--no-deps','prometheus'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    return records
