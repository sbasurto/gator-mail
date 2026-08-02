create or replace function mail_fn_cache_remitentes(v_email text)
returns text
language sql
stable
security definer
set search_path = pg_catalog, public
as $function$
    with messages as (
        select r.mensaje_remitente, r.mensaje_fecha
          from public.mail_mensajes_resumen r
          join public.mail_carpetas c on c.carpeta_id = r.carpeta_id
         where c.carpeta_activa
           and upper(c.carpeta_imap) = 'INBOX'
           and lower(concat(c.usuario_id, '@', c.mail_domain)) = lower(btrim(v_email))
           and btrim(r.mensaje_remitente) <> ''
    ), recent as (
        select mensaje_remitente, count(*) total
          from messages
         where mensaje_fecha >= current_timestamp - interval '7 days'
         group by mensaje_remitente
         order by total desc, mensaje_remitente
         limit 10
    ), historic as (
        select mensaje_remitente, count(*) total
          from messages
         group by mensaje_remitente
         order by total desc, mensaje_remitente
         limit 10
    )
    select json_build_object(
        'codigo', '0',
        'recientes', coalesce((select json_agg(json_build_object(
            'sender', mensaje_remitente, 'count', total) order by total desc, mensaje_remitente) from recent), '[]'::json),
        'historicos', coalesce((select json_agg(json_build_object(
            'sender', mensaje_remitente, 'count', total) order by total desc, mensaje_remitente) from historic), '[]'::json)
    )::text;
$function$;

revoke all on function mail_fn_cache_remitentes(text) from public;
grant execute on function mail_fn_cache_remitentes(text) to w3apps;
