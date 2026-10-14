"""Owns a disposable loopback Keycloak; JDBC transport is synthetic, not business SQL.
Usage: python3 verify_identity_admin_isolated.py /path/to/keycloak-26.7.0.tar.gz
No production URLs/credentials accepted. Artifacts remain private under /tmp.
"""
import hashlib,importlib.util,json,os,secrets,shutil,socket,subprocess,sys,tarfile,tempfile,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path

BASE='http://127.0.0.1:18881'
ISSUER='https://127.0.0.1:18881/realms/gator'
module=Path(__file__).resolve().parents[3]
if len(sys.argv) not in (2,3):raise SystemExit('Usage: verify_identity_admin_isolated.py keycloak-26.7.0.tar.gz [identity_access.py]')
with socket.socket() as s:
    if s.connect_ex(('127.0.0.1',18881))==0:raise SystemExit('Refusing an occupied test port')
root=Path(tempfile.mkdtemp(prefix='gator-identity-admin-isolated-'))
with tarfile.open(sys.argv[1]) as archive:archive.extractall(root,filter='data')
home=root/'keycloak-26.7.0'
shutil.copyfile(module.parent/'dist/gator-keycloak-provider.jar',home/'providers/gator-keycloak-provider.jar')
shutil.copyfile(module/'build/identity-admin-fixture/gator-identity-admin-test-fixture.jar',home/'providers/gator-identity-admin-test-fixture.jar')
fixture=root/'fixture.json'
source={'CaseUser':{'name':'Synthetic Federated','email':'source@example.invalid','enabled':True,'hash':hashlib.sha512(b'preserved-fixture-hash').hexdigest(),'required':'UPDATE_PASSWORD'}}
source['ResetUser']=dict(source['CaseUser'],name='Explicit Reset Fixture',email='reset@example.invalid')
fixture.write_text(json.dumps({'source':source,'operations':{},'projected':{}}))
password=secrets.token_urlsafe(24)
env=dict(os.environ,KC_BOOTSTRAP_ADMIN_USERNAME='isolated-admin',KC_BOOTSTRAP_ADMIN_PASSWORD=password,
    GATOR_IDP_JDBC_URL='jdbc:gator-identity-admin-test:isolated',GATOR_IDP_JDBC_USER='fixture',GATOR_IDP_JDBC_PASSWORD='fixture',
    GATOR_IDENTITY_ADMIN_FIXTURE=str(fixture),JAVA_OPTS_APPEND='-Xms128m -Xmx512m')
