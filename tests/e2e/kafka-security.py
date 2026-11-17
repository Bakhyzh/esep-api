#!/usr/bin/env python3
"""Real SASL/ACL negative probes of the labelled isolated broker. No valid forged identity events."""
import json
import argparse
import os
import re
from pathlib import Path
import secrets
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops

def require(ok,message):
    if not ok:raise RuntimeError(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--project',choices=['esep-platform-local','esep-kafka-upgrade-lab'],default='esep-platform-local');args=parser.parse_args()
    ops.local_daemon();private=ops.environment();broker=ops.container(args.project,'kafka');target=broker['Id']
    if args.project=='esep-kafka-upgrade-lab':require(broker['Config']['Labels'].get('esep.scope')=='synthetic-kafka-upgrade','Unverified upgrade fixture')
    require(broker['State']['Running'],'Isolated broker stopped')
    model=dict(v.split('=',1) for v in broker['Config']['Env'])
    require(model['KAFKA_LOG_DIRS']=='/var/lib/kafka/data','Broker persistence path differs')
    require(model['KAFKA_SUPER_USERS']=='User:broker;User:operator','Unexpected superuser bypass')
    require(model['KAFKA_LISTENER_SECURITY_PROTOCOL_MAP']=='CONTROLLER:SASL_PLAINTEXT,INTERNAL:SASL_PLAINTEXT','Unauthenticated listener available')
    require(model['KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND']=='false','Default allow configured')
    base=['docker','exec','-i','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx96m',target]
    admin=['--bootstrap-server','kafka:9092','--command-config','/tmp/esep-kafka-admin.properties']
    def admin_output(tool,arguments):
        result=subprocess.run(base+['/opt/kafka/bin/'+tool,*admin,*arguments],capture_output=True,timeout=40)
        if result.returncode:
            # Raw Java configuration/diagnostics can contain credentials. Report
            # only exception class names and actual container termination state.
            allowed=('TimeoutException','OutOfMemoryError','UnsupportedVersionException','SaslAuthenticationException',
                     'TopicAuthorizationException','ClusterAuthorizationException','GroupAuthorizationException',
                     'FileNotFoundException','ConfigException','IllegalArgumentException','IOException',
                     'NoSuchFileException','SecurityException','UnknownHostException')
            classes=[name for name in allowed if re.search(rb'\b'+name.encode()+rb'\b',result.stderr)]
            state=json.loads(ops.run(['docker','inspect',target]))[0]['State']
            raise RuntimeError('Kafka '+tool+': exit '+str(result.returncode)+'; classes='+','.join(classes)
                               +'; brokerRunning='+str(state['Running'])+'; brokerOOMKilled='+str(state.get('OOMKilled',False)))
        return result.stdout.decode().splitlines()
    topics=admin_output('kafka-topics.sh',['--list'])
    require({'esep.users.v1','esep.ledger.postings.v1','esep.ledger.transfers.v1','esep.ledger.accounts.v1'}.issubset(topics),'Preserved financial/identity topics missing')
    groups=admin_output('kafka-consumer-groups.sh',['--list','--timeout','15000'])
    checks=[]
    for principal,topic in [('user','esep.ledger.postings.v1'),('ledger','esep.users.v1'),('notification','esep.ledger.transfers.v1'),('analytics','esep.ledger.postings.v1'),('monitor','esep.users.v1')]:
        config='/tmp/esep-acl-probe-'+secrets.token_hex(8)+'.properties'
        env=dict(os.environ,KAFKA_PROBE_PASSWORD=private['KAFKA_'+principal.upper()+'_PASSWORD'])
        script='''umask 077
cat > "$2" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="$1" password="$KAFKA_PROBE_PASSWORD";
request.timeout.ms=2000
delivery.timeout.ms=3000
max.block.ms=3000
retries=0
enable.idempotence=false
EOF
'''
        try:
            subprocess.run(['docker','exec','-i','-e','KAFKA_PROBE_PASSWORD',target,'bash','-c',script,'probe',principal,config],env=env,check=True,capture_output=True)
            result=subprocess.run(base+['/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--producer.config',config,'--topic',topic,'--sync'],input=b'{}\n',capture_output=True,timeout=20)
            require(b'TopicAuthorizationException' in result.stderr,'Unauthorized producer was not demonstrably denied')
            require(not any(v.encode() in result.stdout+result.stderr for k,v in private.items() if 'PASSWORD' in k),'Credential in client diagnostic')
            checks.append({'principal':principal,'forbiddenTopic':topic,'observed':'TopicAuthorizationException'})
        finally:ops.run(['docker','exec',target,'rm','-f',config])
    # AdminClient metadata request requires authentication; API_VERSIONS alone is deliberately allowed before SASL.
    config='/tmp/esep-plaintext-probe-'+secrets.token_hex(8)+'.properties'
    try:
        ops.run(['docker','exec','-i',target,'bash','-c','umask 077; cat > "$1"','probe',config],data=b'security.protocol=PLAINTEXT\nrequest.timeout.ms=1000\ndefault.api.timeout.ms=3000\nsocket.connection.setup.timeout.ms=1000\nretries=0\n')
        result=subprocess.run(base+['/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:9092','--command-config',config,'--list'],capture_output=True,timeout=15)
        require(result.returncode!=0 and b'esep.users.v1' not in result.stdout,'Unauthenticated metadata access accepted')
        checks.append({'principal':'unauthenticated','observed':'metadata request rejected'})
    finally:ops.run(['docker','exec',target,'rm','-f',config])
    print(json.dumps({'result':'PASS','probes':checks,'preservedTopics':len(topics),'existingConsumerGroups':len(groups),'persistentLogPath':True}))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)
