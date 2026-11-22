#!/usr/bin/env python3
"""Run real Minecraft/Fabric server integration fixtures in an isolated local world."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get('MERCY_JDK', '/opt/homebrew/opt/openjdk'))
BUILD = ROOT / 'build'
SERVER = BUILD / 'test-server'
restart_only = '--restart-only' in sys.argv
cp = (BUILD / 'classpath.txt').read_text().strip()
main_jar = BUILD / 'libs/mercy-companions-0.4.2+26.3.jar'
if not main_jar.exists():
    raise SystemExit('Build the main mod first: python3 scripts/build.py')
main_sha256=hashlib.sha256(main_jar.read_bytes()).hexdigest()
classes = BUILD / 'test-classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir(parents=True)
sources = sorted((ROOT / 'src/test/java').rglob('*.java'))
subprocess.run([str(JDK/'bin/javac'), '--release','25','-encoding','UTF-8','-proc:none',
                '-cp',cp + os.pathsep + str(main_jar),'-d',str(classes),*map(str,sources)],check=True)
test_jar = BUILD / 'libs/mercy-integration-tests.jar'
with zipfile.ZipFile(test_jar,'w',zipfile.ZIP_DEFLATED) as z:
    for p in classes.rglob('*.class'): z.write(p,p.relative_to(classes).as_posix())
    z.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'mercy_integration_tests',
        'version':'1.0.0','environment':'server','entrypoints':{'main':['dev.mercy.companion.test.MercyIntegrationTests']},
        'depends':{'mercy_companions':'*'}}))
SERVER.mkdir(parents=True,exist_ok=True)
# Only disposable test artifacts are reset; no launcher account or real save is opened.
world = SERVER/'mercy-test-world'
if world.exists() and not restart_only: shutil.rmtree(world)
mods=SERVER/'mods';mods.mkdir(exist_ok=True)
for old in mods.glob('mercy-companions-*.jar'):
    old.unlink()
for jar in [main_jar,test_jar,ROOT.parent/'downloads/mod-selection/fabric-api-0.166.2+26.3.jar']:
    shutil.copy2(jar,mods/jar.name)
(SERVER/'eula.txt').write_text('eula=true\n')
(SERVER/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n'
    'level-name=mercy-test-world\nlevel-type=minecraft:flat\nlevel-seed=MercyIntegration26_3\ngenerate-structures=false\n'
    'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":3}],"biome":"minecraft:plains"}\n'
    'spawn-protection=0\nview-distance=2\nsimulation-distance=2\nenable-status=false\n'
    'max-tick-time=-1\ndifficulty=normal\n')
result=SERVER/'results.json'
if result.exists(): result.unlink()
args=[str(JDK/'bin/java'),'-Xms256M','-Xmx1536M','-Djava.awt.headless=true','-Dfabric.development=true',
      '-Dfabric.gameJarPath='+str(ROOT.parent/'PrismData/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'),
      '-cp',cp,'net.fabricmc.loader.impl.launch.knot.KnotServer','--nogui']
reports=[]
exit_codes=[]
phases=('restart',) if restart_only else ('integration','restart')
for phase in phases:
    if result.exists(): result.unlink()
    phase_args=args.copy()
    if phase=='restart':
        phase_args.insert(1,'-Dmercy.test.restart=true')
        properties=SERVER/'server.properties'
        properties.write_text(properties.read_text().replace('difficulty=normal','difficulty=peaceful'))
    log_path=SERVER/(phase+'.log')
    with log_path.open('w') as log:
        try: run=subprocess.run(phase_args,cwd=SERVER,stdout=log,stderr=subprocess.STDOUT,timeout=180)
        except subprocess.TimeoutExpired:
            print('Server timed out; see',log_path);sys.exit(2)
    exit_codes.append(run.returncode)
    if not result.exists():
        print(log_path.read_text()[-10000:]);raise SystemExit('No fixture result produced for '+phase)
    report=json.loads(result.read_text())
    (SERVER/(phase+'-results.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2))
    reports.append(report)
    if run.returncode: break
report=reports[0].copy()
report['tests']=[test for phase in reports for test in phase['tests']]
report['passed']=sum(p['passed'] for p in reports)
report['failed']=sum(p['failed'] for p in reports)
report['phases']=list(phases[:len(reports)])
report['main_mod_sha256']=main_sha256
report['vanilla_hostile_type_count']=sum(t['name'].startswith('recall minecraft:') for t in report['tests'])
result.write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False,indent=2))
print('Logs and isolated world:',SERVER)
raise SystemExit(0 if report.get('failed')==0 and len(reports)==len(phases) and all(code==0 for code in exit_codes) else 1)
