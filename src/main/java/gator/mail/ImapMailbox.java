package gator.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;

final class ImapMailbox {
    private static final Logger LOG = Logger.getLogger(ImapMailbox.class.getName());
    private static final PolicyFactory HTML = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS)
            .and(Sanitizers.TABLES).and(Sanitizers.LINKS).and(new HtmlPolicyBuilder()
                    .allowElements("img").allowAttributes("src", "alt", "title").onElements("img")
                    .allowUrlProtocols("cid").toFactory());
    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    record FolderInfo(String name, String label, String parent, String root, int depth, int unread, int total) {
    }

    record Summary(long uid, String from, String subject, Instant sent) {
    }

    record MessagePage(List<Summary> messages, int total, int page, int size) {
    }

    record Attachment(String part, String name, String type, long size) { }

    record Upload(String name, String type, byte[] data, boolean inline) { }

    record Download(String name, byte[] data) { }

    record Mail(String from, String to, String subject, Instant sent, String body, boolean html,
            List<Attachment> attachments) {
    }

    private record InlineImage(String cid, String type, byte[] data) { }

    private static final class Parsed {
        String html = "";
        String plain = "";
        final List<Attachment> attachments = new ArrayList<>();
        final List<InlineImage> images = new ArrayList<>();
    }

    private final String host = env("GATOR_MAIL_IMAP_HOST", "mail.soft-gator.com");
    private final int port = Integer.parseInt(env("GATOR_MAIL_IMAP_PORT", "993"));
    private final String smtpHost = env("GATOR_MAIL_SMTP_HOST", "mail.soft-gator.com");
    private final int smtpPort = Integer.parseInt(env("GATOR_MAIL_SMTP_PORT", "465"));

    List<FolderInfo> folders(String mailbox, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken)) {
            List<FolderInfo> result = new ArrayList<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                String name = folder.getFullName();
                char separator = folder.getSeparator();
                result.add(new FolderInfo(name, label(part(name, separator)), parent(name, separator),
                        root(name, separator),
                        depth(name, separator),
                        Math.max(0, folder.getUnreadMessageCount()), Math.max(0, folder.getMessageCount())));
            }
            result.sort(Comparator.comparingInt((FolderInfo folder) -> priority(folder.name()))
                    .thenComparing(FolderInfo::label, String.CASE_INSENSITIVE_ORDER));
            return result;
        }
    }

    MessagePage messages(String mailbox, String folderName, String query, int requestedPage, int size,
            String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken); Folder folder = folder(store, folderName)) {
            folder.open(Folder.READ_ONLY);
            Message[] messages;
            int total;
            int page;
            if (query == null || query.isBlank()) {
                total = folder.getMessageCount();
                page = Math.min(requestedPage, Math.max(1, (total + size - 1) / size));
                int end = total - (page - 1) * size;
                messages = end < 1 ? new Message[0] : folder.getMessages(Math.max(1, end - size + 1), end);
            } else {
                SearchTerm search = new OrTerm(new SearchTerm[]{new FromStringTerm(query), new SubjectTerm(query),
                        new RecipientStringTerm(Message.RecipientType.TO, query), new BodyTerm(query)});
                Message[] matches = folder.search(search);
                total = matches.length;
                page = Math.min(requestedPage, Math.max(1, (total + size - 1) / size));
                int end = total - (page - 1) * size;
                messages = Arrays.copyOfRange(matches, Math.max(0, end - size), Math.max(0, end));
            }
            UIDFolder uidFolder = (UIDFolder) folder;
            List<Summary> result = new ArrayList<>(messages.length);
            for (int i = messages.length - 1; i >= 0; i--) {
                Message message = messages[i];
                result.add(new Summary(uidFolder.getUID(message), addresses(message.getFrom()),
                        text(message.getSubject(), "(Sin asunto)"),
                        message.getSentDate() == null ? Instant.EPOCH : message.getSentDate().toInstant()));
            }
            return new MessagePage(result, total, page, size);
        }
    }

    Mail read(String mailbox, String folderName, long uid, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken); Folder folder = folder(store, folderName)) {
            folder.open(Folder.READ_ONLY);
            Message message = ((UIDFolder) folder).getMessageByUID(uid);
            if (message == null) return null;
            Parsed parsed = parse(message);
            String body = parsed.html.isBlank() ? parsed.plain : inlineImages(parsed.html, parsed.images);
            return new Mail(addresses(message.getFrom()), addresses(message.getRecipients(Message.RecipientType.TO)),
                    text(message.getSubject(), "(Sin asunto)"),
                    message.getSentDate() == null ? Instant.EPOCH : message.getSentDate().toInstant(),
                    body, !parsed.html.isBlank(), List.copyOf(parsed.attachments));
        }
    }

    Download download(String mailbox, String folderName, long uid, String path, String accessToken) throws Exception {
        if (path == null || !path.matches("[0-9]+(?:\\.[0-9]+)*"))
            throw new IllegalArgumentException("Adjunto inválido");
        try (Store store = connect(mailbox, accessToken); Folder folder = folder(store, folderName)) {
            folder.open(Folder.READ_ONLY);
            Message message = ((UIDFolder) folder).getMessageByUID(uid);
            if (message == null) throw new IllegalArgumentException("Mensaje inexistente");
            jakarta.mail.Part part = part(message, path);
            if (part.getFileName() == null && !jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()))
                throw new IllegalArgumentException("Adjunto inválido");
            byte[] data = part.getInputStream().readNBytes(MAX_FILE_BYTES + 1);
            if (data.length > MAX_FILE_BYTES) throw new IllegalArgumentException("El adjunto excede 25 MB");
            return new Download(fileName(part), data);
        }
    }

    String rename(String mailbox, String folderName, String newName, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken)) {
            Folder source = mutableFolder(store, folderName);
            String destination = destination(folderName, parent(folderName, source.getSeparator()), newName,
                    source.getSeparator());
            rename(source, store.getFolder(destination));
            return destination;
        }
    }

    String move(String mailbox, String folderName, String parentName, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken)) {
            Folder source = mutableFolder(store, folderName);
            char separator = source.getSeparator();
            String parent = parentName == null ? "" : parentName.strip();
            if (!parent.isEmpty()) {
                Folder target = store.getFolder(parent);
                if (!target.exists()) throw new IllegalArgumentException("La carpeta destino no existe");
            }
            String destination = destination(folderName, parent, part(folderName, separator), separator);
            rename(source, store.getFolder(destination));
            return destination;
        }
    }

    void delete(String mailbox, String folderName, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken)) {
            if (!mutableFolder(store, folderName).delete(true))
                throw new IllegalStateException("El servidor IMAP rechazó la eliminación");
        }
    }

    void moveMessage(String mailbox, String sourceName, String destinationName, long uid, String accessToken)
            throws Exception {
        if (uid <= 0 || sourceName == null || destinationName == null || sourceName.equals(destinationName))
            throw new IllegalArgumentException("El mensaje o la carpeta destino no son válidos");
        try (Store store = connect(mailbox, accessToken)) {
            Folder source = folder(store, sourceName);
            Folder destination = folder(store, destinationName);
            source.open(Folder.READ_WRITE);
            try {
                Message message = ((UIDFolder) source).getMessageByUID(uid);
                if (message == null) return;
                ((IMAPFolder) source).moveMessages(new Message[]{message}, destination);
            } finally {
                if (source.isOpen()) source.close(false);
            }
        }
    }

    void deleteMessages(String mailbox, String folderName, long[] uids, String accessToken) throws Exception {
        if (uids == null || uids.length == 0 || uids.length > 100 || Arrays.stream(uids).anyMatch(uid -> uid <= 0))
            throw new IllegalArgumentException("Selecciona entre 1 y 100 mensajes");
        try (Store store = connect(mailbox, accessToken)) {
            Folder source = folder(store, folderName);
            Folder trash = trash(store);
            source.open(Folder.READ_WRITE);
            boolean expunge = source.getFullName().equalsIgnoreCase(trash.getFullName());
            try {
                Message[] messages = Arrays.stream(((UIDFolder) source).getMessagesByUID(uids))
                        .filter(message -> message != null).toArray(Message[]::new);
                if (messages.length == 0) return;
                if (expunge) source.setFlags(messages, new Flags(Flags.Flag.DELETED), true);
                else ((IMAPFolder) source).moveMessages(messages, trash);
            } finally {
                if (source.isOpen()) source.close(expunge);
            }
        }
    }

    String saveDraft(String mailbox, String recipients, String cc, String bcc, String subject, String markdown,
            String html, List<Upload> uploads, String accessToken) throws Exception {
        MimeMessage message = message(Session.getInstance(new Properties()), mailbox, recipients, cc, bcc, subject,
                markdown, html, uploads);
        message.setFlag(Flags.Flag.DRAFT, true);
        message.saveChanges();

        try (Store store = connect(mailbox, accessToken)) {
            Folder drafts = drafts(store);
            drafts.appendMessages(new Message[]{message});
            return drafts.getFullName();
        }
    }

    String send(String mailbox, String recipients, String cc, String bcc, String subject, String markdown,
            String html, List<Upload> uploads, String accessToken) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", smtpHost);
        properties.setProperty("mail.smtp.port", String.valueOf(smtpPort));
        properties.setProperty("mail.smtp.ssl.enable", "true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.auth", "false");
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "15000");
        properties.setProperty("mail.smtp.writetimeout", "15000");
        MimeMessage message = message(Session.getInstance(properties), mailbox, recipients, cc, bcc, subject,
                markdown, html, uploads);
        Transport.send(message);
        try (Store store = connect(mailbox, accessToken)) {
            Folder sent = sent(store);
            message.setFlag(Flags.Flag.SEEN, true);
            sent.appendMessages(new Message[]{message});
            return sent.getFullName();
        } catch (Exception error) {
            LOG.log(Level.WARNING, "El mensaje se envió, pero no pudo guardarse en Enviados", error);
            return "INBOX";
        }
    }

    private static MimeMessage message(Session session, String mailbox, String recipients, String cc, String bcc,
            String subject, String markdown, String html, List<Upload> uploads) throws Exception {
        if (subject == null || subject.length() > 200 || subject.chars().anyMatch(Character::isISOControl)
                || markdown == null || markdown.isBlank() || markdown.length() > 200_000)
            throw new IllegalArgumentException("El asunto o el contenido no son válidos");
        InternetAddress[] to = addresses(recipients, true);
        InternetAddress[] copies = addresses(cc, false);
        InternetAddress[] hiddenCopies = addresses(bcc, false);
        if (to.length == 0) throw new IllegalArgumentException("Agrega al menos un destinatario");
        if (to.length + copies.length + hiddenCopies.length > 100)
            throw new IllegalArgumentException("No se permiten más de 100 destinatarios");

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(mailbox));
        message.setRecipients(Message.RecipientType.TO, to);
        if (copies.length > 0) message.setRecipients(Message.RecipientType.CC, copies);
        if (hiddenCopies.length > 0) message.setRecipients(Message.RecipientType.BCC, hiddenCopies);
        message.setSubject(subject.strip(), "UTF-8");
        message.setSentDate(new Date());
        message.setHeader("X-Gator-Format", "markdown");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(markdown, "UTF-8");
        List<Upload> files = uploads == null ? List.of() : uploads;
        StringBuilder richBody = new StringBuilder(html);
        List<String> inlineCids = new ArrayList<>();
        for (Upload upload : files) if (upload.inline()) {
            String cid = UUID.randomUUID() + "@gator-mail";
            inlineCids.add(cid);
            richBody.append("<p><img src=\"cid:").append(cid).append("\" alt=\"")
                    .append(escape(upload.name())).append("\"></p>");
        }
        MimeBodyPart rich = new MimeBodyPart();
        rich.setContent(richBody.toString(), "text/html; charset=UTF-8");
        MimeMultipart content = new MimeMultipart("alternative");
        content.addBodyPart(plain);
        content.addBodyPart(rich);
        MimeBodyPart body = new MimeBodyPart();
        body.setContent(content);
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(body);
        int inlineIndex = 0;
        for (Upload upload : files) if (upload.inline()) {
            MimeBodyPart image = upload(upload);
            image.setDisposition(jakarta.mail.Part.INLINE);
            image.setHeader("Content-ID", "<" + inlineCids.get(inlineIndex++) + ">");
            related.addBodyPart(image);
        }
        MimeBodyPart relatedBody = new MimeBodyPart();
        relatedBody.setContent(related);
        if (files.stream().anyMatch(upload -> !upload.inline())) {
            MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(relatedBody);
            for (Upload upload : files) if (!upload.inline()) mixed.addBodyPart(upload(upload));
            message.setContent(mixed);
        } else if (inlineIndex > 0) message.setContent(related);
        else message.setContent(content);
        message.saveChanges();
        return message;
    }

    private static InternetAddress[] addresses(String value, boolean required) {
        if (value == null || value.isBlank()) return new InternetAddress[0];
        try {
            return InternetAddress.parse(value, true);
        } catch (Exception error) {
            throw new IllegalArgumentException(required ? "Los destinatarios no son válidos"
                    : "Las direcciones CC o CCO no son válidas");
        }
    }

    private static Folder drafts(Store store) throws Exception {
        for (Folder folder : store.getDefaultFolder().list("*"))
            if (label(part(folder.getFullName(), folder.getSeparator())).equals("Borradores")) return folder;
        Folder drafts = store.getFolder("Drafts");
        if (!drafts.exists() && !drafts.create(Folder.HOLDS_MESSAGES))
            throw new IllegalStateException("No fue posible crear la carpeta Borradores");
        return drafts;
    }

    private static Folder sent(Store store) throws Exception {
        for (Folder folder : store.getDefaultFolder().list("*"))
            if (label(part(folder.getFullName(), folder.getSeparator())).equals("Enviados")) return folder;
        Folder sent = store.getFolder("Sent");
        if (!sent.exists() && !sent.create(Folder.HOLDS_MESSAGES))
            throw new IllegalStateException("No fue posible crear la carpeta Enviados");
        return sent;
    }

    private static Folder trash(Store store) throws Exception {
        for (Folder folder : store.getDefaultFolder().list("*"))
            if (label(part(folder.getFullName(), folder.getSeparator())).equals("Papelera")) return folder;
        Folder trash = store.getFolder("Trash");
        if (!trash.exists() && !trash.create(Folder.HOLDS_MESSAGES))
            throw new IllegalStateException("No fue posible crear la carpeta Papelera");
        return trash;
    }

    static String destination(String source, String parent, String name, char separator) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > 100 || value.indexOf(separator) >= 0
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Nombre de carpeta inválido");
        String target = parent == null || parent.isBlank() ? value : parent + separator + value;
        if (target.equals(source)) throw new IllegalArgumentException("La carpeta ya está en ese destino");
        if (target.startsWith(source + separator))
            throw new IllegalArgumentException("Una carpeta no puede moverse dentro de sí misma");
        return target;
    }

    private static Folder mutableFolder(Store store, String name) throws Exception {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("INBOX"))
            throw new IllegalArgumentException("Esta carpeta no se puede modificar");
        Folder folder = store.getFolder(name);
        if (!folder.exists()) throw new IllegalArgumentException("La carpeta no existe");
        return folder;
    }

    private static void rename(Folder source, Folder destination) throws Exception {
        if (destination.exists()) throw new IllegalArgumentException("Ya existe una carpeta con ese nombre");
        if (!source.renameTo(destination)) throw new IllegalStateException("El servidor IMAP rechazó el cambio");
    }

    static String label(String name) {
        String value = name.toLowerCase(java.util.Locale.ROOT);
        if (value.equals("inbox")) return "Entrada";
        if (value.endsWith("sent") || value.endsWith("sent messages")) return "Enviados";
        if (value.endsWith("drafts")) return "Borradores";
        if (value.endsWith("junk") || value.endsWith("spam")) return "Spam";
        if (value.endsWith("trash") || value.endsWith("deleted")) return "Papelera";
        return name;
    }

    static String part(String name, char separator) {
        int index = name.lastIndexOf(separator);
        return index < 0 ? name : name.substring(index + 1);
    }

    static String parent(String name, char separator) {
        int index = name.lastIndexOf(separator);
        return index <= 0 ? "" : name.substring(0, index);
    }

    static int depth(String name, char separator) {
        return (int) name.chars().filter(value -> value == separator).count();
    }

    static String root(String name, char separator) {
        int index = name.indexOf(separator);
        return index < 0 ? name : name.substring(0, index);
    }

    private static int priority(String name) {
        return switch (label(name)) {
            case "Entrada" -> 0;
            case "Enviados" -> 1;
            case "Borradores" -> 2;
            case "Spam" -> 3;
            case "Papelera" -> 4;
            default -> 5;
        };
    }

    private static Folder folder(Store store, String name) throws Exception {
        Folder folder = store.getFolder(name);
        if (!folder.exists() || (folder.getType() & Folder.HOLDS_MESSAGES) == 0)
            throw new IllegalArgumentException("Carpeta IMAP inválida");
        return folder;
    }

    private Store connect(String mailbox, String accessToken) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.auth.mechanisms", "XOAUTH2");
        properties.setProperty("mail.imaps.auth.plain.disable", "true");
        properties.setProperty("mail.imaps.auth.login.disable", "true");
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", "15000");
        properties.setProperty("mail.imaps.writetimeout", "15000");
        Store store = Session.getInstance(properties).getStore();
        store.connect(host, port, mailbox, accessToken);
        return store;
    }

    private static Parsed parse(jakarta.mail.Part part) throws Exception {
        Parsed parsed = new Parsed();
        parse(part, "", parsed);
        parsed.html = sanitizeHtml(parsed.html);
        parsed.plain = limited(parsed.plain);
        return parsed;
    }

    private static void parse(jakarta.mail.Part part, String path, Parsed parsed) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++)
                parse(multipart.getBodyPart(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, parsed);
            return;
        }
        String cid = contentId(part);
        if (part.isMimeType("image/*") && !cid.isBlank()) {
            byte[] data = part.getInputStream().readNBytes(5 * 1024 * 1024 + 1);
            String type = part.getContentType().split(";", 2)[0].toLowerCase(java.util.Locale.ROOT);
            long imageBytes = parsed.images.stream().mapToLong(image -> image.data().length).sum();
            if (parsed.images.size() < 10 && imageBytes + data.length <= 15 * 1024 * 1024
                    && data.length <= 5 * 1024 * 1024 && safeImage(type, data))
                parsed.images.add(new InlineImage(cid, type, data));
            return;
        }
        if (jakarta.mail.Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null) {
            parsed.attachments.add(new Attachment(path, fileName(part), "application/octet-stream", part.getSize()));
            return;
        }
        if (part.isMimeType("text/html") && parsed.html.isBlank())
            parsed.html = limited(String.valueOf(part.getContent()));
        else if (part.isMimeType("text/plain") && parsed.plain.isBlank())
            parsed.plain = limited(String.valueOf(part.getContent()));
    }

    private static jakarta.mail.Part part(jakarta.mail.Part root, String path) throws Exception {
        jakarta.mail.Part current = root;
        for (String value : path.split("\\.")) {
            if (!current.isMimeType("multipart/*")) throw new IllegalArgumentException("Adjunto inválido");
            Multipart multipart = (Multipart) current.getContent();
            int index = Integer.parseInt(value);
            if (index < 0 || index >= multipart.getCount()) throw new IllegalArgumentException("Adjunto inválido");
            current = multipart.getBodyPart(index);
        }
        return current;
    }

    private static MimeBodyPart upload(Upload upload) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(upload.data(), upload.type())));
        part.setFileName(MimeUtility.encodeText(upload.name(), "UTF-8", null));
        return part;
    }

    private static String inlineImages(String html, List<InlineImage> images) {
        String result = html;
        for (InlineImage image : images) {
            String source = "cid:" + image.cid();
            String embedded = "data:" + image.type() + ";base64," + Base64.getEncoder().encodeToString(image.data());
            result = result.replace("src=\"" + source + "\"", "src=\"" + embedded + "\"");
        }
        return result;
    }

    private static String contentId(jakarta.mail.Part part) throws Exception {
        String[] headers = part.getHeader("Content-ID");
        if (headers == null || headers.length == 0) return "";
        return headers[0].strip().replaceAll("^<|>$", "");
    }

    private static String fileName(jakarta.mail.Part part) throws Exception {
        String name = part.getFileName() == null ? "archivo" : MimeUtility.decodeText(part.getFileName());
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        return name.isEmpty() ? "archivo" : name.substring(0, Math.min(name.length(), 180));
    }

    static boolean safeImage(String type, byte[] data) {
        if (!List.of("image/png", "image/jpeg", "image/gif").contains(type) || data == null || data.length == 0)
            return false;
        try { return ImageIO.read(new ByteArrayInputStream(data)) != null; }
        catch (Exception ignored) { return false; }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null) return "";
        List<String> values = new ArrayList<>(addresses.length);
        for (Address address : addresses)
            values.add(address instanceof InternetAddress internet ? internet.toUnicodeString() : address.toString());
        return String.join(", ", values);
    }

    private static String limited(String value) {
        return value.length() <= 200_000 ? value : value.substring(0, 200_000);
    }

    static String sanitizeHtml(String value) {
        return limited(HTML.sanitize(limited(value)));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
