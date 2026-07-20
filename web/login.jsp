<%@page contentType="text/html" pageEncoding="UTF-8"%>
<% response.sendRedirect(request.getContextPath() + "/oauth/login"); if (true) return; %>
<!doctype html>
<html lang="es"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gator Mail</title><style>
:root{font-family:system-ui,sans-serif;background:#f4f7fb;color:#17233b}*{box-sizing:border-box}body{margin:0}
main{max-width:420px;margin:10vh auto;padding:2rem;background:white;border-radius:16px;box-shadow:0 8px 30px #16324f18}
form,label{display:grid;gap:.7rem}form{gap:1rem}input,button{font:inherit;padding:.85rem;border-radius:9px;border:1px solid #b8c4d3}
button{background:#1769aa;color:white;border:0;cursor:pointer}.error{padding:.8rem;background:#ffe9e9;border-radius:9px}
</style></head><body><main><h1>Gator Mail</h1><p>Accede con tu usuario Gator.</p>
<% if (request.getAttribute("gappFailureReason") != null) { %><p class="error">Usuario o contraseña incorrectos.</p><% } %>
<form method="post"><label>Usuario<input name="srGappUser" autocomplete="username" required autofocus></label>
<label>Contraseña<input type="password" name="srGappPassword" autocomplete="current-password" required></label>
<button>Continuar</button></form></main></body></html>
