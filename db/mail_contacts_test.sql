begin;

insert into app_usuarios (usuario_id, usuario_password, usuario_nombre, usuario_estado)
values ('mail-requester-test', 'temporary-test-password', 'Solicitante', '1');

insert into app_usuario_email (usuario_email_email, usuario_email_estado, usuario_id)
values ('mail-requester-test@soft-gator.com', 1, 'mail-requester-test');

insert into app_contactos (contacto_id, contacto_nombre, cuenta_id, bodega_id, usuario_id)
values ('mail-own-test', 'Contacto Propio', 0, 0, 'mail-requester-test'),
       ('mail-group-test', 'Contacto de Grupo', 0, 0, null),
       ('mail-unrelated-test', 'Contacto Ajeno', 0, 0, null);

insert into app_contacto_email (contacto_email_email, contacto_id)
values ('propio@abasteo.mx', 'mail-own-test'),
       ('grupo@abasteo.mx', 'mail-group-test'),
       ('ajeno@abasteo.mx', 'mail-unrelated-test');

insert into broker_usuario_grupo (usuario_id, grupo_id)
values ('mail-requester-test', 'mail-group-test');

insert into app_grupo_contacto (grupo_id, contacto_id, cuenta_id, bodega_id)
values ('mail-group-test', 'mail-group-test', 0, 0);

do $$
declare resultado text;
begin
    resultado := mail_fn_get_contactos('mail-requester-test@soft-gator.com');
    assert position('propio@abasteo.mx' in resultado) > 0,
        'El directorio no incluyó el contacto del usuario';
    assert position('grupo@abasteo.mx' in resultado) > 0,
        'El directorio no incluyó el contacto del grupo';
    assert position('ajeno@abasteo.mx' in resultado) = 0,
        'El directorio incluyó un contacto ajeno';
end;
$$;

rollback;
