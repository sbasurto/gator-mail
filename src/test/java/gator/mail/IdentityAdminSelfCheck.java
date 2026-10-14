package gator.mail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

final class IdentityAdminSelfCheck {
    static void run() throws Exception {
        assert !MailServlet.needsMailLoginPolicy(false,false,false);
        assert MailServlet.needsMailLoginPolicy(false,true,true);
        assert MailServlet.needsMailLoginPolicy(true,true,true);
        for(boolean feature:new boolean[]{false,true}) {
            try {MailServlet.needsMailLoginPolicy(feature,false,true);throw new AssertionError("Projection schema without grant policy fell back to legacy");}
            catch(IllegalStateException expected) { }
        }
        JsonObject noRoles = new JsonObject();
        assert MailServlet.centralMailAccess(false, noRoles);
        assert !MailServlet.centralMailAccess(true, noRoles);
        JsonObject mailRoles = JsonParser.parseString("{\"resource_access\":{\"gator-mail\":{\"roles\":[\"access\"]}}}").getAsJsonObject();
        assert MailServlet.centralMailAccess(true, mailRoles);
        assert !MailServlet.centralMailAccess(true, JsonParser.parseString("{\"resource_access\":{\"other\":{\"roles\":[\"access\"]}}}").getAsJsonObject());
        assert !MailServlet.centralMailAccess(true, JsonParser.parseString("{\"resource_access\":{\"gator-mail\":{\"roles\":\"access\"}}}").getAsJsonObject());
        assert !MailServlet.centralIdentityAdministration(null, null);
        assert !MailServlet.centralIdentityAdministration("false", "true");
        assert MailServlet.centralIdentityAdministration("true", "false");
        assert MailServlet.centralIdentityAdministration(null, "true");
        assert !MailServlet.centralIdentityAdministration("browser-controlled", "true");
        List<String> calls = new ArrayList<>();
        List<JsonObject> requests = new ArrayList<>();
        JsonObject directory = JsonParser.parseString("{\"usuarios\":[{\"id\":\"local-alias\",\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"native-exact\"},{\"id\":\"legacy\",\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"f:fixture:legacy\"}]}").getAsJsonObject();
        MailIdentityAdmin admin = new MailIdentityAdmin("https://identity.example/realms/test", body -> {
            requests.add(body.deepCopy()); calls.add("central:" + body.get("action").getAsString());
            JsonObject result = JsonParser.parseString("{\"status\":\"complete\",\"operationId\":\"operation-fixture\",\"user\":{\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"native-exact\",\"username\":\"fixture-new\",\"enabled\":false}}").getAsJsonObject();
            if (body.has("targetSubject")) result.getAsJsonObject("user").add("targetSubject", body.get("targetSubject"));
            return result;
        }, (procedure, body) -> {
            calls.add("sql:" + procedure);
            assert !procedure.equals("mail_fn_admin_usuario_crear") && !procedure.equals("mail_fn_admin_usuario_guardar")
                    && !procedure.equals("mail_fn_admin_usuario_reset") && !procedure.equals("mail_fn_admin_usuario_eliminar");
            if (procedure.equals("mail_fn_admin_identity_directory")) return directory;
            JsonObject answer = new JsonObject(); answer.addProperty("token", "retirement-fixture"); return answer;
        }, (user, email) -> {
            calls.add("linux:provision"); return new MailServlet.LinuxMailbox("example.com", "1000", "1000", "/home/examplecom/fixture-new");
        }, (user, destination, token) -> calls.add("linux:retire"));
        JsonObject p = JsonParser.parseString("{\"user\":\"local-alias\",\"name\":\"Edited fixture\",\"email\":\"edited@example.com\",\"sessionTimeoutMinutes\":\"180\"}").getAsJsonObject();
        admin.execute("userSave", p, "admin@example.com", "actor-exact", null);
        JsonObject update = requests.getLast();
        assert update.get("targetSubject").getAsString().equals("native-exact");
        assert !update.has("password") && !update.has("actor") && !update.has("enabled");
        assert calls.contains("sql:mail_fn_admin_usuario_preferencias");
        p.addProperty("user", "legacy"); admin.execute("userGrant", p, "admin@example.com", "actor-exact", null);
        assert requests.getLast().get("targetSubject").getAsString().equals("f:fixture:legacy");
        assert requests.getLast().size() == 2;
        p.addProperty("user", "unlinked");
        int sent = requests.size();
        try { admin.execute("userSave", p, "admin@example.com", "actor-exact", null); throw new AssertionError("Unlinked namesake accepted"); }
        catch (SecurityException expected) { }
        assert requests.size() == sent;
        p.addProperty("user", "fixture-new"); p.addProperty("email", "fixture-new@example.com");
        calls.clear(); requests.clear();
        admin.execute("userCreate", p, "admin@example.com", "actor-exact", null);
        assert requests.size() == 1 && requests.getFirst().get("action").getAsString().equals("create");
        assert !requests.getFirst().has("password") && !requests.getFirst().has("enabled") && !requests.getFirst().has("accountId");
        assert !calls.contains("central:grant") && !calls.contains("central:reset");
        assert calls.indexOf("central:create") < calls.indexOf("linux:provision");
        assert calls.indexOf("sql:mail_fn_admin_buzon_registrar") < calls.indexOf("linux:provision");
        // An unfinished creation retains only noncredential retry state and cannot create again.
        MailIdentityAdmin pendingAdmin = new MailIdentityAdmin("https://identity.example/realms/test", body -> {
            assert body.get("action").getAsString().equals("create");
            return JsonParser.parseString("{\"status\":\"pending\",\"operationId\":\"pending-fixture\",\"user\":{\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"new-subject\"}}").getAsJsonObject();
        }, (procedure, body) -> { throw new AssertionError("Pending create touched SQL"); },
                (user, email) -> { throw new AssertionError("Pending create provisioned Linux"); },
                (user, destination, token) -> { throw new AssertionError("Pending create retired Linux"); });
        pendingAdmin.execute("userCreate", p, "admin@example.com", "actor-exact", null);
        assert pendingAdmin.pending() != null && !pendingAdmin.pending().parameters().has("password");
        try { pendingAdmin.execute("userCreate", p, "admin@example.com", "actor-exact", pendingAdmin.pending()); throw new AssertionError("Duplicate create allowed"); }
        catch (IllegalStateException expected) { }
        MailIdentityAdmin failedAdmin = new MailIdentityAdmin("https://identity.example/realms/test", body ->
                JsonParser.parseString("{\"status\":\"failed\",\"operationId\":\"failed-operation\"}").getAsJsonObject(),
                (procedure, body) -> { throw new AssertionError("Failed operation touched local SQL"); },
                (user, email) -> { throw new AssertionError("Failed operation provisioned Linux"); },
                (user, destination, token) -> { throw new AssertionError("Failed operation retired Linux"); });
        MailIdentityAdmin.Pending failedPending = new MailIdentityAdmin.Pending("userCreate", "native-exact", "failed-operation", p);
        assert failedAdmin.execute("userStatus", p, "admin@example.com", "actor-exact", failedPending).get("status").getAsString().equals("failed");
        assert failedAdmin.pending() == failedPending;
        List<JsonObject> lostCreateRequests=new ArrayList<>();
        MailIdentityAdmin lostCreate=new MailIdentityAdmin("https://identity.example/realms/test",body->{
            lostCreateRequests.add(body.deepCopy());
            if (lostCreateRequests.size()==1) throw new java.io.IOException("Lost response");
            return JsonParser.parseString("{\"status\":\"pending\",\"operationId\":\"same-create\",\"user\":{\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"same-native\"}}").getAsJsonObject();
        },(procedure,body)->{throw new AssertionError("Unconfirmed create touched local SQL");},
            (user,email)->{throw new AssertionError("Unconfirmed create touched Linux");},
            (user,destination,token)->{throw new AssertionError("Create retired Linux");});
        try {lostCreate.execute("userCreate",p,"admin","actor-exact",null);throw new AssertionError("Lost response reported success");}
        catch(java.io.IOException expected) { }
        assert lostCreate.pending()!=null && lostCreate.pending().parameters().has("requestId");
        assert !lostCreate.pending().parameters().has("password");
        lostCreate.execute("userStatus",p,"admin","actor-exact",lostCreate.pending());
        assert lostCreateRequests.size()==2 && lostCreateRequests.get(0).equals(lostCreateRequests.get(1));
        com.sun.net.httpserver.HttpServer rejectedServer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        rejectedServer.createContext("/realms/test/gator-identity-admin",exchange->{exchange.sendResponseHeaders(400,-1);exchange.close();});
        rejectedServer.start();
        try {
            gator.lib.sec.GatorIdentityAdminClient rejectedClient=new gator.lib.sec.GatorIdentityAdminClient(java.net.URI.create("http://127.0.0.1:"+rejectedServer.getAddress().getPort()+"/realms/test"));
            MailIdentityAdmin rejectedCreate=new MailIdentityAdmin("https://identity.example/realms/test",body->rejectedClient.send("synthetic-token",body),
                (procedure,body)->{throw new AssertionError("Rejected create touched SQL");},
                (user,email)->{throw new AssertionError("Rejected create touched Linux");},
                (user,destination,value)->{throw new AssertionError("Rejected create retired Linux");});
            try {rejectedCreate.execute("userCreate",p,"admin","actor-exact",null);throw new AssertionError("Rejected create accepted");}
            catch(gator.lib.sec.GatorIdentityAdminClient.Failure expected){assert expected.statusCode()==400;}
            assert rejectedCreate.pending()==null : "Definitive validation failure retained an unusable create key";
        } finally {rejectedServer.stop(0);}
        p.addProperty("user", "local-alias"); p.addProperty("destination", "review-fixture");
        calls.clear(); requests.clear(); admin.execute("userDelete", p, "admin@example.com", "actor-exact", null);
        assert calls.indexOf("central:disable") < calls.indexOf("linux:retire");
        assert !calls.contains("sql:mail_fn_admin_usuario_eliminar");
        MailIdentityAdmin completedRetirement = new MailIdentityAdmin("https://identity.example/realms/test", body ->
                JsonParser.parseString("{\"status\":\"complete\",\"operationId\":\"finished-retirement\",\"user\":{\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"native-exact\"}}").getAsJsonObject(),
                (procedure, body) -> procedure.equals("mail_fn_admin_identity_directory") ? directory
                    : JsonParser.parseString("{\"token\":\"old-retirement\",\"completed\":true}").getAsJsonObject(),
                (user, email) -> { throw new AssertionError("Retry reprovisioned Linux"); },
                (user, destination, token) -> { throw new AssertionError("Completed retirement retry repeated Linux deletion"); });
        completedRetirement.execute("userDelete", p, "admin@example.com", "actor-exact", null);
        List<String> resetActions = new ArrayList<>();
        MailIdentityAdmin acceptedReset = new MailIdentityAdmin("https://identity.example/realms/test", body -> {
            String resetAction = body.get("action").getAsString(); resetActions.add(resetAction);
            return JsonParser.parseString("{\"status\":\"" + (resetAction.equals("reset") ? "pending" : "complete")
                    + "\",\"operationId\":\"accepted-reset\",\"user\":{\"issuer\":\"https://identity.example/realms/test\",\"targetSubject\":\"native-exact\"}}").getAsJsonObject();
        }, (procedure, body) -> directory, (user, email) -> { throw new AssertionError("Reset provisioned Linux"); },
                (user, destination, token) -> { throw new AssertionError("Reset retired Linux"); });
        JsonObject accepted = acceptedReset.execute("userReset", p, "admin@example.com", "actor-exact", null);
        assert accepted.get("temporaryPassword").getAsString().matches("[A-Za-z0-9_-]{24}");
        assert acceptedReset.pending() != null && !acceptedReset.pending().parameters().has("password");
        JsonObject completedReset = acceptedReset.execute("userStatus", p, "admin@example.com", "actor-exact", acceptedReset.pending());
        assert !completedReset.has("temporaryPassword") && resetActions.equals(List.of("reset", "status"));
        calls.clear(); requests.clear(); admin.execute("userReset", p, "admin@example.com", "actor-exact", null);
        assert requests.size() == 1 && requests.getFirst().get("action").getAsString().equals("reset");
        assert requests.getFirst().get("temporary").getAsBoolean();
        assert requests.getFirst().get("password").getAsString().matches("[A-Za-z0-9_-]{24}");
    }
}
