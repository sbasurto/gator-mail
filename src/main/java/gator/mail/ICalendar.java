package gator.mail;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class ICalendar {
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    record Invite(String uid, int sequence, String method, String summary, String description, String location,
            String organizer, List<String> attendees, Instant start, Instant end, boolean allDay, String timezone,
            String startLine, String endLine) {
        boolean canReply(String mailbox) {
            return "REQUEST".equals(method) && attendees.stream().anyMatch(mailbox::equalsIgnoreCase);
        }
    }

    private record DateValue(Instant instant, boolean allDay, String timezone, String line) { }

    static Invite parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 1_048_576) return null;
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("\n[ \t]", "");
        Map<String, List<String>> values = new LinkedHashMap<>();
        boolean event = false;
        for (String line : text.split("\n")) {
            if ("BEGIN:VEVENT".equalsIgnoreCase(line)) { event = true; continue; }
            if ("END:VEVENT".equalsIgnoreCase(line)) break;
            if (!event || line.length() > 10_000) continue;
            int colon = line.indexOf(':');
            if (colon < 1) continue;
            String key = line.substring(0, colon).toUpperCase(Locale.ROOT);
            values.computeIfAbsent(key.substring(0, key.indexOf(';') < 0 ? key.length() : key.indexOf(';')),
                    ignored -> new ArrayList<>()).add(line);
        }
        String uid = value(values, "UID");
        String organizer = email(value(values, "ORGANIZER"));
        DateValue start = date(first(values, "DTSTART"));
        DateValue end = date(first(values, "DTEND"));
        if (uid.isBlank() || uid.length() > 512 || organizer.isBlank() || start == null) return null;
        if (end == null) end = new DateValue(start.instant().plus(start.allDay() ? Duration.ofDays(1) : Duration.ZERO),
                start.allDay(), start.timezone(), "");
        List<String> attendees = new ArrayList<>();
        for (String line : values.getOrDefault("ATTENDEE", List.of())) {
            String attendee = email(rawValue(line));
            if (!attendee.isBlank() && !attendees.contains(attendee) && attendees.size() < 100) attendees.add(attendee);
        }
        int sequence = 0;
        try { sequence = Math.max(0, Integer.parseInt(value(values, "SEQUENCE"))); }
        catch (RuntimeException ignored) { }
        String method = property(text, "METHOD").toUpperCase(Locale.ROOT);
        if (method.isBlank()) method = "PUBLISH";
        return new Invite(uid, sequence, method, clean(value(values, "SUMMARY"), 200),
                clean(value(values, "DESCRIPTION"), 5_000), clean(value(values, "LOCATION"), 500),
                organizer, List.copyOf(attendees), start.instant(), end.instant(), start.allDay(), start.timezone(),
                start.line(), end.line());
    }

    static byte[] reply(Invite invite, String attendee, String status) {
        if (!List.of("ACCEPTED", "TENTATIVE", "DECLINED").contains(status) || !invite.canReply(attendee))
            throw new IllegalArgumentException("Respuesta de invitación inválida");
        StringBuilder value = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
                .append("PRODID:-//Gator Mail//iCalendar//ES\r\nCALSCALE:GREGORIAN\r\nMETHOD:REPLY\r\nBEGIN:VEVENT\r\n")
                .append("UID:").append(escape(invite.uid())).append("\r\n")
                .append("DTSTAMP:").append(UTC.format(Instant.now())).append("\r\n")
                .append("SEQUENCE:").append(invite.sequence()).append("\r\n")
                .append(invite.startLine()).append("\r\n");
        if (!invite.endLine().isBlank()) value.append(invite.endLine()).append("\r\n");
        value.append("ORGANIZER:mailto:").append(escape(invite.organizer())).append("\r\n")
                .append("ATTENDEE;PARTSTAT=").append(status).append(";RSVP=FALSE:mailto:")
                .append(escape(attendee)).append("\r\n")
                .append("SUMMARY:").append(escape(invite.summary())).append("\r\n")
                .append("END:VEVENT\r\nEND:VCALENDAR\r\n");
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static DateValue date(String line) {
        if (line.isBlank()) return null;
        String raw = rawValue(line);
        String header = line.substring(0, line.indexOf(':'));
        String timezone = parameter(header, "TZID");
        try {
            if (header.toUpperCase(Locale.ROOT).contains("VALUE=DATE") || raw.matches("\\d{8}")) {
                ZoneId zone = zone(timezone);
                return new DateValue(LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant(),
                        true, zone.getId(), line);
            }
            if (raw.endsWith("Z"))
                return new DateValue(Instant.from(UTC.parse(raw)), false, "UTC", line);
            ZoneId zone = zone(timezone);
            LocalDateTime local = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            return new DateValue(local.atZone(zone).toInstant(), false, zone.getId(), line);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static ZoneId zone(String value) {
        try { return value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value); }
        catch (RuntimeException error) { return ZoneId.systemDefault(); }
    }

    private static String parameter(String header, String name) {
        for (String item : header.split(";")) {
            int equals = item.indexOf('=');
            if (equals > 0 && item.substring(0, equals).equalsIgnoreCase(name))
                return item.substring(equals + 1).replace("\"", "");
        }
        return "";
    }

    private static String property(String text, String name) {
        for (String line : text.split("\n"))
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return rawValue(line);
        return "";
    }

    private static String first(Map<String, List<String>> values, String key) {
        List<String> lines = values.get(key);
        return lines == null || lines.isEmpty() ? "" : lines.get(0);
    }

    private static String value(Map<String, List<String>> values, String key) {
        return unescape(rawValue(first(values, key)));
    }

    private static String rawValue(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).strip();
    }

    private static String email(String value) {
        String result = value.strip();
        if (result.regionMatches(true, 0, "mailto:", 0, 7)) result = result.substring(7);
        return result.matches("(?i)^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$") ? result.toLowerCase(Locale.ROOT) : "";
    }

    private static String clean(String value, int max) {
        String result = unescape(value).replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
        return result.substring(0, Math.min(result.length(), max));
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",")
                .replace("\\;", ";").replace("\\\\", "\\");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }
}
