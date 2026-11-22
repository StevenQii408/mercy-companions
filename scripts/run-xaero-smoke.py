#!/usr/bin/env python3
"""Check optional addon initialization in a disposable client; never open a world.

Requires the local game libraries/assets and an instance with the chosen addons.
The repository includes the fixture source, but does not redistribute those addons.
"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,sys,zipfile
parser=argparse.ArgumentParser()
parser.add_argument("--carryon",action="store_true",help="Also check Carry On's default pickup binding")
parser.add_argument("--source",type=Path,help="Game directory containing mods/config; overrides the local workspace default")
args=parser.parse_args()
ROOT=Path(__file__).resolve().parents[1];MC=ROOT.parent;BUILD=ROOT/'build'
SOURCE=args.source or MC/'PrismData/instances/Mercy-26.3/minecraft';STEM='carryon-smoke' if args.carryon else 'xaero-smoke';CLIENT=BUILD/(STEM+'-client')
if SOURCE.resolve()==CLIENT.resolve():parser.error('Source must not be the disposable test directory')
if not (SOURCE/'mods').is_dir():parser.error('Source game directory must contain a mods directory')
cp=(BUILD/'classpath.txt').read_text().strip();classes=BUILD/'xaero-smoke-classes';classes.mkdir(exist_ok=True)
jdk=Path(os.environ.get('MERCY_JDK') or os.environ.get('JAVA_HOME') or '/opt/homebrew/opt/openjdk')
subprocess.run([str(jdk/'bin/javac'),'--release','25','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),str(ROOT/'src/testSmoke/java/dev/mercy/maptest/XaeroSmoke.java')],check=True)
CLIENT.mkdir(exist_ok=True);mods=CLIENT/'mods'
if mods.exists():shutil.rmtree(mods)
mods.mkdir();manifest=[]
for jar in sorted((SOURCE/'mods').glob('*.jar')):
 shutil.copy2(jar,mods/jar.name);manifest.append({'file':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()})
with zipfile.ZipFile(mods/'mercy-xaero-smoke.jar','w',zipfile.ZIP_DEFLATED) as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
 z.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'mercy_xaero_smoke','version':'1.0','environment':'client','entrypoints':{'client':['dev.mercy.maptest.XaeroSmoke']}}))
config=CLIENT/'config';config.mkdir(exist_ok=True)
for name in ([] if args.carryon else ['xaero','xaerohud.txt','iris.properties','iris-excluded.json','sodium-options.json','sodium-mixins.properties','gammautils.json']):
 source=SOURCE/'config'/name
 if source.is_dir():shutil.copytree(source,config/name,dirs_exist_ok=True)
 elif source.is_file():shutil.copy2(source,config/name)
if not args.carryon and (SOURCE/'shaderpacks').exists():shutil.copytree(SOURCE/'shaderpacks',CLIENT/'shaderpacks',dirs_exist_ok=True)
(CLIENT/'options.txt').write_text('version:5023\nlang:zh_cn\nguiScale:2\nfullscreen:false\nmaxFps:30\nrenderDistance:4\nsimulationDistance:6\nsoundCategory_master:0.0\nonboardAccessibility:false\n')
(CLIENT/(STEM+'-manifest.json')).write_text(json.dumps(manifest,indent=2))
for name in [STEM+'-result.json',STEM+'-error.txt']:
 if (CLIENT/name).exists():(CLIENT/name).unlink()
cmd=[str(MC/'runtime/jdk-25.0.5+8-jre/Contents/Home/bin/java'),'-XstartOnFirstThread','-Xms512M','-Xmx2G','-Dfabric.development=true','-Dmercy.smoke.carryon='+str(args.carryon).lower(),'-Dfabric.gameJarPath='+str(MC/'PrismData/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'),'-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotClient','--version','26.3','--gameDir',str(CLIENT),'--assetsDir',str(MC/'PrismData/assets'),'--assetIndex','34','--username','MercyTester','--uuid','324df98822c5328fbff17a02b4df5045','--accessToken','0','--userType','legacy','--width','900','--height','600']
print('Launching isolated maps initialization smoke test:',CLIENT,flush=True)
with (CLIENT/'smoke.log').open('w') as log:
 result=subprocess.run(cmd,cwd=CLIENT,stdout=log,stderr=subprocess.STDOUT,timeout=120)
report=CLIENT/(STEM+'-result.json')
if result.returncode or not report.exists() or (CLIENT/(STEM+'-error.txt')).exists():
 print((CLIENT/'smoke.log').read_text()[-5000:]);sys.exit(1)
print(report.read_text())
