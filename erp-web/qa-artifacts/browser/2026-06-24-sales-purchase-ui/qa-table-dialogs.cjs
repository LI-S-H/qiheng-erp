const path = require('path');
const { chromium } = require('playwright');

const baseUrl = 'http://127.0.0.1:5173';
const outDir = __dirname;

async function loginIfNeeded(page, route) {
  if (!page.url().includes('/login')) return;
  await page.locator('input').nth(0).fill('admin');
  await page.locator('input').nth(1).fill('123456');
  await page.locator('button').filter({ hasText: /登录|登錄|Login/i }).first().click();
  await page.waitForURL(url => url.pathname !== '/login', { timeout: 10000 });
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
}

async function openRoute(page, route) {
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await loginIfNeeded(page, route);
  await page.locator('.business-data-table').first().waitFor({ timeout: 10000 });
  await page.waitForTimeout(300);
}

async function assertCenteredBusinessTable(page, expectedColumns) {
  const state = await page.locator('.business-data-table').first().evaluate((table, columns) => {
    const heads = [...table.querySelectorAll('[data-slot="table-head"]')];
    const cells = [...table.querySelectorAll('tbody tr:not([data-state]) [data-slot="table-cell"]')].slice(0, columns);
    return {
      tableLayout: getComputedStyle(table).tableLayout,
      columnCount: table.querySelector('colgroup')?.querySelectorAll('col').length ?? 0,
      headAlign: heads.map(item => getComputedStyle(item).textAlign),
      cellAlign: cells.map(item => getComputedStyle(item).textAlign),
      minWidth: getComputedStyle(table).minWidth,
    };
  }, expectedColumns);

  if (state.tableLayout !== 'fixed' || state.columnCount !== expectedColumns) {
    throw new Error(`表格固定列宽异常: ${JSON.stringify(state)}`);
  }
  if (![...state.headAlign, ...state.cellAlign].every(value => value === 'center')) {
    throw new Error(`表格未全部居中: ${JSON.stringify(state)}`);
  }
}

async function scrollTableRight(page) {
  await page.locator('.business-data-table').first().evaluate(table => {
    let current = table.parentElement;
    while (current) {
      if (current.scrollWidth > current.clientWidth) {
        current.scrollLeft = current.scrollWidth;
        return;
      }
      current = current.parentElement;
    }
  });
  await page.waitForTimeout(200);
}

async function waitForDialogStable(page) {
  await page.waitForTimeout(700);
  await page.locator('[role="dialog"]').first().evaluate(element => {
    const style = getComputedStyle(element);
    if (Number(style.opacity) < 0.99) {
      throw new Error(`弹窗动画尚未稳定: opacity=${style.opacity}`);
    }
  });
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    await openRoute(page, '/sales/orders');
    await assertCenteredBusinessTable(page, 9);
    await page.screenshot({ path: path.join(outDir, 'sales-orders-list-left.png'), fullPage: true });
    await scrollTableRight(page);
    await page.screenshot({ path: path.join(outDir, 'sales-orders-list-right.png'), fullPage: true });

    const orderRow = page.getByRole('row').filter({ hasText: 'SO202606001' }).first();
    const rowCells = await orderRow.locator('[data-slot="table-cell"]').allInnerTexts();
    if (rowCells.some(text => /^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}/.test(text.trim()) && text.includes('锁定'))) {
      throw new Error('销售订单锁定列仍然把时间当成主值展示');
    }
    await orderRow.getByRole('button').first().click();
    const detailDialog = page.getByRole('dialog').filter({ hasText: '销售单详情' });
    await detailDialog.getByText('库存锁定数量').waitFor({ timeout: 5000 });
    await waitForDialogStable(page);
    await page.screenshot({ path: path.join(outDir, 'sales-orders-detail-lock.png'), fullPage: true });
    await detailDialog.getByRole('button', { name: '关闭' }).click();

    await openRoute(page, '/sales/customers');
    await assertCenteredBusinessTable(page, 8);
    await page.screenshot({ path: path.join(outDir, 'sales-customers-list.png'), fullPage: true });

    await openRoute(page, '/purchase/suppliers');
    await assertCenteredBusinessTable(page, 10);
    await page.screenshot({ path: path.join(outDir, 'purchase-suppliers-list.png'), fullPage: true });
    await page.getByRole('button', { name: '新增供应商' }).click();
    await page.getByRole('dialog').filter({ hasText: '供应商档案会被供货关系和采购订单引用' }).waitFor({ timeout: 5000 });
    await waitForDialogStable(page);
    await page.screenshot({ path: path.join(outDir, 'purchase-supplier-create-dialog.png'), fullPage: true });

    await openRoute(page, '/purchase/supplier-products');
    await assertCenteredBusinessTable(page, 10);
    await page.screenshot({ path: path.join(outDir, 'purchase-supplier-products-list.png'), fullPage: true });
    await page.getByRole('button', { name: '新增供货产品' }).click();
    await page.getByRole('dialog').filter({ hasText: '供货产品用于采购候选和价格建议' }).waitFor({ timeout: 5000 });
    await waitForDialogStable(page);
    await page.screenshot({ path: path.join(outDir, 'purchase-supplier-product-create-dialog.png'), fullPage: true });

    await openRoute(page, '/purchase/orders');
    await assertCenteredBusinessTable(page, 9);
    await page.screenshot({ path: path.join(outDir, 'purchase-orders-list.png'), fullPage: true });

    console.log(`BROWSER_QA_OK ${outDir}`);
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
