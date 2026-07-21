package gator.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.*;
import gator.lib.web.gui.GatorJsonView;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.Map;

public final class OAuthServlet extends HttpServlet {
    private static final String ISSUER = env("GATOR_MAIL_OAUTH_ISSUER", "https://mail.soft-gator.com/auth/realms/gator");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if ("/callback".equals(req.getPathInfo())) callback(req, res);
        else if ("/logout".equals(req.getPathInfo())) logout(req, res);
        else if ("/logged-out".equals(req.getPathInfo())) loggedOut(req, res);
        else login(req, res);
    }

    private void logout(HttpServletRequest req, HttpServletResponse res) throws IOException {
        HttpSession session = req.getSession(false);
        String idToken = session == null ? "" : String.valueOf(session.getAttribute("oidc.id"));
        if (session != null) session.invalidate();
        res.sendRedirect(endSession(idToken));
    }

    private void loggedOut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        MailServlet.prepare(res);
        Map<String, Object> model = MailServlet.baseModel(req, "");
        model.put("loggedOut", true);
        model.put("sessionActive", false);
        res.getWriter().print(new GatorJsonView().renderResource("gator-mail/screens/mail.json", model));
    }
    private void login(HttpServletRequest req, HttpServletResponse res) throws IOException {
        HttpSession s = req.getSession(true); String state = random(24), verifier = random(48);
        s.setAttribute("oidc.state", state); s.setAttribute("oidc.verifier", verifier);
        res.sendRedirect(ISSUER + "/protocol/openid-connect/auth?response_type=code&scope=openid%20profile%20email&client_id=gator-mail&redirect_uri="
                + enc(redirect(req)) + "&state=" + enc(state) + "&code_challenge_method=S256&code_challenge=" + enc(challenge(verifier)));
    }
    private void callback(HttpServletRequest req, HttpServletResponse res) throws IOException {
        HttpSession s = req.getSession(false);
        if (s == null || !constant(String.valueOf(s.getAttribute("oidc.state")), req.getParameter("state"))) { res.sendError(400); return; }
        try {
            JsonObject token = token("grant_type=authorization_code&client_id=gator-mail&code=" + enc(req.getParameter("code"))
                    + "&redirect_uri=" + enc(redirect(req)) + "&code_verifier=" + enc(String.valueOf(s.getAttribute("oidc.verifier"))));
            save(s, token);
            HttpRequest infoReq = HttpRequest.newBuilder(URI.create(ISSUER + "/protocol/openid-connect/userinfo"))
                    .header("Authorization", "Bearer " + token.get("access_token").getAsString()).GET().build();
            HttpResponse<String> info = HTTP.send(infoReq, HttpResponse.BodyHandlers.ofString());
            if (info.statusCode() != 200) throw new IllegalStateException();
            JsonObject user = JsonParser.parseString(info.body()).getAsJsonObject();
            req.changeSessionId();
            s.setAttribute("oidc.user", user.has("email") && !user.get("email").isJsonNull()
                    ? user.get("email").getAsString()
                    : user.get("preferred_username").getAsString());
            s.removeAttribute("oidc.state"); s.removeAttribute("oidc.verifier");
            res.sendRedirect(req.getContextPath() + "/mail");
        } catch (Exception e) { res.sendError(503, "No fue posible completar el acceso OAuth"); }
    }
    static String accessToken(HttpServletRequest req) throws Exception {
        HttpSession s = req.getSession(false); if (s == null) throw new IllegalStateException();
        if (System.currentTimeMillis() >= ((Number)s.getAttribute("oidc.expires")).longValue() - 30_000) {
            save(s, token("grant_type=refresh_token&client_id=gator-mail&refresh_token=" + enc(String.valueOf(s.getAttribute("oidc.refresh")))));
        }
        return String.valueOf(s.getAttribute("oidc.access"));
    }
    private static JsonObject token(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(ISSUER + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException();
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }
    private static void save(HttpSession s, JsonObject token) { s.setAttribute("oidc.access", token.get("access_token").getAsString()); if (token.has("refresh_token")) s.setAttribute("oidc.refresh", token.get("refresh_token").getAsString()); if (token.has("id_token")) s.setAttribute("oidc.id", token.get("id_token").getAsString()); s.setAttribute("oidc.expires", System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000); }
    static String endSession(String idToken) {
        String hint = idToken.isBlank() || "null".equals(idToken) ? "" : "&id_token_hint=" + enc(idToken);
        return ISSUER + "/protocol/openid-connect/logout?client_id=gator-mail" + hint;
    }
    static String accountUrl() { return ISSUER + "/account/#/security/signingin"; }
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
    private static String random(int n) { byte[] b=new byte[n]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String challenge(String v) { try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII))); } catch(Exception e){throw new IllegalStateException(e);} }
    private static boolean constant(String a,String b){return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);}
    private static String env(String n,String d){String v=System.getenv(n);return v==null||v.isBlank()?d:v;}
}
