package gator.mail.keycloak;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.credential.UserCredentialManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.UserStorageUtil;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.adapter.AbstractUserAdapter;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.keycloak.storage.ReadOnlyException;
import java.util.ArrayList;

final class GatorUserStorageProvider implements UserStorageProvider, UserLookupProvider, UserQueryProvider,
        CredentialInputValidator, CredentialInputUpdater {
    private static final String SELECT = """
            select u.usuario_id, u.usuario_password, u.usuario_recover_hash, u.usuario_hash_loops,
                   u.usuario_estado, e.usuario_email_email, u.usuario_hash_auth, u.usuario_nombre
              from app_usuarios u
              left join lateral (
                    select usuario_email_email from app_usuario_email
                     where usuario_id = u.usuario_id
                     order by usuario_email_por_defecto desc nulls last, rowid limit 1
              ) e on true
            """;
    private final KeycloakSession session;
    private final ComponentModel model;
    private final GatorIdentityAdminSql.Transport adminSql;
    private final Map<String, Account> loaded = new HashMap<>();

    GatorUserStorageProvider(KeycloakSession session, ComponentModel model) {
        this(session, model, GatorIdentityAdminSql::call);
    }

    GatorUserStorageProvider(KeycloakSession session, ComponentModel model, GatorIdentityAdminSql.Transport adminSql) {
        this.session = session;
        this.model = model;
        this.adminSql = adminSql;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (model.get("literalUsernames", false))
            return adapter(realm, find(realm, "u.usuario_id = ?", username));
        return adapter(realm, username != null && username.contains("@")
                ? findLegacyEmail(realm, username) : find(realm, "lower(u.usuario_id) = lower(?)", username));
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return adapter(realm, GatorExactIdentity.enabled(session)
                ? find(realm, "lower(e.usuario_email_email) = lower(?)", email, true) : findLegacyEmail(realm, email));
    }

    private Account findLegacyEmail(RealmModel realm, String email) {
        Account account = find(realm, "lower(e.usuario_email_email) = lower(?) and position('@' in u.usuario_id) = 0", email);
        return account != null ? account : find(realm, "lower(e.usuario_email_email) = lower(?)", email);
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        StorageId stored = new StorageId(id);
        if (!model.getId().equals(stored.getProviderId())) return null;
        return adapter(realm, find(realm, "u.usuario_id = ?", stored.getExternalId()));
    }

    private UserModel adapter(RealmModel realm, Account account) {
        if (account == null) return null;
        loaded.put(account.username(), account);
        return new AbstractUserAdapter(session, realm, model) {
            private final Map<String, Object> edited = new HashMap<>();
            private void write(String key, Object value) {
                if (!administration(realm)) throw new ReadOnlyException("Central identity administration disabled");
                Map<String,Object> command = GatorIdentityAdmin.command("sourceUpdate");
                command.put("issuer", GatorIdentityAdmin.issuer(session, realm));
                command.put("targetSubject", getId());
                command.putAll(GatorIdentityAdmin.fields(Map.of(key, value)));
                adminSql.call("app_fn_identity_admin", command);
                edited.put(key, value);
                loaded.remove(account.username());
            }
            @Override public String getFirstName() { return administration(realm) ? (String) edited.getOrDefault("firstName", GatorIdentityAdmin.nameParts(account.name()).firstName()) : super.getFirstName(); }
            @Override public String getLastName() { return administration(realm) ? (String) edited.getOrDefault("lastName", GatorIdentityAdmin.nameParts(account.name()).lastName()) : super.getLastName(); }
            @Override public void setFirstName(String name) {
                write("name", String.join(" ", name == null ? "" : name, getLastName()).trim());
                edited.put("firstName", name == null ? "" : name);
            }
            @Override public void setLastName(String name) {
                String first = getFirstName() == null ? "" : getFirstName();
                write("name", String.join(" ", first, name == null ? "" : name).trim());
                edited.put("firstName", first);
                edited.put("lastName", name == null ? "" : name);
            }
            @Override public void setEmail(String email) {
                if (administration(realm) && java.util.Objects.equals(getEmail(), email)) return;
                write("email", email == null ? "" : email);
                setEmailVerified(false);
            }
            @Override public void setEnabled(boolean enabled) { write("enabled", enabled); }
            // Preserve the external identity and credential; only access grants live in Keycloak.
            @Override protected Set<GroupModel> getGroupsInternal() {
                return UserStorageUtil.userFederatedStorage(session).getGroupsStream(realm, getId())
                        .collect(Collectors.toSet());
            }
            @Override public void joinGroup(GroupModel group) {
                UserStorageUtil.userFederatedStorage(session).joinGroup(realm, getId(), group);
            }
            @Override public void leaveGroup(GroupModel group) {
                UserStorageUtil.userFederatedStorage(session).leaveGroup(realm, getId(), group);
            }
            @Override protected Set<RoleModel> getRoleMappingsInternal() {
                return UserStorageUtil.userFederatedStorage(session).getRoleMappingsStream(realm, getId())
                        .collect(Collectors.toSet());
            }
            @Override public void grantRole(RoleModel role) {
                UserStorageUtil.userFederatedStorage(session).grantRole(realm, getId(), role);
            }
            @Override public void deleteRoleMapping(RoleModel role) {
                UserStorageUtil.userFederatedStorage(session).deleteRoleMapping(realm, getId(), role);
            }
            @Override public String getUsername() { return account.username(); }
            @Override public String getEmail() { return (String) edited.getOrDefault("email", account.email()); }
            @Override public void setSingleAttribute(String name, String value) {
                setAttribute(name, value == null ? List.of() : List.of(value));
            }
            @Override public void removeAttribute(String name) { setAttribute(name, List.of()); }
            @Override public void setAttribute(String name, List<String> values) {
                if (!administration(realm)) throw new ReadOnlyException("Central identity administration disabled");
                if (values.size() > 1) throw new IllegalArgumentException("Identity profile fields are single-valued");
                String value = values.isEmpty() ? "" : values.getFirst();
                switch (name) {
                    case UserModel.USERNAME -> { if (!getUsername().equals(value)) throw new ReadOnlyException("Identity username is immutable"); }
                    case UserModel.FIRST_NAME -> setFirstName(value);
                    case UserModel.LAST_NAME -> setLastName(value);
                    case UserModel.EMAIL -> setEmail(value);
                    default -> throw new ReadOnlyException("Unsupported central profile field");
                }
            }
            @Override public String getFirstAttribute(String name) {
                if (administration(realm) && name.equals(UserModel.FIRST_NAME)) return getFirstName();
                if (administration(realm) && name.equals(UserModel.LAST_NAME)) return getLastName();
                return UserModel.EMAIL.equals(name) ? getEmail() : super.getFirstAttribute(name);
            }
            @Override public List<String> getAttribute(String name) {
                if (administration(realm) && (name.equals(UserModel.FIRST_NAME) || name.equals(UserModel.LAST_NAME))) {
                    String value = getFirstAttribute(name); return value == null || value.isBlank() ? List.of() : List.of(value);
                }
                if (!UserModel.EMAIL.equals(name)) return super.getAttribute(name);
                return getEmail() == null || getEmail().isBlank() ? List.of() : List.of(getEmail());
            }
            @Override public Map<String, List<String>> getAttributes() {
                Map<String, List<String>> attributes = new HashMap<>(super.getAttributes());
                if (!getAttribute(UserModel.EMAIL).isEmpty()) attributes.put(UserModel.EMAIL, getAttribute(UserModel.EMAIL));
                if (administration(realm)) for (String field : List.of(UserModel.FIRST_NAME, UserModel.LAST_NAME))
                    if (!getAttribute(field).isEmpty()) attributes.put(field, getAttribute(field));
                return attributes;
            }
            // Keycloak confirms the current address after the reset-email action token.
            // A later change in the authoritative source invalidates that confirmation.
            @Override public boolean isEmailVerified() {
                return getEmail() != null && !getEmail().isBlank() && getEmail().equals(
                        UserStorageUtil.userFederatedStorage(session).getAttributes(realm, getId())
                                .getFirst("gator.verifiedEmail"));
            }
            @Override public void setEmailVerified(boolean verified) {
                var storage = UserStorageUtil.userFederatedStorage(session);
                if (verified && getEmail() != null && !getEmail().isBlank())
                    storage.setSingleAttribute(realm, getId(), "gator.verifiedEmail", getEmail());
                else storage.removeAttribute(realm, getId(), "gator.verifiedEmail");
            }
            @Override public boolean isEnabled() { return (Boolean) edited.getOrDefault("enabled", account.enabled()); }
            @Override public SubjectCredentialManager credentialManager() {
                return new UserCredentialManager(session, realm, this);
            }
            @Override public Stream<String> getRequiredActionsStream() {
                return account.passwordChangeRequired()
                        ? Stream.of(UserModel.RequiredAction.UPDATE_PASSWORD.name()) : Stream.empty();
            }
            @Override public void addRequiredAction(String action) {
                if (UserModel.RequiredAction.UPDATE_PASSWORD.name().equals(action))
                    execute("update app_usuarios set usuario_hash_auth = 'UPDATE_PASSWORD' where usuario_id = ?", account.username());
                else super.addRequiredAction(action);
            }
            @Override public void addRequiredAction(UserModel.RequiredAction action) {
                addRequiredAction(action.name());
            }
            @Override public void removeRequiredAction(UserModel.RequiredAction action) {
                removeRequiredAction(action.name());
            }
            @Override public void removeRequiredAction(String action) {
                if (UserModel.RequiredAction.UPDATE_PASSWORD.name().equals(action)) clearPasswordChange(account.username());
            }
        };
    }

    private Account find(RealmModel realm, String condition, String value) {
        return find(realm, condition, value, false);
    }

    private Account find(RealmModel realm, String condition, String value, boolean unique) {
        if (value == null || value.isBlank()) return null;
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(SELECT + " where " + condition + nativeGuard(realm) + (unique ? " limit 2" : " limit 1"))) {
            statement.setString(1, value);
            if (queryEnabled(realm)) bindGuard(statement, realm, 2);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                Account account = new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        "1".equals(rs.getString(5)), rs.getString(6), "UPDATE_PASSWORD".equals(rs.getString(7)), rs.getString(8));
                return unique && rs.next() ? null : account;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No fue posible consultar usuarios Gator", e);
        }
    }


    private String nativeGuard(RealmModel realm) {
        return queryEnabled(realm) ? " and not exists(select 1 from app_oidc_identity i where i.usuario_id=u.usuario_id and i.issuer=? and i.subject <> concat('f:',?,':',u.usuario_id))" : "";
    }
    private void bindGuard(PreparedStatement statement, RealmModel realm, int index) throws SQLException {
        statement.setString(index, GatorIdentityAdmin.issuer(session, realm));
        statement.setString(index + 1, model.getId());
    }

    private boolean administration(RealmModel realm) {
        return model.get("identityAdministrationEnabled", false)
                && "GATOR_IDP".equals(model.get("connectionEnvironmentPrefix", "GATOR_IDP"))
                && GatorIdentityAdmin.enabled(realm);
    }

    private boolean queryEnabled(RealmModel realm) {
        return administration(realm) || model.get("nativeIdentityBindings",false)
                && realm!=null && "gator".equals(realm.getName())
                && "GATOR_IDP".equals(model.get("connectionEnvironmentPrefix","GATOR_IDP"));
    }

    @Override public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params,
            Integer firstResult, Integer maxResults) {
        if (!queryEnabled(realm)) return Stream.empty();
        List<String> values = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        Map<String, String> columns = Map.of(UserModel.USERNAME, "u.usuario_id", UserModel.EMAIL,
                "e.usuario_email_email", UserModel.FIRST_NAME, "u.usuario_nombre", UserModel.SEARCH,
                "concat_ws(' ',u.usuario_id,u.usuario_nombre,e.usuario_email_email)");
        for (var entry : params.entrySet()) {
            if (entry.getKey().equals(UserModel.EXACT) || entry.getKey().equals(UserModel.INCLUDE_SERVICE_ACCOUNT)) continue;
            if (entry.getKey().equals(UserModel.ENABLED)) {
                if (!Set.of("true", "false").contains(entry.getValue())) return Stream.empty();
                conditions.add("u.usuario_estado = ?"); values.add("true".equals(entry.getValue()) ? "1" : "0"); continue;
            }
            String column = columns.get(entry.getKey());
            if (column == null) return Stream.empty();
            boolean exact = "true".equals(params.get(UserModel.EXACT));
            conditions.add(exact ? column + " = ?" : "position(lower(?) in lower(" + column + ")) > 0");
            values.add(entry.getValue());
        }
        int first = Math.max(0, firstResult == null ? 0 : firstResult);
        int limit = Math.min(1000, Math.max(0, maxResults == null ? 1000 : maxResults));
        List<UserModel> users = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(SELECT
                + " where " + (conditions.isEmpty() ? "true" : String.join(" and ", conditions)) + nativeGuard(realm)
                + " order by u.usuario_id collate \"C\" limit ? offset ?")) {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
            bindGuard(statement, realm, values.size() + 1);
            statement.setInt(values.size() + 3, limit); statement.setInt(values.size() + 4, first);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) users.add(adapter(realm, new Account(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4), "1".equals(rs.getString(5)), rs.getString(6),
                        "UPDATE_PASSWORD".equals(rs.getString(7)), rs.getString(8))));
            }
            return users.stream();
        } catch (SQLException failure) { throw new IllegalStateException("Identity query unavailable"); }
    }

    @Override public int getUsersCount(RealmModel realm, boolean includeServiceAccount) {
        if (!queryEnabled(realm)) return 0;
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement("select count(*) from app_usuarios u where true" + nativeGuard(realm))) {
            bindGuard(statement, realm, 1);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        } catch (SQLException failure) { throw new IllegalStateException("Identity count unavailable"); }
    }

    @Override public int getUsersCount(RealmModel realm, Map<String, String> params) {
        int total = 0;
        while (true) {
            long page;
            try (Stream<UserModel> users = searchForUserStream(realm, params, total, 1000)) { page = users.count(); }
            total += Math.toIntExact(page);
            if (page < 1000) return total;
        }
    }

    @Override public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group,
            Integer first, Integer max) {
        if (!queryEnabled(realm)) return Stream.empty();
        return UserStorageUtil.userFederatedStorage(session).getMembershipStream(realm, group, first, max)
                .map(id -> getUserById(realm, id)).filter(java.util.Objects::nonNull);
    }

    @Override public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm,
            String name, String value) {
        return searchForUserStream(realm, Map.of(name, value, UserModel.EXACT, "true"), 0, 1000);
    }

    @Override public boolean supportsCredentialType(String type) { return PasswordCredentialModel.TYPE.equals(type); }
    @Override public boolean isConfiguredFor(RealmModel realm, UserModel user, String type) {
        return supportsCredentialType(type);
    }
    @Override public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel credential)) return false;
        Account account = loaded.computeIfAbsent(user.getUsername(), key -> find(realm,
                model.get("literalUsernames", false) || GatorExactIdentity.enabled(session)
                        ? "u.usuario_id = ?" : "lower(u.usuario_id) = lower(?)", key));
        return account != null && account.enabled()
                && GatorPassword.matches(credential.getValue(), account.salt(), account.iterations(), account.hash());
    }
    @Override public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || input.getChallengeResponse() == null
                || input.getChallengeResponse().isBlank()) return false;
        updatePassword(user.getUsername(), input.getChallengeResponse(), administration(realm));
        loaded.remove(user.getUsername());
        return true;
    }
    @Override public void disableCredentialType(RealmModel realm, UserModel user, String type) { }
    @Override public Stream<String> getDisableableCredentialTypesStream(RealmModel realm, UserModel user) {
        return Stream.empty();
    }
    @Override public void close() { loaded.clear(); }

    private void clearPasswordChange(String username) {
        execute("update app_usuarios set usuario_hash_auth = null where usuario_id = ?", username);
    }

    static void requireSyncSuccess(String response) {
        String errorCode = "invalid-response";
        try {
            Map<?, ?> result = org.keycloak.util.JsonSerialization.readValue(response, Map.class);
            if (result.get("responses") instanceof List<?> entries && entries.size() == 1
                    && entries.get(0) instanceof Map<?, ?> entry && entry.get("resNum") instanceof String code) {
                if ("0".equals(code)) return;
                if (code.matches("[A-Z0-9]{1,12}")) errorCode = code;
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            // Downstream output can contain sensitive context; never echo it in server logs.
        }
        throw new IllegalStateException("Password synchronization rejected: " + errorCode);
    }

    private void updatePassword(String username, String password, boolean centralAdministration) {
        boolean exact = centralAdministration || GatorExactIdentity.enabled(session);
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement sync = connection.prepareStatement(
                        "select " + (exact ? "app_fn_sync_identity_password" : "app_fn_admon_tablas_all")
                        + "(json_build_object('usuario', ?, 'password', ?)::text)");
                 PreparedStatement update = connection.prepareStatement(
                        exact ? "update app_usuarios set usuario_password = case "
                        + "when ? ~ '^[0-9A-Fa-f]{128}$' and usuario_recover_hash is not null and usuario_hash_loops::integer > 0 "
                        + "then app_fn_hash_password(?, usuario_recover_hash, usuario_hash_loops::integer) else ? end, "
                        + "usuario_hash_auth = null where usuario_id = ?"
                        : "update app_usuarios set usuario_password = ?, usuario_hash_auth = null where usuario_id = ?")) {
                sync.setString(1, username);
                sync.setString(2, password);
                try (ResultSet response = sync.executeQuery()) {
                    if (exact) {
                        if (!response.next()) throw new IllegalStateException("Missing password synchronization response");
                        requireSyncSuccess(response.getString(1));
                    }
                }
                update.setString(1, password);
                if (exact) {
                    // A literal 128-hex password is not an imported source hash.
                    update.setString(2, password);
                    update.setString(3, password);
                    update.setString(4, username);
                } else {
                    update.setString(2, username);
                }
                if (update.executeUpdate() != 1) throw new IllegalStateException("Usuario Gator no encontrado");
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            String state = e.getSQLState();
            String code = state != null && state.matches("[A-Z0-9]{5}") ? state : "SQL";
            // JDBC exception details can contain the password from the source UPDATE.
            throw new IllegalStateException("No fue posible sincronizar la contraseña Gator (" + code + ")");
        }
    }

    private void execute(String sql, String... values) {
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Usuario Gator no encontrado");
        } catch (SQLException e) {
            throw new IllegalStateException("No fue posible actualizar el usuario Gator", e);
        }
    }

    private String required(String name) {
        String prefix = model.get("connectionEnvironmentPrefix", "GATOR_IDP");
        if (!prefix.matches("[A-Z][A-Z0-9_]*"))
            throw new IllegalArgumentException("Invalid connection environment prefix");
        name = prefix + name.substring("GATOR_IDP".length());
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Falta configurar " + name);
        return value;
    }

    private record Account(String username, String hash, String salt, int iterations, boolean enabled, String email,
                           boolean passwordChangeRequired, String name) { }
}
