# Requires a disposable localhost Keycloak with gator-users configured to read a local test DB.
# Never modifies the SQL user; all grants exist only in the temporary Keycloak realm.
import json,urllib.request,urllib.parse,uuid,sys,importlib.util,tempfile
from pathlib import Path
base='http://127.0.0.1:8181'
if len(sys.argv) not in (2,3):raise SystemExit('Usage: verify_federated_groups_isolated.py /private/isolated-admin.json [identity_access.py]')
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
 request('POST',root+'/clients',{'clientId':'gator-master-local-erm','enabled':True,'publicClient':True,'standardFlowEnabled':True,'redirectUris':['http://127.0.0.1:8181/test'],'fullScopeAllowed':True})
 client=request('GET',root+'/clients?clientId=gator-master-local-erm')[0]
 cp=root+'/clients/'+client['id']
 samples=[cp+'/evaluate-scopes/generate-example-'+kind+'?userId='+urllib.parse.quote(subject,safe='')+'&scope=openid'
          for kind in ['access-token','id-token','userinfo']]
 def has_access(sample):
  claims=request('GET',sample)
  return 'access' in claims.get('resource_access',{}).get('gator-master-local-erm',{}).get('roles',[])
 assert not any(has_access(sample) for sample in samples), 'unexpected access before grants'
 if len(sys.argv)==3:
  spec=importlib.util.spec_from_file_location('identity_access',sys.argv[2])
  access=importlib.util.module_from_spec(spec);spec.loader.exec_module(access)
  plan={'version':1,'issuer':'https://identity.example/realms/gator','server':'localhost',
        'groups':[{'name':'gator-master-local','clients':['gator-master-local-erm'],
                   'members':[{'subject':subject,'username':'admin','confirmed':True}]}],
        'tokenClients':['gator-master-local-erm']}
  access.validate_manifest(plan,'localhost')
  def admin_request(path,value=None,method=None):
   result=request(method or 'GET',root+'/'+path,value)
   return result
  access.reconcile(admin_request,plan)
  assert request('GET',root+'/groups')==[] and request('GET',cp+'/roles')==[], 'dry-run mutated grants'
  with tempfile.TemporaryDirectory() as temporary:
   first=Path(temporary)/'first.json';second=Path(temporary)/'second.json'
   access.reconcile(admin_request,plan,True,first)
   assert json.loads(first.read_text())['verified']
   access.reconcile(admin_request,plan,True,second)
   assert json.loads(second.read_text())['completed']==[], 'repeat must not duplicate grants'
 else:
  request('POST',cp+'/protocol-mappers/models',{'name':'installation-roles','protocol':'openid-connect','protocolMapper':'oidc-usermodel-client-role-mapper','config':{'claim.name':'resource_access.${client_id}.roles','jsonType.label':'String','multivalued':'true','access.token.claim':'true','id.token.claim':'true','userinfo.token.claim':'true'}})
  request('POST',cp+'/roles',{'name':'access'})
  role=request('GET',cp+'/roles/access')
  request('POST',root+'/groups',{'name':'gator-master-local'})
 group=request('GET',root+'/groups?search=gator-master-local&exact=true')[0]
 gp=root+'/groups/'+group['id']
 if len(sys.argv)==2:
  request('POST',gp+'/role-mappings/clients/'+client['id'],[role])
  request('PUT',user_path+'/groups/'+group['id'])
 assert any(g['id']==group['id'] for g in request('GET',user_path+'/groups'))
 assert all(has_access(sample) for sample in samples), 'group access role missing in access/ID token or userinfo'
 request('DELETE',user_path+'/groups/'+group['id'])
 assert request('GET',user_path+'/groups')==[]
 assert not any(has_access(sample) for sample in samples), 'revoked group still grants role'
 assert request('GET',user_path)['id']==subject
 print('PASS: external subject unchanged; group persisted; access/ID token and userinfo role present; group removal revokes role. Local SQL users were only read.')
finally:
 request('DELETE',root)
