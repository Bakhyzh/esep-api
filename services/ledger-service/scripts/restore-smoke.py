#!/usr/bin/env python3
"""Restore synthetic local ledger_db into a NEW database, reconcile, preserve both.

Never accepts source/destination arguments, never drops/overwrites a database.
"""
import hashlib
from pathlib import Path
import runpy
import secrets
import subprocess

SERVICE=Path(__file__).resolve().parents[1]
DC=['docker','compose','-f',str(SERVICE/'compose.yaml'),'--env-file',str(SERVICE/'.env')]


def psql(db,statement):
    return subprocess.check_output(DC+['exec','-T','postgres','psql','-X','-qAt','-U','ledger_bootstrap',
        '-d',db,'-v','ON_ERROR_STOP=1'],input=statement,text=True).strip()


def main():
    target='ledger_restore_check_'+secrets.token_hex(4)
    backup=subprocess.check_output(DC+['exec','-T','postgres','pg_dump','-U','ledger_bootstrap','-d','ledger_db','-Fc','--no-owner','--no-acl'])
    print('Synthetic backup SHA256: '+hashlib.sha256(backup).hexdigest())
    subprocess.run(DC+['exec','-T','postgres','createdb','-U','ledger_bootstrap','-O','ledger_migrator',target],check=True)
    psql('postgres','REVOKE CONNECT ON DATABASE '+target+' FROM PUBLIC;')
    subprocess.run(DC+['exec','-T','postgres','pg_restore','-U','ledger_bootstrap','--role','ledger_migrator',
        '--no-owner','--no-acl','--exit-on-error','-d',target],input=backup,check=True)
    for table in ['ledger_principals','ledger_identity_bindings','accounts','transactions','ledger_entries','outbox_events','flyway_schema_history']:
        order='installed_rank' if table=='flyway_schema_history' else 'id'
        snapshot='SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY '+order+")::text,'[]')) FROM "+table+' t;'
        assert psql('ledger_db',snapshot)==psql(target,snapshot),'Restored rows differ: '+table
    report=psql(target,(SERVICE/'scripts/reconcile.sql').read_text())
    assert all(line.endswith('|0') for line in report.splitlines()),'Financial reconciliation failed'
    for table in ['accounts','transactions','ledger_entries','outbox_events','ledger_identity_bindings']:
        result=psql(target,"SELECT last_value+CASE WHEN is_called THEN 1 ELSE 0 END > (SELECT coalesce(max(id),0) FROM "+table+") FROM "+table+'_id_seq;')
        assert result=='t','Invalid next sequence: '+table
    print('PASS: new synthetic restore '+target+' matches all rows/Flyway history; financial invariants zero; next sequences > max IDs')
    print('Both databases retained. No existing monolith database was accessed.')


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,AssertionError) else type(error).__name__))
        raise SystemExit(1)
