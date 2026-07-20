package gator.mail;

import gator.lib.web.gui.GatorJsonView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AccessCodeSelfCheck {
    public static void main(String[] args) {
        String hash = AccessCode.hash("A1B2C3D4");
        assert AccessCode.matches("a1b2c3d4", hash);
        assert !AccessCode.matches("A1B2C3D5", hash);
        assert !AccessCode.matches("123", hash);
        assert "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM".equals(
                OAuthServlet.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assert OAuthServlet.isLocal("localhost:8080");
        assert !OAuthServlet.isLocal("erp.soft-gator.com");
        assert "Entrada".equals(ImapMailbox.label("INBOX"));
        assert "Enviados".equals(ImapMailbox.label("Sent Messages"));
        assert "Papelera".equals(ImapMailbox.label("Trash"));
        String logout = OAuthServlet.endSession("id token");
        assert logout.contains("id_token_hint=id+token");
        assert !logout.contains("post_logout_redirect_uri");
        assert !OAuthServlet.endSession("").contains("id_token_hint");

        Map<String, Object> model = new HashMap<>();
        for (String key : new String[]{"challenge", "mailboxView", "messageView", "mailContent", "empty",
                "hasMessages", "pending", "error", "loggedOut", "noticeVisible"}) model.put(key, true);
        model.put("contextPath", "/gator-mail");
        model.put("mailbox", "<user@example.com>");
        model.put("folders", List.of(Map.of("className", "active", "href", "mail?folder=INBOX", "label", "Entrada", "count", "1")));
        model.put("messages", List.of(Map.of("href", "mail?uid=1", "from", "Equipo", "subject", "Hola", "sent", "Hoy")));
        try {
            String html = new GatorJsonView().renderResource("gator-mail/screens/mail.json", model);
            assert html.contains("Sesión cerrada");
            assert html.contains("/gator-mail/css/gator-mail.css");
            assert html.contains("&lt;user@example.com&gt;");
            assert html.contains("inputmode=\"numeric\"");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
