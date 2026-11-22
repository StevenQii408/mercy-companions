#!/usr/bin/env python3
"""Run the dedicated death/Freecam matrix in a copied test world, never a user save."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile
from addons import install_addons

ROOT=Path(__file__).resolve().parents[1]
MC=ROOT.parent
BUILD=ROOT/'build'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--label',default='baseline')
parser.add_argument('--jar',type=Path,default=BUILD/'libs/mercy-companions-0.4.2+26.3.jar')
args=parser.parse_args()
if not args.label.replace('-','').replace('_','').isalnum():raise SystemExit('Use an alphanumeric test label.')
client=BUILD/('death-flow-client-'+args.label)
source=BUILD/'ui-test-source-world'
if not args.jar.exists() or not (source/'level.dat').exists():raise SystemExit('Main jar and frozen isolated UI world snapshot are required.')
jdk=Path(os.environ.get('MERCY_JDK','/opt/homebrew/opt/openjdk'))
java=MC/'runtime/jdk-25.0.5+8-jre/Contents/Home/bin/java'
cp=(BUILD/'classpath.txt').read_text().strip()
classes=BUILD/'death-flow-test-classes'
classes.mkdir(exist_ok=True)
fixture_source=ROOT/'src/testClient/java/dev/mercy/companion/testclient/DeathFlowVisualFixture.java'
subprocess.run([str(jdk/'bin/javac'),'--release','25','-encoding','UTF-8','-proc:none',
    '-cp',cp+os.pathsep+str(args.jar),'-d',str(classes),str(fixture_source)],check=True)
fixture=BUILD/'mercy-death-flow-fixture.jar'
with zipfile.ZipFile(fixture,'w',zipfile.ZIP_DEFLATED) as archive:
    for path in classes.rglob('*.class'):archive.write(path,path.relative_to(classes).as_posix())
    archive.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'mercy_death_flow_fixture','version':'1.0.0',
        'environment':'client','entrypoints':{'client':['dev.mercy.companion.testclient.DeathFlowVisualFixture']},
        'depends':{'mercy_companions':'*'}}))
client.mkdir(parents=True,exist_ok=True)
mods=client/'mods'
if mods.exists():shutil.rmtree(mods)
mods.mkdir()
for jar in [args.jar,fixture,MC/'downloads/mod-selection/fabric-api-0.166.2+26.3.jar']:shutil.copy2(jar,mods/jar.name)
world=client/'saves/death-fixture-world'
if world.exists():shutil.rmtree(world)
shutil.copytree(source,world,ignore=shutil.ignore_patterns('session.lock','logs'))
roster=world/'data/mercy_companions.json'
if roster.exists():roster.unlink()
(client/'options.txt').write_text('version:5023\nlang:zh_cn\nguiScale:0\nfullscreen:false\nmaxFps:60\n'
    'pauseOnLostFocus:false\nrenderDistance:4\nsimulationDistance:6\nsoundCategory_master:0.0\n'
    'onboardAccessibility:false\n',encoding='utf-8')
addons=install_addons(client)
manifest={'mainJarSha256':hashlib.sha256(args.jar.read_bytes()).hexdigest(),'addons':addons}
(client/'death-manifest.json').write_text(json.dumps(manifest,indent=2))
for name in ['death-results.json','death-error.txt']:
    path=client/name
    if path.exists():path.unlink()
command=[str(java),'-XstartOnFirstThread','-Xms512M','-Xmx3G','-Dfabric.development=true',
    '-Dfabric.gameJarPath='+str(MC/'PrismData/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'),
    '-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient','--version','26.3','--gameDir',str(client),
    '--assetsDir',str(MC/'PrismData/assets'),'--assetIndex','34','--username','MercyTester',
    '--uuid','324df98822c5328fbff17a02b4df5045','--accessToken','0','--userType','legacy','--width','1280','--height','800']
print('Launching isolated death flow:',client,'jar',manifest['mainJarSha256'],flush=True)
with (client/'death.log').open('w') as log:
    try:result=subprocess.run(command,cwd=client,stdout=log,stderr=subprocess.STDOUT,timeout=300)
    except subprocess.TimeoutExpired:
        print('Timed out; inspect',client/'death.log');sys.exit(2)
if not (client/'death-results.json').exists() or (client/'death-error.txt').exists():
    print((client/'death.log').read_text()[-7000:]);sys.exit(1)
print((client/'death-results.json').read_text())
print('Screenshots:',client/'screenshots')
sys.exit(result.returncode)
