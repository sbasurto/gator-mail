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
    static String lastUser;
    static String email;
    static int rowCount = 1;
    static Map<String, String> fixtureAccounts;
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
                            if (operation.getName().equals("setString")) { lastUser = (String) parameters[1]; bindings.put((Integer) parameters[0], lastUser); return null; }
                            if (operation.getName().equals("executeQuery")) {
                                List<String> names = preparedSql.contains("app_fn_sync_identity_password") ? List.of(syncResponse)
                                        : fixtureAccounts == null ? Collections.nCopies(rowCount, lastUser)
                                        : fixtureAccounts.keySet().stream().filter(name -> {
                                            if (lastQuery.contains("lower(e.usuario_email_email)")) return fixtureAccounts.get(name).equalsIgnoreCase(lastUser);
                                            if (lastQuery.contains("lower(u.usuario_id)")) return name.equalsIgnoreCase(lastUser);
                                            return name.equals(lastUser);
                                        }).toList();
                                int[] index = {-1};
                                return proxy(ResultSet.class, (row, getter, column) -> {
                                    if (getter.getName().equals("next")) { return ++index[0] < names.size(); }
                                    if (getter.getName().equals("getInt")) return 3;
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
        assert lastQuery.contains("u.usuario_id = ?");
        mail.getUserByUsername(null, "user@example.com");
        assert lastQuery.contains("u.usuario_id = ?");

        fixtureAccounts = Map.of("Admin", "shared@example.invalid", "admin", "shared@example.invalid",
                "user@example.com", "different@example.invalid", "Only", "alias@example.invalid");
        assert mail.getUserByUsername(null, "Admin").getUsername().equals("Admin");
        assert mail.getUserByUsername(null, "admin").getUsername().equals("admin");
        assert mail.getUserByUsername(null, "ADMIN") == null;
        assert mail.getUserByUsername(null, "user@example.com").getUsername().equals("user@example.com");
        assert mail.getUserByUsername(null, "alias@example.invalid").getUsername().equals("Only");
        assert mail.getUserByUsername(null, "only").getUsername().equals("Only");
        assert mail.getUserByEmail(null, "shared@example.invalid") == null;
        fixtureAccounts = null;

        email = "verified-source@example.invalid";
        var account = mail.getUserByUsername(null, "Admin");
        new GatorUserStorageProvider(null, legacy).isValid(null, account, UserCredentialModel.password("incorrect"));
        assert lastQuery.endsWith(" where u.usuario_id = ? limit 1") : "Credential reload must preserve exact username";
        String hexPassword = "ab".repeat(64);
        assert mail.updateCredential(null, account, UserCredentialModel.password(hexPassword));
        assert passwordBindings.equals(Map.of(1, hexPassword, 2, hexPassword, 3, hexPassword, 4, "Admin"));
        assert commits == 1 && passwordUpdates == 1;
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
        System.out.println("Storage isolation and legacy compatibility OK");
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
