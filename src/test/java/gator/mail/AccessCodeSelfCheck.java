package gator.mail;

import gator.lib.web.gui.GatorJsonView;
import java.util.Arrays;
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
        String logout = OAuthServlet.endSession("id token");
        assert logout.contains("id_token_hint=id+token");
        assert !logout.contains("post_logout_redirect_uri");
        assert !OAuthServlet.endSession("").contains("id_token_hint");
        assert OAuthServlet.accountUrl().endsWith("/account/#/security/signingin");
        assert "sb***to".equals(MailServlet.userHint("sbasurto"));
        assert "sb***to".equals(MailServlet.userHint("sbasurto@soft-gator.com"));
        assert "s***g".equals(MailServlet.userHint("sg" + "g"));
        assert "**".equals(MailServlet.userHint("sg"));
        assert Arrays.equals(new long[]{1, 42}, MailServlet.uids(new String[]{"1", "42"}));
        assert "asunto urgente".equals(MailServlet.searchQuery("  asunto urgente  "));
        assert MailServlet.page(null) == 1;
        assert MailServlet.page("3") == 3;
        assert MailServlet.pageSize(null) == 20;
        assert MailServlet.pageSize("100") == 100;
        List<Map<String, Object>> pagedFolders = MailServlet.folderGroups(List.of(
                new ImapMailbox.FolderInfo("INBOX", "Entrada", "", "INBOX", 0, 0, 1),
                new ImapMailbox.FolderInfo("Archive", "Archivo", "", "Archive", 0, 0, 50)), "Archive", 60);
        assert "mail?folder=INBOX&page=1&size=60".equals(pagedFolders.get(0).get("href"));
        assert "mail?folder=Archive&page=1&size=60".equals(pagedFolders.get(1).get("href"));
        boolean rejectedUid = false;
        try { MailServlet.uids(new String[]{"0"}); }
        catch (IllegalArgumentException expected) { rejectedUid = true; }
        assert rejectedUid;

        Map<String, Object> model = new HashMap<>();
        for (String key : new String[]{"challenge", "composeView", "mailboxView", "messageView", "mailContent", "empty",
                "hasMessages", "pending", "error", "loggedOut", "noticeVisible", "mailHtml"}) model.put(key, true);
        model.put("mailText", false);
        model.put("body", "<script>parent.alert('bad')</script>");
        model.put("contextPath", "/gator-mail");
        model.put("layoutClass", "mail-workspace");
        model.put("contentClass", "mail-content");
        model.put("sessionActive", true);
        model.put("mailbox", "<user@example.com>");
        model.put("accountHref", OAuthServlet.accountUrl());
        model.put("folderGroups", List.of(Map.of("className", "active", "href", "mail?folder=INBOX",
                "label", "Entrada", "count", "1", "icon", "fas fa-inbox", "folder", "INBOX",
                "draggable", false, "leaf", true, "hasChildren", false)));
        model.put("selectedFolder", "INBOX");
        model.put("folderActionsDisabled", true);
        model.put("csrf", "csrf-token");
        model.put("composeTo", "");
        model.put("composeCc", "");
        model.put("composeBcc", "");
        model.put("composeSubject", "");
        model.put("composeBody", "");
        model.put("contactsAvailable", true);
        model.put("contacts", List.of(Map.of("name", "Contacto Uno", "email", "uno@example.com")));
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
                "uid", 1L, "folder", "INBOX")));
        try {
            String html = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert html.contains("Sesión cerrada");
            assert html.contains("/gator-mail/css/gator-mail.css?v=18");
            assert html.contains("/gator-mail/js/gator-mail.js?v=6");
            assert html.contains("fontawesome-free-5.13.0-web/css/all.min.css");
            assert html.contains("&lt;user@example.com&gt;");
            assert html.contains("pattern=\"[A-Za-z0-9]{8,12}\"");
            assert html.contains("maxlength=\"12\"");
            assert html.contains("sandbox=\"\"");
            assert html.contains("srcdoc=\"&lt;script&gt;parent.alert(&#39;bad&#39;)&lt;/script&gt;\"");
            assert html.contains("mail-compose-body");
            assert html.contains("Guardar borrador");
            assert html.contains("value=\"sendMessage\"");
            assert html.contains(">Enviar</span>");
            assert html.contains("name=\"cc\"");
            assert html.contains("name=\"bcc\"");
            assert html.contains("data-contact-email=\"uno@example.com\"");
            assert html.contains("data-message-uid=\"1\"");
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
            assert !html.contains("mail-layout");
            assert !html.contains("mail-main");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        String document = MailServlet.htmlDocument("/gator-mail", "<p>Hola</p>");
        assert document.contains("/gator-mail/css/mail-content.css?v=1");
        assert document.contains("<p>Hola</p>");
    }
}
