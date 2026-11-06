#!/usr/bin/env python3
"""Dry-run by default; expire only complete authenticated private backups, retaining a minimum count."""
import argparse
from datetime import datetime,timezone,timedelta
from ops_common import *
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--keep',type=int,default=3)
    parser.add_argument('--days',type=int,default=7);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    if args.keep<2 or args.days<1:raise RuntimeError('Retention must preserve at least two backups and one day')
    keys(create=False);artifacts=[]
    for directory in BACKUPS.iterdir():
        if directory.is_symlink():raise RuntimeError('Symlink in backup directory; retention refused')
        if not directory.is_dir() or not directory.name.startswith('esep-backup-'):continue
        if not (directory/'manifest.json.age').exists():continue # Incomplete artifact is never an expiration candidate.
        data=manifest(directory)
        expected={'manifest.json.age'}|{name+'.dump.age' for name in data['databases']}
        if {p.name for p in directory.iterdir()}!=expected:raise RuntimeError('Unexpected artifact contents; deletion refused')
        artifacts.append((datetime.fromisoformat(data['createdAt']),directory))
    artifacts.sort(reverse=True)
    cutoff=datetime.now(timezone.utc)-timedelta(days=args.days)
    expired=[directory for created,directory in artifacts[args.keep:] if created<cutoff]
    if args.apply:
        for directory in expired:
            # No recursive delete: only already authenticated regular files in this owned, validated artifact.
            for path in directory.iterdir():path.unlink()
            directory.rmdir()
    print(json.dumps({'apply':args.apply,'verifiedBefore':len(artifacts),'retained':len(artifacts)-len(expired),
        'eligible':[p.name for p in expired],'minimum':args.keep,'days':args.days}))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)
