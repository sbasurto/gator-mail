# Integration check for an explicitly started disposable Keycloak 26.7.0.
# Uses synthetic credentials only; deletes only the unique realm it creates.
# Run: python3 verify_legacy_hash_isolated.py /private/isolated-admin.json
import base64,hashlib,json,uuid,urllib.request,urllib.parse,urllib.error,time,sys
from pathlib import Path
base='http://127.0.0.1:8181'
if len(sys.argv) != 2: raise SystemExit('Usage: verify_legacy_hash_isolated.py /private/isolated-admin.json')
admin=json.loads(Path(sys.argv[1]).read_text())
def request(method,path,payload=None,token=None,form=False):
 data=(urllib.parse.urlencode(payload).encode() if form else json.dumps(payload).encode()) if payload is not None else None
 headers={'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'}
 if token: headers['Authorization']='Bearer '+token
 with urllib.request.urlopen(urllib.request.Request(base+path,data,headers,method=method),timeout=30) as r:
  body=r.read()
  return json.loads(body) if body else None
admin_token=request('POST','/realms/master/protocol/openid-connect/token',dict(grant_type='password',client_id='admin-cli',**admin),form=True)['access_token']
realm='gator-migration-test-'+uuid.uuid4().hex[:8]
root='/admin/realms/'+realm
request('POST','/admin/realms',{'realm':realm,'enabled':True,'sslRequired':'none','passwordPolicy':'hashAlgorithm(argon2)'},admin_token)
try:
 request('POST',root+'/clients',{'clientId':'gator-test','publicClient':True,'directAccessGrantsEnabled':True,'standardFlowEnabled':False,'fullScopeAllowed':True},admin_token)
 client=request('GET',root+'/clients?clientId=gator-test',token=admin_token)[0]
 cp=root+'/clients/'+client['id']
 request('POST',cp+'/roles',{'name':'access'},admin_token)
 role=request('GET',cp+'/roles/access',token=admin_token)
 request('POST',cp+'/protocol-mappers/models',{'name':'installation-role','protocol':'openid-connect','protocolMapper':'oidc-usermodel-client-role-mapper','config':{'usermodel.clientRoleMapping.clientId':'gator-test','claim.name':'resource_access.gator-test.roles','jsonType.label':'String','multivalued':'true','access.token.claim':'true','id.token.claim':'true','userinfo.token.claim':'true'}},admin_token)
 password='SyntheticMigrationFixture21!'
 salt=b'synthetic-test-salt'
 value=hashlib.sha512(salt+password.encode()).digest()
 for _ in range(2): value=hashlib.sha512(value).digest()
 credential={'type':'password','credentialData':json.dumps({'hashIterations':3,'algorithm':'gator-legacy-sha512'}),'secretData':json.dumps({'value':value.hex(),'salt':base64.b64encode(salt).decode()})}
 request('POST',root+'/users',{'username':'synthetic-person','email':'fixture@example.invalid','emailVerified':True,'firstName':'Synthetic','lastName':'Fixture','enabled':True,'credentials':[credential]},admin_token)
 user=request('GET',root+'/users?username=synthetic-person&exact=true',token=admin_token)[0]
 def algorithm():
  c=request('GET',root+'/users/'+user['id']+'/credentials',token=admin_token)
  return json.loads(next(x for x in c if x['type']=='password')['credentialData'])['algorithm']
 assert algorithm()=='gator-legacy-sha512','imported algorithm mismatch'
 def login(pw):
  return request('POST','/realms/'+realm+'/protocol/openid-connect/token',{'grant_type':'password','client_id':'gator-test','username':'synthetic-person','password':pw,'scope':'openid profile'},form=True)
 try: login('wrong-password')
 except urllib.error.HTTPError as e:
  error=json.loads(e.read())
  assert e.code in (400,401) and error.get('error')=='invalid_grant', (e.code,error)
 else: raise AssertionError('incorrect password accepted')
 assert algorithm()=='gator-legacy-sha512','failed login rewrote credential'
 role_path=root+'/users/'+user['id']+'/role-mappings/clients/'+client['id']
 request('POST',role_path,[role],admin_token)
 tokens=login(password)
 assert tokens.get('access_token') and tokens.get('refresh_token')
 def userinfo():
  return request('GET','/realms/'+realm+'/protocol/openid-connect/userinfo',token=tokens['access_token'])
 assert 'access' in userinfo().get('resource_access',{}).get('gator-test',{}).get('roles',[])
 request('DELETE',role_path,[role],admin_token)
 assert 'access' not in userinfo().get('resource_access',{}).get('gator-test',{}).get('roles',[]), 'userinfo retained revoked role with old access token'
 request('POST',role_path,[role],admin_token)
 for _ in range(20):
  if algorithm()=='argon2': break
  time.sleep(.1)
 assert algorithm()=='argon2','credential did not upgrade'
 assert login(password).get('access_token'),'same password rejected after native rehash'
 refresh=request('POST','/realms/'+realm+'/protocol/openid-connect/token',{'grant_type':'refresh_token','client_id':'gator-test','refresh_token':tokens['refresh_token']},form=True)
 assert refresh.get('access_token')
 request('POST',root+'/users/'+user['id']+'/logout',token=admin_token)
 try: userinfo()
 except urllib.error.HTTPError as e: assert e.code==401
 else: raise AssertionError('userinfo accepted logged-out session')
 try:
  request('POST','/realms/'+realm+'/protocol/openid-connect/token',{'grant_type':'refresh_token','client_id':'gator-test','refresh_token':refresh['refresh_token']},form=True)
 except urllib.error.HTTPError as e: assert e.code==400
 else: raise AssertionError('revoked refresh accepted')
 report={'keycloak':'26.7.0','scope':'isolated localhost; synthetic user only','import':'PASS','wrong_password':'PASS','rehash_argon2':'PASS','same_password_after_rehash':'PASS','refresh':'PASS','refresh_after_admin_logout':'denied','userinfo_revoked_role_with_old_token':'denied','userinfo_after_logout':'denied','production_migration':'NOT RUN'}
 print(json.dumps(report))
finally:
 request('DELETE',root,token=admin_token)
