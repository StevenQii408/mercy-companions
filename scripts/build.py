#!/usr/bin/env python3
"""Build offline against locally installed, unobfuscated Minecraft 26.3 jars."""
from pathlib import Path
import os, shutil, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]
MC = ROOT.parent
BUILD = ROOT / 'build'
DEPS = BUILD / 'deps'


def configured_path(name, default):
    return Path(os.environ.get(name) or default).expanduser().resolve()


def find_javac():
    executable = 'javac.exe' if os.name == 'nt' else 'javac'
    for variable in ('MERCY_JDK', 'JAVA_HOME'):
        if os.environ.get(variable):
            candidate = configured_path(variable, '') / 'bin' / executable
            if not candidate.is_file():
                raise SystemExit(f'{variable} must point to a full JDK 25+; missing {candidate}')
            return str(candidate)
    # Preserve the original workspace default, then support ordinary PATH installations.
    local = Path('/opt/homebrew/opt/openjdk/bin') / executable
    if local.is_file():
        return str(local)
    candidate = shutil.which(executable)
    if candidate:
        return candidate
    raise SystemExit('JDK 25+ not found. Set MERCY_JDK or JAVA_HOME, or add javac to PATH.')


libraries = configured_path('MERCY_LIBRARIES', MC / 'PrismData/libraries')
api = configured_path('MERCY_FABRIC_API', MC / 'downloads/mod-selection/fabric-api-0.166.2+26.3.jar')
license_file = ROOT / 'LICENSE'
if not libraries.is_dir():
    raise SystemExit(f'MERCY_LIBRARIES must be an existing game dependency directory: {libraries}')
jars = sorted(libraries.rglob('*.jar'))
if not jars:
    raise SystemExit(f'No game dependency JARs found in MERCY_LIBRARIES: {libraries}')
if not api.is_file() or not zipfile.is_zipfile(api):
    raise SystemExit(f'MERCY_FABRIC_API must be a Fabric API JAR for Minecraft 26.3: {api}')
if not license_file.is_file():
    raise SystemExit(f'Release license is missing: {license_file}')
javac = find_javac()
print(f'Compiler: {javac}\nGame libraries: {libraries}\nFabric API: {api}', flush=True)
DEPS.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(api) as z:
    for name in z.namelist():
        if name.endswith('.jar'):
            dest = DEPS / Path(name).name
            dest.write_bytes(z.read(name))
            jars.append(dest)
cp = os.pathsep.join(map(str, jars))
(BUILD / 'classpath.txt').write_text(cp, encoding='utf-8')
classes = BUILD / 'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
args = ['--release', '25', '-encoding', 'UTF-8', '-proc:none', '-classpath', cp, '-d', str(classes)]
argfile = BUILD / 'javac.args'
argfile.write_text('\n'.join('"' + str(x).replace('\\', '\\\\').replace('"', '\\"') + '"' for x in args + sources), encoding='utf-8')
subprocess.run([javac, '@' + str(argfile)], check=True)
dest = BUILD / 'libs/mercy-companions-0.4.2+26.3.jar'
dest.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
    for base in [classes, ROOT / 'src/main/resources']:
        for f in sorted(base.rglob('*')):
            relative = f.relative_to(base)
            if f.is_file() and not any(part.startswith('.') for part in relative.parts):
                z.write(f, relative.as_posix())
    z.write(license_file, 'LICENSE')
print(dest)
