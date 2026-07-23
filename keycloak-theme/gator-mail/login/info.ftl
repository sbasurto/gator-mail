<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        Sesión finalizada
    <#elseif section = "form">
        <div id="kc-info-message" class="gm-logout gm-logout-complete">
            <div class="gm-logout-icon" aria-hidden="true">✓</div>
            <p class="instruction">Cerraste tu sesión de Gator Mail correctamente.</p>
            <p class="gm-logout-help">No quedó ninguna sesión activa en este navegador.</p>
            <a class="gm-logout-login" href="/gator-mail/oauth/login">Ingresar nuevamente</a>
        </div>
    </#if>
</@layout.registrationLayout>
