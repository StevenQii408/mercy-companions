#!/usr/bin/env python3
"""Launch the real 26.3 client in an isolated workspace directory, without launcher credentials.

The default player is an offline test identity. This script never reads account files,
never opens a production save, and does not write to either installed Prism instance.
"""
from pathlib import Path
import argparse
import os
import shlex
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MC = ROOT.parent
BUILD = ROOT / 'build'
CLIENT = BUILD / 'test-client'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true', help='Print the safe launch command without starting Minecraft.')
    parser.add_argument('--world', help='Open an existing world directory inside build/test-client/saves.')
    args = parser.parse_args()

    game_jar = MC / 'PrismData/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar'
    mod_jar = BUILD / 'libs/mercy-companions-0.4.2+26.3.jar'
    api_jar = MC / 'downloads/mod-selection/fabric-api-0.166.2+26.3.jar'
    assets = MC / 'PrismData/assets'
    classpath_file = BUILD / 'classpath.txt'
    for path in [game_jar, mod_jar, api_jar, assets / 'indexes/34.json', classpath_file]:
        if not path.exists():
            raise SystemExit(f'Missing {path}. Build first: python3 scripts/build.py')

    java_home = Path(os.environ.get('MERCY_JAVA_HOME', str(MC / 'runtime/jdk-25.0.5+8-jre/Contents/Home')))
    java = java_home / 'bin/java'
    if not java.is_file():
        raise SystemExit(f'Java runtime not found: {java}. Set MERCY_JAVA_HOME to a Java 25+ runtime.')

    # Flat offline identity is deliberately unrelated to any signed-in launcher profile.
    command = [str(java), '-XstartOnFirstThread', '-Xms512M', '-Xmx3G',
               '-Dfabric.development=true', '-Dfabric.gameJarPath=' + str(game_jar),
               '-cp', classpath_file.read_text().strip(),
               'net.fabricmc.loader.impl.launch.knot.KnotClient',
               '--version', '26.3', '--versionType', 'Mercy development test',
               '--gameDir', str(CLIENT), '--assetsDir', str(assets), '--assetIndex', '34',
               '--username', 'MercyTester', '--uuid', '324df98822c5328fbff17a02b4df5045',
               '--accessToken', '0', '--userType', 'legacy',
               '--width', '1280', '--height', '800']
    if args.world:
        world = (CLIENT / 'saves' / args.world).resolve()
        saves = (CLIENT / 'saves').resolve()
        if world.parent != saves or not world.is_dir():
            raise SystemExit('--world must name an existing direct child of build/test-client/saves.')
        command.extend(['--quickPlaySingleplayer', args.world])

    if args.dry_run:
        print('Working directory:', CLIENT)
        print('Launch command:', shlex.join(command))
        return 0
    CLIENT.mkdir(parents=True, exist_ok=True)
    mods = CLIENT / 'mods'
    mods.mkdir(exist_ok=True)
    for old in mods.glob('mercy-companions-*.jar'):
        old.unlink()
    for jar in [mod_jar, api_jar]:
        shutil.copy2(jar, mods / jar.name)
    options = CLIENT / 'options.txt'
    if not options.exists():
        options.write_text('lang:zh_cn\nguiScale:0\nfullscreen:false\nmaxFps:60\n'
                           'renderDistance:8\nsimulationDistance:6\n'
                           'soundCategory_music:0.0\n', encoding='utf-8')
    print('Starting isolated offline client:', CLIENT, flush=True)
    print('Player: MercyTester. Real launcher accounts and saves are not used.', flush=True)
    return subprocess.call(command, cwd=CLIENT)


if __name__ == '__main__':
    sys.exit(main())
