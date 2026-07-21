-- Directorio de contactos autónomo y compatible por columnas con Gator E.
-- Se omiten únicamente llaves foráneas hacia tablas ajenas al módulo de correo.
create extension if not exists "uuid-ossp";

create table if not exists app_contactos (
    rowid serial unique not null,
    contacto_id text primary key default uuid_generate_v4(),
    contacto_idctrl text unique,
    contacto_nombre text,
    contacto_apellido_p text,
    contacto_apellido_m text,
    contacto_apodo text,
    contacto_profesion text,
    contacto_cia text,
    contacto_titulo text,
    contacto_depto text,
    contacto_asistente text,
    contacto_jefe text,
    contacto_pareja text,
    contacto_cumpleanos timestamp without time zone,
    contacto_aniversario timestamp without time zone,
    contacto_observaciones text,
    imagen_id text default 'no-image-person.png',
    cuenta_id integer not null,
    bodega_id integer not null,
    usuario_id text
);

create table if not exists app_contacto_email (
    rowid bigserial not null,
    contacto_email_id text primary key default uuid_generate_v4(),
    contacto_email_email text not null,
    contacto_email_estado integer default 0,
    contacto_email_codigo text,
    contacto_id text not null references app_contactos(contacto_id) on delete cascade
);

create table if not exists app_email (
    rowid serial unique not null,
    email_id text primary key,
    email_idctrl text unique,
    email_categoria text,
    email_email text
);

create table if not exists app_grupo_contacto (
    rowid serial unique not null,
    gpo_contacto_id serial primary key,
    grupo_id text not null,
    contacto_id text not null references app_contactos(contacto_id) on delete cascade,
    cuenta_id integer not null,
    bodega_id integer not null,
    unique (contacto_id, grupo_id, cuenta_id, bodega_id)
);

create table if not exists app_grupo_email (
    rowid serial unique not null,
    gpo_email_id serial primary key,
    grupo_id text not null,
    email_id text not null references app_email(email_id) on delete cascade,
    cuenta_id integer not null,
    bodega_id integer not null,
    contacto_id text not null references app_contactos(contacto_id) on delete cascade,
    unique (email_id, grupo_id, cuenta_id, bodega_id)
);

create table if not exists app_usuario_email (
    rowid bigserial not null,
    usuario_email_id text primary key default uuid_generate_v4(),
    usuario_email_email text not null,
    usuario_email_estado integer default 0,
    usuario_email_codigo text,
    usuario_id text not null,
    usuario_email_por_defecto integer default 0,
    usuario_email_validado boolean default false,
    usuario_email_fecha timestamp without time zone default now()
);

create table if not exists broker_usuario_grupo (
    rowid serial not null,
    usr_grupo_id serial primary key,
    usuario_id varchar(30) not null,
    grupo_id text not null,
    unique (usuario_id, grupo_id)
);

create index if not exists app_contactos_usuario_idx on app_contactos(usuario_id, contacto_id);
create index if not exists app_usuario_email_lookup_idx on app_usuario_email(lower(usuario_email_email), usuario_id);
create index if not exists app_contacto_email_lookup_idx on app_contacto_email(contacto_id, contacto_email_estado);

create or replace function mail_fn_get_contactos(v_email text)
returns text
language plpgsql
stable
as $$
declare
    resultado json;
begin
    if v_email is null or length(trim(v_email)) > 320 or position('@' in v_email) < 2 then
        return json_build_object('codigo', '-1', 'mensaje', 'Usuario de correo inválido')::text;
    end if;

    with usuarios as (
        select distinct usuario_id
          from app_usuario_email
         where lower(usuario_email_email) = lower(trim(v_email))
           and coalesce(usuario_email_estado, 0) >= 0
    ), contactos as (
        select c.contacto_id,
               concat_ws(' ', nullif(c.contacto_nombre, ''), nullif(c.contacto_apellido_p, ''),
                    nullif(c.contacto_apellido_m, '')) nombre,
               e.email_email email
          from usuarios u
          join broker_usuario_grupo ug on ug.usuario_id = u.usuario_id
          join app_grupo_email ge on ge.grupo_id = ug.grupo_id
          join app_email e on e.email_id = ge.email_id
          left join app_contactos c on c.contacto_id = ge.contacto_id
        union
        select c.contacto_id,
               concat_ws(' ', nullif(c.contacto_nombre, ''), nullif(c.contacto_apellido_p, ''),
                    nullif(c.contacto_apellido_m, '')) nombre,
               ce.contacto_email_email email
          from usuarios u
          join broker_usuario_grupo ug on ug.usuario_id = u.usuario_id
          join app_grupo_contacto gc on gc.grupo_id = ug.grupo_id
          join app_contactos c on c.contacto_id = gc.contacto_id
          join app_contacto_email ce on ce.contacto_id = c.contacto_id
        union
        select c.contacto_id,
               concat_ws(' ', nullif(c.contacto_nombre, ''), nullif(c.contacto_apellido_p, ''),
                    nullif(c.contacto_apellido_m, '')) nombre,
               ce.contacto_email_email email
          from usuarios u
          join app_contactos c on c.usuario_id = u.usuario_id
          join app_contacto_email ce on ce.contacto_id = c.contacto_id
    ), limpios as (
        select distinct on (lower(trim(email))) contacto_id,
               coalesce(nullif(trim(nombre), ''), trim(email)) nombre, lower(trim(email)) email
          from contactos
         where email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'
         order by lower(trim(email)), nullif(trim(nombre), '') nulls last
         limit 500
    )
    select coalesce(json_agg(json_build_object('id', contacto_id, 'nombre', nombre, 'email', email)), '[]'::json)
      into resultado
      from limpios;

    return json_build_object('codigo', '0', 'contactos', resultado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;
