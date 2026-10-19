// 从仓库既有 Lucide 包提取官方图形节点，避免原型自绘出另一套图标。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = path.resolve(__dirname, '../../../erp-web/node_modules/lucide-vue-next/dist/esm/icons');
const names = { home: 'house', sparkles: 'sparkles', shopping: 'shopping-cart', boxes: 'boxes', layout: 'panels-top-left', palette: 'palette', settings: 'settings', search: 'search', chevrons: 'chevrons-up-down', bell: 'bell', menu: 'menu', arrow: 'arrow-right', right: 'chevron-right', left: 'chevron-left', up: 'arrow-up', close: 'x', clock: 'clock', check: 'check', file: 'file-text', chart: 'chart-no-axes-combined', info: 'info', download: 'download', external: 'external-link', warning: 'triangle-alert', inbox: 'inbox', refresh: 'refresh-cw', panel: 'panel-right', calendar: 'calendar', plus: 'plus' };
const paths = {};
for (const [name, file] of Object.entries(names)) {
  const code = fs.readFileSync(path.join(source, file + '.js'), 'utf8');
  const call = code.match(/createLucideIcon\([\s\S]*?\);/)?.[0];
  if (!call) throw new Error('未找到 Lucide 图形节点：' + file);
  const nodes = vm.runInNewContext(call, { createLucideIcon: (_name, value) => value });
  paths[name] = nodes.map(([tag, attributes]) => '<' + tag + ' ' + Object.entries(attributes).filter(([key]) => key !== 'key').map(([key, value]) => `${key}="${String(value).replace(/&/g, '&amp;').replace(/"/g, '&quot;')}"`).join(' ') + '/>').join('');
}
fs.writeFileSync(path.join(__dirname, 'icons.js'), '/* Lucide v0.577.0，ISC，图形来源见 ICONS-LICENSE.txt；由 build-icons.cjs 生成。 */\nwindow.YuncangIcons = ' + JSON.stringify(paths) + ';\n');
fs.copyFileSync(path.resolve(source, '../../../LICENSE'), path.join(__dirname, 'ICONS-LICENSE.txt'));
console.log('已提取 ' + Object.keys(paths).length + ' 个现有 Lucide 图标');
