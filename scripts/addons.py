"""Copy the user's requested, already-installed 26.3 addons into an isolated game directory."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / 'PrismData/instances/Unbound-26.3/minecraft'
MOD_IDS = {'sodium', 'iris', 'freecam', 'gammautils', 'cloth-config'}
CONFIGS = ('iris.properties', 'iris-excluded.json', 'sodium-options.json',
           'sodium-mixins.properties', 'gammautils.json')
KEY_PREFIXES = ('key_key.freecam.', 'key_key.gammautils.', 'key_iris.')


def install_addons(target: Path) -> list[dict]:
    import zipfile
    copied = []
    mods = target / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    found = set()
    for jar in sorted((SOURCE / 'mods').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            if 'fabric.mod.json' not in archive.namelist():
                continue
            metadata = json.loads(archive.read('fabric.mod.json'))
        if metadata['id'] not in MOD_IDS:
            continue
        shutil.copy2(jar, mods / jar.name)
        found.add(metadata['id'])
        copied.append({'id': metadata['id'], 'version': metadata['version'],
                       'file': jar.name, 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
    if found != MOD_IDS:
        raise RuntimeError(f'Missing installed addon dependencies: {MOD_IDS - found}')
    (target / 'config').mkdir(exist_ok=True)
    for name in CONFIGS:
        source = SOURCE / 'config' / name
        if source.exists():
            shutil.copy2(source, target / 'config' / name)
    shaders = target / 'shaderpacks'
    shaders.mkdir(exist_ok=True)
    for source in (SOURCE / 'shaderpacks').glob('ComplementaryUnbound_r5.9.3.zip*'):
        shutil.copy2(source, shaders / source.name)
    options = target / 'options.txt'
    lines = options.read_text().splitlines() if options.exists() else []
    lines = [line for line in lines if not line.startswith(KEY_PREFIXES)]
    lines += [line for line in (SOURCE / 'options.txt').read_text().splitlines()
              if line.startswith(KEY_PREFIXES)]
    options.write_text('\n'.join(lines) + '\n')
    return copied
