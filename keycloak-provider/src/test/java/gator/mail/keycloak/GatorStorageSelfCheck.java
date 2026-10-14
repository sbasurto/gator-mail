package gator.mail.keycloak;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.*;
import org.keycloak.storage.federated.UserFederatedStorageProvider;
import java.util.stream.Stream;

/** Exercises actual lookups through JDBC without contacting a customer database. */
public final class GatorStorageSelfCheck {
    static String lastUrl;
    static String lastQuery;
    static String lastSyncQuery;
    static String lastUser;
    static String email;
    static int rowCount = 1;
    static Map<String, String> fixtureAccounts;
    static Set<String> nativeBindings = Set.of();
    static String syncResponse = "{\"responses\":[{\"resNum\":\"0\"}]}";
    static Map<Integer, String> passwordBindings;
    static int commits, rollbacks, passwordUpdates;
    static boolean rejectPasswordUpdate;

    public static void main(String[] args) throws Exception {
        GatorUserStorageProvider.requireSyncSuccess("{\"responses\":[{\"resNum\":\"0\"}]}");
        for (String failed : List.of("{}", "not-json", "{\"responses\":[{\"resNum\":\"08001\"}]}",
                "{\"responses\":[]}", "{\"responses\":[{\"resNum\":\"0\"},{\"resNum\":\"0\"}]}")) {
            try { GatorUserStorageProvider.requireSyncSuccess(failed); throw new AssertionError("Invalid sync accepted"); }
            catch (IllegalStateException expected) { assert !expected.getMessage().contains(failed); }
        }
        assert new GatorUserStorageProviderFactory().getConfigProperties().stream()
                .map(p -> p.getName()).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("connectionEnvironmentPrefix", "literalUsernames"));
        DriverManager.registerDriver(new Driver() {
            public Connection connect(String url, Properties properties) {
                if (!acceptsURL(url)) return null;
                lastUrl = url;
                return proxy(Connection.class, (object, method, values) -> {
                    if (method.getName().equals("prepareStatement")) {
                        String preparedSql = (String) values[0];
                        lastQuery = preparedSql;
                        Map<Integer, String> bindings = new HashMap<>();
                        return proxy(PreparedStatement.class, (statement, operation, parameters) -> {
                            if (operation.getName().equals("setInt")) return null;
                            if (operation.getName().equals("setString")) { lastUser = (String) parameters[1]; bindings.put((Integer) parameters[0], lastUser); return null; }
                            if (operation.getName().equals("executeQuery")) {
                                if (preparedSql.contains("json_build_object")) lastSyncQuery = preparedSql;
                                List<String> names = preparedSql.contains("json_build_object") ? List.of(syncResponse)
                                        : preparedSql.startsWith("select count(*)") ? List.of("count") : fixtureAccounts == null ? Collections.nCopies(rowCount, lastUser)
                                        : fixtureAccounts.keySet().stream().filter(name -> {
                                            if (lastQuery.contains("subject <> concat('f:'") && nativeBindings.contains(name)) return false;
                                            if (lastQuery.contains("order by u.usuario_id")) return true;
                                            if (lastQuery.contains("lower(e.usuario_email_email)")) return fixtureAccounts.get(name).equalsIgnoreCase(bindings.get(1)) && (!lastQuery.contains("position") || !name.contains("@"));
                                            if (lastQuery.contains("lower(u.usuario_id)")) return name.equalsIgnoreCase(bindings.get(1));
                                            return name.equals(bindings.get(1));
                                        }).toList();
                                int[] index = {-1};
                                return proxy(ResultSet.class, (row, getter, column) -> {
                                    if (getter.getName().equals("next")) { return ++index[0] < names.size(); }
                                    if (getter.getName().equals("getInt")) return preparedSql.startsWith("select count(*)") ? fixtureAccounts.size() - (preparedSql.contains("subject <> concat('f:'") ? nativeBindings.size() : 0) : 3;
                                    if (getter.getName().equals("getString")) return switch ((int) column[0]) {
                                        case 1 -> names.get(index[0]);
                                        case 5 -> "1";
                                        case 6 -> fixtureAccounts == null ? email : fixtureAccounts.get(names.get(index[0]));
                                        default -> null;
                                    };
                                    if (getter.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(getter.getName());
                                });
                            }
                            if (operation.getName().equals("executeUpdate")) {
                                if (preparedSql.contains("usuario_password")) {
                                    if (rejectPasswordUpdate) throw new SQLException("private SQL/password context", "XX000");
                                    passwordBindings = Map.copyOf(bindings); passwordUpdates++;
                                }
                                return 1;
                            }
                            if (operation.getName().equals("close")) return null;
                            throw new UnsupportedOperationException(operation.getName());
                        });
                    }
                    if (method.getName().equals("setAutoCommit")) { assert values[0].equals(false); return null; }
                    if (method.getName().equals("commit")) { commits++; return null; }
                    if (method.getName().equals("rollback")) { rollbacks++; return null; }
                    if (method.getName().equals("close")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
            }
            public boolean acceptsURL(String url) { return url.startsWith("jdbc:gator-test:"); }
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
            public int getMajorVersion() { return 1; }
            public int getMinorVersion() { return 0; }
            public boolean jdbcCompliant() { return false; }
            public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        });
        ComponentModel legacy = new ComponentModel(); legacy.setId("legacy");
        var mail = new GatorUserStorageProvider(null, legacy);
        mail.getUserByUsername(null, "Admin");
        assert lastUrl.equals("jdbc:gator-test:mail");
        assert lastQuery.contains("lower(u.usuario_id) = lower(?)") && lastQuery.endsWith("limit 1");
        mail.getUserByUsername(null, "user@example.com");
        assert lastQuery.contains("position('@' in u.usuario_id) = 0");
        fixtureAccounts = new LinkedHashMap<>();
        fixtureAccounts.put("Admin", "shared@example.invalid");
        fixtureAccounts.put("admin", "shared@example.invalid");
        fixtureAccounts.put("user@example.com", "different@example.invalid");
        fixtureAccounts.put("Alias", "user@example.com");
        assert mail.getUserByUsername(null, "admin").getUsername().equals("Admin") : "Legacy case-fold lookup unchanged";
        assert mail.getUserByUsername(null, "user@example.com").getUsername().equals("Alias") : "Legacy email alias precedence unchanged";
        assert mail.getUserByEmail(null, "shared@example.invalid").getUsername().equals("Admin");
        boolean[] optIn = {true};
        ClientModel exactClient = proxy(ClientModel.class, (o,m,v) -> switch(m.getName()) {
            case "getAttribute" -> optIn[0] ? "true" : null;
            case "getId" -> "exact-client";
            default -> null;
        });
        KeycloakContext clientContext = proxy(KeycloakContext.class, (o,m,v) ->
            m.getName().equals("getClient") ? exactClient : null);
        KeycloakSession exactSession = proxy(KeycloakSession.class, (o,m,v) ->
            m.getName().equals("getContext") ? clientContext : null);
        assert !GatorExactIdentity.enabled((KeycloakSession)null);
        assert GatorExactIdentity.enabled(exactSession);
        optIn[0] = false;
        assert !GatorExactIdentity.enabled(exactSession) : "Shared clients remain legacy by default";
        optIn[0] = true;
        ClientModel otherClient = proxy(ClientModel.class, (o,m,v) -> m.getName().equals("getId") ? "other-client" : null);
        ClientModel[] authClient = {exactClient};
        org.keycloak.sessions.AuthenticationSessionModel authenticated = proxy(
            org.keycloak.sessions.AuthenticationSessionModel.class, (o,m,v) -> m.getName().equals("getClient") ? authClient[0] : null);
        ClientModel[] contextual = {null};
        KeycloakContext authenticationContext = proxy(KeycloakContext.class, (o,m,v) -> switch(m.getName()) {
            case "getAuthenticationSession" -> authenticated;
            case "getClient" -> contextual[0];
            default -> null;
        });
        KeycloakSession authenticationSession = proxy(KeycloakSession.class, (o,m,v) ->
            m.getName().equals("getContext") ? authenticationContext : null);
        assert GatorExactIdentity.enabled(authenticationSession) : "Browser required action uses its authenticated client";
        contextual[0] = otherClient;
        try { GatorExactIdentity.enabled(authenticationSession); throw new AssertionError("Conflicting context downgraded to legacy"); }
        catch (IllegalStateException expected) { }
        contextual[0] = exactClient;
        assert GatorExactIdentity.enabled(authenticationSession);
        authClient[0] = null;
        try { GatorExactIdentity.enabled(authenticationSession); throw new AssertionError("Missing authenticated client downgraded"); }
        catch (IllegalStateException expected) { }
        authClient[0] = exactClient;
        var exactMail = new GatorUserStorageProvider(exactSession, legacy);
        assert exactMail.getUserByEmail(null, "shared@example.invalid") == null;
        assert exactMail.getUserById(null, "f:legacy:admin").getUsername().equals("admin");
        fixtureAccounts = null;

        email = "verified-source@example.invalid";
        var account = mail.getUserByUsername(null, "Admin");
        new GatorUserStorageProvider(null, legacy).isValid(null, account, UserCredentialModel.password("incorrect"));
        assert lastQuery.endsWith(" where lower(u.usuario_id) = lower(?) limit 1") : "Legacy reload unchanged";
        syncResponse = "{\"codigo\":\"0\"}";
        assert mail.updateCredential(null, account, UserCredentialModel.password("legacy-password"));
        assert lastSyncQuery.contains("app_fn_admon_tablas_all") && passwordBindings.equals(Map.of(1,"legacy-password",2,"Admin"));
        syncResponse = "{\"responses\":[{\"resNum\":\"0\"}]}";
        commits = rollbacks = passwordUpdates = 0;
        mail = exactMail;
        String hexPassword = "ab".repeat(64);
        assert mail.updateCredential(null, account, UserCredentialModel.password(hexPassword));
        assert passwordBindings.equals(Map.of(1, hexPassword, 2, hexPassword, 3, hexPassword, 4, "Admin"));
        assert commits == 1 && passwordUpdates == 1;
        assert lastSyncQuery.contains("app_fn_sync_identity_password");
        syncResponse = "{\"responses\":[{\"resNum\":\"42501\"}]}";
        try { mail.updateCredential(null, account, UserCredentialModel.password("rejected-password"));
            throw new AssertionError("Downstream failure accepted"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("42501"); }
        assert rollbacks == 1 && commits == 1 && passwordUpdates == 1;
        syncResponse = "{\"responses\":[{\"resNum\":\"0\"}]}";
        rejectPasswordUpdate = true;
        try { mail.updateCredential(null, account, UserCredentialModel.password("synthetic-secret"));
            throw new AssertionError("SQL failure accepted"); }
        catch (IllegalStateException expected) {
            assert expected.getCause() == null && expected.getMessage().contains("XX000");
            assert !expected.getMessage().contains("private SQL");
        }
        rejectPasswordUpdate = false;
        assert account.getEmail().equals(email);
        assert account.getFirstAttribute(UserModel.EMAIL).equals(email);
        assert account.getAttributeStream(UserModel.EMAIL).toList().equals(List.of(email));
        assert account.getAttributes().get(UserModel.EMAIL).equals(List.of(email));
        assert mail.getUserByEmail(null, email) != null;
        rowCount = 2;
        assert mail.getUserByEmail(null, email) == null : "Ambiguous recovery email must fail closed";
        rowCount = 1;
        assert mail.getUserById(null, "f:legacy:user@example.com").getUsername().equals("user@example.com");
        assert lastQuery.endsWith(" where u.usuario_id = ? limit 1");
        assert mail.getUserById(null, "f:other:Admin") == null;
        email = null;
        assert mail.getUserByUsername(null, "Admin").getAttributeStream(UserModel.EMAIL).count() == 0;

        // Only access mappings live in Keycloak; keep the external subject and JDBC identity.
        var attributes = new org.keycloak.common.util.MultivaluedHashMap<String, String>();
        Set<GroupModel> groups = new HashSet<>();
        Set<RoleModel> roles = new HashSet<>();
        GroupModel group = proxy(GroupModel.class, (o, m, v) -> switch (m.getName()) {
            case "getId" -> "pilot-group";
            case "hashCode" -> 1;
            case "equals" -> o == v[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        RoleModel role = proxy(RoleModel.class, (o, m, v) -> switch (m.getName()) {
            case "getId" -> "access-role";
            case "getCompositesStream" -> Stream.empty();
            case "hashCode" -> 2;
            case "equals" -> o == v[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        RealmModel realm = proxy(RealmModel.class, (o, m, v) -> switch (m.getName()) {
            case "getDefaultGroupsStream" -> Stream.empty();
            case "getDefaultRole" -> role;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        UserFederatedStorageProvider storage = proxy(UserFederatedStorageProvider.class, (o, m, v) -> {
            assert v[0] == realm && v[1].equals("f:legacy:Admin");
            return switch (m.getName()) {
                case "getAttributes" -> attributes;
                case "setSingleAttribute" -> { attributes.put((String) v[2], List.of((String) v[3])); yield null; }
                case "removeAttribute" -> { attributes.remove(v[2]); yield null; }
                case "getGroupsStream" -> groups.stream();
                case "getRoleMappingsStream" -> roles.stream();
                case "joinGroup" -> { groups.add((GroupModel) v[2]); yield null; }
                case "leaveGroup" -> { groups.remove(v[2]); yield null; }
                case "grantRole" -> { roles.add((RoleModel) v[2]); yield null; }
                case "deleteRoleMapping" -> { roles.remove(v[2]); yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
        KeycloakSession session = proxy(KeycloakSession.class, (o, m, v) -> {
            if (m.getName().equals("getProvider") && v[0] == UserFederatedStorageProvider.class) return storage;
            throw new UnsupportedOperationException(m.getName());
        });
        email = "source@example.invalid";
        var mapped = new GatorUserStorageProvider(session, legacy).getUserByUsername(realm, "Admin");
        assert !mapped.isEmailVerified() : "Source address is not verification evidence";
        mapped.setEmailVerified(true);
        assert mapped.isEmailVerified();
        email = "changed@example.invalid";
        var changedEmail = new GatorUserStorageProvider(session, legacy).getUserByUsername(realm, "Admin");
        assert !changedEmail.isEmailVerified() : "Verification cannot survive an address change";
        changedEmail.setEmailVerified(true);
        assert changedEmail.isEmailVerified() && !mapped.isEmailVerified();
        changedEmail.setEmailVerified(false);
        assert !changedEmail.isEmailVerified();
        try { changedEmail.setEmail("attacker@example.invalid"); throw new AssertionError("Email source is read only"); }
        catch (org.keycloak.storage.ReadOnlyException expected) { }

        mapped.removeRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        assert lastQuery.contains("usuario_hash_auth = null") && lastUser.equals("Admin");
        mapped.addRequiredAction(UserModel.RequiredAction.UPDATE_PASSWORD);
        assert lastQuery.contains("usuario_hash_auth = 'UPDATE_PASSWORD'") && lastUser.equals("Admin");
        mapped.joinGroup(group);
        mapped.grantRole(role);
        var reloaded = new GatorUserStorageProvider(session, legacy).getUserById(realm, mapped.getId());
        assert reloaded.getId().equals("f:legacy:Admin") && reloaded.isEnabled();
        assert reloaded.getGroupsStream().anyMatch(g -> g.getId().equals("pilot-group"));
        assert reloaded.getRoleMappingsStream().anyMatch(r -> r.getId().equals("access-role"));
        reloaded.leaveGroup(group);
        reloaded.deleteRoleMapping(role);
        assert mapped.getGroupsStream().count() == 0 && mapped.getRoleMappingsStream().count() == 0;
        try { mapped.setUsername("other"); throw new AssertionError("Renaming would change the subject"); }
        catch (org.keycloak.storage.ReadOnlyException expected) { }

        ComponentModel isolated = new ComponentModel(); isolated.setId("erm");
        isolated.put("connectionEnvironmentPrefix", "GATOR_LOMALINDA_ERM");
        isolated.put("literalUsernames", "true");
        var erm = new GatorUserStorageProvider(null, isolated);
        for (String username : List.of("Admin", "admin", "user@example.com")) {
            assert erm.getUserByUsername(null, username).getUsername().equals(username);
            assert lastUrl.equals("jdbc:gator-test:erm") : "Must never use the mail database";
            assert lastQuery.endsWith(" where u.usuario_id = ? limit 1") : "Preserve exact account identity";
            assert erm.getUserById(null, "f:erm:" + username).getUsername().equals(username);
            assert lastQuery.endsWith(" where u.usuario_id = ? limit 1");
        }
        isolated.put("connectionEnvironmentPrefix", "GATOR_MISSING");
        try { erm.getUserByUsername(null, "admin"); throw new AssertionError("Missing connection must fail closed"); }
        catch (IllegalStateException expected) { assert expected.getMessage().contains("GATOR_MISSING_JDBC_URL"); }
        isolated.put("connectionEnvironmentPrefix", "invalid-prefix");
        try { erm.getUserByUsername(null, "admin"); throw new AssertionError("Invalid prefix accepted"); }
        catch (IllegalArgumentException expected) { }
        ComponentModel central = new ComponentModel(); central.setId("central");
        central.put("identityAdministrationEnabled", "true"); central.put("literalUsernames", "true");
        RealmModel centralRealm = proxy(RealmModel.class, (o,m,v) -> switch(m.getName()) {
            case "getName" -> "gator";case "getId" -> "gator-id";
            case "getAttribute" -> "gator.identityAdmin.enabled".equals(v[0])?"true":"https://identity.example/realms/gator";
            default -> null;
        });
        List<Map<String,Object>> updates = new ArrayList<>();
        UserFederatedStorageProvider centralStorage = proxy(UserFederatedStorageProvider.class, (o,m,v) -> switch(m.getName()) {
            case "getAttributes" -> attributes;
            case "removeAttribute" -> {attributes.remove(v[2]);yield null;}
            default -> throw new UnsupportedOperationException(m.getName());
        });
        KeycloakSession centralSession = proxy(KeycloakSession.class, (o,m,v) -> m.getName().equals("getProvider")?centralStorage:null);
        GatorUserStorageProvider adminSource = new GatorUserStorageProvider(centralSession, central, (fn,input) -> {
            assert fn.equals("app_fn_identity_admin"); updates.add(Map.copyOf(input)); return Map.of("status","pending");
        });
        fixtureAccounts = new LinkedHashMap<>(Map.of("ExactUser","old@example.invalid","NativeSqlUser","native@example.invalid"));
        nativeBindings = Set.of("NativeSqlUser");
        assert adminSource.getUserById(centralRealm,"f:central:NativeSqlUser") == null : "Native SQL projection must not become a federated shadow";
        UserModel editable = adminSource.getUserById(centralRealm,"f:central:ExactUser");
        int passwordBefore=passwordUpdates;
        editable.setFirstName("Given"); editable.setLastName("Family"); editable.setEmail("new@example.invalid"); editable.setEnabled(false);
        assert editable.getId().equals("f:central:ExactUser") && editable.getUsername().equals("ExactUser");
        assert editable.getFirstName().equals("Given") && editable.getLastName().equals("Family");
        assert editable.getEmail().equals("new@example.invalid") && !editable.isEnabled() && !editable.isEmailVerified();
        assert passwordUpdates==passwordBefore && updates.size()==4;
        assert updates.stream().allMatch(u->u.get("action").equals("sourceUpdate") && u.get("targetSubject").equals("f:central:ExactUser") && !u.containsKey("password"));
        assert adminSource.getUsersCount(centralRealm)==1;
        assert adminSource.searchForUserStream(centralRealm,Map.of(),0,10).count()==1;
        assert lastQuery.contains("order by u.usuario_id collate") && lastQuery.contains("limit ? offset ?");
        assert new GatorUserStorageProvider(null,legacy).searchForUserStream(centralRealm,Map.of(),0,10).count()==0;
        central.put("nativeIdentityBindings","true");central.put("identityAdministrationEnabled","false");
        RealmModel disabledRealm=proxy(RealmModel.class,(o,m,v)->switch(m.getName()) {
            case "getName" -> "gator";case "getId" -> "gator-id";
            case "getAttribute" -> "gator.identityAdmin.enabled".equals(v[0])?"false":"https://identity.example/realms/gator";
            default -> null;
        });
        assert adminSource.getUserByUsername(disabledRealm,"NativeSqlUser")==null : "Disabling administration exposed native SQL shadow";
        assert adminSource.getUserById(disabledRealm,"f:central:NativeSqlUser")==null;
        assert adminSource.getUserById(disabledRealm,"f:central:ExactUser")!=null;
        assert adminSource.getUsersCount(disabledRealm)==1;
        assert adminSource.searchForUserStream(disabledRealm,Map.of(),0,10).count()==1;
        try {editable.setEnabled(true);throw new AssertionError("Disabled administration allowed source write");}
        catch(org.keycloak.storage.ReadOnlyException expected) { }
        central.put("identityAdministrationEnabled","true");
        central.put("connectionEnvironmentPrefix","GATOR_LOMALINDA_ERM");
        try {editable.setEnabled(true);throw new AssertionError("Noncentral source mutated");}
        catch(org.keycloak.storage.ReadOnlyException expected) { }
        System.out.println("Storage isolation, central administration and legacy compatibility OK");
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
