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
- `GATOR_MAIL_OAUTH_ISSUER` (issuer HTTPS; si falta, usa `GATOR_OIDC_ISSUER`;
  debe definirse al menos una de las dos variables, sin URL incorporada)
- `GATOR_MAIL_OAUTH_REDIRECT_URI` (opcional; se calcula desde la petición si
  no se define)
- `GATOR_MAIL_SMS_ENABLED` (`true` para habilitar el desafío; deshabilitado por defecto)
- `GATOR_MAIL_SMS_ENDPOINT` (opcional; URL HTTPS del proveedor de desafíos)
- `GATOR_MAIL_SMS_SECRET` (secreto Bearer compartido con ese proveedor)
- `GATOR_MAIL_EVENT_ENDPOINT` (opcional; URL HTTPS para sincronizar eventos)
- `GATOR_MAIL_EVENT_SECRET` (secreto Bearer compartido con ese endpoint)
- `GATOR_MAIL_USER_PROVISIONER` (opcional; por defecto
  `/usr/local/sbin/gator-mail-user-add`)
- `GATOR_MAIL_USER_DELETER` (opcional; por defecto
  `/usr/local/sbin/gator-mail-user-delete`)

El cliente público `gator-mail` debe habilitar Authorization Code con PKCE S256
y registrar exactamente los URI de retorno usados por cada entorno.
El tema anterior de entrada y salida se encuentra en `keycloak-theme/gator-mail`.
El login estándar por aplicación está en [`keycloak-theme/gator-standard`](keycloak-theme/gator-standard/README.md), con nombre y logo de la configuración existente.

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
El guardado de contactos desde las aplicaciones Gator mediante `mail-sms.jsp`
requiere `db/mail_login_contact.sql` en la base autónoma de correo, después de
`db/mail_admin.sql`. Se verifica con `db/mail_login_contact_test.sql` (rollback).
Esta variante utiliza `mail_usuario_telefonos` y conserva usuarios, contraseñas
y permisos existentes.

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
con autenticación `Bearer`: `action` (`send`, `status`, `cancel` o `correct`), `usuario`,
`application`, `userHint` y, para corregir, `telefono`. Debe devolver
`codigo`, `phoneSent`, `challengeHash`, `expiresAt` y, en errores de envío,
`mensaje` y `phoneCorrectionAllowed`. Cada instalación puede reemplazarlo por
su propio proveedor; sin endpoint, el correo abre sin solicitar clave.
Cancelar, rechazar, expirar o fallar una solicitud sólo termina ese intento: la
misma sesión permite crear una autorización móvil nueva con un `requestToken`
distinto o cambiar a SMS. Los identificadores de solicitudes cerradas nunca se
reutilizan.
La acción explícita **Intentar autorización en el iPhone** envía
`mobileOnly=true`: si la cuenta no tiene un dispositivo registrado o el canal
móvil no está disponible, el proveedor debe devolver un error descriptivo y
nunca sustituir esa acción por un SMS silencioso.
La administración de usuarios muestra el teléfono y la acción **Agregar a
Global Safe List** sólo cuando ese endpoint y su secreto están configurados.
Para actualizar un teléfono envía `action: sync`, `usuario`, `email`, `name` y
`telefono`; para autorizar el número envía `action: safeList`, `usuario` y
`telefono`. El proveedor debe validar que el teléfono corresponda al usuario.
Una instalación sin soporte de lista segura puede omitir el endpoint completo;
Gator Mail no incluye credenciales ni dependencias de Twilio.
La preferencia se guarda en `app_usuarios.usuario_sms_auth` y su valor inicial
es `false`: el segundo factor es opt-in. En producción sólo `sbasurto` y
`appreview` se inicializan en `true`; cualquier otro usuario puede activarlo en
**Configuración > Opciones de usuario**. Una instalación que implemente el
endpoint debe omitir todos los canales del desafío cuando ese valor sea falso.
Si el proveedor confirma `smsDisabled: true`, Gator Mail considera satisfecho
el flujo sin crear un desafío; una respuesta incompleta nunca debe provocar un
error de aplicación.
Con la integración Soft Gator, el endpoint intenta primero una autorización en
Gator Mobile y utiliza SMS sólo cuando no hay un dispositivo conectado, vence
la solicitud o el usuario elige **Cancelar y usar SMS**. Mientras espera, la
interfaz consulta silenciosamente el estado y muestra un spinner; no recarga la
página ni presenta alertas periódicas. La cancelación envía `authorizationId` y
`requestToken`, marca la solicitud móvil como `CANCELLED` y continúa con el
método alternativo. Gator Mail nunca usa correo como
fallback para evitar depender del mismo buzón que se está intentando abrir.
Para habilitar este orden en el proveedor de Soft Gator, configure
`GATOR_MOBILE_AUTH_MODE=first`; Gator Mail envía `smsOnly=false` en la solicitud
inicial y reserva `fallback=true` para la acción explícita **Cancelar y usar SMS**.

