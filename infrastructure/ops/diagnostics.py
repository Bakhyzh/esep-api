#!/usr/bin/env python3
"""Read-only PostgreSQL operational facts, without SQL text, bind values, credentials or financial rows."""
import argparse
from ops_common import *
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--database',choices=DATABASES,default='ledger_db');args=parser.parse_args()
    local_daemon();env=environment()
    output=sql(args.database,(ROOT/'infrastructure/ops/diagnostics.sql').read_text(),'platform_ops',env['PLATFORM_OPS_PASSWORD'])
    print(output)
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)
