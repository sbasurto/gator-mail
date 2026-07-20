package gator.mail.keycloak;

public final class GatorPasswordSelfCheck {
    public static void main(String[] args) {
        String expected = "491e4d73982f537e782fba613b38b28eb5e2adeaa56ba3749920c8a28393959b1ca8b9ea93a2982e95826d936bcdb4efd4b3951a86e81d8fe9e28f5900af42b7";
        assert GatorPassword.matches("GatorTest21", "test-salt", 3, expected);
        assert !GatorPassword.matches("incorrecta", "test-salt", 3, expected);
    }
}
