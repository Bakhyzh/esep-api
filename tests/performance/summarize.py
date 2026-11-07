#!/usr/bin/env python3
"""Render committed sanitized measurement artifacts; never fabricates absent samples."""
import argparse
import json
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('directories',nargs='+',type=Path)
args=parser.parse_args()
print('| Run / case | Exit | Requests | RPS | p50 ms | p95 ms | p99 ms | Non-2xx % | PG connections peak | Kafka lag peak |')
print('|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|')
for directory in args.directories:
 for r in json.loads((directory/'results.json').read_text()):
  m=r['metrics'];v=lambda key:m.get(key,{}).get('values',{})
  latency=v('http_req_duration');requests=v('api_requests');samples=json.loads((directory/(r['case']+'.resources.json')).read_text())
  def peak(key):
   values=[float(item['value'][1]) for sample in samples for item in sample.get(key,[]) if item['value'][1] not in ('NaN','+Inf','-Inf')]
   return f'{max(values):g}' if values else 'not collected'
  print(f"| {directory.name} / {r['case']} | {r['exit_code']} | {requests.get('count',0):.0f} | {requests.get('rate',0):.2f} | {latency.get('med',0):.2f} | {latency.get('p(95)',0):.2f} | {latency.get('p(99)',0):.2f} | {v('api_http_error').get('rate',0)*100:.2f} | {peak('postgres_connections')} | {peak('kafka_lag')} |")
print('\nNon-2xx includes intentional idempotency422 and allowed stress429. Exit99 is a failed threshold, never PASS.')
