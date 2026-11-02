\set ON_ERROR_STOP on
begin;

do $test$
declare
    email text := (select concat(usuario_id, '@', mail_domain) from app_usuario_mail
                    order by usuario_id, mail_domain limit 1);
    result jsonb;
    folders jsonb;
    query text;
    messages jsonb := '[]';
begin
    perform mail_fn_cache_carpetas_guardar(jsonb_build_object('email', email, 'folders',
        jsonb_build_array(jsonb_build_object('name', 'Sent', 'label', 'Enviados',
            'parent', '', 'root', 'Sent', 'depth', 0, 'unread', 0, 'total', 23, 'sort_order', 1)))::text);
    for i in 1..23 loop
        messages := messages || jsonb_build_object('uid', i, 'sender', email,
            'recipient_to', 'Ana Destino <ana@cliente.test>',
            'recipient_cc', 'Equipo Copias <equipo@copias.test>',
            'recipient_bcc', 'Archivo Privado <archivo@privado.test>',
            'subject', 'Propuesta anual', 'sent_epoch', 1785686400 + i, 'seen', true);
    end loop;
    perform mail_fn_cache_mensajes_guardar(jsonb_build_object('email', email, 'folder', 'Sent',
        'uidValidity', 42, 'uidNext', 24, 'modSeq', 7, 'total', 23, 'unread', 0,
        'reset', true, 'messages', messages)::text);

    -- A matching message in another mailbox must not affect totals or pagination.
    result := (select jsonb_build_object('email', concat(usuario_id, '@', mail_domain))
                 from app_usuario_mail where concat(usuario_id, '@', mail_domain) <> email limit 1);
    assert result is not null, 'Run this test in a disposable database with two synthetic mailboxes';
    perform mail_fn_cache_carpetas_guardar((result || jsonb_build_object('folders',
        jsonb_build_array(jsonb_build_object('name', 'Sent', 'label', 'Enviados',
            'parent', '', 'root', 'Sent', 'depth', 0, 'unread', 0, 'total', 1, 'sort_order', 1))))::text);
    perform mail_fn_cache_mensajes_guardar((result || jsonb_build_object('folder', 'Sent',
        'uidValidity', 42, 'uidNext', 2, 'modSeq', 7, 'total', 1, 'unread', 0,
        'reset', true, 'messages', jsonb_build_array(messages -> 0)))::text);

    foreach query in array array['ANA@CLIENTE.TEST', 'Destino', 'equipo@copias.test',
            'Archivo Privado', 'propuesta', email] loop
        result := mail_fn_cache_mensajes(jsonb_build_object('email', email, 'folder', 'Sent',
            'query', query, 'page', 2, 'size', 20)::text)::jsonb;
        assert result ->> 'total' = '23', 'Missing search match: ' || query;
        assert result ->> 'page' = '2' and jsonb_array_length(result -> 'mensajes') = 3;
        assert result #>> '{mensajes,0,to}' = 'Ana Destino <ana@cliente.test>';
        assert result #>> '{mensajes,0,cc}' = 'Equipo Copias <equipo@copias.test>';
        assert result #>> '{mensajes,0,bcc}' = 'Archivo Privado <archivo@privado.test>';
        assert result ->> 'recipientsCached' = 'true';
    end loop;

    result := mail_fn_cache_mensajes(jsonb_build_object('email', email, 'folder', 'Sent',
        'query', 'inexistente', 'page', 1, 'size', 20)::text)::jsonb;
    assert result ->> 'total' = '0';
    result := mail_fn_cache_mensajes(jsonb_build_object('email', 'unknown@example.test',
        'folder', 'Sent', 'query', 'ana', 'page', 1, 'size', 20)::text)::jsonb;
    assert result ->> 'codigo' = '-1', 'Search crossed mailbox boundary';

    -- Missing legacy headers must trigger hydration even when the search matches nothing.
    update mail_mensajes_resumen set mensaje_para = null
     where concat(usuario_id, '@', mail_domain) = email and mensaje_uid = 1;
    result := mail_fn_cache_mensajes(jsonb_build_object('email', email, 'folder', 'Sent',
        'query', 'inexistente', 'page', 1, 'size', 20)::text)::jsonb;
    assert result ->> 'recipientsCached' = 'false';
    folders := mail_fn_cache_carpetas(email)::jsonb;
    assert folders #>> '{carpetas,0,recipientsCached}' = 'false';
    perform mail_fn_cache_mensajes_guardar(jsonb_build_object('email', email, 'folder', 'Sent',
        'uidValidity', 42, 'uidNext', 24, 'modSeq', 7, 'total', 23, 'unread', 0,
        'reset', true, 'messages', messages)::text);
    folders := mail_fn_cache_carpetas(email)::jsonb;
    assert folders #>> '{carpetas,0,recipientsCached}' = 'true';
end;
$test$;

rollback;
