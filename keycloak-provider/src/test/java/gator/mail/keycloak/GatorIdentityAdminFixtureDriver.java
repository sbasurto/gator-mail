package gator.mail.keycloak;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.file.*;
import org.keycloak.util.JsonSerialization;

/** Test-only JDBC transport for a disposable Keycloak; never packaged in the provider JAR. */
public final class GatorIdentityAdminFixtureDriver implements Driver {
    private static final ReentrantLock LOCK=new ReentrantLock();
    static { try { DriverManager.registerDriver(new GatorIdentityAdminFixtureDriver()); } catch(SQLException error) {throw new ExceptionInInitializerError(error);} }
    @Override public Connection connect(String url,Properties properties) {
        if(!acceptsURL(url))return null;
        Map<String,Object>[] transaction=new Map[]{null};boolean[] locked={false};
        return proxy(Connection.class,(o,m,v)->switch(m.getName()) {
            case "setAutoCommit" -> {if(Boolean.FALSE.equals(v[0])){LOCK.lock();locked[0]=true;}yield null;}
            case "prepareStatement" -> statement((String)v[0],transaction,locked);
            case "commit" -> {if(transaction[0]!=null)write(transaction[0]);transaction[0]=null;if(locked[0]){LOCK.unlock();locked[0]=false;}yield null;}
            case "rollback","close" -> {transaction[0]=null;if(locked[0]){LOCK.unlock();locked[0]=false;}yield null;}
            case "isClosed" -> false;
            default -> defaultValue(m.getReturnType());
        });
    }
    private static PreparedStatement statement(String sql,Map<String,Object>[] transaction,boolean[] locked) {
        Map<Integer,Object> params=new TreeMap<>();
        return proxy(PreparedStatement.class,(o,m,v)->switch(m.getName()) {
            case "setString","setInt" -> {params.put((Integer)v[0],v[1]);yield null;}
            case "executeQuery" -> {
                Map<String,Object> state=read();if(sql.startsWith("select app_fn_identity_admin")&&Boolean.TRUE.equals(state.get("offline")))throw new SQLException("Synthetic storage offline","08001");
                if(sql.contains("app_fn_sync_identity_password")) {
                    state.put("passwordSyncCalls",((Number)state.getOrDefault("passwordSyncCalls",0)).intValue()+1);transaction[0]=state;
                    yield rows(List.<Object[]>of(new Object[]{"{\"responses\":[{\"resNum\":\"0\"}]}"}));
                }
                if(sql.startsWith("select app_fn_identity_admin")) {
                    Map<String,Object> input=JsonSerialization.readValue((String)params.get(1),Map.class);
                    Map<String,Object> response=contract(state,input);transaction[0]=state;
                    yield rows(List.<Object[]>of(new Object[]{JsonSerialization.writeValueAsString(Map.of("responses",List.of(Map.of("resNum","0","response",response))))}));
                }
                yield rows(accounts(state,sql,params));
            }
            case "executeUpdate" -> {
                Map<String,Object> state=transaction[0]==null?read():transaction[0];
                Map<String,Map<String,Object>> source=(Map)state.get("source");
                String username=Objects.toString(params.get(sql.contains("usuario_password")?params.size():1),"");
                Map<String,Object> profile=source.get(username);if(profile==null)yield 0;
                if(sql.contains("usuario_password")) {
                    byte[] hash=((String)params.get(1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-512");digest.update("synthetic-salt".getBytes());hash=digest.digest(hash);
                    for(int i=1;i<3;i++)hash=digest.digest(hash);profile.put("hash",java.util.HexFormat.of().formatHex(hash));profile.put("required",null);
                } else if(sql.contains("usuario_hash_auth")) profile.put("required",sql.contains("'UPDATE_PASSWORD'")?"UPDATE_PASSWORD":null);
                if(locked[0])transaction[0]=state;else write(state);yield 1;
            }
            default -> defaultValue(m.getReturnType());
        });
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> contract(Map<String,Object> state,Map<String,Object> input) {
        String action=(String)input.get("action");
        Map<String,Map<String,Object>> source=(Map)state.get("source"),operations=(Map)state.get("operations"),projected=(Map)state.get("projected");
        if(action.equals("authorize")) {
            boolean allowed=Objects.equals(state.get("adminSubject"),input.get("actorSubject"))&&"1".equals(input.get("destinationDbId"));
            if ("get".equals(input.get("operation")) && state.containsKey("listOnlySubject"))
                allowed = allowed && Objects.equals(state.get("listOnlySubject"), input.get("targetSubject"));
            if ("list".equals(input.get("operation"))) {
                Set<String> subjects = new HashSet<>();
                if (state.containsKey("listOnlySubject")) subjects.add((String)state.get("listOnlySubject"));
                else {
                    subjects.addAll((Collection<String>)state.getOrDefault("knownSubjects",List.of()));
                    subjects.addAll(projected.keySet());
                    source.keySet().forEach(name -> subjects.add("f:"+state.get("componentId")+":"+name));
                }
                return Map.of("authorized",allowed,"authorizedSubjects",subjects);
            }
            return Map.of("authorized",allowed);
        }
        if(action.equals("sourceUpdate")) {
            String subject=(String)input.get("targetSubject");String username=subject.substring(subject.lastIndexOf(':')+1);
            Map<String,Object> profile=source.get(username);if(profile==null)throw new IllegalStateException("Missing synthetic user");
            for(String field:List.of("name","email","enabled"))if(input.containsKey(field))profile.put(field,input.get(field));
            Map<String,Object> event=new LinkedHashMap<>(input);event.putAll(profile);event.put("operationId",UUID.randomUUID().toString());
            event.put("operation","update");event.put("version",System.currentTimeMillis());event.put("destinationDbId","1");event.put("status","pending");
            operations.put((String)event.get("operationId"),event);return Map.of("status","pending");
        }
        if(action.equals("enqueue")) {
            String id=(String)input.get("operationId");
            operations.computeIfAbsent(id,key->{Map<String,Object> event=new LinkedHashMap<>(input);event.putIfAbsent("destinationDbId","1");event.put("status","pending");return event;});
            return Map.of("operationId",id,"status",operations.get(id).get("status"));
        }
        if(action.equals("pending"))return Map.of("events",operations.values().stream().filter(e->"pending".equals(e.get("status"))).toList());
        if(action.equals("complete")) {
            Map<String,Object> event=operations.get(input.get("operationId"));if(event==null)throw new IllegalStateException("Missing synthetic operation");
            if(!Objects.equals(event.get("version"),input.get("version")))throw new IllegalStateException("Synthetic version mismatch");
            projected.put((String)event.get("targetSubject"),new LinkedHashMap<>(event));event.put("status","applied");return Map.of("status","applied");
        }
        if(action.equals("status")) {
            if (Objects.equals(state.get("hiddenStatusOperation"),input.get("operationId"))) return Map.of("status","notFound");
            if (Objects.equals(state.get("failedStatusOperation"),input.get("operationId"))) return Map.of("status","failed");
            Map<String,Object> event=operations.get(input.get("operationId"));if(Objects.equals(state.get("pendingGrantSubject"),input.get("targetSubject")))return Map.of("status","pending");return event==null?Map.of("status","notFound"):Map.of("status",event.get("status"));
        }
        throw new IllegalArgumentException("Unsupported synthetic contract");
    }
    @SuppressWarnings("unchecked")
    private static List<Object[]> accounts(Map<String,Object> state,String sql,Map<Integer,Object> params) {
        Map<String,Map<String,Object>> source=(Map)state.get("source");List<Object[]> rows=new ArrayList<>();
        Set<String> nativeBound=new HashSet<>((List<String>)state.getOrDefault("nativeBound",List.of()));
        boolean guard=sql.contains("subject <> concat('f:'");
        if(sql.startsWith("select count(*)"))return List.<Object[]>of(new Object[]{source.size() - (guard ? nativeBound.size() : 0)});
        for(var entry:source.entrySet()) {
            if(guard && nativeBound.contains(entry.getKey()))continue;
            if(sql.contains("where u.usuario_id = ?")&&!entry.getKey().equals(params.get(1)))continue;
            Map<String,Object> p=entry.getValue();
            if(sql.contains("lower(e.usuario_email_email)")&&!Objects.toString(p.get("email"),"").equalsIgnoreCase(Objects.toString(params.get(1),"")))continue;
            if(sql.contains("lower(u.usuario_id)")&&!entry.getKey().equalsIgnoreCase(Objects.toString(params.get(1),"")))continue;
            rows.add(new Object[]{entry.getKey(),p.get("hash"),"synthetic-salt",3,Boolean.TRUE.equals(p.get("enabled"))?"1":"0",p.get("email"),p.get("required"),p.get("name")});
        }
        if(sql.contains("order by u.usuario_id")) {
            int count=((Number)params.get(params.size()-1)).intValue(),first=((Number)params.get(params.size())).intValue();
            rows=rows.subList(Math.min(first,rows.size()),Math.min(first+count,rows.size()));
        }
        return rows;
    }
    private static ResultSet rows(List<Object[]> rows) {
        int[] index={-1};return proxy(ResultSet.class,(o,m,v)->switch(m.getName()) {
            case "next" -> ++index[0]<rows.size();
            case "getString" -> {Object value=rows.get(index[0])[(Integer)v[0]-1];yield value==null?null:value.toString();}
            case "getInt" -> ((Number)rows.get(index[0])[(Integer)v[0]-1]).intValue();
            default -> defaultValue(m.getReturnType());
        });
    }
    private static Path file(){return Path.of(System.getenv("GATOR_IDENTITY_ADMIN_FIXTURE"));}
    private static Map<String,Object> read() {try{return JsonSerialization.readValue(Files.readString(file()),Map.class);}catch(Exception e){throw new IllegalStateException("Synthetic fixture unavailable");}}
    private static void write(Map<String,Object> state) {try {Path next=file().resolveSibling(file().getFileName()+".next");Files.writeString(next,JsonSerialization.writeValueAsString(state));Files.move(next,file(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new IllegalStateException("Synthetic fixture unavailable");}}
    private static Object defaultValue(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;return null;}
    private static <T>T proxy(Class<T>type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
    @Override public boolean acceptsURL(String url){return url!=null&&url.equals("jdbc:gator-identity-admin-test:isolated");}
    @Override public DriverPropertyInfo[] getPropertyInfo(String url,Properties p){return new DriverPropertyInfo[0];}
    @Override public int getMajorVersion(){return 1;}@Override public int getMinorVersion(){return 0;}
    @Override public boolean jdbcCompliant(){return false;}
    @Override public java.util.logging.Logger getParentLogger(){return java.util.logging.Logger.getGlobal();}
}
