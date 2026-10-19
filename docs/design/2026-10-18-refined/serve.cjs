const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const prefix = '/docs/design/2026-10-18-refined/';
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.ttf': 'font/ttf' };
const allowed = ['/docs/design/2026-10-18-refined/', '/docs/design/2026-10-16-agent-desktop/', '/erp-web/src/assets/fonts/', '/erp-web/src/assets/brand/'];
http.createServer((req, res) => {
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); } catch { res.writeHead(400).end(); return; }
  if (pathname === '/') { res.writeHead(302, { Location: prefix }).end(); return; }
  if (!allowed.some(item => pathname.startsWith(item))) { res.writeHead(404).end(); return; }
  const target = path.resolve(root, '.' + pathname + (pathname.endsWith('/') ? 'index.html' : ''));
  if (!target.startsWith(root + path.sep) || !types[path.extname(target)]) { res.writeHead(403).end(); return; }
  fs.stat(target, (error, stat) => {
    if (error || !stat.isFile()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(target)], 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
    fs.createReadStream(target).pipe(res);
  });
}).listen(5191, '127.0.0.1', () => console.log('http://127.0.0.1:5191' + prefix));
