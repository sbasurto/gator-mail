-- Se instala en G-ERM. Captura cambios hechos por app_fn_admon_tablas_all y por
-- el flujo heredado de eventos, sin bloquear G-ERM si Gator Mail no responde.
create extension if not exists dblink;

create table if not exists mail_event_sync_queue (
    sync_id bigserial primary key,
    sync_fecha timestamp without time zone default now() not null,
    sync_tabla text not null,
    sync_operacion text not null,
    sync_payload jsonb,
    sync_error text not null
);

create or replace function mail_fn_sync_event_change()
returns trigger language plpgsql security definer set search_path = public as $$
declare
    conexion text;
    columnas text;
    actualizaciones text;
    llave text;
    payload jsonb := case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end;
    sentencia text;
begin
    if tg_table_name not in ('app_eventos', 'app_grupo_evento', 'app_evento_participante') then
        raise exception 'Tabla de sincronización no permitida: %', tg_table_name;
    end if;
    llave := case tg_table_name when 'app_eventos' then 'evento_id'
        when 'app_grupo_evento' then 'gpo_evento_id' else 'evento_part_id' end;
    select format('host=%L port=%L dbname=%L user=%L password=%L',
                  db_hostaddr, db_port, db_sid, db_user, db_passwd)
      into conexion from broker_db where db_use = 'gatormail' limit 1;
    if conexion is null then raise exception 'No existe broker_db para gatormail'; end if;

    if tg_op = 'DELETE' then
        sentencia := format('delete from public.%I where %I = %L', tg_table_name, llave, payload ->> llave);
    else
        select string_agg(format('%I', a.attname), ', ' order by a.attnum),
               string_agg(format('%1$I = excluded.%1$I', a.attname), ', ' order by a.attnum)
                   filter (where a.attname <> llave)
          into columnas, actualizaciones
          from pg_attribute a join pg_class c on c.oid = a.attrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relname = tg_table_name
           and a.attnum > 0 and not a.attisdropped and a.attgenerated = '';
        sentencia := format('insert into public.%1$I (%2$s) select %2$s '
            'from json_populate_record(null::public.%1$I, %3$L::json) '
            'on conflict (%4$I) do update set %5$s',
            tg_table_name, columnas, payload::text, llave, actualizaciones);
    end if;
    perform dblink_connect(conexion);
    perform dblink_exec(sentencia);
    perform dblink_disconnect();
    return case when tg_op = 'DELETE' then old else new end;
exception when others then
    begin perform dblink_disconnect(); exception when others then null; end;
    insert into mail_event_sync_queue(sync_tabla, sync_operacion, sync_payload, sync_error)
    values (tg_table_name, tg_op, payload, sqlerrm);
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

do $$
declare tabla text;
begin
    foreach tabla in array array['app_eventos', 'app_grupo_evento', 'app_evento_participante'] loop
        execute format('drop trigger if exists mail_sync_events on public.%I', tabla);
        execute format('create trigger mail_sync_events after insert or update or delete on public.%I '
                       'for each row execute function mail_fn_sync_event_change()', tabla);
    end loop;
end;
$$;

-- La instalación inicial se ejecuta con los triggers activos para usar el mismo
-- camino que app_fn_admon_tablas_all y dejar cualquier fallo en la cola.
update app_eventos set evento_id = evento_id;
update app_grupo_evento set gpo_evento_id = gpo_evento_id;
update app_evento_participante set evento_part_id = evento_part_id;
