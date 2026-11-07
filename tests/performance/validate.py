#!/usr/bin/env python3
"""Offline k6/Compose contract checks; does not claim an executed load test."""
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile

root=Path(__file__).resolve().parents[2]
image='grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8'
with tempfile.TemporaryDirectory(prefix='esep-performance-validation-') as temporary:
    env=dict(os.environ)
    # Inert placeholders, not credentials or valid JWTs. No real private env is read.
    for key in re.findall(r'\$\{([A-Z_]+):\?', (root/'compose.platform.yaml').read_text()):
        env[key]=str(root) if key=='PLATFORM_FRONTEND_CONTEXT' else 'validation-only-placeholder'
    env.update(ESEP_PERF_ACTORS=json.dumps({'base':'http://edge:8080','actors':[{}]*8}),
               ESEP_PERF_CASE='baseline',PERF_RESULTS_DIR=temporary)
    for case in ('baseline','normal','stress','spike','soak','transfers','duplicates','analytics','balancing'):
        env['ESEP_PERF_CASE']=case
        result=subprocess.run(['docker','run','--rm','-e','ESEP_PERF_ACTORS','-e','ESEP_PERF_CASE',
            '-v',str(root/'tests/performance')+':/work:ro',image,
            'inspect','--include-system-env-vars','/work/platform.js'],env=env,check=True,capture_output=True,text=True)
        options=json.loads(result.stdout)
        if set(options['scenarios'])!={case}:raise RuntimeError('Incorrect workload scenario')
    rendered=subprocess.check_output(['docker','compose','-f','compose.platform.yaml','-f','compose.performance.yaml','--profile','performance','config','--format','json'],cwd=root,env=env,text=True)
    if json.loads(rendered)['services']['k6']['tmpfs']!=['/tmp:size=32m,mode=1777']:raise RuntimeError('Invalid isolated tmpfs mount')
    subprocess.run(['docker','compose','-f','compose.platform.yaml','-f','compose.performance.yaml',
        '--profile','performance','config','-q'],cwd=root,env=env,check=True)
print('PASS: nine k6 scenarios compile and optional Compose validates; no load executed')