## Filtros IMAP

Los filtros no dependen de Sieve ni se ejecutan dentro de Tomcat. El servicio
independiente `gator-mail-filter` obtiene las reglas de `db_gatormail`, revisa
Entrada cada 15 segundos y mueve la primera coincidencia con UID MOVE.
Un administrador puede marcar desde el menú contextual una dirección o dominio como spam global;
el servicio revisa también los mensajes existentes en Entrada de cada buzón. Los bloqueos y el
avance de esa revisión se administran en **Configuración > Filtros**.
El checkpoint `(mailbox, UIDVALIDITY, UID)`, los reintentos y las últimas 50
decisiones se consultan en **Configuración > Filtros**. El primer arranque de
cada buzón toma como línea base su UID actual; la interfaz permite solicitar
explícitamente que las reglas también revisen los mensajes históricos de Entrada.

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
sudo install -o root -g root -m 0755 deploy/gator-mail-user-delete /usr/local/sbin/
sudo install -o root -g root -m 0440 deploy/gator-mail-user-add.sudoers /etc/sudoers.d/gator-mail-user-add
sudo install -o root -g root -m 0440 deploy/gator-mail-user-delete.sudoers /etc/sudoers.d/gator-mail-user-delete
sudo visudo -cf /etc/sudoers.d/gator-mail-user-add
sudo visudo -cf /etc/sudoers.d/gator-mail-user-delete
```

El directorio base del dominio debe existir. Por ejemplo,
`jperez@soft-gator.com` se crea como `/home/softgatorcom/jperez`; el helper es
idempotente, prepara `.maildir/{cur,new,tmp}` y rechaza reutilizar un usuario
ubicado en otro directorio.

La baja bloquea el acceso Linux, importa el Maildir en `Revisión/<usuario>` de
la cuenta seleccionada y conserva una copia recuperable en
`/var/lib/gator-mail/deleted` antes de eliminar la identidad.

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

## Apariencia y navegación

En la cabecera o en **Configuración > Opciones de usuario > Apariencia** se puede
escoger **Azul corporativo** (predeterminado) o **Verde**. Ambos diseños comparten
las mismas funciones y permisos. La preferencia se conserva por cuenta en este
navegador; no se sincroniza entre dispositivos. Si el almacenamiento está bloqueado,
el cambio funciona durante la página actual y se informa que no pudo guardarse.

En escritorio, abrir un mensaje mantiene el listado y la paginación a su lado.
En móvil, **Menú** abre la navegación y **Volver** regresa al listado. Los contactos
tienen búsqueda por nombre o correo y los formularios de registros se despliegan
al seleccionar su encabezado. El calendario permite alternar mes y agenda.

`./gradlew check` incluye `appearanceTest` (Node 22 o posterior), que comprueba
selección de paleta, separación entre cuentas y almacenamiento bloqueado.
Para generar pantallas de prueba con datos ficticios:
`./gradlew selfTest -PuiFixtureDir=/tmp/gator-mail-ui-fixtures`.
Los prototipos originales azul y verde se conservan en `gator-gui-essay/web/`.

## Firma personal

En **Configuración > Opciones de usuario > Firma del usuario** se puede subir,
reemplazar o quitar una imagen PNG/JPG (máximo 2 MiB y 2000 × 1000 píxeles).
Se valida, ajusta proporcionalmente a un máximo de 600 × 300 píxeles y convierte
a PNG; el nombre del archivo original no se utiliza.
Al redactar, responder o reenviar, **Incluir mi firma** permite decidir si se
adjunta como imagen incrustada al final del correo, también al guardar borrador.
La firma cuenta dentro del límite de 10 archivos y 25 MiB.

Las firmas se guardan por cuenta fuera del WAR, en
`${catalina.base}/data/gator-mail/signatures`; se puede cambiar con
`GATOR_MAIL_SIGNATURE_DIR`. El usuario de Tomcat necesita escritura en ese
directorio, que debe incluirse en los respaldos. En instalaciones con varias
instancias, usar un directorio persistente compartido. La consulta de la imagen
requiere sesión y verificación de acceso; cada cuenta sólo accede a su propia
firma. No se modifica el esquema de base de datos.
