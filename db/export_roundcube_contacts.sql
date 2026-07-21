copy (
    select c.contact_id, lower(split_part(u.username, '@', 1)) usuario_id,
           coalesce(nullif(btrim(c.name), ''), nullif(btrim(c.email), ''), 'Sin nombre') nombre,
           lower(btrim(c.email)) email, c.changed, coalesce(c.del, 0) eliminado
      from rccontacts c
      join rcusers u using (user_id)
     where btrim(coalesce(c.email, '')) <> ''
) to stdout with (format binary);
