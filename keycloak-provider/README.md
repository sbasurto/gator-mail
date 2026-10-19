# Federación de usuarios Gator

`gator-users` consulta las tablas existentes y valida sus hashes SHA-512. Sin
opciones conserva `GATOR_IDP_JDBC_URL`, `GATOR_IDP_JDBC_USER` y
`GATOR_IDP_JDBC_PASSWORD` y la búsqueda histórica por usuario/correo.

La configuración histórica de realms separados usa el componente con
`connectionEnvironmentPrefix=GATOR_LOMALINDA_ERM` (o STORE/WMS). Se conserva durante
la transición; no es el modelo para nuevas instalaciones. El diseño aprobado
usa un realm `gator`, concesiones por instalación y vínculos locales exactos.
Las tres variables
con ese prefijo deben existir en el entorno privado de Keycloak. Si faltan,
produce error sin recurrir a otra base. No se copian usuarios ni hashes.

Los nombres sensibles a mayúsculas requieren `literalUsernames=true` y el
formulario `gator-exact-username-password` en su flujo de navegador. Este resuelve
el identificador federado exacto y conserva los controles nativos de contraseña,
estado activo, intentos fallidos y reautenticación. Debe haber una sola fuente
Gator literal por realm. Deshabilitar login por correo, registro, recuperación y
direct grants: esos otros flujos no preservan nombres sensibles a mayúsculas.
El realm existente conserva su componente y flujo.

En el acceso central por cliente (`gator.exactIdentity=true`, sin activar
`literalUsernames` global), login y recuperación comparten la resolución:
primero el nombre exacto; si no existe y el realm permite login por correo,
el correo único asociado. Las direcciones ambiguas se rechazan. Se conserva
el subject de la cuenta y se verifican su contraseña y estado activo.

Loma Linda conserva sus bases de ERM, Store y WMS; RF comparte la identidad de WMS.
Las asociaciones de subject a usuario local se configuran explícitamente en cada
aplicación Spring. Los permisos siguen en su base original. Las altas posteriores
requieren agregar su asociación a las aplicaciones.

Verificación desde `gator-mail`: `./gradlew :keycloak-provider:check :keycloak-provider:jar`.
Antes de activar una instalación, comprobar nombres con mayúsculas y con `@`,
contraseña incorrecta, reautenticación y callbacks de cada dominio. Respaldar el
JAR y la configuración de Keycloak antes de reconstruirlo y reiniciarlo.

## Transición a credenciales nativas de Keycloak

El proveedor `gator-legacy-sha512` verifica exclusivamente credenciales históricas
importadas de forma explícita: algoritmo `gator-legacy-sha512`, salt como bytes
UTF-8 del salt original, hash hexadecimal SHA-512 e iteraciones originales
(1 a 1 000 000). No genera contraseñas nuevas y no debe seleccionarse como
algoritmo predeterminado del realm. Los registros fuera de ese contrato quedan
pendientes de migrar, conservando el acceso anterior.

El algoritmo vigente del realm debe seguir siendo el nativo de Keycloak. Su
PasswordCredentialProvider 26.7.0 valida el hash histórico y programa su
actualización al algoritmo vigente después de un login correcto. La suite
verifica el hash con datos sintéticos; el rehash y las sesiones deben verificarse
además en una instancia aislada de Keycloak antes de instalar el JAR compartido.
El 2026-10-05 se verificó en Keycloak 26.7.0 aislado con H2 y un usuario sintético:
importación histórica, rechazo de contraseña incorrecta, rehash a Argon2 tras
login correcto, misma contraseña después del rehash, refresh y rechazo del
refresh tras logout administrativo. No acredita la migración de usuarios reales.
La prueba reproducible está en `src/test/python/verify_legacy_hash_isolated.py`;
usa exclusivamente `http://127.0.0.1:8181` y elimina sólo el realm temporal que
crea. Recibe como argumento un archivo privado JSON de credenciales del
administrador **de esa instancia aislada**, nunca del servicio compartido.

