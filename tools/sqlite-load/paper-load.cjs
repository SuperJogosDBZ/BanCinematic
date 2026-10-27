// Requires the already-installed test client in .runtime-test/node_modules.
const fs = require('fs'), path = require('path'), { spawn } = require('child_process');
const root = path.resolve(__dirname, '../..');
const mc = require(require.resolve('minecraft-protocol', { paths: [path.join(root, '.runtime-test')] }));
const serverDir = path.resolve(process.argv[2]);
const outputDir = path.dirname(serverDir), port = Number(process.env.BCLOAD_PORT || 25585);
const java = process.env.BCLOAD_JAVA || 'java';
const log = fs.createWriteStream(path.join(outputDir, 'paper-load.log'));
let output = '', server;
const clients = [], samples = [], summaries = [], phases = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(test, timeout = 30000) {
    const start = performance.now();
    while (!test()) {
        if (server && server.exitCode !== null) throw Error('Server stopped: ' + server.exitCode);
        if (performance.now() - start > timeout) throw Error('Timeout: ' + test.toString());
        await sleep(100);
    }
}
function command(text) { log.write('COMMAND ' + text + '\n'); server.stdin.write(text + '\n'); }
function stats(values) {
    if (!values.length) return { count: 0 };
    values.sort((a, b) => a - b);
    const p = fraction => values[Math.ceil(values.length * fraction) - 1];
    return { count: values.length, p50: p(.5), p95: p(.95), p99: p(.99), max: values.at(-1) };
}
function connect(index) {
    const name = 'SqlLoad' + String(index).padStart(4, '0');
    const start = performance.now();
    const client = mc.createClient({ host: '127.0.0.1', port, username: name, auth: 'offline', version: '1.21.11' });
    const item = { index, name, client, start, joined: false, ended: false, messages: [], expectedBanned: index % 10 === 0 };
    clients.push(item);
    client.on('playerJoin', () => { item.joined = true; item.joinMs = performance.now() - start; });
    for (const event of ['login', 'respawn']) client.on(event, () => setTimeout(() => {
        if (!item.ended) client.write('player_loaded', {});
    }, 200));
    client.on('position', packet => { if (packet.teleportId !== undefined) client.write('teleport_confirm', { teleportId: packet.teleportId }); });
    for (const event of ['system_chat', 'player_chat']) client.on(event, packet => {
        item.messages.push(JSON.stringify(packet, (key, value) => typeof value === 'bigint' ? value.toString() : value));
    });
    client.on('disconnect', packet => { item.disconnect = packet; log.write('DISCONNECT ' + name + ' ' + JSON.stringify(packet) + '\n'); });
    client.on('end', reason => { item.ended = true; item.endReason = reason; item.endMs = performance.now() - start; });
    client.on('error', error => { item.error = error.message; log.write('CLIENT_ERROR ' + name + ' ' + error.message + '\n'); });
    return item;
}

async function phase(name, first, count, rate, staggerMs) {
    const offset = output.length;
    command(`sqliteload start ${name} ${rate}`);
    await until(() => output.slice(offset).includes('LOAD_STARTED ' + name));
    const start = performance.now(), cohort = [];
    for (let i = 0; i < count; i++) {
        cohort.push(connect(first + i));
        if (staggerMs) await sleep(staggerMs);
    }
    await until(() => cohort.every(item => item.joined || item.ended), 60000);
    const expectedAllowed = cohort.filter(item => !item.expectedBanned).length;
    await until(() => samples.some(sample => sample.phase === name && sample.ready === expectedAllowed), 60000);
    const wrong = cohort.filter(item => item.expectedBanned ? item.joined : !item.joined || item.ended);
    if (wrong.length) throw Error('Incorrect login outcome: ' + wrong.map(item => item.name + ':' + item.endReason).join(','));
    const observer = cohort.find(item => item.joined && item.index % 10 === 3);
    const muted = cohort.find(item => item.joined && item.index % 10 === 1);
    const talker = cohort.find(item => item.joined && item.index % 10 === 4);
    const blockedMarker = 'SQLITE_BLOCKED_' + name, allowedMarker = 'SQLITE_ALLOWED_' + name;
    muted.client.chat(blockedMarker); talker.client.chat(allowedMarker);
    await until(() => observer.messages.some(message => message.includes(allowedMarker)), 15000);
    await sleep(500);
    if (observer.messages.some(message => message.includes(blockedMarker))) throw Error('Muted message leaked');
    await sleep(Math.max(0, 25000 - (performance.now() - start)));
    const paused = Date.now();
    command('sqliteload pause');
    await until(() => {
        const sample = samples.filter(sample => sample.phase === name).at(-1);
        return sample && sample.adminCompleted === sample.adminSubmitted && sample.epochMs >= paused;
    }, 30000);
    const elapsed = performance.now() - start;
    for (const item of cohort) if (!item.ended) item.client.end('load-phase-finished');
    await until(() => cohort.every(item => item.ended), 15000);
    command('sqliteload stop');
    await until(() => summaries.some(summary => summary.phase === name));
    phases.push({ name, clients: count, expectedBanned: count - expectedAllowed, joined: cohort.filter(item => item.joined).length,
        elapsedMs: elapsed, clientJoinMs: stats(cohort.filter(item => item.joined).map(item => item.joinMs)),
        chatMutedBlocked: true, chatAllowedDelivered: true, adminRatePerSecond: rate,
        cohort: cohort.map(({ index, name, expectedBanned, joined, joinMs, endReason, error }) => ({ index, name, expectedBanned, joined, joinMs, endReason, error })) });
    console.log('PAPER_PHASE ' + name + ' joined=' + expectedAllowed + ' banned=' + (count - expectedAllowed));
    await sleep(1000);
}

