package gator.mail.keycloak;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.resetcred.ResetCredentialChooseUser;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.UserModel;

/** Recovery uses the same case-sensitive identity selection as login. */
public final class GatorExactResetCredentialChooseUser extends ResetCredentialChooseUser {
    @Override public String getId() { return "gator-exact-reset-credentials-user"; }
    @Override public String getHelpText() { return "Exact identity recovery only for server client attribute gator.exactIdentity=true; otherwise standard Keycloak recovery."; }
    @Override public String getDisplayType() { return "Gator exact recovery user"; }
    @Override public Authenticator create(KeycloakSession session) { return this; }

    @Override public void action(AuthenticationFlowContext context) {
        if (!GatorExactIdentity.enabled(context.getAuthenticationSession().getClient())) {
            super.action(context);
            return;
        }
        String username = context.getHttpRequest().getDecodedFormParameters().getFirst("username");
        if (username == null || username.isBlank()) {
            super.action(context);
            return;
        }
        username = username.trim();
        UserModel user = GatorExactUsernamePasswordForm.findExactUser(context.getSession(), context.getRealm(), username);
        if (user == null && context.getRealm().isLoginWithEmailAllowed() && username.contains("@")) {
            try {
                user = context.getSession().users().getUserByEmail(context.getRealm(), username);
            } catch (ModelDuplicateException ambiguousEmail) {
                // Ambiguous addresses cannot identify a recovery account.
            }
        }
        context.getAuthenticationSession().setAuthNote("ATTEMPTED_USERNAME", username);
        context.getAuthenticationSession().removeAuthNote("RESET_CREDENTIAL_USER_CHOSEN");
        if (user == null) {
            context.getEvent().clone().detail(Details.USERNAME, username).error(Errors.USER_NOT_FOUND);
            context.clearUser();
        } else if (!user.isEnabled()) {
            context.getEvent().clone().detail(Details.USERNAME, username).user(user).error(Errors.USER_DISABLED);
            context.clearUser();
        } else {
            context.getAuthenticationSession().setAuthNote("RESET_CREDENTIAL_USER_CHOSEN", "true");
            context.setUser(user);
        }
        // Preserve Keycloak's indistinguishable response for unknown and disabled users.
        context.success();
    }
}
