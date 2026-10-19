// 逐页验证销售外观；写操作只允许项目Mock，最终截图不参与通过条件。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const pageName = process.argv[2] || 'customers';
const dir = path.resolve(`../docs/qa-screenshots/2026-10-18-sales-style/${pageName}`);
fs.mkdirSync(dir, { recursive: true });
let page;
const results = [];
const check = name => { results.push({ name, passed: true }); console.log('PASS', name); };
const rows = () => page.locator('.purchase-list-body .business-data-table tbody tr');
const orderRow = no => rows().filter({ hasText: no.replace(/^SO(\d{8})(.*)$/, 'SO-$1-$2') });
const viewport = () => page.locator('.purchase-list-body > [data-slot="table-container"]');
async function settle() { await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]')); await page.waitForTimeout(300); }
async function shot(name) { await page.waitForTimeout(250); await page.screenshot({ path: path.join(dir, `${name}.png`) }); }
async function close(dialog) { await dialog.locator('[data-slot="dialog-close"]').click(); await dialog.waitFor({ state: 'hidden' }); }
async function loadingFeedback() {
  const overlay = page.locator('.purchase-list-body [data-list-loading]'); await overlay.waitFor();
  const state = await overlay.evaluate(el => ({ busy: el.parentElement.querySelector('[data-table-pagination]').getAttribute('aria-busy') === 'true', inert: el.parentElement.querySelector('[data-slot="table-container"]').inert, leaving: el.classList.contains('page-loading-leave-active'), blur: getComputedStyle(el).backdropFilter }));
  assert(state.busy ? state.inert : state.leaving); assert.equal(state.blur, 'none');
}
async function query(name) { await page.getByRole('button', { name, exact: true }).click(); await loadingFeedback(); await settle(); }
async function size(n) { await page.locator('[data-table-pagination] [role="combobox"]').click(); await page.getByRole('option', { name: `${n} 条`, exact: true }).click(); await loadingFeedback(); await settle(); }
async function action(row, name) { await row.locator('[data-row-actions-trigger]').click(); await page.getByRole('menuitem', { name, exact: true }).click(); }
async function remote(trigger, text, match) { await trigger.click(); const menu = page.locator('[data-remote-search-select-content]'); await menu.locator('input').fill(text); await menu.locator('[data-select-option]:not([disabled])').filter({ hasText: match }).first().click(); await menu.waitFor({ state: 'hidden' }); }
async function layout(columns) {
  for (const [width, height] of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) {
    await page.setViewportSize({ width, height }); await page.locator('main').evaluate(el => { el.scrollTop = 0; });
    await viewport().evaluate(el => { el.scrollTop = 0; el.scrollLeft = 0; }); await settle();
    const value = await viewport().evaluate(el => {
      const table = el.querySelector('table'), row = table.querySelector('tbody tr'), root = document.querySelector('.purchase-pilot-page');
      const cells = [...table.querySelectorAll('th'), ...row.children];
      return { columns: table.querySelectorAll('col').length, overflow: document.documentElement.scrollWidth - innerWidth,
        pageWidth: root.getBoundingClientRect().width, mainWidth: root.parentElement.clientWidth, vertical: el.scrollHeight - el.clientHeight,
        rowHeight: row.getBoundingClientRect().height, styles: cells.map(c => ({ font: getComputedStyle(c).fontSize, align: getComputedStyle(c).textAlign })),
        centers: cells.filter(c => c.innerText.trim()).map(c => { const bounds = []; const walk = document.createTreeWalker(c, NodeFilter.SHOW_TEXT); let node;
          while ((node = walk.nextNode())) { if (!node.textContent.trim() || node.parentElement.closest('.sr-only')) continue; const range = document.createRange(); range.selectNodeContents(node); const r = range.getBoundingClientRect(); if (r.width) bounds.push(r); }
          return bounds.length ? Math.abs((Math.min(...bounds.map(r => r.left)) + Math.max(...bounds.map(r => r.right))) / 2 - (c.getBoundingClientRect().left + c.getBoundingClientRect().width / 2)) : 0; }),
        controls: [...root.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"]')].map(e => e.getBoundingClientRect().height) };
    });
    assert.equal(value.columns, columns); assert(value.overflow <= 1); assert(Math.abs(value.pageWidth - value.mainWidth) <= 1);
    assert(value.vertical <= 1); assert.equal(value.rowHeight, 64); assert(value.styles.every(s => s.font === '14px' && s.align === 'center'));
    assert(value.centers.every(x => x <= 1), JSON.stringify(value.centers)); assert(value.controls.every(h => h === 36));
    assert(await page.locator('[data-list-summary] dd').evaluateAll(values => values.every(el => el.scrollWidth <= el.clientWidth)), '汇总数值应完整可读');
    const pins = await viewport().evaluate(async el => {
      const first = el.querySelector('tbody tr [data-table-sticky-edge="start"]'), last = el.querySelector('tbody tr [data-table-sticky-edge="end"]');
      const x = [first.getBoundingClientRect().x, last.getBoundingClientRect().x]; el.scrollLeft = el.scrollWidth;
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return { delta: [first.getBoundingClientRect().x - x[0], last.getBoundingClientRect().x - x[1]], background: getComputedStyle(first).backgroundColor };
    });
    assert(pins.delta.every(v => Math.abs(v) <= 1)); assert.notEqual(pins.background, 'rgba(0, 0, 0, 0)');
    await viewport().evaluate(el => { el.scrollLeft = 0; }); await shot(`list-${width}x${height}`); check(`${width}字号、真实文字居中、满宽、行高、十条展开与固定列`);
  }
  await page.setViewportSize({ width: 1440, height: 1450 }); await page.locator('main').evaluate(el => { el.scrollTop = 0; }); await shot('overview');
  await page.setViewportSize({ width: 1440, height: 900 });
  await size(20); const tall = await viewport().evaluate(el => el.scrollHeight > el.clientHeight); assert(tall);
  await viewport().evaluate(el => { el.scrollTop = el.scrollHeight; }); assert(await viewport().evaluate(el => Math.abs(el.querySelector('th').getBoundingClientRect().top - el.getBoundingClientRect().top) <= 1));
  await size(50); assert(await viewport().evaluate(el => el.scrollHeight > el.clientHeight)); await size(10);
  assert(await viewport().evaluate(el => el.scrollTop === 0 && el.scrollHeight - el.clientHeight <= 1)); check('10→20→50→10、固定表头与滚动恢复');
}
async function formBounds(form) {
  const numeric = await form.locator('.order-line-table input[type="number"]').evaluateAll(inputs => inputs.map(input => ({
    width: input.getBoundingClientRect().width, cap: input.classList.contains('price-input') ? 104 : 80,
    appearance: getComputedStyle(input).appearance, spinner: getComputedStyle(input, '::-webkit-inner-spin-button').display,
  })));
  assert(numeric.every(input => input.width <= input.cap + 1 && input.width >= 48 && input.appearance === 'textfield'), JSON.stringify(numeric));
  const currencies = await form.locator('.relative:has(> input[type="number"]):has(> span)').evaluateAll(groups => groups.map(group => {
    const symbol = group.querySelector('span').getBoundingClientRect(), input = group.querySelector('input').getBoundingClientRect();
    return { gap: input.left - symbol.right, center: Math.abs(symbol.y + symbol.height / 2 - input.y - input.height / 2) };
  }));
  assert(currencies.every(item => item.gap >= 7 && item.gap <= 9 && item.center <= 1), JSON.stringify(currencies));
  if (numeric.length > 1) {
    await form.locator('.order-line-table input[type="number"]').first().hover();
    const quantityCell = await form.locator('.order-line-table tbody tr').first().locator('td').nth(1).boundingBox();
    const priceCell = await form.locator('.order-line-table tbody tr').first().locator('td').nth(2).boundingBox();
    await page.screenshot({ path: path.join(dir, 'numeric-inputs.png'), clip: { x: quantityCell.x, y: quantityCell.y, width: priceCell.x + priceCell.width - quantityCell.x, height: quantityCell.height } });
  }
  const typography = await form.evaluate(el => ({
    title: getComputedStyle(el.querySelector('[data-slot="dialog-title"]')).fontSize,
    labels: [...el.querySelectorAll('[data-slot="label"]')].map(node => getComputedStyle(node).fontSize),
    controls: [...el.querySelectorAll('input[data-slot="input"], textarea, [role="combobox"]')].map(node => ({ font: getComputedStyle(node).fontSize, height: node.getBoundingClientRect().height, tag: node.tagName })),
  }));
  assert.equal(typography.title, '16px'); assert(typography.labels.every(v => v === '13px'));
  assert(typography.controls.every(v => v.font === '13px' && (v.tag === 'TEXTAREA' || v.height === 36)), JSON.stringify(typography));
  for (const [width, height] of [[1115, 640], [390, 844]]) {
    await page.setViewportSize({ width, height }); await settle();
    assert(await form.evaluate(el => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight && el.querySelector('[data-slot="scroll-area-viewport"]').clientHeight > 0; }));
    await shot(`form-${width}x${height}`);
  }
  await page.setViewportSize({ width: 1440, height: 900 });
}
async function customers() {
  await layout(8);
  await page.getByPlaceholder('请输入名称', { exact: true }).fill('上海'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('上海'))); await query('重置');
  await page.getByPlaceholder('如 C001').fill('C001'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  await page.getByPlaceholder('请输入联系人').fill('不存在联系人'); await query('查询'); assert((await rows().innerText()).includes('暂无客户')); await query('重置');
  await page.locator('[data-list-filter-panel] [role="combobox"]').click(); await shot('status-options'); await page.getByRole('option', { name: '停用', exact: true }).click(); await query('查询');
  assert((await rows().allTextContents()).every(t => t.includes('停用'))); await query('重置'); await query('刷新');
  check('四个筛选、下拉、空态、重置及刷新');
  const target = rows().filter({ hasText: 'C001' });
  await target.getByRole('button', { name: '查看', exact: true }).click(); const detail = page.locator('[data-customer-detail-dialog]'); await detail.waitFor();
  for (const text of ['档案与结算', '联系与备注', '最后维护人', '信用额度', '地址', '备注']) assert((await detail.innerText()).includes(text)); await shot('detail'); await close(detail);
  await action(target, '编辑'); const form = page.locator('[data-customer-form-dialog]'); await form.waitFor();
  assert.equal(await form.locator('input').first().inputValue(), 'C001'); assert(await form.locator('input').first().getAttribute('readonly') !== null);
  await form.locator('textarea').fill('客户外观回显验证'); await shot('edit'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await target.getByRole('button', { name: '查看', exact: true }).click(); await detail.getByText('客户外观回显验证', { exact: true }).waitFor(); await close(detail);
  await action(target, '停用'); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); await shot('status-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' });
  await target.getByRole('checkbox').click(); await page.getByRole('button', { name: '批量删除', exact: true }).click(); await confirm.waitFor(); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await target.getByRole('checkbox').click();
  check('详情内容、编辑保存回显、只读编码、停用及批量确认');
  await page.getByRole('button', { name: '新增客户', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.getByText('请输入客户名称', { exact: true }).waitFor();
  await page.waitForTimeout(200); assert.equal(await form.locator('input[aria-invalid="true"]').evaluate(el => getComputedStyle(el).borderColor), 'rgb(207, 154, 162)'); await shot('create-invalid'); await close(form); await page.getByRole('button', { name: '新增客户', exact: true }).click(); await form.waitFor();
  const fields = form.locator('input'); const newName = `外观验收客户${Date.now()}`;
  await fields.nth(1).fill(newName); await fields.nth(2).fill('李明'); await fields.nth(3).fill('13800138000'); await fields.nth(4).fill('80000'); await fields.nth(5).fill('上海市客户地址'); await form.locator('textarea').fill('保留全部客户档案字段');
  await fields.nth(2).focus(); await page.waitForTimeout(200); assert((await fields.nth(2).evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03')); await shot('create'); await formBounds(form);
  await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await page.getByPlaceholder('请输入名称', { exact: true }).fill(newName); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('80000.00'));
  await action(rows(), '编辑'); await form.waitFor(); await form.locator('[role="combobox"]').click(); await page.getByRole('option', { name: '停用', exact: true }).click(); await form.getByRole('button', { name: '保存', exact: true }).click(); await confirm.waitFor(); assert(await form.evaluate(el => el.inert));
  await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); assert(!(await form.evaluate(el => el.inert))); await close(form); await query('重置');
  check('新增必填、全部字段保存回显、柔和焦点、长弹窗及二次确认inert');
}
async function orders() {
  await layout(9);
  assert.equal(await page.locator('[data-pilot-status-column] span.text-\\[11px\\]').count(), 0);
  await page.getByPlaceholder('如 SO202606001').fill('SO202607003'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  const filters = page.locator('[data-list-filter-panel] [data-remote-search-select-trigger]');
  const remotes = page.locator('[data-list-filter-panel] [role="combobox"]:not([data-anchored-select-trigger])');
  await remote(remotes.nth(0), '上海', '上海林间'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('上海林间'))); await query('重置');
  await remote(remotes.nth(1), 'WH001', '华东中心仓'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('华东中心仓'))); await query('重置');
  await page.locator('[data-list-filter-panel] [data-anchored-select-trigger]').click(); await shot('status-options'); await page.getByRole('option', { name: '待审核', exact: true }).click(); await query('查询'); assert.equal(await rows().count(), 2); await query('重置');
  await page.getByPlaceholder('如 SO202606001').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置'); await query('刷新');
  const beforePage = await rows().allTextContents();
  await page.locator('[data-slot="pagination-next"]').click(); await loadingFeedback(); assert.deepEqual(await rows().allTextContents(), beforePage); await shot('next-loading'); await settle(); assert.equal(await rows().count(), 1);
  await page.locator('[data-slot="pagination-previous"]').click(); await loadingFeedback(); await settle(); check('四筛选、远程搜索、状态单标签、空态、刷新及分页平滑加载');
  const detail = page.locator('.sales-order-workbench'), form = page.locator('[data-sales-form-dialog]'), confirm = page.locator('[data-confirm-dialog]');
  await orderRow('SO202607001').getByRole('button', { name: '查看', exact: true }).click(); await detail.getByText('业务信息', { exact: true }).waitFor();
  for (const text of ['商品明细', '流程记录', '客户', '销售单号', '预计发货', '制单人', '提交人', '审核人', '已锁定', '已出库', '待出库', '明细备注']) assert((await detail.innerText()).includes(text));
  assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)');
  assert((await detail.locator('th').evaluateAll(cells => cells.map(c => [getComputedStyle(c).fontSize, getComputedStyle(c).textAlign]))).every(s => s[0] === '14px' && s[1] === 'center')); await shot('detail');
  await page.setViewportSize({ width: 390, height: 844 }); await settle(); assert(await detail.evaluate(el => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight; })); await shot('detail-mobile'); await close(detail); await page.setViewportSize({ width: 1440, height: 900 });
  await orderRow('SO202607003').getByRole('button', { name: '处理', exact: true }).click(); await detail.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor();
  assert.equal(await form.locator('[data-sales-form-items] input[type="number"]').first().inputValue(), '6'); await form.locator('textarea').fill('销售草稿外观回显'); await shot('edit'); await form.getByRole('button', { name: '保存修改', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await orderRow('SO202607003').getByRole('button', { name: '处理', exact: true }).click(); await detail.getByText('销售草稿外观回显', { exact: true }).waitFor();
  await detail.getByRole('button', { name: '提交销售单', exact: true }).click(); await confirm.waitFor(); assert(await detail.evaluate(el => el.inert)); await shot('submit-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); assert(!(await detail.evaluate(el => el.inert))); await close(detail); check('详情全部业务字段、手机详情、编辑回显及提交确认inert');
  await page.getByRole('button', { name: '新增销售单', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.getByText('请选择出库仓库', { exact: true }).waitFor();
  await page.waitForTimeout(200); assert.equal(await form.locator('[role="combobox"][aria-invalid="true"]').first().evaluate(el => getComputedStyle(el).borderColor), 'rgb(207, 154, 162)'); await shot('create-invalid'); await close(form); await page.getByRole('button', { name: '新增销售单', exact: true }).click(); await form.waitFor();
  await remote(form.locator('[role="combobox"]').nth(0), 'C001', '上海林间'); await remote(form.locator('[role="combobox"]').nth(1), 'WH001', '华东中心仓');
  await form.getByRole('button', { name: '打开日期选择器' }).click(); await page.locator('.order-date-picker__content').waitFor(); await shot('date-picker'); await page.locator('.order-date-picker__day:not(.is-outside)').last().click();
  await form.locator('textarea').fill('新增销售外观验收'); await form.getByRole('button', { name: '添加产品', exact: true }).click();
  await remote(form.locator('[data-sales-form-items] [role="combobox"]'), 'P000001', '经典原味苏打水'); await form.locator('[data-sales-form-items] input[type="number"]').first().fill('1');
  await form.locator('textarea').focus(); await page.waitForTimeout(200); assert((await form.locator('textarea').evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03'));
  await form.getByRole('button', { name: '添加产品', exact: true }).click(); const lines = form.locator('[data-sales-form-items] tbody tr'); assert.equal(await lines.count(), 2); await lines.last().getByRole('button', { name: '删除', exact: true }).click(); assert.equal(await lines.count(), 1);
  await shot('create'); await formBounds(form); await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  const created = orderRow('SO202607008'); await created.getByRole('button', { name: '处理', exact: true }).click(); await detail.getByText('新增销售外观验收', { exact: true }).waitFor();
  await detail.getByRole('button', { name: '提交销售单', exact: true }).click(); await confirm.waitFor(); await confirm.getByRole('button', { name: '提交', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await detail.waitFor({ state: 'hidden' }); await settle(); assert((await created.innerText()).includes('待审核'));
  await created.getByRole('button', { name: '处理', exact: true }).click(); await detail.getByRole('button', { name: '审核通过', exact: true }).click(); await confirm.waitFor(); await shot('approve-confirm'); await confirm.getByRole('button', { name: '审核通过', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await settle(); assert((await created.innerText()).includes('待出库'));
  await orderRow('SO202607003').getByRole('button', { name: '处理', exact: true }).click(); await detail.getByRole('button', { name: '取消销售单', exact: true }).click(); await confirm.waitFor(); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await close(detail);
  check('新增校验、日期与产品选择、增删明细、Mock保存提交审核、取消确认及柔和焦点');
}
async function returns() {
  await layout(9);
  const filters = page.locator('[data-list-filter-panel]'), detail = page.locator('[data-return-detail-dialog]'), form = page.locator('[data-return-form-dialog]');
  await filters.locator('input').first().fill('SR202607001'); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  await filters.locator('input').nth(1).fill('SO202607002'); await query('查询'); assert.equal(await rows().count(), 3); await query('重置');
  const remotes = filters.locator('[role="combobox"]:not([data-anchored-select-trigger])');
  await remote(remotes.nth(0), '南京', '南京星火'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('南京星火')));
  await remote(remotes.nth(1), 'WH002', '华南中心仓'); await query('查询'); assert.equal(await rows().count(), 3);
  await filters.locator('[data-anchored-select-trigger]').click(); await shot('status-options'); await page.getByRole('option', { name: '待审核', exact: true }).click(); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  await filters.locator('input').first().fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无销售退货单')); await query('重置'); await query('刷新'); check('五筛选、远程搜索、AND组合、空态与平滑加载');
  const draft = rows().filter({ hasText: 'SR-20260700-1' }); await draft.getByRole('button', { name: '处理', exact: true }).click(); await detail.getByText('业务信息', { exact: true }).waitFor();
  for (const text of ['商品明细', '业务进度', '业务信息', '原销售单', '处理方式', '退回原因', '申请退回', '审核退回', '已退货入库', '待退货入库', '流程记录', '原因说明']) assert((await detail.innerText()).includes(text), text);
  assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)'); await shot('detail');
  const confirm = page.locator('[data-confirm-dialog]'), prompt = page.locator('[data-prompt-dialog]');
  await detail.getByRole('button', { name: '提交销售退货', exact: true }).click(); await confirm.waitFor(); assert(await detail.evaluate(el => el.inert)); await shot('submit-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' });
  await detail.getByRole('button', { name: '取消销售退货', exact: true }).click(); await prompt.waitFor(); assert(await detail.evaluate(el => el.inert)); assert.equal(await page.locator('[data-slot="dialog-overlay"]').last().evaluate(el => getComputedStyle(el).backdropFilter), 'none'); await shot('cancel-reason'); await prompt.getByRole('button', { name: '取消', exact: true }).click(); await prompt.waitFor({ state: 'hidden' });
  await detail.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); await form.locator('[data-return-form-items] input[type="number"]').first().fill('1'); await form.locator('textarea').nth(1).fill('销售退货外观回显'); await shot('edit'); await form.getByRole('button', { name: '保存修改', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await draft.getByRole('button', { name: '处理', exact: true }).click(); await detail.locator('.return-workbench-notes p').filter({ hasText: '销售退货外观回显' }).waitFor(); await page.setViewportSize({ width: 390, height: 844 }); await settle();
  assert(await detail.evaluate(el => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight; })); await shot('detail-mobile'); await close(detail); await page.setViewportSize({ width: 1440, height: 900 });
  await rows().filter({ hasText: 'SR-20260700-2' }).getByRole('button', { name: '处理', exact: true }).click(); await detail.locator('[data-return-detail-items] input[type="number"]').first().waitFor(); await shot('approve'); await close(detail); check('详情内容保留、编辑回显、审核数量、手机详情及双层确认inert');
  await page.getByRole('button', { name: '新增销售退货', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.getByText('请至少添加一条退货明细', { exact: true }).waitFor(); await page.waitForTimeout(200); await shot('create-invalid'); await close(form);
  await page.getByRole('button', { name: '新增销售退货', exact: true }).click(); await form.waitFor(); await remote(form.locator('[role="combobox"]').first(), 'SO202606002', 'SO202606002');
  await remote(form.locator('[data-return-form-items] [role="combobox"]'), '', ''); await form.locator('[data-return-form-items] input[type="number"]').first().fill('1');
  await form.locator('textarea').first().fill('包装破损，登记客户退货'); await form.locator('textarea').nth(1).fill('新增销售退货外观验收');
  await form.getByRole('button', { name: '打开日期选择器', exact: true }).click(); await page.locator('.order-date-picker__content').waitFor(); await shot('date-picker'); await page.locator('.order-date-picker__day:not(.is-outside)').last().click();
  assert((await form.locator('th').evaluateAll(cells => cells.map(c => [getComputedStyle(c).fontSize, getComputedStyle(c).textAlign]))).every(s => s[0] === '13px' && s[1] === 'center'));
  await form.locator('textarea').first().focus(); await page.waitForTimeout(200); assert((await form.locator('textarea').first().evaluate(el => getComputedStyle(el).boxShadow)).includes('0.03')); await page.waitForFunction(() => !document.querySelector('[data-sonner-toast]')); await shot('create'); await formBounds(form);
  await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle(); assert.equal(await rows().count(), 7);
  await rows().filter({ hasText: '成都青柠商贸' }).getByRole('button', { name: '处理', exact: true }).click(); await detail.locator('.return-workbench-notes p').filter({ hasText: '新增销售退货外观验收' }).waitFor(); await close(detail); check('新增必填、原单与产品、日期、低高度和手机长表单、柔和聚焦及Mock保存回显');
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto(`${base}/sales/${pageName}`);
    if (page.url().includes('/login')) { await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456'); await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(u => !u.pathname.includes('/login')); await page.goto(`${base}/sales/${pageName}`); }
    await rows().first().waitFor(); await settle(); assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin');
    if (pageName === 'customers') await customers(); else if (pageName === 'orders') await orders(); else if (pageName === 'returns') await returns(); else throw new Error(`尚未实现该页核验：${pageName}`);
    assert.equal(errors.length, 0, errors.join('\n')); check('无页面脚本错误'); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(results, null, 2)); console.log(`SALES_STYLE_OK ${pageName}: ${results.length}组`);
  } catch (error) { await shot('failed').catch(() => undefined); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error; } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
