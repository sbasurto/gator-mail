create or replace function public.app_fn_send_login_challenge(v_json text)
returns text
language plpgsql
as $function$
declare
        i_api_accion   text;
        i_api_sms      json;
        i_application  text := left(btrim(utils_fn_coalesce(utils_fn_get_json(v_json::json, 'application'), 'Soft Gator')), 32);
        i_code         text;
        i_email        text;
        i_email_sent   boolean := false;
        i_expires_at   bigint;
        i_hash         text;
        i_hint_source  text;
        i_phone        text;
        i_phone_sent   boolean := false;
        i_sms_only     boolean := utils_fn_coalesce(utils_fn_get_json(v_json::json, 'smsOnly'), 'false')::boolean;
        i_user_hint    text := left(btrim(utils_fn_coalesce(utils_fn_get_json(v_json::json, 'userHint'), '')), 32);
        i_usuario      text := btrim(utils_fn_coalesce(utils_fn_get_json(v_json::json, 'usuario'), ''));
begin
        if i_usuario = '' or not exists (select 1 from app_usuarios where usuario_id = i_usuario) then
                return json_build_object('codigo', '-1', 'mensaje', 'Usuario no válido')::text;
        end if;

        if i_application = '' then i_application := 'Soft Gator'; end if;
        if i_user_hint = '' then
                i_hint_source := case when position('@' in i_usuario) > 1 then split_part(i_usuario, '@', 1) else i_usuario end;
                i_user_hint := (case
                        when length(i_hint_source) <= 2 then repeat('*', length(i_hint_source))
                        when length(i_hint_source) <= 4 then left(i_hint_source, 1) || '***' || right(i_hint_source, 1)
                        else left(i_hint_source, 2) || '***' || right(i_hint_source, 2)
                end);
        end if;

        select usuario_email_email from app_usuario_email
         where usuario_id = i_usuario and btrim(usuario_email_email) <> ''
         order by usuario_email_por_defecto desc nulls last, rowid limit 1 into i_email;
        select case when usuario_telefono_numero like '+%' then usuario_telefono_numero
                    else concat(utils_fn_coalesce(usuario_telefono_prefijo, ''), usuario_telefono_numero) end
          from app_usuario_telefono where usuario_id = i_usuario and btrim(usuario_telefono_numero) <> ''
         order by rowid limit 1 into i_phone;

        if (i_sms_only and utils_fn_is_empty(i_phone))
           or (not i_sms_only and utils_fn_is_empty(i_email) and utils_fn_is_empty(i_phone)) then
                return json_build_object('codigo', '-1', 'mensaje', 'No hay un medio de contacto disponible')::text;
        end if;

        i_code := app_fn_create_login_keyword();
        i_hash := encode(digest(i_code, 'sha256'), 'hex');
        i_expires_at := (extract(epoch from clock_timestamp() + interval '5 minutes') * 1000)::bigint;

        if not i_sms_only and not utils_fn_is_empty(i_email) then
                begin
                        perform utils_fn_send_email(i_email, 'Clave de acceso ' || i_application,
                                concat('<h4>Clave temporal para ', i_application, ' (', i_user_hint,
                                       ')</h4><h1>', i_code, '</h1><p>Vence en cinco minutos.</p>'), '0', null);
                        i_email_sent := true;
                exception when others then
                        i_email_sent := false;
                end;
        end if;

        if not utils_fn_is_empty(i_phone) then
                begin
                        select api2_data, api2_swiss_action from app_api2
                         where api2_tipo = 'sms' order by api2_prioridad desc, api2_preferida desc limit 1
                         into i_api_sms, i_api_accion;
                        if i_api_sms is not null and not utils_fn_is_empty(i_api_accion) then
                                i_api_sms := jsonb_set(i_api_sms::jsonb, '{destPhoneNumber}', to_jsonb(i_phone), true);
                                i_api_sms := jsonb_set(i_api_sms::jsonb, '{message}',
                                        to_jsonb(concat('[', i_application, '] Clave para ', i_user_hint, ': ',
                                                        i_code, '. Vence en 5 minutos.')), true);
                                perform utils_fn_swiss_knife_call(i_api_accion, i_api_sms::text);
                                i_phone_sent := true;
                        end if;
                exception when others then
                        i_phone_sent := false;
                end;
        end if;

        if not i_email_sent and not i_phone_sent then
                return json_build_object('codigo', '-1', 'mensaje', 'No fue posible enviar la clave temporal')::text;
        end if;

        return json_build_object('codigo', '0', 'challengeHash', i_hash, 'expiresAt', i_expires_at,
                                 'emailSent', i_email_sent, 'phoneSent', i_phone_sent)::text;
end;
$function$;
