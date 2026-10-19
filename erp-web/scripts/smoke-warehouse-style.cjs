// 逐页验证销售外观；写操作只允许项目Mock，最终截图不参与通过条件。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const pageName = process.argv[2] || 'warehouses';
const dir = path.resolve(`../docs/qa-screenshots/2026-10-18-warehouse-style/${pageName}`);
fs.mkdirSync(dir, { recursive: true });
let page;
const results = [];
const check = name => { results.push({ name, passed: true }); console.log('PASS', name); };
const rows = () => page.locator('.purchase-list-body .business-data-table > tbody > tr:not([data-stock-bill-detail-host-id]):not([data-stock-ledger-detail-host-id])');
const orderRow = no => rows().filter({ hasText: no.replace(/^SO(\d{8})(.*)$/, 'SO-$1-$2') });
const viewport = () => page.locator('.purchase-list-body > [data-slot="table-container"], .purchase-list-body > .stock-bill-table-scroll > [data-slot="table-container"], .purchase-list-body .stock-ledger-table-scroll > [data-slot="table-container"]');
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
async function remote(trigger, text, match) { await trigger.click(); const menu = page.locator('[data-remote-search-select-content]'); await menu.locator('input').fill(text); await page.waitForTimeout(500); await menu.locator('[data-select-option]:not([disabled])').filter({ hasText: match }).first().click(); await menu.waitFor({ state: 'hidden' }); }
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
        centers: cells.filter(c => c.innerText.trim() && !c.querySelector('button[aria-expanded]')).map(c => { const bounds = []; const walk = document.createTreeWalker(c, NodeFilter.SHOW_TEXT); let node;
          while ((node = walk.nextNode())) { if (!node.textContent.trim() || node.parentElement.closest('.sr-only')) continue; const range = document.createRange(); range.selectNodeContents(node); const r = range.getBoundingClientRect(); if (r.width) bounds.push(r); }
          return bounds.length ? Math.abs((Math.min(...bounds.map(r => r.left)) + Math.max(...bounds.map(r => r.right))) / 2 - (c.getBoundingClientRect().left + c.getBoundingClientRect().width / 2)) : 0; }),
        controls: [...root.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"]')].map(e => e.getBoundingClientRect().height) };
    });
    assert.equal(value.columns, columns); assert(value.overflow <= 1); assert(Math.abs(value.pageWidth - value.mainWidth) <= 1);
    assert(value.vertical <= 1); assert.equal(value.rowHeight, 64); assert(value.styles.every(s => s.font === '14px' && s.align === 'center'));
    assert(value.centers.every(x => x <= 1), JSON.stringify(value.centers)); assert(value.controls.every(h => h === 36));
    const filterLayout = await page.locator('[data-list-filter-panel]').evaluate(el => {
      const fields = [...el.querySelectorAll('[data-filter-size]')].map(node => node.getBoundingClientRect());
      const actions = el.querySelector('[data-list-filter-actions]').getBoundingClientRect();
      const group = el.querySelector('.list-filter-panel__actions').getBoundingClientRect();
      return { widths: fields.map(r => r.width), bottom: Math.abs(actions.bottom - group.bottom), gap: fields.length && Math.abs(group.bottom - fields.at(-1).bottom) <= 1 ? group.left - fields.at(-1).right : group.left - el.getBoundingClientRect().left };
    });
    assert(filterLayout.widths.every(w => w <= 241)); assert(filterLayout.bottom <= 1); assert(filterLayout.gap >= -1 && filterLayout.gap <= 17, JSON.stringify(filterLayout));
    assert(await page.locator('[data-list-summary] dd').evaluateAll(values => values.every(el => el.scrollWidth <= el.clientWidth)), '汇总数值应完整可读');
    const pins = await viewport().evaluate(async el => {
      const first = el.querySelector('tbody tr [data-table-sticky-edge="start"]'), last = el.querySelector('tbody tr [data-table-sticky-edge="end"]');
      const pinned = [first, last].filter(Boolean); const x = pinned.map(cell => cell.getBoundingClientRect().x); el.scrollLeft = el.scrollWidth;
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return { delta: pinned.map((cell, index) => cell.getBoundingClientRect().x - x[index]), background: getComputedStyle(first).backgroundColor };
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
async function workBills() {
  const inbound = pageName === 'inbound-bills'; const direction = inbound ? '入库' : '出库';
  await layout(inbound ? 13 : 12);
  const filter = page.locator('[data-list-filter-panel]'), detail = page.locator('.stock-bill-workbench'), form = page.locator('[data-stock-bill-form-dialog]');
  await filter.locator('input').first().fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  const billNo = await rows().first().locator('[data-order-number]').innerText();
  await filter.locator('input').first().fill(billNo.replaceAll('-', '')); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  await filter.locator('[data-anchored-select-trigger]').last().click(); await page.getByRole('option', { name: '待确认', exact: true }).click(); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('待确认'))); await query('重置'); await query('刷新');
  await page.locator('[data-stock-bill-column-trigger]').click(); const menu = page.locator('.column-preferences-canvas'); await menu.waitFor(); await shot('columns');
  await menu.getByRole('switch', { name: '切换来源单号显示', exact: true }).click(); await page.locator('[data-column-layout-state="idle"]').waitFor(); assert.equal(await page.locator('.business-data-table > colgroup > col').count(), inbound ? 12 : 11); await page.keyboard.press('Escape');
  await page.reload(); await rows().first().waitFor(); await settle(); assert.equal(await page.locator('.business-data-table > colgroup > col').count(), inbound ? 12 : 11);
  await page.locator('[data-stock-bill-column-trigger]').click(); await page.getByRole('button', { name: '恢复默认设置', exact: true }).click(); await page.locator('[data-column-layout-state="idle"]').waitFor(); assert.equal(await page.locator('.business-data-table > colgroup > col').count(), inbound ? 13 : 12); check('筛选、空态、刷新、列显示动画、持久化及恢复');
  const first = rows().first(); await first.getByRole('button', { name: '展开明细', exact: true }).click(); await page.locator('[data-stock-bill-expanded-item-id]').first().waitFor(); await shot('expanded');
  const density = await page.locator('.warehouse-detail-table-frame').first().evaluate(el => ({ width: el.getBoundingClientRect().width, header: el.querySelector('.warehouse-detail-table-frame__header').getBoundingClientRect().height, cells: [...el.querySelectorAll('th,td')].map(cell => ({ height: cell.getBoundingClientRect().height, font: getComputedStyle(cell).fontSize })) }));
  assert(density.width <= 1061 && density.header <= 37 && density.cells.every(cell => cell.height <= 39 && cell.font === '13px'), JSON.stringify(density));
  const drawer = page.locator('[data-stock-bill-detail-id]').first(); assert((await drawer.innerText()).includes(inbound ? '入库量' : '出库量'));
  await first.getByRole('button', { name: '收起明细', exact: true }).click(); await page.waitForTimeout(350); assert(await page.locator('[data-stock-bill-detail-host-id]').first().evaluate(el => el.getBoundingClientRect().height <= 1));
  await first.getByRole('button', { name: /处理|查看/ }).click(); await detail.getByText('业务信息', { exact: true }).waitFor();
  for (const text of ['产品明细', '流程记录', '负责人', '来源单号', '合格数量', '不合格数量']) assert((await detail.innerText()).includes(text), text);
  assert.equal(await detail.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)'); await shot('detail');
  await page.setViewportSize({ width: 390, height: 844 }); await settle(); assert(await detail.evaluate(el => { const r = el.getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight; })); await shot('detail-mobile'); await close(detail); await page.setViewportSize({ width: 1440, height: 900 });
  const pending = rows().filter({ hasText: '待确认' }).first(); await pending.getByRole('button', { name: '处理', exact: true }).click(); await detail.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: /保存/ }).waitFor(); await shot('edit');
  assert(await form.locator('input[type="number"]').count() > 0); await close(form); check('展开收起动效、原数量字段、详情内容、手机详情及待确认编辑');
  await page.getByRole('button', { name: `新增${direction}单`, exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.locator('[aria-invalid="true"]').first().waitFor(); await shot('create-invalid'); await close(form);
  await page.getByRole('button', { name: `新增${direction}单`, exact: true }).click(); await form.waitFor();
  await form.locator('[data-anchored-select-trigger]').click(); await page.getByRole('option', { name: `库存调整${direction}`, exact: true }).click();
  await remote(form.locator('[role="combobox"]:not([data-anchored-select-trigger])').first(), 'WH001', '华东中心仓');
  await remote(form.locator('[role="combobox"]:not([data-anchored-select-trigger])').nth(1), 'WH003', '华北中心仓');
  await form.getByPlaceholder('说明盘点差异或调整依据').fill('外观验证盘点调整'); await remote(form.locator('table [role="combobox"]').first(), 'P000001', '经典原味苏打水');
  await form.locator('table input[type="number"]').first().fill('1'); await page.waitForFunction(() => !document.querySelector('[data-sonner-toast]')); await shot('create'); await formBounds(form);
  await form.getByRole('button', { name: '保存草稿', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filter.locator('[data-anchored-select-trigger]').last().click(); await page.getByRole('option', { name: '草稿', exact: true }).click(); await query('查询'); const created = rows().filter({ hasText: `调整${direction}` }).first(); await created.getByRole('button', { name: '处理', exact: true }).click(); await detail.locator('.stock-workbench-notes p').filter({ hasText: '外观验证盘点调整' }).waitFor();
  await detail.getByRole('button', { name: '提交确认', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); assert(await detail.evaluate(el => el.inert)); await shot('submit-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await close(detail); await query('重置'); check('新增必填、调整类型、原仓库产品选择、13px表单、低高度与手机滚动、Mock保存及提交确认inert');
}
async function ledger() {
  await layout(9);
  const filter = page.locator('[data-list-filter-panel]');
  assert.equal(await page.getByRole('button', { name: /新增|编辑|提交|确认|取消/ }).count(), 0);
  await filter.locator('input').first().fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  const firstNo = await rows().first().locator('[data-order-number]').innerText();
  await filter.locator('input').first().fill(firstNo.replaceAll('-', '')); await query('查询'); assert.equal(await rows().count(), 1); await query('重置');
  await filter.locator('[data-anchored-select-trigger]').last().click(); await page.getByRole('option', { name: '系统生成', exact: true }).click(); await query('查询'); assert((await rows().allTextContents()).every(text => text.includes('系统生成'))); await query('重置'); await query('刷新'); check('原九列与只读限制、单号和录入方式筛选、空态、重置及刷新');
  const row = rows().filter({ hasText: 'SL-20260718-0001' }); await row.waitFor();
  await row.getByRole('button', { name: /展开.*明细/ }).click(); await page.locator('[data-stock-ledger-expanded-item-id]').first().waitFor(); await page.waitForTimeout(300);
  const detail = page.locator('[data-stock-ledger-detail-id]').filter({ has: page.locator('[data-stock-ledger-expanded-item-id]') }).first();
  for (const label of ['变动前', '变动数量', '变动后', '合格数量', '不合格数量', '+20']) assert((await detail.innerText()).includes(label), label);
  const geometry = await detail.locator('.warehouse-detail-table-frame').evaluate(el => ({ width: el.getBoundingClientRect().width, cells: [...el.querySelectorAll('th,td')].map(cell => ({ height: cell.getBoundingClientRect().height, font: getComputedStyle(cell).fontSize })) }));
  assert(geometry.width <= 1061 && geometry.cells.every(cell => cell.height <= 39 && cell.font === '13px'), JSON.stringify(geometry));
  await shot('expanded'); await viewport().evaluate(el => { el.scrollLeft = el.scrollWidth; }); await shot('expanded-right');
  await viewport().evaluate(el => { el.scrollLeft = 0; }); await row.getByRole('button', { name: /收起.*明细/ }).click(); await page.waitForTimeout(350); assert(await page.locator('[data-stock-ledger-detail-host-id]').filter({ has: page.locator('[data-stock-ledger-detail-id]') }).first().evaluate(el => el.getBoundingClientRect().height <= 1));
  await row.getByRole('button', { name: /展开.*明细/ }).click(); await page.setViewportSize({ width: 390, height: 844 }); await settle(); assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)); await shot('expanded-mobile'); check('紧凑明细、合格不合格数量、正负数、横向滚动和展开收起动效');
}
async function stocks() {
  await layout(10); const filter = page.locator('[data-list-filter-panel]');
  for (const label of ['当前库存', '锁定库存', '可用库存', '安全库存', '库存健康', '占用情况']) assert((await page.locator('thead').innerText()).includes(label));
  await filter.getByPlaceholder('如 P000001').fill('P000002'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('P000002'))); await query('重置');
  await filter.getByPlaceholder('请输入产品名称').fill('复印纸'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('复印纸'))); await query('重置');
  await remote(filter.locator('[role="combobox"]').first(), 'WH001', '华东中心仓'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('华东中心仓')));
  await filter.locator('[data-anchored-select-trigger]').first().click(); await shot('health-options'); await page.getByRole('option', { name: '低库存', exact: true }).click(); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('低库存'))); await query('重置');
  await filter.locator('[data-anchored-select-trigger]').nth(1).click(); await page.getByRole('option', { name: '部分锁定', exact: true }).click(); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('部分锁定'))); await query('重置');
  await filter.getByPlaceholder('请输入产品名称').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无符合条件')); await query('重置'); await query('刷新'); check('全部独立筛选、AND组合、远程仓库、空态及刷新');
  await page.getByRole('button', { name: '查看风险库存', exact: true }).click(); await settle(); assert(await filter.getByPlaceholder('如 P000001').isDisabled()); assert((await rows().allTextContents()).every(t => /低库存|无可用库存|零库存/.test(t))); await shot('risk-preset'); await query('重置'); assert(!(await filter.getByPlaceholder('如 P000001').isDisabled()));
  const warning = rows().filter({ hasText: 'P000002' }).first(), normal = rows().filter({ hasText: 'P000007' }).first();
  assert.notEqual(await warning.locator('td').first().evaluate(el => getComputedStyle(el).backgroundColor), await normal.locator('td').first().evaluate(el => getComputedStyle(el).backgroundColor));
  await page.locator('[data-slot="pagination-next"]').click(); await loadingFeedback(); await settle(); assert(await rows().count() > 0); await page.locator('[data-slot="pagination-previous"]').click(); await loadingFeedback(); await settle(); check('风险独占预设、普通条件恢复、风险语义色、只读数据和无总数分页');
}
async function warehouses() {
  await layout(9);
  const filters = page.locator('[data-list-filter-panel]');
  for (const [placeholder, value, expected] of [['如 WH001', 'WH001', '华东中心仓'], ['请输入仓库名称', '南京', '南京备货仓'], ['请输入联系人', '周宁', '华东中心仓'], ['请输入联系电话', '021-', '华东中心仓']]) {
    await filters.getByPlaceholder(placeholder, { exact: true }).fill(value); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes(expected)); await query('重置');
  }
  await filters.locator('[role="combobox"]').click(); await shot('status-options'); await page.getByRole('option', { name: '停用', exact: true }).click(); await query('查询'); assert.equal(await rows().count(), 2); await query('重置');
  await filters.getByPlaceholder('请输入仓库名称', { exact: true }).fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无数据')); await query('重置'); await query('刷新');
  await page.locator('[data-slot="pagination-next"]').click(); await loadingFeedback(); await settle(); assert.equal(await rows().count(), 2); await page.locator('[data-slot="pagination-previous"]').click(); await loadingFeedback(); await settle(); check('五筛选、空态、刷新、无总数分页和加载过渡');
  const target = rows().filter({ hasText: 'WH001' }), form = page.locator('[data-warehouse-form-dialog]'), confirm = page.locator('[data-confirm-dialog]');
  await target.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert.equal(await form.locator('[data-warehouse-code]').inputValue(), 'WH001'); assert(await form.locator('[data-warehouse-code]').getAttribute('readonly') !== null);
  await form.locator('textarea').fill('仓库外观回显验证'); await shot('edit'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await target.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert.equal(await form.locator('textarea').inputValue(), '仓库外观回显验证'); await form.getByRole('radio', { name: '停用', exact: true }).click(); await form.getByRole('button', { name: '保存', exact: true }).click(); await confirm.waitFor(); assert(await form.evaluate(el => el.inert)); await shot('disable-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); assert(!(await form.evaluate(el => el.inert))); await close(form);
  await target.getByRole('checkbox').click(); await page.getByRole('button', { name: '批量停用', exact: true }).click(); await confirm.waitFor(); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await confirm.waitFor({ state: 'hidden' }); await target.getByRole('checkbox').click(); check('编辑预填与保存、只读编码、单选状态和双层确认inert及批量入口');
  await page.getByRole('button', { name: '新增仓库', exact: true }).click(); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.getByText('请输入仓库名称', { exact: true }).waitFor(); await page.waitForTimeout(200); assert.equal(await form.locator('input[aria-invalid="true"]').evaluate(el => getComputedStyle(el).borderColor), 'rgb(207, 154, 162)'); await shot('create-invalid'); await close(form);
  await page.getByRole('button', { name: '新增仓库', exact: true }).click(); await form.waitFor(); const name = `仓储外观验证${Date.now()}`;
  await form.getByPlaceholder('请输入仓库名称', { exact: true }).fill(name); await form.getByPlaceholder('请输入联系人', { exact: true }).fill('王林'); await form.getByPlaceholder('请输入联系电话', { exact: true }).fill('021-5558-1234'); await form.getByPlaceholder('请输入仓库地址', { exact: true }).fill('上海市仓储示范路88号'); await form.locator('textarea').fill('仓库用途及备注全部保留');
  await page.waitForFunction(() => !document.querySelector('[data-sonner-toast]')); await shot('create'); await formBounds(form); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filters.getByPlaceholder('请输入仓库名称', { exact: true }).fill(name); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('王林')); await rows().getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert.equal(await form.locator('textarea').inputValue(), '仓库用途及备注全部保留'); await close(form); await query('重置'); check('新增校验、完整表单、13px输入、低高度和手机滚动、Mock保存回显');
}
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto(`${base}/warehouse/${pageName}`);
    if (page.url().includes('/login')) { await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456'); await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(u => !u.pathname.includes('/login')); await page.goto(`${base}/warehouse/${pageName}`); }
    await rows().first().waitFor(); await settle(); assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin');
    if (pageName === 'warehouses') await warehouses(); else if (pageName === 'stocks') await stocks(); else if (pageName.endsWith('bound-bills')) await workBills(); else if (pageName === 'stock-bills') await ledger(); else throw new Error(`待接入核验页：${pageName}`);
    assert.equal(errors.length, 0, errors.join('\n')); check('无页面脚本错误'); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(results, null, 2)); console.log(`WAREHOUSE_STYLE_OK ${pageName}: ${results.length}组`);
  } catch (error) { await shot('failed').catch(() => undefined); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error; } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
