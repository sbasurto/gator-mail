create table if not exists mail_administradores (
    usuario_id text primary key references app_usuarios(usuario_id) on delete cascade
);

alter table app_usuarios alter column usuario_sesion_timeout set default 10800000;
alter table app_usuarios add column if not exists usuario_sms_auth boolean not null default true;

create table if not exists mail_usuario_telefonos (
    usuario_id text primary key references app_usuarios(usuario_id) on delete cascade,
    telefono text not null check (telefono ~ '^\+[1-9][0-9]{7,14}$'),
    global_safe_list boolean not null default false
);

create table if not exists mail_usuario_eliminaciones (
    eliminacion_id uuid primary key default uuid_generate_v4(),
    usuario_id text not null,
    destino_id text not null,
    actor text not null,
    estado text not null default 'PENDING' check (estado in ('PENDING', 'COMPLETED')),
    fecha timestamp without time zone not null default now(),
    fecha_completada timestamp without time zone
);

create unique index if not exists mail_usuario_eliminaciones_pendiente
    on mail_usuario_eliminaciones(usuario_id) where estado = 'PENDING';

insert into mail_administradores(usuario_id)
select distinct u.usuario_id
  from app_usuarios u
  left join app_usuario_email e on e.usuario_id = u.usuario_id
 where lower(u.usuario_id) = 'admin'
    or lower(e.usuario_email_email) = 'sbasurto@soft-gator.com'
on conflict do nothing;

revoke all on mail_administradores, mail_usuario_telefonos from public;

create or replace function mail_fn_es_admin(v_email text)
returns boolean language sql stable security definer set search_path = public as $$
    select exists (
        select 1
          from mail_administradores a
          join app_usuario_email e on e.usuario_id = a.usuario_id
         where lower(e.usuario_email_email) = lower(trim(v_email))
           and coalesce(e.usuario_email_estado, 0) >= 0
    );
$$;

create or replace function mail_fn_admin_access(v_email text)
returns text language sql stable security definer set search_path = public as $$
    select json_build_object('codigo', '0', 'admin', mail_fn_es_admin(v_email))::text;
$$;

create or replace function mail_fn_usuario_opciones(v_usuario text)
returns text language sql stable security definer set search_path = public as $$
    select json_build_object('codigo', case when count(*) = 1 then '0' else '-1' end,
           'smsEnabled', coalesce(bool_or(usuario_sms_auth), true))::text
      from app_usuarios where usuario_id = trim(v_usuario);
$$;

create or replace function mail_fn_usuario_opciones_guardar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := lower(trim(v ->> 'actor'));
    usuario text := trim(v ->> 'user');
begin
    if not exists (select 1 from app_usuario_email where usuario_id = usuario
                    and lower(usuario_email_email) = actor and coalesce(usuario_email_estado, 0) >= 0) then
        raise exception 'No puedes modificar las opciones de otro usuario';
    end if;
    update app_usuarios set usuario_sms_auth = coalesce((v ->> 'smsEnabled')::boolean, false)
     where usuario_id = usuario;
    if not found then raise exception 'Usuario inexistente'; end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuarios(v_email text)
