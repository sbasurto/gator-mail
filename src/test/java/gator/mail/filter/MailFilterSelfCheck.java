package gator.mail.filter;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public final class MailFilterSelfCheck {
    public static void main(String[] args) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("facturas@example.com"));
        message.setRecipients(Message.RecipientType.TO, "usuario@example.com");
        message.setSubject("Factura julio");
        message.setHeader("X-Spam-Flag", "YES");
        message.setText("contenido");
        message.saveChanges();

        assert MailFilterService.matches(rule("FROM", "ENDS_WITH", "example.com", null), message);
        assert MailFilterService.matches(rule("FROM", "EQUALS", "facturas@example.com", null), message);
        assert MailFilterService.matches(rule("SUBJECT", "CONTAINS", "factura", null), message);
        assert MailFilterService.matches(rule("HEADER", "EQUALS", "yes", "X-Spam-Flag"), message);
        assert !MailFilterService.matches(rule("TO", "CONTAINS", "otro@example.com", null), message);
        assert "linea\\nnueva\\\"".equals(MailFilterService.json("linea\nnueva\""));

        Path secret = Files.createTempFile("gator-mail-filter", ".secret");
        try {
            Files.writeString(secret, "test-secret");
            MailFilterService.Config config = MailFilterService.Config.load(Map.of(
                    "GATOR_MAIL_FILTER_MASTER_SECRET_FILE", secret.toString(),
                    "GATOR_MAIL_FILTER_MASTER_USER", "test-master"));
            assert "jdbc:postgresql://127.0.0.1:6432/db_gatormail".equals(config.dbUrl());
            assert "127.0.0.1".equals(config.imapHost());
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    private static MailFilterService.Rule rule(String field, String operator, String value, String header) {
        return new MailFilterService.Rule(1, "Prueba", field, operator, header, value, "Archivo");
    }
}
