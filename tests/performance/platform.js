// Real local APIs and real Keycloak tokens. No credentials, bodies or dynamic URLs enter output.
import http from 'k6/http';
import exec from 'k6/execution';
import {check, sleep, fail} from 'k6';
import {Counter, Rate} from 'k6/metrics';

const fixture=JSON.parse(__ENV.ESEP_PERF_ACTORS);
const testCase=__ENV.ESEP_PERF_CASE;
const tokenEndpoints={'http://localhost:18081':'http://localhost:8180/realms/esep/protocol/openid-connect/token','http://edge:8080':'http://keycloak:8080/realms/esep/protocol/openid-connect/token'};
if(!tokenEndpoints[fixture.base] || fixture.actors.length<8)throw new Error('Isolated fixture required');
const tokenUrl=fixture.tokenUrl || tokenEndpoints[fixture.base];
if(tokenUrl!==tokenEndpoints[fixture.base])throw new Error('Unverified token endpoint');
const workloads={
  baseline:{vus:1,duration:'20s',pace:1},
  normal:{vus:4,duration:'30s',pace:0.25},
  stress:{startVUs:1,stages:[{duration:'10s',target:4},{duration:'10s',target:8},{duration:'10s',target:8},{duration:'5s',target:0}],pace:0},
  spike:{startVUs:1,stages:[{duration:'5s',target:1},{duration:'1s',target:8},{duration:'15s',target:8},{duration:'1s',target:1},{duration:'5s',target:1}],pace:0.1},
  soak:{vus:2,duration:'300s',pace:0.5},
  transfers:{vus:4,duration:'30s',pace:1},
  duplicates:{vus:4,duration:'30s',pace:3},
  analytics:{vus:4,duration:'30s',pace:0.25},
  balancing:{vus:4,duration:'20s',pace:0.25}
};
if(!workloads[testCase])throw new Error('Unknown controlled workload');
const {pace,...load}=workloads[testCase];
const overload=['stress','spike'].includes(testCase);
const scenario=load.stages?{executor:'ramping-vus',...load,gracefulRampDown:'5s',gracefulStop:'15s'}:{executor:'constant-vus',...load,gracefulStop:'15s'};
export const options={scenarios:{[testCase]:scenario},
  systemTags:['status','method','name','scenario','expected_response','check','error_code','proto'],
  summaryTrendStats:['avg','min','med','max','p(95)','p(99)'],
  thresholds:{checks:['rate==1'],unexpected_responses:['rate==0'],
    'gateway_responses{instance:gateway-1}':['count>0'],'gateway_responses{instance:gateway-2}':['count>0'],
    ...Object.fromEntries([0,200,201,400,401,403,404,409,422,429,500,502,503,504].map(code=>['api_http_status{code:'+code+'}',['count>=0']]))},
  discardResponseBodies:false
};
const apiRequests=new Counter('api_requests');
const statuses=new Counter('api_http_status');
const apiErrors=new Rate('api_http_error'); // Includes actual non-2xx, even expected overload/conflict statuses.
const unexpected=new Rate('unexpected_responses');
const created=new Counter('transactions_created');
const refreshes=new Counter('oidc_refresh_success');
const gateways=new Counter('gateway_responses');
const ledgers=new Counter('ledger_responses');
let session;
function actor(){
  const i=(exec.vu.idInTest-1)%fixture.actors.length;
  if(!session)session={...fixture.actors[i]};
  if(Date.now()/1000>=session.expiresAt-30){
    const response=http.post(tokenUrl,
      {grant_type:'refresh_token',client_id:'esep-web',refresh_token:session.refreshToken},
      {tags:{name:'oidc.refresh'},timeout:'10s'});
    if(response.status!==200)fail('OIDC refresh failed');
    const tokens=response.json();session.token=tokens.access_token;session.refreshToken=tokens.refresh_token;
    session.expiresAt=Date.now()/1000+tokens.expires_in;refreshes.add(1);
  }
  return session;
}
function parameters(a,name,key){return {headers:{...(fixture.base==='http://edge:8080'?{Host:'localhost:18081'}:{}),Authorization:'Bearer '+a.token,'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},tags:{name},timeout:'10s'};}
function record(response,allowed){
  statuses.add(1,{code:String(response.status)});
  apiRequests.add(1);apiErrors.add(response.status<200 || response.status>=300);
  unexpected.add(!allowed.includes(response.status));
  check(response,{'controlled expected HTTP status':r=>allowed.includes(r.status)});
  const g=response.headers['X-Gateway-Instance'],l=response.headers['X-Ledger-Instance'];
  if(['gateway-1','gateway-2'].includes(g))gateways.add(1,{instance:g});
  if(['ledger-1','ledger-2'].includes(l))ledgers.add(1,{instance:l});
  if(response.status===201)created.add(1);
}
export default function(){
  const a=actor();
  if(['transfers','duplicates'].includes(testCase)){
    const key='k6-'+fixture.nonce+'-'+testCase+'-'+exec.vu.idInTest+'-'+exec.scenario.iterationInTest;
    const body=JSON.stringify({fromAccountId:a.accountId,toAccountId:a.recipientId,amount:'0.0001'});
    if(testCase==='duplicates'){
      const batch=Array.from({length:4},()=>({method:'POST',url:fixture.base+'/api/transfers',body,params:parameters(a,'ledger.transfer.duplicate',key)}));
      const responses=http.batch(batch);responses.forEach(r=>record(r,[200,201]));
      const ids=new Set(responses.filter(r=>[200,201].includes(r.status)).map(r=>r.json('id')));
      check(null,{'concurrent original-key responses identify one transaction':()=>ids.size===1,
        'at most one duplicate response created':()=>responses.filter(r=>r.status===201).length<=1});
      const conflict=http.post(fixture.base+'/api/transfers',JSON.stringify({fromAccountId:a.accountId,toAccountId:a.recipientId,amount:'0.0002'}),parameters(a,'ledger.transfer.conflict',key));
      record(conflict,[422]);
      check(conflict,{'changed payload rejected by the existing idempotency contract':r=>r.status===422 && r.json('code')==='IDEMPOTENCY_KEY_REUSED'});
    }else record(http.post(fixture.base+'/api/transfers',body,parameters(a,'ledger.transfer',key)),[201]);
  }else{
    const paths=testCase==='analytics'?['/api/analytics/spending?currency=KZT','/api/analytics/monthly-comparison?currency=KZT']:
      testCase==='balancing'?['/api/accounts']:
      ['/api/accounts','/api/transactions','/api/analytics/spending?currency=KZT','/api/notifications/unread-count'];
    const path=paths[exec.vu.iterationInScenario%paths.length];
    const name=path.split('?')[0];
    record(http.get(fixture.base+path,parameters(a,name)),overload?[200,429]:[200]);
  }
  if(pace)sleep(pace);
}
export function handleSummary(data){
  // Explicit allowlist: options/env/setup data can contain credentials and are NEVER serialized.
  const result={case:testCase,metrics:data.metrics,durationMs:data.state.testRunDurationMs};
  return {[__ENV.ESEP_PERF_SUMMARY]:JSON.stringify(result,null,2),stdout:'Completed isolated '+testCase+' workload; sanitized metrics written.\n'};
}
