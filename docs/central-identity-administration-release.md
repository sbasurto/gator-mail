# Publicación de administración central

La distribución no activa la función. Mail conserva su modo anterior mientras
`gator.identityAdmin.enabled` no sea `true` en el contexto confiable. El proveedor
también requiere los gates de realm, componente y cliente documentados en
`keycloak-provider/README.md`.

1. Generar y revisar el JAR y WAR con `./gradlew check war
   :keycloak-provider:jar`. Registrar sus SHA256 y conservar los artefactos
   anteriores y configuraciones privadas. El JAR de transporte sintético de
   pruebas nunca se publica.
2. Probar Local y Hera con datos sintéticos: roles SQL reales, transporte
   restringido, autoría exacta, fallos y recuperación, CREATE cerrado, concesión
   explícita, habilitación y contraseña sólo por RESET. Comprobar también
   paginación con denegaciones anteriores al desplazamiento solicitado y el
   límite de cinco segundos del cliente compartido.
3. Publicar el contrato SQL canónico por `gator-deployer`, usando el esquema
   obligatorio sin Liquibase. Publicar todos los helpers de Mail, incluida
   `mail_fn_identity_login_policy`, antes de habilitar el WAR. Mantener la
   configuración de instalación desactivada durante la preparación. El rol
   del proveedor sólo ejecuta la API restringida; nunca recibe credenciales
   generales del broker ni permisos sobre bases ajenas.
4. Publicar el proveedor mediante `identity-provider-rolling`, con su
   comprobación de compatibilidad de Keycloak. Conservar el mismo componente,
   subjects, credenciales y perfiles existentes. No reiniciar Tomcat para
   reemplazar una aplicación si existe publicación paralela del WAR.
5. Preparar con `identity-config` el destino permitido, actores confirmados y
   grupo dedicado que concede únicamente el rol `access` de la aplicación.
   Para Mail puede crearse un grupo vacío; no asignar usuarios existentes como
   efecto de la activación. Conservar los listeners y habilitar eventos sin
   representaciones de petición. El mapper UserInfo estándar debe incluir
   `resource_access.gator-mail.roles`.
6. Coordinar la activación SQL, los gates del proveedor y el parámetro de
   contexto Mail; verificar inmediatamente el acceso existente y una identidad
   sintética nueva sin grant, con grant y posteriormente habilitada. No afirmar
   éxito hasta confirmar proyección y estado del destino. No usar cuentas reales
   para pruebas de restablecimiento de contraseña.

Ante fallos, detener nuevas acciones administrativas y conservar eventos y
operaciones pendientes. Desactivar el gate del WAR no elimina los guards SQL
ni devuelve permiso para modificar identidades vinculadas por rutas antiguas.
No borrar operaciones para forzar un reintento ni sustituir vínculos por nombres.
La retirada de Mail elimina sólo el buzón físico y sus metadatos, tras bloquear
el acceso; no borra identidades, grupos o historial.

Mail conserva el UUID `requestId` antes del CREATE inicial. Ante una respuesta
perdida, comprobar estado reenvía esa misma clave y parámetros; el proveedor
resuelve su marcador exacto y devuelve la misma identidad y operación.
No preparar el buzón sin confirmar la proyección SQL. Los reintentos conservan
el mismo subject y no vuelven a enviar contraseñas.

Después de validar el esquema, conservar permanentemente el atributo del
componente `nativeIdentityBindings=true`, incluso al desactivar la API. Así se
evita publicar una segunda identidad federada para una fila SQL ya vinculada a
un UUID nativo. La política de acceso de las altas Mail proyectadas también
permanece activa al apagar su interfaz; requiere el rol `access` de Gator Mail
y no añade requisitos a los usuarios anteriores.

Las altas usan nombre completo separado en el último espacio para cumplir el
perfil central sin modificar su política. Si el perfil exige apellido, una
alta de una sola palabra se rechaza antes de crear la identidad. Un CREATE
rechazado definitivamente con HTTP400 permite corregir los datos; una respuesta
perdida o un conflicto conserva la clave original para evitar duplicados.
