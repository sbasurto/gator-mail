package gator.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

final class MailSentSelfCheck {
    static void run() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("owner@example.test"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse("Ana <ana@example.test>, otro@example.test"));
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse("equipo@example.test"));
        message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse("archivo@example.test"));
        message.setSubject("Propuesta");
        var summary = ImapMailbox.summary(7, message);
        assert summary.uid() == 7 && summary.from().equals("owner@example.test");
        assert summary.to().contains("Ana") && summary.to().contains("otro@example.test");
        assert summary.cc().equals("equipo@example.test");
        assert summary.bcc().equals("archivo@example.test");
        assert summary.correspondents(false).equals("owner@example.test");
        assert summary.contact(false).equals("owner@example.test");
        assert MailServlet.filterSender(summary.contact(true)).equals("ana@example.test");
        String outgoing = summary.correspondents(true);
        assert outgoing.startsWith("Para: Ana") && outgoing.contains("otro@example.test");
        assert outgoing.contains("CC: equipo@example.test") && outgoing.contains("CCO: archivo@example.test");
        assert !outgoing.contains("owner@example.test");
        message.setRecipients(Message.RecipientType.TO, new InternetAddress[0]);
        assert ImapMailbox.summary(8, message).contact(true).equals("equipo@example.test");
        message.setRecipients(Message.RecipientType.CC, new InternetAddress[0]);
        assert ImapMailbox.summary(8, message).contact(true).equals("archivo@example.test");
        message.setRecipients(Message.RecipientType.BCC, new InternetAddress[0]);
        assert ImapMailbox.summary(8, message).correspondents(true).equals("(Sin destinatarios)");
        System.out.println("Sent headers preserve To/CC/BCC and display recipients; Inbox preserves sender");
    }
}