returns text language plpgsql stable security definer set search_path = public as $$
declare resultado json;
begin
    if not mail_fn_es_admin(v_email) then raise exception 'Acceso administrativo denegado'; end if;
    select coalesce(json_agg(json_build_object(
               'id', u.usuario_id, 'name', coalesce(u.usuario_nombre, ''),
               'email', coalesce(e.usuario_email_email, ''), 'enabled', u.usuario_estado = '1',
               'phone', coalesce(t.telefono, ''), 'safeListed', coalesce(t.global_safe_list, false),
               'sessionTimeoutMinutes', greatest(1, coalesce(u.usuario_sesion_timeout, 10800000) / 60000)
           ) order by u.usuario_id), '[]'::json)
      into resultado
      from app_usuarios u
      left join lateral (
          select usuario_email_email from app_usuario_email
           where usuario_id = u.usuario_id and coalesce(usuario_email_estado, 0) >= 0
           order by usuario_email_por_defecto desc nulls last, rowid limit 1
      ) e on true
      left join mail_usuario_telefonos t on t.usuario_id = u.usuario_id;
    return json_build_object('codigo', '0', 'usuarios', resultado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuario_guardar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := trim(v ->> 'actor');
    enabled boolean := coalesce((v ->> 'enabled')::boolean, false);
    nombre text := trim(v ->> 'name');
    session_timeout_minutes integer := coalesce((v ->> 'sessionTimeoutMinutes')::integer, 180);
    telefono text := nullif(regexp_replace(trim(coalesce(v ->> 'phone', '')), '[\s().-]', '', 'g'), '');
    usuario text := trim(v ->> 'user');
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    if usuario is null or usuario = '' or length(usuario) > 320 or length(nombre) > 200
            or session_timeout_minutes not between 1 and 10080
            or (telefono is not null and telefono !~ '^\+[1-9][0-9]{7,14}$') then
        raise exception 'Usuario inválido';
    end if;
    if not enabled and exists (
        select 1 from app_usuario_email where usuario_id = usuario
         and lower(usuario_email_email) = lower(actor)
    ) then raise exception 'No puedes desactivar tu propia cuenta'; end if;
    update app_usuarios set usuario_nombre = nombre, usuario_estado = case when enabled then '1' else '0' end,
           usuario_sesion_timeout = session_timeout_minutes * 60000
     where usuario_id = usuario;
    if not found then raise exception 'Usuario inexistente'; end if;
    if telefono is not null then
        insert into mail_usuario_telefonos(usuario_id, telefono)
        values (usuario, telefono)
        on conflict (usuario_id) do update
          set telefono = excluded.telefono,
              global_safe_list = mail_usuario_telefonos.global_safe_list
                    and mail_usuario_telefonos.telefono = excluded.telefono;
    end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuario_crear(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := trim(v ->> 'actor');
    correo text := lower(trim(v ->> 'email'));
    nombre text := trim(v ->> 'name');
    password text := v ->> 'password';
    session_timeout_minutes integer := coalesce((v ->> 'sessionTimeoutMinutes')::integer, 180);
    usuario text := lower(trim(v ->> 'user'));
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    if usuario !~ '^[a-z_][a-z0-9_.-]{0,31}$'
            or correo !~ '^[a-z0-9.!#$%&''*+/=?^_`{|}~-]+@[a-z0-9.-]+\.[a-z]{2,63}$'
            or nombre = '' or length(nombre) > 200
            or password !~ '^[A-Za-z0-9_-]{24}$'
            or session_timeout_minutes not between 1 and 10080 then
        raise exception 'Datos del usuario inválidos';
    end if;
    if exists (select 1 from app_usuarios where lower(usuario_id) = usuario)
            or exists (select 1 from app_usuario_email where lower(usuario_email_email) = correo
                       and coalesce(usuario_email_estado, 0) >= 0) then
        raise exception 'El usuario o correo ya existe';
    end if;
    insert into app_usuarios(usuario_id, usuario_password, usuario_nombre, usuario_estado,
                             usuario_hash_auth, usuario_sesion_timeout)
    values (usuario, password, nombre, '1', 'UPDATE_PASSWORD', session_timeout_minutes * 60000);
    insert into app_usuario_email(usuario_email_email, usuario_email_estado, usuario_id, usuario_email_por_defecto)
    values (correo, 1, usuario, 1);
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuario_safe_list(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := trim(v ->> 'actor');
    v_telefono text := trim(v ->> 'phone');
    usuario text := trim(v ->> 'user');
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    update mail_usuario_telefonos set global_safe_list = true
     where usuario_id = usuario and telefono = v_telefono;
    if not found then raise exception 'El teléfono registrado no coincide'; end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuario_eliminar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor_email text := lower(trim(v ->> 'actor'));
    destino text := trim(v ->> 'destination');
    token uuid;
    usuario text := trim(v ->> 'user');
begin
    if not mail_fn_es_admin(actor_email) then raise exception 'Acceso administrativo denegado'; end if;
    if usuario is null or usuario = '' or destino is null or destino = '' or usuario = destino then
        raise exception 'Selecciona una cuenta genérica distinta';
    end if;
    if exists (select 1 from app_usuario_email where usuario_id = usuario
                and lower(usuario_email_email) = actor_email and coalesce(usuario_email_estado, 0) >= 0) then
        raise exception 'No puedes eliminar tu propia cuenta';
    end if;
    if not exists (select 1 from app_usuarios where usuario_id = usuario) then
        raise exception 'Usuario inexistente';
    end if;
    if not exists (select 1 from app_usuarios u join app_usuario_email e using (usuario_id)
                    where u.usuario_id = destino and u.usuario_estado = '1'
                      and coalesce(e.usuario_email_estado, 0) >= 0) then
        raise exception 'La cuenta genérica debe estar activa y tener correo';
    end if;
    if coalesce((v ->> 'confirmed')::boolean, false) then
        token := nullif(v ->> 'token', '')::uuid;
        if not exists (select 1 from mail_usuario_eliminaciones where eliminacion_id = token
                        and usuario_id = usuario and destino_id = destino and estado = 'PENDING') then
            raise exception 'El traslado del buzón no está autorizado';
        end if;
        delete from app_usuarios where usuario_id = usuario;
        update mail_usuario_eliminaciones set estado = 'COMPLETED', fecha_completada = now()
         where eliminacion_id = token;
        return json_build_object('codigo', '0')::text;
    end if;
    select eliminacion_id into token from mail_usuario_eliminaciones
     where usuario_id = usuario and destino_id = destino and estado = 'PENDING';
    if token is null and exists (select 1 from mail_usuario_eliminaciones
                                 where usuario_id = usuario and estado = 'PENDING') then
        raise exception 'Ya existe un traslado pendiente hacia otra cuenta';
    end if;
    if token is null then
        insert into mail_usuario_eliminaciones(usuario_id, destino_id, actor)
        values (usuario, destino, actor_email) returning eliminacion_id into token;
    end if;
    return json_build_object('codigo', '0', 'token', token)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_usuario_reset(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := trim(v ->> 'actor');
    password text := v ->> 'password';
    usuario text := trim(v ->> 'user');
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    if usuario is null or usuario = '' or password is null or password !~ '^[A-Za-z0-9_-]{24}$' then
        raise exception 'Solicitud de restablecimiento inválida';
    end if;
    update app_usuarios
       set usuario_password = password, usuario_hash_auth = 'UPDATE_PASSWORD'
     where usuario_id = usuario;
    if not found then raise exception 'Usuario inexistente'; end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_contactos(v_email text)
returns text language plpgsql stable security definer set search_path = public as $$
declare resultado json;
begin
    if not mail_fn_es_admin(v_email) then raise exception 'Acceso administrativo denegado'; end if;
    with emails as (
        select distinct on (contacto_id) contacto_id, contacto_email_email
          from app_contacto_email where coalesce(contacto_email_estado, 0) >= 0
         order by contacto_id, rowid
    ), grupos as (
        select contacto_id, string_agg(grupo_id, ', ' order by grupo_id) grupo_id
          from app_grupo_contacto group by contacto_id
    )
    select coalesce(json_agg(json_build_object(
               'id', c.contacto_id,
               'name', concat_ws(' ', nullif(c.contacto_nombre, ''), nullif(c.contacto_apellido_p, ''),
                    nullif(c.contacto_apellido_m, '')),
               'email', coalesce(e.contacto_email_email, ''),
               'owner', coalesce(c.usuario_id, ''), 'group', coalesce(g.grupo_id, '')
           ) order by c.contacto_nombre, e.contacto_email_email), '[]'::json)
      into resultado
      from app_contactos c
      left join emails e using (contacto_id)
      left join grupos g using (contacto_id);
    return json_build_object('codigo', '0', 'contactos', resultado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_contacto_guardar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    actor text := trim(v ->> 'actor');
    contacto text := nullif(trim(v ->> 'id'), '');
    correo text := lower(trim(v ->> 'email'));
    grupo text := nullif(trim(v ->> 'group'), '');
    nombre text := trim(v ->> 'name');
    propietario text := nullif(trim(v ->> 'owner'), '');
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    if nombre is null or nombre = '' or length(nombre) > 200
            or correo !~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'
            or length(correo) > 320 or (propietario is null and grupo is null) then
        raise exception 'Contacto inválido';
    end if;
    if propietario is not null and not exists (select 1 from app_usuarios where usuario_id = propietario) then
        raise exception 'Usuario propietario inexistente';
    end if;
    if grupo is not null and exists (
        select 1 from regexp_split_to_table(grupo, ',') value
         where not exists (select 1 from broker_usuario_grupo where grupo_id = trim(value))
    ) then
        raise exception 'Grupo inexistente';
    end if;
    if contacto is null then
        contacto := uuid_generate_v4()::text;
        insert into app_contactos(contacto_id, contacto_nombre, cuenta_id, bodega_id, usuario_id)
        values (contacto, nombre, 0, 0, propietario);
        insert into app_contacto_email(contacto_email_email, contacto_email_estado, contacto_id)
        values (correo, 1, contacto);
    else
        update app_contactos set contacto_nombre = nombre, contacto_apellido_p = null,
               contacto_apellido_m = null, usuario_id = propietario where contacto_id = contacto;
        if not found then raise exception 'Contacto inexistente'; end if;
        update app_contacto_email set contacto_email_email = correo, contacto_email_estado = 1
         where rowid = (select min(rowid) from app_contacto_email where contacto_id = contacto);
        if not found then
            insert into app_contacto_email(contacto_email_email, contacto_email_estado, contacto_id)
            values (correo, 1, contacto);
        end if;
    end if;
    delete from app_grupo_contacto where contacto_id = contacto;
    if grupo is not null then
        insert into app_grupo_contacto(grupo_id, contacto_id, cuenta_id, bodega_id)
        select distinct trim(value), contacto, 0, 0 from regexp_split_to_table(grupo, ',') value;
    end if;
    return json_build_object('codigo', '0', 'id', contacto)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_admin_contacto_eliminar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare v jsonb := v_json::jsonb;
begin
    if not mail_fn_es_admin(trim(v ->> 'actor')) then raise exception 'Acceso administrativo denegado'; end if;
    delete from app_contactos where contacto_id = trim(v ->> 'id');
    if not found then raise exception 'Contacto inexistente'; end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

revoke all on function mail_fn_es_admin(text), mail_fn_admin_access(text), mail_fn_admin_usuarios(text),
    mail_fn_admin_usuario_guardar(text), mail_fn_admin_usuario_safe_list(text),
    mail_fn_admin_usuario_reset(text), mail_fn_admin_usuario_eliminar(text), mail_fn_admin_contactos(text),
    mail_fn_admin_contacto_guardar(text), mail_fn_admin_contacto_eliminar(text) from public;
grant execute on function mail_fn_admin_usuario_reset(text) to w3apps;
grant execute on function mail_fn_admin_usuario_eliminar(text) to w3apps;
