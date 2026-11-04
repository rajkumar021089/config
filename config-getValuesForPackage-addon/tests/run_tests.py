from pathlib import Path
import subprocess
import shutil
import tempfile
import base64
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = root / 'config/code/source/config/services.java'
node_dir = root / 'config/ns/config/services/getValuesForPackage'
fragment = ET.parse(node_dir / 'java.frag').getroot()
body = base64.b64decode(fragment.find("value[@name='body']").text).decode()
assert body == (root / 'designer-source/getValuesForPackage.java.txt').read_text()
assert body in source.read_text()
node = ET.parse(node_dir / 'node.ndf').getroot()
sig = node.find("record[@name='svc_sig']")
def shape(node):
    result = {}
    for record in node.findall("array[@name='rec_fields']/record"):
        name = record.find("value[@name='field_name']").text
        kind = record.find("value[@name='field_type']").text
        dim = record.find("value[@name='field_dim']").text
        result[name] = (kind, dim, shape(record))
    return result
out = shape(sig.find("record[@name='sig_out']"))
inp = shape(sig.find("record[@name='sig_in']"))
assert inp['ignoreKeysStartingWith'][:2] == ('string', '1')
assert out['contents'][:2] == ('record', '1')
assert out['keyValues'][:2] == ('record', '1')
detail = out['contents'][2]
assert detail['valueList'][:2] == ('string', '1')
assert detail['files'][:2] == ('record', '1')
assert set(detail['files'][2]) == {'fileName', 'value', 'isActive', 'isEffective'}
assert set(detail) == {'package', 'key', 'value', 'valueList', 'variable', 'isGlobalValue',
                       'containsUnresolvedInterpolator', 'files'}
print('PASS namespace input/output dimensions and Java fragment/source consistency', flush=True)
with tempfile.TemporaryDirectory(prefix='config-addon-classes-') as classes:
    java = sorted((root / 'tests/fixtures').rglob('*.java'))
    java += [source, root / 'tests/ConfigFullTest.java', root / 'tests/ConfigPackageValuesTest.java']
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    java_home = Path(shutil.which('java')).resolve().parent.parent
    level = ['--release', '11'] if (java_home / 'lib/ct.sym').exists() else [
        '-source', '11', '-target', '11', '-Xlint:-options']
    print('Compiler options: ' + ' '.join(level), flush=True)
    subprocess.run([*compiler, *level, '-encoding', 'UTF-8', '-d', classes,
                    *map(str, java)], check=True)
    for test in ['ConfigFullTest', 'ConfigPackageValuesTest']:
        subprocess.run(['java', '-cp', classes, test], check=True)
