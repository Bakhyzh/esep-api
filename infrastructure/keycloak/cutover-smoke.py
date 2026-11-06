#!/usr/bin/env python3
"""Local cutover acceptance using real Keycloak, isolated financial DB and optional browser.

Never uses password grant. Temporary Keycloak users/rotation provider are removed;
local financial fixtures remain as an audit trail. Refuses any other DB container.
"""
import argparse
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
API = 'http://localhost:8081'


def api(path, token=None, data=None, key=None):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if data is not None:
        headers['Content-Type'] = 'application/json'
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(API + path, headers=headers,
                                 data=None if data is None else json.dumps(data).encode())
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read()
        return response.status, json.loads(body) if body else None


def sql(statement, variables=()):
    cmd = ['docker', 'exec', '-i', 'esep-cutover-postgres', 'psql', '-X', '-qAt',
           '-U', 'esep', '-d', 'esep_oidc', '-v', 'ON_ERROR_STOP=1']
    for name, value in variables:
        cmd += ['-v', name + '=' + str(value)]
    return subprocess.check_output(cmd, input=statement, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identity-dir', type=Path, required=True)
    parser.add_argument('--frontend-dir', type=Path)
    args = parser.parse_args()
    h = runpy.run_path(str(ROOT / 'infrastructure/keycloak/validate.py'))
    dc = h['DC']
    dc[dc.index('--env-file') + 1] = str(args.identity_dir.resolve() / '.env')
    env = json.loads(subprocess.check_output(dc + ['config', '--format', 'json'], text=True))['services']['keycloak']['environment']
    admin = h['admin_login'](env)
    realm, client, roles = h['verify_configuration'](admin)
    request, check, passed = h['request'], h['check'], h['passed']
    base = h['BASE'] + '/admin/realms/esep'
    urls = []
    component_url = None
    try:
        fixtures = []
        for assigned in ('USER', 'USER', 'ADMIN'):
            name = 'phase23-' + secrets.token_hex(8)
            password = secrets.token_urlsafe(32)
            status, headers, _ = request('POST', base + '/users', dict(username=name,
                email=name+'@example.invalid', emailVerified=True, enabled=True,
                firstName='Local', lastName='Cutover',
                credentials=[dict(type='password', value=password, temporary=False)]), token=admin)
            check(status == 201, 'Temporary identity creation failed')
            url = headers['Location']
            urls.append(url)
            status, _, _ = request('POST', url+'/role-mappings/clients/'+client['id'], [roles[assigned]], token=admin)
            check(status == 204, 'Temporary role assignment failed')
            tokens = h['login']('esep','esep-web',h['CALLBACK'],name,password)
            fixtures.append(dict(name=name,password=password,url=url,tokens=tokens))
        owner, other, operator = fixtures
        raw = owner['tokens']['access_token']
        sub = owner['url'].rsplit('/',1)[1]
        issuer = h['ISSUER']
        # Simulated independently verified legacy owner: account predates its OIDC link.
        local_id = int(sql("INSERT INTO users(email,password_hash,role) VALUES (:'email','legacy-hash-preserved','USER') RETURNING id;",
                           [('email',owner['name']+'@example.invalid')]))
        account = int(sql("INSERT INTO accounts(user_id,currency) VALUES (:'id'::bigint,'KZT') RETURNING id;", [('id',local_id)]))
        check(api('/api/auth/me',raw)[0] == 403, 'Email collision must require explicit migration')
        link = (ROOT/'infrastructure/keycloak/link-existing-user.sql').read_text()
        params = [('local_user_id',local_id),('issuer',issuer),('subject',sub),('approved_operator','phase23-fixture/verified-owner')]
        sql(link, params+[('apply','false')])
        check(sql('SELECT count(*) FROM user_identities WHERE user_id=:'+"'id'::bigint;", [('id',local_id)]) == '0', 'Dry run wrote a mapping')
        sql(link, params+[('apply','true')])
        sql(link, params+[('apply','true')])
        status, me = api('/api/auth/me',raw)
        check(status == 200 and me['id'] == local_id, 'Original numeric identity not preserved')
        check(api('/api/accounts/'+str(account),raw)[0] == 200, 'Existing account not accessible')
        check(sql("SELECT password_hash FROM users WHERE id=:'id'::bigint;", [('id',local_id)]) == 'legacy-hash-preserved', 'Legacy password changed')
        passed('Approved migration dry-run, apply and repeat preserve original user/account/hash; email-only collision denied')
        second = other['tokens']['access_token']
        check(api('/api/auth/me',second)[0] == 200, 'New identity provisioning failed')
        check(api('/api/accounts/'+str(account),second)[0] == 404, 'Foreign account disclosed')
        status, target = api('/api/accounts',second,dict(currency='KZT'))
        check(status == 201, 'Account creation failed')
        check(api('/api/deposits',raw,dict(accountId=account,amount='100'),secrets.token_hex(12))[0] == 403, 'USER gained ADMIN deposit')
        adm = operator['tokens']['access_token']
        check(api('/api/deposits',adm,dict(accountId=account,amount='100'),secrets.token_hex(12))[0] == 201, 'ADMIN deposit failed')
        transfer = dict(fromAccountId=account,toAccountId=target['id'],amount='10')
        check(api('/api/transfers',second,transfer,secrets.token_hex(12))[0] == 404, 'Unauthorized transfer accepted')
        key = secrets.token_hex(12)
        check(api('/api/transfers',raw,transfer,key)[0] == 201, 'Authorized transfer failed')
        check(api('/api/transfers',raw,transfer,key)[0] == 200, 'Idempotent replay failed')
        check(api('/api/transfers',raw,dict(transfer,amount='1000'),secrets.token_hex(12))[0] == 422, 'Insufficient funds not enforced')
        check(api('/api/accounts/'+str(account),raw)[1]['balance'] == 90, 'Unexpected source balance')
        passed('Real Keycloak USER/ADMIN, provisioning, account ownership, funded transfer, insufficient funds and replay')
        if args.frontend_dir:
            owner['new_password'] = secrets.token_urlsafe(32)
            check(request('PUT',owner['url'],dict(requiredActions=['UPDATE_PASSWORD']),token=admin)[0] == 204,
                  'Activation action setup failed')
            result = subprocess.run(['npm','run','e2e','--','e2e/oidc.spec.ts'], cwd=args.frontend_dir,
                env=dict(os.environ,ESEP_OIDC_USERNAME=owner['name'],ESEP_OIDC_PASSWORD=owner['password'],ESEP_OIDC_USER_ID=str(local_id),ESEP_OIDC_NEW_PASSWORD=owner['new_password']))
            check(result.returncode == 0, 'Browser OIDC E2E failed')
            owner['password'] = owner['new_password']
            passed('Browser frontend -> real Keycloak Code+PKCE S256 -> real Spring API -> preserved local identity -> logout')
        # JWT logout/revocation limitation: sessions end, signed access token remains valid.
        status, _, _ = request('POST',owner['url']+'/logout',token=admin)
        check(status == 204, 'Keycloak logout failed')
        check(api('/api/auth/me',raw)[0] == 200, 'Unexpected immediate access-token revocation')
        status, _, _ = request('POST',issuer+'/protocol/openid-connect/token',dict(grant_type='refresh_token',client_id='esep-web',refresh_token=owner['tokens']['refresh_token']),form=True)
        check(status == 400, 'Refresh survived logout')
        status, _, _ = request('DELETE',other['url']+'/role-mappings/clients/'+client['id'],[roles['USER']],token=admin)
        check(status == 204 and api('/api/auth/me',second)[0] == 200, 'Role-removal old-token behavior changed')
        fresh = h['login']('esep','esep-web',h['CALLBACK'],other['name'],other['password'])['access_token']
        check(api('/api/auth/me',fresh)[0] == 403, 'Removed role remained in fresh token')
        passed('Logout disables refresh; old access JWT and old role claims remain valid until expiry; fresh roleless token denied')
        # Add and remove a temporary signing provider without deleting old realm keys.
        status, headers, _ = request('POST',base+'/components',dict(name='phase23-rotation-'+secrets.token_hex(4),
            parentId=realm['id'],providerId='rsa-generated',providerType='org.keycloak.keys.KeyProvider',
            config=dict(priority=['200'],keySize=['2048'],algorithm=['RS256'],enabled=['true'],active=['true'])),token=admin)
        check(status == 201, 'Temporary rotation provider failed')
        component_url = headers['Location']
        rotated = h['login']('esep','esep-web',h['CALLBACK'],owner['name'],owner['password'])['access_token']
        kid = lambda t: json.loads(h['unb64'](t.split('.')[0]))['kid']
        check(kid(rotated) != kid(raw), 'Signing key did not rotate')
        check(api('/api/auth/me',rotated)[0] == 200 and api('/api/auth/me',raw)[0] == 200, 'JWKS rotation broke validation')
        passed('Live Keycloak signing-key rotation refreshes Nimbus JWKS; retained old and new keys validate')
        subprocess.run(dc+['restart','keycloak'],check=True,stdout=subprocess.DEVNULL)
        for _ in range(60):
            try:
                h['get_json'](issuer+'/.well-known/openid-configuration')
                break
            except Exception:
                time.sleep(1)
        check(api('/api/auth/me',rotated)[0] == 200, 'JWT failed after Keycloak restart')
        post = h['login']('esep','esep-web',h['CALLBACK'],owner['name'],owner['password'])['access_token']
        check(api('/api/auth/me',post)[1]['id'] == local_id, 'Identity lost after restart')
        passed('Keycloak restart preserves realm, signing key, identity and API ownership')
        check(sql("SELECT count(*) FROM users WHERE email='admin@esep.dev';") == '0','Local profile ran dev ADMIN seed')
        passed('Local cutover has no default ADMIN; persistent audit financial fixtures retained')
    finally:
        admin = h['admin_login'](env)
        if component_url:
            check(request('DELETE',component_url,token=admin)[0] in (204,404),'Rotation provider cleanup failed')
        for url in urls:
            check(request('DELETE',url,token=admin)[0] in (204,404),'Temporary identity cleanup failed')
        passed('Temporary Keycloak users and signing provider removed; original realm/data retained')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        raise SystemExit(1)
