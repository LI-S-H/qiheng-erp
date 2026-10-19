// 三个试点页的分页与滚动边界；使用现有Mock记录，不更改业务数据。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const evidenceDir = path.resolve(process.env.QA_SCREENSHOT_DIR || '../docs/qa-screenshots/2026-10-18-pagination-scroll');
fs.mkdirSync(evidenceDir, { recursive: true });
const results = [];
let page;
const tableViewport = () => page.locator('.purchase-pilot-page .purchase-list-body > [data-slot="table-container"]');
async function settled() {
  await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]') && [...document.querySelectorAll('.purchase-pilot-page [data-list-filter-actions] button')].every(button => !button.disabled));
  await page.waitForTimeout(200);
}
async function selectSize(size) {
  await page.locator('.purchase-pilot-page [data-table-pagination] [role="combobox"]').click();
  await page.getByRole('option', { name: `${size} 条`, exact: true }).click();
  await page.waitForFunction(value => document.querySelector('.purchase-pilot-page')?.getAttribute('data-list-page-size') === String(value), size);
  await settled();
}
async function inspect(size) {
  await page.locator('main').evaluate(el => { el.scrollTop = 0; });
  const state = await tableViewport().evaluate(el => {
    const row = el.querySelector('tbody tr'); const last = el.querySelector('tbody tr:last-child');
    return {
      maxHeight: getComputedStyle(el).maxHeight, client: el.clientHeight, scroll: el.scrollHeight, scrollTop: el.scrollTop,
      rowCount: el.querySelectorAll('tbody tr').length,
      rowHeight: row.getBoundingClientRect().height, font: getComputedStyle(row.querySelector('td')).fontSize,
      allRowsWithinTable: last.getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1,
      outerOverflow: document.documentElement.scrollWidth - innerWidth,
    };
  });
  assert.equal(state.rowHeight, 64); assert.equal(state.font, '14px'); assert(state.outerOverflow <= 1);
  if (size === 10) {
    assert.equal(state.maxHeight, 'none'); assert(state.scroll - state.client <= 1 && state.allRowsWithinTable, '10条应完整展开当前页所有实际记录');
    assert.equal(state.scrollTop, 0, '切回10条不能遗留旧滚动位置');
  } else {
    assert.notEqual(state.maxHeight, 'none'); assert(state.scroll - state.client > 1, '较大分页的现有记录超出限高后应可内部滚动');
  }
  const sticky = await tableViewport().evaluate(async el => {
    const first = el.querySelector('tbody [data-table-sticky-edge="start"]');
    const last = el.querySelector('tbody [data-table-sticky-edge="end"]');
    const positions = () => ({ first: first.getBoundingClientRect().x, last: last.getBoundingClientRect().x });
    const before = positions(); el.scrollLeft = el.scrollWidth; el.scrollTop = el.scrollHeight;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return { before, after: positions(), scrollTop: el.scrollTop, header: el.querySelector('th').getBoundingClientRect().top, top: el.getBoundingClientRect().top };
  });
  assert(Math.abs(sticky.before.first - sticky.after.first) <= 1 && Math.abs(sticky.before.last - sticky.after.last) <= 1, '横向固定列不得漂移');
  assert(Math.abs(sticky.header - sticky.top) <= 1, '滚动后表头仍应固定');
  if (size === 10) assert.equal(sticky.scrollTop, 0); else assert(sticky.scrollTop > 0);
  await page.locator('main').evaluate(el => { el.scrollTop = el.scrollHeight; });
  assert(await page.locator('.purchase-pilot-page [data-table-pagination]').evaluate(el => { const rect = el.getBoundingClientRect(); const main = el.closest('main').getBoundingClientRect(); return rect.top >= main.top && rect.bottom <= main.bottom + 1; }), '分页应能通过主工作区滚动访问');
  return { state, sticky };
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${baseUrl}/purchase/orders`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456');
      await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(url => !url.pathname.includes('/login'));
    }
    for (const route of ['/purchase/orders', '/purchase/returns', '/purchase/suppliers']) {
      await page.goto(`${baseUrl}${route}`); await page.locator('.purchase-pilot-page tbody tr').first().waitFor(); await settled();
      await page.evaluate(() => document.fonts.ready);
      for (const [width, height] of [[1440, 900], [1115, 838], [390, 844]]) {
        await page.setViewportSize({ width, height }); await page.waitForTimeout(250);
        const checks = [];
        for (const size of [10, 20, 50, 10]) {
          if (await page.locator('.purchase-pilot-page').getAttribute('data-list-page-size') !== String(size)) await selectSize(size);
          checks.push({ size, ...await inspect(size) });
        }
        results.push({ route, width, height, checks, passed: true });
        console.log('PASS', route, `${width}x${height}`, '10→20→50→10');
      }
      await page.setViewportSize({ width: 1440, height: 1250 });
      await page.locator('main').evaluate(el => { el.scrollTop = 0; });
      await tableViewport().evaluate(el => { el.scrollLeft = 0; }); await page.mouse.move(0, 0); await page.waitForTimeout(250);
      await page.screenshot({ path: path.join(evidenceDir, `${route.split('/').pop()}-10-expanded.png`) });
    }
    await page.goto(`${baseUrl}/sales/returns`); await page.locator('[data-return-order-table] tbody tr').first().waitFor();
    assert.equal(await page.locator('[data-list-page-size]').count(), 0, '销售退货不应启用采购滚动规则');
    assert.equal(errors.length, 0, errors.join('\n'));
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ results, errors }, null, 2));
    console.log(`PAGINATION_SCROLL_OK: ${results.length} 组、36次分页状态检查通过`);
  } catch (error) {
    await page.screenshot({ path: path.join(evidenceDir, 'failed.png') }).catch(() => undefined);
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
