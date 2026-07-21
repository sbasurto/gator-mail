# Gator Mail

Cliente web IMAP ligero integrado con las cuentas, grupos y aplicaciones de
Gator. El primer alcance permite iniciar sesión, verificar el acceso mediante
una clave enviada exclusivamente por SMS, consultar las carpetas IMAP y leer
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
- PostgreSQL con el esquema Gator y las funciones de acceso y desafío instaladas.
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

El cliente público `gator-mail` debe habilitar Authorization Code con PKCE S256
y registrar exactamente los URI de retorno usados por cada entorno.
El tema claro de entrada y salida se encuentra en `keycloak-theme/gator-mail`.

Las carpetas IMAP se guardan en la tabla jerárquica `mail_carpetas`; el script
idempotente para crearla está en `db/mail_carpetas.sql`.
El directorio autónomo y compatible con Gator E se instala con
`db/mail_contacts.sql`; no requiere tablas externas ni copia hashes de usuario.
La administración de usuarios y contactos se instala con `db/mail_admin.sql`;
las cuentas asociadas a `sbasurto@soft-gator.com` y la cuenta local `admin`
quedan autorizadas inicialmente y pueden ampliarse desde la tabla
`mail_administradores`.
En instalaciones Gator, `db/sync_contacts_from_gator.sql` registra la base en
`broker_db` y replica altas, cambios y bajas hacia Gator Mail. Un fallo del
destino no bloquea Gator E: queda registrado en `mail_contact_sync_queue`.

## Compilar y probar

Coloque `gator-mail` y `gator-lib` como directorios hermanos y ejecute:

```bash
./gradlew clean check war
```

El artefacto queda en `dist/gator-mail.war`.

## Despliegue Gator

El contexto esperado es `/gator-mail`. La aplicación requiere acceso a la
configuración de base `indexMasterErm`, una entrada `broker_db` con
`db_use = 'mail'` y la asignación de esa aplicación a los grupos autorizados.
La función de desafío debe aceptar `smsOnly = true` y devolver únicamente el
hash de la clave temporal. También acepta `application` y `userHint` opcionales
para identificar el origen y la cuenta en el SMS; la definición compatible se
encuentra en `db/app_fn_send_login_challenge.sql`. No requiere Shiro ni
contraseñas de correo.

La sesión HTTP se conserva durante reinicios controlados de Gator Mail para no
repetir el segundo factor mientras la misma sesión continúe activa. Cerrar
sesión o dejarla expirar elimina esa verificación.

El vínculo **Contraseña** abre la consola de cuenta de Keycloak. Los cambios
usan el proveedor Gator existente, que sincroniza `app_usuarios` mediante
`app_fn_admon_tablas_all`.

## Aprovisionamiento de buzones

- Si el correo ya existe en `softmail_users`, se reutilizan su identidad,
  directorio y mensajes; Gator Mail no cambia su contraseña.
- Si existe como usuario Unix heredado, debe registrarse como buzón virtual
  apuntando al Maildir actual antes de retirar la compatibilidad PAM.
- Si no existe, se crea un buzón virtual en `softmail_users`; no se crea una
  cuenta del sistema operativo. La operación debe ser idempotente y validar el
  dominio, UID/GID, directorio y cuota.
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
