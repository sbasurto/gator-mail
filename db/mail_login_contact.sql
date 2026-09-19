-- Standalone db_gatormail counterpart of gator-security/db/mail_sms_endpoint.sql.
-- Business databases retain their existing app_usuario_telefono implementation.
create or replace function public.mail_fn_sync_gatormail_user(v_json text)
returns text language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v jsonb := v_json::jsonb;
    username text := btrim(coalesce(v ->> 'usuario', ''));
    email text := lower(btrim(coalesce(v ->> 'email', '')));
    phone text := regexp_replace(btrim(coalesce(v ->> 'telefono', '')), '[\s().-]', '', 'g');
    email_id text;
begin
    if username = '' or length(username) > 320 or not exists (
        select 1 from app_usuarios where usuario_id = username and usuario_estado = '1'
    ) then raise exception 'Usuario no válido'; end if;
    if phone !~ '^\+[1-9][0-9]{7,14}$' then raise exception 'El teléfono no es válido'; end if;
    if email !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$' then
        raise exception 'El correo no es válido';
    end if;
    perform pg_advisory_xact_lock(hashtext('mail_login_contact'));
    select usuario_email_id into email_id from app_usuario_email
     where usuario_id = username and lower(usuario_email_email) = email order by rowid limit 1;
    if email_id is null then
        if exists (select 1 from app_usuario_email
                   where lower(usuario_email_email) = email and usuario_id <> username) then
            raise exception 'El correo pertenece a otro usuario';
        end if;
        insert into app_usuario_email(usuario_id, usuario_email_email, usuario_email_estado)
        values (username, email, 1) returning usuario_email_id into email_id;
    end if;
    update app_usuario_email set usuario_email_por_defecto =
        case when usuario_email_id = email_id then 1 else 0 end where usuario_id = username;
    insert into mail_usuario_telefonos(usuario_id, telefono) values (username, phone)
    on conflict (usuario_id) do update set telefono = excluded.telefono,
        global_safe_list = mail_usuario_telefonos.global_safe_list
            and mail_usuario_telefonos.telefono = excluded.telefono;
    return json_build_object('codigo', '0', 'usuario', username)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;
revoke all on function public.mail_fn_sync_gatormail_user(text) from public;
grant execute on function public.mail_fn_sync_gatormail_user(text) to w3apps;
