package gator.mail.keycloak;

import jakarta.ws.rs.core.MultivaluedMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.authentication.authenticators.util.AuthenticatorUtils;
import org.keycloak.events.Details;
import org.keycloak.models.UserModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.StorageId;

/** Opt-in form for databases whose account names are case-sensitive. */
public final class GatorExactUsernamePasswordForm extends UsernamePasswordForm {
    @Override
    public boolean validateUserAndPassword(AuthenticationFlowContext context, MultivaluedMap<String, String> input) {
        if (isUserAlreadySetBeforeUsernamePasswordAuth(context))
            return super.validateUserAndPassword(context, input);
        if (!GatorExactIdentity.enabled(context.getAuthenticationSession().getClient()))
            return super.validateUserAndPassword(context, input);
        context.clearUser();
        String username = input.getFirst("username");
        UserModel user = null;
        if (username != null && !username.isBlank()) {
            username = username.trim();
            context.getEvent().detail(Details.USERNAME, username);
            context.getAuthenticationSession().setAuthNote(ATTEMPTED_USERNAME, username);
            user = findExactUser(context.getSession(), context.getRealm(), username);
        }
        testInvalidUser(context, user);
        if (user == null) return false;
        // Failure accounting must retain this exact ID instead of resolving a lowercased username.
        context.setUser(user);
        if (!validatePassword(context, user, input, false) || !enabledUser(context, user))
            return false;
        AuthenticatorUtils.processRememberMe(context, input);
        context.setUser(user);
        return true;
    }

    static UserModel findExactUser(KeycloakSession session, RealmModel realm, String username) {
        var providers = realm.getComponentsStream(realm.getId(), UserStorageProvider.class.getName())
                .filter(model -> "gator-users".equals(model.getProviderId()))
                .toList();
        if (providers.size() != 1)
            throw new IllegalStateException("Exact username login requires one Gator identity source");
        // Keycloak's username cache lowercases names; the immutable storage ID preserves them.
        UserModel user = session.users().getUserById(realm, "f:" + providers.getFirst().getId() + ":" + username);
        if (user == null) {
            UserModel nativeUser = session.users().getUserByUsername(realm, username);
            if (nativeUser != null && StorageId.isLocalStorage(nativeUser)
                    && nativeUser.getFederationLink() == null && username.equals(nativeUser.getUsername()))
                user = nativeUser;
        }
        return user;
    }
}
