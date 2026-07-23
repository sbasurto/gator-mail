create table if not exists mail_administradores (
    usuario_id text primary key references app_usuarios(usuario_id) on delete cascade
);

insert into mail_administradores(usuario_id)
select distinct u.usuario_id
  from app_usuarios u
  left join app_usuario_email e on e.usuario_id = u.usuario_id
 where lower(u.usuario_id) = 'admin'
    or lower(e.usuario_email_email) = 'sbasurto@soft-gator.com'
on conflict do nothing;

revoke all on mail_administradores from public;

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

create or replace function mail_fn_admin_usuarios(v_email text)
returns text language plpgsql stable security definer set search_path = public as $$
declare resultado json;
begin
    if not mail_fn_es_admin(v_email) then raise exception 'Acceso administrativo denegado'; end if;
    select coalesce(json_agg(json_build_object(
               'id', u.usuario_id, 'name', coalesce(u.usuario_nombre, ''),
               'email', coalesce(e.usuario_email_email, ''), 'enabled', u.usuario_estado = '1'
           ) order by u.usuario_id), '[]'::json)
      into resultado
      from app_usuarios u
      left join lateral (
          select usuario_email_email from app_usuario_email
           where usuario_id = u.usuario_id and coalesce(usuario_email_estado, 0) >= 0
           order by usuario_email_por_defecto desc nulls last, rowid limit 1
      ) e on true;
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
    usuario text := trim(v ->> 'user');
begin
    if not mail_fn_es_admin(actor) then raise exception 'Acceso administrativo denegado'; end if;
    if usuario is null or usuario = '' or length(usuario) > 320 or length(nombre) > 200 then
        raise exception 'Usuario inválido';
    end if;
    if not enabled and exists (
        select 1 from app_usuario_email where usuario_id = usuario
         and lower(usuario_email_email) = lower(actor)
    ) then raise exception 'No puedes desactivar tu propia cuenta'; end if;
    update app_usuarios set usuario_nombre = nombre, usuario_estado = case when enabled then '1' else '0' end
     where usuario_id = usuario;
    if not found then raise exception 'Usuario inexistente'; end if;
    return json_build_object('codigo', '0')::text;
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
    mail_fn_admin_usuario_guardar(text), mail_fn_admin_usuario_reset(text), mail_fn_admin_contactos(text),
    mail_fn_admin_contacto_guardar(text), mail_fn_admin_contacto_eliminar(text) from public;
grant execute on function mail_fn_admin_usuario_reset(text) to w3apps;
