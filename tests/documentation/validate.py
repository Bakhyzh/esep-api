#!/usr/bin/env python3
"""Validate maintained architecture/report links without running financial services."""
from pathlib import Path
import re
root=Path(__file__).resolve().parents[2]
files=[root/'README.md',root/'docs/FINAL_ESEP_IMPLEMENTATION_REPORT.md']
files += [root/'docs/architecture'/name for name in ['CURRENT_STATE.md','TARGET_ARCHITECTURE.md','SERVICE_BOUNDARIES.md','DATABASE_STRATEGY.md','DEPLOYMENT_ARCHITECTURE.md','SECURITY_ARCHITECTURE.md','OBSERVABILITY.md','PERFORMANCE_TESTING.md','PRODUCTION_READINESS.md','FINAL_PROJECT_REPORT.md','PHASE_I_HARDENING_REPORT.md']]
for path in files:
    text=path.read_text()
    for link in re.findall(r'\[[^\]]+\]\(([^)]+)\)',text):
        if link.startswith(('https:','http:','mailto:','#','/')):continue
        target=link.split('#',1)[0]
        if target and not (path.parent/target).exists():raise RuntimeError('Missing local documentation link in '+path.name+': '+target)
print('PASS: maintained architecture/report documents and local links')
