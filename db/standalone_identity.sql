create extension if not exists "uuid-ossp";
create extension if not exists pgcrypto;
create extension if not exists dblink;

create sequence if not exists app_usuarios_rowid_seq;
create sequence if not exists app_usuario_email_rowid_seq;
create sequence if not exists broker_db_rowid_seq;
create sequence if not exists broker_db_db_id_seq;

create table if not exists broker_db (
    rowid integer not null default nextval('broker_db_rowid_seq'),
    db_id integer primary key default nextval('broker_db_db_id_seq'),
    db_user text not null,
    db_passwd text not null,
    db_type text not null check (db_type in ('SEC', 'DAT')),
    db_port text not null,
    db_sid text not null,
    db_name text not null,
    db_kind varchar(50) default 'pgsql',
    db_use text default 'wms',
    db_grupo text default uuid_generate_v4(),
    db_icon text default 'fa-circle',
    db_path text default 'none',
    db_cfile text default 'init',
    db_conf_file text default 'pgdefault',
    db_conf_file2 text default 'pg_app_db',
    db_context_path text default '',
    db_hostaddr text default '127.0.0.1',
    db_comment text
);

revoke all on broker_db from public;

create table if not exists app_usuarios (
    rowid bigint not null default nextval('app_usuarios_rowid_seq'),
    usuario_id text primary key,
    usuario_password text not null,
    usuario_nombre text,
    usuario_estado text default '0',
    usuario_cargo text,
    usuario_rfc text,
    usuario_idioma text default 'es',
    usuario_debug_level text default '0',
    usuario_hash_loops text default ((random() * 20000)::integer),
    usuario_password_corto text,
    usuario_recover_hash text default uuid_generate_v4(),
    usuario_sesion_timeout integer default 10800000,
    usuario_publico integer default 0,
    usuario_hash_auth text
);

create table if not exists app_usuario_email (
    rowid bigint not null default nextval('app_usuario_email_rowid_seq'),
    usuario_email_id text primary key default uuid_generate_v4(),
    usuario_email_email text not null,
    usuario_email_estado integer default 0,
    usuario_email_codigo text,
    usuario_id text not null references app_usuarios(usuario_id) on delete cascade,
    usuario_email_por_defecto integer default 0,
    usuario_email_validado boolean default false,
    usuario_email_fecha timestamp without time zone default now()
);

create table if not exists app_usuario_mail (
    usuario_id text references app_usuarios(usuario_id) on delete cascade,
    mail_domain text not null,
    mail_os_uid text not null,
    mail_os_gid text not null,
    mail_home text not null,
    mail_soft_expiration_date timestamp without time zone,
    mail_hard_expiration_date timestamp without time zone,
    primary key (usuario_id, mail_domain)
);

\ir mail_carpetas.sql

create or replace function app_fn_hash_password(v_password text, v_salt text, v_loops integer)
returns text
language plpgsql
immutable strict
as $function$
declare
    value bytea := digest(convert_to(v_salt || v_password, 'UTF8'), 'sha512');
begin
    for i in 2..greatest(v_loops, 1) loop
        value := digest(value, 'sha512');
    end loop;
    return encode(value, 'hex');
end;
$function$;

create or replace function app_usuarios_fn_encode2()
returns trigger
language plpgsql
as $function$
begin
    if tg_op = 'INSERT' or new.usuario_password is distinct from old.usuario_password then
        if new.usuario_password ~ '^[0-9A-Fa-f]{128}$'
                and new.usuario_recover_hash is not null
                and new.usuario_hash_loops::integer > 0 then
            new.usuario_password := lower(new.usuario_password);
        else
            new.usuario_recover_hash := uuid_generate_v4();
            new.usuario_hash_loops := 3000 + floor(random() * 17001)::integer;
            new.usuario_password := app_fn_hash_password(
                    new.usuario_password, new.usuario_recover_hash, new.usuario_hash_loops::integer);
        end if;
    end if;
    return new;
end;
$function$;

create or replace function app_fn_admon_tablas_all(v_json text)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
    connection_name text;
    configured integer := 0;
    password text := nullif(btrim(v_json::jsonb ->> 'password'), '');
    result text;
    skipped integer := 0;
    synchronized integer := 0;
    target record;
    username text := nullif(btrim(v_json::jsonb ->> 'usuario'), '');
begin
    if username is null or password is null then
        raise exception 'usuario and password are required';
    end if;

    for target in
        select db_id, db_hostaddr, db_port, db_sid, db_user, db_passwd
          from public.broker_db
         where db_kind = 'pgsql' and db_type = 'DAT' and db_use in ('store', 'wms')
         order by db_id
    loop
        configured := configured + 1;
        connection_name := 'gator_mail_sync_' || target.db_id;
        perform public.dblink_connect(connection_name, format(
                'hostaddr=%s port=%s dbname=%s user=%s password=%s',
                target.db_hostaddr, target.db_port, target.db_sid, target.db_user, target.db_passwd));
        begin
            result := public.dblink_exec(connection_name, format(
                    'update public.app_usuarios set usuario_password = %L where lower(usuario_id) = lower(%L)',
                    password, username));
            perform public.dblink_disconnect(connection_name);
        exception when others then
            perform public.dblink_disconnect(connection_name);
            raise;
        end;
        if result = 'UPDATE 1' then
            synchronized := synchronized + 1;
        elsif result = 'UPDATE 0' then
            skipped := skipped + 1;
        else
            raise exception 'Unexpected synchronization result % in %', result, target.db_sid;
        end if;
    end loop;

    if configured = 0 then
        raise exception 'No synchronization targets configured in broker_db';
    end if;
    return json_build_object('codigo', '0', 'sincronizadas', synchronized, 'omitidas', skipped)::text;
end;
$function$;

revoke all on function app_fn_admon_tablas_all(text) from public;

drop trigger if exists app_trg_usuarios on app_usuarios;
create trigger app_trg_usuarios
before insert or update of usuario_password on app_usuarios
for each row execute function app_usuarios_fn_encode2();
