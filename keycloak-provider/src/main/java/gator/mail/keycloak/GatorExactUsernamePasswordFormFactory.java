package gator.mail.keycloak;

import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.models.KeycloakSession;

public final class GatorExactUsernamePasswordFormFactory extends UsernamePasswordFormFactory {
    @Override public String getId() { return "gator-exact-username-password"; }
    @Override public String getDisplayType() { return "Gator exact username and password"; }
    @Override public Authenticator create(KeycloakSession session) { return new GatorExactUsernamePasswordForm(); }
}