process=None
log=open(root/'keycloak.log','ab')
def start():
    global process
    process=subprocess.Popen([str(home/'bin/kc.sh'),'start-dev','--http-host=127.0.0.1','--http-port=18881',
        '--http-management-port=19001','--hostname-strict=false'],env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(300):
        if process.poll() is not None:raise AssertionError('Disposable Keycloak exited; inspect '+str(root/'keycloak.log'))
        try:
            with urllib.request.urlopen(BASE+'/realms/master',timeout=1):return
        except (urllib.error.URLError,TimeoutError):time.sleep(.5)
    raise AssertionError('Disposable Keycloak did not start')
def stop():
    global process
    if process is not None:
        process.terminate()
        try:process.wait(timeout=30)
        except subprocess.TimeoutExpired:process.kill();process.wait()
        process=None

def token(realm,client,user,secret,client_secret=None):
    values=dict(grant_type='password',client_id=client,username=user,password=secret,scope='openid profile email')
    if client_secret:values['client_secret']=client_secret
    data=urllib.parse.urlencode(values).encode()
    with urllib.request.urlopen(BASE+'/realms/'+realm+'/protocol/openid-connect/token',data,timeout=15) as r:return json.load(r)['access_token']
def request(method,path,data=None,bearer=None,expected=None):
    headers={'Content-Type':'application/json'}
    if bearer:headers['Authorization']='Bearer '+bearer
    req=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as r:
            body=r.read();result=json.loads(body) if body else None
            if expected is not None:assert r.status==expected,(r.status,expected)
            return result
    except urllib.error.HTTPError as error:
        if expected is not None and error.code==expected:return None
        detail=error.read().decode();raise AssertionError(str(error.code)+' '+path+' '+detail[:500]) from None

def wait_projection(subject,field,value):
    for _ in range(100):
        state=json.loads(fixture.read_text());projected=state['projected'].get(subject,{})
        if projected.get(field)==value:return state
        time.sleep(.5)
    raise AssertionError('Projection did not converge; inspect '+str(root/'keycloak.log'))

try:
    start();master=token('master','admin-cli','isolated-admin',password)
    request('POST','/admin/realms',{'realm':'gator','enabled':True,'sslRequired':'none','attributes':{'frontendUrl':'https://127.0.0.1:18881'}},master)
    realm=request('GET','/admin/realms/gator',bearer=master)
    request('POST','/admin/realms/gator/components',{'name':'synthetic-source','providerId':'gator-users','providerType':'org.keycloak.storage.UserStorageProvider','parentId':realm['id'],
        'config':{'enabled':['true'],'priority':['0'],'cachePolicy':['NO_CACHE'],'literalUsernames':['true'],'identityAdministrationEnabled':['true']}},master)
    component=request('GET','/admin/realms/gator/components?type=org.keycloak.storage.UserStorageProvider',bearer=master)[0]
    federated='f:'+component['id']+':CaseUser'
    fedpath='/admin/realms/gator/users/'+urllib.parse.quote(federated,safe='')
    request('POST','/admin/realms/gator/users',{'username':'synthetic-admin','firstName':'Synthetic','lastName':'Administrator','enabled':True,'emailVerified':True,'email':'admin@example.invalid','credentials':[{'type':'password','value':password,'temporary':False}]},master)
    admin=request('GET','/admin/realms/gator/users?username=synthetic-admin&exact=true',bearer=master)[0]
    if len(sys.argv)==3:
        spec=importlib.util.spec_from_file_location('isolated_identity_access',sys.argv[2]);helper=importlib.util.module_from_spec(spec);spec.loader.exec_module(helper)
        request('POST','/admin/realms/gator/clients',{'clientId':'gator-mail','enabled':True,'publicClient':False,
            'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'implicitFlowEnabled':False,'serviceAccountsEnabled':False,
            'fullScopeAllowed':True,'attributes':{'pkce.code.challenge.method':'S256'}},master)
        def transport(path,data=None,method='GET'):
            return request(method,'/admin/realms/gator'+('/'+path if path else ''),data,master)
        plan={'version':1,'server':'isolated','issuer':ISSUER,'identityAdministration':{'enabled':False,'componentId':component['id'],
            'expectedSha256':'0'*64,'clients':[{'clientId':'gator-mail','destinationDbId':1,'system':'mail',
            'accessGroupName':'gator-mail-central-users','admins':[{'subject':admin['id'],'username':admin['username'],'confirmed':True}]}]}}
        before=helper.administration_snapshot(transport,plan);plan['identityAdministration']['expectedSha256']=helper.administration_digest(before)
        helper.configure_administration(transport,plan,True,str(root/'configuration-journal.json'))
        after=helper.administration_snapshot(transport,plan)
        assert after['clients']['gator-mail']['people'][admin['id']]['user']==before['clients']['gator-mail']['people'][admin['id']]['user']
        assert transport('groups/'+after['clients']['gator-mail']['group']['id']+'/members')==[]
        plan['identityAdministration']['expectedSha256']=helper.administration_digest(after)
        helper.configure_administration(transport,plan,True,str(root/'configuration-idempotent-journal.json'))
        assert helper.administration_snapshot(transport,plan)==after
        component['config']['identityAdministrationEnabled']=['true']
        request('PUT','/admin/realms/gator/components/'+component['id'],component,master)
    state=json.loads(fixture.read_text());state['adminSubject']=admin['id'];fixture.write_text(json.dumps(state))
    request('POST','/admin/realms/gator/clients',{'clientId':'installation-client','enabled':True,'publicClient':True,'directAccessGrantsEnabled':True,
        'attributes':{'gator.identityAdmin.destinationDbId':'1'},'fullScopeAllowed':True},master)
    client=request('GET','/admin/realms/gator/clients?clientId=installation-client',bearer=master)[0]
    cp='/admin/realms/gator/clients/'+client['id']
    request('POST',cp+'/roles',{'name':'identity-admin'},master)
    role=request('GET',cp+'/roles/identity-admin',bearer=master)
    request('POST',cp+'/roles',{'name':'access'},master)
    access=request('GET',cp+'/roles/access',bearer=master)
    request('POST','/admin/realms/gator/groups',{'name':'safe-application-access'},master)
    safe_group=request('GET','/admin/realms/gator/groups?search=safe-application-access',bearer=master)[0]
    request('POST','/admin/realms/gator/groups/'+safe_group['id']+'/role-mappings/clients/'+client['id'],[access],master)
    request('POST','/admin/realms/gator/groups',{'name':'unsafe-administrative-access'},master)
    unsafe_group=request('GET','/admin/realms/gator/groups?search=unsafe-administrative-access',bearer=master)[0]
    request('POST','/admin/realms/gator/groups/'+unsafe_group['id']+'/role-mappings/clients/'+client['id'],[access,role],master)
    client=request('GET',cp,bearer=master)
    client['attributes']['gator.identityAdmin.accessGroupId']=safe_group['id']
    request('PUT',cp,client,master)
    request('POST','/admin/realms/gator/users/'+admin['id']+'/role-mappings/clients/'+client['id'],[role],master)
    request('POST','/admin/realms/gator/users',{'username':'synthetic-native','firstName':'Before','email':'native@example.invalid','enabled':True,
        'requiredActions':['UPDATE_PASSWORD'],'credentials':[{'type':'password','value':secrets.token_urlsafe(20),'temporary':True}]},master)
    native=request('GET','/admin/realms/gator/users?username=synthetic-native&exact=true',bearer=master)[0]
    np='/admin/realms/gator/users/'+native['id']
    state=json.loads(fixture.read_text());state['componentId']=component['id'];state['knownSubjects']=[admin['id'],native['id']];fixture.write_text(json.dumps(state))
    before_credentials=request('GET',np+'/credentials',bearer=master)
    before_required=request('GET',np,bearer=master)['requiredActions']
    endpoint='/realms/gator/gator-identity-admin'
    request('POST',endpoint,{'action':'get','targetSubject':native['id']},expected=404)
    realm=request('GET','/admin/realms/gator',bearer=master)
    realm['attributes'].update({'gator.identityAdmin.enabled':'true','gator.identityAdmin.issuer':ISSUER})
    realm.update(adminEventsEnabled=True,adminEventsDetailsEnabled=False,eventsListeners=['jboss-logging','gator-identity-admin'])
    request('PUT','/admin/realms/gator',realm,master)
    admin_token=token('gator','installation-client','synthetic-admin',password)
    request('POST',endpoint,{'action':'get','targetSubject':native['id']},expected=401)
    request('POST',endpoint,{'action':'status','targetSubject':native['id'],'operationId':'unknown-operation'},admin_token,404)
    queried=request('POST',endpoint,{'action':'get','targetSubject':native['id']},admin_token)
    assert queried['user']['targetSubject']==native['id'] and queried['status']=='complete'
    state=json.loads(fixture.read_text());state['listOnlySubject']=native['id'];fixture.write_text(json.dumps(state))
    listed=request('POST',endpoint,{'action':'list','first':0,'max':1},admin_token)
    assert [u['targetSubject'] for u in listed['users']]==[native['id']], 'Denied early identities must not hide later authorized user'
    assert request('POST',endpoint,{'action':'list','first':1,'max':1},admin_token)['users']==[]
    state=json.loads(fixture.read_text());state.pop('listOnlySubject');fixture.write_text(json.dumps(state))
    if len(sys.argv)==3:
        # Direct grants are enabled only in this disposable fixture to exercise actual UserInfo claims.
        mail_client=transport('clients?clientId=gator-mail')[0];mail_path='clients/'+mail_client['id']
        mail_client=transport(mail_path);mail_client['directAccessGrantsEnabled']=True;transport(mail_path,mail_client,'PUT')
        mail_secret=transport(mail_path+'/client-secret')['value']
        mail_admin_token=token('gator','gator-mail','synthetic-admin',password,mail_secret)
        mail_created=request('POST',endpoint,{'action':'create','requestId':'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            'username':'synthetic-mail-created','localUsuario':'mail-created','name':'Mail Created','email':'mail-created@example.invalid'},mail_admin_token)
        mail_subject=mail_created['user']['targetSubject'];mail_user='/admin/realms/gator/users/'+mail_subject
        wait_projection(mail_subject,'operation','create')
        assert request('GET',mail_user+'/groups',bearer=master)==[]
        mail_password=secrets.token_urlsafe(24)
        request('POST',endpoint,{'action':'reset','targetSubject':mail_subject,'password':mail_password,'temporary':False},mail_admin_token)
        request('POST',endpoint,{'action':'enable','targetSubject':mail_subject},mail_admin_token)
        mail_token=token('gator','gator-mail','synthetic-mail-created',mail_password,mail_secret)
        info=request('GET','/realms/gator/protocol/openid-connect/userinfo',bearer=mail_token)
        assert 'access' not in info.get('resource_access',{}).get('gator-mail',{}).get('roles',[])
        credentials_before_grant=request('GET',mail_user+'/credentials',bearer=master)
        assert request('POST',endpoint,{'action':'grant','targetSubject':mail_subject},mail_admin_token)['granted'] is True
        mail_token=token('gator','gator-mail','synthetic-mail-created',mail_password,mail_secret)
        info=request('GET','/realms/gator/protocol/openid-connect/userinfo',bearer=mail_token)
        assert info['sub']==mail_subject and 'access' in info['resource_access']['gator-mail']['roles']
        assert request('GET',mail_user+'/credentials',bearer=master)==credentials_before_grant
        # Account access is explicit in this fixture so denial cannot be explained by missing account roles.
        account_client=transport('clients?clientId=account')[0]
        manage_account=transport('clients/'+account_client['id']+'/roles/manage-account')
        transport('users/'+mail_subject+'/role-mappings/clients/'+account_client['id'],[manage_account],'POST')
        mail_token=token('gator','gator-mail','synthetic-mail-created',mail_password,mail_secret)
        account_before=request('GET','/realms/gator/account',bearer=mail_token)
        attempted=dict(account_before)
        attempted['attributes']={ 'gator.identityAdmin.create'+field:['tampered-by-account']
            for field in ('OperationId','ActorSubject','ClientId','DestinationDbId','RequestFingerprint') }
        req=urllib.request.Request(BASE+'/realms/gator/account',data=json.dumps(attempted).encode(),
            headers={'Content-Type':'application/json','Authorization':'Bearer '+mail_token},method='POST')
        try:
            with urllib.request.urlopen(req,timeout=15) as response:assert response.status in (200,204)
        except urllib.error.HTTPError as error:assert error.code in (400,403)
        original_mail_create={'action':'create','requestId':'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            'username':'synthetic-mail-created','localUsuario':'mail-created','name':'Mail Created','email':'mail-created@example.invalid'}
        marker_replay=request('POST',endpoint,original_mail_create,mail_admin_token)
        assert marker_replay['operationId']==mail_created['operationId'] and marker_replay['user']['targetSubject']==mail_subject
        request('POST',endpoint,{'action':'update','targetSubject':mail_subject,'name':'Mail Edited'},mail_admin_token)
        assert request('GET',mail_user,bearer=master)['firstName']=='Mail'
        assert request('GET',mail_user,bearer=master)['lastName']=='Edited'
        assert request('POST',endpoint,original_mail_create,mail_admin_token)['operationId']==mail_created['operationId']
    baseline_hash=json.loads(fixture.read_text())['source']['CaseUser']['hash']
    before_fed=request('GET',fedpath,bearer=master)
    assert before_fed['id']==federated and 'UPDATE_PASSWORD' in before_fed['requiredActions']
    request('PUT',fedpath,{'firstName':'Changed Federated','lastName':'Profile','email':'changed@example.invalid','enabled':True},master)
    after_fed=request('GET',fedpath,bearer=master)
    assert after_fed['id']==federated and 'UPDATE_PASSWORD' in after_fed['requiredActions']
    source_after=json.loads(fixture.read_text())['source']['CaseUser']
    assert source_after['hash']==baseline_hash and source_after['name']=='Changed Federated Profile'
    users=request('GET','/admin/realms/gator/users?max=100',bearer=master)
    assert any(u['id']==federated for u in users) and any(u['id']==native['id'] for u in users)
    result=request('POST',endpoint,{'action':'update','targetSubject':native['id'],'name':'Changed Native','email':'changed-native@example.invalid'},admin_token)
    assert result['status']=='pending'
    state=json.loads(fixture.read_text());state['hiddenStatusOperation']=result['operationId'];fixture.write_text(json.dumps(state))
    assert request('POST',endpoint,{'action':'status','targetSubject':native['id'],'operationId':result['operationId']},admin_token)['status']=='pending'
    request('POST',endpoint,{'action':'status','targetSubject':federated,'operationId':result['operationId']},admin_token,404)
    state=json.loads(fixture.read_text());state.pop('hiddenStatusOperation');state['failedStatusOperation']=result['operationId'];fixture.write_text(json.dumps(state))
    assert request('POST',endpoint,{'action':'status','targetSubject':native['id'],'operationId':result['operationId']},admin_token)['status']=='failed'
    state=json.loads(fixture.read_text());state.pop('failedStatusOperation');fixture.write_text(json.dumps(state))
    wait_projection(native['id'],'name','Changed Native')
    assert request('GET',np+'/credentials',bearer=master)==before_credentials
    assert request('GET',np,bearer=master)['requiredActions']==before_required
    request('POST',endpoint,{'action':'update','targetSubject':native['id'],'destinationDbId':'999'},admin_token,400)
    request('POST',endpoint,{'action':'update','targetSubject':native['id'],'password':'forbidden'},admin_token,400)
    request('POST',endpoint,{'action':'update','targetSubject':native['id'],'email':'invalid-email'},admin_token,400)
    request('POST','/admin/realms/gator/groups',{'name':'existing-native-membership'},master)
    existing_group=request('GET','/admin/realms/gator/groups?search=existing-native-membership',bearer=master)[0]
    request('PUT',np+'/groups/'+existing_group['id'],bearer=master)
    assert request('POST',endpoint,{'action':'grant','targetSubject':native['id']},admin_token)['granted'] is True
    assert {g['id'] for g in request('GET',np+'/groups',bearer=master)}=={existing_group['id'],safe_group['id']}
    assert request('POST',endpoint,{'action':'grant','targetSubject':federated},admin_token)['granted'] is True
    assert safe_group['id'] in [g['id'] for g in request('GET',fedpath+'/groups',bearer=master)]
    assert 'UPDATE_PASSWORD' in request('GET',fedpath,bearer=master)['requiredActions']
    create_request={'action':'create','requestId':'11111111-2222-3333-4444-555555555555','username':'synthetic-created','localUsuario':'local-created','accountId':1,'bodegaId':1,'name':'Created Fixture','email':'created@example.invalid'}
    result=request('POST',endpoint,create_request,admin_token)
    created=result['user']['targetSubject']
    assert result['status']=='pending' and result['user']['enabled'] is False
    assert request('GET','/admin/realms/gator/users/'+created+'/groups',bearer=master)==[]
    wait_projection(created,'operation','create')
    # Treat the first successful response as lost: same UUID returns the same committed operation.
    created_credentials=request('GET','/admin/realms/gator/users/'+created+'/credentials',bearer=master)
    retried=request('POST',endpoint,create_request,admin_token)
    assert retried['user']['targetSubject']==created and retried['operationId']==result['operationId']
    assert len(request('GET','/admin/realms/gator/users?username=synthetic-created&exact=true',bearer=master))==1
    assert request('GET','/admin/realms/gator/users/'+created+'/credentials',bearer=master)==created_credentials
    request('POST',endpoint,dict(create_request,name='Changed retry'),admin_token,409)
    request('POST','/admin/realms/gator/users',{'username':'synthetic-other-admin','firstName':'Other','lastName':'Administrator',
        'email':'other-admin@example.invalid','emailVerified':True,'enabled':True,'credentials':[{'type':'password','value':password,'temporary':False}]},master)
    other_admin=request('GET','/admin/realms/gator/users?username=synthetic-other-admin&exact=true',bearer=master)[0]
    request('POST','/admin/realms/gator/users/'+other_admin['id']+'/role-mappings/clients/'+client['id'],[role],master)
    other_token=token('gator','installation-client','synthetic-other-admin',password)
    request('POST',endpoint,create_request,other_token,403)
    client['attributes']['gator.identityAdmin.destinationDbId']='2';request('PUT',cp,client,master)
    request('POST',endpoint,create_request,admin_token,403)
    client['attributes']['gator.identityAdmin.destinationDbId']='1';request('PUT',cp,client,master)
    state=json.loads(fixture.read_text());state['pendingGrantSubject']=created;fixture.write_text(json.dumps(state))
    request('POST',endpoint,{'action':'grant','targetSubject':created},admin_token,409)
    assert request('GET','/admin/realms/gator/users/'+created+'/groups',bearer=master)==[]
    state=json.loads(fixture.read_text());state.pop('pendingGrantSubject');fixture.write_text(json.dumps(state))
    client['attributes']['gator.identityAdmin.accessGroupId']=unsafe_group['id'];request('PUT',cp,client,master)
    request('POST',endpoint,{'action':'grant','targetSubject':created},admin_token,403)
    assert request('GET','/admin/realms/gator/users/'+created+'/groups',bearer=master)==[]
    client['attributes']['gator.identityAdmin.accessGroupId']=safe_group['id'];request('PUT',cp,client,master)
    grant=request('POST',endpoint,{'action':'grant','targetSubject':created},admin_token)
    assert grant['granted'] is True and grant['status']=='complete'
    assert [g['id'] for g in request('GET','/admin/realms/gator/users/'+created+'/groups',bearer=master)]==[safe_group['id']]
    assert role['id'] not in [r['id'] for r in request('GET','/admin/realms/gator/users/'+created+'/role-mappings/clients/'+client['id']+'/composite',bearer=master)]
    state=json.loads(fixture.read_text());state['source']['synthetic-created']=dict(state['source']['CaseUser']);state['nativeBound']=['synthetic-created'];fixture.write_text(json.dumps(state))
    fake_id=urllib.parse.quote('f:'+component['id']+':synthetic-created',safe='')
    request('GET','/admin/realms/gator/users/'+fake_id,bearer=master,expected=404)
    matches=request('GET','/admin/realms/gator/users?username=synthetic-created&exact=true',bearer=master)
    assert [u['id'] for u in matches]==[created]
    client['attributes']['gator.identityAdmin.system']='mail';request('PUT',cp,client,master)
    request('POST',endpoint,{'action':'create','username':'bad-mail-scope','localUsuario':'bad-mail','accountId':1,'bodegaId':1},admin_token,400)
    request('POST',endpoint,{'action':'create','requestId':'bbbbbbbb-cccc-dddd-eeee-ffffffffffff','username':'synthetic-single-name',
        'localUsuario':'local-single-name','name':'Nickname','email':'single-name@example.invalid'},admin_token,400)
    assert request('GET','/admin/realms/gator/users?username=synthetic-single-name&exact=true',bearer=master)==[]
    mail=request('POST',endpoint,{'action':'create','username':'synthetic-mail','localUsuario':'local-mail','name':'Mail Fixture','email':'mail@example.invalid'},admin_token)
    assert mail['user']['enabled'] is False
    wait_projection(mail['user']['targetSubject'],'operation','create')
    client['attributes'].pop('gator.identityAdmin.system');request('PUT',cp,client,master)
    created_password=secrets.token_urlsafe(20)
    explicit_reset=request('POST',endpoint,{'action':'reset','targetSubject':created,'password':created_password,'temporary':False},admin_token)
    assert explicit_reset['status']=='pending'
    assert request('GET','/admin/realms/gator/users/'+created+'/credentials',bearer=master)
    assert 'UPDATE_PASSWORD' not in request('GET','/admin/realms/gator/users/'+created,bearer=master)['requiredActions']
    reset_subject='f:'+component['id']+':ResetUser'
    reset_before=json.loads(fixture.read_text())['source']['ResetUser']['hash']
    request('POST',endpoint,{'action':'reset','targetSubject':reset_subject,'password':secrets.token_urlsafe(20),'temporary':False},admin_token)
    reset_after=json.loads(fixture.read_text())
    assert reset_after['source']['ResetUser']['hash']!=reset_before and reset_after['passwordSyncCalls']==1
    assert not reset_after['source']['ResetUser'].get('required')
    request('POST',endpoint,{'action':'enable','targetSubject':created},admin_token)
    wait_projection(created,'enabled',True)
    token('gator','installation-client','synthetic-created',created_password)
    assert request('GET','/admin/realms/gator/users/'+created+'/sessions',bearer=master)
    request('POST',endpoint,{'action':'disable','targetSubject':created},admin_token)
    assert request('GET','/admin/realms/gator/users/'+created+'/sessions',bearer=master)==[]
    request('DELETE','/admin/realms/gator/users/'+admin['id']+'/role-mappings/clients/'+client['id'],[role],master)
    request('POST',endpoint,{'action':'get','targetSubject':native['id']},admin_token,403)
    request('POST',endpoint,{'action':'grant','targetSubject':created},admin_token,403)
    request('POST','/admin/realms/gator/users/'+admin['id']+'/role-mappings/clients/'+client['id'],[role],master)
    # A committed native console change during SQL-contract transport outage survives process restart and retained-event replay.
    state=json.loads(fixture.read_text());state['offline']=True;fixture.write_text(json.dumps(state))
    request('PUT',np,{'firstName':'After Restart','email':'restart@example.invalid','enabled':True},master)
    assert request('GET',np,bearer=master)['firstName']=='After Restart'
    stop();state=json.loads(fixture.read_text());state['offline']=False;fixture.write_text(json.dumps(state));start()
    wait_projection(native['id'],'name','After Restart Native')
    master=token('master','admin-cli','isolated-admin',password)
    assert request('GET',np+'/credentials',bearer=master)==before_credentials
    assert request('GET',np,bearer=master)['requiredActions']==before_required
    state=json.loads(fixture.read_text())
    assert state['source']['CaseUser']['hash']==baseline_hash
    assert not any('password' in operation for operation in state['operations'].values())
    # API rollback retains the permanent native-binding filter and existing source identities.
    component=request('GET','/admin/realms/gator/components/'+component['id'],bearer=master)
    component['config']['nativeIdentityBindings']=['true']
    component['config']['identityAdministrationEnabled']=['false']
    request('PUT','/admin/realms/gator/components/'+component['id'],component,master)
    realm=request('GET','/admin/realms/gator',bearer=master)
    realm['attributes']['gator.identityAdmin.enabled']='false'
    request('PUT','/admin/realms/gator',realm,master)
    request('POST',endpoint,{'action':'get','targetSubject':created},admin_token,404)
    request('GET','/admin/realms/gator/users/'+fake_id,bearer=master,expected=404)
    assert [u['id'] for u in request('GET','/admin/realms/gator/users?username=synthetic-created&exact=true',bearer=master)]==[created]
    assert request('GET',fedpath,bearer=master)['id']==federated
    (root/'result.json').write_text(json.dumps({'passed':True,'nativeSubject':native['id'],'federatedSubject':federated,'operationCount':len(state['operations']),'scope':'Keycloak integration with synthetic JDBC contract; business SQL integration remains separate'},indent=2))
    print('PASS: native/federated edits and query, exact subjects, auth/input boundaries, creation grants none, explicit safe grant, Mail scope, native SQL shadow rejected, credentials/required actions preserved, durable native restart replay. Evidence '+str(root))
finally:
    stop();log.close()
