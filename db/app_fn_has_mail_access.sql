create or replace function public.app_fn_has_mail_access(v_json text)
returns text
language plpgsql
stable
security definer
set search_path = public
as $function$
declare
    i_email text;
    i_session_timeout integer;
    i_usuario text := btrim(coalesce(v_json::jsonb ->> 'usuario', ''));
begin
    select e.usuario_email_email, coalesce(u.usuario_sesion_timeout, 10800000)
      into i_email, i_session_timeout
      from app_usuarios u
      join app_usuario_email e on e.usuario_id = u.usuario_id
      join app_usuario_mail m on m.usuario_id = u.usuario_id
     where u.usuario_estado = '1'
       and coalesce(e.usuario_email_estado, 0) >= 0
       and (lower(u.usuario_id) = lower(i_usuario)
            or lower(e.usuario_email_email) = lower(i_usuario))
       and lower(e.usuario_email_email) = lower(u.usuario_id || '@' || m.mail_domain)
     order by e.usuario_email_por_defecto desc nulls last, e.rowid
     limit 1;

    if i_email is null then
        return json_build_object('codigo', '-1', 'mensaje', 'No tienes un buzón asociado')::text;
    end if;
    return json_build_object('codigo', '0', 'email', i_email,
            'sessionTimeout', i_session_timeout)::text;
end;
$function$;

revoke all on function public.app_fn_has_mail_access(text) from public;
grant execute on function public.app_fn_has_mail_access(text) to w3apps;
