package gator.mail;

import java.util.ArrayList;
import java.util.List;

public final class MailHtmlSelfCheck {
    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        List<String> images = new ArrayList<>();
        String html = MailHtml.render("""
                <html><head><style>@import "https://bad.test/a.css";
                @media screen and (max-width:600px) { .column { width:100%!important; } }
                .banner { background: url('https://images.test/bg.png'); color:#123456; }
                </style><base href="https://bad.test/"><meta http-equiv="refresh" content="0;url=https://bad.test">
                <link rel="stylesheet" href="https://bad.test/style.css"></head>
                <body bgcolor="#eeeeee"><table width="640" cellpadding="0" cellspacing="0" role="presentation">
                <tr><td background="cid:tile" style="padding:20px;background-image:URL(https://images.test/tile.png)">
                <IMG SRC="https://images.test/logo.png?a=1&amp;b=2" srcset="https://bad.test/2x.png 2x" ONERROR="evil()">
                <script>secretEvil()</script><iframe src="https://bad.test/"></iframe>
                <form action="https://bad.test"><p>Keep this text</p><input name="password"><button>Submit</button></form>
                <a href="java&#115;cript:evil()" onclick="evil()">Bad</a>
                <a href="https://example.com/">Good</a></td></tr></table></body></html>
                """, source -> { images.add(source); return "/mail/image?i=" + images.size(); });
        assert html.contains("@media screen and (max-width:600px)") : html;
        assert html.contains("width:100%!important") && html.contains("color:#123456") : html;
        assert html.contains("width=\"640\"") && html.contains("cellpadding=\"0\"") : html;
        assert html.contains("bgcolor=\"#eeeeee\"") && html.contains("padding:20px") : html;
        assert html.contains("Keep this text") : html;
        assert !html.contains("bad.test") && !html.contains("evil()") && !html.contains("secretEvil") : html;
        assert !html.contains("<form") && !html.contains("<input") && !html.contains("<script") : html;
        assert !html.contains("srcset") && !html.contains("@import") : html;
        assert html.contains("target=\"_blank\"") && html.contains("rel=\"noreferrer noopener\"") : html;
        assert images.equals(List.of("https://images.test/bg.png", "cid:tile", "https://images.test/tile.png",
                "https://images.test/logo.png?a=1&b=2")) : images;
        assert html.contains("/mail/image?i=1") && html.contains("/mail/image?i&#61;4") : html;
        String escaped = MailHtml.render("<style>.a{background:u\\72l(https://bad.test)}</style>"
                + "<div style=\"background:image-set('https://bad.test/a.png' 1x)\">Text</div>", value -> "");
        assert !escaped.contains("bad.test") : escaped;
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        var message = new jakarta.mail.internet.MimeBodyPart();
        var multipart = new jakarta.mail.internet.MimeMultipart("related");
        var body = new jakarta.mail.internet.MimeBodyPart();
        body.setContent("<style>.banner{background:url(cid:logo)}</style><img src='CID:logo'>", "text/html");
        multipart.addBodyPart(body);
        var picture = new jakarta.mail.internet.MimeBodyPart();
        picture.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(png.toByteArray(), "image/png")));
        picture.setHeader("Content-ID", "<logo>");
        picture.setHeader("Content-Type", "image/png");
        multipart.addBodyPart(picture);
        message.setContent(multipart);
        message.setHeader("Content-Type", multipart.getContentType());
        body.setHeader("Content-Type", "text/html");
        var parse = ImapMailbox.class.getDeclaredMethod("parse", jakarta.mail.Part.class);
        parse.setAccessible(true);
        Object parsed = parse.invoke(null, message);
        var original = parsed.getClass().getDeclaredField("originalHtml");
        original.setAccessible(true);
        String resolved = (String) original.get(parsed);
        assert resolved.contains("<style>") && !resolved.toLowerCase().contains("cid:logo") : resolved;
        assert resolved.contains("src='data:image/png;base64,") : resolved;
        var inlineType = Class.forName("gator.mail.ImapMailbox$InlineImage");
        var constructor = inlineType.getDeclaredConstructor(String.class, String.class, byte[].class);
        constructor.setAccessible(true);
        var inline = ImapMailbox.class.getDeclaredMethod("inlineImages", String.class, List.class);
        inline.setAccessible(true);
        String repeated = "<img src='cid:logo'>".repeat(100);
        String limited = (String) inline.invoke(null, repeated,
                List.of(constructor.newInstance("logo", "image/png", new byte[1_000_000])));
        assert limited.length() < 20_000_000 + repeated.length() : limited.length();
        assert !limited.contains("cid:logo");
        String counted = (String) inline.invoke(null, repeated,
                List.of(constructor.newInstance("logo", "image/png", png.toByteArray())));
        assert counted.split("data:image/png", -1).length - 1 == 40;
    }

    static void writeFixture(java.nio.file.Path directory) throws Exception {
        var image = new java.awt.image.BufferedImage(32, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(32, 138, 118));
        graphics.fillRect(0, 0, 32, 16);
        graphics.dispose();
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        java.nio.file.Files.write(directory.resolve("safe-image.png"), MailImages.png(bytes.toByteArray()));
        String original = """
                <html lang="es"><head><meta http-equiv="refresh" content="0;url=https://blocked.test/">
                <style>
                body {margin:0;background:#eef2f5;font:16px/1.6 Arial;color:#243746}
                .newsletter {width:640px;max-width:100%;margin:0 auto;background:white;border-collapse:collapse}
                .banner {padding:36px;background:#164b63;color:white}
                .content {padding:28px} .columns td {width:50%;padding:12px;border:0}
                @media(max-width:500px) {.columns td {display:block;width:auto} .content {padding:16px}}
                </style></head><body><table class="newsletter" cellpadding="0" cellspacing="0" role="presentation">
                <tr><td class="banner"><h1 style="margin:0;font-size:28px">Un correo con su diseño original</h1>
                <p style="margin:8px 0 0">Colores, imágenes y distribución del remitente.</p></td></tr>
                <tr><td class="content"><img id="remote" alt="Imagen externa verificada" src="https://images.example/banner.png" width="128" height="64">
                <img id="embedded" alt="Imagen adjunta verificada" src="data:image/png;base64,EMBEDDED_IMAGE" width="64" height="32">
                <h2 style="font-size:22px;color:#164b63">Contenido completo</h2>
                <p>Las tablas conservan sus espacios, sin bordes añadidos por el lector.</p>
                <table class="columns" cellpadding="0" cellspacing="0"><tr><td><strong>Primera columna</strong><p>Texto del mensaje.</p></td>
                <td><strong>Segunda columna</strong><p>Se adapta al tamaño de la pantalla.</p></td></tr></table>
                <div id="background" style="height:32px;background-image:url('https://images.example/tile.png')"></div>
                <p><a href="https://example.com/details" onclick="window.evil=1">Ver detalles</a></p>
                <script>window.evil=1;parent.document.body.dataset.evil='yes';</script>
                <form action="https://blocked.test/send"><input name="secret"><button>Enviar</button></form>
                </td></tr></table></body></html>
                """.replace("EMBEDDED_IMAGE", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
        String rendered = MailHtml.render(original, source -> MailServlet.imageSource("/gator-mail", source));
        java.nio.file.Files.writeString(directory.resolve("html-content.html"), rendered);
        var headers = new java.util.HashMap<String, String>();
        MailServlet.prepareHtmlContent((jakarta.servlet.http.HttpServletResponse) java.lang.reflect.Proxy.newProxyInstance(
                MailHtmlSelfCheck.class.getClassLoader(), new Class<?>[]{jakarta.servlet.http.HttpServletResponse.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setHeader")) headers.put((String) args[0], (String) args[1]);
                    return null;
                }));
        java.nio.file.Files.writeString(directory.resolve("html-content-headers.json"), new com.google.gson.Gson().toJson(headers));
    }
}
