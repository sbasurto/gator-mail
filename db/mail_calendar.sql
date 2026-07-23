-- Calendario autónomo de Gator Mail.
create extension if not exists "uuid-ossp";

create table if not exists app_eventos (
    rowid integer unique not null,
    evento_id text primary key default uuid_generate_v4(),
    evento_organizador text,
    evento_resumen text,
    evento_descripcion text,
    evento_lugar text,
    evento_fecha_ini timestamp without time zone,
    evento_fecha_fin timestamp without time zone,
    evento_hora_ini text,
    evento_hora_fin text,
    evento_estatus numeric default 0,
    evento_tags text,
    evento_ciclo integer default 0,
    evento_ciclo_irr text,
    evento_repite_ciclo integer,
    evento_call_priority integer,
    evento_call_tel_ori text,
    evento_call_tel_dst text,
    evento_link text,
    evento_timezone text,
    evento_pendiente integer default 1,
    evento_reagendado integer default 0,
    cuenta_id integer not null,
    bodega_id integer not null,
    usuario_id varchar(30) not null,
    evento_fecha_fin_real timestamp without time zone
);

create table if not exists app_grupo_evento (
    rowid integer unique not null,
    gpo_evento_id integer primary key,
    grupo_id text not null,
    evento_id text not null references app_eventos(evento_id) on delete cascade,
    cuenta_id integer not null,
    bodega_id integer not null,
    unique (evento_id, grupo_id, cuenta_id, bodega_id)
);

create table if not exists app_evento_participante (
    rowid integer unique not null,
    contacto_id text,
    evento_id text references app_eventos(evento_id) on delete cascade,
    evento_part_nombre text,
    evento_part_email text,
    cuenta_id integer not null,
    bodega_id integer not null,
    evento_part_id text primary key default uuid_generate_v4()
);

alter table app_eventos add column if not exists evento_uid_ical text;
alter table app_eventos add column if not exists evento_secuencia integer default 0;
alter table app_evento_participante add column if not exists evento_part_estado text default 'NEEDS-ACTION';

drop index if exists app_eventos_uid_ical_idx;
create unique index app_eventos_uid_ical_idx
    on app_eventos(evento_uid_ical, usuario_id) where evento_uid_ical is not null;

create index if not exists app_eventos_calendar_idx
    on app_eventos(cuenta_id, bodega_id, evento_fecha_ini, evento_fecha_fin);
create index if not exists app_grupo_evento_grupo_idx on app_grupo_evento(grupo_id, evento_id);
create index if not exists app_evento_participante_evento_idx
    on app_evento_participante(evento_id, contacto_id);

create sequence if not exists mail_event_rowid_seq increment by -1 minvalue -2147483648 start with -1;
create sequence if not exists mail_event_part_rowid_seq increment by -1 minvalue -2147483648 start with -1;

create or replace function mail_fn_evento_guardar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    correo text := lower(trim(v ->> 'organizer'));
    evento_id text := v ->> 'eventId';
    usuario text;
    fecha_ini timestamp;
    fecha_fin timestamp;
    invitados jsonb := coalesce(v -> 'guests', '[]'::jsonb);
    invitado jsonb;
