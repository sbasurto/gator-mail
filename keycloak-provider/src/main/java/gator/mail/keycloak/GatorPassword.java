package gator.mail.keycloak;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GatorPassword {
    private GatorPassword() { }

    static boolean matches(String password, String salt, int iterations, String expected) {
        if (password == null || salt == null || expected == null || iterations < 1) return false;
        byte[] value = password.getBytes(StandardCharsets.UTF_8);
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(saltBytes);
            value = digest.digest(value);
            for (int i = 1; i < iterations; i++) value = digest.digest(value);
            return MessageDigest.isEqual(HexFormat.of().formatHex(value).getBytes(StandardCharsets.US_ASCII),
                    expected.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
