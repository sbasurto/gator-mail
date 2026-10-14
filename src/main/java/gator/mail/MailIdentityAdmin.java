package gator.mail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Keeps central identity writes separate from local Mail preferences and Linux operations. */
final class MailIdentityAdmin {
    @FunctionalInterface interface Central { JsonObject send(JsonObject request) throws Exception; }
    @FunctionalInterface interface Local { JsonObject call(String procedure, JsonObject request) throws Exception; }
    @FunctionalInterface interface Provisioner { MailServlet.LinuxMailbox provision(String user, String email) throws Exception; }
    @FunctionalInterface interface Retirer { void retire(String user, String destination, String token) throws Exception; }
    record Pending(String action, String subject, String operationId, JsonObject parameters) { }
    private final String issuer;
    private final Central central;
    private final Local local;
    private final Provisioner provisioner;
    private final Retirer retirer;
    private Pending pending;

    MailIdentityAdmin(String issuer, Central central, Local local, Provisioner provisioner, Retirer retirer) {
        this.issuer = issuer; this.central = central; this.local = local; this.provisioner = provisioner; this.retirer = retirer;
    }

    Pending pending() { return pending; }

    JsonObject execute(String action, JsonObject parameters, String actor, String actorSubject, Pending previous) throws Exception {
        pending = previous;
        if ("userStatus".equals(action)) {
            if (pending == null) throw new IllegalStateException("No hay una operación pendiente");
            if ("userCreate".equals(pending.action()) && pending.subject().isBlank())
                return execute("userCreate",pending.parameters(),actor,actorSubject,null);
            JsonObject request = request("status", pending.subject()); request.addProperty("operationId", pending.operationId());
            JsonObject result = central.send(request);
            if ("failed".equals(text(result,"status"))) return result;
            if (complete(result)) finish(pending.action(), pending.parameters(), actor, pending.subject());
            return result;
        }
        if (pending != null) throw new IllegalStateException("Comprueba primero la operación pendiente");
        String subject = "userCreate".equals(action) ? null : target(parameters, actor);
        JsonObject request;
        String password = null;
        if ("userCreate".equals(action)) {
            parameters=parameters.deepCopy();
            if (!parameters.has("requestId")) parameters.addProperty("requestId",java.util.UUID.randomUUID().toString());
            pending=new Pending(action,"","",retryParameters(parameters));
            request = request("create", null);
            request.add("requestId",parameters.get("requestId"));
            request.addProperty("username", text(parameters, "user"));
            request.addProperty("localUsuario", text(parameters, "user"));
            request.addProperty("name", text(parameters, "name")); request.addProperty("email", text(parameters, "email"));
        } else if ("userSave".equals(action)) {
            request = request("update", subject); request.addProperty("name", text(parameters, "name"));
            request.addProperty("email", text(parameters, "email"));
        } else if ("userToggle".equals(action)) {
            JsonObject user = central.send(request("get", subject)).getAsJsonObject("user");
            boolean enabled = user.get("enabled").getAsBoolean();
            if (enabled && subject.equals(actorSubject)) throw new SecurityException("No puedes desactivar tu propia cuenta");
            request = request(enabled ? "disable" : "enable", subject);
        } else if ("userDelete".equals(action)) {
            if (subject.equals(actorSubject)) throw new SecurityException("No puedes retirar tu propio buzón");
            request = request("disable", subject);
        } else if ("userReset".equals(action)) {
            password = MailServlet.temporaryPassword();
            request = request("reset", subject); request.addProperty("password", password); request.addProperty("temporary", true);
        } else if ("userGrant".equals(action)) {
            request = request("grant", subject);
        } else if ("userSafeList".equals(action)) {
            preferences(parameters, actor);
            JsonObject result = new JsonObject(); result.addProperty("status", "complete"); return result;
        } else throw new IllegalArgumentException("Operación de usuario no válida");
        JsonObject result;
        try { result = central.send(request); }
        catch (gator.lib.sec.GatorIdentityAdminClient.Failure failure) {
            if ("userCreate".equals(action) && failure.statusCode() == 400) pending = null;
            throw failure;
        }
        if (result.has("user")) {
            String returnedSubject = text(result.getAsJsonObject("user"), "targetSubject");
            if (!issuer.equals(text(result.getAsJsonObject("user"), "issuer"))
                    || subject != null && !subject.equals(returnedSubject))
                throw new SecurityException("La identidad central no coincide");
            subject = returnedSubject;
        }
        if (subject == null || subject.isBlank()) throw new IllegalStateException("Falta la identidad central confirmada");
        if ("userReset".equals(action)) {
            if (!complete(result)) {
                if (!result.has("operationId")) throw new IllegalStateException("Falta la confirmación del restablecimiento");
                // The reset was accepted; status retries must never submit its password again.
                pending = new Pending(action, subject, text(result, "operationId"), retryParameters(parameters));
            }
            result.addProperty("temporaryPassword", password);
            return result;
        }
        if (result.has("operationId")) pending = new Pending(action, subject, text(result, "operationId"), retryParameters(parameters));
        if (complete(result)) finish(action, parameters, actor, subject);
        else if (pending == null) throw new IllegalStateException("Falta el identificador de la operación pendiente");
        return result;
    }

