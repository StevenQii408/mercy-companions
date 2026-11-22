#!/usr/bin/env python3
"""Capture vanilla and companion screens at GUI scale 4 and auto in a copied test world."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--language',choices=['zh_cn','en_us'],default='zh_cn')
parser.add_argument('--bag-only',action='store_true')
parser.add_argument('--actions-only',action='store_true')
parser.add_argument('--world-source',type=Path,help='Read-only snapshot of the isolated server test world')
parser.add_argument('--with-addons',action='store_true',help='Use the same shaders, Freecam and night vision as the original instance.')
args=parser.parse_args()

ROOT = Path(__file__).resolve().parents[1]
MC = ROOT.parent
BUILD = ROOT / 'build'
CLIENT = BUILD / ('vanilla-ui-test-client' if args.language=='zh_cn' else 'vanilla-ui-test-client-en_us')
if args.with_addons:
    CLIENT = BUILD / ('addon-ui-test-client' if args.language=='zh_cn' else 'addon-ui-test-client-en_us')
JDK = Path(os.environ.get('MERCY_JDK', '/opt/homebrew/opt/openjdk'))
JAVA = MC / 'runtime/jdk-25.0.5+8-jre/Contents/Home/bin/java'
cp = (BUILD / 'classpath.txt').read_text().strip()
main_jar = BUILD / 'libs/mercy-companions-0.4.2+26.3.jar'
source_world = args.world_source or BUILD / 'test-server/mercy-test-world'
if not main_jar.exists() or not (source_world/'level.dat').exists():
    raise SystemExit('Main build and isolated server test world must exist first.')
classes = BUILD / 'vanilla-ui-test-classes'
classes.mkdir(parents=True, exist_ok=True)
sources = sorted((ROOT / 'src/testClient/java').rglob('*.java'))
subprocess.run([str(JDK/'bin/javac'),'--release','25','-encoding','UTF-8','-proc:none',
    '-cp',cp+os.pathsep+str(main_jar),'-d',str(classes),*map(str,sources)],check=True)
test_jar = BUILD / 'mercy-vanilla-ui-fixture.jar'
with zipfile.ZipFile(test_jar,'w',zipfile.ZIP_DEFLATED) as z:
    for p in classes.rglob('*.class'): z.write(p,p.relative_to(classes).as_posix())
    z.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'mercy_vanilla_ui_fixture',
        'version':'1.0.0','environment':'client','entrypoints':{'client':['dev.mercy.companion.testclient.VanillaUiVisualFixture']},
        'depends':{'mercy_companions':'*'}}))
CLIENT.mkdir(parents=True,exist_ok=True)
mods=CLIENT/'mods';mods.mkdir(exist_ok=True)
for old in mods.glob('mercy-companions-*.jar'):
    old.unlink()
for jar in [main_jar,test_jar,MC/'downloads/mod-selection/fabric-api-0.166.2+26.3.jar']:
    shutil.copy2(jar,mods/jar.name)
world = CLIENT/'saves/ui-fixture-world'
if world.exists(): shutil.rmtree(world)
shutil.copytree(source_world,world,ignore=shutil.ignore_patterns('session.lock','logs'))
# The copied terrain is useful; unrelated server-test owners and deliberately stressed rosters are not.
copied_roster=world/'data/mercy_companions.json'
if copied_roster.exists(): copied_roster.unlink()
(CLIENT/'options.txt').write_text(f'version:5023\nlang:{args.language}\nguiScale:4\nfullscreen:false\nmaxFps:60\n'
    'pauseOnLostFocus:false\nrenderDistance:4\nsimulationDistance:6\nsoundCategory_master:0.0\n'
    'onboardAccessibility:false\n',encoding='utf-8')
if args.with_addons:
    from addons import install_addons
    manifest=install_addons(CLIENT)
    installed=MC/'PrismData/instances/Mercy-26.3/minecraft/mods'
    map_ids={'xaerominimap','xaeroworldmap'}
    for old in mods.glob('xaero*.jar'): old.unlink()
    for jar in sorted(installed.glob('xaero*.jar')):
        with zipfile.ZipFile(jar) as archive: metadata=json.loads(archive.read('fabric.mod.json'))
        if metadata['id'] not in map_ids: continue
        shutil.copy2(jar,mods/jar.name)
        manifest.append({'id':metadata['id'],'version':metadata['version'],'file':jar.name,
                         'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()})
    if not map_ids.issubset({entry['id'] for entry in manifest}):
        raise SystemExit('Both installed Xaero map addons are required for this combination fixture.')
    (CLIENT/'addon-manifest.json').write_text(json.dumps(manifest,indent=2))
for name in ['ui-results.json','ui-error.txt','addon-results.json','particle-results.json','bag-action-results.json']:
    p=CLIENT/name
    if p.exists():p.unlink()
command=[str(JAVA),'-XstartOnFirstThread','-Xms512M','-Xmx3G','-Dfabric.development=true',
    '-Dmercy.ui.bagOnly='+str(args.bag_only).lower(),
    '-Dmercy.ui.actionsOnly='+str(args.actions_only).lower(),
    '-Dmercy.ui.addons='+str(args.with_addons).lower(),
    '-Dfabric.gameJarPath='+str(MC/'PrismData/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'),
    '-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--version','26.3','--gameDir',str(CLIENT),'--assetsDir',str(MC/'PrismData/assets'),'--assetIndex','34',
    '--username','MercyTester','--uuid','324df98822c5328fbff17a02b4df5045','--accessToken','0',
    '--userType','legacy','--width','1280','--height','800']
print('Launching isolated vanilla UI fixture:',CLIENT,flush=True)
with (CLIENT/'ui.log').open('w') as log:
    try: result=subprocess.run(command,cwd=CLIENT,stdout=log,stderr=subprocess.STDOUT,timeout=300)
    except subprocess.TimeoutExpired:
        print('UI fixture timed out; see',CLIENT/'ui.log');sys.exit(2)
report=CLIENT/'ui-results.json'
if not report.exists() or (CLIENT/'ui-error.txt').exists():
    print((CLIENT/'ui.log').read_text()[-6000:]);sys.exit(1)
if args.with_addons and not (CLIENT/'addon-results.json').exists():
    raise SystemExit('No addon compatibility result was produced.')
print(report.read_text())
print('Screenshots:',CLIENT/'screenshots')
sys.exit(result.returncode)
