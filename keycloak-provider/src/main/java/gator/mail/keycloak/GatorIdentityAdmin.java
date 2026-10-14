package gator.mail.keycloak;

import java.util.*;
import org.keycloak.events.admin.*;
import org.keycloak.models.*;

/** Shared trusted scope, sanitized snapshots and exact identifiers for the provider. */
final class GatorIdentityAdmin {
    static final String ENABLED = "gator.identityAdmin.enabled";
    static final String DESTINATION = "gator.identityAdmin.destinationDbId";
    static boolean enabled(String realm, String flag) { return "gator".equals(realm) && "true".equals(flag); }
    static boolean enabled(RealmModel realm) { return realm != null && enabled(realm.getName(), realm.getAttribute(ENABLED)); }
    static String issuer(KeycloakSession session, RealmModel realm) {
        String issuer = realm.getAttribute("gator.identityAdmin.issuer");
        if (issuer == null || !issuer.matches("https://[^\\s]+/realms/gator"))
            throw new IllegalStateException("Missing trusted central issuer metadata");
        return issuer;
    }
    static String subject(String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.chars().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("Invalid identity subject");
        return value;
    }
    static Map<String, Object> fields(Map<String, Object> input) {
        if (!Set.of("name", "email", "enabled").containsAll(input.keySet()))
            throw new IllegalArgumentException("Unsupported identity field");
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key.equals("enabled")) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("Invalid enabled state");
            } else if (!(value instanceof String text) || text.length() > 512
                    || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid profile field");
            result.put(key, value);
        });
        return result;
    }
    static String eventSubject(AdminEvent event) {
        if (event.getError() != null || event.getResourceType() != ResourceType.USER
                || event.getOperationType() == null || event.getResourcePath() == null
                || !event.getResourcePath().startsWith("users/")) return null;
        String id = event.getResourcePath().substring(6).split("/", 2)[0];
        try { return subject(java.net.URLDecoder.decode(id.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    static Map<String,Object> snapshot(KeycloakSession session, RealmModel realm, UserModel user) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("issuer", issuer(session, realm)); result.put("targetSubject", subject(user.getId()));
        result.put("name", name(user)); result.put("email", user.getEmail() == null ? "" : user.getEmail());
        result.put("enabled", user.isEnabled());
        return result;
    }
    static String name(UserModel user) {
        return String.join(" ", List.of(user.getFirstName() == null ? "" : user.getFirstName(),
                user.getLastName() == null ? "" : user.getLastName())).trim();
    }
    record NameParts(String firstName,String lastName) { }
    static NameParts nameParts(String name) {
        if(name==null) return new NameParts("","");
        int split=name.lastIndexOf(' ');
        return split<0?new NameParts(name,""):new NameParts(name.substring(0,split),name.substring(split+1));
    }
    static Map<String,Object> command(String action) {
        Map<String,Object> input = new LinkedHashMap<>(); input.put("action", action);
        input.put("usuario", "gator_idp"); input.put("idctrl", UUID.randomUUID().toString()); return input;
    }
}
