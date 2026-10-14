package gator.mail.keycloak;

import org.keycloak.Config;
import org.keycloak.events.*;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.timer.TimerProvider;

public final class GatorIdentityAdminEventsFactory implements EventListenerProviderFactory {
    @Override public String getId(){return "gator-identity-admin";}
    @Override public EventListenerProvider create(KeycloakSession session){return new GatorIdentityAdminEvents(session);}
    @Override public void init(Config.Scope config) { }
    @Override public void postInit(KeycloakSessionFactory factory) {
        try(KeycloakSession session=factory.create()) {
            session.getProvider(TimerProvider.class).schedule(() -> {
                try { KeycloakModelUtils.runJobInTransaction(factory,fresh -> {
                    RealmModel realm=fresh.realms().getRealmByName("gator");
                    if(realm!=null)GatorIdentityAdminEvents.replay(fresh,realm,GatorIdentityAdminSql::call);
                }); } catch(RuntimeException unavailable) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Identity synchronization pending; durable replay will retry"); }
            },30000,"gator-identity-admin-replay");
        }
    }
    @Override public void close() { }
}
