(() => {
    const root = document.documentElement;
    const account = document.querySelector('#mail-account-key')?.value || '';
    const key = 'gator-mail.theme.' + account;
    const valid = value => value === 'green' ? 'green' : 'blue';
    let theme = 'blue';
    try { if (account) theme = valid(localStorage.getItem(key)); } catch (_) { /* Storage may be disabled. */ }
    root.dataset.mailTheme = theme;
    document.addEventListener('DOMContentLoaded', () => {
        const controls = document.querySelectorAll('.mail-theme-choice');
        const status = document.querySelector('#mail-theme-status');
        controls.forEach(control => {
            control.value = theme;
            control.onchange = () => {
                theme = valid(control.value);
                root.dataset.mailTheme = theme;
                controls.forEach(other => other.value = theme);
                let saved = false;
                try { if (account) { localStorage.setItem(key, theme); saved = true; } } catch (_) { /* Keep the current selection usable. */ }
                if (status) status.textContent = saved ? 'Apariencia guardada para esta cuenta en este navegador.'
                        : 'Apariencia aplicada. Este navegador no permite guardar la preferencia.';
            };
        });
    });
})();
