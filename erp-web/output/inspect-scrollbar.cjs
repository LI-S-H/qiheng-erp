const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await page.goto('http://127.0.0.1:5173/warehouse/inbound-bills', { waitUntil: 'domcontentloaded' });
  if (page.url().includes('/login')) {
    await page.getByLabel('登录账号').fill('admin');
    await page.getByLabel('登录密码').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => !url.pathname.includes('/login'));
    await page.goto('http://127.0.0.1:5173/warehouse/inbound-bills', { waitUntil: 'domcontentloaded' });
  }
  await page.getByText('入库单列表').waitFor();
  const data = await page.locator('.stock-bill-table-scroll').first().evaluate(el => ({
    html: el.outerHTML.slice(0, 2000),
    scrollbars: Array.from(el.querySelectorAll('[data-slot="scroll-area-scrollbar"]')).map(x => ({ attr: x.getAttribute('data-orientation'), cls: x.className, rect: x.getBoundingClientRect().toJSON?.() }))
  }));
  console.log(JSON.stringify(data, null, 2));
  await browser.close();
})();
