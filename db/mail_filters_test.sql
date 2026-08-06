begin;

insert into mail_filtro_reglas(mailbox, nombre, prioridad, habilitada, campo, operador, valor, carpeta_destino)
values ('filter-test@example.com', 'Prueba', 100, true, 'SUBJECT', 'CONTAINS', 'factura', 'Archivo');
insert into mail_filtro_estado(mailbox, uidvalidity, ultimo_uid, estado)
values ('filter-test@example.com', 123, 42, 'ACTIVO');
insert into mail_filtro_auditoria(mailbox, uidvalidity, uid, estado)
values ('filter-test@example.com', 123, 41, 'SIN_COINCIDENCIA');

do $$
declare resultado json := mail_fn_filtros_aplicar('filter-test@example.com')::json;
begin
    assert resultado ->> 'codigo' = '0', 'No se programó la revisión';
    assert (select ultimo_uid = 0 and estado = 'REPROCESAR' from mail_filtro_estado
             where mailbox = 'filter-test@example.com'), 'No se reinició el checkpoint';
    assert not exists (select 1 from mail_filtro_auditoria
                        where mailbox = 'filter-test@example.com'), 'No se liberó la auditoría previa';
end;
$$;

rollback;
