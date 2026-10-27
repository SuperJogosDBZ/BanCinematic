"""Prepare a new local-only Paper fixture; never modifies the template or seed."""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
import re
import shutil
import sqlite3
import uuid
import xml.etree.ElementTree as ET

parser = ArgumentParser()
parser.add_argument('--template', type=Path, required=True)
parser.add_argument('--seed', type=Path, required=True)
parser.add_argument('--probe-classes', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
if 'eula=true' not in (args.template / 'eula.txt').read_text(encoding='utf-8'):
    raise SystemExit('The Paper template must already have an accepted EULA.')
args.output.mkdir(parents=True, exist_ok=False)
for name in ['cache', 'libraries', 'versions', 'config', 'world', 'world_nether', 'world_the_end']:
    if (args.template / name).exists():
        shutil.copytree(args.template / name, args.output / name)
for name in ['paper.jar', 'bukkit.yml', 'spigot.yml', 'eula.txt']:
    shutil.copy2(args.template / name, args.output / name)
properties = (args.template / 'server.properties').read_text(encoding='utf-8')
for key, value in {
    'server-ip': '127.0.0.1', 'server-port': '25585', 'max-players': '400',
    'gamemode': 'creative', 'force-gamemode': 'true', 'difficulty': 'peaceful',
    'online-mode': 'false', 'view-distance': '3', 'simulation-distance': '3',
}.items():
    properties = re.sub(r'^' + re.escape(key) + r'=.*$', key + '=' + value, properties, flags=re.M)
(args.output / 'server.properties').write_text(properties, encoding='utf-8')
bukkit = (args.output / 'bukkit.yml').read_text(encoding='utf-8')
bukkit = re.sub(r'connection-throttle:\s*-?\d+', 'connection-throttle: 0', bukkit)
(args.output / 'bukkit.yml').write_text(bukkit, encoding='utf-8')
for name in ['ops.json', 'banned-players.json', 'banned-ips.json', 'whitelist.json']:
    (args.output / name).write_text('[]', encoding='utf-8')
plugin = args.output / 'plugins/BanCinematic'
plugin.mkdir(parents=True)
config = (root / 'src/main/resources/config.yml').read_text(encoding='utf-8')
(plugin / 'config.yml').write_text(config.replace('  enabled: true', '  enabled: false'), encoding='utf-8')
version = ET.parse(root / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
shutil.copy2(root / f'target/BanCinematic-{version}.jar', args.output / 'plugins/BanCinematic.jar')
with sqlite3.connect(args.seed.resolve().as_uri() + '?mode=ro', uri=True) as source:
    with sqlite3.connect(plugin / 'bancinematic.db') as destination:
        source.backup(destination)
with sqlite3.connect(plugin / 'bancinematic.db') as connection:
    for i in range(400):
        name = f'SqlLoad{i:04d}'
        identity = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))
        connection.execute('INSERT INTO player_names VALUES (?,?,?,?)', (name.lower(), identity, name, 1735689600000))
        if i % 10 == 0:
            connection.execute('INSERT INTO ban_records VALUES (?,?,?,?,NULL)', (identity, name, 'seed bot ban', 'LOAD_STAFF'))
        elif i % 10 in [1, 2]:
            connection.execute('INSERT INTO mute_records VALUES (?,?,?,?,NULL)', (identity, name, 'seed bot mute', 'LOAD_STAFF'))
with ZipFile(args.output / 'plugins/SqlitePaperProbe.jar', 'x', ZIP_DEFLATED) as archive:
    for file in args.probe_classes.rglob('*.class'):
        archive.write(file, file.relative_to(args.probe_classes).as_posix())
    archive.writestr('plugin.yml', '''name: SqlitePaperProbe
version: 1.0
main: SqlitePaperProbe
api-version: '1.21.11'
depend: [BanCinematic]
commands:
  sqliteload:
    permission: sqliteload.admin
permissions:
  sqliteload.admin:
    default: op
''')
print(args.output.resolve())
