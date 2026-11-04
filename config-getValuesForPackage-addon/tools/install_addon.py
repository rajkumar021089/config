"""Merge only the new service into an existing canonical config package; keep settings/UI intact."""
from pathlib import Path
import argparse
import os
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

ADDON = Path(__file__).resolve().parents[1]

def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
        if path.exists():
            shutil.copymode(path, temporary)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)

def install(package, check_only=False):
    package = package.resolve(strict=True)
    if package == (ADDON / 'config').resolve():
        raise ValueError('Choose the installed package, not this add-on reference directory')
    manifest = ET.parse(package / 'manifest.v3').getroot()
    name = manifest.find("value[@name='name']")
    if name is None or name.text != 'config':
        raise ValueError('This installer targets the canonical config package; use Designer for a renamed namespace')
    source = package / 'code/source/config/services.java'
    if source.is_symlink() or not source.is_file():
        raise ValueError('Expected a regular code/source/config/services.java file')
    if not (package / 'ns/config/services/node.idf').is_file():
        raise ValueError('Existing config.services namespace was not found')
    old = source.read_bytes()
    text = old.decode('utf-8')
    if not re.search(r'\bpackage\s+config\s*;', text) or not re.search(r'\bclass\s+services\b', text):
        raise ValueError('Unexpected Java package/class')
    required = ['ConfigStore', 'Snapshot', 'authorize(', 'store()', 'flag(', 'input(', 'output(', 'failure(']
    if any(token not in text for token in required):
        raise ValueError('Required config 2.3.3 helpers are missing; review this package before merging')
    method = (ADDON / 'designer-source/METHOD.java.txt').read_text()
    if method in text:
        updated = old
    elif re.search(r'\bvoid\s+getValuesForPackage\s*\(', text):
        raise ValueError('A different getValuesForPackage method already exists; no files changed')
    else:
        if not text.rstrip().endswith('}'):
            raise ValueError('Unexpected end of Java class; no files changed')
        closing = text.rfind('}')
        updated = (text[:closing] + '\n' + method + text[closing:]).encode('utf-8')
    planned = []
    if updated != old:
        planned.append((source, updated))
    relative = Path('ns/config/services/getValuesForPackage')
    for filename in ['node.ndf', 'java.frag']:
        target = package / relative / filename
        content = (ADDON / 'config' / relative / filename).read_bytes()
        if target.is_symlink():
            raise ValueError('Service metadata must not be symbolic links')
        if target.exists() and target.read_bytes() != content:
            raise ValueError('Different existing service metadata at ' + str(target) + '; no files changed')
        if not target.exists():
            planned.append((target, content))
    if not planned:
        print('Service source and metadata are already installed.')
        return
    print('Files to update:')
    for path, _ in planned:
        print('  ' + str(path.relative_to(package)))
    if check_only:
        print('Check only; no files changed.')
        return
    backup_root = package / 'addon-backups'
    backup_root.mkdir(exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix='getValuesForPackage-', dir=backup_root))
    for path, _ in planned:
        if path.exists():
            saved = backup / path.relative_to(package)
            saved.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, saved)
    for path, content in planned:
        atomic_write(path, content)
    print('Backup: ' + str(backup))
    print('Merged service source and namespace metadata.')
    print('Compile config.services against the real IS libraries, then reload the config package.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package_directory', type=Path)
    parser.add_argument('--check', action='store_true', help='Validate and show intended files without writing')
    args = parser.parse_args()
    try:
        install(args.package_directory, args.check)
    except (ValueError, OSError, ET.ParseError) as exc:
        parser.exit(1, 'Installation stopped: ' + str(exc) + '\n')
