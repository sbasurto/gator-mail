package gator.mail.keycloak;

import org.keycloak.Config;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.credential.hash.PasswordHashProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public final class GatorLegacyPasswordHashProviderFactory implements PasswordHashProviderFactory {
    @Override public String getId() { return GatorLegacyPasswordHashProvider.ID; }
    @Override public PasswordHashProvider create(KeycloakSession session) { return new GatorLegacyPasswordHashProvider(); }
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) { }
    @Override public void close() { }
}
