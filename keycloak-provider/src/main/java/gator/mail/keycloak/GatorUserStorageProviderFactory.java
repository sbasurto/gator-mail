package gator.mail.keycloak;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import java.util.List;

public final class GatorUserStorageProviderFactory
        implements UserStorageProviderFactory<GatorUserStorageProvider> {
    @Override public String getId() { return "gator-users"; }
    @Override public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("connectionEnvironmentPrefix", "Connection environment prefix",
                        "Prefix of JDBC_URL, JDBC_USER and JDBC_PASSWORD environment variables.",
                        ProviderConfigProperty.STRING_TYPE, "GATOR_IDP"),
                new ProviderConfigProperty("literalUsernames", "Exact database usernames",
                        "Requires the Gator exact username browser form in an isolated realm.",
                        ProviderConfigProperty.BOOLEAN_TYPE, false));
    }
    @Override public GatorUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new GatorUserStorageProvider(session, model);
    }
}
