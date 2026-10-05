# Requires a disposable localhost Keycloak with gator-users configured to read a local test DB.
# Never modifies the SQL user; all grants exist only in the temporary Keycloak realm.
import json,urllib.request,urllib.parse,uuid,sys
from pathlib import Path
base='http://127.0.0.1:8181'
if len(sys.argv)!=2:raise SystemExit('Usage: verify_federated_groups_isolated.py /private/isolated-admin.json')
admin=json.loads(Path(sys.argv[1]).read_text())
form=urllib.parse.urlencode(dict(grant_type='password',client_id='admin-cli',**admin)).encode()
with urllib.request.urlopen(base+'/realms/master/protocol/openid-connect/token',form,timeout=20) as r: token=json.load(r)['access_token']
realm='gator-groups-test-'+uuid.uuid4().hex[:8]
root='/admin/realms/'+realm

def request(method,path,data=None):
 req=urllib.request.Request(base+path,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=None if data is None else json.dumps(data).encode(),method=method)
 with urllib.request.urlopen(req,timeout=30) as r:
  body=r.read();return json.loads(body) if body else None
request('POST','/admin/realms',{'realm':realm,'enabled':True,'sslRequired':'none'})
try:
 realm_id=request('GET',root)['id']
 request('POST',root+'/components',{'name':'local-read-only-users','providerId':'gator-users','providerType':'org.keycloak.storage.UserStorageProvider','parentId':realm_id,'config':{'enabled':['true'],'priority':['0'],'cachePolicy':['NO_CACHE'],'literalUsernames':['true']}})
 components=request('GET',root+'/components?type=org.keycloak.storage.UserStorageProvider')
 assert len(components)==1
 subject='f:'+components[0]['id']+':admin'
 user_path=root+'/users/'+urllib.parse.quote(subject,safe='')
 user=request('GET',user_path)
 assert user['id']==subject and user['username']=='admin' and user['enabled']
 request('POST',root+'/clients',{'clientId':'gator-master-local-erm','enabled':True,'publicClient':True,'standardFlowEnabled':True,'redirectUris':['http://127.0.0.1:8181/test'],'fullScopeAllowed':True,'protocolMappers':[{'name':'installation-roles','protocol':'openid-connect','protocolMapper':'oidc-usermodel-client-role-mapper','config':{'claim.name':'resource_access.${client_id}.roles','jsonType.label':'String','multivalued':'true','access.token.claim':'true','id.token.claim':'true','userinfo.token.claim':'true'}}]})
 client=request('GET',root+'/clients?clientId=gator-master-local-erm')[0]
 cp=root+'/clients/'+client['id']
 request('POST',cp+'/roles',{'name':'access'})
 role=request('GET',cp+'/roles/access')
 request('POST',root+'/groups',{'name':'gator-master-local'})
 group=request('GET',root+'/groups?search=gator-master-local&exact=true')[0]
 gp=root+'/groups/'+group['id']
 request('POST',gp+'/role-mappings/clients/'+client['id'],[role])
 request('PUT',user_path+'/groups/'+group['id'])
 assert any(g['id']==group['id'] for g in request('GET',user_path+'/groups'))
 sample=cp+'/evaluate-scopes/generate-example-access-token?userId='+urllib.parse.quote(subject,safe='')+'&scope=openid'
 claims=request('GET',sample)
 assert 'access' in claims.get('resource_access',{}).get('gator-master-local-erm',{}).get('roles',[]), 'group access role missing in token'
 request('DELETE',user_path+'/groups/'+group['id'])
 assert request('GET',user_path+'/groups')==[]
 claims=request('GET',sample)
 assert 'access' not in claims.get('resource_access',{}).get('gator-master-local-erm',{}).get('roles',[]), 'revoked group still grants role'
 assert request('GET',user_path)['id']==subject
 print('PASS: external subject unchanged; group persisted; effective access token role present; group removal revokes role. Local SQL users were only read.')
finally:
 request('DELETE',root)
