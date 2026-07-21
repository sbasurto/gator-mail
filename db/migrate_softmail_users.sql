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
    identity_count integer;
    mailbox_count integer;
    address_count integer;
begin
    select count(*) into legacy_count from softmail_users;
    select count(*) into identity_count
      from softmail_users s
      join app_usuarios u
        on lower(u.usuario_id) = lower(split_part(s.user_uid, '@', 1))
       and coalesce(u.usuario_estado, '0') = '1';
    select count(*) into mailbox_count
      from softmail_users s
      join app_usuario_mail m
        on lower(m.usuario_id) = lower(split_part(s.user_uid, '@', 1))
       and lower(m.mail_domain) = lower(s.user_domain);
    select count(*) into address_count
      from softmail_users s
     where exists (
        select 1 from app_usuario_email e
         where lower(e.usuario_email_email) = lower(s.user_uid)
           and coalesce(e.usuario_email_estado, 0) >= 0
     );
    if identity_count <> legacy_count or mailbox_count <> legacy_count or address_count <> legacy_count then
        raise exception 'Mail access coverage: identities %, mailboxes %, addresses %, expected %',
                identity_count, mailbox_count, address_count, legacy_count;
    end if;
end;
$function$;
