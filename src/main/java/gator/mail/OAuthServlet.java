package gator.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.*;
import gator.lib.web.gui.GatorSessionPages;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.Map;

public final class OAuthServlet extends HttpServlet {
    static String issuer() {
        String value = env("GATOR_MAIL_OAUTH_ISSUER", env("GATOR_OIDC_ISSUER", ""));
        if (value.isBlank()) throw new IllegalStateException("Falta GATOR_MAIL_OAUTH_ISSUER o GATOR_OIDC_ISSUER");
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalStateException("El issuer OAuth debe ser una URL HTTPS válida");
        return value.replaceAll("/+$", "");
    }
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("Pragma", "no-cache");
        if ("/callback".equals(req.getPathInfo())) callback(req, res);
        else if ("/logout".equals(req.getPathInfo())) confirmLogout(req, res);
        else if ("/logged-out".equals(req.getPathInfo())) loggedOut(req, res);
        else login(req, res, "/password".equals(req.getPathInfo()) ? "UPDATE_PASSWORD" : "");
    }

    private void confirmLogout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        MailServlet.prepare(res);
        HttpSession session = req.getSession(true);
        res.getWriter().print(GatorSessionPages.confirmation(req.getContextPath(), language(session),
                req.getContextPath() + "/oauth/logout", "csrf", MailServlet.csrf(session),
                req.getContextPath() + "/mail"));
    }

    @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!"/logout".equals(req.getPathInfo())) { res.sendError(405); return; }
        HttpSession session = req.getSession(false);
        Object expected = session == null ? null : session.getAttribute("mail.csrf");
        if (!(expected instanceof String csrf) || !validState(csrf, req.getParameter("csrf"))) {
            res.sendError(403); return;
        }
        String idToken = String.valueOf(session.getAttribute("oidc.id"));
        String language = language(session);
        session.invalidate();
        res.setHeader("Cache-Control", "no-store");
        if (idToken.isBlank() || "null".equals(idToken)) {
            res.sendRedirect(req.getContextPath() + "/oauth/logged-out?language=" + enc(language));
            return;
        }
        String callback = redirect(req);
        String returnUrl = callback.substring(0, callback.length() - "callback".length()) + "logged-out?language=" + enc(language);
        res.sendRedirect(endSession(idToken) + "&post_logout_redirect_uri=" + enc(returnUrl));
    }

    private static String language(HttpSession session) {
        Object value = session == null ? null : session.getAttribute("oidc.locale");
        return value instanceof String tag && java.util.Locale.forLanguageTag(tag.replace('_', '-'))
                .getLanguage().equals("en") ? "en" : "es";
    }

    private void loggedOut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        MailServlet.prepare(res);
        res.getWriter().print(GatorSessionPages.signedOut(req.getContextPath(),
                req.getParameter("language"), "true".equals(req.getParameter("expired"))));
    }
    private void login(HttpServletRequest req, HttpServletResponse res, String action) throws IOException {
        HttpSession s = req.getSession(true); String state = random(24), verifier = random(48);
        s.setAttribute("oidc.state", state); s.setAttribute("oidc.verifier", verifier);
        res.sendRedirect(issuer() + "/protocol/openid-connect/auth?response_type=code&scope=openid%20profile%20email&client_id=gator-mail&redirect_uri="
                + enc(redirect(req)) + "&state=" + enc(state) + "&code_challenge_method=S256&code_challenge=" + enc(challenge(verifier))
                + (action.isBlank() ? "" : "&kc_action=" + enc(action)));
    }
    private void callback(HttpServletRequest req, HttpServletResponse res) throws IOException {
        HttpSession s = req.getSession(false);
        if (s == null || !validState(String.valueOf(s.getAttribute("oidc.state")), req.getParameter("state"))) {
            res.sendRedirect(req.getContextPath() + "/oauth/login");
            return;
        }
        try {
            JsonObject token = token("grant_type=authorization_code&client_id=gator-mail&code=" + enc(req.getParameter("code"))
                    + "&redirect_uri=" + enc(redirect(req)) + "&code_verifier=" + enc(String.valueOf(s.getAttribute("oidc.verifier"))));
            HttpRequest infoReq = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/userinfo"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token.get("access_token").getAsString()).GET().build();
            HttpResponse<String> info = HTTP.send(infoReq, HttpResponse.BodyHandlers.ofString());
            if (info.statusCode() != 200 || info.body().length() > 65_536) throw new IllegalStateException();
            JsonObject user = JsonParser.parseString(info.body()).getAsJsonObject();
            saveAuthenticatedSession(req, token, user);
            res.sendRedirect(req.getContextPath() + "/mail");
        } catch (Exception e) { res.sendError(503, "No fue posible completar el acceso OAuth"); }
    }
    static void saveAuthenticatedSession(HttpServletRequest request, JsonObject token, JsonObject user) {
        String subject = authenticatedSubject(user);
        if (!user.has("preferred_username") || !user.get("preferred_username").isJsonPrimitive()
                || !user.get("preferred_username").getAsJsonPrimitive().isString()
                || user.get("preferred_username").getAsString().isBlank())
            throw new IllegalArgumentException("Missing username");
        String username = user.get("preferred_username").getAsString();
        String locale = user.has("locale") && !user.get("locale").isJsonNull()
                ? user.get("locale").getAsString() : null;
        HttpSession session = request.getSession(false);
        synchronized (session) {
            save(session, token);
            if (!token.has("refresh_token")) session.removeAttribute("oidc.refresh");
            if (!token.has("id_token")) session.removeAttribute("oidc.id");
            request.changeSessionId();
            if (!subject.equals(session.getAttribute("oidc.subject"))
                    || !username.equals(session.getAttribute("oidc.user"))) {
                java.util.Collections.list(session.getAttributeNames()).stream()
                        .filter(name -> name.startsWith("mail.")).forEach(session::removeAttribute);
            }
            session.setAttribute("oidc.subject", subject);
            session.setAttribute("oidc.user", username);
            if (locale == null) session.removeAttribute("oidc.locale");
            else session.setAttribute("oidc.locale", locale);
            session.removeAttribute("oidc.state");
            session.removeAttribute("oidc.verifier");
        }
    }

    static String accessToken(HttpServletRequest req) throws Exception {
        HttpSession s = req.getSession(false);
        if (s == null) throw new ReauthenticationRequired();
        synchronized (s) {
            if (!(s.getAttribute("oidc.expires") instanceof Number expires)
                    || s.getAttribute("oidc.access") == null) throw new ReauthenticationRequired();
            if (System.currentTimeMillis() >= expires.longValue() - 30_000) {
                Object refresh = s.getAttribute("oidc.refresh");
                if (refresh == null) throw new ReauthenticationRequired();
                save(s, token("grant_type=refresh_token&client_id=gator-mail&refresh_token="
                        + enc(String.valueOf(refresh))));
            }
            return String.valueOf(s.getAttribute("oidc.access"));
        }
    }

    static JsonObject identityRequest(HttpServletRequest request, JsonObject body) {
        try {
            HttpRequest info = HttpRequest.newBuilder(URI.create(issuer()+"/protocol/openid-connect/userinfo"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization","Bearer "+accessToken(request)).GET().build();
            var response = HTTP.send(info,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()==401 || response.statusCode()==403) throw new ReauthenticationRequired();
            if (response.statusCode()!=200 || response.body().length()>65536) throw new IOException("Identity service unavailable");
            JsonObject identity = JsonParser.parseString(response.body()).getAsJsonObject();
            HttpSession session = request.getSession(false);
            String subject = authenticatedSubject(identity);
            if (session.getAttribute("oidc.subject") instanceof String previous && !previous.equals(subject))
                throw new ReauthenticationRequired();
            session.setAttribute("oidc.subject",subject);
            return identityRequest(body,identity);
        } catch (Exception failure) { throw new IllegalStateException("No fue posible verificar la identidad",failure); }
    }

    static String authenticatedSubject(JsonObject authenticatedUser) {
        if (!authenticatedUser.has("sub") || !authenticatedUser.get("sub").isJsonPrimitive()
                || !authenticatedUser.get("sub").getAsJsonPrimitive().isString()
                || authenticatedUser.get("sub").getAsString().isBlank())
            throw new IllegalArgumentException("Missing subject");
        return authenticatedUser.get("sub").getAsString();
    }

    static JsonObject identityRequest(JsonObject body, JsonObject authenticatedUser) {
        String subject = authenticatedSubject(authenticatedUser);
        JsonObject result = body.deepCopy();
        result.addProperty("issuer", issuer());
        result.addProperty("subject", subject);
        return result;
    }

    static boolean active(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("oidc.access") == null) return false;
        try {
            HttpRequest info = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/userinfo"))
                    .header("Authorization", "Bearer " + session.getAttribute("oidc.access")).GET().build();
            return HTTP.send(info, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception error) {
            return false;
        }
    }
    private static JsonObject token(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return tokenResponse(res.statusCode(), res.body());
    }
    static JsonObject tokenResponse(int status, String body) throws Exception {
        if (status == 400) {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject();
            if (error.has("error") && "invalid_grant".equals(error.get("error").getAsString()))
                throw new ReauthenticationRequired();
        }
        if (status != 200) throw new IOException("No fue posible renovar OAuth: HTTP " + status);
        return JsonParser.parseString(body).getAsJsonObject();
    }
    private static void save(HttpSession session, JsonObject token) {
        String access = token.get("access_token").getAsString();
        long expires = System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000;
        String refresh = token.has("refresh_token") ? token.get("refresh_token").getAsString() : null;
        String id = token.has("id_token") ? token.get("id_token").getAsString() : null;
        session.setAttribute("oidc.access", access);
        session.setAttribute("oidc.expires", expires);
        if (refresh != null) session.setAttribute("oidc.refresh", refresh);
        if (id != null) session.setAttribute("oidc.id", id);
    }
    static String endSession(String idToken) {
        String hint = idToken.isBlank() || "null".equals(idToken) ? "" : "&id_token_hint=" + enc(idToken);
        return issuer() + "/protocol/openid-connect/logout?client_id=gator-mail" + hint;
    }
    private static String redirect(HttpServletRequest request) {
        String configured = System.getenv("GATOR_MAIL_OAUTH_REDIRECT_URI");
        if (configured != null && !configured.isBlank()) return configured;

        String forwardedHost = first(request.getHeader("X-Forwarded-Host"));
        String host = forwardedHost.isBlank() ? request.getServerName() : forwardedHost;
        boolean local = isLocal(host);
        String scheme = local ? first(request.getHeader("X-Forwarded-Proto")) : "https";
        if (scheme.isBlank()) scheme = request.getScheme();
        String authority = host;
        if (local && forwardedHost.isBlank() && request.getServerPort() != 80 && request.getServerPort() != 443) {
            authority += ":" + request.getServerPort();
        }
        return scheme + "://" + authority + request.getContextPath() + "/oauth/callback";
    }
    private static String first(String value) {
        if (value == null) return "";
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }
    static boolean isLocal(String host) {
        String value = host.toLowerCase();
        return value.equals("localhost") || value.startsWith("localhost:") || value.startsWith("127.")
                || value.equals("[::1]") || value.startsWith("[::1]:") || value.equals("::1");
    }
    static boolean validState(String expected, String returned) {
        return returned != null && !"null".equals(expected)
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), returned.getBytes(StandardCharsets.UTF_8));
    }
    private static String random(int n) { byte[] b=new byte[n]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String challenge(String v) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII))); } catch(Exception e){throw new IllegalStateException(e);} }
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private static String env(String n,String d){String v=System.getenv(n);return v==null||v.isBlank()?d:v;}

    static final class ReauthenticationRequired extends Exception { }
}
