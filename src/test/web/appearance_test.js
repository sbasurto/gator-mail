// node src/test/web/appearance_test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.resolve(__dirname, '../../../web/js/gator-mail-appearance.js'), 'utf8');
const storage = new Map();
function page(account, blocked = false) {
    const listeners = {}, root = {dataset: {}}, controls = [{value: ''}, {value: ''}];
    const context = {document: {documentElement: root, body: {dataset: {mailAccount: account}},
        addEventListener: (name, fn) => listeners[name] = fn,
        querySelectorAll: () => controls, querySelector: selector => selector === "#mail-account-key" ? {value: account} : null},
        localStorage: {getItem(key) {if (blocked) throw Error('denied'); return storage.get(key);},
            setItem(key, value) {if (blocked) throw Error('denied'); storage.set(key, value);}}};
    vm.runInNewContext(script, context);
    listeners.DOMContentLoaded();
    return {root, controls, change(theme) {controls[0].value = theme; controls[0].onchange();}};
}
const first = page('one@example.com');
assert.equal(first.root.dataset.mailTheme, 'blue');
first.change('green');
assert.equal(first.root.dataset.mailTheme, 'green');
assert.equal(first.controls[1].value, 'green');
assert.equal(page('one@example.com').root.dataset.mailTheme, 'green');
assert.equal(page('two@example.com').root.dataset.mailTheme, 'blue');
first.change('untrusted');
assert.equal(page('one@example.com').root.dataset.mailTheme, 'blue');
const denied = page('one@example.com', true);
denied.change('green');
assert.equal(denied.root.dataset.mailTheme, 'green');
assert.equal(page('').root.dataset.mailTheme, 'blue');
console.log('Temas: selección, persistencia por cuenta, valores inválidos y almacenamiento bloqueado correctos.');

// CSS masks must use local SVG masks: production CSP rejects data: images.
const css = fs.readFileSync(path.resolve(__dirname, '../../../web/css/gator-mail.css'), 'utf8');
const sprite = fs.readFileSync(path.resolve(__dirname, '../../../web/css/mail-icons.svg'), 'utf8');
assert.ok(!css.includes('data:image/svg'), 'Iconos compatibles con CSP default-src self');
const masks = [...css.matchAll(/--gm-icon: url\("mail-icons.svg#([a-z]+)"\)/g)];
assert.ok(masks.length > 0);
for (const [, id] of masks) assert.ok(sprite.includes('<mask id="' + id + '"'), 'Máscara SVG local: ' + id);
