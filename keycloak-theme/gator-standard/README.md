# Login estándar Gator

Tema basado en `keycloak.v2` de Keycloak 26.7.0. `login.ftl` conserva el
formulario original y cambia su encabezado; CSS y JavaScript adaptan la
presentación de `gator-lib-web-resources/index_tpl.jsp`. Las demás pantallas
heredan las plantillas de Keycloak. La activación se realiza por cliente, conservando las políticas existentes.

## Nombre y logo por instalación

Preparar los atributos públicos desde el archivo index existente:

```bash
python3 prepare_branding.py --index /ruta/indexInstalacion \
  --images /ruta/imagenes --client gator-erm-local --output /tmp/branding-erm
```

El resultado incluye `client-branding.json` y, cuando existe `clientLogo`,
una copia del logo bajo `login/resources/img/clients/`. Combinar esos recursos
con este tema antes de empaquetarlo. Conservar sus nombres y subdirectorios.
El script rechaza rutas que escapan del directorio de imágenes. No lee ni
exporta credenciales del index y no llama a Keycloak.

Al publicar, **fusionar** los atributos del resultado con los atributos
existentes del cliente; no reemplazar el mapa completo, que contiene otras
políticas. `login_theme` selecciona este tema por cliente,
`gator.displayName` proviene de `nameToDisplay` y `gator.clientLogo` de
`clientLogo`. Cada cliente debe tener la marca de su instalación.
No cambiar globalmente el tema del realm para probar un solo cliente.

## Recuperación de contraseña: propuesta, no aplicada

Consulta de solo lectura del realm `gator`, 2026-09-19:

- `resetPasswordAllowed=false`.
- Flujo asignado: `reset credentials` (no se han auditado sus ejecuciones).
- Sin servidor SMTP ni remitente configurados.

`recovery-realm.patch.json` contiene únicamente el cambio propuesto para
habilitar el enlace nativo. **No aplicarlo antes de configurar y verificar
SMTP.** Faltan servidor, puerto, remitente autorizado, transporte TLS y, si
corresponde, autenticación. Guardar la contraseña SMTP exclusivamente en el
canal privado de configuración; nunca en este repositorio.

El proveedor `GatorUserStorageProvider` implementa `CredentialInputUpdater`:
escribe sobre la cuenta existente y llama a `app_fn_admon_tablas_all` para
sincronizar la contraseña. No crea otra identidad. El cambio puede afectar
las aplicaciones que comparten esa cuenta. Esta comprobación es de código,
no una prueba de recuperación real.

Antes de activar en Artemisa se requiere solicitud explícita. Para la prueba
posterior: revisar las ejecuciones del flujo, comprobar el correo de una
cuenta de prueba, autorizar el envío y cambio de contraseña, verificar el
enlace de un solo uso y su caducidad, y comprobar acceso con la nueva clave.
Mantener mensajes que no revelen si una cuenta existe. No cambiar grupos,
asociaciones ni políticas de contraseña. Reversión: deshabilitar
`resetPasswordAllowed`; eso no revierte contraseñas ya cambiadas.

## Verificación local

```bash
python3 test_prepare_branding.py
```

La vista previa estática no valida el flujo OIDC ni el envío de correo. La recuperación requiere una prueba integrada posterior a configurar SMTP.

## Publicación

Copiar `login/` y los logos preparados al directorio `themes/gator-standard/`
de la instalación de Keycloak. Guardar previamente los atributos completos
por cliente en un respaldo privado y fusionar solo los tres atributos de marca.
Para la API, leer la representación completa del cliente, fusionar los atributos
y devolver esa representación conservando `webOrigins` (incluido `[]`),
callbacks y políticas. Un PUT que contenga solo `attributes` puede hacer que
Keycloak calcule nuevos orígenes web. Verificar las listas sin depender de su orden.
Comprobar una autorización OIDC nueva (HTTP 200, CSS, nombre y logo correctos)
antes de extender la activación al resto. No requiere recompilar los WAR.
Las aplicaciones locales usan el mismo Keycloak de Artemisa.

El cliente compartido `gator-mail` conserva el nombre Gator Mail para ambos
entornos. Un cliente compartido entre instalaciones debe tener una marca común;
si necesita marcas diferentes, debe separarse por instalación antes de activar
este tema. No deducir ni modificar permisos a partir de la marca.

Reversión: restaurar los atributos respaldados del cliente. El tema anterior
`gator-mail` se conserva. No cambiar el tema del realm ni reiniciar Tomcat.

El favicon se declara mediante `favicons.standard` en `theme.properties`, con
un nombre que incluye su hash. Al cambiarlo, actualizar también la ruta: tanto
Keycloak (caché gzip) como el navegador pueden conservar el icono heredado bajo
`img/favicon.ico`. Verificar el HTML servido y comparar los bytes recibidos con
`Accept-Encoding: identity` y `gzip` (descomprimiendo este último).
Referencia: https://www.keycloak.org/ui-customization/themes#_advanced_css_script_and_favicon_imports
