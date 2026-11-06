#!/usr/bin/env python3
"""Rehearse compatible-build legacy auth on loopback 8082 against isolated esep_oidc.

Creates one random legacy USER fixture, preserves all financial rows and terminates
the temporary rollback process. The primary Keycloak API remains on 8081.
"""
import json
import os
from pathlib import Path
import runpy
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def call(path, data=None, token=None):
    headers = {'Content-Type':'application/json'}
    if token:
        headers['Authorization'] = 'Bearer '+token
    req = urllib.request.Request('http://localhost:8082'+path, headers=headers,
        data=None if data is None else json.dumps(data).encode())
    try:
        response = urllib.request.urlopen(req,timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        return response.status,json.loads(body) if body else None


def main():
    sql = runpy.run_path(str(Path(__file__).with_name('cutover-smoke.py')))['sql']
    snapshot = """SELECT md5(jsonb_build_array(
        (SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM accounts a),
        (SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM transactions t),
        (SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM ledger_entries e))::text);"""
    before = sql(snapshot)
    env = dict(os.environ,SPRING_PROFILES_ACTIVE='rollback-local',JWT_SECRET=secrets.token_urlsafe(48),
        DB_URL='jdbc:postgresql://localhost:55433/esep_oidc',DB_USERNAME='esep',DB_PASSWORD='esep',
        REDIS_PORT='56379',KAFKA_BOOTSTRAP_SERVERS='localhost:59094',SERVER_PORT='8082')
    jar = ROOT/'target/esep-api-0.0.1-SNAPSHOT.jar'
    with open('/tmp/esep-phase23-rollback-app.log','w') as log:
        proc = subprocess.Popen(['java','-Dserver.address=127.0.0.1','-jar',str(jar)],cwd=ROOT,env=env,stdout=log,stderr=log)
        try:
            for _ in range(60):
                if proc.poll() is not None:
                    raise RuntimeError('Rollback JVM failed to start')
                try:
                    if call('/actuator/health')[0] == 200:
                        break
                except (OSError,TimeoutError):
                    pass
                time.sleep(1)
            email = 'rollback-'+secrets.token_hex(8)+'@example.invalid'
            password = secrets.token_urlsafe(32)
            status,user = call('/api/auth/register',dict(email=email,password=password))
            assert status == 201
            status,result = call('/api/auth/login',dict(email=email,password=password))
            assert status == 200
            status,me = call('/api/auth/me',token=result['accessToken'])
            assert status == 200 and me['id'] == user['id']
            null_email = sql("SELECT email FROM users WHERE password_hash IS NULL ORDER BY id LIMIT 1;")
            assert null_email and call('/api/auth/login',dict(email=null_email,password='dummy-password-for-timing'))[0] == 401
            assert before == sql(snapshot)
            print('PASS: compatible-build legacy register/login/me and NULL-password rejection; financial snapshot unchanged')
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: '+type(error).__name__)
        raise SystemExit(1)
