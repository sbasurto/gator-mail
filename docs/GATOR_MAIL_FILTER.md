# Gator Mail Filter

Servicio GPLv3 independiente de Tomcat que aplica reglas almacenadas en
`db_gatormail` mediante IMAP IDLE y UID MOVE. No requiere Sieve y no lee ni
almacena cuerpos de mensajes.

## Requisitos

- Java 21, PostgreSQL, PgBouncer en el puerto 6432 y Dovecot con IMAPS.
- `db/mail_filters.sql` aplicado en `db_gatormail`.
- Un usuario maestro Dovecot y un rol PostgreSQL limitado a las tablas
  `mail_filtro_*`.

## Construcción e instalación

```bash
./gradlew check distTar
sudo deploy/install-filter.sh \
  build/distributions/gator-mail-filter-0.1.0.tar \
  deploy/gator-mail-filter.service \
  deploy/gator-mail-filter.openrc
```

El instalador crea los usuarios, genera secretos en `/etc/gator-mail-filter/`,
configura el usuario maestro Dovecot, concede los permisos mínimos y activa la
unidad systemd u OpenRC. No imprime contraseñas. Si existe el `auth_file`
estándar de PgBouncer, sincroniza automáticamente el verificador SCRAM del rol
`gator_mail_filter`; con `auth_query` no se necesita ese paso.

Defina `GATOR_MAIL_FILTER_IMAP_HOST` con el nombre incluido en el certificado
TLS durante la primera instalación. Las actualizaciones conservan ese valor.

Para una instalación manual, copie
`deploy/gator-mail-filter.conf.example` a `/etc/gator-mail-filter.conf`,
reemplace todos los secretos y mantenga el archivo con permisos `0640`.

## Operación

```bash
systemctl status gator-mail-filter
journalctl -u gator-mail-filter -f -o cat
```

Cada línea del log es JSON e incluye buzón, UIDVALIDITY, UID, regla, carpeta,
intento y resultado. Los reintentos y el último error también están disponibles
en **Configuración > Filtros**. El primer arranque comienza en `UIDNEXT - 1`,
por lo que no reorganiza mensajes históricos.

Para actualizar, ejecute de nuevo el instalador con el nuevo `distTar`. Cada
versión queda en `/opt/gator-mail-filter/releases/`; para regresar, cambie el
enlace `/opt/gator-mail-filter/current` a una versión anterior y reinicie el
servicio.

## Integraciones propias

Cambie las variables `GATOR_MAIL_FILTER_DB_*`, `GATOR_MAIL_FILTER_IMAP_*` y
`GATOR_MAIL_FILTER_MASTER_*` para su infraestructura. El proyecto no depende de
dominios, usuarios ni servicios privados de Soft Gator.
