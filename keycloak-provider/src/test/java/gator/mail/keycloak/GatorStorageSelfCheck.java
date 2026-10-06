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

    public static void main(String[] args) throws Exception {
        assert new GatorUserStorageProviderFactory().getConfigProperties().stream()
                .map(p -> p.getName()).collect(java.util.stream.Collectors.toSet())
                .containsAll(Set.of("connectionEnvironmentPrefix", "literalUsernames"));
        DriverManager.registerDriver(new Driver() {
            public Connection connect(String url, Properties properties) {
                if (!acceptsURL(url)) return null;
                lastUrl = url;
                return proxy(Connection.class, (object, method, values) -> {
                    if (method.getName().equals("prepareStatement")) {
                        lastQuery = (String) values[0];
                        return proxy(PreparedStatement.class, (statement, operation, parameters) -> {
                            if (operation.getName().equals("setString")) { lastUser = (String) parameters[1]; return null; }
                            if (operation.getName().equals("executeQuery")) {
                                boolean[] first = {true};
                                return proxy(ResultSet.class, (row, getter, column) -> {
                                    if (getter.getName().equals("next")) { boolean result = first[0]; first[0] = false; return result; }
                                    if (getter.getName().equals("getInt")) return 3;
                                    if (getter.getName().equals("getString")) return switch ((int) column[0]) {
                                        case 1 -> lastUser;
                                        case 5 -> "1";
                                        default -> null;
                                    };
                                    if (getter.getName().equals("close")) return null;
                                    throw new UnsupportedOperationException(getter.getName());
                                });
                            }
                            if (operation.getName().equals("close")) return null;
                            throw new UnsupportedOperationException(operation.getName());
                        });
                    }
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
        assert lastQuery.contains("lower(u.usuario_id) = lower(?)");
        mail.getUserByUsername(null, "user@example.com");
        assert lastQuery.contains("lower(e.usuario_email_email)");

        // Only access mappings live in Keycloak; keep the external subject and JDBC identity.
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
        var mapped = new GatorUserStorageProvider(session, legacy).getUserByUsername(realm, "Admin");
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
