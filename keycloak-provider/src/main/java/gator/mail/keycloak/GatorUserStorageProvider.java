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

final class GatorUserStorageProvider implements UserStorageProvider, UserLookupProvider,
        CredentialInputValidator, CredentialInputUpdater {
    private static final String SELECT = """
            select u.usuario_id, u.usuario_password, u.usuario_recover_hash, u.usuario_hash_loops,
                   u.usuario_estado, e.usuario_email_email, u.usuario_hash_auth
              from app_usuarios u
              left join lateral (
                    select usuario_email_email from app_usuario_email
                     where usuario_id = u.usuario_id
                     order by usuario_email_por_defecto desc nulls last, rowid limit 1
              ) e on true
            """;
    private final KeycloakSession session;
    private final ComponentModel model;
    private final Map<String, Account> loaded = new HashMap<>();

    GatorUserStorageProvider(KeycloakSession session, ComponentModel model) {
        this.session = session;
        this.model = model;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        Account exact = find("u.usuario_id = ?", username);
        if (exact != null || model.get("literalUsernames", false)) return adapter(realm, exact);
        return adapter(realm, username != null && username.contains("@")
                ? findByEmail(username) : find("lower(u.usuario_id) = lower(?)", username, true));
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return adapter(realm, findByEmail(email));
    }

    private Account findByEmail(String email) {
        // A shared address must never select an arbitrary account for recovery.
        return find("lower(e.usuario_email_email) = lower(?)", email, true);
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        StorageId stored = new StorageId(id);
        if (!model.getId().equals(stored.getProviderId())) return null;
        return adapter(realm, find("u.usuario_id = ?", stored.getExternalId()));
    }

    private UserModel adapter(RealmModel realm, Account account) {
        if (account == null) return null;
        loaded.put(account.username(), account);
        return new AbstractUserAdapter(session, realm, model) {
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
            @Override public String getEmail() { return account.email(); }
            @Override public String getFirstAttribute(String name) {
                return UserModel.EMAIL.equals(name) ? getEmail() : super.getFirstAttribute(name);
            }
            @Override public List<String> getAttribute(String name) {
                if (!UserModel.EMAIL.equals(name)) return super.getAttribute(name);
                return getEmail() == null || getEmail().isBlank() ? List.of() : List.of(getEmail());
            }
            @Override public Map<String, List<String>> getAttributes() {
                Map<String, List<String>> attributes = new HashMap<>(super.getAttributes());
                if (!getAttribute(UserModel.EMAIL).isEmpty()) attributes.put(UserModel.EMAIL, getAttribute(UserModel.EMAIL));
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
            @Override public boolean isEnabled() { return account.enabled(); }
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

    private Account find(String condition, String value) {
        return find(condition, value, false);
    }

    private Account find(String condition, String value, boolean unique) {
        if (value == null || value.isBlank()) return null;
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(SELECT + " where " + condition + (unique ? " limit 2" : " limit 1"))) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                Account account = new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        "1".equals(rs.getString(5)), rs.getString(6), "UPDATE_PASSWORD".equals(rs.getString(7)));
                return unique && rs.next() ? null : account;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No fue posible consultar usuarios Gator", e);
        }
    }

    @Override public boolean supportsCredentialType(String type) { return PasswordCredentialModel.TYPE.equals(type); }
    @Override public boolean isConfiguredFor(RealmModel realm, UserModel user, String type) {
        return supportsCredentialType(type);
    }
    @Override public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || !(input instanceof UserCredentialModel credential)) return false;
        Account account = loaded.computeIfAbsent(user.getUsername(), key -> find("u.usuario_id = ?", key));
        return account != null && account.enabled()
                && GatorPassword.matches(credential.getValue(), account.salt(), account.iterations(), account.hash());
    }
    @Override public boolean updateCredential(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType()) || input.getChallengeResponse() == null
                || input.getChallengeResponse().isBlank()) return false;
        updatePassword(user.getUsername(), input.getChallengeResponse());
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

    private void updatePassword(String username, String password) {
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement sync = connection.prepareStatement(
                        "select app_fn_sync_identity_password(json_build_object('usuario', ?, 'password', ?)::text)");
                 PreparedStatement update = connection.prepareStatement(
                        "update app_usuarios set usuario_password = case "
                        + "when ? ~ '^[0-9A-Fa-f]{128}$' and usuario_recover_hash is not null and usuario_hash_loops::integer > 0 "
                        + "then app_fn_hash_password(?, usuario_recover_hash, usuario_hash_loops::integer) else ? end, "
                        + "usuario_hash_auth = null where usuario_id = ?")) {
                sync.setString(1, username);
                sync.setString(2, password);
                try (ResultSet response = sync.executeQuery()) {
                    if (!response.next()) throw new IllegalStateException("Missing password synchronization response");
                    requireSyncSuccess(response.getString(1));
                }
                update.setString(1, password);
                // A literal 128-hex password must not be mistaken for an imported legacy hash by the source trigger.
                update.setString(2, password);
                update.setString(3, password);
                update.setString(4, username);
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
                           boolean passwordChangeRequired) { }
}
