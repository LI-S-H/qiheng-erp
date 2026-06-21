const { chromium } = require('playwright');
async function login(page, route) {
  await page.goto(`http://127.0.0.1:5173${route}`, { waitUntil: 'domcontentloaded' });
  if (page.url().includes('/login')) {
    await page.getByLabel('登录账号').fill('admin');
    await page.getByLabel('登录密码').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => !url.pathname.includes('/login'));
    await page.goto(`http://127.0.0.1:5173${route}`, { waitUntil: 'domcontentloaded' });
  }
}
async function capture(route, billNo, file) {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await login(page, route);
  await page.getByText(route.includes('inbound') ? '入库单列表' : '出库单列表').waitFor();
  await page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first().evaluate(el => { el.scrollLeft = el.scrollWidth; });
  await page.waitForTimeout(300);
  const rowState = await page.getByRole('row').filter({ hasText: billNo }).first().evaluate(el => {
    const cells = Array.from(el.children);
    const createdAt = cells[10];
    const actions = cells[11];
    return {
      createdAt: createdAt.innerText,
      actions: actions.innerText,
      createdAtOverflow: createdAt.scrollWidth - createdAt.clientWidth,
      actionsOverflow: actions.scrollWidth - actions.clientWidth,
      createdAtRight: createdAt.getBoundingClientRect().right,
      actionsLeft: actions.getBoundingClientRect().left,
    };
  });
  console.log(file, JSON.stringify(rowState));
  await page.screenshot({ path: file, fullPage: true });
  await browser.close();
}
(async () => {
  await capture('/warehouse/inbound-bills', 'IB202606140001', 'smoke-warehouse-inbound-right.png');
  await capture('/warehouse/outbound-bills', 'OB202606140002', 'smoke-warehouse-outbound-right.png');
})();
