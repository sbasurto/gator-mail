# Gator Mail

Cliente web IMAP ligero integrado con las cuentas, grupos y aplicaciones de
Gator. El primer alcance permite iniciar sesión, verificar opcionalmente el acceso
mediante una clave enviada por SMS, consultar las carpetas IMAP y leer
mensajes de texto o HTML aislado. También permite mover, renombrar y eliminar
carpetas directamente en el servidor IMAP, redactar en Markdown y guardar el
resultado como texto y HTML sanitizado en Borradores. Los mensajes se pueden
buscar, seleccionar y eliminar por bloque, o mover arrastrándolos a otra carpeta.
Las carpetas se renombrarán o eliminarán desde su menú de clic derecho.
El buzón pagina 20 mensajes de forma predeterminada y permite mostrar 20, 40,
60, 80 o 100 mensajes por página.
La redacción admite Para, CC y CCO, con selección múltiple desde el directorio
de contactos asociado a los grupos del usuario.

No utiliza Roundcube, no guarda contraseñas IMAP por usuario y no interpreta
HTML recibido. Keycloak autentica al usuario y el mismo token OAuth2 abre su
buzón en Dovecot mediante XOAUTH2. Exim recibe el envío por SMTPS desde la IP
autorizada de la aplicación, con el remitente fijado al buzón autenticado y sin
almacenar contraseñas por usuario.

## Requisitos

- Java 21 y un contenedor compatible con Jakarta Servlet 6.1, como Tomcat 11.
- PostgreSQL con el esquema autónomo de Gator Mail instalado.
- Keycloak 26.7 con el proveedor de usuarios Gator instalado.
- Dovecot 2.3.21 o posterior con TLS, `OAUTHBEARER` y `XOAUTH2`.
- `gator-lib` 1.0.0-SNAPSHOT. Durante el desarrollo se resuelve como un build
  compuesto desde `../gator-lib`.

## Configuración IMAP

El proceso de Tomcat puede recibir estas variables de entorno:

- `GATOR_MAIL_IMAP_HOST` (predeterminado: `mail.soft-gator.com`)
- `GATOR_MAIL_IMAP_PORT` (predeterminado: `993`)
- `GATOR_MAIL_SMTP_HOST` (predeterminado: `mail.soft-gator.com`)
- `GATOR_MAIL_SMTP_PORT` (predeterminado: `465`)
- `GATOR_MAIL_OAUTH_ISSUER` (predeterminado:
  `https://mail.soft-gator.com/auth/realms/gator`)
- `GATOR_MAIL_OAUTH_REDIRECT_URI` (opcional; se calcula desde la petición si
  no se define)
- `GATOR_MAIL_SMS_ENABLED` (`true` para habilitar el desafío; deshabilitado por defecto)
- `GATOR_MAIL_SMS_ENDPOINT` (opcional; URL HTTPS del proveedor de desafíos)
- `GATOR_MAIL_SMS_SECRET` (secreto Bearer compartido con ese proveedor)
- `GATOR_MAIL_EVENT_ENDPOINT` (opcional; URL HTTPS para sincronizar eventos)
- `GATOR_MAIL_EVENT_SECRET` (secreto Bearer compartido con ese endpoint)
- `GATOR_MAIL_USER_PROVISIONER` (opcional; por defecto
  `/usr/local/sbin/gator-mail-user-add`)

El cliente público `gator-mail` debe habilitar Authorization Code con PKCE S256
y registrar exactamente los URI de retorno usados por cada entorno.
El tema claro de entrada y salida se encuentra en `keycloak-theme/gator-mail`.

