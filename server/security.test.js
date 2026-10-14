const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'mashwari-server-audit-'));
const token = crypto.randomBytes(24).toString('hex');
let child, base, output = '';
before(async () => {
  child = spawn(process.execPath, [path.join(__dirname, 'index.js')], {
    env: { ...process.env, ADMIN_TOKEN: token, HOST: '127.0.0.1', PORT: '0', DATA_DIR: directory },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Server startup timeout')), 10000);
    child.once('error', reject);
    child.once('exit', code => reject(new Error(`Server exited ${code}`)));
    child.stdout.on('data', bytes => {
      output += bytes.toString();
      const match = output.match(/http:\/\/localhost:(\d+)/);
      if (match) { base = `http://127.0.0.1:${match[1]}`; clearTimeout(timeout); resolve(); }
    });
  });
});
after(async () => {
  if (child && child.exitCode === null) await new Promise(resolve => { child.once('exit', resolve); child.kill(); });
  fs.rmSync(directory, { recursive: true, force: true });
});

test('private device data rejects anonymous requests and preserves authenticated operation', async () => {
  assert.equal((await fetch(`${base}/api/devices`)).status, 401);
  assert.equal((await fetch(`${base}/api/devices/sample`, { method: 'PUT', body: '{}' })).status, 401);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  assert.equal((await fetch(`${base}/api/devices/sample`, { method: 'PUT', headers, body: '{"sample":true}' })).status, 200);
  const saved = await (await fetch(`${base}/api/devices/sample`, { headers })).json();
  assert.equal(saved.state.sample, true);
  assert.equal((await fetch(`${base}/api/content`)).status, 200);
  assert.ok(!output.includes(token));
});

test('oversized request fails before retaining an unbounded body', async () => {
  const response = await fetch(`${base}/api/devices/sample`, {
    method: 'PUT', headers: { Authorization: `Bearer ${token}` }, body: 'x'.repeat(4 * 1024 * 1024 + 1),
  });
  assert.equal(response.status, 413);
});

test('malformed Host header cannot terminate the server', async () => {
  const response = await new Promise((resolve, reject) => {
    require('node:http').get(`${base}/api/health`, { headers: { Host: 'invalid%' } }, result => {
      result.resume(); result.on('end', () => resolve(result.statusCode));
    }).on('error', reject);
  });
  assert.equal(response, 200);
  assert.equal((await fetch(`${base}/api/health`)).status, 200);
});

test('known development token cannot expose the server on all network interfaces', () => {
  const result = spawnSync(process.execPath, [path.join(__dirname, 'index.js')], {
    env: { ...process.env, ADMIN_TOKEN: '', HOST: '0.0.0.0', PORT: '0', DATA_DIR: directory }, encoding: 'utf8',
  });
  assert.notEqual(result.status, 0);
  assert.ok(result.stderr.includes('ADMIN_TOKEN'));
});
