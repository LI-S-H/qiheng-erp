const { chromium } = require('../../../erp-web/node_modules/playwright');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto('http://127.0.0.1:5189/login');
    await page.locator('#username').fill('admin');
    await page.locator('#password').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL('**/dashboard');
    const data = await page.evaluate(async () => {
      const purchase = await import('/src/modules/purchase/api.ts');
      const stock = await import('/src/modules/warehouse/stocks/api.ts');
      return {
        purchase: await purchase.listPurchaseOrders({ pageNum: 1, pageSize: 100 }),
        stock: await stock.listWarehouseStocks({ pageNum: 1, pageSize: 100 }),
      };
    });
    fs.writeFileSync(path.join(__dirname, 'data.json'), JSON.stringify(data, null, 2));
    console.log(JSON.stringify({ purchaseKeys: Object.keys(data.purchase), purchase: data.purchase.records[0], stock: data.stock.records[0] }));
    const pages = {};
    for (const [key, route] of [['purchase', '/purchase/orders'], ['stock', '/warehouse/stocks']]) {
      await page.goto('http://127.0.0.1:5189' + route);
      await page.waitForLoadState('networkidle');
      pages[key] = await page.evaluate(() => ({
        text: document.querySelector('main').innerText,
        columns: [...document.querySelectorAll('main table thead th')].map(el => el.innerText),
        rows: [...document.querySelectorAll('main table tbody tr')].map(row => [...row.querySelectorAll('td')].map(el => el.innerText)),
      }));
    }
    fs.writeFileSync(path.join(__dirname, 'source-pages.json'), JSON.stringify(pages, null, 2));
    await page.goto('http://127.0.0.1:5188/docs/design/2026-10-16-agent-desktop/#purchase');
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(__dirname, 'reference-purchase.png') });
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
