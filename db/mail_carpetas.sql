create table if not exists mail_carpetas (
    carpeta_id bigint generated always as identity primary key,
    usuario_id text not null,
    mail_domain text not null,
    carpeta_padre_id bigint,
    carpeta_imap text not null check (btrim(carpeta_imap) <> ''),
    carpeta_nombre text not null check (btrim(carpeta_nombre) <> ''),
    carpeta_orden integer not null default 0,
    carpeta_seleccionable boolean not null default true,
    carpeta_activa boolean not null default true,
    carpeta_actualizada timestamp with time zone not null default current_timestamp,
    foreign key (usuario_id, mail_domain)
        references app_usuario_mail (usuario_id, mail_domain) on delete cascade,
    unique (usuario_id, mail_domain, carpeta_imap),
    unique (carpeta_id, usuario_id, mail_domain),
    foreign key (carpeta_padre_id, usuario_id, mail_domain)
        references mail_carpetas (carpeta_id, usuario_id, mail_domain) on delete cascade,
    check (carpeta_padre_id is null or carpeta_padre_id <> carpeta_id)
);

create index if not exists mail_carpetas_menu_idx
    on mail_carpetas (usuario_id, mail_domain, carpeta_padre_id, carpeta_orden, carpeta_nombre)
    where carpeta_activa;

revoke all on mail_carpetas from public;
