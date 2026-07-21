create or replace function public.app_fn_has_mail_access(v_json text)
returns text
language plpgsql
stable
as $function$
declare
    i_email text;
    i_usuario text := btrim(utils_fn_coalesce(utils_fn_get_json(v_json::json, 'usuario'), ''));
begin
    if i_usuario = '' or not exists (
        select 1
          from broker_usuario_grupo bug
          join broker_grupo_servidor_db bgsd on bgsd.grupo_id = bug.grupo_id
          join broker_db bd on bd.db_id = bgsd.db_id
         where bug.usuario_id = i_usuario and bd.db_use = 'mail'
    ) then
        return json_build_object('codigo', '-1', 'mensaje', 'No tienes acceso a Gator Mail')::text;
    end if;

    select usuario_email_email into i_email
      from app_usuario_email
     where usuario_id = i_usuario and btrim(usuario_email_email) <> ''
     order by usuario_email_por_defecto desc nulls last, rowid
     limit 1;

    if utils_fn_is_empty(i_email) then
        return json_build_object('codigo', '-1', 'mensaje', 'No tienes un buzón asociado')::text;
    end if;
    return json_build_object('codigo', '0', 'email', i_email)::text;
end;
$function$;
