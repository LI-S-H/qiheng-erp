// 供货产品逐页外观与原交互验证；写操作仅允许项目Mock。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const evidenceDir = path.resolve(process.env.QA_SCREENSHOT_DIR || '../docs/qa-screenshots/2026-10-18-supplier-product-style');
fs.mkdirSync(evidenceDir, { recursive: true });
const results = [];
let page;
const rows = () => page.locator('.supplier-product-table tbody tr');
const viewport = () => page.locator('.supplier-product-pilot-page .purchase-list-body > [data-slot="table-container"]');
const check = (name, detail = '通过') => { results.push({ name, detail, passed: true }); console.log('PASS', name); };
async function settled() {
  await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]') && [...document.querySelectorAll('.purchase-pilot-page [data-list-filter-actions] button')].every(button => !button.disabled));
  await page.waitForTimeout(250);
}
async function shot(name) { await page.waitForTimeout(250); await page.screenshot({ path: path.join(evidenceDir, `${name}.png`) }); }
async function close(dialog) { await dialog.locator('[data-slot="dialog-close"]').click(); await dialog.waitFor({ state: 'hidden' }); }
async function action(row, name) { await row.locator('[data-row-actions-trigger]').click(); await page.getByRole('menuitem', { name, exact: true }).click(); }
async function query(name) {
  await page.getByRole('button', { name, exact: true }).click(); assert(await page.getByRole('button', { name: '重置', exact: true }).isDisabled());
  assert(await viewport().evaluate(el => el.inert)); await settled();
}
async function remote(trigger, search, match) {
  await trigger.click(); const menu = page.locator('[data-remote-search-select-content]'); await menu.locator('input').fill(search);
  const options = menu.locator('[data-select-option]:not([disabled])');
  await (match ? options.filter({ hasText: match }).first() : options.first()).click(); await menu.waitFor({ state: 'hidden' });
}
async function size(value) {
  await page.locator('.purchase-pilot-page [data-table-pagination] [role="combobox"]').click(); await page.getByRole('option', { name: `${value} 条`, exact: true }).click();
  await page.waitForFunction(n => document.querySelector('.purchase-pilot-page').getAttribute('data-list-page-size') === String(n), value); await settled();
}
async function layout(width, height) {
  await page.setViewportSize({ width, height }); await page.locator('main').evaluate(el => { el.scrollTop = 0; });
  await viewport().evaluate(el => { el.scrollLeft = 0; el.scrollTop = 0; }); await page.mouse.move(0, 0); await page.waitForTimeout(300);
  const state = await viewport().evaluate(el => {
    const table = el.querySelector('table'); const row = table.querySelector('tbody tr');
    const root = document.querySelector('.purchase-pilot-page'); const main = root.parentElement;
    const cells = [...table.querySelectorAll('th'), ...row.children];
    const centerOffsets = cells.flatMap(cell => {
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT); const rects = []; let node;
      while ((node = walker.nextNode())) {
        if (!node.textContent.trim() || node.parentElement.closest('.sr-only')) continue;
        const range = document.createRange(); range.selectNodeContents(node); rects.push(range.getBoundingClientRect());
      }
      const box = cell.getBoundingClientRect();
      if (cell.matches('.supplier-product-table__actions') && rects.length) return [Math.abs((Math.min(...rects.map(r => r.left)) + Math.max(...rects.map(r => r.right)) - box.left - box.right) / 2)];
      return rects.map(r => Math.abs((r.left + r.right - box.left - box.right) / 2));
    });
    return { outerOverflow: document.documentElement.scrollWidth - innerWidth, mainOverflow: main.scrollWidth - main.clientWidth,
      pageWidth: root.getBoundingClientRect().width, mainWidth: main.clientWidth,
      align: cells.map(c => getComputedStyle(c).textAlign), fonts: cells.map(c => getComputedStyle(c).fontSize), centerOffsets,
      rowHeight: row.getBoundingClientRect().height, count: table.querySelectorAll('col').length, widths: [...row.children].map(c => c.getBoundingClientRect().width),
      verticalOverflow: el.scrollHeight - el.clientHeight, lastWithin: table.querySelector('tbody tr:last-child').getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1,
      controls: [...root.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"]')].map(c => c.getBoundingClientRect().height),
      basicCount: root.querySelectorAll('[data-filter-size]').length, ranges: root.querySelectorAll('.supplier-product-filter-range').length,
    };
  });
  assert(state.outerOverflow <= 1 && state.mainOverflow <= 1 && Math.abs(state.pageWidth - state.mainWidth) <= 1, '页面应满宽且无外层横向溢出');
  assert(state.align.every(v => v === 'center') && state.fonts.every(v => v === '14px'));
  assert(state.centerOffsets.every(v => v <= 1), `实际文字必须居中：${state.centerOffsets}`);
  assert.equal(state.count, 13); assert.equal(state.widths[0], 44); assert.equal(state.rowHeight, 64);
  assert.equal(state.basicCount, 9); assert.equal(state.ranges, 5); assert(state.controls.every(v => v === 36));
  assert(state.verticalOverflow <= 1 && state.lastWithin, '默认10条应完整展开');
  await shot(`products-${width}x${height}`);
  const sticky = await viewport().evaluate(async el => {
    const first = el.querySelector('tbody [data-table-sticky-edge="start"]'); const last = el.querySelector('tbody [data-table-sticky-edge="end"]');
    const coords = () => [first.getBoundingClientRect().x, last.getBoundingClientRect().x]; const before = coords(); el.scrollLeft = el.scrollWidth;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return { before, after: coords(), bg: getComputedStyle(last).backgroundColor };
  });
  assert(sticky.before.every((v, index) => Math.abs(v - sticky.after[index]) <= 1) && !sticky.bg.includes('rgba'));
  await page.locator('main').evaluate(el => { el.scrollTop = el.scrollHeight; });
  assert(await page.locator('[data-table-pagination]').evaluate(el => el.getBoundingClientRect().bottom <= el.closest('main').getBoundingClientRect().bottom + 1));
  check(`${width}x${height} 字号、字形居中、字段、满宽、十条展开及横向固定`, { state, sticky });
}
async function pinGeometry() {
  await page.waitForTimeout(250);
  const geometry = await viewport().evaluate(el => {
    const table = el.querySelector('table'); const widths = [...table.querySelectorAll('col')].map(c => c.getBoundingClientRect().width);
    const cells = [...table.querySelector('thead tr').children]; let expected = 0;
    return cells.map((cell, index) => {
      const result = { text: cell.innerText, expected, left: parseFloat(getComputedStyle(cell).left), pinned: cell.matches('.supplier-product-table__pinned-left') };
      expected += widths[index]; return result;
    });
  });
  assert(geometry.filter(g => g.pinned).every(g => Math.abs(g.left - g.expected) <= 1), `固定偏移应累计新col实际宽度：${JSON.stringify(geometry)}`);
  return geometry;
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = [];
    page.on('pageerror', e => { errors.push(e.message); console.error('PAGE_ERROR', e.message); });
    await page.goto(`${baseUrl}/purchase/supplier-products`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456'); await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login')); await page.goto(`${baseUrl}/purchase/supplier-products`);
    }
    await rows().first().getByRole('checkbox').waitFor(); await settled(); await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin');
    await page.setViewportSize({ width: 1440, height: 1450 }); await shot('products-overview');
    for (const view of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) await layout(...view);
    await page.setViewportSize({ width: 1440, height: 900 }); await size(20);
    assert(await viewport().evaluate(el => el.scrollHeight > el.clientHeight));
    await viewport().evaluate(el => { el.scrollTop = el.scrollHeight; });
    assert(await viewport().evaluate(el => Math.abs(el.querySelector('th').getBoundingClientRect().top - el.getBoundingClientRect().top) <= 1));
    await size(50); assert(await viewport().evaluate(el => el.scrollHeight > el.clientHeight));
    await size(10); assert(await viewport().evaluate(el => el.scrollTop === 0 && el.scrollHeight - el.clientHeight <= 1));
    check('10→20→50→10及纵向固定表头');

    await page.getByRole('button', { name: '列设置', exact: true }).click(); const settings = page.locator('.column-preferences-canvas'); await settings.waitFor();
    assert(await settings.evaluate(el => el.getBoundingClientRect().top >= 0 && el.getBoundingClientRect().bottom <= innerHeight));
    await shot('products-columns');
    await settings.getByRole('switch', { name: '切换产品列固定', exact: true }).click();
    await settings.getByRole('switch', { name: '切换供应商列固定', exact: true }).click(); await pinGeometry();
    await settings.getByRole('switch', { name: '切换当前报价列固定', exact: true }).click(); await pinGeometry();
    await settings.getByRole('switch', { name: '切换供应商显示', exact: true }).click();
    assert.equal(await page.locator('.supplier-product-table col').count(), 12); await pinGeometry();
    await settings.getByRole('switch', { name: '切换当前报价列固定', exact: true }).click();
    await settings.getByRole('switch', { name: '切换最小起订量列固定', exact: true }).click(); await pinGeometry();
    await page.keyboard.press('Escape'); await settings.waitFor({ state: 'hidden' });
    await page.setViewportSize({ width: 1920, height: 1080 }); const pinned = await pinGeometry();
    await page.setViewportSize({ width: 1115, height: 838 }); await pinGeometry();
    const pinScroll = await viewport().evaluate(async el => {
      const cells = [...el.querySelectorAll('tbody tr:first-child .supplier-product-table__pinned-left')]; const before = cells.map(c => c.getBoundingClientRect().x);
      el.scrollLeft = el.scrollWidth; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return { before, after: cells.map(c => c.getBoundingClientRect().x), overflow: el.scrollWidth - el.clientWidth };
    });
    assert(pinScroll.overflow > 0); assert(pinScroll.before.every((v, i) => Math.abs(v - pinScroll.after[i]) <= 1)); await shot('products-pinned');
    await page.reload(); await rows().first().getByRole('checkbox').waitFor(); await settled(); await pinGeometry();
    assert.equal(await page.locator('.supplier-product-table col').count(), 12);
    await page.getByRole('button', { name: '列设置', exact: true }).click(); await settings.getByRole('button', { name: '恢复默认设置', exact: true }).click();
    await page.keyboard.press('Escape'); await settings.waitFor({ state: 'hidden' }); assert.equal(await page.locator('.supplier-product-table col').count(), 13);
    await page.setViewportSize({ width: 1440, height: 900 }); check('相邻/非相邻固定、隐藏恢复、宽屏偏移刷新及持久化', { pinned, pinScroll });

    const filter = page.locator('.purchase-pilot-page [data-list-filter-panel]'); const productCode = page.getByPlaceholder('请输入产品编码');
    await productCode.focus(); await page.waitForTimeout(200); assert((await productCode.evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03'));
    await productCode.fill('P000026'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
    await page.getByPlaceholder('请输入供应商名称').fill('华东'); await query('查询'); assert.equal(await rows().count(), 2); await query('重置');
    await remote(filter.locator('[role="combobox"]').first(), '森纸', '森纸'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
    await remote(filter.locator('[role="combobox"]').nth(1), '复印', 'A4复印纸'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
    await filter.locator('[data-anchored-select-trigger]').first().click(); await shot('products-quote-dropdown'); await page.getByRole('option', { name: '有效报价', exact: true }).click();
    await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
    await page.getByRole('spinbutton', { name: '质量分最低值', exact: true }).fill('95'); await query('查询'); assert((await rows().allTextContents()).every(text => /95\.20|96\.10|97\.20/.test(text))); await query('重置');
    await page.getByPlaceholder('请输入产品名称').fill('不存在的产品'); await query('查询'); await shot('products-empty'); await query('重置'); await query('刷新');
    assert(await page.locator('[data-table-pagination] [data-slot="pagination-next"]').isDisabled(), '当前十条数据仅一页，下一页应禁用');
    assert(await page.locator('[data-table-pagination] [data-slot="pagination-previous"]').isDisabled());
    const target = rows().filter({ hasText: 'A4复印纸' });
    await target.getByRole('checkbox').click(); await page.getByRole('button', { name: '批量停用', exact: true }).click();
    const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); await shot('products-batch-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click();
    await confirm.waitFor({ state: 'hidden' }); await target.getByRole('checkbox').click(); check('筛选、远程搜索、范围、空态、刷新、分页及批量确认');

    await target.getByRole('button', { name: '查看', exact: true }).click(); const detail = page.locator('[data-supplier-product-detail-workbench]');
    await detail.getByText('关系资料', { exact: true }).waitFor(); const text = await detail.innerText();
    for (const copy of ['当前报价', '报价有效期', '最小起订量', '平均到货周期', '推荐评分', '评分样本金额', '关系资料', '数量精度', '备注', '报价与成交', '报价更新时间', '最近采购价', '最近采购时间', '报价调整原因', '产品评分', '维护信息', '创建时间', '最后更新时间', '最后更新人']) assert(text.includes(copy), `详情内容保留：${copy}`);
    assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)'); await shot('products-detail');
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(200); await shot('products-detail-mobile');
    assert(await detail.evaluate(el => el.getBoundingClientRect().left >= 0 && el.getBoundingClientRect().right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight));
    await close(detail); await page.setViewportSize({ width: 1440, height: 900 });
    await action(target, '编辑'); const form = page.locator('[data-supplier-product-form-dialog]'); await form.waitFor();
    assert.equal(await form.locator('input[type="number"]').inputValue(), '12'); await form.locator('textarea').fill('供货产品初版回显验证'); await shot('products-edit');
    await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled();
    await target.getByRole('button', { name: '查看', exact: true }).click(); await detail.getByText('供货产品初版回显验证', { exact: true }).waitFor(); await close(detail);
    await action(target, '调整报价'); const quote = page.locator('[data-supplier-quote-dialog]'); await quote.waitFor();
    await quote.getByRole('button', { name: '确认调整', exact: true }).click(); await page.getByText('请填写本次报价调整原因', { exact: true }).waitFor();
    await quote.locator('input[type="number"]').fill('92'); await quote.locator('input[type="date"]').fill('2027-12-31'); await quote.locator('textarea').fill('视觉验证：更新有效报价');
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0); await shot('products-quote');
    await quote.getByRole('button', { name: '确认调整', exact: true }).click(); await quote.waitFor({ state: 'hidden' }); await settled(); assert((await target.innerText()).includes('92.00'));
    await action(target, '评分变更记录'); const log = page.locator('[data-score-change-log-dialog]'); await log.getByText('指标类型', { exact: true }).waitFor(); await settled();
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0); await shot('products-score-log'); await close(log);
    check('详情内容、手机详情、编辑回显、调整报价校验保存及评分记录');

    await page.getByRole('button', { name: '新增供货关系', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByText('请选择供应商和产品', { exact: true }).waitFor();
    await remote(form.locator('[role="combobox"]').first(), '森纸', '森纸');
    await remote(form.locator('[role="combobox"]').nth(1), 'P000001', '经典原味苏打水');
    await form.locator('input[type="number"]').first().fill('2'); await form.locator('textarea').fill('供货关系新增外观验证');
    await form.locator('textarea').focus(); await page.waitForTimeout(200); assert((await form.locator('textarea').evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03'));
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0); await shot('products-create');
    for (const [width, height] of [[1115, 640], [390, 844]]) {
      await page.setViewportSize({ width, height }); await page.waitForTimeout(200);
      assert(await form.evaluate(el => { const r = el.getBoundingClientRect(); const area = el.querySelector('[data-slot="scroll-area-viewport"]'); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight && area.scrollHeight > area.clientHeight; }));
      await shot(`products-create-${width}x${height}`);
    }
    await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled();
    await page.setViewportSize({ width: 1440, height: 900 }); await page.getByPlaceholder('请输入供应商名称').fill('森纸'); await page.getByPlaceholder('请输入产品编码').fill('P000001');
    await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('经典原味苏打水')); await query('重置');
    check('新增必填、搜索、柔和聚焦、低高度及手机滚动和Mock保存查询回显');

    for (const route of ['/purchase/orders', '/purchase/returns', '/purchase/suppliers']) {
      await page.goto(`${baseUrl}${route}`); await page.locator('.purchase-pilot-page tbody tr').first().waitFor(); await settled();
      assert(await page.locator('.purchase-pilot-page .purchase-list-body > [data-slot="table-container"]').evaluate(el => getComputedStyle(el).maxHeight === 'none' && el.scrollHeight - el.clientHeight <= 1));
    }
    await page.getByRole('button', { name: '工作台', exact: true }).click(); await page.locator('[data-page-loading]').waitFor(); await page.waitForURL(u => u.pathname === '/dashboard');
    await page.locator('.app-shell:not(.purchase-pilot-theme)').waitFor(); assert.equal(errors.length, 0, errors.join('\n'));
    check('前三页10条展开、页面切换动效、其它路由主题隔离及脚本错误', { errors });
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify(results, null, 2)); console.log(`SUPPLIER_PRODUCT_STYLE_OK: ${results.length} 组检查通过；截图：${evidenceDir}`);
  } catch (error) {
    await shot('failed').catch(() => undefined); fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
