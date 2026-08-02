\set ON_ERROR_STOP on
begin;

select concat(usuario_id, '@', mail_domain) as test_email
  from app_usuario_mail order by usuario_id, mail_domain limit 1 \gset

select mail_fn_cache_carpetas_guardar(json_build_object(
    'email', :'test_email',
    'folders', json_build_array(
        json_build_object('name', 'INBOX', 'label', 'Entrada', 'parent', '', 'root', 'INBOX',
            'depth', 0, 'unread', 1, 'total', 1, 'sort_order', 0),
        json_build_object('name', 'Clientes', 'label', 'Clientes', 'parent', '', 'root', 'Clientes',
            'depth', 0, 'unread', 0, 'total', 0, 'sort_order', 1),
        json_build_object('name', 'Clientes.VIP', 'label', 'VIP', 'parent', 'Clientes', 'root', 'Clientes',
            'depth', 1, 'unread', 0, 'total', 0, 'sort_order', 2)))::text);

select mail_fn_cache_mensajes_guardar(json_build_object(
    'email', :'test_email', 'folder', 'INBOX', 'uidValidity', 42, 'uidNext', 2,
    'modSeq', 7, 'total', 1, 'unread', 1, 'reset', true,
    'messages', json_build_array(json_build_object('uid', 1, 'sender', 'prueba@example.com',
        'subject', 'Mensaje de prueba', 'sent_epoch', 1785686400, 'seen', false)))::text);

do $test$
declare
    email text := (select concat(usuario_id, '@', mail_domain)
                     from app_usuario_mail order by usuario_id, mail_domain limit 1);
    folders jsonb := mail_fn_cache_carpetas(email)::jsonb;
    messages jsonb := mail_fn_cache_mensajes(json_build_object(
        'email', email, 'folder', 'INBOX', 'query', 'prueba', 'page', 1, 'size', 20)::text)::jsonb;
begin
    assert jsonb_array_length(folders -> 'carpetas') = 3;
    assert (select value ->> 'parent' = 'Clientes' from jsonb_array_elements(folders -> 'carpetas') value
             where value ->> 'name' = 'Clientes.VIP');
    assert messages ->> 'total' = '1';
    assert messages #>> '{mensajes,0,subject}' = 'Mensaje de prueba';
end;
$test$;

select mail_fn_cache_marcar_leido(json_build_object(
    'email', :'test_email', 'folder', 'INBOX', 'uid', 1)::text);

do $test$
begin
    assert exists (select 1 from mail_mensajes_resumen where mensaje_uid = 1 and mensaje_leido);
end;
$test$;

rollback;
