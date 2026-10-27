"""Prepare a fresh Paper folder using only the owned remote MySQL test prefix."""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json
import os
import re
import shutil
import xml.etree.ElementTree as ET

parser = ArgumentParser()
parser.add_argument('--template', type=Path, required=True)
parser.add_argument('--state', type=Path, required=True)
parser.add_argument('--probe-classes', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
prefix = json.loads(args.state.read_text(encoding='utf-8'))['prefix']
if not re.fullmatch(r'bc_lm_2710_[0-9a-f]{8}_', prefix):
    raise SystemExit('Not a MySQL load-test prefix.')
if 'eula=true' not in (args.template / 'eula.txt').read_text(encoding='utf-8'):
    raise SystemExit('The template must already have an accepted EULA.')
args.output.mkdir(parents=True, exist_ok=False)
for name in ['cache', 'libraries', 'versions', 'config', 'world', 'world_nether', 'world_the_end']:
    if (args.template / name).exists():
        shutil.copytree(args.template / name, args.output / name)
for name in ['paper.jar', 'bukkit.yml', 'spigot.yml', 'eula.txt']:
    shutil.copy2(args.template / name, args.output / name)
properties = (args.template / 'server.properties').read_text(encoding='utf-8')
for key, value in {
    'server-ip': '127.0.0.1', 'server-port': '25586', 'max-players': '80',
    'gamemode': 'creative', 'force-gamemode': 'true', 'difficulty': 'peaceful',
    'online-mode': 'false', 'view-distance': '3', 'simulation-distance': '3',
}.items():
    properties = re.sub(r'^' + re.escape(key) + r'=.*$', key + '=' + value, properties, flags=re.M)
(args.output / 'server.properties').write_text(properties, encoding='utf-8')
bukkit = (args.output / 'bukkit.yml').read_text(encoding='utf-8')
(args.output / 'bukkit.yml').write_text(re.sub(r'connection-throttle:\s*-?\d+', 'connection-throttle: 0', bukkit), encoding='utf-8')
for name in ['ops.json', 'banned-players.json', 'banned-ips.json', 'whitelist.json']:
    (args.output / name).write_text('[]', encoding='utf-8')
plugin = args.output / 'plugins/BanCinematic'
plugin.mkdir(parents=True)
config = (root / 'src/main/resources/config.yml').read_text(encoding='utf-8')
config = config.replace('  enabled: true', '  enabled: false').replace('type: SQLITE', 'type: MYSQL')
config = config.replace('table-prefix: ""', 'table-prefix: "' + prefix + '"')
config = config.replace('host: 127.0.0.1', 'host: ' + os.environ['BANCINEMATIC_TEST_MYSQL_HOST'])
config = config.replace('database: bancinematic', 'database: ' + os.environ['BANCINEMATIC_TEST_MYSQL_DATABASE'])
config = config.replace('username: root', 'username: ' + os.environ['BANCINEMATIC_TEST_MYSQL_USERNAME'])
# Password stays empty on disk. The child JVM receives BANCINEMATIC_MYSQL_PASSWORD.
(plugin / 'config.yml').write_text(config, encoding='utf-8')
version = ET.parse(root / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
shutil.copy2(root / f'target/BanCinematic-{version}.jar', args.output / 'plugins/BanCinematic.jar')
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
