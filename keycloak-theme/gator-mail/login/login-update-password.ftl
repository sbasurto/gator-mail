<!doctype html>
<html lang="es">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Crea una contraseña nueva</title>
    <link rel="stylesheet" href="${url.resourcesPath}/css/gator-mail-v5.css">
</head>
<body class="gm-page">
<main class="gm-card">
    <div class="gm-logo" aria-hidden="true"></div>
    <p class="gm-product">Gator Mail</p>
    <h1>Crea una contraseña nueva</h1>
    <p class="gm-password-help">Escribe una contraseña que sólo tú conozcas y confírmala.</p>

    <#if message?has_content>
        <p class="gm-message gm-${message.type}">${kcSanitize(message.summary)?no_esc}</p>
    </#if>

    <form id="kc-passwd-update-form" action="${url.loginAction}" method="post">
        <label class="gm-password-label" for="password-new">Nueva contraseña</label>
        <input class="gm-password-input" id="password-new" name="password-new" type="password"
               autocomplete="new-password" minlength="12" required autofocus>

        <label class="gm-password-label" for="password-confirm">Repite la contraseña</label>
        <input class="gm-password-input" id="password-confirm" name="password-confirm" type="password"
               autocomplete="new-password" minlength="12" required>

        <button class="gm-password-submit" name="login" type="submit">Guardar contraseña</button>
    </form>
</main>
</body>
</html>
