package gator.mail;

import jakarta.servlet.http.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

final class LogoutSelfCheck {
    static void run() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        boolean[] invalidated = {false};
        int[] status = {200};
        String[] parameter = {null}, path = {"/logout"}, redirect = {null};
        StringWriter body = new StringWriter();
        HttpSession session = proxy(HttpSession.class, (method, args) -> switch (method) {
            case "getAttribute" -> attributes.get(args[0]);
            case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
            case "invalidate" -> { invalidated[0] = true; yield null; }
            default -> null;
        });
        HttpServletRequest request = proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getPathInfo" -> path[0];
            case "getContextPath" -> "/gator-mail";
            case "getServerName" -> "localhost";
            case "getServerPort" -> 8080;
            case "getScheme" -> "http";
            case "getSession" -> session;
            case "getParameter" -> parameter[0];
            default -> null;
        });
        HttpServletResponse response = proxy(HttpServletResponse.class, (method, args) -> switch (method) {
            case "getWriter" -> new PrintWriter(body);
            case "sendError" -> { status[0] = (int) args[0]; yield null; }
            case "sendRedirect" -> { redirect[0] = (String) args[0]; yield null; }
            default -> null;
        });
        OAuthServlet servlet = new OAuthServlet();
        servlet.doGet(request, response);
        assert !invalidated[0];
        assert body.toString().contains("gator-logout.js");
        assert body.toString().contains("favicon-softgator.png");
        servlet.doPost(request, response);
        assert status[0] == 403 && !invalidated[0];
        parameter[0] = (String) attributes.get("mail.csrf");
        servlet.doPost(request, response);
        assert invalidated[0];
        assert redirect[0].startsWith("/gator-mail/oauth/logged-out");
        attributes.put("oidc.id", "test-id-token");
        attributes.put("oidc.locale", "en");
        servlet.doPost(request, response);
        assert redirect[0].contains("id_token_hint=test-id-token");
        assert redirect[0].contains("post_logout_redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fgator-mail%2Foauth%2Flogged-out%3Flanguage%3Den");
        body.getBuffer().setLength(0);
        parameter[0] = null;
        path[0] = "/logged-out";
        servlet.doGet(request, response);
        assert body.toString().contains("Tu sesión ha finalizado");
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> call.invoke(method.getName(), args)));
    }
}
