package gator.mail;

import com.google.gson.*;
import gator.lib.sec.GatorOidcIdentity;
import gator.lib.web.mobile.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public final class MailScannerServlet extends GatorMobileScannerServlet {
    @Override protected Actor authorizeWeb(HttpServletRequest request) {
        var session=request.getSession(false);
        if(session==null || session.getAttribute("oidc.user")==null || session.getAttribute("oidc.subject")==null)throw new Rejected(401);
        if(!Boolean.TRUE.equals(session.getAttribute("mail.challenge.verified")))throw new Rejected(403);
        if(!"GET".equals(request.getMethod())) {
            String expected=Objects.toString(session.getAttribute("mail.csrf"),"");
            String supplied=Objects.toString(request.getHeader("X-CSRF-TOKEN"),"");
            if(expected.isBlank() || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new Rejected(403);
        }
        JsonObject claims=OAuthServlet.authenticatedUserInfo(request);
        JsonObject identity=OAuthServlet.identityRequest(new JsonObject(),claims);
        String configuration=getServletContext().getInitParameter("gappConfigFile");
        String user=MailServlet.operationalUser(session,identity,(issuer,subject)->GatorOidcIdentity.resolve(configuration,issuer,subject));
        if(!Boolean.TRUE.equals(session.getAttribute("mail.challenge.verified")))throw new Rejected(403);
        requireAccess(identity,user,claims);
        return new Actor(configuration,user,identity.get("issuer").getAsString(),identity.get("subject").getAsString(),request.getContextPath().substring(1),null,null);
    }
    @Override protected Actor authorizeMobile(HttpServletRequest request,GatorMobileIdentity.Identity identity) {
        try {
            String context=request.getContextPath().substring(1);
            var target=assignments().get(context);
            String configuration=getServletContext().getInitParameter("gappConfigFile");
            if(target==null || !target.linked() || !target.index().equals(configuration) || !OAuthServlet.issuer().equals(identity.issuer()))throw new Rejected(403);
            GatorOidcIdentity.requireAccess(identity.claims(),target.accessClientId());
            String user=GatorOidcIdentity.resolve(configuration,identity.issuer(),identity.subject());
            JsonObject actor=new JsonObject();actor.addProperty("issuer",identity.issuer());actor.addProperty("subject",identity.subject());
            requireAccess(actor,user,new Gson().toJsonTree(identity.claims()).getAsJsonObject());
            return new Actor(configuration,user,identity.issuer(),identity.subject(),context,null,null);
        } catch(java.io.IOException unavailable){throw new Rejected(503);}
    }
    private void requireAccess(JsonObject identity,String user,JsonObject claims) {
        try {MailAccess.load(getServletContext(),identity,user,claims);}
        catch(SecurityException denied){throw denied;}
        catch(Exception unavailable){throw new Rejected(503);}
    }
    @Override protected JsonObject browserConfiguration(HttpServletRequest request) {
        JsonObject result=new JsonObject();int port=request.getServerPort();String scheme=request.getScheme();
        String origin=scheme+"://"+request.getServerName()+((port==443 && scheme.equals("https") || port==80 && scheme.equals("http"))?"":":"+port);
        result.addProperty("endpoint",origin+request.getContextPath()+"/api/scanner/");
        result.addProperty("csrfHeader","X-CSRF-TOKEN");result.addProperty("csrfToken",MailServlet.csrf(request.getSession(false)));return result;
    }
}