async function run() {
    server = spawn(java, ['-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dloadtest.playerBase=' + (process.env.BCLOAD_PLAYER_BASE || '80000'), '-XX:ActiveProcessorCount=2', '-Xms512M', '-Xmx1536M', '-jar', 'paper.jar', '--nogui'],
        { cwd: serverDir, windowsHide: true, env: process.env, stdio: ['pipe', 'pipe', 'pipe'] });
    let partial = '';
    for (const stream of [server.stdout, server.stderr]) stream.on('data', bytes => {
        const text = bytes.toString(); output += text; log.write(text);
        partial += text;
        const lines = partial.split(/\r?\n/); partial = lines.pop();
        for (const line of lines) for (const [marker, list] of [['LOAD_SAMPLE ', samples], ['LOAD_SUMMARY ', summaries]]) {
            const position = line.indexOf(marker);
            if (position >= 0) { try { list.push(JSON.parse(line.slice(position + marker.length))); } catch {} }
        }
    });
    await until(() => output.includes('Done (') && output.includes('LOAD_PROBE_READY'), 180000);
    if (output.includes('Error occurred while enabling')) throw Error('Plugin startup error');
    await sleep(5000);
    const plan = process.env.BCLOAD_PLAN ? JSON.parse(process.env.BCLOAD_PLAN) : [
        ['ramp20_staff2', 0, 20, 2, 100],
        ['burst100_staff10', 20, 100, 10, 0],
        ['burst200_staff25', 120, 200, 25, 0]
    ];
    for (const entry of plan) {
        await phase(...entry);
        const limit = Number(process.env.BCLOAD_MAX_PRELOGIN_MS || 0);
        if (limit && summaries.at(-1).preloginMs.p95 >= limit) {
            console.log('PAPER_RAMP_STOPPED prelogin p95 reached ' + limit + 'ms');
            break;
        }
    }
    if (/Could not pass event|NoSuchMethodError|NoSuchFieldError|LOAD_ADMIN_ERROR/.test(output)) throw Error('Runtime error in server log');
    command('stop');
    const deadline = performance.now() + 45000;
    while (server.exitCode === null && performance.now() < deadline) await sleep(100);
    if (server.exitCode === null) throw Error('Server did not stop');
    fs.writeFileSync(path.join(outputDir, 'paper-results.json'), JSON.stringify({ completed: true, version: '1.21.11', phases, summaries, samples }, null, 2));
    console.log('PAPER_COMPLETE ' + outputDir);
}

run().catch(async error => {
    console.error(error.stack);
    fs.writeFileSync(path.join(outputDir, 'paper-results.json'), JSON.stringify({ completed: false, error: error.message, phases, summaries, samples }, null, 2));
    clients.forEach(item => { if (!item.ended) item.client.end(); });
    if (server && server.exitCode === null) {
        command('stop');
        const deadline = performance.now() + 30000;
        while (server.exitCode === null && performance.now() < deadline) await sleep(100);
        if (server.exitCode === null) server.kill();
    }
    process.exitCode = 1;
}).finally(() => log.end());
