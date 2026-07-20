package gator.mail.keycloak;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;

public final class GatorUserStorageProviderFactory
        implements UserStorageProviderFactory<GatorUserStorageProvider> {
    @Override public String getId() { return "gator-users"; }
    @Override public GatorUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new GatorUserStorageProvider(session, model);
    }
}