    private void finish(String action, JsonObject parameters, String actor, String subject) throws Exception {
        JsonObject value = parameters.deepCopy(); value.addProperty("actor", actor);
        value.addProperty("targetIssuer", issuer); value.addProperty("targetSubject", subject);
        if ("userCreate".equals(action)) {
            value.addProperty("action", "preflight"); local.call("mail_fn_admin_buzon_registrar", value);
            MailServlet.LinuxMailbox linux = provisioner.provision(text(parameters, "user"), text(parameters, "email"));
            value.addProperty("action", "register"); value.addProperty("mailDomain", linux.domain());
            value.addProperty("mailOsUid", linux.uid()); value.addProperty("mailOsGid", linux.gid()); value.addProperty("mailHome", linux.home());
            local.call("mail_fn_admin_buzon_registrar", value);
        } else if ("userDelete".equals(action)) {
            JsonObject authorization = local.call("mail_fn_admin_buzon_retirar", value);
            if (authorization.has("completed") && authorization.get("completed").getAsBoolean()) { pending = null; return; }
            String token = text(authorization, "token");
            retirer.retire(text(parameters, "user"), text(parameters, "destination"), token);
            value.addProperty("token", token); value.addProperty("confirmed", true);
            local.call("mail_fn_admin_buzon_retirar", value);
        } else if ("userSave".equals(action)) preferences(parameters, actor);
        pending = null;
    }

    private void preferences(JsonObject parameters, String actor) throws Exception {
        JsonObject value = new JsonObject(); value.addProperty("actor", actor);
        for (String key : new String[] {"user", "sessionTimeoutMinutes", "phone"})
            if (parameters.has(key)) value.add(key, parameters.get(key));
        local.call("mail_fn_admin_usuario_preferencias", value);
    }

    private String target(JsonObject parameters, String actor) throws Exception {
        JsonObject query = new JsonObject(); query.addProperty("actor", actor);
        String wanted = text(parameters, "user"); String subject = null;
        for (JsonElement element : local.call("mail_fn_admin_identity_directory", query).getAsJsonArray("usuarios")) {
            JsonObject user = element.getAsJsonObject();
            if (!wanted.equals(text(user, "id"))) continue;
            if (subject != null || !issuer.equals(text(user, "issuer"))) throw new SecurityException("Vínculo central no válido");
            subject = text(user, "targetSubject");
        }
        if (subject == null || subject.isBlank()) throw new SecurityException("El usuario no tiene vínculo central exacto");
        return subject;
    }

    private static JsonObject retryParameters(JsonObject parameters) {
        JsonObject safe = new JsonObject();
        for (String field : new String[] {"user", "name", "email", "sessionTimeoutMinutes", "phone", "destination", "requestId"})
            if (parameters.has(field)) safe.add(field, parameters.get(field));
        return safe;
    }

    private static JsonObject request(String action, String subject) {
        JsonObject value = new JsonObject(); value.addProperty("action", action);
        if (subject != null) value.addProperty("targetSubject", subject); return value;
    }
    private static boolean complete(JsonObject result) {
        String status = text(result, "status");
        if (!status.equals("complete") && !status.equals("pending")) throw new IllegalStateException("La operación central no se completó");
        return status.equals("complete");
    }
    private static String text(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonPrimitive() || !value.get(field).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Datos de administración de usuario no válidos");
        return value.get(field).getAsString();
    }
}
