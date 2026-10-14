package gator.mail.keycloak;

import java.util.*;
import org.keycloak.events.admin.*;
import org.keycloak.models.*;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import java.lang.reflect.Proxy;

/** Security and retry checks use synthetic identities and no network/database. */
public final class GatorIdentityAdminSelfCheck {
    public static void main(String[] args) {
        assert GatorIdentityAdmin.nameParts("Given Family").equals(new GatorIdentityAdmin.NameParts("Given","Family"));
        assert GatorIdentityAdmin.nameParts("Given  Family").equals(new GatorIdentityAdmin.NameParts("Given ","Family"));
        assert GatorIdentityAdmin.nameParts("Nickname").lastName().isEmpty();
        var profile=new org.keycloak.representations.userprofile.config.UPConfig();
        assert GatorIdentityAdminResource.protectedCreateMarkers(profile);
        profile.setUnmanagedAttributePolicy(org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy.ENABLED);
        assert !GatorIdentityAdminResource.protectedCreateMarkers(profile);
        for(String marker:GatorIdentityAdminResource.CREATE_MARKERS) profile.addOrReplaceAttribute(
                new org.keycloak.representations.userprofile.config.UPAttribute(marker,
                    new org.keycloak.representations.userprofile.config.UPAttributePermissions(Set.of("admin"),Set.of("admin"))));
        assert GatorIdentityAdminResource.protectedCreateMarkers(profile);
        profile.getAttributes().getFirst().getPermissions().setEdit(Set.of("admin","user"));
        assert !GatorIdentityAdminResource.protectedCreateMarkers(profile);
        try {
            GatorIdentityAdminSql.response("{\"responses\":[{\"resNum\":\"42501\",\"response\":{\"message\":\"private actor data\"}}]}");
            throw new AssertionError("Revoked SQL actor accepted");
        } catch(GatorIdentityAdminSql.Rejected denied) {
            assert GatorIdentityAdminResource.sqlFailureStatus(denied.code)==403;
            assert !denied.getMessage().contains("private actor data");
        }
        assert GatorIdentityAdminResource.sqlFailureStatus("503")==503;
        assert GatorIdentityAdminResource.sqlFailureStatus("22023")==400;
        String requestId="11111111-2222-3333-4444-555555555555";
        assert GatorIdentityAdminResource.createSubject("https://id/realms/gator","client",requestId)
                .equals(GatorIdentityAdminResource.createSubject("https://id/realms/gator","client",requestId));
        assert !GatorIdentityAdminResource.createSubject("https://id/realms/gator","client",requestId)
                .equals(GatorIdentityAdminResource.createSubject("https://id/realms/gator","other",requestId));
        assert GatorIdentityAdminResource.createFingerprint(Map.of("username","exact","name","Name"))
                .equals(GatorIdentityAdminResource.createFingerprint(Map.of("name","Name","username","exact")));
        assert !GatorIdentityAdminResource.createFingerprint(Map.of("name","Before"))
                .equals(GatorIdentityAdminResource.createFingerprint(Map.of("name","After")));
        assert GatorIdentityAdminResource.authorizedSubjects(Map.of("authorizedSubjects",List.of("exact-A","exact-B")))
                .equals(Set.of("exact-A","exact-B"));
        assert GatorIdentityAdminResource.authorizedSubjects(Map.of("authorizedSubjects",List.of())).isEmpty();
        for (Map<String,Object> invalid : List.of(Map.<String,Object>of(),Map.<String,Object>of("authorizedSubjects","all"),
                Map.<String,Object>of("authorizedSubjects",List.of(42)),Map.<String,Object>of("authorizedSubjects",List.of("")),
                Map.<String,Object>of("authorizedSubjects",List.of("same","same")))) {
            try { GatorIdentityAdminResource.authorizedSubjects(invalid); throw new AssertionError("Malformed SQL scope accepted"); }
            catch (IllegalStateException expected) { }
        }
        assert !GatorIdentityAdmin.enabled("gator", null);
        assert !GatorIdentityAdmin.enabled("legacy", "true");
        assert GatorIdentityAdmin.enabled("gator", "true");
        for (String bad : List.of("", " ", "\n")) {
            try { GatorIdentityAdmin.subject(bad); throw new AssertionError("Invalid subject accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        assert GatorIdentityAdmin.subject("f:provider:CaseUser").equals("f:provider:CaseUser");
        assert GatorIdentityAdmin.eventSubject(event("users/f:provider:CaseUser", ResourceType.USER)).equals("f:provider:CaseUser");
        assert GatorIdentityAdmin.eventSubject(event("users/x/reset-password", ResourceType.USER)).equals("x");
        assert GatorIdentityAdmin.eventSubject(event("clients/x", ResourceType.CLIENT)) == null;
        AdminEvent bad = event("users/x", ResourceType.USER); bad.setError("rejected");
        assert GatorIdentityAdmin.eventSubject(bad) == null;
        assert GatorIdentityAdmin.eventSubject(event("users/x/groups/g", ResourceType.GROUP_MEMBERSHIP)) == null;
        Map<String,Object> input = new HashMap<>(Map.of("name", "Name", "email", "a@example.invalid", "enabled", true));
        assert GatorIdentityAdmin.fields(input).equals(input);
        input.put("password", "private");
        try { GatorIdentityAdmin.fields(input); throw new AssertionError("Credential silently accepted"); }
        catch (IllegalArgumentException expected) { assert !expected.getMessage().contains("private"); }
        assert GatorIdentityAdminSql.response("{\"responses\":[{\"resNum\":\"0\",\"response\":{\"status\":\"pending\"}}]}").get("status").equals("pending");
        for (String failed : List.of("{}", "not-json", "{\"responses\":[{\"resNum\":\"403\",\"response\":{\"secret\":\"private\"}}]}")) {
            try { GatorIdentityAdminSql.response(failed); throw new AssertionError("Failure accepted"); }
            catch (IllegalStateException expected) { assert !expected.getMessage().contains("private"); }
        }
        retainedOperation();
        authorizedPagination();
        accessGroup();
        tokenScope();
        replayFailure();
        enqueueFailure();
        System.out.println("Identity administration security checks passed");
    }
    static void retainedOperation() {
        AdminEvent event=new AdminEvent();event.setResourceType(org.keycloak.events.admin.ResourceType.USER);
        event.setOperationType(org.keycloak.events.admin.OperationType.UPDATE);event.setResourcePath("users/exact-target");
        event.setDetails(new java.util.HashMap<>(Map.of("gator.operationId","operation-fixture","gator.destinationDbId","1")));
        assert GatorIdentityAdminResource.retainedOperation(event,"operation-fixture","exact-target","1");
        assert !GatorIdentityAdminResource.retainedOperation(event,"unknown-operation","exact-target","1");
        assert !GatorIdentityAdminResource.retainedOperation(event,"operation-fixture","other-target","1");
        assert !GatorIdentityAdminResource.retainedOperation(event,"operation-fixture","exact-target","2");
        event.setError("Denied");assert !GatorIdentityAdminResource.retainedOperation(event,"operation-fixture","exact-target","1");
    }
    static void authorizedPagination() {
        List<Integer> raw=java.util.stream.IntStream.range(0,240).boxed().toList();
        List<Integer> requests=new ArrayList<>();
        java.util.function.IntFunction<List<Integer>> pages=offset->{requests.add(offset);return raw.subList(Math.min(offset,raw.size()),Math.min(offset+100,raw.size()));};
        java.util.function.Predicate<Integer> allowed=value->value>=105 && value%2==1;
        assert GatorIdentityAdminResource.authorizedPage(pages,allowed,1,3).equals(List.of(107,109,111)):"Pagination must count only authorized identities";
        assert requests.equals(List.of(0,100)):"Stop after the requested authorized slice";
        assert GatorIdentityAdminResource.authorizedPage(pages,allowed,67,10).equals(List.of(239));
        assert GatorIdentityAdminResource.authorizedPage(pages,allowed,68,10).isEmpty();
        requests.clear();assert GatorIdentityAdminResource.authorizedPage(pages,allowed,0,0).isEmpty() && requests.isEmpty();
    }
    static void accessGroup() {
        RoleModel access=proxy(RoleModel.class,(o,m,v)->switch(m.getName()) {case "getId"->"access";case "isComposite"->false;default->null;});
        RoleModel privileged=proxy(RoleModel.class,(o,m,v)->switch(m.getName()) {case "getId"->"other";case "isComposite"->false;default->null;});
        List<RoleModel>[] roles=new List[]{List.of(access)};
        GroupModel group=proxy(GroupModel.class,(o,m,v)->switch(m.getName()) {case "getId"->"safe";case "getRoleMappingsStream"->roles[0].stream();default->null;});
        GatorIdentityAdminResource.validateAccessGroup(group,access);
        roles[0]=List.of(access,privileged);
        try {GatorIdentityAdminResource.validateAccessGroup(group,access);throw new AssertionError("Overprivileged group accepted");}
        catch(SecurityException denied) { }
        roles[0]=List.of();
        try {GatorIdentityAdminResource.validateAccessGroup(group,access);throw new AssertionError("Group without access accepted");}
        catch(SecurityException denied) { }
    }
    static void tokenScope() {
        RoleModel role=proxy(RoleModel.class,(o,m,v)->m.getName().equals("getId")?"admin-role":null);
        boolean[] granted={true};
        UserModel user=proxy(UserModel.class,(o,m,v)->switch(m.getName()) {
            case "getId"->"exact-admin-subject";case "isEnabled"->true;case "hasRole"->granted[0];default->null;
        });
        ClientModel client=proxy(ClientModel.class,(o,m,v)->switch(m.getName()) {
            case "getClientId"->"installation-client";case "isEnabled"->true;case "getRole"->role;default->null;
        });
        AccessToken token=new AccessToken();token.setSubject("exact-admin-subject");
        token.issuer("https://identity.example/realms/gator");token.issuedFor("installation-client");
        token.addAccess("installation-client").addRole("identity-admin");
        AuthResult auth=new AuthResult(user,null,token,client);
        GatorIdentityAdminResource.authorizeToken(auth,null,client,token.getIssuer());
        for(String wrong:List.of("subject","issuer","client","grant")) {
            if(wrong.equals("subject"))token.setSubject("forged");
            if(wrong.equals("issuer"))token.issuer("https://wrong.example/realms/gator");
            if(wrong.equals("client"))token.issuedFor("wrong-client");
            if(wrong.equals("grant"))granted[0]=false;
            try {GatorIdentityAdminResource.authorizeToken(auth,null,client,"https://identity.example/realms/gator");throw new AssertionError("Forged/revoked scope accepted");}
            catch(SecurityException denied) { }
            token.setSubject("exact-admin-subject");token.issuer("https://identity.example/realms/gator");token.issuedFor("installation-client");granted[0]=true;
        }
    }
    static void replayFailure() {
        List<Map<String,Object>> calls=new ArrayList<>();
        GatorIdentityAdminSql.Transport transport=(fn,input)-> {
            calls.add(new LinkedHashMap<>(input));
            if("complete".equals(input.get("action")) && "op".equals(input.get("operationId")))throw new IllegalStateException("Destination down");
            if("pending".equals(input.get("action")))return Map.of("events",List.of(Map.of("targetSubject","native-subject","operationId","op","destinationDbId","1","version",1L),Map.of("targetSubject","native-subject","operationId","other-op","destinationDbId","2","version",2L)));
            return Map.of();
        };
        UserProvider users=proxy(UserProvider.class,(o,m,v)->null);
        KeycloakSession session=proxy(KeycloakSession.class,(o,m,v)->m.getName().equals("users")?users:null);
        GatorIdentityAdminEvents.drain(session,null,transport);
        assert calls.stream().filter(c->"complete".equals(c.get("action"))).count()==2:"A failed destination must not starve other pending destinations";
        assert calls.stream().noneMatch(c->c.containsKey("status")):"Provider must not assert SQL destination success";
    }
    static void enqueueFailure() {
        List<String> calls=new ArrayList<>();
        AdminEvent first=event("users/first",ResourceType.USER), second=event("users/second",ResourceType.USER);
        second.setId("second-event");
        org.keycloak.events.admin.AdminEventQuery[] query=new org.keycloak.events.admin.AdminEventQuery[1];
        query[0]=proxy(org.keycloak.events.admin.AdminEventQuery.class,(o,m,v)->m.getName().equals("getResultStream")?List.of(first,second).stream():query[0]);
        org.keycloak.events.EventStoreProvider store=proxy(org.keycloak.events.EventStoreProvider.class,(o,m,v)->query[0]);
        RealmModel realm=proxy(RealmModel.class,(o,m,v)->switch(m.getName()) {
            case "getId","getName"->"gator";case "getAttribute"-> v[0].equals("gator.identityAdmin.enabled")?"true":"https://identity.example/realms/gator";
            case "isAdminEventsEnabled"->true;case "isAdminEventsDetailsEnabled"->false;default->null;
        });
        KeycloakContext context=proxy(KeycloakContext.class,(o,m,v)->null);
        UserProvider users=proxy(UserProvider.class,(o,m,v)->null);
        KeycloakSession session=proxy(KeycloakSession.class,(o,m,v)->switch(m.getName()) {case "getContext"->context;case "users"->users;case "getProvider"->store;default->null;});
        GatorIdentityAdminEvents.replay(session,realm,(fn,input)-> {
            String action=(String)input.get("action");calls.add(action);
            if(action.equals("enqueue") && input.get("targetSubject").equals("first"))throw new IllegalStateException("Synthetic enqueue failure");
            return action.equals("pending")?Map.of("events",List.of()):Map.of();
        });
        assert calls.equals(List.of("enqueue","enqueue","pending")):"An unavailable enqueue must not starve later events or SQL drain";
    }
    static <T>T proxy(Class<T>type,java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    static AdminEvent event(String path, ResourceType type) {
        AdminEvent event = new AdminEvent(); event.setResourcePath(path); event.setResourceType(type);
        event.setOperationType(OperationType.UPDATE); event.setId("synthetic-event"); return event;
    }
}
