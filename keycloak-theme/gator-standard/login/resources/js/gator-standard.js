document.addEventListener("DOMContentLoaded", () => {
    const username = document.querySelector("#kc-form-login #username");
    const password = document.querySelector("#kc-form-login #password");
    if (password) password.placeholder = "Contraseña";
    if (!username) return;
    username.placeholder = "ingrese usuario";
    const field = username.parentElement;
    const row = document.createElement("div");
    row.className = "gator-username-row";
    field.before(row);
    row.append(field);
    const clear = document.createElement("button");
    clear.type = "button";
    clear.className = "gator-clear-user";
    clear.setAttribute("aria-label", "Limpiar usuario");
    clear.textContent = "×";
    clear.addEventListener("click", () => {
        username.value = "";
        username.dispatchEvent(new Event("input", { bubbles: true }));
        username.focus();
    });
    row.append(clear);
});
