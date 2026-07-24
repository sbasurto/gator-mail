-- Filtros IMAP autónomos. El worker procesa únicamente mensajes recibidos
-- después de su primer arranque por buzón.
create table if not exists mail_filtro_reglas (
    regla_id bigserial primary key,
    mailbox text not null check (mailbox = lower(mailbox) and mailbox ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'),
    nombre text not null check (length(trim(nombre)) between 1 and 100),
    prioridad integer not null default 100 check (prioridad between 1 and 9999),
    habilitada boolean not null default true,
    campo text not null check (campo in ('FROM', 'TO', 'CC', 'SUBJECT', 'HEADER', 'SIZE')),
    operador text not null check (operador in ('CONTAINS', 'EQUALS', 'STARTS_WITH', 'ENDS_WITH', 'GT', 'LT')),
    encabezado text check (encabezado is null or encabezado ~ '^[A-Za-z0-9-]{1,100}$'),
    valor text not null check (length(valor) between 1 and 500),
    carpeta_destino text not null check (length(carpeta_destino) between 1 and 500),
    fecha_creacion timestamptz not null default current_timestamp,
    fecha_actualizacion timestamptz not null default current_timestamp,
    unique (mailbox, nombre)
);

create index if not exists mail_filtro_reglas_activas_idx
    on mail_filtro_reglas(mailbox, prioridad, regla_id) where habilitada;

create table if not exists mail_filtro_estado (
    mailbox text primary key,
    uidvalidity bigint,
    ultimo_uid bigint not null default 0,
    estado text not null default 'INICIANDO',
    ultimo_error text,
    reintentos integer not null default 0,
    fecha_heartbeat timestamptz not null default current_timestamp,
    fecha_actualizacion timestamptz not null default current_timestamp
);

create table if not exists mail_filtro_auditoria (
    auditoria_id bigserial primary key,
    mailbox text not null,
    uidvalidity bigint not null,
    uid bigint not null,
    regla_id bigint references mail_filtro_reglas(regla_id) on delete set null,
    regla_nombre text,
    carpeta_destino text,
    message_id text,
    estado text not null check (estado in ('PROCESANDO', 'MOVIDO', 'SIN_COINCIDENCIA', 'REINTENTO', 'FALLIDO', 'DESCONOCIDO')),
    intento integer not null default 1,
    detalle text,
    fecha_creacion timestamptz not null default current_timestamp,
    fecha_actualizacion timestamptz not null default current_timestamp,
    unique (mailbox, uidvalidity, uid)
);

create index if not exists mail_filtro_auditoria_mailbox_idx
    on mail_filtro_auditoria(mailbox, fecha_actualizacion desc);

create or replace function mail_fn_filtros(v_email text)
returns text language plpgsql stable security definer set search_path = public as $$
declare resultado json;
begin
    select json_build_object(
        'codigo', '0',
        'reglas', coalesce((select json_agg(json_build_object(
            'id', r.regla_id, 'name', r.nombre, 'priority', r.prioridad,
            'enabled', r.habilitada, 'field', r.campo, 'operator', r.operador,
            'header', coalesce(r.encabezado, ''), 'value', r.valor,
            'destination', r.carpeta_destino
        ) order by r.prioridad, r.regla_id)
        from mail_filtro_reglas r where r.mailbox = lower(trim(v_email))), '[]'::json),
        'estado', coalesce((select json_build_object(
            'status', e.estado, 'lastUid', e.ultimo_uid,
            'heartbeat', to_char(e.fecha_heartbeat at time zone current_setting('TIMEZONE'), 'YYYY-MM-DD HH24:MI:SS'),
            'retries', e.reintentos, 'error', coalesce(e.ultimo_error, '')
        ) from mail_filtro_estado e where e.mailbox = lower(trim(v_email))),
        json_build_object('status', 'SIN_INICIAR', 'lastUid', 0, 'heartbeat', '', 'retries', 0, 'error', '')),
        'auditoria', coalesce((select json_agg(x.item order by x.fecha desc) from (
            select a.fecha_actualizacion fecha, json_build_object(
                'uid', a.uid, 'rule', coalesce(a.regla_nombre, 'Sin coincidencia'),
                'destination', coalesce(a.carpeta_destino, ''), 'status', a.estado,
                'attempt', a.intento,
                'date', to_char(a.fecha_actualizacion at time zone current_setting('TIMEZONE'), 'YYYY-MM-DD HH24:MI:SS'),
                'detail', coalesce(a.detalle, '')
            ) item
            from mail_filtro_auditoria a
            where a.mailbox = lower(trim(v_email))
            order by a.fecha_actualizacion desc limit 50
        ) x), '[]'::json)
    ) into resultado;
    return resultado::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_filtro_guardar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare
    v jsonb := v_json::jsonb;
    correo text := lower(trim(v ->> 'mailbox'));
    id bigint := nullif(v ->> 'id', '')::bigint;
    v_nombre text := trim(v ->> 'name');
    v_prioridad integer := (v ->> 'priority')::integer;
    v_habilitada boolean := coalesce((v ->> 'enabled')::boolean, false);
    v_campo text := upper(trim(v ->> 'field'));
    v_operador text := upper(trim(v ->> 'operator'));
    v_encabezado text := nullif(trim(v ->> 'header'), '');
    v_valor text := trim(v ->> 'value');
    v_destino text := trim(v ->> 'destination');
begin
    if correo !~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'
            or length(v_nombre) not between 1 and 100 or v_prioridad not between 1 and 9999
            or v_campo not in ('FROM', 'TO', 'CC', 'SUBJECT', 'HEADER', 'SIZE')
            or v_operador not in ('CONTAINS', 'EQUALS', 'STARTS_WITH', 'ENDS_WITH', 'GT', 'LT')
            or (v_campo = 'HEADER' and (v_encabezado is null or v_encabezado !~ '^[A-Za-z0-9-]{1,100}$'))
            or (v_campo <> 'HEADER' and v_encabezado is not null)
            or (v_campo = 'SIZE' and (v_operador not in ('GT', 'LT') or v_valor !~ '^[0-9]{1,12}$'))
            or (v_campo <> 'SIZE' and v_operador in ('GT', 'LT'))
            or length(v_valor) not between 1 and 500 or length(v_destino) not between 1 and 500
            or upper(v_destino) = 'INBOX' then
        return json_build_object('codigo', '-1', 'mensaje', 'Regla inválida')::text;
    end if;
    if id is null then
        insert into mail_filtro_reglas(mailbox, nombre, prioridad, habilitada, campo, operador,
            encabezado, valor, carpeta_destino)
        values (correo, v_nombre, v_prioridad, v_habilitada, v_campo, v_operador,
            v_encabezado, v_valor, v_destino)
        returning regla_id into id;
    else
        update mail_filtro_reglas set nombre = v_nombre, prioridad = v_prioridad, habilitada = v_habilitada,
               campo = v_campo, operador = v_operador, encabezado = v_encabezado, valor = v_valor,
               carpeta_destino = v_destino, fecha_actualizacion = current_timestamp
         where regla_id = id and mailbox = correo;
        if not found then return json_build_object('codigo', '-1', 'mensaje', 'Regla inexistente')::text; end if;
    end if;
    return json_build_object('codigo', '0', 'id', id)::text;
exception when unique_violation then
    return json_build_object('codigo', '-1', 'mensaje', 'Ya existe una regla con ese nombre')::text;
when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

create or replace function mail_fn_filtro_eliminar(v_json text)
returns text language plpgsql security definer set search_path = public as $$
declare v jsonb := v_json::jsonb;
begin
    delete from mail_filtro_reglas
     where regla_id = (v ->> 'id')::bigint and mailbox = lower(trim(v ->> 'mailbox'));
    if not found then return json_build_object('codigo', '-1', 'mensaje', 'Regla inexistente')::text; end if;
    return json_build_object('codigo', '0')::text;
exception when others then
    return json_build_object('codigo', '-1', 'mensaje', sqlerrm)::text;
end;
$$;

revoke all on mail_filtro_reglas, mail_filtro_estado, mail_filtro_auditoria from public;
revoke all on function mail_fn_filtros(text), mail_fn_filtro_guardar(text), mail_fn_filtro_eliminar(text) from public;
grant execute on function mail_fn_filtros(text), mail_fn_filtro_guardar(text), mail_fn_filtro_eliminar(text) to w3apps;
