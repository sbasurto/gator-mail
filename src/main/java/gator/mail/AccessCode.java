package gator.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class AccessCode {
    private AccessCode() {
    }

    static boolean matches(String value, String expectedHash) {
        if (value == null || expectedHash == null || !value.matches("[A-Za-z0-9]{8,12}")) {
            return false;
        }
        return MessageDigest.isEqual(hash(value.toUpperCase()).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
