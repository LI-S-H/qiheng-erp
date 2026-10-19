const fs = require('node:fs');
const path = require('node:path');
const read = name => fs.readFileSync(path.join(__dirname, name), 'utf8');
const sprite = read('icons.svg').replace('<svg ', '<svg style="position:absolute;width:0;height:0;overflow:hidden" aria-hidden="true" ');
const previewImages = {};
for (const name of ['dashboard-current','assistant-current','dashboard-preview','assistant-preview']) {
  const file = path.join(__dirname, `${name}.png`);
  if(fs.existsSync(file))previewImages[name]=`data:image/png;base64,${fs.readFileSync(file).toString('base64')}`;
}
let html = read('index.html')
  .replace('<link rel="stylesheet" href="styles.css" />', () => `<style>${read('styles.css')}</style>`)
  .replace('<link rel="stylesheet" href="refinements.css" />', () => `<style>${read('refinements.css')}</style>`)
  .replace('<body>', `<body>${sprite}`)
  .replace('src="logo.png"', `src="data:image/png;base64,${fs.readFileSync(path.join(__dirname, 'logo.png')).toString('base64')}"`)
  .replace('<script src="app.js"></script>', () => `<script>const previewImages=${JSON.stringify(previewImages)};\n${read('app.js').replaceAll('icons.svg#', '#').replaceAll('${key}-current.png', '${previewImages[key+\'-current\']}').replaceAll('${key}-preview.png', '${previewImages[key+\'-preview\']}')}</script>`)
  .replace('<script src="refinements.js"></script>', () => `<script>${read('refinements.js').replaceAll('icons.svg#', '#')}</script>`);
for (const name of ['dashboard-current','assistant-current','dashboard-preview','assistant-preview']) {
  const file = path.join(__dirname, `${name}.png`);
  if (fs.existsSync(file)) html = html.replaceAll(`${name}.png`, `data:image/png;base64,${fs.readFileSync(file).toString('base64')}`);
}
fs.writeFileSync(path.join(__dirname, 'preview.html'), html);
console.log('独立画布已生成，无需本地服务。');
