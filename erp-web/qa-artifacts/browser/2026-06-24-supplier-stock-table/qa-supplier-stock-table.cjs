const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = 'http://127.0.0.1:5173';
const outDir = __dirname;

fs.mkdirSync(outDir, { recursive: true });

async function loginIfNeeded(page, route) {
  if (!page.url().includes('/login')) return;
  await page.locator('input').nth(0).fill('admin');
  await page.locator('input').nth(1).fill('123456');
  await page.locator('button').filter({ hasText: /登录|Login/i }).first().click();
  await page.waitForURL(url => url.pathname !== '/login', { timeout: 10000 });
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
}

async function openRoute(page, route) {
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
  await loginIfNeeded(page, route);
  await page.locator('.erp-data-table').first().waitFor({ timeout: 10000 });
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(400);
}

async function assertSupplierProductFits(page) {
  const state = await page.locator('.erp-data-table').first().evaluate(table => {
    const container = table.closest('[data-slot="scroll-area-viewport"]') || table.parentElement;
    const cols = [...table.querySelectorAll('col')].map(col => {
      const widthClass = [...col.classList].find(item => /^w-\[\d+px\]$/.test(item));
      return widthClass ? Number(widthClass.match(/\d+/)[0]) : 0;
    });
    const supplierCol = table.querySelectorAll('col')[1];
    const actionCell = table.querySelector('tbody [data-slot="table-cell"]:last-child');
    const actionBox = actionCell?.getBoundingClientRect();
    const actionButtonBoxes = [...(actionCell?.querySelectorAll('button') ?? [])].map(button => button.getBoundingClientRect());
    const supplierWidthClass = supplierCol ? [...supplierCol.classList].find(item => /^w-\[\d+px\]$/.test(item)) : '';

    return {
      minWidth: Number.parseFloat(getComputedStyle(table).minWidth),
      colSum: cols.reduce((sum, width) => sum + width, 0),
      supplierWidthClass,
      viewportWidth: container?.clientWidth ?? 0,
      scrollWidth: container?.scrollWidth ?? 0,
      clientWidth: container?.clientWidth ?? 0,
      actionButtonsFit: Boolean(actionBox && actionButtonBoxes.every(box => box.left >= actionBox.left - 1 && box.right <= actionBox.right + 1)),
    };
  });

  if (state.minWidth !== state.colSum) {
    throw new Error(`供货商品列宽合计异常: ${JSON.stringify(state)}`);
  }
  if (state.supplierWidthClass !== 'w-[160px]') {
    throw new Error(`供货商品供应商列未缩短: ${JSON.stringify(state)}`);
  }
  if (state.scrollWidth > state.clientWidth + 2) {
    throw new Error(`供货商品桌面视口仍需要横向滚动: ${JSON.stringify(state)}`);
  }
  if (!state.actionButtonsFit) {
    throw new Error(`供货商品操作列按钮超出单元格: ${JSON.stringify(state)}`);
  }
}

async function assertStockCodeAboveName(page) {
  const state = await page.locator('[data-stock-id]').first().evaluate(row => {
    const cells = [...row.querySelectorAll('[data-slot="table-cell"]')];
    return [cells[0], cells[1]].map(cell => {
      const wrapper = cell.querySelector('div');
      const code = cell.querySelector('code');
      const name = cell.querySelector('span');
      const wrapperStyle = wrapper ? getComputedStyle(wrapper) : null;
      const codeBox = code?.getBoundingClientRect();
      const nameBox = name?.getBoundingClientRect();
      return {
        display: wrapperStyle?.display,
        flexDirection: wrapperStyle?.flexDirection,
        alignItems: wrapperStyle?.alignItems,
        textAlign: getComputedStyle(cell).textAlign,
        codeAboveName: Boolean(codeBox && nameBox && codeBox.bottom <= nameBox.top + 2),
        codeCenterOffset: codeBox && cell ? Math.abs((codeBox.left + codeBox.width / 2) - (cell.getBoundingClientRect().left + cell.getBoundingClientRect().width / 2)) : 999,
        nameCenterOffset: nameBox && cell ? Math.abs((nameBox.left + nameBox.width / 2) - (cell.getBoundingClientRect().left + cell.getBoundingClientRect().width / 2)) : 999,
      };
    });
  });

  for (const item of state) {
    if (item.display !== 'flex' || item.flexDirection !== 'column' || item.alignItems !== 'center' || item.textAlign !== 'center' || !item.codeAboveName) {
      throw new Error(`库存仓库/产品编码未在名称正上方: ${JSON.stringify(state)}`);
    }
    if (item.codeCenterOffset > 2 || item.nameCenterOffset > 2) {
      throw new Error(`库存仓库/产品内容未居中: ${JSON.stringify(state)}`);
    }
  }
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    await openRoute(page, '/purchase/supplier-products');
    await assertSupplierProductFits(page);
    await page.screenshot({ path: path.join(outDir, 'purchase-supplier-products-1440-list.png'), fullPage: true });

    await openRoute(page, '/warehouse/stocks');
    await assertStockCodeAboveName(page);
    await page.screenshot({ path: path.join(outDir, 'warehouse-stocks-1440-list.png'), fullPage: true });

    console.log(`BROWSER_QA_OK ${outDir}`);
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
