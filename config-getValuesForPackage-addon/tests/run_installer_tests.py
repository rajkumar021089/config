from pathlib import Path
import hashlib
import subprocess
import sys
import tempfile

addon = Path(__file__).resolve().parents[1]
installer = addon / 'tools/install_addon.py'
method = (addon / 'designer-source/METHOD.java.txt').read_text()
reference = (addon / 'config/code/source/config/services.java').read_text()

def snapshot(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob('*') if p.is_file()}

count = 0
def check(ok, label):
    global count
    assert ok, label
    count += 1
    print('PASS ' + label)

with tempfile.TemporaryDirectory(prefix='config-addon-install-') as temp:
    package = Path(temp) / 'config'
    source = package / 'code/source/config/services.java'
    source.parent.mkdir(parents=True)
    original = reference.replace(method, '')
    at = original.rfind('}')
    original = original[:at] + '  private static final String LOCAL_CUSTOMIZATION = "keep-me";\n' + original[at:]
    source.write_text(original)
    (package / 'manifest.v3').write_text('<Values><value name="name">config</value></Values>')
    namespace = package / 'ns/config/services'
    namespace.mkdir(parents=True)
    (namespace / 'node.idf').write_text('<Values/>')
    (package / 'config').mkdir()
    (package / 'config/manager.properties').write_text('environment=custom\n')
    (package / 'pub').mkdir()
    (package / 'pub/console.js').write_text('// Existing menu-navigation fix and local UI changes\n')
    before = snapshot(package)
    subprocess.run([sys.executable, installer, package, '--check'], check=True, capture_output=True)
    check(snapshot(package) == before, 'installer check mode performs no writes')
    subprocess.run([sys.executable, installer, package], check=True, capture_output=True)
    updated = source.read_text()
    check(method in updated and 'LOCAL_CUSTOMIZATION = "keep-me"' in updated,
          'installer merges new method and preserves local Java changes')
    check(updated.replace('\n' + method, '', 1) == original,
          'existing Java source content is otherwise unchanged')
    check((namespace / 'getValuesForPackage/node.ndf').is_file()
          and (namespace / 'getValuesForPackage/java.frag').is_file(),
          'installer adds both namespace files')
    for name in ['config/manager.properties', 'pub/console.js']:
        check(snapshot(package)[name] == before[name], 'installer preserves ' + name)
    backups = list((package / 'addon-backups').glob('*/code/source/config/services.java'))
    check(len(backups) == 1 and backups[0].read_text() == original,
          'installer backup retains complete original source')
    installed = snapshot(package)
    subprocess.run([sys.executable, installer, package], check=True, capture_output=True)
    check(snapshot(package) == installed, 'reinstall is idempotent and creates no extra backup')

    source.write_text(original)
    node = namespace / 'getValuesForPackage/node.ndf'
    node.write_text('<Values>different service metadata</Values>')
    conflicting = snapshot(package)
    failed = subprocess.run([sys.executable, installer, package], capture_output=True)
    check(failed.returncode != 0 and snapshot(package) == conflicting,
          'metadata conflict is rejected before modifying Java source')
    (package / 'manifest.v3').write_text('<Values><value name="name">NBKConfig</value></Values>')
    renamed = snapshot(package)
    failed = subprocess.run([sys.executable, installer, package], capture_output=True)
    check(failed.returncode != 0 and snapshot(package) == renamed,
          'installer rejects an unrecognized renamed package without writes')
print('TOTAL ' + str(count) + ' installer checks passed')