Las carpetas IMAP se guardan en la tabla jerárquica `mail_carpetas`; el script
idempotente para crearla está en `db/mail_carpetas.sql`.
El listado de carpetas y mensajes se sirve desde la caché reconstruible de
`db_gatormail`, instalada con `db/mail_cache.sql`. Sólo conserva el remitente,
asunto, fecha, estado leído y la identidad IMAP `(carpeta, UIDVALIDITY, UID)`;
los cuerpos y adjuntos permanecen exclusivamente en Dovecot. La primera
hidratación consulta IMAP y las siguientes actualizaciones usan `UIDNEXT` y
`MODSEQ`. Si cambia `UIDVALIDITY` o se detecta una eliminación, se reconstruye
únicamente la carpeta afectada. Abrir, responder, mover, eliminar y descargar
siguen operando primero en IMAP y actualizan PostgreSQL sólo después del éxito.
La prueba reversible del esquema está en `db/mail_cache_test.sql`.
El tablero calcula los remitentes de los últimos siete días y los históricos
con `db/mail_sender_rankings.sql`, exclusivamente a partir de la caché de Entrada.
El benchmark reversible `db/mail_cache_benchmark.sql` mide listado y rankings
con 100 000 encabezados y siempre termina con `rollback`.
El directorio autónomo se instala con `db/mail_contacts.sql`; no requiere
tablas externas ni copia hashes de usuario.
La administración de usuarios y contactos se instala con `db/mail_admin.sql`;
las cuentas asociadas a `sbasurto@soft-gator.com` y la cuenta local `admin`
quedan autorizadas inicialmente y pueden ampliarse desde la tabla
`mail_administradores`.
El calendario autónomo se instala con `db/mail_calendar.sql` y administra sus
eventos, grupos y participantes directamente en `db_gatormail`.
Cuando `GATOR_MAIL_EVENT_ENDPOINT` y `GATOR_MAIL_EVENT_SECRET` están definidos,
cada alta se envía además por `POST` como JSON con `action: event`; aceptar,
marcar como tentativa o rechazar una invitación se envía con `action: reply`,
`uid`, `sequence`, `attendee` y `status` (`ACCEPTED`, `TENTATIVE` o
`DECLINED`); concluir un evento propio se envía con `action: complete`,
`eventId`, `organizer`, `sequence` y `status: COMPLETED`. El endpoint debe ser idempotente por `eventId` y devolver
`codigo: "0"`. Cada instalación puede reemplazarlo para sincronizar su propio
calendario externo.
Este contrato es independiente de `GATOR_MAIL_SMS_ENDPOINT`: no se deben
combinar las URL ni los secretos. Si no se configura el endpoint de eventos,
el calendario continúa funcionando únicamente con `db_gatormail`; si no se
configura el endpoint SMS, no se solicita la clave temporal.

## Compilar y probar

Coloque `gator-mail` y `gator-lib` como directorios hermanos y ejecute:

```bash
./gradlew clean check war
```

El artefacto queda en `dist/gator-mail.war`.

## Despliegue Gator

El contexto esperado es `/gator-mail`. La aplicación requiere acceso a la
configuración de identidad `pg_gatormail_identity`, una entrada `broker_db` con
`db_use = 'mail'` y la asignación de esa aplicación a los grupos autorizados.
El segundo factor sólo se solicita cuando `GATOR_MAIL_SMS_ENABLED=true`,
`GATOR_MAIL_SMS_ENDPOINT` y `GATOR_MAIL_SMS_SECRET` están configurados, y el usuario lo mantiene habilitado
en **Configuración > Opciones de usuario**. El endpoint recibe JSON por `POST`
con autenticación `Bearer`: `action` (`send` o `correct`), `usuario`,
`application`, `userHint` y, para corregir, `telefono`. Debe devolver
`codigo`, `phoneSent`, `challengeHash`, `expiresAt` y, en errores de envío,
`mensaje` y `phoneCorrectionAllowed`. Cada instalación puede reemplazarlo por
su propio proveedor; sin endpoint, el correo abre sin solicitar clave.
La administración de usuarios muestra el teléfono y la acción **Agregar a
Global Safe List** sólo cuando ese endpoint y su secreto están configurados.
Para actualizar un teléfono envía `action: sync`, `usuario`, `email`, `name` y
`telefono`; para autorizar el número envía `action: safeList`, `usuario` y
`telefono`. El proveedor debe validar que el teléfono corresponda al usuario.
Una instalación sin soporte de lista segura puede omitir el endpoint completo;
Gator Mail no incluye credenciales ni dependencias de Twilio.
La preferencia se guarda en `app_usuarios.usuario_sms_auth`; una instalación
que implemente el endpoint debe omitir el desafío cuando ese valor sea falso.
Con la integración Soft Gator, el endpoint intenta primero una autorización en
Gator Mobile y utiliza SMS sólo cuando no hay un dispositivo conectado, vence
la solicitud o el usuario elige **Usar SMS**. Gator Mail nunca usa correo como
fallback para evitar depender del mismo buzón que se está intentando abrir.

