begin;

insert into app_usuarios(usuario_id, usuario_password, usuario_nombre, usuario_estado)
values ('mail-calendar-test', 'temporary-test-password', 'Calendario', '1');
insert into app_usuario_email(usuario_email_email, usuario_email_estado, usuario_id)
values ('mail-calendar-test@soft-gator.com', 1, 'mail-calendar-test');
insert into app_eventos(rowid, evento_id, evento_resumen, evento_fecha_ini, evento_fecha_fin,
                        cuenta_id, bodega_id, usuario_id)
values (-10001, 'mail-calendar-visible', 'Evento visible', current_timestamp,
        current_timestamp + interval '1 hour', 0, 0, 'mail-calendar-test'),
       (-10002, 'mail-calendar-hidden', 'Evento ajeno', current_timestamp,
        current_timestamp + interval '1 hour', 0, 0, 'admin');

do $$
declare resultado text := mail_fn_get_eventos('mail-calendar-test@soft-gator.com');
begin
    assert position('Evento visible' in resultado) > 0, 'No se listó el evento del usuario';
    assert position('Evento ajeno' in resultado) = 0, 'Se listó un evento ajeno';
end;
$$;

rollback;
