package gator.mail.keycloak;

import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;

/** Opt-in comes only from the server's authenticated client, never request parameters. */
final class GatorExactIdentity {
    private GatorExactIdentity() { }

    static boolean enabled(ClientModel client) {
        return client != null && "true".equals(client.getAttribute("gator.exactIdentity"));
    }

    static boolean enabled(KeycloakSession session) {
        if (session == null || session.getContext() == null) return false;
        var context = session.getContext();
        var authentication = context.getAuthenticationSession();
        ClientModel client = context.getClient();
        if (authentication != null) {
            ClientModel authenticated = authentication.getClient();
            if (authenticated == null || client != null && !authenticated.getId().equals(client.getId()))
                throw new IllegalStateException("Conflicting authenticated identity client context");
            client = authenticated;
        }
        return enabled(client);
    }
}
