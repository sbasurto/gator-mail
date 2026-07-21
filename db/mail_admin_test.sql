begin;

insert into app_usuarios(usuario_id, usuario_password, usuario_nombre, usuario_estado)
values ('mail-admin-test', 'temporary-test-password', 'Administrador de prueba', '1'),
       ('mail-user-test', 'temporary-test-password', 'Usuario anterior', '1');
insert into app_usuario_email(usuario_email_email, usuario_email_estado, usuario_id, usuario_email_por_defecto)
values ('mail-admin-test@soft-gator.com', 1, 'mail-admin-test', 1);
insert into mail_administradores(usuario_id) values ('mail-admin-test');

do $$
declare resultado json;
declare contacto text;
begin
    resultado := mail_fn_admin_usuario_guardar('{"actor":"mail-admin-test@soft-gator.com",'
        '"user":"mail-user-test","name":"Usuario actualizado","enabled":false}')::json;
    assert resultado ->> 'codigo' = '0', 'No se actualizó el usuario';
    assert (select usuario_nombre = 'Usuario actualizado' and usuario_estado = '0'
              from app_usuarios where usuario_id = 'mail-user-test'), 'Cambio de usuario incorrecto';

    resultado := mail_fn_admin_contacto_guardar('{"actor":"mail-admin-test@soft-gator.com",'
        '"name":"Contacto de prueba","email":"contacto-admin-test@example.com",'
        '"owner":"mail-user-test","group":""}')::json;
    assert resultado ->> 'codigo' = '0', 'No se creó el contacto';
    contacto := resultado ->> 'id';
    assert position('contacto-admin-test@example.com' in
        mail_fn_admin_contactos('mail-admin-test@soft-gator.com')) > 0, 'No se listó el contacto';
    assert (mail_fn_admin_contacto_eliminar(json_build_object(
        'actor', 'mail-admin-test@soft-gator.com', 'id', contacto)::text)::json ->> 'codigo') = '0',
        'No se eliminó el contacto';
end;
$$;

rollback;
