package gator.mail.keycloak;

import org.keycloak.Config;
import org.keycloak.models.*;
import org.keycloak.services.resource.*;

public final class GatorIdentityAdminResourceFactory implements RealmResourceProviderFactory {
    @Override public String getId() { return "gator-identity-admin"; }
    @Override public RealmResourceProvider create(KeycloakSession session) {
        return new RealmResourceProvider() {
            @Override public Object getResource() { return new GatorIdentityAdminResource(session); }
            @Override public void close() { }
        };
    }
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
