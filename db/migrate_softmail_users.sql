insert into app_usuarios (
    usuario_id, usuario_password, usuario_nombre, usuario_estado, usuario_hash_auth
)
select lower(split_part(s.user_uid, '@', 1)), :'temporary_password', s.user_full_name, '1', 'UPDATE_PASSWORD'
  from softmail_users s
 where not exists (
    select 1 from app_usuarios u
     where lower(u.usuario_id) = lower(split_part(s.user_uid, '@', 1))
 )
on conflict (usuario_id) do nothing;

with mapped as (
    select s.*,
           coalesce(
               (select u.usuario_id from app_usuarios u
                 where lower(u.usuario_id) = lower(split_part(s.user_uid, '@', 1)) limit 1),
               (select e.usuario_id from app_usuario_email e
                 where lower(e.usuario_email_email) = lower(s.user_uid)
                 order by e.usuario_email_por_defecto desc nulls last, e.rowid limit 1)
           ) as usuario_id
      from softmail_users s
)
insert into app_usuario_mail (
    usuario_id, mail_domain, mail_os_uid, mail_os_gid, mail_home,
    mail_soft_expiration_date, mail_hard_expiration_date
)
select usuario_id, user_domain, user_os_uid, user_os_gid, user_home,
       user_soft_expiration_date, user_hard_expiration_date
  from mapped
 where usuario_id is not null
on conflict (usuario_id, mail_domain) do update set
    mail_os_uid = excluded.mail_os_uid,
    mail_os_gid = excluded.mail_os_gid,
    mail_home = excluded.mail_home,
    mail_soft_expiration_date = excluded.mail_soft_expiration_date,
    mail_hard_expiration_date = excluded.mail_hard_expiration_date;

insert into app_usuario_email (
    usuario_email_email, usuario_email_estado, usuario_id,
    usuario_email_por_defecto, usuario_email_validado
)
select s.user_uid, 1, m.usuario_id, 1, true
  from softmail_users s
  join app_usuario_mail m
    on lower(m.usuario_id) = lower(split_part(s.user_uid, '@', 1))
 where not exists (
    select 1 from app_usuario_email e
     where e.usuario_id = m.usuario_id
       and lower(e.usuario_email_email) = lower(s.user_uid)
 );

do $function$
declare
    legacy_count integer;
    migrated_count integer;
begin
    select count(*) into legacy_count from softmail_users;
    select count(*) into migrated_count
      from softmail_users s
      join app_usuario_mail m
        on lower(m.usuario_id) = lower(split_part(s.user_uid, '@', 1))
       and lower(m.mail_domain) = lower(s.user_domain);
    if migrated_count <> legacy_count then
        raise exception 'Migrated % of % mail users', migrated_count, legacy_count;
    end if;
end;
$function$;
