package gator.mail;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class ImapMailbox {
    record FolderInfo(String name, String label, int unread, int total) {
    }

    record Summary(long uid, String from, String subject, Instant sent) {
    }

    record Mail(String from, String to, String subject, Instant sent, String body) {
    }

    private final String host = env("GATOR_MAIL_IMAP_HOST", "mail.soft-gator.com");
    private final int port = Integer.parseInt(env("GATOR_MAIL_IMAP_PORT", "993"));

    List<FolderInfo> folders(String mailbox, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken)) {
            List<FolderInfo> result = new ArrayList<>();
            for (Folder folder : store.getDefaultFolder().list("*")) {
                if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                result.add(new FolderInfo(folder.getFullName(), label(folder.getFullName()),
                        Math.max(0, folder.getUnreadMessageCount()), Math.max(0, folder.getMessageCount())));
            }
            result.sort(Comparator.comparingInt((FolderInfo folder) -> priority(folder.name()))
                    .thenComparing(FolderInfo::label, String.CASE_INSENSITIVE_ORDER));
            return result;
        }
    }

    List<Summary> messages(String mailbox, String folderName, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken); Folder folder = folder(store, folderName)) {
            folder.open(Folder.READ_ONLY);
            int end = folder.getMessageCount();
            if (end == 0) return List.of();
            Message[] messages = folder.getMessages(Math.max(1, end - 49), end);
            UIDFolder uidFolder = (UIDFolder) folder;
            List<Summary> result = new ArrayList<>(messages.length);
            for (int i = messages.length - 1; i >= 0; i--) {
                Message message = messages[i];
                result.add(new Summary(uidFolder.getUID(message), addresses(message.getFrom()),
                        text(message.getSubject(), "(Sin asunto)"),
                        message.getSentDate() == null ? Instant.EPOCH : message.getSentDate().toInstant()));
            }
            return result;
        }
    }

    Mail read(String mailbox, String folderName, long uid, String accessToken) throws Exception {
        try (Store store = connect(mailbox, accessToken); Folder folder = folder(store, folderName)) {
            folder.open(Folder.READ_ONLY);
            Message message = ((UIDFolder) folder).getMessageByUID(uid);
            if (message == null) return null;
            return new Mail(addresses(message.getFrom()), addresses(message.getRecipients(Message.RecipientType.TO)),
                    text(message.getSubject(), "(Sin asunto)"),
                    message.getSentDate() == null ? Instant.EPOCH : message.getSentDate().toInstant(),
                    plainText(message));
        }
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

    private static String plainText(Object part) throws Exception {
        if (part instanceof jakarta.mail.Part mailPart) {
            if (mailPart.isMimeType("text/plain")) return limited(String.valueOf(mailPart.getContent()));
            if (mailPart.isMimeType("multipart/*")) return plainText(mailPart.getContent());
            return "";
        }
        if (part instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String value = plainText(bodyPart);
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null) return "";
        List<String> values = new ArrayList<>(addresses.length);
        for (Address address : addresses) values.add(address.toString());
        return String.join(", ", values);
    }

    private static String limited(String value) {
        return value.length() <= 200_000 ? value : value.substring(0, 200_000);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
