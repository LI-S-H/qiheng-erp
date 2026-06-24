const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = 'http://127.0.0.1:5173';
const outDir = __dirname;

fs.mkdirSync(outDir, { recursive: true });

const routes = [
  { route: '/system/users', name: 'system-users' },
  { route: '/system/roles', name: 'system-roles' },
  { route: '/system/depts', name: 'system-depts' },
  { route: '/system/permissions', name: 'system-permissions' },
  { route: '/product/categories', name: 'product-categories' },
  { route: '/product/products', name: 'product-products', screenshot: true },
  { route: '/warehouse/warehouses', name: 'warehouse-warehouses' },
  { route: '/warehouse/stocks', name: 'warehouse-stocks', screenshot: true },
  { route: '/warehouse/inbound-bills', name: 'warehouse-inbound-bills', screenshot: true, scrollRight: true },
  { route: '/warehouse/outbound-bills', name: 'warehouse-outbound-bills', screenshot: true, scrollRight: true },
  { route: '/purchase/suppliers', name: 'purchase-suppliers', screenshot: true },
  { route: '/purchase/supplier-products', name: 'purchase-supplier-products', screenshot: true },
  { route: '/purchase/orders', name: 'purchase-orders', screenshot: true },
  { route: '/sales/customers', name: 'sales-customers' },
  { route: '/sales/orders', name: 'sales-orders', screenshot: true, scrollRight: true },
];

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
  await page.waitForTimeout(500);
}

async function assertTables(page, routeName) {
  const states = await page.locator('.erp-data-table').evaluateAll(tables =>
    tables.map((table, tableIndex) => {
      const heads = [...table.querySelectorAll('[data-slot="table-head"]')];
      const cells = [...table.querySelectorAll('[data-slot="table-cell"]')].slice(0, 80);
      const alignedNodes = [...table.querySelectorAll('.text-left, .text-right')].slice(0, 80);
      const cols = [...(table.querySelector('colgroup')?.querySelectorAll('col') ?? [])]
        .map(col => {
          const widthClass = [...col.classList].find(item => /^w-\[\d+px\]$/.test(item));
          return widthClass ? Number(widthClass.match(/\d+/)[0]) : 0;
        })
        .filter(Boolean);
      const minWidth = Number.parseFloat(getComputedStyle(table).minWidth);

      return {
        tableIndex,
        tableLayout: getComputedStyle(table).tableLayout,
        minWidth,
        colSum: cols.reduce((sum, width) => sum + width, 0),
        columnCount: cols.length,
        badHeadAlign: heads
          .map((item, index) => ({ index, align: getComputedStyle(item).textAlign, text: item.textContent.trim() }))
          .filter(item => item.align !== 'center'),
        badCellAlign: cells
          .map((item, index) => ({ index, align: getComputedStyle(item).textAlign, text: item.textContent.trim().slice(0, 30) }))
          .filter(item => item.align !== 'center'),
        badNestedAlign: alignedNodes
          .map((item, index) => ({ index, align: getComputedStyle(item).textAlign, text: item.textContent.trim().slice(0, 30) }))
          .filter(item => item.align !== 'center'),
      };
    }),
  );

  for (const state of states) {
    if (state.tableLayout !== 'fixed') {
      throw new Error(`${routeName} table ${state.tableIndex} table-layout 不是 fixed: ${JSON.stringify(state)}`);
    }
    if (state.columnCount > 0 && Math.round(state.minWidth) !== state.colSum) {
      throw new Error(`${routeName} table ${state.tableIndex} 列宽总和不匹配: ${JSON.stringify(state)}`);
    }
    if (state.badHeadAlign.length || state.badCellAlign.length || state.badNestedAlign.length) {
      throw new Error(`${routeName} table ${state.tableIndex} 未完全居中: ${JSON.stringify(state)}`);
    }
  }
}

async function scrollFirstTableRight(page) {
  await page.locator('.erp-data-table').first().evaluate(table => {
    let current = table.parentElement;
    while (current) {
      if (current.scrollWidth > current.clientWidth) {
        current.scrollLeft = current.scrollWidth;
        return;
      }
      current = current.parentElement;
    }
  });
  await page.waitForTimeout(250);
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    for (const item of routes) {
      await openRoute(page, item.route);
      await assertTables(page, item.name);
      if (item.screenshot) {
        await page.screenshot({ path: path.join(outDir, `${item.name}-left.png`), fullPage: true });
      }
      if (item.scrollRight) {
        await scrollFirstTableRight(page);
        await assertTables(page, item.name);
        await page.screenshot({ path: path.join(outDir, `${item.name}-right.png`), fullPage: true });
      }
    }

    console.log(`BROWSER_QA_OK ${outDir}`);
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
