#!/usr/bin/env python3
"""Add/verify one exact local public PKCE client; preserve existing clients and identities."""
import argparse
import json
from pathlib import Path
import runpy
import subprocess

ROOT=Path(__file__).resolve().parents[2]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--identity-dir',required=True,type=Path);args=p.parse_args()
    helper=runpy.run_path(str(ROOT/'infrastructure/keycloak/validate.py'))
    dc=helper['DC'];dc[dc.index('--env-file')+1]=str(args.identity_dir.resolve()/'.env')
    model=json.loads(subprocess.check_output(dc+['config','--format','json'],text=True))
    token=helper['admin_login'](model['services']['keycloak']['environment'])
    _,api,roles=helper['verify_configuration'](token)
    get,request,check=helper['get_json'],helper['request'],helper['check']
    base=helper['BASE']+'/admin/realms/esep/clients'
    clients={c['clientId']:c for c in get(base,token)};source=clients['esep-web']
    desired=dict(clientId='esep-platform-web',name='ESEP local edge',enabled=True,protocol='openid-connect',
        publicClient=True,bearerOnly=False,standardFlowEnabled=True,implicitFlowEnabled=False,
        directAccessGrantsEnabled=False,serviceAccountsEnabled=False,fullScopeAllowed=False,
        redirectUris=['http://localhost:18081/oidc/callback'],webOrigins=['http://localhost:18081'],
        attributes={'pkce.code.challenge.method':'S256','post.logout.redirect.uris':'http://localhost:18081/'},
        defaultClientScopes=[s['name'] for s in get(base+'/'+source['id']+'/default-client-scopes',token)],
        protocolMappers=[{k:v for k,v in m.items() if k!='id'} for m in source['protocolMappers']])
    client=clients.get(desired['clientId'])
    if client is None:
        check(request('POST',base,desired,token=token)[0]==201,'Local edge client creation failed')
        client={c['clientId']:c for c in get(base,token)}[desired['clientId']]
    for field in ('publicClient','bearerOnly','standardFlowEnabled','implicitFlowEnabled','directAccessGrantsEnabled',
                  'serviceAccountsEnabled','fullScopeAllowed','redirectUris','webOrigins'):
        check(client.get(field)==desired[field],'Existing edge client differs; refusing implicit overwrite')
    for k,v in desired['attributes'].items():check(client.get('attributes',{}).get(k)==v,'Edge PKCE/logout configuration differs')
    # Scope mappings permit only these existing roles in tokens; they do not grant a user any role.
    mapping=base+'/'+client['id']+'/scope-mappings/clients/'+api['id']
    current=get(mapping,token)
    check(set(x['name'] for x in current).issubset({'USER','ADMIN'}),'Unexpected edge role scope')
    missing=[roles[name] for name in ('USER','ADMIN') if name not in {x['name'] for x in current}]
    if missing:check(request('POST',mapping,missing,token=token)[0]==204,'Edge scope mapping failed')
    mappers={m['name']:m for m in client['protocolMappers']}
    check(mappers['esep-api-audience']['config']['included.client.audience']=='esep-api','Edge audience differs')
    check(any(s['name']=='basic' for s in get(base+'/'+client['id']+'/default-client-scopes',token)),'Basic subject scope missing')
    helper['passed']('Exact local18081 public Code+PKCE edge client verified; original5173 client and user roles preserved')
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        raise SystemExit(1)
