<#import "template.ftl" as layout>
<@layout.registrationLayout; section>
    <#if section = "header">
        Cerrar sesión
    <#elseif section = "form">
        <div id="kc-logout-confirm" class="content-area gm-logout">
            <div class="gm-logout-icon" aria-hidden="true">→</div>
            <p class="instruction">¿Quieres terminar tu sesión de Gator Mail?</p>
            <p class="gm-logout-help">Tu correo permanecerá protegido y podrás ingresar nuevamente cuando lo necesites.</p>

            <form class="form-actions" action="${url.logoutConfirmAction}" onsubmit="confirmLogout.disabled = true; return true;" method="POST">
                <input type="hidden" name="session_code" value="${logoutConfirm.code}">
                <div class="${properties.kcFormGroupClass!}">
                    <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                        <input tabindex="4"
                               class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                               name="confirmLogout" id="kc-logout" type="submit" value="Cerrar sesión"/>
                    </div>
                </div>
            </form>

            <#if (client.baseUrl)?has_content>
                <div id="kc-info-message">
                    <a href="${client.baseUrl}">Cancelar y volver al correo</a>
                </div>
            <#else>
                <div id="kc-info-message">
                    <a href="${url.loginUrl}">Cancelar y volver al acceso</a>
                </div>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
