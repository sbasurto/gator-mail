package gator.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.*;
import java.lang.reflect.Proxy;
import java.util.*;

public final class MailScannerSelfCheck {
    public static void main(String[] args) throws Exception {
        var granted=JsonParser.parseString("{\"codigo\":\"0\",\"email\":\"fixture@example.com\"}").getAsJsonObject();
        var denied=JsonParser.parseString("{\"codigo\":\"-1\"}").getAsJsonObject();
        var claims=JsonParser.parseString("{\"resource_access\":{\"gator-mail\":{\"roles\":[\"access\"]}}}").getAsJsonObject();
        for(boolean requiresGrant:new boolean[]{false,true}) {
            try {MailAccess.require(denied,requiresGrant,claims);throw new AssertionError("Missing local Mail access accepted");}
            catch(SecurityException expected) { }
        }
        try {MailAccess.require(granted,true,new JsonObject());throw new AssertionError("Missing central Mail access accepted");}
        catch(SecurityException expected) { }
        assert MailAccess.require(granted,true,claims).equals(granted);
        assert MailAccess.require(granted,false,new JsonObject()).equals(granted);
        var servlet=new MailScannerServlet();
        Map<String,Object> attributes=new HashMap<>();
        var session=(HttpSession)Proxy.newProxyInstance(MailScannerSelfCheck.class.getClassLoader(),new Class<?>[]{HttpSession.class},(p,m,a)->m.getName().equals("getAttribute")?attributes.get(a[0]):null);
        var request=(HttpServletRequest)Proxy.newProxyInstance(MailScannerSelfCheck.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(p,m,a)->m.getName().equals("getSession")?session:null);
        try {servlet.authorizeWeb(request);throw new AssertionError("Anonymous Mail session accepted");}
        catch(gator.lib.web.mobile.GatorMobileScannerServlet.Rejected expected){assert expected.status==401;}
        attributes.put("oidc.subject","fixture-subject");attributes.put("oidc.user","fixture");
        try {servlet.authorizeWeb(request);throw new AssertionError("Unverified Mail challenge accepted");}
        catch(gator.lib.web.mobile.GatorMobileScannerServlet.Rejected expected){assert expected.status==403;}
        System.out.println("Mail scanner access, central grants and challenge checks passed");
    }
}
