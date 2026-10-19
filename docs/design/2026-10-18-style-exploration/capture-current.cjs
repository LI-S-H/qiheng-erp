const { chromium } = require('../../../erp-web/node_modules/playwright');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  fs.mkdirSync(__dirname, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1024 }, deviceScaleFactor: 1 });
    await page.goto('http://127.0.0.1:5189/login');
    await page.locator('#username').fill('admin');
    await page.locator('#password').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL('**/dashboard');
    for (const [route, name] of [
      ['/dashboard', 'current-dashboard'],
      ['/purchase/orders', 'current-purchase-orders'],
      ['/warehouse/stocks', 'current-stock'],
    ]) {
      await page.goto('http://127.0.0.1:5189' + route);
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(350);
      await page.screenshot({ path: path.join(__dirname, name + '.png'), fullPage: false });
      console.log(name + ' ' + page.url());
      if (name === 'current-purchase-orders') {
        fs.writeFileSync(path.join(__dirname, 'current-purchase-orders.txt'), await page.locator('body').innerText());
      }
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
