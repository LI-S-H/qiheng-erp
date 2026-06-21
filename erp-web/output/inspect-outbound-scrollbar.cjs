const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto('http://127.0.0.1:5173/warehouse/outbound-bills', { waitUntil: 'domcontentloaded' });
  if (page.url().includes('/login')) {
    await page.getByLabel('登录账号').fill('admin');
    await page.getByLabel('登录密码').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => !url.pathname.includes('/login'));
    await page.goto('http://127.0.0.1:5173/warehouse/outbound-bills', { waitUntil: 'domcontentloaded' });
  }
  await page.getByText('出库单列表').waitFor();
  console.log(await page.locator('.stock-bill-table-scroll').count());
  console.log(await page.locator('.stock-bill-table-scroll [data-orientation="horizontal"]').count());
  console.log(await page.locator('[data-slot="scroll-area-scrollbar"]').evaluateAll(els => els.map(e => ({attr:e.getAttribute('data-orientation'), cls:e.className, text:e.outerHTML.slice(0,200)}))));
  await browser.close();
})();
