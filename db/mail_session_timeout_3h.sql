alter table app_usuarios alter column usuario_sesion_timeout set default 10800000;

update app_usuarios
   set usuario_sesion_timeout = 10800000
 where usuario_sesion_timeout is null
    or usuario_sesion_timeout = 3600000;
