# Acceso local: sbasurto — 2026-09-18

Se habilitó el usuario en db_ermmaster, db_storemaster, db_wmsmaster,
db_rhmaster, db_helpdeskmaster y db_gatormail con todos los grupos de admin
de cada base. Se conservaron contraseñas y permisos existentes; los usuarios
nuevos recibieron credenciales aleatorias independientes. Gator Mail confirmó
el acceso administrativo por su función mail_fn_admin_access.

Se completó la vinculación OIDC en gator-hd-soft, gatorrhmaster, gatorsmaster,
gatorwmaster y gatorwrfmaster y se recargaron sus WAR sin cambiar sus bytes.
Las otras cuatro aplicaciones (gatoremaster, cpsmexico, gator-ecom y
softgator-web) ya tenían la vinculación. Todo se publicó con gator-deployer.

Se copiaron el correo y teléfono de la identidad sbasurto de Artemisa a las
seis bases locales. Los valores privados y respaldos quedan fuera de Git.
No se modificó la preferencia de segundo factor ni se enviaron mensajes.

El error «No fue posible contactar el servicio de verificación» aparecía al
guardar el contacto: g-sec/mail-sms.jsp llamaba a mail_fn_sync_gatormail_user,
ausente en db_gatormail. La implementación empresarial dependía de tablas
que no existen en la base autónoma. db/mail_login_contact.sql implementa ese
contrato sobre sus tablas propias, para usuarios activos existentes; conserva
asociaciones previas y rechaza nuevos correos pertenecientes a otro usuario.
No instalar esta variante en bases empresariales.

Helpdesk tenía una versión antigua de app_fn_get_session_data que omitía los
contactos. Se probó y publicó la versión vigente de internal/app/postgresql/sec,
que también limita las cuentas a los grupos del usuario.

Validación: migración de contactos aplicada dos veces con rollback antes de
publicar; prueba SQL de sincronización (datos inválidos, idempotencia, contraseña,
usuario desactivado/inexistente, correo ajeno y Global Safe List); HTTP autenticado
sync y send/preflight con código 0, sin enviar códigos; carga de cuentas y menús
ERM, Store, WMS, RF y RH; contrato de sesión y contactos para ERM/CPS, ecommerce,
portal y Helpdesk; nueve vinculaciones OIDC verificadas.

Las sesiones ya abiertas conservan los contactos anteriores: cerrar sesión e
iniciar nuevamente. No se automatizó el login interactivo del usuario.

Evidencia privada: ~/.local/state/gator/releases/2026-09-18-local-sbasurto/.
Alcance de esta corrección: local; no requiere cambios en Hera ni producción.
