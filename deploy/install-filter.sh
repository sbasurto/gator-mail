#!/bin/bash
set -euo pipefail

archive=${1:?Falta el paquete del servicio}
systemd_unit=${2:?Falta la unidad systemd}
openrc_service=${3:?Falta el servicio OpenRC}
service_user=gator-mail-filter
service_home=/opt/gator-mail-filter
secret_dir=/etc/gator-mail-filter
secret_file=$secret_dir/imap-master.secret
db_secret_file=$secret_dir/database.secret
dovecot_master=/etc/dovecot/passwd.masterusers
dovecot_config=/etc/dovecot/conf.d/11-gator-mail-filter.conf
dovecot_main=/etc/dovecot/dovecot.conf

if [[ $EUID -ne 0 ]]; then
    echo "El instalador requiere root" >&2
    exit 1
fi
for file in "$archive" "$systemd_unit" "$openrc_service"; do
    [[ -f $file ]] || { echo "No existe $file" >&2; exit 1; }
done
command -v psql >/dev/null
command -v openssl >/dev/null
command -v doveconf >/dev/null

getent group "$service_user" >/dev/null || groupadd --system "$service_user"
getent passwd "$service_user" >/dev/null || useradd --system --gid "$service_user" \
    --home-dir "$service_home" --shell /sbin/nologin "$service_user"
install -d -m 0755 "$service_home/releases"
install -d -m 0750 -o root -g "$service_user" "$secret_dir"
install -d -m 0750 -o "$service_user" -g "$service_user" /var/log/gator-mail-filter

if [[ ! -s $secret_file ]]; then
    temporary_secret=$(mktemp "$secret_dir/.imap-master.XXXXXX")
    openssl rand -hex 32 > "$temporary_secret"
    chown root:"$service_user" "$temporary_secret"
    chmod 0640 "$temporary_secret"
    mv -f "$temporary_secret" "$secret_file"
fi
if [[ ! -s $db_secret_file ]]; then
    temporary_secret=$(mktemp "$secret_dir/.database.XXXXXX")
    openssl rand -hex 32 > "$temporary_secret"
    chown root:"$service_user" "$temporary_secret"
    chmod 0640 "$temporary_secret"
    mv -f "$temporary_secret" "$db_secret_file"
fi

release="$service_home/releases/$(date +%Y%m%d%H%M%S)"
install -d -m 0755 "$release"
tar -xf "$archive" -C "$release"
application=$(find "$release" -mindepth 1 -maxdepth 1 -type d -name 'gator-mail-filter-*' -print -quit)
[[ -x $application/bin/gator-mail-filter ]] || { echo "Paquete del servicio inválido" >&2; exit 1; }
chown -R root:root "$release"
ln -sfn "$application" "$service_home/.current"
mv -Tf "$service_home/.current" "$service_home/current"

master_hash="{SHA512-CRYPT}$(openssl passwd -6 -stdin < "$secret_file")"
temporary_master=$(mktemp /etc/dovecot/.passwd.masterusers.XXXXXX)
if [[ -f $dovecot_master ]]; then
    grep -v "^${service_user}:" "$dovecot_master" > "$temporary_master" || true
fi
printf '%s:%s\n' "$service_user" "$master_hash" >> "$temporary_master"
install -m 0640 -o root -g dovecot "$temporary_master" "$dovecot_master"
rm -f "$temporary_master"

temporary_dovecot=$(mktemp /etc/dovecot/conf.d/.11-gator-mail-filter.XXXXXX)
printf '%s\n' \
    'auth_master_user_separator = *' \
    'passdb passwd-file {' \
    "  passwd_file_path = $dovecot_master" \
    '  master = yes' \
    '}' > "$temporary_dovecot"
if [[ -f $dovecot_config ]]; then
    cp -p "$dovecot_config" "$dovecot_config.previous"
fi
cp -p "$dovecot_main" "$dovecot_main.previous"
install -m 0644 -o root -g root "$temporary_dovecot" "$dovecot_config"
rm -f "$temporary_dovecot"
if ! grep -qxF '!include conf.d/11-gator-mail-filter.conf' "$dovecot_main"; then
    printf '\n!include conf.d/11-gator-mail-filter.conf\n' >> "$dovecot_main"
fi
if ! doveconf -n >/dev/null; then
    if [[ -f $dovecot_config.previous ]]; then
        mv -f "$dovecot_config.previous" "$dovecot_config"
    else
        rm -f "$dovecot_config"
    fi
    mv -f "$dovecot_main.previous" "$dovecot_main"
    echo "La configuración Dovecot no es válida; se restauró la anterior" >&2
    exit 1
fi
rm -f "$dovecot_config.previous"
rm -f "$dovecot_main.previous"

database_secret=$(<"$db_secret_file")
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 -v role_password="$database_secret" -d db_gatormail <<'SQL'
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'gator_mail_filter') then
        create role gator_mail_filter login;
    end if;
end $$;
alter role gator_mail_filter password :'role_password';
grant connect on database db_gatormail to gator_mail_filter;
grant usage on schema public to gator_mail_filter;
grant select on mail_filtro_reglas to gator_mail_filter;
grant select, insert, update on mail_filtro_estado, mail_filtro_auditoria to gator_mail_filter;
grant usage, select on sequence mail_filtro_auditoria_auditoria_id_seq to gator_mail_filter;
SQL

temporary_environment=$(mktemp /etc/.gator-mail-filter.XXXXXX)
printf '%s\n' \
    'GATOR_MAIL_FILTER_DB_URL=jdbc:postgresql://127.0.0.1:6432/db_gatormail' \
    'GATOR_MAIL_FILTER_DB_USER=gator_mail_filter' \
    "GATOR_MAIL_FILTER_DB_PASSWORD=$database_secret" \
    'GATOR_MAIL_FILTER_IMAP_HOST=127.0.0.1' \
    'GATOR_MAIL_FILTER_IMAP_PORT=993' \
    "GATOR_MAIL_FILTER_MASTER_USER=$service_user" \
    "GATOR_MAIL_FILTER_MASTER_SECRET_FILE=$secret_file" \
    'GATOR_MAIL_FILTER_MASTER_SEPARATOR=*' \
    'GATOR_MAIL_FILTER_REFRESH_SECONDS=15' \
    'GATOR_MAIL_FILTER_MAX_ATTEMPTS=5' > "$temporary_environment"
install -m 0640 -o root -g "$service_user" "$temporary_environment" /etc/gator-mail-filter.conf
rm -f "$temporary_environment"

if command -v systemctl >/dev/null && [[ -d /run/systemd/system ]]; then
    install -m 0644 -o root -g root "$systemd_unit" /etc/systemd/system/gator-mail-filter.service
    systemctl daemon-reload
    systemctl reload dovecot
    systemctl enable --now gator-mail-filter.service
    systemctl restart gator-mail-filter.service
    systemctl --no-pager --full status gator-mail-filter.service
else
    install -m 0755 -o root -g root "$openrc_service" /etc/init.d/gator-mail-filter
    rc-update add gator-mail-filter default
    rc-service dovecot reload
    rc-service gator-mail-filter restart
    rc-service gator-mail-filter status
fi
