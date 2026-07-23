<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${message.summary}
    <#elseif section = "form">
        <div id="kc-info-message">
            <p>${message.summary}</p>
            <a href="/gator-mail/oauth/login">Ingresar nuevamente</a>
        </div>
    </#if>
</@layout.registrationLayout>
