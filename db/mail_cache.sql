alter table mail_carpetas
    add column if not exists carpeta_raiz text not null default '',
    add column if not exists carpeta_nivel integer not null default 0,
    add column if not exists carpeta_no_leidos integer not null default 0,
    add column if not exists carpeta_total integer not null default 0,
    add column if not exists carpeta_uidvalidity bigint not null default 0,
    add column if not exists carpeta_uidnext bigint not null default 0,
    add column if not exists carpeta_modseq bigint not null default 0,
    add column if not exists carpeta_sincronizada timestamp with time zone;

create table if not exists mail_mensajes_resumen (
    mensaje_resumen_id bigint generated always as identity primary key,
    usuario_id text not null,
    mail_domain text not null,
    carpeta_id bigint not null,
    mensaje_uidvalidity bigint not null,
    mensaje_uid bigint not null check (mensaje_uid > 0),
    mensaje_remitente text not null default '',
    mensaje_asunto text not null default '(Sin asunto)',
    mensaje_fecha timestamp with time zone not null,
    mensaje_leido boolean not null default false,
    mensaje_actualizado timestamp with time zone not null default current_timestamp,
    foreign key (carpeta_id, usuario_id, mail_domain)
        references mail_carpetas (carpeta_id, usuario_id, mail_domain) on delete cascade,
    unique (carpeta_id, mensaje_uidvalidity, mensaje_uid)
);

create index if not exists mail_mensajes_resumen_lista_idx
    on mail_mensajes_resumen (carpeta_id, mensaje_fecha desc, mensaje_uid desc);

create or replace function mail_fn_cache_carpetas_guardar(v_json text)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
    payload jsonb := v_json::jsonb;
    v_domain text;
    v_email text := lower(btrim(payload ->> 'email'));
    v_user text;
begin
    select m.usuario_id, m.mail_domain into v_user, v_domain
      from public.app_usuario_mail m
     where lower(concat(m.usuario_id, '@', m.mail_domain)) = v_email
     limit 1;
    if v_user is null or jsonb_typeof(payload -> 'folders') <> 'array'
            or jsonb_array_length(payload -> 'folders') > 1000 then
        return json_build_object('codigo', '-1', 'mensaje', 'Sincronización de carpetas inválida')::text;
    end if;

    update public.mail_carpetas
       set carpeta_padre_id = null
     where usuario_id = v_user and mail_domain = v_domain;

    insert into public.mail_carpetas (
        usuario_id, mail_domain, carpeta_imap, carpeta_nombre, carpeta_raiz, carpeta_nivel,
        carpeta_no_leidos, carpeta_total, carpeta_orden, carpeta_activa, carpeta_actualizada
    )
    select v_user, v_domain, f.name, f.label, f.root, greatest(0, f.depth),
           greatest(0, f.unread), greatest(0, f.total), greatest(0, f.sort_order), true, current_timestamp
      from jsonb_to_recordset(payload -> 'folders') as f(
          name text, label text, parent text, root text, depth integer, unread integer, total integer,
          sort_order integer)
     where btrim(coalesce(f.name, '')) <> '' and length(f.name) <= 1000
    on conflict (usuario_id, mail_domain, carpeta_imap) do update set
        carpeta_nombre = excluded.carpeta_nombre,
        carpeta_raiz = excluded.carpeta_raiz,
        carpeta_nivel = excluded.carpeta_nivel,
        carpeta_no_leidos = excluded.carpeta_no_leidos,
        carpeta_total = excluded.carpeta_total,
        carpeta_orden = excluded.carpeta_orden,
        carpeta_activa = true,
        carpeta_actualizada = current_timestamp;

    update public.mail_carpetas c
       set carpeta_activa = false, carpeta_actualizada = current_timestamp
     where c.usuario_id = v_user and c.mail_domain = v_domain
       and not exists (
           select 1 from jsonb_to_recordset(payload -> 'folders') as f(name text)
            where f.name = c.carpeta_imap);

    update public.mail_carpetas c
       set carpeta_padre_id = p.carpeta_id
      from jsonb_to_recordset(payload -> 'folders') as f(name text, parent text)
      join public.mail_carpetas p
        on p.usuario_id = v_user and p.mail_domain = v_domain and p.carpeta_imap = f.parent
     where c.usuario_id = v_user and c.mail_domain = v_domain and c.carpeta_imap = f.name
       and btrim(coalesce(f.parent, '')) <> '';

    return json_build_object('codigo', '0')::text;
