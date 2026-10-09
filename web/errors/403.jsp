<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%
    response.setStatus(403);
    response.setHeader("Cache-Control", "no-store");
    String errorCode = "403";
    String errorTitle = "No tienes acceso a Gator Mail";
    String errorMessage = "Esta cuenta no tiene permiso para usar Gator Mail o no tiene un buzón asociado. Puedes iniciar sesión con otra cuenta o solicitar acceso al administrador.";
    request.setAttribute("gator.error.actionPath", "/oauth/login?prompt=login");
    request.setAttribute("gator.error.actionLabel", "Volver al inicio de sesión");
%>
<%@ include file="/errors/access-error.jspf" %>
