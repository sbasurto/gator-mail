package gator.mail;

import com.google.gson.*;
import gator.lib.db.ADO;
import gator.lib.db.GappSQLStatement;
import gator.lib.db.helpers.GappDBHelper;
import jakarta.servlet.ServletContext;

/** The browser UI and scanner share the same current mailbox and central grant policy. */
final class MailAccess {
    private MailAccess() { }
    static JsonObject require(JsonObject access,boolean requiresGrant,JsonObject claims) {
        if(!access.has("codigo") || !"0".equals(access.get("codigo").getAsString())
            || !MailServlet.centralMailAccess(requiresGrant,claims))throw new SecurityException("Mail access denied");
        return access;
    }
    static JsonObject load(ServletContext context,JsonObject identity,String user,JsonObject claims) throws Exception {
        boolean requiresGrant=false;
        String contacts=context.getInitParameter("gappContactsDbFile");
        ADO database=new ADO(contacts,true);
        try {
            GappSQLStatement statement=new GappSQLStatement();
            statement.setQuery("SELECT to_regprocedure('public.mail_fn_identity_login_policy(text)') IS NOT NULL AS helper, "
                + "to_regclass('public.app_identity_admin_operation') IS NOT NULL AS projection_schema");
            boolean available;
            try(var rows=database.executePreparedStmtRset(statement);var query=rows==null?null:rows.getStatement()) {
                if(rows==null || !rows.next())throw new IllegalStateException("Mail login policy unavailable");
                boolean feature=MailServlet.centralIdentityAdministration(context.getInitParameter("gator.identityAdmin.enabled"),System.getenv("GATOR_IDENTITY_ADMIN_ENABLED"));
                available=MailServlet.needsMailLoginPolicy(feature,rows.getBoolean("helper"),rows.getBoolean("projection_schema"));
            }
            if(available) {
                statement=new GappSQLStatement();statement.setQuery("select mail_fn_identity_login_policy(?) as policy");
                JsonObject policy=identity.deepCopy();policy.addProperty("usuario",user);statement.addParam(policy.toString());
                try(var rows=database.executePreparedStmtRset(statement);var query=rows==null?null:rows.getStatement()) {
                    if(rows==null || !rows.next())throw new IllegalStateException("Mail login policy unavailable");
                    JsonObject result=JsonParser.parseString(rows.getString("policy")).getAsJsonObject().getAsJsonArray("responses").get(0).getAsJsonObject();
                    if(!"0".equals(result.get("resNum").getAsString()))throw new SecurityException("Mail login policy denied");
                    requiresGrant=result.getAsJsonObject("response").get("requiresAccessGrant").getAsBoolean();
                }
            }
        } finally {database.close();}
        GappSQLStatement statement=new GappSQLStatement();statement.setQuery("select app_fn_has_mail_access(?) as access");
        JsonObject input=new JsonObject();input.addProperty("usuario",user);statement.addParam(input.toString());
        var rows=new GappDBHelper(context.getInitParameter("gappConfigFile")).execute(statement);
        if(rows==null || rows.size()!=1)throw new IllegalStateException("Mail access unavailable");
        return require(JsonParser.parseString(rows.getFirst().get("access")).getAsJsonObject(),requiresGrant,claims);
    }
}
