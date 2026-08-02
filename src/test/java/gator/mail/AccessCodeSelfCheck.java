package gator.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gator.lib.web.gui.GatorJsonView;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AccessCodeSelfCheck {
    public static void main(String[] args) {
        String hash = AccessCode.hash("A1B2C3D4");
        assert AccessCode.matches("a1b2c3d4", hash);
        assert !AccessCode.matches("A1B2C3D5", hash);
        assert !AccessCode.matches("123", hash);
        assert AccessCode.matches("AB12CD34EF56", AccessCode.hash("AB12CD34EF56"));
        assert AccessCode.matches("  AB12CD34  ", AccessCode.hash("AB12CD34"));
        assert !AccessCode.matches("AB12CD34EF567", AccessCode.hash("AB12CD34EF567"));
        assert "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(
                OAuthServlet.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assert OAuthServlet.isLocal("localhost:8080");
        assert !OAuthServlet.isLocal("erp.soft-gator.com");
        assert "Entrada".equals(ImapMailbox.label("INBOX"));
        assert "Enviados".equals(ImapMailbox.label("Sent Messages"));
        assert "Papelera".equals(ImapMailbox.label("Trash"));
        assert "Alberto".equals(ImapMailbox.part("Administracion.Alberto", '.'));
        assert "Administracion".equals(ImapMailbox.parent("Administracion.Alberto", '.'));
        assert ImapMailbox.depth("Administracion.Alberto", '.') == 1;
        assert "Administracion".equals(ImapMailbox.root("Administracion.Alberto", '.'));
        assert "Clientes.Nueva".equals(ImapMailbox.destination("Clientes.Anterior", "Clientes", "Nueva", '.'));
        boolean rejectedCycle = false;
        try { ImapMailbox.destination("Clientes", "Clientes.Hijo", "Clientes", '.'); }
        catch (IllegalArgumentException expected) { rejectedCycle = true; }
        assert rejectedCycle;
        String safeMail = ImapMailbox.sanitizeHtml("<p>Hola <strong>Gator</strong></p><script>alert(1)</script>");
        assert safeMail.contains("<p>Hola <strong>Gator</strong></p>");
        assert !safeMail.contains("script");
        assert !safeMail.contains("alert");
        String safeImage = ImapMailbox.sanitizeHtml("<img src=\"cid:logo\" onerror=\"alert(1)\">");
        assert safeImage.contains("cid:logo");
        assert !safeImage.contains("onerror");
        MailServlet.MessageBody htmlBody = MailServlet.messageBody(
                "<h1>Hola</h1><p><strong>HTML</strong></p><script>alert(1)</script>", "html");
        assert htmlBody.html().contains("<h1>Hola</h1>");
        assert htmlBody.html().contains("<strong>HTML</strong>");
        assert !htmlBody.html().contains("script");
        assert htmlBody.plain().contains("Hola");
        String richHtml = ImapMailbox.sanitizeHtml("<a href=\"https://example.com\">Enlace</a>"
                + "<table><tr><th>A</th></tr><tr><td>B</td></tr></table><img src=\"cid:logo\">");
        assert richHtml.contains("href=\"https://example.com\"");
        assert richHtml.contains("<table>");
        assert richHtml.contains("cid:logo");
        String calendarText = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REQUEST\r\nBEGIN:VEVENT\r\n"
                + "UID:demo@example.com\r\nSEQUENCE:2\r\nDTSTART:20260724T160000Z\r\n"
                + "DTEND:20260724T170000Z\r\nORGANIZER:mailto:organizer@example.com\r\n"
                + "ATTENDEE;RSVP=TRUE:mailto:user@example.com\r\nSUMMARY:Reunión\r\n"
                + "DESCRIPTION:Agenda completa\\nSegundo punto\r\nLOCATION:Sala 1\r\n"
                + "URL:https://example.com/reunion\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        ICalendar.Invite invite = ICalendar.parse(calendarText.getBytes());
        assert invite != null;
        assert invite.canReply("user@example.com");
        assert "organizer@example.com".equals(invite.organizer());
        assert "Agenda completa\nSegundo punto".equals(invite.description());
        assert "https://example.com/reunion".equals(invite.link());
        assert "CONFIRMED".equals(invite.status());
        assert new String(ICalendar.reply(invite, "user@example.com", "ACCEPTED"))
                .contains("ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE:mailto:user@example.com");
        assert ImapMailbox.safeImage("image/png", Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        assert !ImapMailbox.safeImage("image/svg+xml", "<svg/>".getBytes());
        ImapMailbox.Upload arbitraryFile = MailServlet.upload("documento.pdf", "application/pdf",
                "%PDF".getBytes(), true);
        assert !arbitraryFile.inline();
        assert "application/octet-stream".equals(arbitraryFile.type());
        String logout = OAuthServlet.endSession("id token");
        assert logout.contains("id_token_hint=id+token");
        assert !logout.contains("post_logout_redirect_uri");
        assert !OAuthServlet.endSession("").contains("id_token_hint");
        assert "sb***to".equals(MailServlet.userHint("sbasurto"));
        assert "sb***to".equals(MailServlet.userHint("sbasurto@soft-gator.com"));
        assert "s***g".equals(MailServlet.userHint("sg" + "g"));
        assert "**".equals(MailServlet.userHint("sg"));
        assert "+5215511186677".equals(MailServlet.phone(" +52 (1) 55-1118-6677 "));
        boolean rejectedPhone = false;
        try { MailServlet.phone("5511186677"); }
        catch (IllegalArgumentException expected) { rejectedPhone = true; }
        assert rejectedPhone;
        String temporaryPassword = MailServlet.temporaryPassword();
        assert temporaryPassword.matches("[A-Za-z0-9_-]{24}");
        assert !temporaryPassword.equals(MailServlet.temporaryPassword());
        assert MailServlet.sessionTimeoutSeconds(JsonParser.parseString("{\"sessionTimeout\":10800000}").getAsJsonObject()) == 10800;
        assert MailServlet.sessionTimeoutSeconds(new JsonObject()) == 10800;
        assert Arrays.equals(new long[]{1, 42}, MailServlet.uids(new String[]{"1", "42"}));
        assert "asunto urgente".equals(MailServlet.searchQuery("  asunto urgente  "));
        assert MailServlet.page(null) == 1;
        assert MailServlet.page("3") == 3;
        assert MailServlet.pageSize(null) == 20;
        assert MailServlet.pageSize("100") == 100;
        assert "Re: Hola".equals(MailServlet.subject("Hola", "Re:"));
        assert "re: Hola".equals(MailServlet.subject("re: Hola", "Re:"));
        assert "&lt;b&gt;&amp;<br>Hola".equals(MailServlet.htmlText("<b>&\nHola"));
        assert "otro@example.com".equals(MailServlet.replyAllCc("yo@example.com", "autor@example.com",
                "yo@example.com, otro@example.com", "autor@example.com"));
        assert "Junk".equals(ImapMailbox.promotedName("INBOX.Junk", '.'));
        assert "inline-1@gator-mail".equals(ImapMailbox.inlineCid(0));
        assert "Spam".equals(ImapMailbox.promotedName("INBOX.Spam", '.'));
        assert ImapMailbox.promotedName("INBOX.Archivo", '.').isEmpty();
        List<Map<String, Object>> pagedFolders = MailServlet.folderGroups(List.of(
                new ImapMailbox.FolderInfo("INBOX", "Entrada", "", "INBOX", 0, 0, 1),
                new ImapMailbox.FolderInfo("Sent", "Enviados", "", "Sent", 0, 0, 4),
                new ImapMailbox.FolderInfo("INBOX.Spam", "Spam", "INBOX", "INBOX", 1, 0, 3),
                new ImapMailbox.FolderInfo("Archive", "Archivo", "", "Archive", 0, 0, 50)), "Archive", 60);
        assert "mail?folder=INBOX&page=1&size=60".equals(pagedFolders.get(0).get("href"));
        assert "Entrada".equals(pagedFolders.get(0).get("label"));
        assert Boolean.TRUE.equals(pagedFolders.get(0).get("leaf"));
        assert "Enviados".equals(pagedFolders.get(1).get("label"));
        assert "Spam".equals(pagedFolders.get(2).get("label"));
        assert Boolean.TRUE.equals(pagedFolders.get(2).get("leaf"));
        assert "mail?folder=Archive&page=1&size=60".equals(pagedFolders.get(3).get("href"));
        List<Map<String, Object>> folderMenus = MailServlet.folderMenus(List.of(
                new ImapMailbox.FolderInfo("INBOX", "Entrada", "", "INBOX", 0, 0, 1),
                new ImapMailbox.FolderInfo("Sent", "Enviados", "", "Sent", 0, 0, 4),
                new ImapMailbox.FolderInfo("Archive", "Archivo", "", "Archive", 0, 0, 50)), "Archive", 60);
        assert "Correo".equals(folderMenus.get(0).get("label"));
        assert "Carpetas personales".equals(folderMenus.get(1).get("label"));
        assert Boolean.TRUE.equals(folderMenus.get(1).get("open"));
        List<Map<String, Object>> deepFolders = MailServlet.folderGroups(List.of(
                new ImapMailbox.FolderInfo("Clientes", "Clientes", "", "Clientes", 0, 0, 3),
                new ImapMailbox.FolderInfo("Clientes.Activos", "Activos", "Clientes", "Clientes", 1, 0, 2),
                new ImapMailbox.FolderInfo("Clientes.Activos.VIP", "VIP", "Clientes.Activos", "Clientes", 2, 0, 1)),
                "", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deepChildren = (List<Map<String, Object>>) deepFolders.get(0).get("children");
        assert deepChildren.size() == 2;
        assert "Activos".equals(deepChildren.get(0).get("label"));
        assert String.valueOf(deepChildren.get(1).get("className")).contains("mail-folder-depth-3");
        assert !MailServlet.cacheSessionKey("USER@EXAMPLE.COM", "INBOX")
                .equals(MailServlet.cacheSessionKey("user@example.com", "Clientes.VIP"));
        boolean rejectedUid = false;
        try { MailServlet.uids(new String[]{"0"}); }
        catch (IllegalArgumentException expected) { rejectedUid = true; }
        assert rejectedUid;

        Map<String, Object> model = new HashMap<>();
        for (String key : new String[]{"challenge", "codeChallenge", "phoneCorrection", "composeView", "mailboxView", "messageView", "mailContent", "empty",
                "hasMessages", "pending", "error", "loggedOut", "noticeVisible", "sendNotice", "mailHtml",
                "originalHtmlAvailable",
                "configurationAvailable", "configurationAdminAvailable", "configurationUsersView",
                "configurationContactsView", "configurationFiltersView", "configurationFoldersView",
                "configurationOptionsView", "calendarView",
                "dashboardView", "eventsAvailable", "eventFormView", "eventCreated", "eventUpdated", "eventSyncFailed",
                "eventCanComplete", "eventCompleted", "eventCompletedState",
                "invitationAvailable", "invitationCanReply", "invitationCancelled", "invitationReplyNotice",
                "invitationSyncFailed", "smsAdminAvailable", "userAdminNotice"}) model.put(key, true);
        model.put("invitationCannotReply", false);
        model.put("smsAuthenticationEnabled", true);
        model.put("configurationOptionsClass", "active");
        model.put("mailText", false);
        model.put("passwordReset", true);
        model.put("temporaryPassword", "Abcd_1234-Efgh_5678-Ijkl");
        model.put("userAdminMessage", "Teléfono agregado");
        model.put("body", "<script>parent.alert('bad')</script>");
        model.put("originalHtml", "<script>alert('original')</script><p>Hola</p>");
        model.put("contextPath", "/gator-mail");
        model.put("layoutClass", "mail-workspace");
        model.put("contentClass", "mail-content");
        model.put("sessionActive", true);
        model.put("logoutTitle", "Sesión cerrada correctamente");
        model.put("logoutCopy", "Tu sesión de Gator Mail terminó de forma segura.");
        model.put("mailbox", "<user@example.com>");
        model.put("accountHref", "/gator-mail/oauth/password");
        model.put("folderMenus", folderMenus);
        model.put("selectedFolder", "INBOX");
        model.put("folderActionsDisabled", true);
        model.put("csrf", "csrf-token");
        model.put("composeTo", "");
        model.put("composeCc", "");
        model.put("composeBcc", "");
        model.put("composeSubject", "");
        model.put("composeBody", "");
        model.put("invitationTitle", "Reunión de proyecto");
        model.put("invitationOrganizer", "organizer@example.com");
        model.put("invitationLocation", "Sala 1");
        model.put("invitationDescription", "Agenda completa");
        model.put("invitationDescriptionAvailable", true);
        model.put("invitationAttendees", "user@example.com");
        model.put("invitationAttendeesAvailable", true);
        model.put("invitationTimezone", "UTC");
        model.put("invitationStatus", "CONFIRMED");
        model.put("invitationLink", "https://example.com/reunion");
        model.put("invitationLinkAvailable", true);
        model.put("invitationMethod", "REQUEST");
        model.put("invitationSequence", 2);
        model.put("invitationEventUid", "demo@example.com");
        model.put("invitationStart", "24/07/2026 10:00");
        model.put("invitationEnd", "24/07/2026 11:00");
        model.put("invitationFolder", "INBOX");
        model.put("invitationUid", "1");
        model.put("invitationReplyLabel", "Aceptaste esta invitación.");
        model.put("composeTitle", "Responder");
        model.put("composeCancelHref", "mail?folder=INBOX&uid=1");
        model.put("contactsAvailable", true);
        model.put("contactsEmpty", true);
        model.put("contacts", List.of(Map.of("name", "Contacto Uno", "email", "uno@example.com")));
        model.put("configurationOpen", true);
        model.put("mailOpen", true);
        model.put("mailFoldersMenu", true);
        model.put("mailNavigationOnly", true);
        model.put("configurationUsersClass", "active");
        model.put("configurationContactsClass", "");
        model.put("configurationFiltersClass", "");
        model.put("configurationFoldersClass", "");
        model.put("calendarClass", "active");
        model.put("eventsEmpty", false);
        model.put("mailTotal", 12);
        model.put("mailUnread", 5);
        model.put("mailRead", 7);
        model.put("recentSendersAvailable", true);
        model.put("recentSendersEmpty", false);
        model.put("recentSenders", List.of(Map.of("sender", "reciente@example.com", "count", 4)));
        model.put("historicSendersAvailable", true);
        model.put("historicSendersEmpty", false);
        model.put("historicSenders", List.of(Map.of("sender", "historico@example.com", "count", 12)));
        model.put("calendarMonth", "Julio 2026");
        model.put("calendarPrevious", "mail?action=calendar&month=2026-06");
        model.put("calendarNext", "mail?action=calendar&month=2026-08");
        model.put("eventOrganizer", "usuario@example.com");
        model.put("eventStatus", "Activo");
        model.put("eventFormTitle", "Actualizar evento");
        model.put("eventSubmitLabel", "Guardar cambios");
        model.put("eventId", "7dc5dfc8-756b-4d35-b6db-dba287f46d71");
        model.put("eventSummary", "Evento Uno");
        model.put("eventDescription", "Descripción");
        model.put("eventPlace", "Oficina");
        model.put("eventGuests", "uno@example.com");
        model.put("eventTags", "seguimiento");
        model.put("eventLink", "https://example.com/evento");
        model.put("eventLinkAvailable", true);
        model.put("eventReadOnlyView", false);
        model.put("eventStart", "2026-07-21T10:00");
        model.put("eventEnd", "2026-07-21T11:00");
        model.put("eventTimezone", "America/Mexico_City");
        model.put("calendarDays", List.of(Map.of("number", 21, "className", "is-today",
                "events", List.of(Map.of("summary", "Evento Uno", "description", "Descripción",
                        "time", "10:00", "statusClass", "is-on-time")))));
        model.put("events", List.of(Map.of("summary", "Evento Uno", "description", "Descripción",
                "place", "Oficina", "start", "21/07/2026 10:00", "end", "21/07/2026 11:00",
                "status", "A tiempo", "statusClass", "is-on-time")));
        model.put("configurationUsers", List.of(Map.of("id", "usuario", "name", "Usuario Uno",
                "email", "usuario@example.com", "enabled", true, "phone", "+525512345678",
                "safeListed", true, "sessionTimeoutMinutes", 180,
                "status", "Activo", "toggleLabel", "Desactivar")));
        model.put("configurationContacts", List.of(Map.of("id", "contacto", "name", "Contacto Uno",
                "email", "uno@example.com", "owner", "usuario", "group", "2")));
        List<Map<String, Object>> filterFields = List.of(
                Map.of("value", "FROM", "label", "Remitente", "selected", true));
        List<Map<String, Object>> filterOperators = List.of(
                Map.of("value", "CONTAINS", "label", "Contiene", "selected", true));
        List<Map<String, Object>> filterHeaders = List.of(
                Map.of("value", "From", "label", "Remitente", "selected", false),
                Map.of("value", "To", "label", "Destinatario", "selected", false),
                Map.of("value", "X-Spam-Flag", "label", "Detección de spam", "selected", true),
                Map.of("value", "Authentication-Results", "label", "Resultado de autenticación", "selected", false));
        List<Map<String, Object>> filterDestinations = List.of(
                Map.of("value", "Archivo", "label", "Archivo", "selected", true));
        model.put("filterDestinationAvailable", true);
        model.put("filterRulesEmpty", false);
        model.put("filterAuditEmpty", false);
        model.put("filterErrorAvailable", false);
        model.put("filterFields", filterFields);
        model.put("filterOperators", filterOperators);
        model.put("filterHeaders", filterHeaders);
        model.put("filterDestinations", filterDestinations);
        model.put("filterStatus", "IDLE");
        model.put("filterLastUid", 42);
        model.put("filterHeartbeat", "2026-07-24 10:00:00");
        model.put("filterRetries", 0);
        model.put("filterRules", List.of(Map.of("id", 1, "name", "Facturas", "priority", 100,
                "enabled", true, "value", "factura", "fields", filterFields,
                "operators", filterOperators, "headers", filterHeaders, "destinations", filterDestinations)));
        model.put("filterAudit", List.of(Map.of("uid", 42, "rule", "Facturas", "destination", "Archivo",
                "status", "MOVIDO", "attempt", 1, "date", "2026-07-24 10:00:00", "detail", "")));
        model.put("configurationFoldersEmpty", false);
        model.put("configurationFolders", List.of(Map.of("name", "Archivo", "label", "Archivo", "messages", 3)));
        model.put("attachmentsAvailable", true);
        model.put("attachments", List.of(Map.of("name", "documento.pdf", "size", "10 KB", "href", "mail?action=attachment")));
        model.put("cc", "copia@example.com");
        model.put("replyHref", "mail?action=reply&uid=1");
        model.put("replyAllHref", "mail?action=replyAll&uid=1");
        model.put("forwardHref", "mail?action=forward&uid=1");
        model.put("composeAction", true);
        model.put("query", "urgente");
        model.put("emptyText", "No se encontraron mensajes.");
        model.put("page", 1);
        model.put("pageSize", 20);
        model.put("pageSummary", "Página 1 de 1 · 1 mensajes");
        model.put("hasPrevious", false);
        model.put("hasNext", false);
        model.put("pageSizes", List.of(Map.of("label", 20, "href", "mail?size=20", "className", "active")));
        model.put("messages", List.of(Map.of("href", "mail?uid=1", "from", "Equipo", "subject", "Hola", "sent", "Hoy",
                "uid", 1L, "folder", "INBOX", "state", "No leído", "stateClass", "is-unread", "icon", "fa-envelope")));
        try {
            String html = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert html.contains("Sesión cerrada");
            assert html.contains("/gator-mail/css/gator-mail.css?v=35");
            assert html.contains("/elib/js/sweetalert2.all.min.js");
            assert html.contains("/gator-mail/js/gator-mail.js?v=14");
            assert html.contains("Nueva subcarpeta");
            assert html.contains("href=\"/gator-mail/oauth/password\"");
            assert html.contains("fontawesome-free-5.13.0-web/css/all.min.css");
            assert html.contains("&lt;user@example.com&gt;");
            assert html.contains("pattern=\"[A-Za-z0-9]{8,12}\"");
            assert html.contains("maxlength=\"12\"");
            assert html.contains("sandbox=\"\"");
            assert html.contains("srcdoc=\"&lt;script&gt;parent.alert(&#39;bad&#39;)&lt;/script&gt;\"");
            assert html.contains("Ver HTML original");
            assert html.contains("&lt;script&gt;alert(&#39;original&#39;)&lt;/script&gt;&lt;p&gt;Hola&lt;/p&gt;");
            assert !html.contains("<script>alert('original')</script>");
            assert html.contains("mail-compose-body");
            assert html.contains("id=\"mail-html-editor\"");
            assert html.contains("contenteditable=\"true\"");
            assert html.contains("name=\"format\"");
            assert html.contains("id=\"mail-format-html\"");
            assert html.contains(">HTML</button>");
            assert html.contains("name=\"format\" value=\"html\"");
            assert html.contains("placeholder=\"Escribe tu mensaje con Markdown…\"");
            assert html.indexOf("id=\"mail-format-html\"") < html.indexOf("id=\"mail-format-markdown\"");
            assert html.contains("data-md=\"link\"");
            assert html.contains("data-md=\"table\"");
            assert html.contains("id=\"mail-insert-image\"");
            assert html.contains("class=\"mail-compose-form\" method=\"post\" action=\"/gator-mail/mail\"");
            assert html.contains("enctype=\"multipart/form-data\"");
            assert html.contains("name=\"attachments\"");
            assert html.contains("name=\"images\"");
            assert html.contains("documento.pdf");
            assert html.contains("title=\"Descargar documento.pdf\"");
            assert html.contains("Reunión de proyecto");
            assert html.contains("Agenda completa");
            assert html.contains("https://example.com/reunion");
            assert html.contains("demo@example.com");
            assert html.contains("value=\"inviteReply\"");
            assert html.contains("value=\"accepted\"");
            assert html.contains("Aceptaste esta invitación.");
            assert html.contains("Sesión cerrada correctamente");
            assert html.contains(">Responder a todos</span>");
            assert html.contains(">Reenviar</span>");
            assert html.contains("Guardar borrador");
            assert html.contains("value=\"sendMessage\"");
            assert html.contains(">Enviar</span>");
            assert html.contains("name=\"cc\"");
            assert html.contains("name=\"bcc\"");
            assert html.contains("data-contact-email=\"uno@example.com\"");
            assert html.contains("No hay contactos disponibles. Agrégalos en Configuración &gt; Contactos.");
            assert html.contains(">Contactos</span>");
            assert html.contains(">Configuración</span>");
            assert html.contains(">Filtros</span>");
            assert html.contains(">Carpetas</span>");
            assert html.contains("value=\"filterSave\"");
            assert html.contains("value=\"X-Spam-Flag\" selected");
            assert html.contains("value=\"From\">Remitente</option>");
            assert html.contains("value=\"To\">Destinatario</option>");
            assert html.contains(">Detección de spam</option>");
            assert html.contains("value=\"Authentication-Results\"");
            assert html.contains(">Resultado de autenticación</option>");
            assert html.contains("Facturas");
            assert html.contains("Último UID: 42");
            assert html.contains("value=\"folderCreate\"");
            assert html.contains("3 mensajes");
            assert html.contains(">Correo</span>");
            assert html.contains(">Carpetas personales</span>");
            assert html.contains("class=\"mail-folder mail-folder-parent\" href=\"/gator-mail/mail\"");
            assert html.contains("class=\"mail-folder mail-folder-child active\"");
            assert html.contains(">Calendario</span>");
            assert html.contains(">Evento Uno</strong>");
            assert html.contains(">Total de correos</small>");
            assert html.contains("Remitentes últimos 7 días");
            assert html.contains("reciente@example.com");
            assert html.contains("Remitentes históricos");
            assert html.contains("historico@example.com");
            assert html.contains(">Julio 2026</h1>");
            assert html.contains("value=\"eventSave\"");
            assert html.contains("value=\"eventComplete\"");
            assert html.contains(">Marcar como concluido</span>");
            assert html.contains("class=\"mail-event-form\" method=\"post\" action=\"/gator-mail/mail\"");
            assert html.contains("name=\"guests\"");
            assert html.contains("data-contact-target=\"mail-event-guests\"");
            assert html.contains("value=\"7dc5dfc8-756b-4d35-b6db-dba287f46d71\"");
            assert html.contains("event.ics");
            assert html.contains("no pudo sincronizarse con el calendario externo");
            assert html.contains("class=\"mail-agenda-day is-today\"");
            assert html.contains("value=\"userSave\"");
            assert html.contains("value=\"userReset\"");
            assert html.contains("name=\"phone\"");
            assert html.contains("name=\"sessionTimeoutMinutes\"");
            assert html.contains("value=\"+525512345678\"");
            assert html.contains("value=\"userSafeList\"");
            assert html.contains(">En Global Safe List</span>");
            assert html.contains("class=\"mail-admin-row mail-admin-user\" method=\"post\" action=\"/gator-mail/mail\"");
            assert html.contains("Contraseña temporal: Abcd_1234-Efgh_5678-Ijkl");
            assert html.contains("value=\"contactSave\"");
            assert html.contains("class=\"mail-admin-row mail-admin-contact mail-admin-new\" method=\"post\" action=\"/gator-mail/mail\"");
            assert html.contains("data-message-uid=\"1\"");
            assert html.contains(">No leído</span>");
            assert html.contains("class=\"d-none mail-swal mail-swal-success\"");
            assert html.contains("id=\"mail-select-all\"");
            assert html.contains("value=\"messageDelete\"");
            assert html.contains("id=\"mail-folder-menu\"");
            assert html.contains("value=\"urgente\"");
            assert html.contains("class=\"mail-action-bar\"");
            assert html.contains("class=\"mail-action-pager\"");
            assert html.contains("class=\"mail-action-buttons\"");
            assert html.contains("form=\"mail-bulk-form\"");
            assert html.contains("Página 1 de 1");
            assert html.contains("Administrar contraseña");
            assert html.contains("Opciones de usuario");
            assert html.contains("Solicitar clave por SMS al iniciar sesión");
            assert html.contains("value=\"optionsSave\"");
            assert html.indexOf("title=\"Redactar correo\"") < html.indexOf("title=\"Administrar contraseña\"");
            assert !html.contains("mail-layout");
            assert !html.contains("mail-main");
            model.put("eventFormView", false);
            model.put("eventReadOnlyView", true);
            String readOnlyEvent = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert readOnlyEvent.contains("Calendario · Solo lectura");
            assert readOnlyEvent.contains(">Invitados</dt>");
            assert !readOnlyEvent.contains("value=\"eventSave\"");
            model.put("smsAdminAvailable", false);
            String withoutSms = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert !withoutSms.contains("value=\"userSafeList\"");
            assert !withoutSms.contains("value=\"+525512345678\"");
            model.put("logoutTitle", "Tu sesión expiró");
            model.put("logoutCopy", "Por seguridad terminamos la sesión.");
            String expired = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert expired.contains("Tu sesión expiró");
            assert expired.contains("Por seguridad terminamos la sesión.");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        String document = MailServlet.htmlDocument("/gator-mail", "<p>Hola</p>");
        assert document.contains("/gator-mail/css/mail-content.css?v=1");
        assert document.contains("img-src data:");
        assert document.contains("<p>Hola</p>");
    }
}
