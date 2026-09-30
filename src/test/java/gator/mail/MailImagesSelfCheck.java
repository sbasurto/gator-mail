package gator.mail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class MailImagesSelfCheck {
    public static void main(String[] args) throws Exception { run(); }

    static void run() throws Exception {
        for (String address : new String[] {"0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "192.168.1.1", "100.64.0.1", "192.0.0.8", "192.0.2.1", "198.19.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "::",
                "fc00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::808:808",
                "2002:0808:0808::1", "2001::1", "2001:db8::1", "3fff::1"}) {
            assert !MailImages.publicAddress(InetAddress.getByName(address)) : address;
        }
        assert MailImages.publicAddress(InetAddress.getByName("8.8.8.8"));
        assert MailImages.publicAddress(InetAddress.getByName("2606:4700:4700::1111"));
        for (String url : new String[] {"file:///etc/passwd", "ftp://example.org/a", "https://user:pass@example.org/a",
                "http://example.org:8080/a", "http://example.org\\@127.0.0.1/a", "http://example.org/a\r\nx:y",
                "http://localhost/a", "http://bad_host.org/a", "http://[fe80::1%25eth0]/a", "http://[::ffff:8.8.8.8]/a", "http://0177.0.0.1/a", "http://0x7f.0.0.1/a", "http://8.8.2056/a"}) {
            rejected(() -> MailImages.url(url));
        }
        assert MailImages.url("https://example.org/a.png?x=1&y=2").getHost().equals("example.org");
        assert MailImages.connectionTarget(InetAddress.getByName("177.0.0.1"), 80).equals("::177.0.0.1:80");
        assert MailImages.connectionTarget(InetAddress.getByName("2606:4700:4700::1111"), 443)
                .equals("::[2606:4700:4700:0:0:0:0:1111]:443");
        assert MailImages.redirect("302", "https://cdn.example.org/image.png", 2).getHost().equals("cdn.example.org");
        rejected(() -> MailImages.redirect("302", "https://cdn.example.org/image.png", 3));
        rejected(() -> MailImages.redirect("304", "https://cdn.example.org/image.png", 0));
        rejected(() -> MailImages.redirect("302", "file:///etc/passwd", 0));
        rejected(() -> MailImages.redirect("302", "https://user:pass@example.org/a", 0));
        rejected(() -> MailImages.publicAddresses(new InetAddress[0]));
        rejected(() -> MailImages.publicAddresses(new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")}));
        rejected(() -> MailImages.fetch("http://127.0.0.1/image.png"));
        var field = MailImages.class.getDeclaredField("DOWNLOADS");
        field.setAccessible(true);
        Semaphore slots = (Semaphore) field.get(null);
        slots.acquire(4);
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<Boolean> waiting = new FutureTask<>(() -> {
            started.countDown();
            rejected(() -> MailImages.fetch("http://127.0.0.1/image.png"));
            return true;
        });
        Thread worker = new Thread(waiting);
        try {
            worker.start();
            assert started.await(1, TimeUnit.SECONDS);
            Thread.sleep(50);
            assert !waiting.isDone() : "Concurrent images must wait for an available slot";
        } finally { slots.release(4); }
        assert waiting.get(3, TimeUnit.SECONDS);
        assert slots.availablePermits() == 4;
        Thread.currentThread().interrupt();
        try {
            rejected(() -> MailImages.fetch("http://127.0.0.1/image.png"));
            assert Thread.currentThread().isInterrupted();
            assert slots.availablePermits() == 4;
        } finally { Thread.interrupted(); }
        rejected(() -> MailImages.png("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8)));
        rejected(() -> MailImages.png(new byte[] {1, 2, 3}));
        rejected(() -> MailImages.png(new byte[MailImages.MAX_BYTES + 1]));
        for (String format : new String[] {"png", "jpeg", "gif"}) {
            BufferedImage original = new BufferedImage(17, 11, BufferedImage.TYPE_INT_RGB);
            original.setRGB(4, 4, 0xff339966);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assert ImageIO.write(original, format, encoded);
            encoded.write("<script>evil()</script>".getBytes(StandardCharsets.UTF_8));
            byte[] result = MailImages.png(encoded.toByteArray());
            assert result[0] == (byte) 0x89 && result[1] == 'P';
            assert !new String(result, StandardCharsets.ISO_8859_1).contains("<script>");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
            assert decoded.getWidth() == 17 && decoded.getHeight() == 11;
        }
        ByteArrayOutputStream large = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB), "png", large);
        rejected(() -> MailImages.png(large.toByteArray()));
        large.reset();
        ImageIO.write(new BufferedImage(3000, 3000, BufferedImage.TYPE_BYTE_GRAY), "png", large);
        rejected(() -> MailImages.png(large.toByteArray()));
    }

    private static void rejected(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Unsafe image input accepted");
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }
}