## Filtros IMAP

Los filtros no dependen de Sieve ni se ejecutan dentro de Tomcat. El servicio
independiente `gator-mail-filter` obtiene las reglas de `db_gatormail`, espera
correo nuevo mediante IMAP IDLE y mueve la primera coincidencia con UID MOVE.
El checkpoint `(mailbox, UIDVALIDITY, UID)`, los reintentos y las últimas 50
decisiones se consultan en **Configuración > Filtros**. El primer arranque de
cada buzón toma como línea base su UID actual: no reorganiza correo histórico.

Instale primero `db/mail_filters.sql` y después el servicio. En servidores
systemd use `deploy/gator-mail-filter.service`; para Gentoo/OpenRC se incluye
`deploy/gator-mail-filter.openrc`. `deploy/install-filter.sh` crea un usuario
de sistema, un usuario maestro Dovecot y un rol PostgreSQL restringido sin
imprimir sus secretos. Los eventos estructurados se consultan con:

```bash
journalctl -u gator-mail-filter -f -o cat
```

Cada registro incluye buzón, UIDVALIDITY, UID, regla, carpeta, intento,
resultado y detalle. Nunca contiene cuerpos ni contraseñas.
La guía completa de construcción, instalación, actualización y reversa está en
[`docs/GATOR_MAIL_FILTER.md`](docs/GATOR_MAIL_FILTER.md).

La sesión HTTP se conserva durante reinicios controlados de Gator Mail para no
repetir el segundo factor mientras la misma sesión continúe activa. Cerrar
sesión o dejarla expirar elimina esa verificación.
Si el primer SMS no puede entregarse, el proveedor puede habilitar una única
corrección del teléfono mediante `phoneCorrectionAllowed`.

El vínculo **Contraseña** inicia directamente la acción OIDC `UPDATE_PASSWORD`
con el formulario del tema de Gator Mail; no abre la consola de cuenta de
Keycloak. Los cambios usan el proveedor Gator existente, que sincroniza
`app_usuarios` mediante `app_fn_admon_tablas_all`.

## Aprovisionamiento de usuarios y buzones

El alta administrativa crea primero la cuenta Linux y después registra el
usuario en PostgreSQL. Instale el helper y su regla limitada de `sudo` en el
servidor de correo:

```bash
sudo install -o root -g root -m 0755 deploy/gator-mail-user-add /usr/local/sbin/
sudo install -o root -g root -m 0440 deploy/gator-mail-user-add.sudoers /etc/sudoers.d/gator-mail-user-add
sudo visudo -cf /etc/sudoers.d/gator-mail-user-add
```

El directorio base del dominio debe existir. Por ejemplo,
`jperez@soft-gator.com` se crea como `/home/softgatorcom/jperez`; el helper es
idempotente, prepara `.maildir/{cur,new,tmp}` y rechaza reutilizar un usuario
ubicado en otro directorio.

- Si el correo ya existe en `softmail_users`, se reutilizan su identidad,
  directorio y mensajes; Gator Mail no cambia su contraseña.
- Si existe como usuario Unix heredado, debe registrarse como buzón virtual
  apuntando al Maildir actual antes de retirar la compatibilidad PAM.
- Si no existe, el administrador crea la cuenta Unix en el directorio del
  dominio y registra el acceso de Gator Mail con contraseña temporal.
- Hasta que el aprovisionamiento termine, la aplicación muestra el estado
  `Tu buzón está pendiente` y permite comprobarlo nuevamente.

## Seguridad

- La segunda verificación dura cinco minutos, permite cinco intentos y limita
  el reenvío a una solicitud cada 30 segundos.
- Los tokens, sesiones y contraseñas no se escriben en el log.
- Authorization Code usa PKCE S256 y rota el identificador de sesión al entrar.
- Los mensajes se abren en modo de solo lectura y el cuerpo se limita a 200 KB.
- El HTML del mensaje se sanitiza y se presenta dentro de un `iframe sandbox`.
- El borrado mueve los mensajes a Papelera; desde Papelera es definitivo.
- Las operaciones sobre carpetas requieren sesión verificada y token CSRF;
  `INBOX` no se puede mover, renombrar ni eliminar.

## Licencia

GPL-3.0. Consulte [LICENSE](LICENSE) y [NOTICE](NOTICE).
