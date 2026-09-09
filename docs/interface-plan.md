# Interfaz de Gator Mail: azul y verde

Diseño aprobado: gator-gui-essay/web/gator-mail-propuesta.html y gator-mail-propuesta-azul.html. Ambos prototipos se conservan.

1. Añadir y probar elección de tema por cuenta y navegador, con azul predeterminado, valores permitidos y recuperación cuando el almacenamiento está bloqueado.
2. Integrar la navegación del diseño en mail.json, preservar permisos, formularios, CSRF y URLs existentes. Usar un CSS compartido con dos paletas. Lectura y listado comparten página en escritorio; móvil muestra el mensaje y acceso de regreso.
3. Agrupar los formularios de configuración y contactos; reutilizar el selector de contactos, editor, calendario y confirmaciones existentes.
4. Ejecutar self-checks Java y navegador (ambos temas, persistencia, navegación, móvil, permisos y mensaje HTML aislado).
5. Compilar y desplegar sólo gator-mail mediante gator-deployer wars --server=localhost; verificar respuesta HTTP y abrir Firefox.

No hay cambios de esquema ni de los servicios IMAP/SMTP. La preferencia visual se conserva en localStorage separada por cuenta, sin sincronización entre dispositivos. Los estados de éxito, advertencia y error conservan colores semánticos.

## Corrección del resumen aprobado

Se reproducen las cuatro tarjetas, las tablas de próximos eventos y mensajes pendientes, y el ranking con selector de periodo. La comparación de navegador usa el HTML aprobado como referencia y verifica posición y tamaño de los siete paneles con los mismos datos de prueba. Los iconos SVG del diseño se incluyen como máscaras CSS sin dependencias nuevas. El menú móvil dispone de cierre accesible.

Validación: ./gradlew check war -PuiFixtureDir=/tmp/gator-mail-ui-fixtures y comparación de navegador de 13 pantallas en dos temas y dos tamaños. Las fechas y mensajes del resumen se obtienen de los servicios y caché existentes; los datos ficticios sólo están en las pruebas.
