const { chromium } = require('../../../erp-web/node_modules/playwright');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    const target = fs.readFileSync(path.join(__dirname, 'target.png'));
    const width = target.readUInt32BE(16), height = target.readUInt32BE(20);
    const base = 'http://127.0.0.1:5191/docs/design/2026-10-18-refined/';
    const page = await browser.newPage({ viewport: { width, height }, reducedMotion: 'reduce' });
    await page.goto(base);
    await page.locator('tbody tr').first().waitFor();
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(__dirname, 'matched-purchase.png') });
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.setContent(`<html lang="zh-CN"><head><meta charset="utf-8"><style>body{margin:0;padding:16px;background:#e9edf3;font:14px/1.5 'Microsoft YaHei',sans-serif}.compare{display:grid;grid-template-columns:1fr 1fr;gap:16px}figure{margin:0}figcaption{padding:0 0 12px;color:#35445c}img{display:block;width:100%;height:auto;background:white}</style></head><body><div class="compare"><figure><figcaption>精修概念（视觉意向）</figcaption><img src="${base}target.png"></figure><figure><figcaption>真实实现（相同尺寸与初始数据）</figcaption><img src="${base}matched-purchase.png"></figure></div></body></html>`);
    await page.locator('img').evaluateAll(images => Promise.all(images.map(image => image.decode())));
    await page.screenshot({ path: path.join(__dirname, 'comparison.png'), fullPage: true });
    console.log(JSON.stringify({ source: { width, height }, implementation: { width, height }, comparison: 'comparison.png' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
