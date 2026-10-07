const { chromium } = require('playwright');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5300';

async function loginIfNeeded(page) {
  if (!page.url().includes('/login')) return;
  await page.locator('#username').fill('admin');
  await page.locator('input[type="password"]').fill('123456');
  await page.locator('button[type="submit"]').click();
  await page.waitForFunction(() => !window.location.pathname.includes('/login'));
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.setDefaultTimeout(8_000);
  try {
    await page.goto(`${baseUrl}/purchase/supplier-products`, { waitUntil: 'domcontentloaded' });
    await loginIfNeeded(page);
    await page.goto(`${baseUrl}/purchase/supplier-products`, { waitUntil: 'domcontentloaded' });
    await page.locator('[data-row-actions-trigger]').first().waitFor();

    const supplierProductActionsTrigger = page.locator('[data-row-actions-trigger]').first();
    await supplierProductActionsTrigger.hover();
    const supplierProductActionsTooltip = page.locator('[role="tooltip"]').last();
    await supplierProductActionsTooltip.waitFor({ state: 'visible' });
    if (!(await supplierProductActionsTooltip.innerText()).startsWith('更多操作：')) {
      throw new Error('“更多”悬停提示文案错误');
    }

    await supplierProductActionsTrigger.click();
    const menu = page.getByRole('menu');
    await menu.waitFor();
    if (await menu.getByRole('menuitem').count() < 3) {
      throw new Error('供货关系“更多”菜单没有完整显示操作项');
    }
    await page.keyboard.press('Escape');

    await page.locator('button').filter({ hasText: '查看' }).first().click();
    await page.getByRole('dialog').waitFor();
    await page.waitForTimeout(250);
    if (await page.locator('[data-page-loading]').isVisible()) {
      throw new Error('打开详情时错误触发了整页加载遮罩');
    }
    await page.getByRole('dialog').getByRole('button', { name: '关闭', exact: true }).click();

    await page.getByRole('button', { name: '新增供货关系', exact: true }).click();
    const createDialog = page.getByRole('dialog', { name: '新增供货关系' });
    const productSelector = createDialog.getByRole('combobox').nth(1);
    await productSelector.click();
    const productOption = page.locator('[data-remote-search-select-content] [data-select-option]').first();
    await productOption.waitFor();
    const productOptionText = (await productOption.innerText()).trim();
    await productOption.click();
    const productCode = productOptionText.split(/\s+/)[0];
    const codeInput = createDialog.locator('input[readonly]');
    if (await codeInput.inputValue() !== productCode) {
      throw new Error(`产品选择后未自动带出编码：期望 ${productCode}，实际 ${await codeInput.inputValue()}`);
    }
    await createDialog.getByRole('button', { name: '取消', exact: true }).click();

    await page.goto(`${baseUrl}/purchase/suppliers`, { waitUntil: 'domcontentloaded' });
    await page.locator('[data-row-actions-trigger]').first().waitFor();
    await page.locator('[data-row-actions-trigger]').first().click();
    if (await page.getByRole('menu').getByRole('menuitem').count() < 3) {
      throw new Error('供应商“更多”菜单没有完整显示操作项');
    }
    console.log('SUPPLIER_CURRENT_SMOKE_OK');
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
