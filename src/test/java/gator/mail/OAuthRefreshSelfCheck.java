package gator.mail;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import jakarta.servlet.http.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

public final class OAuthRefreshSelfCheck {
    static final Map<String,Object> attributes=new ConcurrentHashMap<>();
    static final AtomicInteger renewals=new AtomicInteger(), userinfos=new AtomicInteger();
    static volatile int mode=200;
    static String refresh="refresh-0", access="access-0";
    static final HttpSession session=(HttpSession)java.lang.reflect.Proxy.newProxyInstance(OAuthRefreshSelfCheck.class.getClassLoader(),new Class<?>[]{HttpSession.class},(proxy,method,args)->switch(method.getName()){
        case "getAttribute" -> attributes.get(args[0]);
        case "setAttribute" -> {attributes.put((String)args[0],args[1]);yield null;}
        case "removeAttribute" -> {attributes.remove(args[0]);yield null;}
        case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
        default -> null;
    });
    static final HttpServletRequest request=(HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(OAuthRefreshSelfCheck.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->method.getName().equals("getSession")?session:null);
    static void expire(){attributes.put("oidc.expires",System.currentTimeMillis()-1);}
    static boolean causedBy(Throwable error,Class<?> type){for(Throwable current=error;current!=null;current=current.getCause())if(type.isInstance(current))return true;return false;}
    public static void main(String[] args)throws Exception {
        Path temporary=Files.createTempDirectory("gator-oauth-refresh-");
        Path keys=temporary.resolve("test.p12");
        var keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias","session-test","-keyalg","RSA","-keysize","2048","-validity","1",
                "-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-storetype","PKCS12",
                "-keystore",keys.toString(),"-storepass","changeit","-keypass","changeit","-noprompt")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if(keytool.waitFor()!=0)throw new IllegalStateException("Could not create local HTTPS test identity");
        System.setProperty("javax.net.ssl.trustStore",keys.toString());
        System.setProperty("javax.net.ssl.trustStorePassword","changeit");
        var ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(keys)){ks.load(in,"changeit".toCharArray());}
        var kmf=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());kmf.init(ks,"changeit".toCharArray());var ssl=SSLContext.getInstance("TLS");ssl.init(kmf.getKeyManagers(),null,null);
        int port=URI.create(System.getenv("GATOR_MAIL_OAUTH_ISSUER")).getPort();var server=HttpsServer.create(new InetSocketAddress("localhost",port),0);server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/realms/test/protocol/openid-connect/token",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);renewals.incrementAndGet();
            int status=mode;String response;
            if(status==200){
                assert body.equals("grant_type=refresh_token&client_id=gator-mail&refresh_token="+refresh):"Refresh rotation used the previous token";
                access="access-"+renewals.get();refresh="refresh-"+renewals.get();response="{\"access_token\":\""+access+"\",\"refresh_token\":\""+refresh+"\",\"expires_in\":300}";
            }else response=status==400?"{\"error\":\"invalid_grant\"}":"temporarily unavailable";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.createContext("/realms/test/protocol/openid-connect/userinfo",exchange->{
            userinfos.incrementAndGet();int status=mode==503?503:Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),"Bearer "+access)?200:401;
            byte[] bytes="{\"sub\":\"subject-fixture\",\"preferred_username\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();try{
            attributes.put("oidc.access",access);attributes.put("oidc.refresh",refresh);attributes.put("oidc.expires",System.currentTimeMillis()+300_000);attributes.put("oidc.subject","subject-fixture");attributes.put("mail.challenge.verified",true);attributes.put("mail.draft","draft-preserved");
            assert OAuthServlet.accessToken(request).equals(access)&&renewals.get()==0;System.out.println("PASS: valid access token is reused without requesting login");
            for(int i=0;i<3;i++){expire();assert OAuthServlet.authenticatedUserInfo(request).get("sub").getAsString().equals("subject-fixture");assert attributes.get("oidc.refresh").equals(refresh);assert Boolean.TRUE.equals(attributes.get("mail.challenge.verified"));assert attributes.get("mail.draft").equals("draft-preserved");}
            assert renewals.get()==3;System.out.println("PASS: 3 sequential expirations refresh and rotate tokens, preserving the session, challenge and draft");
            expire();var workers=Executors.newFixedThreadPool(8);try{var futures=new ArrayList<Future<String>>();for(int i=0;i<8;i++)futures.add(workers.submit(()->OAuthServlet.accessToken(request)));for(var f:futures)assert f.get().equals(access);}finally{workers.shutdownNow();}
            assert renewals.get()==4;System.out.println("PASS: 8 simultaneous requests perform exactly one refresh");
            expire();mode=503;try{OAuthServlet.authenticatedUserInfo(request);throw new AssertionError("Temporary outage accepted");}catch(IllegalStateException expected){assert !causedBy(expected,OAuthServlet.ReauthenticationRequired.class);assert attributes.get("oidc.refresh").equals(refresh);}mode=200;assert OAuthServlet.authenticatedUserInfo(request).get("sub").getAsString().equals("subject-fixture");System.out.println("PASS: temporary refresh failure retains the refresh token and recovers on the next request");
            expire();mode=400;try{OAuthServlet.authenticatedUserInfo(request);throw new AssertionError("Expired grant accepted");}catch(IllegalStateException expected){assert causedBy(expected,OAuthServlet.ReauthenticationRequired.class);}mode=200;System.out.println("PASS: revoked or expired refresh grant requires authentication");
            attributes.put("oidc.access","expired-access");expire();int before=renewals.get();assert OAuthServlet.active(request):"Renewable token must not end the session after an IMAP failure";assert renewals.get()==before+1;assert Boolean.TRUE.equals(attributes.get("mail.challenge.verified"));assert attributes.get("mail.draft").equals("draft-preserved");System.out.println("PASS: IMAP recovery renews expired access while retaining challenge and draft");
            mode=503;assert OAuthServlet.active(request):"Temporary identity outage must not end the session";mode=200;assert OAuthServlet.active(request);System.out.println("PASS: IMAP recovery preserves the session during temporary identity outages");
            expire();mode=400;assert !OAuthServlet.active(request):"Definitively expired refresh must end the session";
            mode=200;assert OAuthServlet.active(request);
            attributes.remove("oidc.access");assert !OAuthServlet.active(request):"Missing credentials must not preserve an authenticated session";
            System.out.println("PASS: expired grants and missing credentials still require authentication");
        }finally{server.stop(0);executor.shutdownNow();Files.deleteIfExists(keys);Files.deleteIfExists(temporary);}
    }
}
