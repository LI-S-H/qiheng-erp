// 供应商单页外观及原交互验证；所有保存只允许 Mock 会话。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const evidenceDir = path.resolve(process.env.QA_SCREENSHOT_DIR || '../docs/qa-screenshots/2026-10-18-supplier-style');
fs.mkdirSync(evidenceDir, { recursive: true });
const results = [];
let page;
const check = (name, detail = '通过') => { results.push({ name, detail, passed: true }); console.log('PASS', name); };
const rows = () => page.locator('.supplier-table tbody tr');
const viewport = () => page.locator('.supplier-pilot-page .purchase-list-body > [data-slot="table-container"]');
async function settled() {
  await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]') && [...document.querySelectorAll('[data-list-filter-actions] button')].every(button => !button.disabled));
  await page.waitForTimeout(250);
}
async function shot(name) { await page.waitForTimeout(250); await page.screenshot({ path: path.join(evidenceDir, `${name}.png`) }); }
async function close(dialog) { await dialog.locator('[data-slot="dialog-close"]').click(); await dialog.waitFor({ state: 'hidden' }); }
async function query(name) {
  await page.getByRole('button', { name, exact: true }).click();
  assert(await page.getByRole('button', { name: '重置', exact: true }).isDisabled());
  assert(await viewport().evaluate(el => el.inert)); await settled();
}
async function action(row, name) { await row.locator('[data-row-actions-trigger]').click(); await page.getByRole('menuitem', { name, exact: true }).click(); }
async function layout(width, height) {
  await page.setViewportSize({ width, height }); await page.locator('main').evaluate(el => { el.scrollTop = 0; });
  await viewport().evaluate(el => { el.scrollLeft = 0; el.scrollTop = 0; });
  await page.mouse.move(0, 0); await page.waitForTimeout(300);
  const state = await viewport().evaluate(el => {
    const table = el.querySelector('table'); const row = table.querySelector('tbody tr');
    const root = document.querySelector('.supplier-pilot-page'); const main = root.parentElement;
    const cells = [...table.querySelectorAll('th'), ...row.children];
    const centerOffsets = cells.flatMap(cell => {
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT); const rects = []; let node;
      while ((node = walker.nextNode())) {
        if (!node.textContent.trim() || node.parentElement.closest('.sr-only')) continue;
        const range = document.createRange(); range.selectNodeContents(node); rects.push(range.getBoundingClientRect());
      }
      const box = cell.getBoundingClientRect();
      if (cell.matches('.supplier-table__actions') && rects.length) return [Math.abs((Math.min(...rects.map(r => r.left)) + Math.max(...rects.map(r => r.right)) - box.left - box.right) / 2)];
      return rects.map(rect => Math.abs((rect.left + rect.right - box.left - box.right) / 2));
    });
    const checkbox = row.querySelector('[role="checkbox"]').getBoundingClientRect(); const selection = row.cells[0].getBoundingClientRect();
    const actions = row.lastElementChild; const actionBox = actions.getBoundingClientRect();
    return {
      overflow: document.documentElement.scrollWidth - innerWidth, mainOverflow: main.scrollWidth - main.clientWidth,
      pageWidth: root.getBoundingClientRect().width, mainWidth: main.clientWidth, left: root.getBoundingClientRect().left - main.getBoundingClientRect().left,
      align: cells.map(cell => getComputedStyle(cell).textAlign), fonts: cells.map(cell => getComputedStyle(cell).fontSize),
      centerOffsets, checkboxOffset: (checkbox.left + checkbox.right - selection.left - selection.right) / 2,
      rowHeight: row.getBoundingClientRect().height, count: table.querySelectorAll('col').length,
      widths: [...row.children].map(cell => cell.getBoundingClientRect().width),
      controls: [...root.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"], [data-list-filter-actions] button')].map(control => control.getBoundingClientRect().height),
      actionFits: [...actions.querySelectorAll('button')].every(button => { const r = button.getBoundingClientRect(); return r.left >= actionBox.left && r.right <= actionBox.right; }),
      footerBottom: root.querySelector('[data-table-pagination]').getBoundingClientRect().bottom,
      verticalOverflow: el.scrollHeight - el.clientHeight,
      allRowsWithinTable: table.querySelector('tbody tr:last-child').getBoundingClientRect().bottom <= el.getBoundingClientRect().bottom + 1,
    };
  });
  assert(state.overflow <= 1 && state.mainOverflow <= 1, `外层不得溢出：${JSON.stringify(state)}`);
  assert(Math.abs(state.pageWidth - state.mainWidth) <= 1 && Math.abs(state.left) <= 1, '页面必须满宽');
  assert(state.align.every(value => value === 'center') && state.fonts.every(value => value === '14px'), '表头及数据14px并居中');
  assert(state.centerOffsets.every(value => value <= 1), `实际文字应居中：${state.centerOffsets}`);
  assert(Math.abs(state.checkboxOffset) <= 1, `勾选框应居中：${state.checkboxOffset}`);
  assert.equal(state.count, 13); assert.equal(state.rowHeight, 64); assert.equal(state.widths[0], 44);
  assert(state.actionFits, '查看和更多必须放入操作列内'); assert(state.controls.every(value => value === 36));
  assert(state.verticalOverflow <= 1 && state.allRowsWithinTable, '默认10条必须完整展开，不能在表格内部纵向滚动');
  await shot(`suppliers-${width}x${height}`);
  const sticky = await viewport().evaluate(async el => {
    const first = el.querySelector('tbody [data-table-sticky-edge="start"]'); const last = el.querySelector('tbody [data-table-sticky-edge="end"]');
    const coords = () => ({ first: first.getBoundingClientRect().x, last: last.getBoundingClientRect().x }); const before = coords();
    el.scrollLeft = el.scrollWidth; el.scrollTop = 160; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return { before, after: coords(), top: el.getBoundingClientRect().top, header: el.querySelector('th').getBoundingClientRect().top, background: getComputedStyle(last).backgroundColor, scrollLeft: el.scrollLeft };
  });
  assert(Math.abs(sticky.before.first - sticky.after.first) <= 1 && Math.abs(sticky.before.last - sticky.after.last) <= 1);
  assert(Math.abs(sticky.top - sticky.header) <= 1 && !sticky.background.includes('rgba'));
  await shot(`suppliers-${width}x${height}-scroll`); check(`${width}x${height} 字号、字形居中、列宽、满宽及固定滚动`, { state, sticky });
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = [];
    page.on('pageerror', error => { errors.push(error.message); console.error('PAGE_ERROR', error.message); }); await page.goto(`${baseUrl}/purchase/suppliers`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456');
      await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(url => !url.pathname.includes('/login'));
      await page.goto(`${baseUrl}/purchase/suppliers`);
    }
    await rows().first().locator('[role="checkbox"]').waitFor(); await settled(); await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin'); assert.equal(await rows().count(), 6);
    for (const size of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) await layout(...size);
    await page.setViewportSize({ width: 1440, height: 900 }); await viewport().evaluate(el => { el.scrollLeft = 0; el.scrollTop = 0; });
    await page.waitForTimeout(250);
    const originalWidths = await page.locator('.supplier-table col').evaluateAll(cols => cols.map(col => col.getBoundingClientRect().width));
    await page.getByRole('button', { name: '列设置', exact: true }).click(); const settings = page.locator('.column-preferences-canvas');
    await settings.waitFor();
    assert(await settings.evaluate(el => el.getBoundingClientRect().top >= 0 && el.getBoundingClientRect().bottom <= innerHeight), '列设置应按可用高度收缩，不能截断标题或底部');
    await shot('suppliers-columns');
    await settings.getByRole('switch', { name: '切换供应商显示', exact: true }).click();
    await settings.getByRole('switch', { name: '切换服务分显示', exact: true }).click(); await page.waitForTimeout(200);
    assert.equal(await page.locator('.supplier-table col').count(), 11);
    await page.keyboard.press('Escape'); await settings.waitFor({ state: 'hidden' }); await page.reload();
    await rows().first().locator('[role="checkbox"]').waitFor(); await settled(); assert.equal(await page.locator('.supplier-table col').count(), 11);
    await page.getByRole('button', { name: '列设置', exact: true }).click();
    await settings.getByRole('button', { name: '恢复默认设置', exact: true }).click(); await page.keyboard.press('Escape');
    await settings.waitFor({ state: 'hidden' }); await page.waitForTimeout(200); assert.equal(await page.locator('.supplier-table col').count(), 13);
    const restored = await page.locator('.supplier-table col').evaluateAll(cols => cols.map(col => col.getBoundingClientRect().width));
    assert(restored.every((width, index) => Math.abs(width - originalWidths[index]) <= 1), '恢复字段后字宽缓存不能错配');
    check('列隐藏、恢复、持久化及字段缓存');

    const filter = page.locator('.supplier-pilot-page [data-list-filter-panel]');
    const code = page.getByPlaceholder('请输入供应商编码'); await code.focus(); await page.waitForTimeout(200);
    const focus = await code.evaluate(el => ({ border: getComputedStyle(el).borderColor, shadow: getComputedStyle(el).boxShadow }));
    assert.equal(focus.border, 'rgb(182, 194, 210)'); assert(focus.shadow.includes('0.03')); await shot('suppliers-focus');
    await code.fill('S001'); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('华东')); await query('重置');
    await page.getByPlaceholder('请输入供应商名称').fill('森纸'); await page.getByPlaceholder('请输入联系人').fill('宋'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
    await filter.locator('[data-anchored-select-trigger]').nth(1).click(); await shot('suppliers-status-dropdown');
    await page.getByRole('option', { name: '停用', exact: true }).click(); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
    await filter.locator('[data-anchored-select-trigger]').first().click(); await page.getByRole('option', { name: '已就绪', exact: true }).click(); await query('查询');
    assert((await rows().innerText()).includes('暂无')); await shot('suppliers-empty'); await query('重置');
    await filter.locator('.supplier-score-range').first().locator('input').first().fill('90'); await query('查询'); assert.equal(await rows().count(), 3); await query('重置');
    await page.getByRole('textbox', { name: '评分样本金额最低值', exact: true }).fill('0'); await query('查询');
    await query('重置'); await query('刷新');
    await page.locator('[data-table-pagination] [role="combobox"]').click(); await page.getByRole('option', { name: '20 条', exact: true }).click(); await settled(); assert.equal(await rows().count(), 6);
    const target = rows().filter({ hasText: '华东饮品供应链' }); await target.getByRole('checkbox').click();
    assert.equal(await page.getByRole('button', { name: '批量停用', exact: true }).isDisabled(), false);
    await page.getByRole('button', { name: '批量停用', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); await shot('suppliers-batch-confirm');
    await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' });
    await target.getByRole('checkbox').click(); check('全部筛选保留、范围筛选、焦点、空态、刷新、条数和批量确认', focus);

    await target.getByRole('button', { name: '查看', exact: true }).click(); const detail = page.locator('[data-supplier-detail-workbench]');
    await detail.getByText('基础资料', { exact: true }).waitFor(); const detailText = await detail.innerText();
    for (const text of ['综合评分', '服务评分', '交付评分', '质量评分', '价格评分', '评分样本金额', '基础资料', '联系地址', '付款条件', '备注', '评分与样本', '服务分调整原因', '维护信息', '创建时间', '最后更新时间', '最后更新人']) assert(detailText.includes(text));
    assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)'); await shot('suppliers-detail');
    assert(await detail.locator('.supplier-detail-facts > div').evaluateAll(elements => elements.every(el => el.querySelector('dt').getBoundingClientRect().right < el.querySelector('dd').getBoundingClientRect().left)), '详情字段名和值不能碰撞');
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(200); await shot('suppliers-detail-mobile');
    assert(await detail.evaluate(el => el.getBoundingClientRect().left >= 0 && el.getBoundingClientRect().right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight));
    await close(detail); await page.setViewportSize({ width: 1440, height: 900 });
    await action(target, '编辑'); const form = page.locator('[data-supplier-form-dialog]'); await form.waitFor();
    assert.equal(await form.getByPlaceholder('请输入供应商名称').inputValue(), '华东饮品供应链');
    await form.locator('textarea').fill('供应商简约外观初版回显验证'); await shot('suppliers-edit');
    await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled();
    await target.getByRole('button', { name: '查看', exact: true }).click(); await detail.getByText('供应商简约外观初版回显验证', { exact: true }).waitFor(); await close(detail);
    await action(target, '调整服务分'); const score = page.locator('[data-supplier-score-dialog]'); await score.waitFor();
    await score.getByRole('button', { name: '确认调整', exact: true }).click(); await page.getByText('请填写本次调整原因', { exact: true }).waitFor();
    await score.locator('input').fill('90'); await score.locator('textarea').fill('视觉验证：合作服务评估'); await shot('suppliers-service-score');
    await score.getByRole('button', { name: '确认调整', exact: true }).click(); await score.waitFor({ state: 'hidden' }); await settled(); assert((await target.innerText()).includes('90.00'));
    await action(target, '评分变更记录'); const log = page.locator('[data-score-change-log-dialog]'); await log.waitFor(); await settled();
    assert.equal(await log.getAttribute('data-slot'), 'dialog-content');
    assert((await log.locator('th').evaluateAll(elements => elements.map(el => getComputedStyle(el).textAlign))).every(value => value === 'center'));
    await log.getByText('指标类型', { exact: true }).waitFor();
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0);
    await shot('suppliers-score-log'); await log.locator('[data-anchored-select-trigger]').first().click(); await page.getByRole('option', { name: '服务分', exact: true }).click();
    await log.getByRole('button', { name: '查询', exact: true }).click(); await settled(); await shot('suppliers-score-log-filtered'); await close(log);
    check('详情内容、手机详情、编辑回显、服务分校验保存及评分日志');

    await page.getByRole('button', { name: '新增供应商', exact: true }).click(); await form.waitFor();
    await form.getByRole('button', { name: '保存', exact: true }).click(); await page.getByText('请输入供应商名称', { exact: true }).waitFor();
    const createdName = '视觉初版供应商（华东仓配服务中心）';
    await form.getByPlaceholder('请输入供应商名称').fill(createdName);
    await form.locator('input').nth(1).fill('测试联系人'); await form.locator('input').nth(2).fill('021-12345678');
    await form.locator('input[type="number"]').fill('80'); await form.getByPlaceholder('填写初始服务分时必填').fill('初始合作评估');
    await form.locator('textarea').fill('仅项目Mock会话的外观验证'); await form.locator('textarea').focus(); await page.waitForTimeout(200);
    assert((await form.locator('textarea').evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03'));
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0); await shot('suppliers-create');
    for (const [width, height] of [[1115, 640], [390, 844]]) {
      await page.setViewportSize({ width, height }); await page.waitForTimeout(200);
      assert(await form.evaluate(el => { const r = el.getBoundingClientRect(); const area = el.querySelector('[data-slot="scroll-area-viewport"]'); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight && area.scrollHeight > area.clientHeight; }));
      await shot(`suppliers-create-${width}x${height}`);
    }
    await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settled(); assert.equal(await rows().count(), 7);
    await page.setViewportSize({ width: 1440, height: 900 }); assert.equal(await page.locator('.supplier-table [title]').filter({ hasText: createdName }).count(), 1);
    await target.getByRole('checkbox').click(); await page.getByRole('button', { name: '删除', exact: true }).click(); await confirm.waitFor();
    await confirm.getByRole('button', { name: '批量删除', exact: true }).click();
    await page.getByText('所选供应商中存在已被业务引用的数据', { exact: true }).waitFor();
    assert.equal(await target.count(), 1, '已有引用的供应商不得删除');
    // 当前 Mock 以统一错误拒绝删除，不返回部分失败数组；单独调用原显示函数验证失败弹窗外观。
    const injected = await page.evaluate(() => {
      let instance = document.querySelector('.supplier-pilot-page').__vueParentComponent;
      while (instance && typeof instance.setupState?.showDeleteFailures !== 'function') instance = instance.parent;
      if (!instance) return false;
      instance.setupState.showDeleteFailures('批量删除未完成', [{ supplierId: '2010000000000000001', reason: '供应商已被供货关系或采购订单引用，无法删除' }]);
      return true;
    });
    assert(injected, '开发态应能调用已有失败明细显示函数');
    const failure = page.locator('[data-supplier-failure-dialog]'); await failure.waitFor();
    await page.waitForFunction(() => document.querySelectorAll('[data-sonner-toast]').length === 0);
    await shot('suppliers-delete-failure'); await close(failure);
    check('新增必填、长名称、初始服务分、低高度及手机滚动、Mock保存、引用删除保护及失败弹窗表面', { failureSurface: '仅开发态调用已有显示函数注入失败数组，未模拟真实后端部分删除流程' });

    for (const route of ['/purchase/orders', '/purchase/returns']) {
      await page.goto(`${baseUrl}${route}`); await page.locator('.purchase-pilot-theme .business-data-table tbody tr').first().waitFor(); await settled();
      assert.equal(await page.locator('.business-data-table col').count(), 9);
      assert((await page.locator('.business-data-table th, .business-data-table tbody tr:first-child td').evaluateAll(elements => elements.map(el => ({ align: getComputedStyle(el).textAlign, font: getComputedStyle(el).fontSize })))).every(style => style.align === 'center' && style.font === '14px'));
      await shot(route.endsWith('/orders') ? 'orders-regression' : 'returns-regression');
    }
    await page.getByRole('button', { name: '工作台', exact: true }).click(); await page.locator('[data-page-loading]').waitFor(); await page.waitForURL(url => url.pathname === '/dashboard');
    await page.locator('.app-shell:not(.purchase-pilot-theme)').waitFor(); await settled();
    await page.goto(`${baseUrl}/dashboard`); await page.getByRole('heading', { name: '工作台', exact: true }).waitFor(); await settled();
    assert.equal(await page.locator('.purchase-pilot-theme').count(), 0); assert.equal(errors.length, 0, errors.join('\n'));
    check('已认可两页回归、九列默认算法、路由动效、其它页主题隔离及脚本错误', { errors });
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify(results, null, 2)); console.log(`SUPPLIER_STYLE_OK: ${results.length} 组检查通过；截图：${evidenceDir}`);
  } catch (error) {
    await shot('failed').catch(() => undefined); fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
