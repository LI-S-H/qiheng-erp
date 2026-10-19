const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../..');
const ts = require(path.join(root, 'erp-web/node_modules/typescript'));
// 原样使用项目既有示例契约；不创建业务数据，也不请求服务。
const source = fs.readFileSync(path.join(root, 'erp-web/src/modules/dashboard/api.ts'), 'utf8');
const block = source.slice(source.indexOf('function formatMockDateTime'), source.indexOf('function normalizeMetric'));
const js = ts.transpile(block, { target: ts.ScriptTarget.ES2022 });
const data = vm.runInNewContext(`${js}\nJSON.stringify({overview:mockOverview,inventory:mockInventoryStatus})`, { Date });
let html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const sprite = fs.readFileSync(path.join(root, 'docs/design/2026-10-19-workspace-preview/icons.svg'), 'utf8');
const logo = fs.readFileSync(path.join(root, 'docs/design/2026-10-19-workspace-preview/logo.png')).toString('base64');
html = html.replace('<!-- SPRITE -->', sprite.replace('<svg ', '<svg style="display:none" '))
  .replace('<!-- STYLE -->', () => `<style>${fs.readFileSync(path.join(__dirname, 'style.css'), 'utf8')}</style>`)
  .replace('<!-- APP -->', () => `<script>const fixture=${data};\n${fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8')}</script>`)
  .replace('LOGO_URL', `data:image/png;base64,${logo}`);
fs.writeFileSync(path.join(__dirname, 'preview.html'), html);
console.log('ERP工作台独立预览生成完成，沿用现有接口示例。');
