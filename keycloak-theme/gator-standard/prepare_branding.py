"""Prepare public Keycloak branding from the existing Gator index (no API calls)."""
import argparse
import json
import re
import shutil
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--index', required=True, type=Path)
parser.add_argument('--images', required=True, type=Path)
parser.add_argument('--client', required=True)
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
config = json.loads(args.index.read_text())
logo = config.get('clientLogo') or ''
if logo:
    if not re.fullmatch(r'[A-Za-z0-9_./-]+', logo) or '..' in logo or logo.startswith('/'):
        parser.error('clientLogo debe ser una ruta relativa válida')
    source = (args.images / logo).resolve()
    if not source.is_relative_to(args.images.resolve()) or not source.is_file():
        parser.error('No se encontró clientLogo dentro del directorio de imágenes')
    target = args.output / 'login/resources/img/clients' / logo
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
args.output.mkdir(parents=True, exist_ok=True)
# Merge these attributes into the existing client; never replace its other attributes.
patch = {'clientId': args.client, 'attributes': {
    'login_theme': 'gator-standard',
    'gator.displayName': config.get('nameToDisplay') or args.client,
    'gator.clientLogo': logo,
}}
(args.output / 'client-branding.json').write_text(json.dumps(patch, ensure_ascii=False, indent=2) + '\n')
print('Branding preparado; no se modificó Keycloak.')
