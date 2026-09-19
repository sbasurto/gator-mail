"""Small regression check: public branding and logo path containment."""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

script = Path(__file__).with_name('prepare_branding.py')
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    images = root / 'images'
    images.mkdir()
    (images / 'customer.png').write_bytes(b'customer logo')
    (root / 'private').write_text('secret')
    (images / 'escape.png').symlink_to(root / 'private')
    for n, logo in enumerate(['', None, 'customer.png', '../private', '/private', 'escape.png']):
        index = root / 'index.json'
        index.write_text(json.dumps({'nameToDisplay': 'Gator Master',
                                     'clientLogo': logo, 'password': 'not-public'}))
        output = root / str(n)
        result = subprocess.run([sys.executable, str(script), '--index', str(index),
                                 '--images', str(images), '--client', 'gator-erm-local',
                                 '--output', str(output)], capture_output=True)
        if n >= 3:
            assert result.returncode != 0, logo
            assert not (output / 'client-branding.json').exists()
            continue
        assert result.returncode == 0, result.stderr
        raw = (output / 'client-branding.json').read_text()
        assert 'not-public' not in raw and 'password' not in raw
        attrs = json.loads(raw)['attributes']
        assert attrs == {'login_theme': 'gator-standard',
                         'gator.displayName': 'Gator Master', 'gator.clientLogo': logo or ''}
        if logo:
            assert (output / 'login/resources/img/clients/customer.png').read_bytes() == b'customer logo'
print('Branding: nombre, logo, secretos y rutas verificados.')

# Changing the URL prevents reuse of the browser's cached parent favicon.
import hashlib
login = script.parent / 'login'
properties = dict(line.split('=', 1) for line in (login / 'theme.properties').read_text().splitlines() if '=' in line)
favicon = login / 'resources' / properties['favicons.standard']
assert favicon.is_file() and favicon.name != 'favicon.ico'
assert hashlib.sha256(favicon.read_bytes()).hexdigest()[:8] in favicon.name
print('Favicon: ruta versionada y hash verificados.')
