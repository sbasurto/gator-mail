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
