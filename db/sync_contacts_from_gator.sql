-- Integración opcional para instalaciones Gator: replica cambios del directorio
-- hacia db_gatormail sin crear una dependencia de lectura en Gator Mail.
create extension if not exists dblink;

insert into broker_db (
    db_id, db_user, db_passwd, db_type, db_port, db_sid, db_name, db_kind, db_use,
    db_grupo, db_icon, db_path, db_cfile, db_conf_file, db_conf_file2,
    db_context_path, db_hostaddr, db_comment
)
select 3459, db_user, db_passwd, db_type, '5432', 'db_gatormail', 'Gator Mail Data',
       'pgsql', 'gatormail', db_grupo, 'fas fa-address-book', 'mail', 'initGatorMail',
       'pg_mail_master', 'indexMasterErm', 'gator-mail', db_hostaddr,
       'Base autónoma de Gator Mail'
  from broker_db
 where db_use = 'erm'
   and not exists (select 1 from broker_db where db_use = 'gatormail');

create table if not exists mail_contact_sync_queue (
    sync_id bigserial primary key,
    sync_fecha timestamp without time zone default now() not null,
    sync_tabla text not null,
    sync_operacion text not null,
    sync_payload jsonb,
    sync_error text not null
);

create or replace function mail_fn_sync_contact_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    conexion text;
    columnas text;
    actualizaciones text;
    llave text;
    payload jsonb;
    sentencia text;
begin
    if tg_table_name not in ('app_contactos', 'app_contacto_email', 'app_email',
            'app_grupo_contacto', 'app_grupo_email', 'app_usuario_email', 'broker_usuario_grupo') then
        raise exception 'Tabla de sincronización no permitida: %', tg_table_name;
    end if;

    llave := case tg_table_name
        when 'app_contactos' then 'contacto_id'
        when 'app_contacto_email' then 'contacto_email_id'
        when 'app_email' then 'email_id'
        when 'app_grupo_contacto' then 'gpo_contacto_id'
        when 'app_grupo_email' then 'gpo_email_id'
        when 'app_usuario_email' then 'usuario_email_id'
        when 'broker_usuario_grupo' then 'usr_grupo_id'
    end;
    payload := case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end;

    select format('host=%L port=%L dbname=%L user=%L password=%L',
                  db_hostaddr, db_port, db_sid, db_user, db_passwd)
      into conexion
      from broker_db
     where db_use = 'gatormail'
     limit 1;
    if conexion is null then raise exception 'No existe broker_db para gatormail'; end if;

    if tg_op = 'DELETE' then
        sentencia := format('delete from public.%I where %I = %L',
                tg_table_name, llave, payload ->> llave);
    else
        select string_agg(format('%I', a.attname), ', ' order by a.attnum),
               string_agg(format('%1$I = excluded.%1$I', a.attname), ', ' order by a.attnum)
                   filter (where a.attname <> llave)
          into columnas, actualizaciones
          from pg_attribute a
          join pg_class c on c.oid = a.attrelid
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relname = tg_table_name
           and a.attnum > 0 and not a.attisdropped and a.attgenerated = '';
        sentencia := format(
            'insert into public.%1$I (%2$s) select %2$s from json_populate_record(null::public.%1$I, %3$L::json) '
            'on conflict (%4$I) do update set %5$s',
            tg_table_name, columnas, payload::text, llave, actualizaciones);
    end if;

    perform dblink_connect(conexion);
    perform dblink_exec(sentencia);
    perform dblink_disconnect();
    return case when tg_op = 'DELETE' then old else new end;
exception when others then
    begin perform dblink_disconnect(); exception when others then null; end;
    insert into mail_contact_sync_queue(sync_tabla, sync_operacion, sync_payload, sync_error)
    values (tg_table_name, tg_op, payload, sqlerrm);
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

do $$
declare tabla text;
begin
    foreach tabla in array array['app_contactos', 'app_contacto_email', 'app_email',
            'app_grupo_contacto', 'app_grupo_email', 'app_usuario_email', 'broker_usuario_grupo'] loop
        execute format('drop trigger if exists mail_sync_contacts on public.%I', tabla);
        execute format('create trigger mail_sync_contacts after insert or update or delete on public.%I '
                       'for each row execute function mail_fn_sync_contact_change()', tabla);
    end loop;
end;
$$;
