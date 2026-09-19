-- Run with psql -v ON_ERROR_STOP=1 after mail_login_contact.sql; all data rolls back.
begin;
do $$
declare
    username text := 'contact-test-' || uuid_generate_v4()::text;
    payload text;
    original_password text;
    result jsonb;
begin
    insert into app_usuarios(usuario_id, usuario_password, usuario_estado)
    values (username, 'unchanged-test-password', '1');
    select usuario_password into original_password from app_usuarios where usuario_id = username;
    payload := json_build_object('usuario', username, 'email', username || '@example.invalid',
                                 'telefono', '+12025550123')::text;
    result := mail_fn_sync_gatormail_user(payload)::jsonb;
    assert result->>'codigo' = '0', result::text;
    result := mail_fn_sync_gatormail_user(payload)::jsonb;
    assert result->>'codigo' = '0', result::text;
    assert (select count(*) = 1 from app_usuario_email where usuario_id = username);
    assert (select telefono = '+12025550123' from mail_usuario_telefonos where usuario_id = username);
    assert (select usuario_password = original_password from app_usuarios where usuario_id = username);
    update mail_usuario_telefonos set global_safe_list = true where usuario_id = username;
    perform mail_fn_sync_gatormail_user(payload);
    assert (select global_safe_list from mail_usuario_telefonos where usuario_id = username);
    perform mail_fn_sync_gatormail_user(jsonb_set(payload::jsonb,'{telefono}','"+12025550124"')::text);
    assert not (select global_safe_list from mail_usuario_telefonos where usuario_id = username);
    result := mail_fn_sync_gatormail_user(jsonb_set(payload::jsonb,'{telefono}','"invalid"')::text)::jsonb;
    assert result->>'codigo' = '-1';
    result := mail_fn_sync_gatormail_user(jsonb_set(payload::jsonb,'{email}','"invalid"')::text)::jsonb;
    assert result->>'codigo' = '-1';
    insert into app_usuarios(usuario_id, usuario_password, usuario_estado) values (username || '-other', 'test-password', '1');
    result := mail_fn_sync_gatormail_user(jsonb_set(payload::jsonb,'{usuario}',to_jsonb(username || '-other'))::text)::jsonb;
    assert result->>'codigo' = '-1';
    assert not exists(select 1 from mail_usuario_telefonos where usuario_id = username || '-other');
    result := mail_fn_sync_gatormail_user(jsonb_set(payload::jsonb,'{usuario}',to_jsonb(username || '-missing'))::text)::jsonb;
    assert result->>'codigo' = '-1';
    update app_usuarios set usuario_estado = '0' where usuario_id = username;
    result := mail_fn_sync_gatormail_user(payload)::jsonb;
    assert result->>'codigo' = '-1';
end $$;
rollback;