El adaptador de usuarios históricos conserva `f:<provider>:<usuario>` y la
validación de credenciales existente. Sólo las concesiones de grupos/roles se
guardan en el almacenamiento federado nativo de Keycloak. No cambia nombres,
correos ni estados SQL, ni añade usuarios duplicados. La suite verifica que las
concesiones sobreviven a recrear el adaptador y se pueden revocar. También pasó
`src/test/python/verify_federated_groups_isolated.py` en Keycloak 26.7.0 aislado:
grupo persistido, rol efectivo en el token y revocación al retirar la pertenencia,
con subject conservado. Su fuente SQL fue el master local, utilizado sólo para
lectura. La aceptación Local/Hera de aplicaciones y la revisión del despliegue
compartido siguen siendo obligatorias.

No sobrescribir credenciales o crear usuarios homónimos de identidades ya
federadas. El inventario debe distinguir usuarios nativos, importados y externos;
la transición de estos últimos necesita equivalencias y continuidad de sesiones
antes de cambiar su subject. Si una persona tiene contraseñas distintas, no se
elige una fuente silenciosamente ni se cambia el acceso a todas las bases.

## Administración central de identidad (implementada, deshabilitada por defecto)

La extensión comparte la fuente JDBC existente y añade consulta paginada y
escritura de nombre, correo y estado para federados, el recurso autenticado
`/realms/gator/gator-identity-admin` y la proyección duradera de eventos nativos.
No añade servicios, WARs ni dependencias. Los realms históricos siguen con el
adaptador de sólo lectura y sus credenciales/concesiones existentes.

La activación exige **todas** estas configuraciones de operador, después de
instalar y comprobar el contrato SQL canónico:

- Realm `gator`: `gator.identityAdmin.enabled=true` y
  `gator.identityAdmin.issuer` con su issuer HTTPS exacto.
- Fuente `gator-users`: `identityAdministrationEnabled=true` y el prefijo
  `GATOR_IDP`; otro prefijo nunca admite escritura ni consulta administrativa.
- Eventos administrativos persistidos habilitados, **representaciones
  deshabilitadas**, listener `gator-identity-admin`. Conservar los eventos hasta
  confirmar su proyección; no purgarlos durante fallos o una recuperación.
- Cliente: atributo privado de operador `gator.identityAdmin.destinationDbId`
  con el identificador numérico `broker_db` permitido y rol `identity-admin`.
  El token y los roles efectivos actuales deben conceder ese rol en el mismo
  cliente `azp`; SQL comprueba además el actor por issuer/subject y su
  `sys_admon` y alcance local (Mail usa su autorización canónica por binding
  exacto y `mail_fn_identity_authorize`). No habilitarlo para proveedores por defecto.
- JDBC de la fuente usa el rol restringido `gator_idp`. Sólo ese rol ejecuta
  `app_fn_identity_admin(text)`; `PUBLIC` no recibe privilegios. El texto
  `usuario: gator_idp` de una petición no constituye una autorización.

POST JSON: acciones `list`, `get`, `create`, `update`, `disable`, `enable`,
`reset`, `status`, `grant`. Las identidades existentes se seleccionan sólo mediante
`targetSubject`; no se renombran subjects/usuarios. `update` acepta nombre,
correo y estado. `reset` acepta contraseña y `temporary` opcional (true por
omisión), exclusivamente a petición expresa; no persiste la contraseña en
cola ni eventos. `create` exige usuario nuevo, destino local, cuenta y bodega
explícitos; el cliente privado `gator.identityAdmin.system=mail` exige sólo
`localUsuario` y rechaza cuenta/bodega ficticias. Crea una identidad nativa
deshabilitada, sin roles/grupos por
omisión. La habilitación y las concesiones de instalación siguen siendo pasos
explícitos posteriores a la proyección confirmada; los permisos de negocio no
se conceden como efecto de actualizar una identidad.

CREATE admite un `requestId` UUID opcional. El cliente debe conservarlo antes
del primer envío y reutilizarlo ante una respuesta perdida. El servidor deriva
un subject nativo inmutable de issuer/cliente/UUID y registra actor, destino,
huella de parámetros y operación en atributos protegidos. Reintentar exige
autorización vigente y la misma procedencia SQL; devuelve el mismo subject y
operación, sin volver a crear, actualizar contraseñas, habilitar o conceder
roles. Un actor/destino diferente recibe 403; reutilizar la clave con otros
parámetros recibe 409. No se buscan identidades por nombre para recuperarlas.

