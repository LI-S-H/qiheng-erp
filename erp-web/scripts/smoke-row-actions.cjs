const { chromium } = require('playwright');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5300';

async function loginIfNeeded(page) {
  if (!page.url().includes('/login')) return;
  await page.locator('#username').fill('admin');
  await page.locator('input[type="password"]').fill('123456');
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => !window.location.pathname.includes('/login'));
}

async function assertRowActionMenu(page, route) {
  console.log(`检查 ${route}`);
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await page.locator('[data-row-actions-trigger]').first().waitFor();

  const trigger = page.locator('[data-row-actions-trigger]').first();
  if ((await trigger.innerText()).trim() !== '更多') {
    throw new Error(`${route} 的行菜单触发文字不是“更多”`);
  }
  if (await trigger.getAttribute('title')) {
    throw new Error(`${route} 的“更多”仍保留原生悬停提示`);
  }

  await trigger.hover();
  if (await page.locator('[data-row-actions-tooltip]').count()) {
    throw new Error(`${route} 的“更多”仍渲染自定义悬停提示`);
  }

  await trigger.click();
  const menu = page.getByRole('menu').last();
  await menu.waitFor({ state: 'visible' });
  if (await menu.getByRole('menuitem').count() < 1) {
    throw new Error(`${route} 的“更多”菜单没有操作项`);
  }
  await page.keyboard.press('Escape');
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(10_000);

  try {
    await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'domcontentloaded' });
    await loginIfNeeded(page);

    const rowActionRoutes = [
      '/product/products',
      '/product/categories',
      '/system/users',
      '/system/roles',
      '/system/depts',
      '/system/permissions',
      '/warehouse/warehouses',
      '/sales/customers',
      '/purchase/suppliers',
      '/purchase/supplier-products',
      '/ai/tasks',
      '/ai/assistant',
    ];
    for (const route of rowActionRoutes) {
      await assertRowActionMenu(page, route);
    }

    await page.goto(`${baseUrl}/dashboard`, { waitUntil: 'domcontentloaded' });
    await page.locator('.dashboard-todo').first().waitFor();
    await page.locator('.dashboard-todo').first().click();
    await page.getByRole('dialog').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'hidden' });

    console.log('ROW_ACTIONS_SMOKE_OK');
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
