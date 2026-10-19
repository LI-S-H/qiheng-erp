// 对十六个已迁移分页页面核验反馈；只切换分页，不写业务数据。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const dir = path.resolve('../docs/qa-screenshots/2026-10-18-sales-style/pagination');
fs.mkdirSync(dir, { recursive: true });
const routes = ['/purchase/orders', '/purchase/returns', '/purchase/suppliers', '/purchase/supplier-products', '/sales/customers', '/sales/orders', '/sales/returns', '/warehouse/warehouses', '/warehouse/stocks', '/warehouse/inbound-bills', '/warehouse/outbound-bills', '/warehouse/stock-bills', '/product/products', '/system/users', '/system/roles', '/system/permissions'];
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const results = [];
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(`${base}/login`); await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456'); await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(u => !u.pathname.includes('/login'));
    for (const route of routes) {
      await page.goto(`${base}${route}`); await page.locator('.purchase-list-body tbody tr').first().waitFor(); await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]'));
      const rows = page.locator('.purchase-list-body tbody tr'), pagination = page.locator('[data-table-pagination]');
      const before = await rows.allTextContents();
      await pagination.locator('[role="combobox"]').click(); await page.getByRole('option', { name: '20 条', exact: true }).click();
      const overlay = page.locator('.purchase-list-body [data-list-loading]'); await overlay.waitFor(); assert.equal(await pagination.getAttribute('aria-busy'), 'true'); assert.deepEqual(await rows.allTextContents(), before);
      await page.waitForFunction(() => document.querySelector('[data-table-pagination]').getAttribute('aria-busy') === 'false');
      const fade = await overlay.evaluate(el => ({ transition: getComputedStyle(el).transitionDuration, className: el.className, opacity: Number(getComputedStyle(el).opacity) }));
      assert(fade.className.includes('page-loading-leave-active')); assert.equal(fade.transition, '0.22s'); assert(fade.opacity >= 0 && fade.opacity <= 1);
      await overlay.waitFor({ state: 'hidden' });
      await pagination.locator('[role="combobox"]').click(); await page.getByRole('option', { name: '10 条', exact: true }).click();
      await overlay.waitFor(); await overlay.waitFor({ state: 'hidden' });
      const next = pagination.locator('[data-slot="pagination-next"]');
      if (await next.isEnabled()) {
        const previousRows = await rows.allTextContents();
        await next.click(); await overlay.waitFor();
        assert.equal(await pagination.getAttribute('aria-busy'), 'true'); assert.deepEqual(await rows.allTextContents(), previousRows);
        await page.waitForFunction(() => document.querySelector('[data-table-pagination]').getAttribute('aria-busy') === 'false');
        assert.equal(await overlay.evaluate(el => getComputedStyle(el).transitionDuration), '0.22s');
        await overlay.waitFor({ state: 'hidden' }); assert.notDeepEqual(await rows.allTextContents(), previousRows);
        console.log('PASS', route, '点击下一页保留旧行、加载锁和220ms淡出');
      }
      if (route === '/sales/orders') {
        await page.emulateMedia({ reducedMotion: 'reduce' }); await pagination.locator('[role="combobox"]').click(); await page.getByRole('option', { name: '50 条', exact: true }).click(); await overlay.waitFor();
        const duration = await overlay.evaluate(el => getComputedStyle(el).transitionDuration.split(',').map(v => parseFloat(v)));
        assert(duration.every(v => v <= 0.001)); await overlay.waitFor({ state: 'hidden' }); await page.emulateMedia({ reducedMotion: 'no-preference' });
      }
      results.push({ route, fade, passed: true }); console.log('PASS', route, '即时加载、保留旧记录、220ms淡出和分页锁');
    }
    await page.getByRole('button', { name: '工作台', exact: true }).click(); await page.locator('[data-page-loading]').waitFor(); await page.waitForURL(u => u.pathname === '/dashboard'); assert.equal(await page.locator('.dashboard-pilot-theme').count(), 1);
    fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(results, null, 2)); console.log('REFINED_PAGINATION_OK 16页，减少动态效果及路由主题隔离通过');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