`get` devuelve `{user, status:"complete"}`. `list` acepta `first` y `max` (hasta
100); los desplazamientos cuentan usuarios autorizados después de aplicar el
alcance SQL. `authorize/list` debe devolver `authorizedSubjects`, el conjunto
exacto permitido; no se hacen consultas SQL individuales para filtrar cada
usuario. Una respuesta de alcance ausente o malformada falla cerrada. La
exploración se limita a 10.000 usuarios de realm y falla
explícitamente si supera el límite. Una operación desconocida devuelve 404;
un evento durable todavía no proyectado puede devolver pending, sólo para el
subject y destino exactos retenidos. Un estado SQL failed permanece failed.

`grant` acepta solamente `targetSubject`. El atributo privado del cliente
`gator.identityAdmin.accessGroupId` identifica un grupo existente que concede
únicamente el rol no compuesto `access` de ese cliente. Se rechazan grupos o
ancestros con otros roles; no se sustituyen grupos existentes. Para nuevas
identidades creadas por el recurso, exige primero la operación CREATE completa
registrada por el servidor. Las identidades migradas existentes siguen sujetas
al alcance SQL exacto. Devuelve `granted: true` sólo tras verificar el rol efectivo.
No hay concesiones automáticas al crear, habilitar o editar.

La fuente central excluye de lookup/byID/consulta/conteo cualquier fila cuya
vinculación issuer/subject pertenezca a otra identidad que `f:componente:usuario`.
Esto evita inventar una segunda identidad federada para una proyección Mail nativa;
las fuentes legacy mantienen su comportamiento previo.
Tras validar la tabla de vínculos, conservar en el componente central
`nativeIdentityBindings=["true"]` permanentemente, incluso si se desactiva
`identityAdministrationEnabled` o el gate del realm. Este filtro no habilita
mutaciones administrativas. La recuperación CREATE exige política de atributos
no gestionados DISABLED/ADMIN_VIEW/ADMIN_EDIT o los cinco marcadores declarados
con permisos de lectura y edición únicamente para administradores.

Las mutaciones devuelven `operationId` y `status: pending`. No afirman éxito de
réplica antes de confirmar los destinos. `status` devuelve pending/complete/
failed; consulta exige autorización del mismo target y destino. La interfaz Gator no
expone credenciales administrativas, conexiones ni tokens del servidor.

El listener vuelve a leer el estado vigente después del commit de Keycloak.
La tarea periódica repite los eventos USER retenidos, con identificadores
idempotentes, y drena los destinos SQL pendientes. No usa un corte por fecha:
una transacción larga puede confirmar un evento con fecha anterior. SQL conserva
la operación original y sus versiones y no reabre destinos ya aplicados; `complete`
resuelve la conexión permitida, aplica y confirma el destino. El proveedor nunca
acepta una afirmación de éxito del cliente ni conoce contraseñas remotas de las
bases de negocio. Una eliminación física desde una consola con privilegios
globales bloquea las proyecciones locales sin borrar su historial; la acción de
baja ofrecida por el recurso es deshabilitar.

Pruebas sintéticas:

```bash
./gradlew :keycloak-provider:check :keycloak-provider:jar \
  :keycloak-provider:identityAdminFixtureJar --offline --no-watch-fs
python3 keycloak-provider/src/test/python/verify_identity_admin_isolated.py \
  /ruta/local/keycloak-26.7.0.tar.gz
```

La segunda prueba posee una instancia desechable en `127.0.0.1:18881`, genera
identidades y credenciales sintéticas y utiliza un transporte JDBC de pruebas
separado que **no se incluye en el JAR publicado**. Comprueba SPI/REST/eventos
reales de Keycloak y recuperación tras reinicio. No acredita por sí sola el SQL
canónico, destinos reales, los 129 usuarios nativos ni los 465 federados:
la integración SQL Local/Hera y la comparación privada de inventarios siguen
siendo verificaciones necesarias antes de activar.

Registro de recursos según la [guía oficial de extensiones Keycloak](https://www.keycloak.org/docs/latest/server_development/#_extensions):
SPI RealmResourceProvider, `META-INF/beans.xml` y anotación JAX-RS Provider.

Aceptación aislada verificada el 13 de octubre de 2026: edición y consulta
nativa/federada, reset explícito en identidades sintéticas, conservación de
credenciales y acciones requeridas de las demás, revocación efectiva del
administrador aun con JWT vigente, bloqueo que elimina sesiones y repetición
durable de un cambio confirmado antes de una caída del contrato SQL/reinicio.
Los tests de transporte no sustituyen las pruebas canónicas SQL Local/Hera.
