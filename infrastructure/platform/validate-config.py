#!/usr/bin/env python3
"""Syntax-only Compose validation with ephemeral credentials; starts no containers."""
import os
from pathlib import Path
import re
import secrets
import subprocess
root=Path(__file__).resolve().parents[2]
files=['compose.platform.yaml','compose.monitoring.yaml','compose.monitoring-compact.yaml']
env=os.environ.copy()
for name in re.findall(r'\$\{([A-Z_]+):\?', '\n'.join((root/p).read_text() for p in files)):
    env[name]=str(root) if name=='PLATFORM_FRONTEND_CONTEXT' else secrets.token_urlsafe(36)
subprocess.run(['docker','compose',*[arg for path in files for arg in ['-f',path]],'--profile','monitoring','--profile','dashboards','config','-q'],cwd=root,env=env,check=True)
print('Platform plus optional monitoring configuration: PASS (no containers started)')
