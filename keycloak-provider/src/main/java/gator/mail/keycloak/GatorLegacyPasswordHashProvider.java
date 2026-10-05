package gator.mail.keycloak;

import java.nio.charset.StandardCharsets;
import org.keycloak.credential.hash.PasswordHashProvider;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.credential.PasswordCredentialModel;

/** Read-only compatibility for explicitly imported Gator credentials. */
public final class GatorLegacyPasswordHashProvider implements PasswordHashProvider {
    public static final String ID = "gator-legacy-sha512";

    @Override public boolean verify(String rawPassword, PasswordCredentialModel credential) {
        if (credential == null || credential.getPasswordCredentialData() == null
                || credential.getPasswordSecretData() == null) return false;
        var data = credential.getPasswordCredentialData();
        var secret = credential.getPasswordSecretData();
        if (!ID.equals(data.getAlgorithm()) || secret.getSalt() == null || secret.getValue() == null
                || !secret.getValue().matches("[a-fA-F0-9]{128}")
                || data.getHashIterations() < 1 || data.getHashIterations() > 1_000_000) return false;
        return GatorPassword.matches(rawPassword, new String(secret.getSalt(), StandardCharsets.UTF_8),
                data.getHashIterations(), secret.getValue());
    }

    @Override public boolean policyCheck(PasswordPolicy policy, PasswordCredentialModel credential) { return false; }

    @Override public PasswordCredentialModel encodedCredential(String password, int iterations) {
        throw new UnsupportedOperationException("Legacy Gator hashes are import-only; use the realm's current password policy");
    }

    @Override public void close() { }
}
