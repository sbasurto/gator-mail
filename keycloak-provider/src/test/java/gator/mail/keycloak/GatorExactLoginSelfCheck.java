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
        component.setProviderId("gator-users"); component.put("literalUsernames", "false");
        boolean[] optIn = {true};
        ClientModel client = proxy(ClientModel.class, (p,m,a) -> switch(m.getName()) {
            case "getAttribute" -> optIn[0] ? "true" : null;
            case "getId" -> "fixture-client";
            default -> fallback(m);
        });
        Map<String, String> notes = new HashMap<>();
        UserModel[] selected = {null}, nativeCandidate = {null};
        boolean[] missingFederated = {false}, emailAllowed = {false}, ambiguousEmail = {false}, enabled = {true};
        UserModel[] emailCandidate = {null};
        String[] requested = {null};
        boolean[] correct = {true}, failed = {false};
        int[] passwordChecks = {0};
        SubjectCredentialManager credentials = proxy(SubjectCredentialManager.class, (p,m,a) -> {
            if (m.getName().equals("isValid")) { passwordChecks[0]++; return correct[0]; }
            return fallback(m);
        });
        UserProvider users = proxy(UserProvider.class, (p,m,a) -> {
            if (m.getName().equals("getUserByUsername")) {
                assert missingFederated[0] : "Federated lookup must not use normalized username cache";
                return nativeCandidate[0];
            }
            if (m.getName().equals("getUserByEmail")) {
                if (ambiguousEmail[0]) throw new ModelDuplicateException("ambiguous");
                return emailCandidate[0];
            }
            if (m.getName().equals("getUserById")) {
                String id = (String) a[1]; requested[0] = id;
                if (missingFederated[0]) return null;
                return proxy(UserModel.class, (u, op, values) -> switch (op.getName()) {
                    case "getId" -> id;
                    case "getUsername" -> id.substring("f:source:".length());
                    case "isEnabled" -> enabled[0];
                    case "credentialManager" -> credentials;
                    default -> fallback(op);
                });
            }
            return fallback(m);
        });
        RealmModel realm = proxy(RealmModel.class, (p,m,a) -> switch (m.getName()) {
            case "getId", "getName" -> "isolated";
            case "getComponentsStream" -> Stream.of(component);
            case "isLoginWithEmailAllowed" -> emailAllowed[0];
            default -> fallback(m);
        });
        KeycloakSessionFactory factory = proxy(KeycloakSessionFactory.class, (p,m,a) -> fallback(m));
        KeycloakSession session = proxy(KeycloakSession.class, (p,m,a) -> switch(m.getName()) {
            case "users" -> users;
            case "getProvider" -> proxy(org.keycloak.credential.hash.PasswordHashProvider.class, (p2,m2,a2) -> fallback(m2));
            case "getKeycloakSessionFactory" -> factory;
            default -> fallback(m);
        });
        EventBuilder event = new EventBuilder(realm, session) {
            @Override public EventBuilder clone() { return this; }
            @Override public void error(String error) { /* No external event store in this check. */ }
        }.event(EventType.LOGIN);
        AuthenticationSessionModel auth = proxy(AuthenticationSessionModel.class, (p,m,a) -> {
            if (m.getName().equals("getClient")) return client;
            if (m.getName().equals("getAuthNote")) return notes.get(a[0]);
            if (m.getName().equals("removeAuthNote")) { notes.remove(a[0]); return null; }
            if (m.getName().equals("setAuthNote")) { notes.put((String)a[0],(String)a[1]); return null; }
            return fallback(m);
        });
        LoginFormsProvider form = proxy(LoginFormsProvider.class, (p,m,a) -> m.getReturnType()==LoginFormsProvider.class ? p : fallback(m));
        AuthenticationExecutionModel execution = new AuthenticationExecutionModel(); execution.setId("login");
        var input = new MultivaluedHashMap<String,String>(); input.putSingle("password", "test");
        AuthenticationFlowContext context = proxy(AuthenticationFlowContext.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getRealm": return realm;
                case "getHttpRequest": return proxy(org.keycloak.http.HttpRequest.class, (p2,m2,a2) -> m2.getName().equals("getDecodedFormParameters") ? input : fallback(m2));
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
        notes.remove("USER_SET_BEFORE_USERNAME_PASSWORD_AUTH");
        missingFederated[0] = true;
        String[] nativeName = {"aidamasovega"}, nativeId = {"native-account-id"};
        nativeCandidate[0] = proxy(UserModel.class, (p,m,a) -> switch (m.getName()) {
            case "getId" -> nativeId[0];
            case "getUsername" -> nativeName[0];
            case "isEnabled" -> enabled[0];
            case "credentialManager" -> credentials;
            default -> fallback(m);
        });
        input.putSingle("username", "aidamasovega"); correct[0] = true;
        assert authenticator.validateUserAndPassword(context,input) : "Native supplier must retain access";
        assert selected[0] == nativeCandidate[0];
        correct[0] = false;
        assert !authenticator.validateUserAndPassword(context,input) : "Native password must still be verified";
        correct[0] = true;
        input.putSingle("username", "Aidamasovega");
        assert !authenticator.validateUserAndPassword(context,input) : "Native case-folded alias must be rejected";
        input.putSingle("username", "aidamasovega"); nativeId[0] = "f:source:other";
        assert !authenticator.validateUserAndPassword(context,input) : "Fallback cannot use a cached federated identity";
        nativeCandidate[0] = null;
        assert !authenticator.validateUserAndPassword(context,input) : "Unknown identity must be rejected";
        emailCandidate[0] = proxy(UserModel.class, (p,m,a) -> switch (m.getName()) {
            case "getId" -> "f:source:MailUser";
            case "getUsername" -> "MailUser";
            case "isEnabled" -> enabled[0];
            case "credentialManager" -> credentials;
            default -> fallback(m);
        });
        emailAllowed[0] = true;
        input.putSingle("username", "unique@example.com");
        assert authenticator.validateUserAndPassword(context,input) : "Unique email login must select the existing identity";
        assert selected[0] == emailCandidate[0];
        correct[0] = false;
        assert !authenticator.validateUserAndPassword(context,input) : "Email login must verify the password";
        assert selected[0] == emailCandidate[0] : "Email failure accounting must retain the resolved subject";
        correct[0] = true; enabled[0] = false;
        assert !authenticator.validateUserAndPassword(context,input) : "Disabled email identity cannot log in";
        enabled[0] = true; ambiguousEmail[0] = true;
        assert !authenticator.validateUserAndPassword(context,input) : "Ambiguous email cannot select a login identity";
        ambiguousEmail[0] = false; emailAllowed[0] = false;
        assert !authenticator.validateUserAndPassword(context,input) : "Realm must explicitly allow email login";
        emailAllowed[0] = true; missingFederated[0] = false;
        assert authenticator.validateUserAndPassword(context,input);
        assert selected[0].getId().equals("f:source:unique@example.com") : "An exact email-shaped username takes precedence over an email alias";
        emailAllowed[0] = false; emailCandidate[0] = null;
        var reset = new GatorExactResetCredentialChooseUser();
        missingFederated[0] = false;
        for (String name : List.of("Ecruz", "ecruz", "user@example.com")) {
            input.putSingle("username", name);
            reset.action(context);
            assert selected[0].getId().equals("f:source:" + name) : "Recovery must retain exact source identity";
        }
        missingFederated[0] = true; nativeName[0] = "aidamasovega"; nativeId[0] = "native-account-id";
        nativeCandidate[0] = proxy(UserModel.class, (p,m,a) -> switch (m.getName()) {
            case "getId" -> nativeId[0];
            case "getUsername" -> nativeName[0];
            case "isEnabled" -> enabled[0];
            default -> fallback(m);
        });
        input.putSingle("username", "aidamasovega"); reset.action(context);
        assert selected[0] == nativeCandidate[0] : "Native recovery must retain access";
        input.putSingle("username", "Aidamasovega"); reset.action(context);
        assert selected[0] == null : "Recovery must reject a native case alias";
        emailAllowed[0] = true; emailCandidate[0] = nativeCandidate[0]; nativeCandidate[0] = null;
        input.putSingle("username", "unique@example.com"); reset.action(context);
        assert selected[0] == emailCandidate[0] : "Unique email recovery must retain access";
        assert "true".equals(notes.get("RESET_CREDENTIAL_USER_CHOSEN"));
        ambiguousEmail[0] = true; reset.action(context);
        assert selected[0] == null : "Ambiguous email must not select an identity";
        assert !notes.containsKey("RESET_CREDENTIAL_USER_CHOSEN");
        ambiguousEmail[0] = false; enabled[0] = false; reset.action(context);
        assert selected[0] == null : "Disabled user cannot recover through this chooser";
        assert !notes.containsKey("RESET_CREDENTIAL_USER_CHOSEN");
        // A global wrapper must invoke the unmodified built-in chooser for other clients.
        optIn[0] = false; enabled[0] = true; emailAllowed[0] = false;
        nativeCandidate[0] = emailCandidate[0]; requested[0] = null;
        input.putSingle("username", "LegacyAlias");
        reset.action(context);
        assert requested[0] == null : "Legacy recovery must not use exact ID lookup";
        assert selected[0] == nativeCandidate[0] : "Legacy chooser retains the built-in lookup result";
        assert reset.getHelpText().contains("gator.exactIdentity");
        System.out.println("Exact federated/native login and recovery, passwords, rejection and reauthentication OK");
    }
}
