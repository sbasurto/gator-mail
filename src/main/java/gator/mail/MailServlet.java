package gator.mail;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gator.lib.db.ADO;
import gator.lib.db.GappSQLStatement;
import gator.lib.db.helpers.GappDBHelper;
import gator.lib.web.gui.GatorJsonView;
import jakarta.mail.AuthenticationFailedException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

@MultipartConfig(fileSizeThreshold = 1_048_576, maxFileSize = 26_214_400, maxRequestSize = 31_457_280)
public final class MailServlet extends HttpServlet {
    private static final long CODE_LIFETIME_MS = 300_000;
    private static final long RESEND_WAIT_MS = 30_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Parser MARKDOWN = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_HTML = HtmlRenderer.builder().build();
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
        request.setCharacterEncoding(StandardCharsets.UTF_8);
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
            } else if (manageFolders(request, response, session, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else if (moveMessage(request, response, session, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else if (deleteMessages(request, response, session, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else if (sendMessage(request, response, session, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else if (saveDraft(request, response, session, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else if (downloadAttachment(request, response, mailbox, OAuthServlet.accessToken(request))) {
                return;
            } else {
                mailboxModel(model, request, mailbox, OAuthServlet.accessToken(request));
            }
        } catch (IllegalArgumentException error) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, error.getMessage());
            return;
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
        model.put("accountHref", OAuthServlet.accountUrl());
        model.put("challenge", false);
        model.put("mailboxView", false);
        model.put("messageView", false);
        model.put("composeView", false);
        model.put("mailContent", false);
        model.put("mailHtml", false);
        model.put("mailText", false);
        model.put("attachmentsAvailable", false);
        model.put("composeAction", false);
        model.put("folderActionsDisabled", true);
        model.put("selectedFolder", "");
        model.put("csrf", "");
        model.put("composeTo", "");
        model.put("composeCc", "");
        model.put("composeBcc", "");
        model.put("composeSubject", "");
        model.put("composeBody", "");
        model.put("contactsAvailable", false);
        model.put("query", "");
        model.put("emptyText", "No hay mensajes en esta carpeta.");
        model.put("empty", false);
        model.put("hasMessages", false);
        model.put("pending", false);
        model.put("error", false);
        model.put("loggedOut", false);
        model.put("noticeVisible", false);
        model.put("sendNotice", false);
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
        String query = searchQuery(request.getParameter("q"));
        int size = rememberedPageSize(request, request.getSession());
        model.put("folderGroups", folderGroups(folders, folderName, size));
        model.put("selectedFolder", folderName);
        model.put("pageSize", size);
        model.put("folderActionsDisabled", folderName.equalsIgnoreCase("INBOX"));
        model.put("csrf", csrf(request.getSession()));
        model.put("query", query);
        model.put("mailContent", true);
        model.put("composeAction", true);
        model.put("layoutClass", "mail-workspace");
        model.put("contentClass", "mail-content");
        model.put("sendNotice", "1".equals(request.getParameter("sent")));

        if ("compose".equals(request.getParameter("action"))) {
            model.put("composeView", true);
            model.put("composeAction", false);
            contactsModel(model, mailbox);
            return;
        }

        int requestedPage = page(request.getParameter("page"));

        String uid = request.getParameter("uid");
        if (uid != null && uid.matches("[0-9]+")) {
            ImapMailbox.Mail mail = imap.read(mailbox, folderName, Long.parseLong(uid), accessToken);
            if (mail == null) {
                model.put("error", true);
                return;
            }
            model.put("messageView", true);
            model.put("folderHref", mailboxHref(folderName, query, requestedPage, size));
            model.put("folderLabel", selected.label());
            model.put("subject", mail.subject());
            model.put("from", mail.from());
            model.put("to", mail.to());
            model.put("sent", DATE.format(mail.sent()));
            model.put("body", mail.html() ? htmlDocument(request.getContextPath(), mail.body()) : mail.body());
            model.put("mailHtml", mail.html());
            model.put("mailText", !mail.html());
            List<Map<String, Object>> attachments = new ArrayList<>();
            for (ImapMailbox.Attachment attachment : mail.attachments()) attachments.add(Map.of(
                    "name", attachment.name(), "size", fileSize(attachment.size()),
                    "href", "mail?action=attachment&folder=" + url(folderName) + "&uid=" + uid
                            + "&part=" + url(attachment.part())));
            model.put("attachments", attachments);
            model.put("attachmentsAvailable", !attachments.isEmpty());
            return;
        }

        model.put("mailboxView", true);
        model.put("folderLabel", selected.label());
        ImapMailbox.MessagePage result = imap.messages(mailbox, folderName, query, requestedPage, size, accessToken);
        List<ImapMailbox.Summary> messages = result.messages();
        int pages = Math.max(1, (result.total() + result.size() - 1) / result.size());
        model.put("refreshHref", mailboxHref(folderName, query, result.page(), result.size()));
        model.put("page", result.page());
        model.put("pageSize", result.size());
        model.put("pageSummary", "Página " + result.page() + " de " + pages + " · " + result.total() + " mensajes");
        model.put("hasPrevious", result.page() > 1);
        model.put("hasNext", result.page() < pages);
        model.put("previousHref", mailboxHref(folderName, query, result.page() - 1, result.size()));
        model.put("nextHref", mailboxHref(folderName, query, result.page() + 1, result.size()));
        List<Map<String, Object>> sizes = new ArrayList<>();
        for (int option = 20; option <= 100; option += 20)
            sizes.add(Map.of("label", option, "href", mailboxHref(folderName, query, 1, option),
                    "className", option == result.size() ? "active" : ""));
        model.put("pageSizes", sizes);
        model.put("empty", messages.isEmpty());
        model.put("emptyText", query.isBlank() ? "No hay mensajes en esta carpeta." : "No se encontraron mensajes.");
        model.put("hasMessages", !messages.isEmpty());
        List<Map<String, Object>> messageModels = new ArrayList<>();
        for (ImapMailbox.Summary mail : messages) {
            messageModels.add(Map.of(
                    "href", mailboxHref(folderName, query, result.page(), result.size()) + "&uid=" + mail.uid(),
                    "from", mail.from(), "subject", mail.subject(), "sent", DATE.format(mail.sent()),
                    "uid", mail.uid(), "folder", folderName));
        }
        model.put("messages", messageModels);
    }

    private boolean deleteMessages(HttpServletRequest request, HttpServletResponse response, HttpSession session,
            String mailbox, String accessToken) throws Exception {
        if (!"messageDelete".equals(request.getParameter("action"))) return false;
        if (!"POST".equals(request.getMethod()) || !csrf(session).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        String folder = request.getParameter("folder");
        imap.deleteMessages(mailbox, folder, uids(request.getParameterValues("uid")), accessToken);
        String query = searchQuery(request.getParameter("q"));
        response.sendRedirect(mailboxHref(folder, query, page(request.getParameter("page")),
                pageSize(request.getParameter("size"))));
        return true;
    }

    private boolean moveMessage(HttpServletRequest request, HttpServletResponse response, HttpSession session,
            String mailbox, String accessToken) throws Exception {
        if (!"messageMove".equals(request.getParameter("action"))) return false;
        if (!"POST".equals(request.getMethod()) || !csrf(session).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        try {
            String source = request.getParameter("source");
            imap.moveMessage(mailbox, source, request.getParameter("target"),
                    Long.parseLong(request.getParameter("uid")), accessToken);
            response.sendRedirect("mail?folder=" + url(source));
        } catch (IllegalArgumentException error) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No fue posible mover el mensaje");
        }
        return true;
    }

    private boolean saveDraft(HttpServletRequest request, HttpServletResponse response, HttpSession session,
            String mailbox, String accessToken) throws Exception {
        if (!"saveDraft".equals(request.getParameter("action"))) return false;
        if (!"POST".equals(request.getMethod()) || !csrf(session).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        try {
            String markdown = request.getParameter("body");
            String html = ImapMailbox.sanitizeHtml(MARKDOWN_HTML.render(MARKDOWN.parse(markdown == null ? "" : markdown)));
            String drafts = imap.saveDraft(mailbox, request.getParameter("to"), request.getParameter("cc"),
                    request.getParameter("bcc"), request.getParameter("subject"), markdown, html,
                    uploads(request), accessToken);
            response.sendRedirect("mail?folder=" + url(drafts));
        } catch (IllegalArgumentException error) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, error.getMessage());
        }
        return true;
    }

    private boolean sendMessage(HttpServletRequest request, HttpServletResponse response, HttpSession session,
            String mailbox, String accessToken) throws Exception {
        if (!"sendMessage".equals(request.getParameter("action"))) return false;
        if (!"POST".equals(request.getMethod()) || !csrf(session).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        String markdown = request.getParameter("body");
        String html = ImapMailbox.sanitizeHtml(MARKDOWN_HTML.render(MARKDOWN.parse(markdown == null ? "" : markdown)));
        String folder = imap.send(mailbox, request.getParameter("to"), request.getParameter("cc"),
                request.getParameter("bcc"), request.getParameter("subject"), markdown, html,
                uploads(request), accessToken);
        response.sendRedirect("mail?folder=" + url(folder) + "&sent=1");
        return true;
    }

    private boolean downloadAttachment(HttpServletRequest request, HttpServletResponse response, String mailbox,
            String accessToken) throws Exception {
        if (!"attachment".equals(request.getParameter("action"))) return false;
        long uid = Long.parseLong(request.getParameter("uid"));
        if (uid <= 0) throw new IllegalArgumentException("Adjunto inválido");
        ImapMailbox.Download download = imap.download(mailbox, request.getParameter("folder"), uid,
                request.getParameter("part"), accessToken);
        response.reset();
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + url(download.name()));
        response.setContentLength(download.data().length);
        response.getOutputStream().write(download.data());
        return true;
    }

    private static List<ImapMailbox.Upload> uploads(HttpServletRequest request) throws Exception {
        List<ImapMailbox.Upload> result = new ArrayList<>();
        long total = 0;
        for (Part part : request.getParts()) {
            boolean inline = "images".equals(part.getName());
            if (!inline && !"attachments".equals(part.getName())) continue;
            if (part.getSubmittedFileName() == null || part.getSize() == 0) continue;
            if (result.size() >= 10 || part.getSize() > 25 * 1024 * 1024 || (total += part.getSize()) > 25 * 1024 * 1024)
                throw new IllegalArgumentException("Máximo 10 archivos y 25 MB en total");
            String name = Path.of(part.getSubmittedFileName().replace('\\', '/')).getFileName().toString()
                    .replaceAll("[\\p{Cntrl}]", "").strip();
            if (name.isEmpty() || name.length() > 180) throw new IllegalArgumentException("Nombre de archivo inválido");
            byte[] data = part.getInputStream().readNBytes(25 * 1024 * 1024 + 1);
            String type = inline ? part.getContentType().split(";", 2)[0].toLowerCase(java.util.Locale.ROOT)
                    : "application/octet-stream";
            if (inline && !ImapMailbox.safeImage(type, data))
                throw new IllegalArgumentException("Solo se permiten imágenes PNG, JPEG o GIF válidas");
            result.add(new ImapMailbox.Upload(name, type, data, inline));
        }
        return result;
    }

    static String htmlDocument(String contextPath, String body) {
        return "<!doctype html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'self'; img-src data:\">"
                + "<link rel=\"stylesheet\" href=\"" + contextPath + "/css/mail-content.css?v=1\"></head>"
                + "<body class=\"gator-email\">" + body + "</body></html>";
    }

    private boolean manageFolders(HttpServletRequest request, HttpServletResponse response, HttpSession session,
            String mailbox, String accessToken) throws Exception {
        String action = request.getParameter("action");
        if (action == null || !action.startsWith("folder")) return false;
        if (!"POST".equals(request.getMethod()) || !csrf(session).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return true;
        }
        try {
            String folder = request.getParameter("folder");
            switch (action) {
                case "folderRename" -> response.sendRedirect("mail?folder="
                        + url(imap.rename(mailbox, folder, request.getParameter("name"), accessToken)));
                case "folderMove" -> response.sendRedirect("mail?folder="
                        + url(imap.move(mailbox, folder, request.getParameter("parent"), accessToken)));
                case "folderDelete" -> {
                    imap.delete(mailbox, folder, accessToken);
                    response.sendRedirect("mail?folder=INBOX");
                }
                default -> response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        } catch (IllegalArgumentException error) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, error.getMessage());
        }
        return true;
    }

    private static String csrf(HttpSession session) {
        String value = (String) session.getAttribute("mail.csrf");
        if (value == null) {
            value = UUID.randomUUID().toString();
            session.setAttribute("mail.csrf", value);
        }
        return value;
    }

    private JsonObject call(String procedure, JsonObject value) {
        GappSQLStatement statement = new GappSQLStatement();
        statement.setStoreProcedure(procedure);
        statement.addParam(gson.toJson(value));
        String config = getServletContext().getInitParameter("gappConfigFile");
        return JsonParser.parseString(new GappDBHelper(config).executeStore(statement)).getAsJsonObject();
    }

    private void contactsModel(Map<String, Object> model, String mailbox) {
        ADO database = null;
        try {
            String config = getServletContext().getInitParameter("gappContactsDbFile");
            if (config == null || config.isBlank()) return;
            GappSQLStatement statement = new GappSQLStatement();
            statement.setStoreProcedure("mail_fn_get_contactos");
            statement.addParam(mailbox);
            database = new ADO(config, true);
            JsonObject result = JsonParser.parseString(database.execStore(statement)).getAsJsonObject();
            if (!"0".equals(result.get("codigo").getAsString())) return;
            List<Map<String, Object>> contacts = new ArrayList<>();
            for (JsonElement element : result.getAsJsonArray("contactos")) {
                JsonObject contact = element.getAsJsonObject();
                contacts.add(Map.of("name", contact.get("nombre").getAsString(),
                        "email", contact.get("email").getAsString()));
            }
            model.put("contacts", contacts);
            model.put("contactsAvailable", !contacts.isEmpty());
        } catch (Exception error) {
            getServletContext().log("No fue posible cargar el directorio de contactos", error);
        } finally {
            if (database != null) database.close();
        }
    }

    private static JsonObject json(String key, String value) {
        JsonObject json = new JsonObject();
        json.addProperty(key, value);
        return json;
    }

    static List<Map<String, Object>> folderGroups(List<ImapMailbox.FolderInfo> folders, String selected, int size) {
        Map<String, List<ImapMailbox.FolderInfo>> children = new LinkedHashMap<>();
        for (ImapMailbox.FolderInfo folder : folders)
            if (folder.depth() > 0) children.computeIfAbsent(folder.root(), ignored -> new ArrayList<>()).add(folder);

        List<Map<String, Object>> groups = new ArrayList<>();
        for (ImapMailbox.FolderInfo folder : folders) {
            if (folder.depth() > 0) continue;
            List<ImapMailbox.FolderInfo> nested = children.remove(folder.name());
            if (nested == null) {
                Map<String, Object> leaf = folderModel(folder, selected, false, size);
                leaf.put("leaf", true);
                leaf.put("hasChildren", false);
                groups.add(leaf);
                continue;
            }
            List<Map<String, Object>> submenu = new ArrayList<>();
            Map<String, Object> folderEntry = folderModel(folder, selected, true, size);
            folderEntry.put("label", "Mensajes");
            submenu.add(folderEntry);
            for (ImapMailbox.FolderInfo child : nested) submenu.add(folderModel(child, selected, true, size));
            Map<String, Object> group = folderModel(folder, selected, false, size);
            group.put("leaf", false);
            group.put("hasChildren", true);
            group.put("open", folder.name().equals(selected)
                    || nested.stream().anyMatch(child -> child.name().equals(selected)));
            group.put("children", submenu);
            groups.add(group);
        }
        return groups;
    }

    private static Map<String, Object> folderModel(ImapMailbox.FolderInfo folder, String selected, boolean child,
            int size) {
        Map<String, Object> model = new HashMap<>();
        model.put("className", (folder.name().equals(selected) ? "active " : "")
                + (child ? "mail-folder-child" : ""));
        model.put("href", mailboxHref(folder.name(), "", 1, size));
        model.put("label", folder.label());
        model.put("count", (folder.unread() > 0 ? folder.unread() + " sin leer · " : "") + folder.total());
        model.put("icon", folderIcon(folder.label()));
        model.put("folder", folder.name());
        model.put("draggable", !folder.name().equalsIgnoreCase("INBOX"));
        return model;
    }

    private static String folderIcon(String label) {
        return switch (label) {
            case "Entrada" -> "fas fa-inbox";
            case "Enviados" -> "fas fa-paper-plane";
            case "Borradores" -> "fas fa-file-alt";
            case "Spam" -> "fas fa-shield-alt";
            case "Papelera" -> "fas fa-trash-alt";
            default -> "fas fa-folder";
        };
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String fileSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576d);
    }

    private static String mailboxHref(String folder, String query, int page, int size) {
        return "mail?folder=" + url(folder) + (query.isBlank() ? "" : "&q=" + url(query))
                + "&page=" + page + "&size=" + size;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static long[] uids(String[] values) {
        if (values == null || values.length == 0 || values.length > 100)
            throw new IllegalArgumentException("Selecciona entre 1 y 100 mensajes");
        long[] result = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            try { result[i] = Long.parseLong(values[i]); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Selección de mensajes inválida"); }
            if (result[i] <= 0) throw new IllegalArgumentException("Selección de mensajes inválida");
        }
        return result;
    }

    static String searchQuery(String value) {
        String query = value == null ? "" : value.strip();
        if (query.length() > 100 || query.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Búsqueda inválida");
        return query;
    }

    static int page(String value) {
        if (value == null || value.isBlank()) return 1;
        try {
            int page = Integer.parseInt(value);
            if (page > 0 && page <= 100_000) return page;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Página inválida");
    }

    static int pageSize(String value) {
        if (value == null || value.isBlank()) return 20;
        try {
            int size = Integer.parseInt(value);
            if (size >= 20 && size <= 100 && size % 20 == 0) return size;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Tamaño de página inválido");
    }

    private static int rememberedPageSize(HttpServletRequest request, HttpSession session) {
        String value = request.getParameter("size");
        if (value != null && !value.isBlank()) {
            int size = pageSize(value);
            session.setAttribute("mail.pageSize", size);
            return size;
        }
        Object saved = session.getAttribute("mail.pageSize");
        return saved instanceof Integer size ? pageSize(String.valueOf(size)) : 20;
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
