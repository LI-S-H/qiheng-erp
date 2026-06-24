const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = 'http://127.0.0.1:5173';
const outDir = __dirname;

fs.mkdirSync(outDir, { recursive: true });

const routes = [
  { route: '/sales/customers', name: 'sales-customers', minWidth: 1089 },
  { route: '/purchase/supplier-products', name: 'purchase-supplier-products', minWidth: 1124 },
  { route: '/purchase/orders', name: 'purchase-orders', minWidth: 1155 },
  { route: '/sales/orders', name: 'sales-orders', minWidth: 1160 },
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
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(400);
}

async function assertMainTable(page, expected) {
  const state = await page.locator('.erp-data-table').first().evaluate(table => {
    const container = table.closest('[data-slot="scroll-area-viewport"]') || table.parentElement;
    const cols = [...table.querySelectorAll('col')].map(col => {
      const widthClass = [...col.classList].find(item => /^w-\[\d+px\]$/.test(item));
      return widthClass ? Number(widthClass.match(/\d+/)[0]) : 0;
    });
    const actionCells = [...table.querySelectorAll('tbody tr [data-slot="table-cell"]:last-child')].slice(0, 8);
    const actionButtonsFit = actionCells.every(cell => {
      const cellBox = cell.getBoundingClientRect();
      return [...cell.querySelectorAll('button')].every(button => {
        const buttonBox = button.getBoundingClientRect();
        return buttonBox.left >= cellBox.left - 1 && buttonBox.right <= cellBox.right + 1;
      });
    });

    return {
      minWidth: Number.parseFloat(getComputedStyle(table).minWidth),
      colSum: cols.reduce((sum, width) => sum + width, 0),
      clientWidth: container?.clientWidth ?? 0,
      scrollWidth: container?.scrollWidth ?? 0,
      actionButtonsFit,
      alignments: [...table.querySelectorAll('[data-slot="table-head"], [data-slot="table-cell"]')]
        .slice(0, 80)
        .map(node => getComputedStyle(node).textAlign),
    };
  });

  if (state.minWidth !== expected.minWidth || state.colSum !== expected.minWidth) {
    throw new Error(`${expected.name} 列宽配置不一致: ${JSON.stringify(state)}`);
  }
  if (state.scrollWidth > state.clientWidth + 2) {
    throw new Error(`${expected.name} 1440 视口仍需要横向滚动: ${JSON.stringify(state)}`);
  }
  if (!state.actionButtonsFit) {
    throw new Error(`${expected.name} 操作按钮超出单元格: ${JSON.stringify(state)}`);
  }
  if (!state.alignments.every(item => item === 'center')) {
    throw new Error(`${expected.name} 表格未保持居中: ${JSON.stringify(state)}`);
  }
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    for (const route of routes) {
      await openRoute(page, route.route);
      await assertMainTable(page, route);
      await page.screenshot({ path: path.join(outDir, `${route.name}-1440-list.png`), fullPage: true });
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