end;
$function$;

create or replace function mail_fn_cache_carpetas(v_email text)
returns text
language sql
stable
security definer
set search_path = pg_catalog, public
as $function$
    select json_build_object('codigo', '0', 'carpetas', coalesce(json_agg(json_build_object(
        'name', c.carpeta_imap, 'label', c.carpeta_nombre,
        'parent', coalesce(p.carpeta_imap, ''), 'root', c.carpeta_raiz,
        'depth', c.carpeta_nivel, 'unread', c.carpeta_no_leidos, 'total', c.carpeta_total,
        'uidValidity', c.carpeta_uidvalidity, 'uidNext', c.carpeta_uidnext,
        'modSeq', c.carpeta_modseq, 'cached', (
            select count(*) from public.mail_mensajes_resumen r where r.carpeta_id = c.carpeta_id)
    ) order by c.carpeta_orden, c.carpeta_nombre), '[]'::json))::text
      from public.mail_carpetas c
      left join public.mail_carpetas p on p.carpeta_id = c.carpeta_padre_id
     where c.carpeta_activa
       and lower(concat(c.usuario_id, '@', c.mail_domain)) = lower(btrim(v_email));
$function$;

create or replace function mail_fn_cache_mensajes_guardar(v_json text)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $function$
declare
    payload jsonb := v_json::jsonb;
    v_folder_id bigint;
    v_reset boolean := coalesce((payload ->> 'reset')::boolean, false);
    v_uidvalidity bigint := coalesce((payload ->> 'uidValidity')::bigint, 0);
begin
    select c.carpeta_id into v_folder_id
      from public.mail_carpetas c
     where c.carpeta_activa
       and lower(concat(c.usuario_id, '@', c.mail_domain)) = lower(btrim(payload ->> 'email'))
       and c.carpeta_imap = payload ->> 'folder';
    if v_folder_id is null or v_uidvalidity <= 0
            or jsonb_typeof(payload -> 'messages') <> 'array'
            or jsonb_array_length(payload -> 'messages') > 100000 then
        return json_build_object('codigo', '-1', 'mensaje', 'Sincronización de mensajes inválida')::text;
    end if;

    if v_reset then
        delete from public.mail_mensajes_resumen where carpeta_id = v_folder_id;
    end if;

    insert into public.mail_mensajes_resumen (
        usuario_id, mail_domain, carpeta_id, mensaje_uidvalidity, mensaje_uid,
        mensaje_remitente, mensaje_asunto, mensaje_fecha, mensaje_leido
    )
    select c.usuario_id, c.mail_domain, c.carpeta_id, v_uidvalidity, m.uid,
           left(coalesce(m.sender, ''), 1000), left(coalesce(m.subject, '(Sin asunto)'), 2000),
           to_timestamp(m.sent_epoch), coalesce(m.seen, false)
      from public.mail_carpetas c
      cross join jsonb_to_recordset(payload -> 'messages') as m(
          uid bigint, sender text, subject text, sent_epoch double precision, seen boolean)
     where c.carpeta_id = v_folder_id and m.uid > 0
    on conflict (carpeta_id, mensaje_uidvalidity, mensaje_uid) do update set
        mensaje_remitente = excluded.mensaje_remitente,
        mensaje_asunto = excluded.mensaje_asunto,
        mensaje_fecha = excluded.mensaje_fecha,
        mensaje_leido = excluded.mensaje_leido,
        mensaje_actualizado = current_timestamp;

    update public.mail_carpetas
       set carpeta_uidvalidity = v_uidvalidity,
           carpeta_uidnext = greatest(0, coalesce((payload ->> 'uidNext')::bigint, 0)),
           carpeta_modseq = greatest(0, coalesce((payload ->> 'modSeq')::bigint, 0)),
           carpeta_total = greatest(0, coalesce((payload ->> 'total')::integer, 0)),
           carpeta_no_leidos = greatest(0, coalesce((payload ->> 'unread')::integer, 0)),
           carpeta_sincronizada = current_timestamp,
           carpeta_actualizada = current_timestamp
     where carpeta_id = v_folder_id;
    return json_build_object('codigo', '0')::text;
