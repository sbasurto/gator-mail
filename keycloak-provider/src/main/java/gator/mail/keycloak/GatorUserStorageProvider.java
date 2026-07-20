package gator.mail.keycloak;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputUpdater;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.credential.UserCredentialManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.StorageId;
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
        return adapter(realm, username != null && username.contains("@")
                ? findByEmail(username) : find("lower(u.usuario_id) = lower(?)", username));
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        return adapter(realm, findByEmail(email));
    }

    private Account findByEmail(String email) {
        Account account = find("lower(e.usuario_email_email) = lower(?) and position('@' in u.usuario_id) = 0", email);
        return account != null ? account : find("lower(e.usuario_email_email) = lower(?)", email);
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        return getUserByUsername(realm, new StorageId(id).getExternalId());
    }

    private UserModel adapter(RealmModel realm, Account account) {
        if (account == null) return null;
        loaded.put(account.username(), account);
        return new AbstractUserAdapter(session, realm, model) {
            @Override public String getUsername() { return account.username(); }
            @Override public String getEmail() { return account.email(); }
            @Override public boolean isEnabled() { return account.enabled(); }
            @Override public SubjectCredentialManager credentialManager() {
                return new UserCredentialManager(session, realm, this);
            }
            @Override public Stream<String> getRequiredActionsStream() {
                return account.passwordChangeRequired()
                        ? Stream.of(UserModel.RequiredAction.UPDATE_PASSWORD.name()) : Stream.empty();
            }
            @Override public void removeRequiredAction(String action) {
                if (UserModel.RequiredAction.UPDATE_PASSWORD.name().equals(action)) clearPasswordChange(account.username());
            }
        };
    }

    private Account find(String condition, String value) {
        if (value == null || value.isBlank()) return null;
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement(SELECT + " where " + condition + " limit 1")) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        "1".equals(rs.getString(5)), rs.getString(6), "UPDATE_PASSWORD".equals(rs.getString(7))) : null;
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
        Account account = loaded.computeIfAbsent(user.getUsername(), key -> find("lower(u.usuario_id) = lower(?)", key));
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

    private void updatePassword(String username, String password) {
        try (Connection connection = DriverManager.getConnection(required("GATOR_IDP_JDBC_URL"),
                required("GATOR_IDP_JDBC_USER"), required("GATOR_IDP_JDBC_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (PreparedStatement sync = connection.prepareStatement(
                        "select app_fn_admon_tablas_all(json_build_object('usuario', ?, 'password', ?)::text)");
                 PreparedStatement update = connection.prepareStatement(
                        "update app_usuarios set usuario_password = ?, usuario_hash_auth = null where usuario_id = ?")) {
                sync.setString(1, username);
                sync.setString(2, password);
                sync.executeQuery();
                update.setString(1, password);
                update.setString(2, username);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Usuario Gator no encontrado");
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No fue posible sincronizar la contraseña Gator", e);
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Falta configurar " + name);
        return value;
    }

    private record Account(String username, String hash, String salt, int iterations, boolean enabled, String email,
                           boolean passwordChangeRequired) { }
}
