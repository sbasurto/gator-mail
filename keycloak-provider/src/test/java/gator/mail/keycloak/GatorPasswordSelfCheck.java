package gator.mail.keycloak;

public final class GatorPasswordSelfCheck {
    public static void main(String[] args) {
        String expected = "491e4d73982f537e782fba613b38b28eb5e2adeaa56ba3749920c8a28393959b1ca8b9ea93a2982e95826d936bcdb4efd4b3951a86e81d8fe9e28f5900af42b7";
        assert GatorPassword.matches("GatorTest21", "test-salt", 3, expected);
        assert !GatorPassword.matches("incorrecta", "test-salt", 3, expected);
        var provider = new GatorLegacyPasswordHashProvider();
        var credential = org.keycloak.models.credential.PasswordCredentialModel.createFromValues(
                "gator-legacy-sha512", "test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8), 3, expected);
        assert provider.verify("GatorTest21", credential) : "Imported credential must preserve the existing password";
        assert !provider.verify("incorrecta", credential);
        assert !provider.policyCheck(null, credential) : "Legacy algorithm must not become the current policy";
        assert !provider.verify("GatorTest21", org.keycloak.models.credential.PasswordCredentialModel.createFromValues(
                "other", "test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8), 3, expected));
        assert !provider.verify("GatorTest21", org.keycloak.models.credential.PasswordCredentialModel.createFromValues(
                "gator-legacy-sha512", "test-salt".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, expected));
        try {
            provider.encodedCredential("GatorTest21", 3);
            throw new AssertionError("New passwords must use Keycloak's current algorithm");
        } catch (UnsupportedOperationException expectedError) { }

    }
}
