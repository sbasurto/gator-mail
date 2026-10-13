package gator.mail.keycloak;

import java.util.*;
import java.util.stream.Stream;
import org.keycloak.events.*;
import org.keycloak.events.admin.*;
import org.keycloak.models.*;

/** Native console and resource changes replay from Keycloak's committed event store. */
public final class GatorIdentityAdminEvents implements EventListenerProvider {
    private final KeycloakSession session;
    GatorIdentityAdminEvents(KeycloakSession session) { this.session=session; }
    @Override public void onEvent(Event event) { }
    @Override public void onEvent(AdminEvent event,boolean includeRepresentation) {
        RealmModel realm=session.realms().getRealm(event.getRealmId());
        if (!GatorIdentityAdmin.enabled(realm) || GatorIdentityAdmin.eventSubject(event)==null) return;
        String realmId=realm.getId();
        // Never project uncommitted Keycloak state or make a SQL outage roll back a committed native change.
        session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
            @Override protected void commitImpl() {
                try { org.keycloak.models.utils.KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(),
                    fresh -> replay(fresh,fresh.realms().getRealm(realmId),GatorIdentityAdminSql::call)); }
                catch (RuntimeException unavailable) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Identity synchronization pending; durable replay will retry"); }
            }
            @Override protected void rollbackImpl() { }
        });
    }
    @Override public void close() { }

    static void replay(KeycloakSession session,RealmModel realm,GatorIdentityAdminSql.Transport sql) {
        if (!GatorIdentityAdmin.enabled(realm)) return;
        if (!realm.isAdminEventsEnabled() || realm.isAdminEventsDetailsEnabled())
            throw new IllegalStateException("Durable identity events must exclude representations");
        session.getContext().setRealm(realm);
        EventStoreProvider store=session.getProvider(EventStoreProvider.class);
        if (store==null) throw new IllegalStateException("Missing durable identity event store");
        // Replay retained events, not a timestamp cutoff: a long transaction may commit an older timestamp late.
        int first=0;
        while (true) {
            List<AdminEvent> events;
            try (Stream<AdminEvent> stream=store.createAdminQuery().realm(realm.getId()).resourceType(ResourceType.USER)
                    .orderByAscTime().firstResult(first).maxResults(100).getResultStream()) { events=stream.toList(); }
            for (AdminEvent event:events) {
                try { enqueue(session,realm,event,sql); }
                catch (RuntimeException unavailable) {
                    System.getLogger(GatorIdentityAdminEvents.class.getName()).log(System.Logger.Level.WARNING,
                            "Identity event pending; durable replay will retry and other events will continue");
                }
            }
            if (events.size()<100) break;
            first+=events.size();
        }
        drain(session,realm,sql);
    }

    static void enqueue(KeycloakSession session,RealmModel realm,AdminEvent event,GatorIdentityAdminSql.Transport sql) {
        String subject=GatorIdentityAdmin.eventSubject(event);
        if (subject==null) return;
        UserModel user=session.users().getUserById(realm,subject);
        Map<String,Object> input=GatorIdentityAdmin.command("enqueue");
        if (user!=null) input.putAll(GatorIdentityAdmin.snapshot(session,realm,user));
        else { input.put("issuer",GatorIdentityAdmin.issuer(session,realm));input.put("targetSubject",subject);input.put("enabled",false); }
        String operation=event.getDetails()==null ? null:event.getDetails().get("gator.operationId");
        input.put("operationId", operation==null ? "keycloak:"+event.getId():operation);
        input.put("version",event.getTime());
        input.put("operation",user==null ? "disable":"update");
        // These details are written only by the authenticated custom resource, not arbitrary representations.
        if (event.getDetails()!=null && operation!=null) {
            for (String key:List.of("destinationDbId","operation","localUsuario","accountId","bodegaId")) {
                String value=event.getDetails().get("gator."+key);if(value!=null)input.put(key,value);
            }
        }
        if(event.getAuthDetails()!=null) {
            input.put("actorSubject",event.getAuthDetails().getUserId());
            input.put("actorIssuer",event.getDetails()!=null && event.getDetails().containsKey("gator.actorIssuer")
                    ? event.getDetails().get("gator.actorIssuer"):GatorIdentityAdmin.issuer(session,realm));
        }
        sql.call("app_fn_identity_admin",input);
    }

    @SuppressWarnings("unchecked")
    static void drain(KeycloakSession session,RealmModel realm,GatorIdentityAdminSql.Transport sql) {
        Map<String,Object> pending=sql.call("app_fn_identity_admin",GatorIdentityAdmin.command("pending"));
        if (!(pending.get("events") instanceof List<?> operations)) throw new IllegalStateException("Invalid pending operations");
        for(Object raw:operations) {
            if(!(raw instanceof Map<?,?> row))throw new IllegalStateException("Invalid identity operation");
            Map<String,Object> complete=GatorIdentityAdmin.command("complete");
            complete.put("operationId",GatorIdentityAdmin.subject((String)row.get("operationId")));
            complete.put("destinationDbId",row.get("destinationDbId"));
            complete.put("version",row.get("version"));
            // SQL resolves the allowlisted destination, applies it and acknowledges only canonical success.
            try { sql.call("app_fn_identity_admin",complete); }
            catch (RuntimeException failedDestination) {
                System.getLogger(GatorIdentityAdminEvents.class.getName()).log(System.Logger.Level.WARNING,
                        "Identity destination pending; other destinations will continue");
            }
        }
    }
}
