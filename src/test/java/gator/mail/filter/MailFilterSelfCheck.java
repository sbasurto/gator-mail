package gator.mail.filter;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
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
        assert MailFilterService.matches(rule("SUBJECT", "CONTAINS", "factura", null), message);
        assert MailFilterService.matches(rule("HEADER", "EQUALS", "yes", "X-Spam-Flag"), message);
        assert !MailFilterService.matches(rule("TO", "CONTAINS", "otro@example.com", null), message);
        assert "linea\\nnueva\\\"".equals(MailFilterService.json("linea\nnueva\""));
    }

    private static MailFilterService.Rule rule(String field, String operator, String value, String header) {
        return new MailFilterService.Rule(1, "Prueba", field, operator, header, value, "Archivo");
    }
}
