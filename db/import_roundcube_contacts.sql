with deduplicados as (
    select distinct on (email) usuario_id, nombre, email, changed, eliminado
      from roundcube_contacts_import
     where email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'
     order by email, changed desc nulls last, contact_id desc
)
insert into app_contactos (
    contacto_id, contacto_idctrl, contacto_nombre, cuenta_id, bodega_id, usuario_id
)
select 'roundcube:' || md5(email), 'roundcube:' || md5(email), nombre, 0, 0, usuario_id
  from deduplicados
on conflict (contacto_id) do update set
    contacto_nombre = excluded.contacto_nombre,
    usuario_id = excluded.usuario_id;

with deduplicados as (
    select distinct on (email) email, eliminado
      from roundcube_contacts_import
     where email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$'
     order by email, changed desc nulls last, contact_id desc
)
insert into app_contacto_email (
    contacto_email_id, contacto_email_email, contacto_email_estado, contacto_id
)
select 'roundcube-email:' || md5(email), email, case when eliminado = 0 then 1 else -1 end,
       'roundcube:' || md5(email)
  from deduplicados
on conflict (contacto_email_id) do update set
    contacto_email_email = excluded.contacto_email_email,
    contacto_email_estado = excluded.contacto_email_estado,
    contacto_id = excluded.contacto_id;

do $function$
declare
    esperado integer;
    migrado integer;
begin
    select count(distinct email) into esperado
      from roundcube_contacts_import
     where email ~ '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$';
    select count(*) into migrado
      from app_contacto_email
     where contacto_email_id like 'roundcube-email:%';
    if migrado <> esperado then
        raise exception 'Contactos Roundcube migrados: %, esperados: %', migrado, esperado;
    end if;
end;
$function$;
