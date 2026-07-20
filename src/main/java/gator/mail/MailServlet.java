package gator.mail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gator.lib.db.GappSQLStatement;
import gator.lib.db.helpers.GappDBHelper;
import gator.lib.web.gui.GatorJsonView;
import jakarta.mail.AuthenticationFailedException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MailServlet extends HttpServlet {
    private static final long CODE_LIFETIME_MS = 300_000;
    private static final long RESEND_WAIT_MS = 30_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final Gson gson = new Gson();
    private final GatorJsonView view = new GatorJsonView();
    private final ImapMailbox imap = new ImapMailbox();

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        process(request, response);
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        process(request, response);
    }

    private void process(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepare(response);
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("oidc.user") == null) {
            response.sendRedirect(request.getContextPath() + "/oauth/login");
            return;
        }

        Map<String, Object> model = baseModel(request, "");
        String user = String.valueOf(session.getAttribute("oidc.user"));
        try {
            JsonObject access = call("app_fn_has_mail_access", json("usuario", user));
            if (!"0".equals(access.get("codigo").getAsString())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            String mailbox = access.get("email").getAsString();
            model.put("mailbox", mailbox);
            String notice = challenge(request, session, user);
            if (!Boolean.TRUE.equals(session.getAttribute("mail.challenge.verified"))) {
                challengeModel(model, session, notice);
            } else {
                mailboxModel(model, request, mailbox, OAuthServlet.accessToken(request));
            }
        } catch (Exception error) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            model.put(causedBy(error, AuthenticationFailedException.class) ? "pending" : "error", true);
        }
        response.getWriter().print(view.renderResource("gator-mail/screens/mail.json", model));
    }

    static void prepare(HttpServletResponse response) {
        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'");
    }

    static Map<String, Object> baseModel(HttpServletRequest request, String mailbox) {
        Map<String, Object> model = new HashMap<>();
        model.put("contextPath", request.getContextPath());
        model.put("mailbox", mailbox);
        model.put("challenge", false);
        model.put("mailboxView", false);
        model.put("messageView", false);
        model.put("mailContent", false);
        model.put("empty", false);
        model.put("hasMessages", false);
        model.put("pending", false);
        model.put("error", false);
        model.put("loggedOut", false);
        model.put("noticeVisible", false);
        model.put("layoutClass", "container-fluid");
        model.put("contentClass", "container py-4");
        model.put("sessionActive", true);
        return model;
    }

    private String challenge(HttpServletRequest request, HttpSession session, String user) {
        if (Boolean.TRUE.equals(session.getAttribute("mail.challenge.verified"))) return "";
        String token = (String) session.getAttribute("mail.challenge.token");
        if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute("mail.challenge.token", token);
        }
        long now = System.currentTimeMillis();
        if ("verify".equals(request.getParameter("action"))) {
            if (!token.equals(request.getParameter("token"))) return "Solicitud inválida";
            long expires = number(session.getAttribute("mail.challenge.expires"));
            int attempts = (int) number(session.getAttribute("mail.challenge.attempts"));
            if (now > expires) return "La clave expiró";
            if (attempts >= MAX_ATTEMPTS) return "Demasiados intentos";
            if (AccessCode.matches(request.getParameter("code"), (String) session.getAttribute("mail.challenge.hash"))) {
                session.setAttribute("mail.challenge.verified", true);
                session.removeAttribute("mail.challenge.hash");
                return "";
            }
            session.setAttribute("mail.challenge.attempts", attempts + 1);
            return "La clave no es válida";
        }
        boolean resend = "resend".equals(request.getParameter("action"));
        long lastSent = number(session.getAttribute("mail.challenge.sent"));
        if (resend && (!token.equals(request.getParameter("token")) || now - lastSent < RESEND_WAIT_MS))
            return "Espera 30 segundos antes de solicitar otra clave";
        if (session.getAttribute("mail.challenge.hash") == null || resend) {
            JsonObject requestJson = json("usuario", user);
            requestJson.addProperty("smsOnly", true);
            requestJson.addProperty("application", "Gator Mail");
            requestJson.addProperty("userHint", userHint(user));
            JsonObject result = call("app_fn_send_login_challenge", requestJson);
            if (!"0".equals(result.get("codigo").getAsString()) || !result.get("phoneSent").getAsBoolean())
                return result.has("mensaje") ? result.get("mensaje").getAsString() : "No fue posible enviar la clave por SMS";
            session.setAttribute("mail.challenge.hash", result.get("challengeHash").getAsString());
            session.setAttribute("mail.challenge.expires", Math.min(result.get("expiresAt").getAsLong(), now + CODE_LIFETIME_MS));
            session.setAttribute("mail.challenge.attempts", 0L);
            session.setAttribute("mail.challenge.sent", now);
            return "Enviamos una clave temporal por SMS";
        }
        return "";
    }

    private void challengeModel(Map<String, Object> model, HttpSession session, String notice) {
        long remaining = Math.max(0, (RESEND_WAIT_MS - (System.currentTimeMillis()
                - number(session.getAttribute("mail.challenge.sent"))) + 999) / 1000);
        model.put("challenge", true);
        model.put("token", String.valueOf(session.getAttribute("mail.challenge.token")));
        model.put("notice", notice);
        model.put("noticeVisible", !notice.isBlank());
        model.put("resendDisabled", remaining > 0);
        model.put("resendWait", remaining > 0 ? "Disponible en " + remaining + " s" : "");
    }

    private void mailboxModel(Map<String, Object> model, HttpServletRequest request, String mailbox,
            String accessToken) throws Exception {
        List<ImapMailbox.FolderInfo> folders = imap.folders(mailbox, accessToken);
        String requested = request.getParameter("folder");
        ImapMailbox.FolderInfo selected = folders.stream().filter(folder -> folder.name().equals(requested))
                .findFirst().orElseGet(() -> folders.stream().filter(folder -> folder.name().equalsIgnoreCase("INBOX"))
                        .findFirst().orElseThrow());
        String folderName = selected.name();
        List<Map<String, Object>> folderModels = new ArrayList<>();
        for (ImapMailbox.FolderInfo folder : folders) {
            folderModels.add(Map.of(
                    "className", folder.name().equals(folderName) ? "active" : "",
                    "href", "mail?folder=" + url(folder.name()),
                    "label", folder.label(),
                    "count", (folder.unread() > 0 ? folder.unread() + " sin leer · " : "") + folder.total()));
        }
        model.put("folders", folderModels);
        model.put("mailContent", true);
        model.put("layoutClass", "mail-workspace");
        model.put("contentClass", "mail-content");

        String uid = request.getParameter("uid");
        if (uid != null && uid.matches("[0-9]+")) {
            ImapMailbox.Mail mail = imap.read(mailbox, folderName, Long.parseLong(uid), accessToken);
            if (mail == null) {
                model.put("error", true);
                return;
            }
            model.put("messageView", true);
            model.put("folderHref", "mail?folder=" + url(folderName));
            model.put("folderLabel", selected.label());
            model.put("subject", mail.subject());
            model.put("from", mail.from());
            model.put("to", mail.to());
            model.put("sent", DATE.format(mail.sent()));
            model.put("body", mail.body());
            return;
        }

        model.put("mailboxView", true);
        model.put("folderLabel", selected.label());
        model.put("refreshHref", "mail?folder=" + url(folderName));
        List<ImapMailbox.Summary> messages = imap.messages(mailbox, folderName, accessToken);
        model.put("empty", messages.isEmpty());
        model.put("hasMessages", !messages.isEmpty());
        List<Map<String, Object>> messageModels = new ArrayList<>();
        for (ImapMailbox.Summary mail : messages) {
            messageModels.add(Map.of(
                    "href", "mail?folder=" + url(folderName) + "&uid=" + mail.uid(),
                    "from", mail.from(), "subject", mail.subject(), "sent", DATE.format(mail.sent())));
        }
        model.put("messages", messageModels);
    }

    private JsonObject call(String procedure, JsonObject value) {
        GappSQLStatement statement = new GappSQLStatement();
        statement.setStoreProcedure(procedure);
        statement.addParam(gson.toJson(value));
        String config = getServletContext().getInitParameter("gappConfigFile");
        return JsonParser.parseString(new GappDBHelper(config).executeStore(statement)).getAsJsonObject();
    }

    private static JsonObject json(String key, String value) {
        JsonObject json = new JsonObject();
        json.addProperty(key, value);
        return json;
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static String userHint(String user) {
        String value = user == null ? "" : user.strip();
        int at = value.indexOf('@');
        String identity = at > 0 ? value.substring(0, at) : value;
        if (identity.length() <= 2) return "*".repeat(identity.length());
        if (identity.length() <= 4) return identity.charAt(0) + "***" + identity.substring(identity.length() - 1);
        return identity.substring(0, 2) + "***" + identity.substring(identity.length() - 2);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (type.isInstance(current)) return true;
        return false;
    }
}
