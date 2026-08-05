(() => {
    document.querySelector("#mail-print-again")?.addEventListener("click", () => window.print());
    window.addEventListener("load", () => window.print());
})();
