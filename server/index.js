#!/usr/bin/env node
/**
 * سيرفر المحتوى + لوحة التحكم — بدون أي تبعيات خارجية.
 *
 *   node server/index.js            → http://localhost:8787
 *   ADMIN_TOKEN=... PORT=... node server/index.js
 *
 * الملفات تُخزَّن في server/data/*.json، وتُزرع مرة واحدة من content/*.json.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || 8787);
const TOKEN = process.env.ADMIN_TOKEN || 'dev-token';
const HOST = process.env.HOST || (process.env.ADMIN_TOKEN ? '0.0.0.0' : '127.0.0.1');
if (!['127.0.0.1', 'localhost', '::1'].includes(HOST) && TOKEN === 'dev-token') {
  throw new Error('ADMIN_TOKEN مستقل مطلوب قبل إتاحة السيرفر على الشبكة');
}
const ROOT = path.join(__dirname, '..');
const SEED_DIR = path.join(ROOT, 'content');
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DEVICES_DIR = path.join(DATA_DIR, 'devices');
const PUBLIC_DIR = path.join(__dirname, 'public');
const VERSION_FILE = path.join(DATA_DIR, '.version');

const KEYS = ['program', 'courses', 'tracks', 'projects', 'resources'];
const VERSION = process.env.CONTENT_VERSION || readIf(path.join(SEED_DIR, '..', 'package.json'), (f) => JSON.parse(f).version) || '1.0.0';

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(DEVICES_DIR, { recursive: true });
for (const key of KEYS) {
  const target = path.join(DATA_DIR, `${key}.json`);
  const seed = path.join(SEED_DIR, `${key}.json`);
  if (!fs.existsSync(target) && fs.existsSync(seed)) fs.copyFileSync(seed, target);
}

function readIf(file, map) {
  try {
    return map(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function dataVersion() {
  const stat = KEYS.map((k) => {
    try {
      return fs.statSync(path.join(DATA_DIR, `${k}.json`)).mtimeMs;
    } catch {
      return 0;
    }
  });
  const max = Math.max(...stat, 0);
  return `${VERSION}-server-${new Date(max || Date.now()).toISOString().slice(0, 16).replace(/[:T]/g, '')}`;
}

function loadBundle() {
  const out = { version: dataVersion(), source: 'server', fetchedAt: Date.now(), updatedAt: readUpdatedAt() };
  for (const key of KEYS) {
    const parsed = readIf(path.join(DATA_DIR, `${key}.json`), (raw) => JSON.parse(raw));
    out[key] = parsed === null ? (key === 'program' ? {} : []) : parsed;
  }
  return out;
}

function readUpdatedAt() {
  try {
    return fs.readFileSync(VERSION_FILE, 'utf8').trim();
  } catch {
    return String(Date.now());
  }
}

function touchUpdatedAt() {
  fs.writeFileSync(VERSION_FILE, String(Date.now()));
}

function validate(key, value) {
  if (key === 'courses') {
    if (!Array.isArray(value)) return 'courses يجب أن يكون مصفوفة';
    const ids = new Set();
    for (const c of value) {
      if (typeof c.id !== 'string' || !c.id) return 'كل مقرر يحتاج id نصي';
      if (ids.has(c.id)) return `مكرر: ${c.id}`;
      ids.add(c.id);
      if (typeof c.name !== 'string' || !c.name) return `${c.id}: الاسم مطلوب`;
      if (typeof c.credits !== 'number' || c.credits < 0) return `${c.id}: credits رقم موجب`;
      if (typeof c.semester !== 'number') return `${c.id}: semester رقم`;
      if (c.prereqs && !Array.isArray(c.prereqs)) return `${c.id}: prereqs مصفوفة`;
    }
    for (const c of value) {
      for (const p of c.prereqs ?? []) {
        if (!ids.has(p)) return `${c.id}: متطلب غير موجود (${p})`;
        if (p === c.id) return `${c.id}: لا يمكن أن يكون متطلباً لنفسه`;
      }
    }
    return null;
  }
  if (key === 'program') {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return 'program يجب أن يكون كائناً';
    if (!value.gpaScales || typeof value.gpaScales !== 'object') return 'program.gpaScales مطلوب';
    for (const [scaleKey, scale] of Object.entries(value.gpaScales)) {
      if (!Array.isArray(scale.grades) || scale.grades.length === 0) return `${scaleKey}: grades مصفوفة غير فارغة`;
    }
    if (typeof value.totalCreditHours !== 'number') return 'totalCreditHours رقم';
    return null;
  }
  if (!Array.isArray(value)) return `${key} يجب أن يكون مصفوفة`;
  return null;
}

function json(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization',
    'Cache-Control': 'no-store',
  });
  res.end(text);
}

function authorized(req) {
  const header = req.headers.authorization || '';
  return header.replace(/^Bearer\s+/i, '').trim() === TOKEN;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    let oversized = false;
    req.on('data', (chunk) => {
      if (oversized) return;
      bytes += chunk.length;
      if (bytes > 4 * 1024 * 1024) {
        oversized = true;
        chunks.length = 0;
        return reject(Object.assign(new Error('الحجم أكبر من 4MB'), { statusCode: 413 }));
      }
      chunks.push(chunk);
    });
    req.on('end', () => { if (!oversized) resolve(Buffer.concat(chunks).toString('utf8')); });
    req.on('error', reject);
  });
}

function sanitizeId(value) {
  return String(value).replace(/[^A-Za-z0-9._-]/g, '').slice(0, 64);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

const server = http.createServer(async (req, res) => {
  let url;
  try {
    // Routing needs only the path; an untrusted Host header must not crash URL parsing.
    url = new URL(req.url, 'http://localhost');
  } catch {
    return json(res, 400, { error: 'عنوان طلب غير صالح' });
  }
  const parts = url.pathname.split('/').filter(Boolean);

  if (req.method === 'OPTIONS') return json(res, 200, { ok: true });

  try {
    if (url.pathname === '/api/health') {
      return json(res, 200, { ok: true, version: VERSION, contentVersion: dataVersion(), keys: KEYS });
    }

    if (url.pathname === '/api/content' && req.method === 'GET') {
      return json(res, 200, loadBundle());
    }

    if (parts[0] === 'api' && parts[1] === 'content' && parts[2]) {
      const key = parts[2].replace(/\.json$/, '');
      if (!KEYS.includes(key)) return json(res, 404, { error: `مفتاح غير معروف: ${key}` });
      const file = path.join(DATA_DIR, `${key}.json`);
      if (req.method === 'GET') {
        const data = readIf(file, (raw) => JSON.parse(raw));
        return json(res, 200, { key, data });
      }
      if (req.method === 'PUT') {
        if (!authorized(req)) return json(res, 401, { error: 'رمز الوصول غير صحيح' });
        const raw = await readBody(req);
        let value;
        try {
          value = JSON.parse(raw);
        } catch (e) {
          return json(res, 400, { error: `JSON غير صالح: ${e.message}` });
        }
        const err = validate(key, value);
        if (err) return json(res, 400, { error: err });
        fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
        touchUpdatedAt();
        return json(res, 200, { ok: true, key, bytes: Buffer.byteLength(raw), contentVersion: dataVersion() });
      }
    }

    if (parts[0] === 'api' && parts[1] === 'devices') {
      if (!authorized(req)) return json(res, 401, { error: 'رمز الوصول مطلوب لبيانات الأجهزة' });
      const id = sanitizeId(parts[2] || '');
      if (req.method === 'GET' && id) {
        const data = readIf(path.join(DEVICES_DIR, `${id}.json`), (raw) => JSON.parse(raw));
        return json(res, 200, { device: id, state: data });
      }
      if (req.method === 'PUT' && id) {
        const state = JSON.parse(await readBody(req));
        fs.writeFileSync(
          path.join(DEVICES_DIR, `${id}.json`),
          JSON.stringify({ savedAt: Date.now(), ...state }, null, 2),
        );
        return json(res, 200, { ok: true, device: id });
      }
      const list = fs.readdirSync(DEVICES_DIR).filter((f) => f.endsWith('.json'));
      return json(res, 200, { devices: list.map((f) => f.replace(/\.json$/, '')) });
    }

    if (url.pathname === '/' || url.pathname === '/admin') {
      const file = path.join(PUBLIC_DIR, 'admin.html');
      res.writeHead(200, { 'Content-Type': MIME['.html'] });
      return res.end(fs.readFileSync(file));
    }

    const staticFile = path.join(PUBLIC_DIR, path.normalize(url.pathname).replace(/^(\.\.[/\\])+/, ''));
    if (staticFile.startsWith(PUBLIC_DIR) && fs.existsSync(staticFile) && fs.statSync(staticFile).isFile()) {
      res.writeHead(200, { 'Content-Type': MIME[path.extname(staticFile)] || 'application/octet-stream' });
      return res.end(fs.readFileSync(staticFile));
    }

    return json(res, 404, { error: 'not found', hint: 'استخدم /admin للوحة التحكم و /api/content للتطبيق' });
  } catch (err) {
    return json(res, err.statusCode || 500, { error: err.message || String(err) });
  }
});

server.listen(PORT, HOST, () => {
  const boundPort = server.address().port;
  console.log(`🛰  سيرفر المحتوى يعمل على http://localhost:${boundPort}`);
  console.log(`   لوحة التحكم: http://localhost:${boundPort}/admin`);
  console.log('   رمز الوصول مُعدّ؛ لا يُطبع في السجل');
});
