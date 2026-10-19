// 独立方案预览只开放方案文件与已有品牌资源，不对外暴露整个仓库。
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const base = __dirname;
const repo = path.resolve(base, '../../..');
const prefix = '/docs/design/2026-10-16-agent-desktop/';
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.ttf': 'font/ttf', '.md': 'text/plain; charset=utf-8', '.txt': 'text/plain; charset=utf-8' };
http.createServer((req, res) => {
  let pathname;
  try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); }
  catch { res.writeHead(400).end(); return; }
  if (pathname === '/') { res.writeHead(302, { Location: prefix }).end(); return; }
  let target;
  if (pathname.startsWith(prefix)) {
    target = path.resolve(base, pathname.slice(prefix.length) || 'index.html');
    if (!target.startsWith(base + path.sep)) { res.writeHead(403).end(); return; }
  } else if (/^\/erp-web\/src\/assets\/(?:fonts\/HarmonyOS_Sans_SC_(?:Regular|Medium|Semibold)\.ttf|brand\/yuncang-logo\.png)$/.test(pathname)) {
    target = path.join(repo, pathname);
  } else { res.writeHead(404).end(); return; }
  const type = types[path.extname(target)];
  if (!type) { res.writeHead(404).end(); return; }
  fs.stat(target, (error, stat) => {
    if (error || !stat.isFile()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': stat.size, 'Cache-Control': 'no-cache', 'X-Content-Type-Options': 'nosniff' });
    fs.createReadStream(target).pipe(res);
  });
}).listen(5188, '127.0.0.1', () => console.log('方案演示：http://127.0.0.1:5188' + prefix));
