package gator.mail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

final class SignatureStore {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path directory;

    SignatureStore(Path directory) { this.directory = directory; }

    private Path file(String mailbox) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(mailbox.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".png");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    byte[] read(String mailbox) throws IOException {
        Path file = file(mailbox);
        if (Files.notExists(file)) return null;
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException("La firma guardada excede el límite");
            return data;
        }
    }

    void save(String mailbox, byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_BYTES)
            throw new IllegalArgumentException("Selecciona una firma PNG o JPG de hasta 2 MiB");
        byte[] png;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("El archivo no es una imagen PNG o JPG válida");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg"))
                    throw new IllegalArgumentException("La firma debe ser PNG o JPG");
                reader.setInput(input);
                if (reader.getWidth(0) > 2000 || reader.getHeight(0) > 1000)
                    throw new IllegalArgumentException("La firma debe medir como máximo 2000 × 1000 píxeles");
                var output = new ByteArrayOutputStream();
                var image = reader.read(0);
                double scale = Math.min(1, Math.min(600.0 / image.getWidth(), 300.0 / image.getHeight()));
                if (scale < 1) {
                    var resized = new java.awt.image.BufferedImage(Math.max(1, (int) (image.getWidth() * scale)),
                            Math.max(1, (int) (image.getHeight() * scale)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = resized.createGraphics();
                    try {
                        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(image, 0, 0, resized.getWidth(), resized.getHeight(), null);
                    } finally { graphics.dispose(); }
                    image = resized;
                }
                ImageIO.write(image, "png", output);
                png = output.toByteArray();
                if (png.length > MAX_BYTES) throw new IllegalArgumentException("Reduce la imagen: la firma supera 2 MiB");
            } catch (IOException invalid) {
                throw new IllegalArgumentException("No se pudo leer la imagen de la firma", invalid);
            } finally { reader.dispose(); }
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".signature-", ".png");
        try {
            Files.write(temporary, png);
            Files.move(temporary, file(mailbox), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    void remove(String mailbox) throws IOException { Files.deleteIfExists(file(mailbox)); }

    void append(String mailbox, List<ImapMailbox.Upload> uploads) throws IOException {
        byte[] data = read(mailbox);
        if (data == null) throw new IllegalArgumentException("La firma ya no está disponible. Recarga el editor.");
        long total = uploads.stream().mapToLong(upload -> upload.data().length).sum();
        if (uploads.size() >= 10 || total + data.length > 25 * 1024 * 1024)
            throw new IllegalArgumentException("La firma y los adjuntos no deben superar 10 archivos ni 25 MiB");
        uploads.add(new ImapMailbox.Upload("firma.png", "image/png", data, true));
    }
}
