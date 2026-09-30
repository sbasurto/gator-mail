package gator.mail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

final class MailImages {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Semaphore DOWNLOADS = new Semaphore(4);
    // Native DNS resolution may ignore interruption; keep its thread count bounded as well.
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "mail-image-dns");
                thread.setDaemon(true);
                return thread;
            });

    private MailImages() { }

    static byte[] fetch(String value) throws IOException {
        URI uri = url(value);
        Process process = null;
        Path metadata = null;
        boolean acquired = false;
        try {
            // Browsers request several images together; queue briefly instead of dropping the excess.
            acquired = DOWNLOADS.tryAcquire(8, TimeUnit.SECONDS);
            if (!acquired) throw new IOException("Demasiadas imágenes en proceso");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            metadata = Files.createTempFile("mail-image-", ".status");
            for (int redirects = 0; ; redirects++) {
                String host = uri.getHost();
                if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
                String lookup = host;
                var resolution = DNS.submit(() -> InetAddress.getAllByName(lookup));
                InetAddress[] addresses;
                try { addresses = resolution.get(Math.min(2000, remaining(deadline)), TimeUnit.MILLISECONDS); }
                catch (ExecutionException | TimeoutException failure) {
                    throw new IOException("No se pudo resolver la imagen", failure);
                } finally { resolution.cancel(true); }
                InetAddress address = publicAddresses(addresses);
                int port = uri.getPort() == -1 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
                process = new ProcessBuilder("/usr/bin/curl", "--disable", "--silent", "--fail", "--globoff",
                        "--noproxy", "*", "--proto", "=http,https", "--connect-timeout", "3", "--max-time",
                        Double.toString(Math.min(6000, remaining(deadline)) / 1000.0),
                        "--max-filesize", Integer.toString(MAX_BYTES), "--connect-to",
                        connectionTarget(address, port),
                        "--write-out", "%{stderr}%{http_code}\n%{redirect_url}", "--url", uri.toASCIIString())
                        .redirectError(metadata.toFile()).start();
                byte[] response;
                try (var stream = process.getInputStream()) { response = stream.readNBytes(MAX_BYTES + 1); }
                if (response.length > MAX_BYTES) throw new IOException("Imagen demasiado grande");
                if (!process.waitFor(Math.min(1000, remaining(deadline)), TimeUnit.MILLISECONDS) || process.exitValue() != 0)
                    throw new IOException("No se pudo descargar la imagen");
                process = null;
                byte[] statusBytes;
                try (var stream = Files.newInputStream(metadata)) { statusBytes = stream.readNBytes(8197); }
                String status = new String(statusBytes, StandardCharsets.UTF_8);
                if (statusBytes.length > 8196 || status.length() < 4 || status.charAt(3) != '\n')
                    throw new IOException("Respuesta de imagen inválida");
                if (status.substring(0, 3).equals("200")) return png(response);
                uri = redirect(status.substring(0, 3), status.substring(4), redirects);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Descarga de imagen interrumpida", interrupted);
        } catch (RejectedExecutionException busy) {
            throw new IOException("Resolución de imágenes ocupada", busy);
        } finally {
            if (process != null) process.destroyForcibly();
            if (acquired) DOWNLOADS.release();
            if (metadata != null) Files.deleteIfExists(metadata);
        }
    }

    static String connectionTarget(InetAddress address, int port) {
        String target = address.getHostAddress();
        if (target.contains(":")) target = "[" + target + "]";
        // Match every source host/port, including curl's normalization of numeric host literals.
        return "::" + target + ":" + port;
    }

    private static long remaining(long deadline) throws IOException {
        long milliseconds = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (milliseconds <= 0) throw new IOException("Tiempo de descarga de imagen agotado");
        return milliseconds;
    }

    static URI redirect(String status, String target, int redirects) throws IOException {
        if (redirects >= 3 || !(status.equals("301") || status.equals("302") || status.equals("303")
                || status.equals("307") || status.equals("308")))
            throw new IOException("Redirección de imagen no permitida");
        return url(target);
    }

    static URI url(String value) throws IOException {
        try {
            if (value == null || value.length() > 8192) throw new URISyntaxException("", "URL inválida");
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getRawUserInfo() != null || host == null || host.contains("%")
                    || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
                    || uri.getRawFragment() != null)
                throw new URISyntaxException(value, "URL de imagen no permitida");
            if (!host.startsWith("[") && (host.length() > 253 || !host.contains(".")
                    || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || host.contains("..")))
                throw new URISyntaxException(value, "Host de imagen inválido");
            if (host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) throw new IOException("Dirección IPv4 no canónica");
                for (String octet : octets)
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255)
                        throw new IOException("Dirección IPv4 no canónica");
            }
            if (host.startsWith("[")) {
                InetAddress literal = InetAddress.getByName(host.substring(1, host.length() - 1));
                if (literal.getAddress().length != 16 || !publicAddress(literal))
                    throw new IOException("Dirección IPv6 de imagen no permitida");
            }
            return uri;
        } catch (URISyntaxException invalid) { throw new IOException("URL de imagen inválida", invalid); }
    }

    static InetAddress publicAddresses(InetAddress[] addresses) throws IOException {
        if (addresses.length == 0) throw new IOException("La imagen no tiene dirección pública");
        for (InetAddress address : addresses)
            if (!publicAddress(address)) throw new IOException("Dirección de imagen no permitida");
        return addresses[0];
    }

    static boolean publicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255;
        int b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113));
        }
        // Only ordinary global unicast; exclude special, translated, tunnel and documentation ranges.
        return (a & 0xe0) == 0x20 && !(a == 0x20 && (b == 0x02
                || (b == 0x01 && ((bytes[2] & 255) < 2 || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))))
                && !(a == 0x3f && b == 0xff && (bytes[2] & 0xf0) == 0);
    }

    static byte[] png(byte[] source) throws IOException {
        if (source == null || source.length == 0 || source.length > MAX_BYTES)
            throw new IOException("Tamaño de imagen no permitido");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Imagen inválida");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("gif"))
                    throw new IOException("Formato de imagen no permitido");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 8_000_000)
                    throw new IOException("Dimensiones de imagen no permitidas");
                // ponytail: animated GIFs use their first frame; preserve animation only if required.
                BufferedImage image = reader.read(0);
                var bytes = new ByteArrayOutputStream();
                try (var output = new MemoryCacheImageOutputStream(bytes)) {
                    if (!ImageIO.write(image, "png", output)) throw new IOException("No se pudo reconstruir la imagen");
                }
                if (bytes.size() > MAX_BYTES) throw new IOException("Imagen reconstruida demasiado grande");
                return bytes.toByteArray();
            } finally { reader.dispose(); }
        } catch (RuntimeException invalid) { throw new IOException("Imagen inválida", invalid); }
    }
}
