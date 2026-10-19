<#import "template.ftl" as layout>
<#global title = msg((statusCode?? && statusCode == 405)?then("gatorMethodNotAllowedTitle", "errorTitle")) + " | Soft Gator">
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <span class="gator-login-brands">
            <img src="${url.resourcesPath}/img/soft-gator.png" alt="Soft Gator">
        </span>
        <#if statusCode?? && statusCode == 405>
            ${msg("gatorMethodNotAllowedTitle")}
        <#else>
            ${kcSanitize(msg("errorTitle"))?no_esc}
        </#if>
    <#elseif section = "form">
        <div id="kc-error-message">
            <#if statusCode?? && statusCode == 405>
                <p class="instruction">${msg("gatorMethodNotAllowedMessage")}</p>
            <#else>
                <p class="instruction">${kcSanitize(message.summary)?no_esc}</p>
            </#if>
            <#if traceId??>
                <p class="instruction" id="traceId">${msg("traceIdSupportMessage", traceId)}</p>
            </#if>
            <#if !skipLink?? && client?? && client.baseUrl?has_content>
                <p><a id="backToApplication" href="${client.baseUrl}">${msg("backToApplication")}</a></p>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
