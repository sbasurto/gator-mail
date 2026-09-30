package gator.mail.keycloak;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.component.ComponentModel;
import org.keycloak.events.*;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.sessions.AuthenticationSessionModel;

public final class GatorExactLoginSelfCheck {
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object fallback(Method method) {
        if (method.getReturnType() == boolean.class) return false;
        if (method.getReturnType() == int.class) return 0;
        if (method.getReturnType() == Stream.class) return Stream.empty();
        return null;
    }
    public static void main(String[] args) {
        ComponentModel component = new ComponentModel(); component.setId("source");
        component.setProviderId("gator-users"); component.put("literalUsernames", "true");
        Map<String, String> notes = new HashMap<>();
        UserModel[] selected = {null};
        String[] requested = {null};
        boolean[] correct = {true}, failed = {false};
        int[] passwordChecks = {0};
        SubjectCredentialManager credentials = proxy(SubjectCredentialManager.class, (p,m,a) -> {
            if (m.getName().equals("isValid")) { passwordChecks[0]++; return correct[0]; }
            return fallback(m);
        });
        UserProvider users = proxy(UserProvider.class, (p,m,a) -> {
            if (m.getName().equals("getUserByUsername")) throw new AssertionError("Case-normalizing lookup used");
            if (m.getName().equals("getUserById")) {
                String id = (String) a[1]; requested[0] = id;
                return proxy(UserModel.class, (u, op, values) -> switch (op.getName()) {
                    case "getId" -> id;
                    case "getUsername" -> id.substring("f:source:".length());
                    case "isEnabled" -> true;
                    case "credentialManager" -> credentials;
                    default -> fallback(op);
                });
            }
            return fallback(m);
        });
        RealmModel realm = proxy(RealmModel.class, (p,m,a) -> switch (m.getName()) {
            case "getId", "getName" -> "isolated";
            case "getComponentsStream" -> Stream.of(component);
            default -> fallback(m);
        });
        KeycloakSessionFactory factory = proxy(KeycloakSessionFactory.class, (p,m,a) -> fallback(m));
        KeycloakSession session = proxy(KeycloakSession.class, (p,m,a) -> switch(m.getName()) {
            case "users" -> users;
            case "getKeycloakSessionFactory" -> factory;
            default -> fallback(m);
        });
        EventBuilder event = new EventBuilder(realm, session) {
            @Override public void error(String error) { /* No external event store in this check. */ }
        }.event(EventType.LOGIN);
        AuthenticationSessionModel auth = proxy(AuthenticationSessionModel.class, (p,m,a) -> {
            if (m.getName().equals("getAuthNote")) return notes.get(a[0]);
            if (m.getName().equals("setAuthNote")) { notes.put((String)a[0],(String)a[1]); return null; }
            return fallback(m);
        });
        LoginFormsProvider form = proxy(LoginFormsProvider.class, (p,m,a) -> m.getReturnType()==LoginFormsProvider.class ? p : fallback(m));
        AuthenticationExecutionModel execution = new AuthenticationExecutionModel(); execution.setId("login");
        AuthenticationFlowContext context = proxy(AuthenticationFlowContext.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getRealm": return realm;
                case "getSession": return session;
                case "getAuthenticationSession": return auth;
                case "getEvent": return event;
                case "getExecution": return execution;
                case "form": return form;
                case "getUser": return selected[0];
                case "clearUser": selected[0]=null; return null;
                case "setUser": selected[0]=(UserModel)a[0]; return null;
                case "failureChallenge": failed[0]=true; return null;
                default: return fallback(m);
            }
        });
        var authenticator = new GatorExactUsernamePasswordForm();
        var input = new MultivaluedHashMap<String,String>(); input.putSingle("password", "test");
        for (String name : List.of("Ecruz", "ecruz", "user@example.com")) {
            input.putSingle("username",name); correct[0]=true;
            assert authenticator.validateUserAndPassword(context,input);
            assert requested[0].equals("f:source:"+name);
            assert selected[0].getId().equals(requested[0]);
            correct[0]=false; failed[0]=false;
            assert !authenticator.validateUserAndPassword(context,input);
            assert failed[0];
            assert selected[0].getId().equals("f:source:"+name) : "Failure accounting must retain the exact identity";
            assert notes.get("ATTEMPTED_USERNAME").equals(name);
        }
        assert passwordChecks[0]==6;
        notes.put("USER_SET_BEFORE_USERNAME_PASSWORD_AUTH","true");
        requested[0]=null; correct[0]=true; input.putSingle("username","someone-else");
        assert authenticator.validateUserAndPassword(context,input);
        assert requested[0]==null : "Reauthentication cannot change the preselected user";
        assert selected[0].getUsername().equals("user@example.com");
        System.out.println("Exact login, password validation, failure accounting and reauthentication OK");
    }
}
