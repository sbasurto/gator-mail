-- Called by the private rollback fixture, with the same canonical function bodies.
insert into app_usuarios values
 ('fixture-admin','Admin','Original admin credential','1',10800000),
 ('fixture-namesake','Namesake','Original namesake credential','1',10800000),
 ('fixture-native','Native','Closed native credential','0',10800000),
 ('fixture-legacy','Legacy','Original legacy credential','0',10800000),
 ('fixture-review','Review','Original review credential','1',10800000);
insert into mail_administradores values('fixture-admin');
insert into app_usuario_email(usuario_id,usuario_email_email,usuario_email_estado,usuario_email_por_defecto) values
 ('fixture-admin','admin@example.invalid',1,1),('fixture-namesake','admin@example.invalid',1,1),
 ('fixture-native','native@example.invalid',1,1),('fixture-legacy','legacy@example.invalid',1,1),('fixture-review','review@example.invalid',1,1);
insert into app_oidc_identity(issuer,subject,usuario_id,enabled) values
 ('https://id.example/realms/gator','11111111-1111-1111-1111-111111111111','fixture-native',true),
 ('https://id.example/realms/gator','f:fixture:legacy','fixture-legacy',true);
insert into broker_usuario_grupo values(1,'fixture-native','Original profile'),(2,'fixture-legacy','Original legacy profile');
insert into app_usuario_mail values('fixture-legacy','example.invalid','1001','1001','/home/exampleinvalid/fixture-legacy');
create temporary table fixture_users_before as table app_usuarios;
create temporary table fixture_profiles_before as table broker_usuario_grupo;
do $test$
declare response jsonb; token text;
begin
 response:=pg_temp.mail_fn_identity_authorize('{"usuario":"fixture-namesake"}')::jsonb;
 if response#>>'{responses,0,response,authorized}' is distinct from 'false' then raise exception 'Email namesake inherited Mail administrator';end if;
 response:=pg_temp.mail_fn_identity_authorize('{"usuario":"fixture-admin"}')::jsonb;
 if response#>>'{responses,0,response,authorized}' is distinct from 'true' then raise exception 'Exact administrator denied';end if;
 response:=pg_temp.mail_fn_admin_identity_directory('{"actor":"fixture-admin"}')::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' or jsonb_array_length(response#>'{responses,0,response,usuarios}')<>5
    or response::text like '%credential%' then raise exception 'Exact directory rejected or leaked credentials';end if;
 response:=pg_temp.mail_fn_admin_usuario_preferencias('{"actor":"fixture-admin","user":"fixture-native","sessionTimeoutMinutes":30}')::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' or (select usuario_sesion_timeout from app_usuarios where usuario_id='fixture-native')<>1800000 then raise exception 'Preferences failed';end if;
 response:=pg_temp.mail_fn_admin_usuario_preferencias('{"actor":"fixture-admin","user":"fixture-native","sessionTimeoutMinutes":30,"name":"Forbidden identity edit"}')::jsonb;
 if response#>>'{responses,0,resNum}'='0' then raise exception 'Identity field accepted through preferences';end if;
 response:=pg_temp.mail_fn_admin_buzon_registrar('{"actor":"fixture-admin","user":"fixture-native","targetIssuer":"https://id.example/realms/gator","targetSubject":"f:fixture:wrong","action":"preflight"}')::jsonb;
 if response#>>'{responses,0,resNum}'='0' then raise exception 'Wrong native binding authorized';end if;
 response:=pg_temp.mail_fn_admin_buzon_registrar('{"actor":"fixture-admin","user":"fixture-native","targetIssuer":"https://id.example/realms/gator","targetSubject":"11111111-1111-1111-1111-111111111111","action":"register","mailDomain":"example.invalid","mailOsUid":"1000","mailOsGid":"1000","mailHome":"/home/exampleinvalid/fixture-native"}')::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' then raise exception 'Native mailbox registration failed: %',response;end if;
 response:=pg_temp.mail_fn_admin_buzon_registrar('{"actor":"fixture-admin","user":"fixture-native","targetIssuer":"https://id.example/realms/gator","targetSubject":"11111111-1111-1111-1111-111111111111","action":"register","mailDomain":"example.invalid","mailOsUid":"1000","mailOsGid":"1000","mailHome":"/home/exampleinvalid/fixture-native"}')::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' or (select count(*) from app_usuario_mail where usuario_id='fixture-native')<>1 then raise exception 'Registration retry was not idempotent';end if;
 response:=pg_temp.mail_fn_admin_buzon_retirar('{"actor":"fixture-admin","user":"fixture-legacy","destination":"fixture-review","targetIssuer":"https://id.example/realms/gator","targetSubject":"f:fixture:legacy"}')::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' then raise exception 'Retirement preflight failed: %',response;end if;
 token:=response#>>'{responses,0,response,token}';
 response:=pg_temp.mail_fn_admin_buzon_retirar(jsonb_build_object('actor','fixture-admin','user','fixture-legacy','destination','fixture-review','targetIssuer','https://id.example/realms/gator','targetSubject','f:fixture:legacy','token',token,'confirmed',true)::text)::jsonb;
 if response#>>'{responses,0,resNum}'<>'0' or exists(select 1 from app_usuario_mail where usuario_id='fixture-legacy') then raise exception 'Physical mailbox was not retired';end if;
 if (select count(*) from app_oidc_identity)<>2 or exists(
    select (to_jsonb(u)-'usuario_sesion_timeout') from app_usuarios u except select (to_jsonb(u)-'usuario_sesion_timeout') from fixture_users_before u)
    or exists(select * from broker_usuario_grupo except select * from fixture_profiles_before)
    or (select count(*) from app_usuarios)<>5 or (select count(*) from broker_usuario_grupo)<>2 then raise exception 'Identity, credentials or business profiles changed';end if;
end $test$;
