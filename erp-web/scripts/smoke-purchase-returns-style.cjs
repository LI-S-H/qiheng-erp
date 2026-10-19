// 本轮只验证采购退回外观和原交互，保存仅允许项目 Mock 会话。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const evidenceDir = path.resolve(process.env.QA_SCREENSHOT_DIR || '../docs/qa-screenshots/2026-10-18-purchase-return-style');
fs.mkdirSync(evidenceDir, { recursive: true });
const results = [];
const check = (name, detail = '通过') => results.push({ name, detail, passed: true });
let page;
const rows = () => page.locator('[data-return-order-table] tbody tr');
const viewport = () => page.locator('.purchase-return-pilot-page .purchase-list-body > [data-slot="table-container"]');
async function settled() {
  await page.waitForFunction(() => {
    const actions = [...document.querySelectorAll('[data-list-filter-actions] button')];
    return !document.querySelector('[data-list-loading], [data-page-loading]') && actions.every(button => !button.disabled);
  });
  await page.waitForTimeout(250);
}
async function shot(name) {
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(evidenceDir, `${name}.png`) });
}
async function close(dialog) {
  await dialog.locator('[data-slot="dialog-close"]').click();
  await dialog.waitFor({ state: 'hidden' });
}
async function query(name) {
  await page.getByRole('button', { name, exact: true }).click();
  assert(await page.getByRole('button', { name: '重置', exact: true }).isDisabled(), '查询等待期应锁定动作');
  assert(await viewport().evaluate(el => el.inert), '查询等待期表格应不可交互');
  await settled();
}
async function remote(trigger, search = '', match) {
  await trigger.click();
  const menu = page.locator('[data-remote-search-select-content]');
  await menu.locator('input').fill(search);
  const options = menu.locator('[data-select-option]:not([disabled])');
  const option = match ? options.filter({ hasText: match }).first() : options.first();
  await option.waitFor();
  await option.click();
  await menu.waitFor({ state: 'hidden' });
}
async function tableStyles(dialog) {
  const styles = await dialog.locator('th, td').evaluateAll(elements => elements.map(el => ({
    tag: el.tagName, align: getComputedStyle(el).textAlign, font: getComputedStyle(el).fontSize,
  })));
  assert(styles.length && styles.every(style => style.align === 'center'), '弹窗表头和数据应全部居中');
  const headerFont = await dialog.evaluate(el => el.hasAttribute('data-return-form-dialog') ? '13px' : '14px');
  assert(styles.filter(style => style.tag === 'TH').every(style => style.font === headerFont), '表单13px、详情14px且同表一致');
}
async function layout(width, height) {
  await page.setViewportSize({ width, height });
  await page.locator('main').evaluate(el => { el.scrollTop = 0; });
  await viewport().evaluate(el => { el.scrollTop = 0; el.scrollLeft = 0; });
  await page.waitForTimeout(300);
  await page.mouse.move(0, 0);
  const state = await viewport().evaluate(el => {
    const table = el.querySelector('table');
    const row = table.querySelector('tbody tr');
    const root = document.querySelector('.purchase-return-pilot-page');
    const main = root.parentElement;
    const cells = [...table.querySelectorAll('th'), ...row.children];
    const centerOffsets = cells.flatMap(cell => {
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
      const offsets = [];
      let node;
      while ((node = walker.nextNode())) {
        if (!node.textContent.trim()) continue;
        const range = document.createRange(); range.selectNodeContents(node);
        const text = range.getBoundingClientRect(); const box = cell.getBoundingClientRect();
        offsets.push(Math.abs((text.left + text.right - box.left - box.right) / 2));
      }
      return offsets;
    });
    const range = node => { const r = document.createRange(); r.selectNodeContents(node); return r.getBoundingClientRect(); };
    return {
      overflow: document.documentElement.scrollWidth - innerWidth,
      mainOverflow: main.scrollWidth - main.clientWidth,
      widthDelta: root.getBoundingClientRect().width - main.clientWidth,
      leftDelta: root.getBoundingClientRect().left - main.getBoundingClientRect().left,
      count: table.querySelectorAll('col').length,
      align: cells.map(cell => getComputedStyle(cell).textAlign),
      fonts: cells.map(cell => getComputedStyle(cell).fontSize),
      rowHeight: row.getBoundingClientRect().height,
      centerOffsets,
      columns: [...row.children].map(cell => cell.getBoundingClientRect().width),
      gap: range(row.children[6]).left - range(row.children[5]).right,
      statusChildren: [...table.querySelectorAll('tbody [data-pilot-status-column]')].map(cell => cell.children.length),
      controls: [...root.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"], [data-list-filter-actions] button')].map(control => control.getBoundingClientRect().height),
      footerBottom: root.querySelector('[data-table-pagination]').getBoundingClientRect().bottom,
      verticalOverflow: el.scrollHeight - el.clientHeight,
      allRowsWithinTable: table.querySelector('tbody tr:last-child').getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1,
      pageWidth: root.getBoundingClientRect().width,
    };
  });
  assert(state.overflow <= 1 && state.mainOverflow <= 1, `外层不得溢出：${JSON.stringify(state)}`);
  assert(Math.abs(state.widthDelta) <= 1 && Math.abs(state.leftDelta) <= 1, '工作区必须铺满');
  assert.equal(state.count, 9);
  assert(state.align.every(value => value === 'center') && state.fonts.every(value => value === '14px'), '表头/数据同为14px且居中');
  assert(state.centerOffsets.every(value => value <= 1), `实际字形必须居中：${state.centerOffsets}`);
  assert.equal(state.rowHeight, 64);
  assert(state.statusChildren.every(value => value === 1), '状态应只有单个标签');
  assert(state.gap >= 28, '金额与日期不得碰撞');
  assert(state.controls.every(value => value === 36), '筛选和按钮应为36px');
  assert(state.verticalOverflow <= 1 && state.allRowsWithinTable, '默认10条必须完整展开，不能在表格内部纵向滚动');
  await shot(`returns-${width}x${height}`);
  const sticky = await viewport().evaluate(async el => {
    const first = el.querySelector('tbody [data-table-sticky-edge="start"]');
    const last = el.querySelector('tbody [data-table-sticky-edge="end"]');
    const coords = () => ({ first: first.getBoundingClientRect().x, last: last.getBoundingClientRect().x });
    const before = coords(); el.scrollLeft = el.scrollWidth; el.scrollTop = 130;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return { before, after: coords(), headerTop: el.querySelector('th').getBoundingClientRect().top, top: el.getBoundingClientRect().top, scrollLeft: el.scrollLeft, bg: getComputedStyle(first).backgroundColor };
  });
  assert(Math.abs(sticky.before.first - sticky.after.first) <= 1 && Math.abs(sticky.before.last - sticky.after.last) <= 1, '首尾固定列不得漂移');
  assert(Math.abs(sticky.headerTop - sticky.top) <= 1 && !sticky.bg.includes('rgba'), '固定表头与不透明列背景必须保留');
  await shot(`returns-${width}x${height}-scroll`);
  check(`${width}x${height} 字号、居中、满宽、列宽、固定列及滚动`, { state, sticky });
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${baseUrl}/purchase/returns`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'));
      await page.goto(`${baseUrl}/purchase/returns`);
    }
    await rows().first().waitFor(); await settled();
    await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin', '仅允许Mock保存验证');
    assert.equal(await rows().count(), 6);
    assert.deepEqual(await page.locator('[data-list-summary] dd').allTextContents(), ['1', '1', '1', '1']);
    for (const size of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) await layout(...size);
    await page.setViewportSize({ width: 1440, height: 900 });
    await viewport().evaluate(el => { el.scrollTop = 0; el.scrollLeft = 0; });
    const filter = page.locator('[data-list-filter-panel]');
    const input = filter.locator('input').first();
    await input.focus(); await page.waitForTimeout(200);
    const focus = await input.evaluate(el => ({ border: getComputedStyle(el).borderColor, shadow: getComputedStyle(el).boxShadow }));
    assert.equal(focus.border, 'rgb(182, 194, 210)'); assert(focus.shadow.includes('0.03'));
    await shot('returns-soft-focus');
    await filter.locator('[data-anchored-select-trigger]').click();
    const status = page.locator('[data-anchored-select-content]');
    assert.equal(await status.evaluate(el => getComputedStyle(el).borderRadius), '8px');
    await shot('returns-status-dropdown');
    await status.getByRole('option', { name: '待审核', exact: true }).click(); await query('查询');
    assert.equal(await rows().count(), 1); await query('重置');
    await input.fill('不存在的退回单'); await query('查询');
    assert((await rows().innerText()).includes('暂无采购退回单')); await shot('returns-empty'); await query('重置');
    await filter.locator('input').nth(1).fill('PO202607002'); await query('查询');
    assert.equal(await rows().count(), 3); await query('重置');
    const remoteFilters = filter.locator('[role="combobox"]:not([data-anchored-select-trigger])');
    await remoteFilters.first().click();
    await page.locator('[data-remote-search-select-content] input').fill('森纸');
    await page.locator('[data-remote-search-select-content] [data-select-option]').filter({ hasText: '森纸' }).waitFor();
    await shot('returns-supplier-dropdown');
    await page.locator('[data-remote-search-select-content] [data-select-option]').filter({ hasText: '森纸' }).click();
    await query('查询'); assert.equal(await rows().count(), 3);
    await remoteFilters.first().click(); await page.locator('[data-remote-search-select-content] [aria-current="true"]').waitFor();
    await remoteFilters.nth(1).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-remote-search-select-content]').length === 1);
    await page.locator('[data-remote-search-select-content] input').fill('南京');
    await page.locator('[data-remote-search-select-content] [data-select-option]').filter({ hasText: '南京' }).click();
    await query('查询'); assert.equal(await rows().count(), 3);
    await filter.locator('[data-anchored-select-trigger]').click();
    await page.locator('[data-anchored-select-content]').getByRole('option', { name: '待审核', exact: true }).click();
    await query('查询'); assert.equal(await rows().count(), 1, '供应商、仓库和状态应按AND组合');
    await query('重置'); await query('刷新');
    await page.locator('[data-table-pagination] [role="combobox"]').click();
    await page.getByRole('option', { name: '20 条', exact: true }).click(); await settled();
    assert.equal(await rows().count(), 6);
    check('五筛选、柔和焦点、选中、互斥、空态、刷新与每页条数', focus);

    const draft = rows().filter({ hasText: 'PR-20260700-1' });
    await draft.getByRole('button', { name: '处理', exact: true }).click();
    const detail = page.locator('[data-return-detail-dialog]');
    await detail.getByText('业务信息', { exact: true }).waitFor(); await tableStyles(detail);
    const detailText = await detail.innerText();
    for (const text of ['退回金额', '商品明细', '预计退货日期', '累计退货出库', '业务进度', '业务信息', '原采购单', '处理方式', '退回原因', '申请退回', '审核退回', '已退货出库', '待退货出库', '流程记录', '原因说明']) assert(detailText.includes(text), `详情内容保留：${text}`);
    assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)');
    await shot('returns-detail');
    await detail.getByRole('button', { name: '提交采购退回', exact: true }).click();
    const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor();
    assert(await detail.evaluate(el => el.inert)); await shot('returns-confirm');
    await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' });
    assert.equal(await detail.evaluate(el => el.inert), false);
    await detail.getByRole('button', { name: '取消采购退回', exact: true }).click();
    const prompt = page.locator('[data-prompt-dialog]'); await prompt.waitFor();
    assert(await detail.evaluate(el => el.inert));
    assert.equal(await page.locator('[data-slot="dialog-overlay"]').last().evaluate(el => getComputedStyle(el).backdropFilter), 'none');
    await shot('returns-cancel-reason');
    await prompt.getByRole('button', { name: '取消', exact: true }).click(); await prompt.waitFor({ state: 'hidden' });
    await detail.getByRole('button', { name: '编辑', exact: true }).click();
    const form = page.locator('[data-return-form-dialog]');
    await form.getByRole('heading', { name: '编辑采购退回', exact: true }).waitFor();
    assert((await form.locator('[role="combobox"]').first().innerText()).includes('PO202607002'));
    await tableStyles(form);
    // 现有草稿种子数量可能高于其它有效退货占用后的可退余额，保存验证使用合法申请数量。
    await form.locator('[data-return-form-items] input[type="number"]').first().fill('1');
    await form.locator('textarea').nth(1).fill('采购退回视觉初版回显验证'); await shot('returns-edit');
    await form.getByRole('button', { name: '保存修改', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled();
    await draft.getByRole('button', { name: '处理', exact: true }).click();
    await detail.locator('.return-workbench-notes p').filter({ hasText: '采购退回视觉初版回显验证' }).waitFor();
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(200);
    assert(await detail.evaluate(el => el.getBoundingClientRect().left >= 0 && el.getBoundingClientRect().right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight));
    await shot('returns-detail-mobile'); await close(detail);
    await page.setViewportSize({ width: 1440, height: 900 });
    await rows().filter({ hasText: 'PR-20260700-2' }).getByRole('button', { name: '处理', exact: true }).click();
    await detail.locator('[data-return-detail-items] input[type="number"]').first().waitFor();
    await shot('returns-approve'); await close(detail);
    check('详情内容、编辑回显、审核数量、手机弹窗、确认与取消原因inert');

    await page.getByRole('button', { name: '新增采购退回', exact: true }).click(); await form.waitFor();
    await form.getByRole('button', { name: '保存草稿', exact: true }).click();
    await form.getByText('请至少添加一条退货明细', { exact: true }).waitFor();
    assert.equal(await form.locator('[role="combobox"][aria-invalid="true"]').count(), 2);
    await shot('returns-create-invalid'); await close(form);
    await page.getByRole('button', { name: '新增采购退回', exact: true }).click();
    await remote(form.locator('[role="combobox"]').first(), 'PO');
    await form.locator('[data-return-form-items] [role="combobox"]').waitFor();
    await remote(form.locator('[data-return-form-items] [role="combobox"]'));
    const quantity = form.locator('[data-return-form-items] input[type="number"]').first();
    await quantity.fill('1'); await form.locator('textarea').first().fill('商品外包装破损，申请退回');
    await form.locator('textarea').nth(1).fill('采购退回视觉新增验证');
    await tableStyles(form);
    await form.getByRole('button', { name: '打开日期选择器', exact: true }).click();
    await page.locator('.order-date-picker__content').waitFor(); await shot('returns-date');
    await page.locator('.order-date-picker__day:not(.is-outside)').last().click();
    await form.locator('textarea').first().focus(); await page.waitForTimeout(200);
    assert((await form.locator('textarea').first().evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03'));
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0);
    await shot('returns-create');
    for (const [width, height] of [[1115, 640], [390, 844]]) {
      await page.setViewportSize({ width, height }); await page.waitForTimeout(250);
      const geometry = await form.evaluate(el => ({ left: el.getBoundingClientRect().left, right: el.getBoundingClientRect().right, footer: el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom, scroll: el.querySelector('[data-slot="scroll-area-viewport"]').scrollHeight - el.querySelector('[data-slot="scroll-area-viewport"]').clientHeight }));
      assert(geometry.left >= 0 && geometry.right <= width && geometry.footer <= height && geometry.scroll > 0, `长表单正文滚动且底部可见：${JSON.stringify(geometry)}`);
      await shot(`returns-create-${width}x${height}`);
    }
    await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled();
    assert.equal(await rows().count(), 7);
    check('新增必填、来源与产品搜索、日期、数量、柔和焦点、低高度及手机滚动、Mock保存');

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.getByRole('button', { name: '工作台', exact: true }).click(); await page.locator('[data-page-loading]').waitFor();
    await page.waitForURL(url => url.pathname === '/dashboard'); await page.locator('.app-shell:not(.purchase-pilot-theme)').waitFor(); await settled();
    await page.goto(`${baseUrl}/sales/returns`); await page.locator('[data-return-order-table] tbody tr').first().waitFor(); await settled();
    assert.equal(await page.locator('.purchase-pilot-theme').count(), 1);
    assert(await page.locator('[data-return-order-table] [data-table-sticky-edge]').count() > 0);
    assert.equal(await page.locator('[data-return-order-table] [data-pilot-status-column] span.text-\\[11px\\]').count(), 0);
    await shot('sales-returns-shared-theme');
    await page.goto(`${baseUrl}/purchase/orders`); await page.locator('.purchase-pilot-theme .business-data-table tbody tr').first().waitFor(); await settled();
    const orderStyles = await page.locator('.business-data-table th, .business-data-table tbody tr:first-child td').evaluateAll(elements => elements.map(el => ({ alignment: getComputedStyle(el).textAlign, font: getComputedStyle(el).fontSize })));
    assert(orderStyles.every(style => style.alignment === 'center' && style.font === '14px'));
    await shot('orders-approved-style-regression');
    assert.equal(errors.length, 0, errors.join('\n'));
    check('页面切换动效、工作台主题隔离、销售退货同标准及采购订单回归', { errors });
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify(results, null, 2));
    console.log(`PURCHASE_RETURNS_STYLE_OK: ${results.length} 组检查通过；截图：${evidenceDir}`);
  } catch (error) {
    await shot('failed').catch(() => undefined);
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2));
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
