create extension if not exists "uuid-ossp";
create extension if not exists pgcrypto;

create sequence if not exists app_usuarios_rowid_seq;
create sequence if not exists app_usuario_email_rowid_seq;

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
    usuario_sesion_timeout integer default 3600000,
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

drop trigger if exists app_trg_usuarios on app_usuarios;
create trigger app_trg_usuarios
before insert or update of usuario_password on app_usuarios
for each row execute function app_usuarios_fn_encode2();
