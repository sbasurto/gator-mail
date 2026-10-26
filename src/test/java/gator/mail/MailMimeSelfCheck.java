package gator.mail;

import jakarta.activation.CommandMap;
import jakarta.activation.DataContentHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public final class MailMimeSelfCheck {
    public static void run() throws Exception {
        // Tomcat can discover mailcap resources in a different order from Gradle.
        for (String type : List.of("multipart/mixed", "text/plain", "text/html", "message/rfc822")) {
            for (var command : CommandMap.getDefaultCommandMap().getAllCommands(type)) {
                if ("content-handler".equals(command.getCommandName())) {
                    assert DataContentHandler.class.isAssignableFrom(Class.forName(command.getCommandClass()))
                            : "Incompatible MIME handler: " + command.getCommandClass();
                }
            }
        }
        String source = """
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary=outer

                --outer
                Content-Type: multipart/alternative; boundary=inner

                --inner
                Content-Type: text/plain; charset=UTF-8

                Mensaje de prueba
                --inner
                Content-Type: text/html; charset=UTF-8

                <p>Mensaje de prueba</p>
                --inner--
                --outer
                Content-Type: application/octet-stream
                Content-Disposition: attachment; filename=prueba.txt
                Content-Transfer-Encoding: base64

                YWRqdW50bw==
                --outer--
                """.replace("\n", "\r\n");
        var message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        var mixed = (MimeMultipart) message.getContent();
        assert mixed.getCount() == 2;
        var alternative = (MimeMultipart) mixed.getBodyPart(0).getContent();
        assert alternative.getBodyPart(0).getContent().toString().contains("Mensaje de prueba");
        assert alternative.getBodyPart(1).getContent().toString().contains("<p>Mensaje de prueba</p>");
        assert new String(mixed.getBodyPart(1).getInputStream().readAllBytes(), StandardCharsets.UTF_8).equals("adjunto");
        var output = new ByteArrayOutputStream();
        message.writeTo(output);
        assert output.size() > 0;
    }
}
