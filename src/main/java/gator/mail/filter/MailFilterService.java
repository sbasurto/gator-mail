package gator.mail.filter;

import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.angus.mail.imap.IMAPFolder;

public final class MailFilterService {
    private static final Map<String, Thread> WORKERS = new ConcurrentHashMap<>();
    private static volatile boolean running = true;

    private MailFilterService() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.load(System.getenv());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            WORKERS.values().forEach(Thread::interrupt);
        }));
        log("service_start", "", 0, 0, 0, "", "", 0, "OK", "");
        while (running) {
            Set<String> enabled = mailboxes(config);
            disableInactive(config);
            WORKERS.forEach((mailbox, thread) -> {
                if (!enabled.contains(mailbox)) thread.interrupt();
            });
            WORKERS.entrySet().removeIf(entry -> !entry.getValue().isAlive());
            for (String mailbox : enabled) WORKERS.computeIfAbsent(mailbox, key ->
                    Thread.ofVirtual().name("filter-" + key).start(() -> runMailbox(config, key)));
            Thread.sleep(config.refreshSeconds() * 1000L);
        }
    }

    private static void runMailbox(Config config, String mailbox) {
        int reconnects = 0;
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Store store = connect(config, mailbox)) {
                IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);
                initialize(config, mailbox, inbox.getUIDValidity(), inbox.getUIDNext());
                while (running && mailboxEnabled(config, mailbox)) {
                    process(config, mailbox, inbox);
                    reconnects = 0;
                    heartbeat(config, mailbox, "IDLE", null, 0);
                    inbox.idle();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                reconnects++;
                heartbeat(config, mailbox, "ERROR", concise(error), reconnects);
                log("connection", mailbox, 0, 0, 0, "", "", reconnects, "RETRY", concise(error));
                sleep(Math.min(60, 1 << Math.min(reconnects, 6)));
            }
        }
        WORKERS.remove(mailbox, Thread.currentThread());
        log("mailbox_stop", mailbox, 0, 0, 0, "", "", 0, "OK", "");
    }

    private static Store connect(Config config, String mailbox) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.auth.mechanisms", "PLAIN LOGIN");
        properties.setProperty("mail.imaps.connectiontimeout", "15000");
        properties.setProperty("mail.imaps.timeout", "1800000");
        Store store = Session.getInstance(properties).getStore("imaps");
        store.connect(config.imapHost(), config.imapPort(),
                mailbox + config.masterSeparator() + config.masterUser(), config.masterPassword());
        log("imap_connected", mailbox, 0, 0, 0, "", "", 0, "OK", "");
        return store;
    }

    private static void initialize(Config config, String mailbox, long uidValidity, long uidNext) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement select = db.prepareStatement(
                     "select uidvalidity from mail_filtro_estado where mailbox=?")) {
            select.setString(1, mailbox);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next() && rows.getLong(1) == uidValidity) return;
            }
        }
        long baseline = Math.max(0, uidNext - 1);
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     insert into mail_filtro_estado(mailbox,uidvalidity,ultimo_uid,estado,ultimo_error,reintentos)
                     values (?,?,?,'ACTIVO',null,0)
                     on conflict (mailbox) do update set uidvalidity=excluded.uidvalidity,
                         ultimo_uid=excluded.ultimo_uid,estado='ACTIVO',ultimo_error=null,reintentos=0,
                         fecha_heartbeat=current_timestamp,fecha_actualizacion=current_timestamp
                     """)) {
            statement.setString(1, mailbox);
            statement.setLong(2, uidValidity);
            statement.setLong(3, baseline);
            statement.executeUpdate();
        }
        log("uid_baseline", mailbox, uidValidity, baseline, 0, "", "", 0, "OK",
                "Solo se procesaran mensajes posteriores");
    }

    private static void process(Config config, String mailbox, IMAPFolder inbox) throws Exception {
        long uidValidity = inbox.getUIDValidity();
        long lastUid = lastUid(config, mailbox, uidValidity);
        Message[] messages = inbox.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
        List<Rule> rules = rules(config, mailbox);
        for (Message message : messages) {
            long uid = inbox.getUID(message);
            if (uid <= 0) continue;
            Rule rule = rules.stream().filter(value -> matches(value, message)).findFirst().orElse(null);
            int attempt = claim(config, mailbox, uidValidity, uid, rule, messageId(message));
            if (attempt == 0) {
                checkpoint(config, mailbox, uidValidity, uid);
                continue;
            }
            Instant start = Instant.now();
            try {
                if (rule == null) {
                    complete(config, mailbox, uidValidity, uid, "SIN_COINCIDENCIA", "");
                    log("message", mailbox, uidValidity, uid, 0, "", "", attempt,
                            "NO_MATCH", duration(start));
                } else {
                    Folder destination = inbox.getStore().getFolder(rule.destination());
                    if (!destination.exists()) throw new IllegalStateException("La carpeta destino no existe");
                    inbox.moveUIDMessages(new Message[]{message}, destination);
                    complete(config, mailbox, uidValidity, uid, "MOVIDO", "");
                    log("message", mailbox, uidValidity, uid, rule.id(), rule.name(), rule.destination(),
                            attempt, "MOVED", duration(start));
                }
                checkpoint(config, mailbox, uidValidity, uid);
            } catch (Exception error) {
                boolean failed = attempt >= config.maxAttempts();
                complete(config, mailbox, uidValidity, uid, failed ? "FALLIDO" : "REINTENTO", concise(error));
                log("message", mailbox, uidValidity, uid, rule == null ? 0 : rule.id(),
                        rule == null ? "" : rule.name(), rule == null ? "" : rule.destination(),
                        attempt, failed ? "FAILED" : "RETRY", concise(error));
                if (failed) checkpoint(config, mailbox, uidValidity, uid);
                else throw error;
            }
        }
    }

    static boolean matches(Rule rule, Message message) {
        try {
            if ("SIZE".equals(rule.field())) {
                long size = Math.max(0, message.getSize());
                long expected = Long.parseLong(rule.value());
                return "GT".equals(rule.operator()) ? size > expected : size < expected;
            }
            String actual = switch (rule.field()) {
                case "FROM" -> addresses(message.getFrom());
                case "TO" -> addresses(message.getRecipients(Message.RecipientType.TO));
                case "CC" -> addresses(message.getRecipients(Message.RecipientType.CC));
                case "SUBJECT" -> value(message.getSubject());
                case "HEADER" -> String.join(" ", message.getHeader(rule.header()) == null
                        ? new String[0] : message.getHeader(rule.header()));
                default -> "";
            };
            String left = actual.toLowerCase(Locale.ROOT);
            String right = rule.value().toLowerCase(Locale.ROOT);
            return switch (rule.operator()) {
                case "CONTAINS" -> left.contains(right);
                case "EQUALS" -> left.equals(right);
                case "STARTS_WITH" -> left.startsWith(right);
                case "ENDS_WITH" -> left.endsWith(right);
                default -> false;
            };
        } catch (Exception error) {
            throw new IllegalStateException("No fue posible evaluar la regla", error);
        }
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null) return "";
        List<String> values = new ArrayList<>(addresses.length);
        for (Address address : addresses) values.add(address.toString());
        return String.join(", ", values);
    }

    private static Set<String> mailboxes(Config config) throws Exception {
        Set<String> result = new HashSet<>();
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement(
                     "select distinct mailbox from mail_filtro_reglas where habilitada order by mailbox");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static void disableInactive(Config config) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     update mail_filtro_estado e set estado='DETENIDO',ultimo_error=null,reintentos=0,
                         fecha_heartbeat=current_timestamp,fecha_actualizacion=current_timestamp
                     where not exists (select 1 from mail_filtro_reglas r
                         where r.mailbox=e.mailbox and r.habilitada) and e.estado<>'DETENIDO'
                     """)) {
            statement.executeUpdate();
        }
    }

    private static boolean mailboxEnabled(Config config, String mailbox) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement(
                     "select exists(select 1 from mail_filtro_reglas where mailbox=? and habilitada)")) {
            statement.setString(1, mailbox);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    private static List<Rule> rules(Config config, String mailbox) throws Exception {
        List<Rule> result = new ArrayList<>();
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     select regla_id,nombre,campo,operador,encabezado,valor,carpeta_destino
                     from mail_filtro_reglas where mailbox=? and habilitada
                     order by prioridad,regla_id
                     """)) {
            statement.setString(1, mailbox);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new Rule(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)));
            }
        }
        return result;
    }

    private static long lastUid(Config config, String mailbox, long uidValidity) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement(
                     "select ultimo_uid from mail_filtro_estado where mailbox=? and uidvalidity=?")) {
            statement.setString(1, mailbox);
            statement.setLong(2, uidValidity);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Checkpoint IMAP inexistente");
                return row.getLong(1);
            }
        }
    }

    private static int claim(Config config, String mailbox, long uidValidity, long uid, Rule rule,
                             String messageId) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     insert into mail_filtro_auditoria(mailbox,uidvalidity,uid,regla_id,regla_nombre,
                         carpeta_destino,message_id,estado)
                     values (?,?,?,?,?,?,?,'PROCESANDO')
                     on conflict (mailbox,uidvalidity,uid) do update set
                         estado='PROCESANDO',intento=mail_filtro_auditoria.intento+1,
                         detalle=null,fecha_actualizacion=current_timestamp
                     where mail_filtro_auditoria.estado in ('REINTENTO','DESCONOCIDO')
                        or (mail_filtro_auditoria.estado='PROCESANDO'
                            and mail_filtro_auditoria.fecha_actualizacion < current_timestamp - interval '5 minutes')
                     returning intento
                     """)) {
            statement.setString(1, mailbox);
            statement.setLong(2, uidValidity);
            statement.setLong(3, uid);
            if (rule == null) statement.setNull(4, java.sql.Types.BIGINT); else statement.setLong(4, rule.id());
            statement.setString(5, rule == null ? null : rule.name());
            statement.setString(6, rule == null ? null : rule.destination());
            statement.setString(7, messageId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        }
    }

    private static void complete(Config config, String mailbox, long uidValidity, long uid,
                                 String status, String detail) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     update mail_filtro_auditoria set estado=?,detalle=?,fecha_actualizacion=current_timestamp
                     where mailbox=? and uidvalidity=? and uid=?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, detail);
            statement.setString(3, mailbox);
            statement.setLong(4, uidValidity);
            statement.setLong(5, uid);
            statement.executeUpdate();
        }
    }

    private static void checkpoint(Config config, String mailbox, long uidValidity, long uid) throws Exception {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     update mail_filtro_estado set ultimo_uid=greatest(ultimo_uid,?),estado='ACTIVO',
                         ultimo_error=null,reintentos=0,fecha_heartbeat=current_timestamp,
                         fecha_actualizacion=current_timestamp where mailbox=? and uidvalidity=?
                     """)) {
            statement.setLong(1, uid);
            statement.setString(2, mailbox);
            statement.setLong(3, uidValidity);
            statement.executeUpdate();
        }
    }

    private static void heartbeat(Config config, String mailbox, String status, String error, int retries) {
        try (Connection db = connection(config);
             PreparedStatement statement = db.prepareStatement("""
                     update mail_filtro_estado set estado=?,ultimo_error=?,reintentos=?,
                         fecha_heartbeat=current_timestamp,fecha_actualizacion=current_timestamp where mailbox=?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setInt(3, retries);
            statement.setString(4, mailbox);
            statement.executeUpdate();
        } catch (Exception nested) {
            log("heartbeat", mailbox, 0, 0, 0, "", "", retries, "FAILED", concise(nested));
        }
    }

    private static Connection connection(Config config) throws Exception {
        return DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
    }

    private static String messageId(Message message) {
        try {
            String[] values = message.getHeader("Message-ID");
            return values == null || values.length == 0 ? null : value(values[0], 500);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void sleep(int seconds) {
        try { Thread.sleep(seconds * 1000L); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    private static String duration(Instant start) {
        return "duration_ms=" + Duration.between(start, Instant.now()).toMillis();
    }

    private static String concise(Throwable error) {
        return value(error.getClass().getSimpleName() + ": " + value(error.getMessage()), 1000);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String value(String value, int max) {
        String result = value(value).replace('\r', ' ').replace('\n', ' ');
        return result.length() <= max ? result : result.substring(0, max);
    }

    private static synchronized void log(String event, String mailbox, long uidValidity, long uid, long rule,
                                         String ruleName, String folder, int attempt, String outcome, String detail) {
        System.out.printf(
                "{\"time\":\"%s\",\"event\":\"%s\",\"mailbox\":\"%s\",\"uidvalidity\":%d,\"uid\":%d,"
                + "\"rule_id\":%d,\"rule\":\"%s\",\"folder\":\"%s\",\"attempt\":%d,\"outcome\":\"%s\","
                + "\"detail\":\"%s\"}%n",
                Instant.now(), json(event), json(mailbox), uidValidity, uid, rule, json(ruleName),
                json(folder), attempt, json(outcome), json(detail));
    }

    static String json(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value(value).toCharArray()) switch (character) {
            case '"' -> result.append("\\\"");
            case '\\' -> result.append("\\\\");
            case '\b' -> result.append("\\b");
            case '\f' -> result.append("\\f");
            case '\n' -> result.append("\\n");
            case '\r' -> result.append("\\r");
            case '\t' -> result.append("\\t");
            default -> {
                if (character < 0x20) result.append("\\u%04x".formatted((int) character));
                else result.append(character);
            }
        }
        return result.toString();
    }

    record Rule(long id, String name, String field, String operator, String header,
                String value, String destination) {}

    record Config(String dbUrl, String dbUser, String dbPassword, String imapHost, int imapPort,
                  String masterUser, String masterPassword, String masterSeparator,
                  int refreshSeconds, int maxAttempts) {
        static Config load(Map<String, String> environment) throws Exception {
            String secretFile = required(environment, "GATOR_MAIL_FILTER_MASTER_SECRET_FILE");
            String password = Files.readString(Path.of(secretFile), StandardCharsets.UTF_8).trim();
            if (password.isEmpty()) throw new IllegalArgumentException("El secreto maestro está vacío");
            return new Config(
                    environment.getOrDefault("GATOR_MAIL_FILTER_DB_URL",
                            "jdbc:postgresql://127.0.0.1:5432/db_gatormail"),
                    environment.getOrDefault("GATOR_MAIL_FILTER_DB_USER", "gator_mail_filter"),
                    environment.getOrDefault("GATOR_MAIL_FILTER_DB_PASSWORD", ""),
                    environment.getOrDefault("GATOR_MAIL_FILTER_IMAP_HOST", "mail.soft-gator.com"),
                    integer(environment, "GATOR_MAIL_FILTER_IMAP_PORT", 993, 1, 65535),
                    required(environment, "GATOR_MAIL_FILTER_MASTER_USER"), password,
                    environment.getOrDefault("GATOR_MAIL_FILTER_MASTER_SEPARATOR", "*"),
                    integer(environment, "GATOR_MAIL_FILTER_REFRESH_SECONDS", 15, 5, 3600),
                    integer(environment, "GATOR_MAIL_FILTER_MAX_ATTEMPTS", 5, 1, 20));
        }

        private static String required(Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta " + name);
            return value.trim();
        }

        private static int integer(Map<String, String> environment, String name, int fallback, int min, int max) {
            int value = Integer.parseInt(environment.getOrDefault(name, String.valueOf(fallback)));
            if (value < min || value > max) throw new IllegalArgumentException(name + " inválido");
            return value;
        }
    }
}
