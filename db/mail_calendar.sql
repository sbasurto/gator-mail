-- Réplica mínima y de sólo lectura del calendario de G-ERM.
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

create index if not exists app_eventos_calendar_idx
    on app_eventos(cuenta_id, bodega_id, evento_fecha_ini, evento_fecha_fin);
create index if not exists app_grupo_evento_grupo_idx on app_grupo_evento(grupo_id, evento_id);
create index if not exists app_evento_participante_evento_idx
    on app_evento_participante(evento_id, contacto_id);

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
               'status', case when evento_estatus = 4 then 'Terminado'
                              when evento_fecha_ini < current_timestamp then 'Retrasado'
                              when evento_fecha_ini < current_timestamp + interval '2 hours' then 'Próximo'
                              else 'A tiempo' end,
               'statusClass', case when evento_estatus = 4 then 'is-done'
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

revoke all on app_eventos, app_grupo_evento, app_evento_participante from public;
revoke all on function mail_fn_get_eventos(text) from public;