begin
    if correo is null or length(correo) > 320 or position('@' in correo) < 2
            or evento_id is null or evento_id !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            or length(trim(coalesce(v ->> 'summary', ''))) not between 1 and 200 then
        return json_build_object('codigo', '-1', 'mensaje', 'Datos del evento inválidos')::text;
    end if;
    begin
        fecha_ini := (v ->> 'start')::timestamp;
        fecha_fin := (v ->> 'end')::timestamp;
    exception when others then
        return json_build_object('codigo', '-1', 'mensaje', 'Fechas inválidas')::text;
    end;
    if fecha_fin <= fecha_ini then
        return json_build_object('codigo', '-1', 'mensaje', 'La fecha final debe ser posterior a la inicial')::text;
    end if;
    select usuario_id into usuario from app_usuario_email
     where lower(usuario_email_email) = correo and coalesce(usuario_email_estado, 0) >= 0
     order by case when lower(usuario_id) = 'admin' then 1 else 0 end, usuario_id limit 1;
    if usuario is null then
        return json_build_object('codigo', '-1', 'mensaje', 'El organizador no está registrado')::text;
    end if;

    if jsonb_array_length(invitados) > 100 then
        return json_build_object('codigo', '-1', 'mensaje', 'Máximo 100 invitados')::text;
    end if;
    for invitado in select value from jsonb_array_elements(invitados) loop
        if (invitado ->> 'id') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                or length(trim(invitado ->> 'email')) > 320
                or position('@' in trim(invitado ->> 'email')) < 2 then
            return json_build_object('codigo', '-1', 'mensaje', 'Correo de invitado inválido')::text;
        end if;
    end loop;

    insert into app_eventos(rowid, evento_id, evento_organizador, evento_resumen, evento_descripcion,
        evento_lugar, evento_fecha_ini, evento_fecha_fin, evento_estatus, evento_tags, evento_link,
        evento_timezone, evento_pendiente, evento_reagendado, cuenta_id, bodega_id, usuario_id)
    values (nextval('mail_event_rowid_seq'), evento_id, correo, v ->> 'summary', v ->> 'description',
        v ->> 'place', fecha_ini, fecha_fin, 0, v ->> 'tags', v ->> 'link', v ->> 'timezone',
        1, 0, 1, 1, usuario);
    for invitado in select value from jsonb_array_elements(invitados) loop
        insert into app_evento_participante(rowid, evento_id, evento_part_nombre, evento_part_email,
            cuenta_id, bodega_id, evento_part_id)
        values (nextval('mail_event_part_rowid_seq'), evento_id, invitado ->> 'email', invitado ->> 'email',
            1, 1, invitado ->> 'id');
    end loop;
    return json_build_object('codigo', '0', 'eventoId', evento_id)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_invitacion_responder(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    evento text := trim(v ->> 'eventId');
    participante text := trim(v ->> 'participantId');
    correo text := lower(trim(v ->> 'attendee'));
    organizador text := lower(trim(v ->> 'organizer'));
    uid text := trim(v ->> 'uid');
    estado text := upper(trim(v ->> 'status'));
    secuencia integer;
    usuario text;
    fecha_ini timestamp;
    fecha_fin timestamp;
begin
    if evento !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            or participante !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            or length(uid) not between 1 and 512 or estado not in ('ACCEPTED','TENTATIVE','DECLINED')
            or correo !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$'
            or organizador !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$' then
        return json_build_object('codigo', '-1', 'mensaje', 'Respuesta de invitación inválida')::text;
    end if;
    begin
        fecha_ini := (v ->> 'start')::timestamp;
        fecha_fin := (v ->> 'end')::timestamp;
        secuencia := greatest(coalesce((v ->> 'sequence')::integer, 0), 0);
    exception when others then
        return json_build_object('codigo', '-1', 'mensaje', 'Fecha o secuencia inválida')::text;
    end;
    if fecha_fin < fecha_ini then
        return json_build_object('codigo', '-1', 'mensaje', 'Fechas inválidas')::text;
    end if;
    select usuario_id into usuario from app_usuario_email
     where lower(usuario_email_email) = correo and coalesce(usuario_email_estado, 0) >= 0
     order by case when lower(usuario_id) = lower(split_part(correo, '@', 1)) then 0 else 1 end,
              usuario_id limit 1;
    if usuario is null then
        return json_build_object('codigo', '-1', 'mensaje', 'El invitado no está registrado')::text;
    end if;
    if exists (select 1 from app_eventos
            where evento_id = evento and coalesce(evento_secuencia, 0) > secuencia) then
        return json_build_object('codigo', '-1', 'mensaje', 'La invitación está desactualizada')::text;
    end if;

    insert into app_eventos(rowid, evento_id, evento_uid_ical, evento_secuencia, evento_organizador,
        evento_resumen, evento_descripcion, evento_lugar, evento_fecha_ini, evento_fecha_fin,
        evento_estatus, evento_timezone, evento_pendiente, evento_reagendado,
        cuenta_id, bodega_id, usuario_id)
    values (nextval('mail_event_rowid_seq'), evento, uid, secuencia, organizador,
        left(v ->> 'summary', 200), left(v ->> 'description', 5000), left(v ->> 'place', 500),
        fecha_ini, fecha_fin, case estado when 'ACCEPTED' then 1 when 'TENTATIVE' then 2 else 3 end,
        left(v ->> 'timezone', 80), 0, 0, 1, 1, usuario)
    on conflict (evento_id) do update set
        evento_uid_ical = excluded.evento_uid_ical,
        evento_secuencia = excluded.evento_secuencia,
        evento_organizador = excluded.evento_organizador,
        evento_resumen = excluded.evento_resumen,
        evento_descripcion = excluded.evento_descripcion,
        evento_lugar = excluded.evento_lugar,
        evento_fecha_ini = excluded.evento_fecha_ini,
        evento_fecha_fin = excluded.evento_fecha_fin,
        evento_estatus = excluded.evento_estatus,
        evento_timezone = excluded.evento_timezone,
        usuario_id = excluded.usuario_id
      where excluded.evento_secuencia >= coalesce(app_eventos.evento_secuencia, 0);

    insert into app_evento_participante(rowid, evento_id, evento_part_nombre, evento_part_email,
        evento_part_estado, cuenta_id, bodega_id, evento_part_id)
    values (nextval('mail_event_part_rowid_seq'), evento, correo, correo, estado, 1, 1, participante)
    on conflict (evento_part_id) do update set evento_part_estado = excluded.evento_part_estado,
        evento_part_email = excluded.evento_part_email;
    return json_build_object('codigo', '0', 'eventoId', evento, 'estado', estado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_get_eventos(v_email text)
returns text language plpgsql stable security definer set search_path = public as $$
declare resultado json;
begin
    if v_email is null or length(trim(v_email)) > 320 or position('@' in v_email) < 2 then
        return json_build_object('codigo', '-1', 'mensaje', 'Usuario de correo inválido')::text;
    end if;

    with usuarios as (
        select distinct usuario_id from app_usuario_email
         where lower(usuario_email_email) = lower(trim(v_email))
           and coalesce(usuario_email_estado, 0) >= 0
    ), visibles as (
        select distinct e.*
          from app_eventos e
         where e.evento_fecha_ini >= date_trunc('month', current_date) - interval '3 months'
           and e.evento_fecha_ini < date_trunc('month', current_date) + interval '1 month'
           and (mail_fn_es_admin(v_email)
                or e.usuario_id in (select usuario_id from usuarios)
                or exists (select 1 from app_grupo_evento ge
                            join broker_usuario_grupo ug on ug.grupo_id = ge.grupo_id
                           where ge.evento_id = e.evento_id and ug.usuario_id in (select usuario_id from usuarios))
                or exists (select 1 from app_evento_participante ep
                            join app_contactos c on c.contacto_id = ep.contacto_id
                           where ep.evento_id = e.evento_id and c.usuario_id in (select usuario_id from usuarios)))
         order by e.evento_fecha_ini desc
         limit 100
    )
    select coalesce(json_agg(json_build_object(
               'id', evento_id,
               'summary', coalesce(nullif(evento_resumen, ''), '(Sin título)'),
               'description', coalesce(evento_descripcion, ''),
               'place', coalesce(evento_lugar, ''),
               'start', to_char(evento_fecha_ini, 'DD/MM/YYYY HH24:MI'),
               'end', to_char(coalesce(evento_fecha_fin, evento_fecha_ini), 'DD/MM/YYYY HH24:MI'),
               'status', case when evento_estatus = 1 then 'Aceptado'
                              when evento_estatus = 2 then 'Tentativa'
                              when evento_estatus = 3 then 'Rechazado'
                              when evento_estatus = 4 then 'Terminado'
                              when evento_fecha_ini < current_timestamp then 'Retrasado'
                              when evento_fecha_ini < current_timestamp + interval '2 hours' then 'Próximo'
                              else 'A tiempo' end,
               'statusClass', case when evento_estatus = 1 then 'is-on-time'
                                   when evento_estatus = 2 then 'is-next'
                                   when evento_estatus = 3 then 'is-delayed'
                                   when evento_estatus = 4 then 'is-done'
                                   when evento_fecha_ini < current_timestamp then 'is-delayed'
                                   when evento_fecha_ini < current_timestamp + interval '2 hours' then 'is-next'
                                   else 'is-on-time' end
           ) order by evento_fecha_ini desc), '[]'::json)
      into resultado from visibles;
    return json_build_object('codigo', '0', 'eventos', resultado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_get_calendario(v_json text)
returns text language plpgsql stable security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    correo text := trim(v ->> 'email');
    mes_texto text := trim(v ->> 'month');
    mes date;
    resultado json;
begin
    if correo is null or length(correo) > 320 or position('@' in correo) < 2
            or mes_texto !~ '^[0-9]{4}-[0-9]{2}$' then
        return json_build_object('codigo', '-1', 'mensaje', 'Calendario inválido')::text;
    end if;
    mes := to_date(mes_texto, 'YYYY-MM');
    if to_char(mes, 'YYYY-MM') <> mes_texto then
        return json_build_object('codigo', '-1', 'mensaje', 'Mes inválido')::text;
    end if;

    with usuarios as (
        select distinct usuario_id from app_usuario_email
         where lower(usuario_email_email) = lower(correo)
           and coalesce(usuario_email_estado, 0) >= 0
    ), visibles as (
        select distinct e.* from app_eventos e
         where e.evento_fecha_ini < mes + interval '1 month'
           and coalesce(e.evento_fecha_fin, e.evento_fecha_ini) >= mes
           and (mail_fn_es_admin(correo)
                or e.usuario_id in (select usuario_id from usuarios)
                or exists (select 1 from app_grupo_evento ge
                            join broker_usuario_grupo ug on ug.grupo_id = ge.grupo_id
                           where ge.evento_id = e.evento_id and ug.usuario_id in (select usuario_id from usuarios))
                or exists (select 1 from app_evento_participante ep
                            join app_contactos c on c.contacto_id = ep.contacto_id
                           where ep.evento_id = e.evento_id and c.usuario_id in (select usuario_id from usuarios)))
    )
    select coalesce(json_agg(json_build_object(
               'summary', coalesce(nullif(evento_resumen, ''), '(Sin título)'),
               'description', coalesce(evento_descripcion, ''),
               'date', to_char(evento_fecha_ini, 'YYYY-MM-DD'),
               'time', to_char(evento_fecha_ini, 'HH24:MI'),
               'statusClass', case when evento_estatus = 4 then 'is-done'
                                   when evento_fecha_ini < current_timestamp then 'is-delayed'
                                   when evento_fecha_ini < current_timestamp + interval '2 hours' then 'is-next'
                                   else 'is-on-time' end
           ) order by evento_fecha_ini), '[]'::json)
      into resultado from visibles;
    return json_build_object('codigo', '0', 'eventos', resultado)::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

revoke all on app_eventos, app_grupo_evento, app_evento_participante from public;
revoke all on function mail_fn_get_eventos(text), mail_fn_get_calendario(text),
    mail_fn_evento_guardar(text), mail_fn_invitacion_responder(text) from public;
grant execute on function mail_fn_get_eventos(text), mail_fn_get_calendario(text),
    mail_fn_evento_guardar(text), mail_fn_invitacion_responder(text) to w3apps;
