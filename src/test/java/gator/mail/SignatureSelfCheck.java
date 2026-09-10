package gator.mail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import javax.imageio.ImageIO;

final class SignatureSelfCheck {
    static void run() {
        try {
            var directory = Files.createTempDirectory("mail-signature-test");
            var store = new SignatureStore(directory);
            var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            byte[] png = output.toByteArray();
            assert store.read("first@example.com") == null;
            store.save("first@example.com", png);
            assert store.read("FIRST@example.com") != null;
            assert store.read("second@example.com") == null;
            assert new SignatureStore(directory).read("first@example.com") != null;
            var uploads = new java.util.ArrayList<ImapMailbox.Upload>();
            store.append("first@example.com", uploads);
            assert uploads.size() == 1 && uploads.getFirst().inline();
            assert uploads.getFirst().type().equals("image/png");
            var buildMessage = ImapMailbox.class.getDeclaredMethod("message", jakarta.mail.Session.class,
                    String.class, String.class, String.class, String.class, String.class, String.class,
                    String.class, java.util.List.class);
            buildMessage.setAccessible(true);
            var message = (jakarta.mail.internet.MimeMessage) buildMessage.invoke(null,
                    jakarta.mail.Session.getInstance(new java.util.Properties()), "first@example.com",
                    "recipient@example.com", "", "", "Prueba", "Hola", "<p>Hola</p>", uploads);
            var related = (jakarta.mail.Multipart) message.getContent();
            assert related.getCount() == 2;
            assert related.getBodyPart(1).getDisposition().equals(jakarta.mail.Part.INLINE);
            var alternative = (jakarta.mail.Multipart) related.getBodyPart(0).getContent();
            assert alternative.getBodyPart(1).getContent().toString().contains("cid:inline-1@gator-mail");
            assert related.getBodyPart(1).getFileName().equals("firma.png");
            byte[] before = store.read("first@example.com");
            for (byte[] invalid : java.util.List.of(new byte[0], "<svg onload='alert(1)'/>".getBytes(),
                    new byte[SignatureStore.MAX_BYTES + 1])) {
                try { store.save("first@example.com", invalid); throw new AssertionError("Aceptó firma inválida"); }
                catch (IllegalArgumentException expected) { }
                assert java.util.Arrays.equals(before, store.read("first@example.com"));
            }
            output.reset();
            ImageIO.write(new BufferedImage(2001, 1, BufferedImage.TYPE_INT_RGB), "png", output);
            try { store.save("first@example.com", output.toByteArray()); throw new AssertionError("Aceptó ancho excesivo"); }
            catch (IllegalArgumentException expected) { }
            output.reset();
            ImageIO.write(image, "jpeg", output);
            store.save("first@example.com", output.toByteArray());
            assert ImageIO.read(new java.io.ByteArrayInputStream(store.read("first@example.com"))) != null;
            output.reset();
            ImageIO.write(new BufferedImage(1200, 400, BufferedImage.TYPE_INT_RGB), "png", output);
            store.save("first@example.com", output.toByteArray());
            assert ImageIO.read(new java.io.ByteArrayInputStream(store.read("first@example.com"))).getWidth() == 600;
            store.remove("first@example.com");
            assert store.read("first@example.com") == null;
            store.remove("first@example.com");
            try (var files = Files.list(directory)) { assert files.findAny().isEmpty(); }
            Files.delete(directory);
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
