package gator.mail.keycloak;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;
import org.keycloak.events.admin.*;
import org.keycloak.models.*;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminEventBuilder;

/** Realm-local administrative entrypoint; the browser never receives administrative credentials. */
@jakarta.ws.rs.ext.Provider
public final class GatorIdentityAdminResource {
    static final Set<String> CREATE_MARKERS=Set.of("gator.identityAdmin.createOperationId","gator.identityAdmin.createActorSubject",
            "gator.identityAdmin.createClientId","gator.identityAdmin.createDestinationDbId","gator.identityAdmin.createRequestFingerprint");
    private final KeycloakSession session;
    private final GatorIdentityAdminSql.Transport sql;
    GatorIdentityAdminResource(KeycloakSession session) { this(session, GatorIdentityAdminSql::call); }
    GatorIdentityAdminResource(KeycloakSession session, GatorIdentityAdminSql.Transport sql) {
        this.session = session; this.sql = sql;
    }

    @POST @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON)
    public Map<String,Object> administer(Map<String,Object> request) {
        try { return execute(request); }
        catch (GatorIdentityAdminSql.Rejected rejected) {
            session.getTransactionManager().setRollbackOnly();
            if (sqlFailureStatus(rejected.code)==403) throw new ForbiddenException();
            if (sqlFailureStatus(rejected.code)==400) throw new BadRequestException();
            throw new ServiceUnavailableException("Identity administration pending");
        } catch (IllegalArgumentException invalid) { session.getTransactionManager().setRollbackOnly();throw new BadRequestException("Invalid identity request");
        } catch (IllegalStateException unavailable) { session.getTransactionManager().setRollbackOnly();throw new ServiceUnavailableException("Identity administration unavailable");
        } catch(WebApplicationException rejected) {session.getTransactionManager().setRollbackOnly();throw rejected;}
    }

    static int sqlFailureStatus(String code) {
        if (Set.of("403","42501").contains(code)) return 403;
        if (Set.of("400","22023").contains(code)) return 400;
        return 503;
    }

    private Map<String,Object> execute(Map<String,Object> request) {
        RealmModel realm = session.getContext().getRealm();
        if (!GatorIdentityAdmin.enabled(realm)) throw new NotFoundException();
        if (!realm.isAdminEventsEnabled() || realm.isAdminEventsDetailsEnabled()
                || !realm.getEventsListenersStream().anyMatch("gator-identity-admin"::equals))
            throw new ServiceUnavailableException("Identity administration event durability unavailable");
        AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).setRealm(realm).authenticate();
        if (auth == null || auth.token() == null || auth.user() == null) throw new NotAuthorizedException("Bearer");
        ClientModel client = realm.getClientByClientId(auth.token().getIssuedFor());
        try { authorizeToken(auth, realm, client, GatorIdentityAdmin.issuer(session, realm)); }
        catch (SecurityException denied) { throw new ForbiddenException(); }
        String destination = client.getAttribute(GatorIdentityAdmin.DESTINATION);
        if (destination == null || !destination.matches("[0-9]+")) throw new ForbiddenException();
        if (request == null || request.size() > 12) throw new BadRequestException();
        String action = text(request, "action");
        if (!Set.of("list", "get", "create", "update", "disable", "enable", "reset", "status", "grant").contains(action))
            throw new BadRequestException("Unknown identity action");
        Set<String> allowed = new HashSet<>(Set.of("action"));
        switch (action) {
            case "list" -> allowed.addAll(Set.of("first","max"));
            case "create" -> allowed.addAll(Set.of("username","localUsuario","accountId","bodegaId","name","email","requestId"));
            case "update" -> allowed.addAll(Set.of("targetSubject","name","email","enabled"));
            case "status" -> allowed.addAll(Set.of("targetSubject","operationId"));
            case "reset" -> allowed.addAll(Set.of("targetSubject","password","temporary"));
            default -> allowed.add("targetSubject");
        }
        if (!allowed.containsAll(request.keySet())) throw new BadRequestException("Unsupported identity input");
        Map<String,Object> command = GatorIdentityAdmin.command("authorize");
        command.put("actorIssuer", auth.token().getIssuer()); command.put("actorSubject", auth.user().getId());
        command.put("issuer", GatorIdentityAdmin.issuer(session, realm));
        command.put("destinationDbId", destination); command.put("operation", action.equals("grant") ? "update" : action);
        String createId = null; String createFingerprint = null; UserModel replay = null;
        if (action.equals("create")) {
            command.put("localUsuario",text(request,"localUsuario"));
            boolean mail = "mail".equals(client.getAttribute("gator.identityAdmin.system"));
            if (mail && (request.containsKey("accountId") || request.containsKey("bodegaId"))) throw new BadRequestException("Unsupported Mail scope");
            for (String field : mail ? List.<String>of() : List.of("accountId","bodegaId")) {
                Object value=request.get(field);
                if (!(value instanceof String || value instanceof Number) || !value.toString().matches("[0-9]{1,9}"))
                    throw new BadRequestException("Invalid local scope");
                command.put(field,value);
            }
            if (request.containsKey("requestId")) {
                if (!protectedCreateMarkers(session.getProvider(org.keycloak.userprofile.UserProfileProvider.class).getConfiguration()))
                    throw new ServiceUnavailableException("Create retry metadata is not protected");
                String requestId=text(request,"requestId");
                if (!requestId.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) throw new BadRequestException("Invalid create request ID");
                requestId=UUID.fromString(requestId).toString();
                createId=createSubject(text(command,"issuer"),client.getClientId(),requestId);
                createFingerprint=createFingerprint(request);
                replay=session.users().getUserById(realm,createId);
                if (replay!=null) {
                    if (!Objects.equals(replay.getFirstAttribute("gator.identityAdmin.createActorSubject"),auth.user().getId())
                            || !Objects.equals(replay.getFirstAttribute("gator.identityAdmin.createDestinationDbId"),destination)
                            || !Objects.equals(replay.getFirstAttribute("gator.identityAdmin.createClientId"),client.getClientId())) throw new ForbiddenException();
                    if (!Objects.equals(replay.getFirstAttribute("gator.identityAdmin.createRequestFingerprint"),createFingerprint))
                        throw new ClientErrorException("Create request ID reused",409);
                    command.put("targetSubject",replay.getId());
                    command.put("operationId",replay.getFirstAttribute("gator.identityAdmin.createOperationId"));
                }
            }
        } else if (!action.equals("list")) command.put("targetSubject", GatorIdentityAdmin.subject(text(request, "targetSubject")));
        if (action.equals("status")) command.put("operationId",GatorIdentityAdmin.subject(text(request,"operationId")));
        Map<String,Object> authorization = sql.call("app_fn_identity_admin", command);
        if (!Boolean.TRUE.equals(authorization.get("authorized"))) throw new ForbiddenException();
        if (replay!=null) {
            Map<String,Object> statusCommand=new LinkedHashMap<>(command);statusCommand.put("action","status");
            Map<String,Object> state=sql.call("app_fn_identity_admin",statusCommand);
            String status=Objects.toString(state.get("status"),"");
            if (status.equals("notFound")) {
                if (!hasRetainedOperation(realm,text(command,"operationId"),replay.getId(),destination))
                    throw new ServiceUnavailableException("Create operation unavailable");
                status="pending";
            }
            if (!Set.of("pending","complete","failed","applied").contains(status)) throw new ServiceUnavailableException("Create operation unavailable");
            return Map.of("operationId",text(command,"operationId"),"status",status.equals("applied")?"complete":status,"user",view(replay));
        }
        if (action.equals("status")) {
            command.put("action", "status"); command.put("operationId", GatorIdentityAdmin.subject(text(request,"operationId")));
            Map<String,Object> result = new LinkedHashMap<>(sql.call("app_fn_identity_admin", command));
            if ("applied".equals(result.get("status"))) result.put("status","complete");
            if ("notFound".equals(result.get("status"))) {
                if (!hasRetainedOperation(realm, text(command,"operationId"), text(command,"targetSubject"), destination))
                    throw new NotFoundException("Identity operation not found");
                result.put("status","pending");
            }
            return result;
        }
        if (action.equals("list")) {
            int first = integer(request.get("first"), 0); int max = Math.min(100, integer(request.get("max"), 20));
            Set<String> subjects = authorizedSubjects(authorization);
            List<UserModel> eligible = authorizedPage(offset -> {
                try (var stream = session.users().searchForUserStream(realm, Map.of(), offset, 100)) {
                    return stream.toList();
                }
            }, user -> subjects.contains(user.getId()), first, max);
            List<Map<String,Object>> users = eligible.stream().map(this::view).toList();
            return Map.of("users", users);
        }
        Map<String,Object> fields = new LinkedHashMap<>();
        for (String field : List.of("name", "email", "enabled")) if (request.containsKey(field)) fields.put(field,request.get(field));
        GatorIdentityAdmin.fields(fields);
        String resetPassword = "";
        boolean temporary = true;
        if (action.equals("reset")) {
            resetPassword = text(request,"password");
            if (resetPassword.isBlank() || resetPassword.length()>1024) throw new BadRequestException("Invalid password");
            if (request.containsKey("temporary") && !(request.get("temporary") instanceof Boolean)) throw new BadRequestException("Invalid temporary flag");
            temporary = !Boolean.FALSE.equals(request.get("temporary"));
        }
        UserModel user;
        if (action.equals("create")) {
            String username = GatorIdentityAdmin.subject(text(request, "username"));
            if (session.users().getUserByUsername(realm, username) != null) throw new ClientErrorException("Identity exists", 409);
            validateProfile(null, username, fields);
            // Explicitly suppress realm default roles/groups when creating an identity.
            user = session.users().addUser(realm, createId==null?UUID.randomUUID().toString():createId, username, false, false);
            user.setEnabled(false);
        } else {
            user = session.users().getUserById(realm, text(command, "targetSubject"));
            if (user == null) throw new NotFoundException();
        }
        if (action.equals("get")) return Map.of("user", view(user), "status", "complete");
        if (action.equals("grant")) return grant(realm, client, auth, user, command);
        if (!action.equals("create") && (fields.containsKey("name") || fields.containsKey("email")))
            validateProfile(user,user.getUsername(),fields);
        if (fields.containsKey("name")) {
            var name=GatorIdentityAdmin.nameParts((String)fields.get("name"));
            user.setFirstName(name.firstName());user.setLastName(name.lastName());
        }
        if (fields.containsKey("email") && !Objects.equals(user.getEmail(),fields.get("email"))) {
            user.setEmail((String) fields.get("email")); user.setEmailVerified(false);
        }
        if (action.equals("disable")) user.setEnabled(false);
        else if (action.equals("enable")) user.setEnabled(true);
        else if (fields.containsKey("enabled") && !action.equals("create")) user.setEnabled((Boolean)fields.get("enabled"));
        if (action.equals("reset")) {
            if (!user.credentialManager().updateCredential(UserCredentialModel.password(resetPassword, true)))
                throw new BadRequestException("Password update rejected");
            if (temporary) user.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
            else user.removeRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        }
        if (!user.isEnabled() || action.equals("reset")) session.sessions().removeUserSessions(realm,user);
        String operation = UUID.randomUUID().toString();
        if (action.equals("create")) {
            if (createId!=null) {
                operation=UUID.nameUUIDFromBytes(("create-operation:"+createId+":"+auth.user().getId()+":"+destination).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                user.setSingleAttribute("gator.identityAdmin.createActorSubject",auth.user().getId());
                user.setSingleAttribute("gator.identityAdmin.createClientId",client.getClientId());
                user.setSingleAttribute("gator.identityAdmin.createDestinationDbId",destination);
                user.setSingleAttribute("gator.identityAdmin.createRequestFingerprint",createFingerprint);
            }
            user.setSingleAttribute("gator.identityAdmin.createOperationId", operation);
        }
        AdminEventBuilder event = new AdminEventBuilder(realm, new AdminAuth(realm,auth.token(),auth.user(),client),
                session, session.getContext().getConnection()).resource(ResourceType.USER)
                .operation(action.equals("create") ? OperationType.CREATE : OperationType.UPDATE)
                .resourcePath("users", user.getId()).detail("gator.operationId", operation)
                .detail("gator.destinationDbId", destination).detail("gator.operation", action.equals("reset") ? "update" : action)
                .detail("gator.actorIssuer", auth.token().getIssuer());
        if (action.equals("create")) for (String field : List.of("localUsuario", "accountId", "bodegaId"))
            if (command.containsKey(field)) event.detail("gator."+field, Objects.toString(command.get(field),""));
        event.success();
        // The durable event commits with Keycloak; SQL projection happens only after that commit.
        return Map.of("operationId", operation, "status", "pending", "user", view(user));
    }

    static String createSubject(String issuer,String client,String requestId) {
        return UUID.nameUUIDFromBytes(("closed-create:"+issuer+":"+client+":"+requestId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    static boolean protectedCreateMarkers(org.keycloak.representations.userprofile.config.UPConfig profile) {
        if (profile==null) return false;
        var attributes=profile.getAttributes()==null?List.<org.keycloak.representations.userprofile.config.UPAttribute>of():profile.getAttributes();
        for(String marker:CREATE_MARKERS) {
            var definitions=attributes.stream().filter(a->marker.equals(a.getName())).toList();
            if(definitions.isEmpty()) {
                if(profile.getUnmanagedAttributePolicy()==org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy.ENABLED) return false;
            } else if(definitions.size()!=1 || definitions.getFirst().getPermissions()==null
                    || !Set.of("admin").equals(definitions.getFirst().getPermissions().getEdit())
                    || !Set.of("admin").equals(definitions.getFirst().getPermissions().getView())) return false;
        }
        return true;
    }

    static String createFingerprint(Map<String,?> request) {
        try {
            TreeMap<String,Object> fields=new TreeMap<>(request);fields.remove("requestId");
            byte[] canonical=org.keycloak.util.JsonSerialization.writeValueAsBytes(fields);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (java.io.IOException|java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException("Create request unavailable");}
    }

    static boolean retainedOperation(AdminEvent event, String operation, String subject, String destination) {
        return subject.equals(GatorIdentityAdmin.eventSubject(event)) && event.getDetails() != null
                && operation.equals(event.getDetails().get("gator.operationId"))
                && destination.equals(event.getDetails().get("gator.destinationDbId"));
    }

    private boolean hasRetainedOperation(RealmModel realm, String operation, String subject, String destination) {
        org.keycloak.events.EventStoreProvider store = session.getProvider(org.keycloak.events.EventStoreProvider.class);
        if (store == null) throw new IllegalStateException("Durable identity operation history unavailable");
        for (int offset=0; offset<10_000; offset+=100) {
            List<AdminEvent> events;
            try (var stream=store.createAdminQuery().realm(realm.getId()).resourceType(ResourceType.USER)
                    .orderByDescTime().firstResult(offset).maxResults(100).getResultStream()) { events=stream.toList(); }
            if (events.stream().anyMatch(event -> retainedOperation(event,operation,subject,destination))) return true;
            if (events.size()<100) return false;
        }
        throw new IllegalStateException("Durable identity operation query limit exceeded");
    }

    static Set<String> authorizedSubjects(Map<String,Object> authorization) {
        if (!(authorization.get("authorizedSubjects") instanceof Collection<?> values) || values.size()>10000)
            throw new IllegalStateException("Identity scope unavailable");
        Set<String> subjects = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String subject) || subject.isBlank() || subject.length()>1024
                    || subject.chars().anyMatch(c -> c<32) || !subjects.add(subject))
                throw new IllegalStateException("Identity scope unavailable");
        }
        return subjects;
    }

    static <T> List<T> authorizedPage(java.util.function.IntFunction<List<T>> pages,
            java.util.function.Predicate<T> allowed, int first, int max) {
        if (first < 0 || max < 0 || max > 100) throw new IllegalArgumentException("Invalid identity pagination");
        List<T> result = new ArrayList<>();
        if (max == 0) return result;
        int skipped = 0;
        for (int offset = 0; offset < 10_000; offset += 100) {
            List<T> page = pages.apply(offset);
            for (T candidate : page) {
                if (!allowed.test(candidate)) continue;
                if (skipped < first) { skipped++; continue; }
                result.add(candidate);
                if (result.size() == max) return result;
            }
            if (page.size() < 100) return result;
        }
        throw new IllegalStateException("Identity query limit exceeded");
    }

    private Map<String,Object> grant(RealmModel realm, ClientModel client, AuthResult auth,
            UserModel user, Map<String,Object> authorization) {
        String groupId = client.getAttribute("gator.identityAdmin.accessGroupId");
        RoleModel access = client.getRole("access");
        GroupModel group = groupId == null || groupId.isBlank() ? null : session.groups().getGroupById(realm, groupId);
        if (access == null || !access.isClientRole() || !client.getId().equals(access.getContainerId())) throw new ForbiddenException();
        try { validateAccessGroup(group, access); } catch (SecurityException invalid) { throw new ForbiddenException(); }
        String creation = user.getFirstAttribute("gator.identityAdmin.createOperationId");
        if (creation != null) {
            Map<String,Object> status = new LinkedHashMap<>(authorization);
            status.put("action", "status"); status.put("operationId", creation);
            String state = Objects.toString(sql.call("app_fn_identity_admin", status).get("status"), "pending");
            if (!Set.of("complete", "applied").contains(state)) throw new ClientErrorException("Identity projection pending",409);
        }
        if (!user.isMemberOf(group)) user.joinGroup(group);
        if (!user.hasRole(access)) throw new IllegalStateException("Client access unavailable");
        new AdminEventBuilder(realm, new AdminAuth(realm,auth.token(),auth.user(),client), session,
                session.getContext().getConnection()).resource(ResourceType.GROUP_MEMBERSHIP)
                .operation(OperationType.CREATE).resourcePath("users", user.getId(), "groups", groupId).success();
        return Map.of("status","complete","granted",true,"user",view(user));
    }

    static void validateAccessGroup(GroupModel group, RoleModel access) {
        if (group == null || access == null || access.isComposite()) throw new SecurityException("Invalid access group");
        Set<String> seen = new HashSet<>(); boolean mapped = false;
        for (GroupModel current = group; current != null; current = current.getParent()) {
            if (!seen.add(current.getId())) throw new SecurityException("Invalid access group hierarchy");
            try (var roles = current.getRoleMappingsStream()) {
                for (RoleModel role : roles.toList()) {
                    if (role.isComposite() || !access.getId().equals(role.getId())) throw new SecurityException("Overprivileged access group");
                    mapped = true;
                }
            }
        }
        if (!mapped) throw new SecurityException("Missing client access");
    }

    static void authorizeToken(AuthResult auth, RealmModel realm, ClientModel client, String issuer) {
        if (auth == null || auth.user() == null || auth.token() == null || client == null || !client.isEnabled()
                || !auth.user().isEnabled() || !issuer.equals(auth.token().getIssuer())
                || !auth.user().getId().equals(auth.token().getSubject())
                || !client.getClientId().equals(auth.token().getIssuedFor())) throw new SecurityException("Identity administration denied");
        RoleModel role = client.getRole("identity-admin");
        var access = auth.token().getResourceAccess(client.getClientId());
        if (role == null || !auth.user().hasRole(role) || access == null || !access.isUserInRole("identity-admin"))
            throw new SecurityException("Identity administration denied");
    }
    private void validateProfile(UserModel user,String username,Map<String,Object> fields) {
        Map<String,Object> attributes=new LinkedHashMap<>();
        if(user!=null)attributes.putAll(user.getAttributes());
        attributes.put(UserModel.USERNAME,username);
        if(fields.containsKey("name")) {
            var name=GatorIdentityAdmin.nameParts((String)fields.get("name"));
            attributes.put(UserModel.FIRST_NAME,name.firstName());attributes.put(UserModel.LAST_NAME,name.lastName());
            if(user==null && name.lastName().isBlank()) {
                var profile=session.getProvider(org.keycloak.userprofile.UserProfileProvider.class).getConfiguration();
                var surname=profile.getAttribute(UserModel.LAST_NAME);
                var required=surname==null?null:surname.getRequired();
                if(required!=null && (required.isAlways() || required.getRoles()!=null && required.getRoles().contains("user")
                        || required.getScopes()!=null && required.getScopes().contains("profile")))
                    throw new BadRequestException("Full name requires a surname");
            }
        }
        if(fields.containsKey("email"))attributes.put(UserModel.EMAIL,fields.get("email"));
        try {
            var profiles=session.getProvider(org.keycloak.userprofile.UserProfileProvider.class);
            profiles.create(org.keycloak.userprofile.UserProfileContext.USER_API,attributes,user).validate();
        } catch(org.keycloak.userprofile.ValidationException invalid) {throw new BadRequestException("Invalid identity profile");}
    }

    private Map<String,Object> view(UserModel user) {
        Map<String,Object> result = GatorIdentityAdmin.snapshot(session, session.getContext().getRealm(), user);
        result.put("username", user.getUsername()); return result;
    }
    private static String text(Map<String,Object> map,String key) {
        Object value = map.get(key); return value instanceof String text ? text : "";
    }
    private static int integer(Object value,int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.intValue()<0) throw new BadRequestException();
        return number.intValue();
    }
}