end;
$function$;

create or replace function mail_fn_cache_mensajes(v_json text)
returns text
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $function$
declare
    payload jsonb := v_json::jsonb;
    v_folder_id bigint;
    v_page integer := greatest(1, least(100000, coalesce((payload ->> 'page')::integer, 1)));
    v_query text := left(btrim(coalesce(payload ->> 'query', '')), 100);
    v_size integer := greatest(20, least(100, coalesce((payload ->> 'size')::integer, 20)));
    v_total integer;
    v_messages json;
begin
    select c.carpeta_id into v_folder_id
      from public.mail_carpetas c
     where c.carpeta_activa
       and lower(concat(c.usuario_id, '@', c.mail_domain)) = lower(btrim(payload ->> 'email'))
       and c.carpeta_imap = payload ->> 'folder';
    if v_folder_id is null then
        return json_build_object('codigo', '-1', 'mensaje', 'Carpeta inexistente')::text;
    end if;

    select count(*) into v_total from public.mail_mensajes_resumen r
     where r.carpeta_id = v_folder_id
       and (v_query = '' or r.mensaje_remitente ilike concat('%', v_query, '%')
            or r.mensaje_asunto ilike concat('%', v_query, '%'));
    v_page := least(v_page, greatest(1, (v_total + v_size - 1) / v_size));
    select coalesce(json_agg(json_build_object(
        'uid', q.mensaje_uid, 'from', q.mensaje_remitente, 'subject', q.mensaje_asunto,
        'sentEpoch', extract(epoch from q.mensaje_fecha), 'seen', q.mensaje_leido)
        order by q.mensaje_fecha desc, q.mensaje_uid desc), '[]'::json)
      into v_messages
      from (select r.* from public.mail_mensajes_resumen r
             where r.carpeta_id = v_folder_id
               and (v_query = '' or r.mensaje_remitente ilike concat('%', v_query, '%')
                    or r.mensaje_asunto ilike concat('%', v_query, '%'))
             order by r.mensaje_fecha desc, r.mensaje_uid desc
             limit v_size offset (v_page - 1) * v_size) q;
    return json_build_object('codigo', '0', 'total', v_total, 'page', v_page,
            'size', v_size, 'mensajes', v_messages)::text;
end;
$function$;

create or replace function mail_fn_cache_marcar_leido(v_json text)
returns text
language sql
security definer
set search_path = pg_catalog, public
as $function$
    with changed as (
        update public.mail_mensajes_resumen r set mensaje_leido = true, mensaje_actualizado = current_timestamp
          from public.mail_carpetas c
         where r.carpeta_id = c.carpeta_id
           and lower(concat(c.usuario_id, '@', c.mail_domain)) = lower(btrim(v_json::jsonb ->> 'email'))
           and c.carpeta_imap = v_json::jsonb ->> 'folder'
           and r.mensaje_uid = (v_json::jsonb ->> 'uid')::bigint
           and not r.mensaje_leido
        returning r.carpeta_id), folder_updated as (
        update public.mail_carpetas c
           set carpeta_no_leidos = greatest(0, c.carpeta_no_leidos - 1),
               carpeta_actualizada = current_timestamp
         where c.carpeta_id in (select carpeta_id from changed)
        returning 1)
    select json_build_object('codigo', '0', 'actualizados', count(*))::text from folder_updated;
$function$;

revoke all on mail_mensajes_resumen from public;
revoke all on function mail_fn_cache_carpetas_guardar(text) from public;
revoke all on function mail_fn_cache_carpetas(text) from public;
revoke all on function mail_fn_cache_mensajes_guardar(text) from public;
revoke all on function mail_fn_cache_mensajes(text) from public;
revoke all on function mail_fn_cache_marcar_leido(text) from public;
grant execute on function mail_fn_cache_carpetas_guardar(text) to w3apps;
grant execute on function mail_fn_cache_carpetas(text) to w3apps;
grant execute on function mail_fn_cache_mensajes_guardar(text) to w3apps;
grant execute on function mail_fn_cache_mensajes(text) to w3apps;
grant execute on function mail_fn_cache_marcar_leido(text) to w3apps;
