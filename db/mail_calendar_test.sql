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
    resultado := mail_fn_evento_guardar(json_build_object(
        'eventId', '7dc5dfc8-756b-4d35-b6db-dba287f46d71',
        'organizer', 'mail-calendar-test@soft-gator.com',
        'summary', 'Evento creado', 'description', 'Descripción', 'place', 'Sala 1',
        'start', to_char(current_timestamp + interval '1 day', 'YYYY-MM-DD HH24:MI:SS'),
        'end', to_char(current_timestamp + interval '1 day 1 hour', 'YYYY-MM-DD HH24:MI:SS'),
        'timezone', 'America/Mexico_City', 'tags', 'prueba', 'link', 'https://example.com',
        'guests', json_build_array(json_build_object(
            'id', 'bcfbd02f-751f-4647-b0de-8fb5b764cc46', 'email', 'invitado@example.com'))
    )::text);
    assert resultado::jsonb ->> 'codigo' = '0', 'No se creó el evento';
    assert exists (select 1 from app_eventos where evento_id = '7dc5dfc8-756b-4d35-b6db-dba287f46d71'),
        'No se persistió el evento';
    assert exists (select 1 from app_evento_participante
        where evento_part_id = 'bcfbd02f-751f-4647-b0de-8fb5b764cc46'), 'No se persistió el invitado';
    resultado := mail_fn_get_eventos('mail-calendar-test@soft-gator.com');
    assert position('Evento visible' in resultado) > 0, 'No se listó el evento del usuario';
    assert position('Evento ajeno' in resultado) = 0, 'Se listó un evento ajeno';
    resultado := mail_fn_get_calendario(json_build_object(
        'email', 'mail-calendar-test@soft-gator.com', 'month', to_char(current_date, 'YYYY-MM'))::text);
    assert position('Evento visible' in resultado) > 0, 'El calendario no incluyó el evento del usuario';
end;
$$;

do $$
declare
    attendee text;
    event_id text := uuid_generate_v4()::text;
    participant_id text := uuid_generate_v4()::text;
    result jsonb;
begin
    select usuario_email_email into attendee
      from app_usuario_email
     where coalesce(usuario_email_estado, 0) >= 0
     limit 1;
    assert attendee is not null, 'No hay un usuario para la prueba';
    result := mail_fn_invitacion_responder(json_build_object(
            'eventId', event_id, 'participantId', participant_id, 'uid', 'calendar-test@example.com',
            'sequence', 1, 'organizer', 'organizer@example.com', 'attendee', attendee,
            'summary', 'Invitación de prueba', 'description', '', 'place', '',
            'start', '2026-07-23 12:00:00', 'end', '2026-07-23 13:00:00',
            'timezone', 'America/Mexico_City', 'status', 'ACCEPTED')::text)::jsonb;
    assert result ->> 'codigo' = '0', result ->> 'mensaje';
    assert exists (select 1 from app_eventos
            where evento_id = event_id and evento_uid_ical = 'calendar-test@example.com'
              and evento_estatus = 1), 'No se guardó la invitación';
    assert exists (select 1 from app_evento_participante
            where evento_part_id = participant_id and evento_part_estado = 'ACCEPTED'),
            'No se guardó la respuesta';
    result := mail_fn_invitacion_responder(json_build_object(
            'eventId', event_id, 'participantId', participant_id, 'uid', 'calendar-test@example.com',
            'sequence', 0, 'organizer', 'organizer@example.com', 'attendee', attendee,
            'summary', 'Invitación anterior', 'description', '', 'place', '',
            'start', '2026-07-23 12:00:00', 'end', '2026-07-23 13:00:00',
            'timezone', 'America/Mexico_City', 'status', 'DECLINED')::text)::jsonb;
    assert result ->> 'codigo' = '-1', 'Se aceptó una invitación desactualizada';
end;
$$;

rollback;
