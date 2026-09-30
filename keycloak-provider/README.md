# Federación de usuarios Gator

`gator-users` consulta las tablas existentes y valida sus hashes SHA-512. Sin
opciones conserva `GATOR_IDP_JDBC_URL`, `GATOR_IDP_JDBC_USER` y
`GATOR_IDP_JDBC_PASSWORD` y la búsqueda histórica por usuario/correo.

Para otra base, usar un realm independiente y configurar el componente con
`connectionEnvironmentPrefix=GATOR_LOMALINDA_ERM` (o STORE/WMS). Las tres variables
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
