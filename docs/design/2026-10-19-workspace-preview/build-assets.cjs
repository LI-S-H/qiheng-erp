const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const { h } = require(path.join(root, 'erp-web/node_modules/vue'));
const { renderToString } = require(path.join(root, 'erp-web/node_modules/@vue/server-renderer'));
const icons = require(path.join(root, 'erp-web/node_modules/lucide-vue-next'));
const names = ['LayoutDashboard','Settings','Package','Boxes','ShoppingCart','FileText','MessageSquare','ChevronDown','ChevronRight','ArrowRight','ArrowUpRight','ArrowDownRight','Bell','RefreshCw','X','Check','Plus','SquarePen','PanelLeftClose','PanelLeftOpen','PanelRightClose','PanelRightOpen','ClipboardList','Database','Code2','Send','Menu','MoreHorizontal','Clock3','Info','TriangleAlert','ArrowLeft','Search','SlidersHorizontal','ShieldCheck','TrendingUp','Truck','ChartNoAxesCombined','Copy','CircleHelp'];
(async () => {
  const symbols = [];
  for (const name of names) {
    const svg = await renderToString(h(icons[name]));
    symbols.push(`<symbol id="${name}" viewBox="0 0 24 24">${svg.slice(svg.indexOf('>') + 1, svg.lastIndexOf('</svg>'))}</symbol>`);
  }
  fs.writeFileSync(path.join(__dirname, 'icons.svg'), `<svg xmlns="http://www.w3.org/2000/svg"><!-- Lucide icons, ISC license. Reused from the project's existing lucide-vue-next dependency. -->${symbols.join('')}</svg>`);
  fs.copyFileSync(path.join(root, 'erp-web/node_modules/lucide-vue-next/LICENSE'), path.join(__dirname, 'LUCIDE-LICENSE.txt'));
  fs.copyFileSync(path.join(root, 'erp-web/public/yuncang-logo.png'), path.join(__dirname, 'logo.png'));
})().catch(error => { console.error(error); process.exitCode = 1; });
