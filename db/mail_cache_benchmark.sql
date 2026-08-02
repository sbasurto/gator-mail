\set ON_ERROR_STOP on
\timing on
begin;

select usuario_id, mail_domain, concat(usuario_id, '@', mail_domain) test_email
  from app_usuario_mail order by usuario_id, mail_domain limit 1 \gset

insert into mail_carpetas (
    usuario_id, mail_domain, carpeta_imap, carpeta_nombre, carpeta_raiz,
    carpeta_nivel, carpeta_total, carpeta_activa, carpeta_actualizada)
values (:'usuario_id', :'mail_domain', 'INBOX', 'Entrada', 'INBOX', 0, 100000, true, current_timestamp)
on conflict (usuario_id, mail_domain, carpeta_imap) do update
set carpeta_total = excluded.carpeta_total, carpeta_activa = true, carpeta_actualizada = current_timestamp
returning carpeta_id \gset

delete from mail_mensajes_resumen where carpeta_id = :carpeta_id;
insert into mail_mensajes_resumen (
    usuario_id, mail_domain, carpeta_id, mensaje_uidvalidity, mensaje_uid,
    mensaje_remitente, mensaje_asunto, mensaje_fecha, mensaje_leido)
select :'usuario_id', :'mail_domain', :carpeta_id, 1, value,
       concat('Remitente ', value % 100, ' <sender', value % 100, '@example.com>'),
       concat('Mensaje ', value), current_timestamp - (value % 365) * interval '1 day', value % 2 = 0
  from generate_series(1, 100000) value;

explain (analyze, buffers) select mail_fn_cache_mensajes(json_build_object(
    'email', :'test_email', 'folder', 'INBOX', 'query', '', 'page', 1, 'size', 20)::text);
explain (analyze, buffers) select mail_fn_cache_remitentes(:'test_email');

select set_config('gator.test_email', :'test_email', true);
do $test$
declare ranking jsonb := mail_fn_cache_remitentes(current_setting('gator.test_email'))::jsonb;
begin
    assert jsonb_array_length(ranking -> 'recientes') = 10;
    assert jsonb_array_length(ranking -> 'historicos') = 10;
end;
$test$;

rollback;
