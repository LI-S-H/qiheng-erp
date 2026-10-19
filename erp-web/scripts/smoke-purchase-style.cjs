// 本脚本验证采购视觉试点的真实 Vue 组件；写操作仅允许项目 Mock 会话。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const evidenceDir = path.resolve(process.env.QA_SCREENSHOT_DIR || '../docs/qa-screenshots/purchase-style-pilot');
fs.mkdirSync(evidenceDir, { recursive: true });
const results = [];
let page;
let testServer;
const check = (name, detail) => results.push({ name, detail, passed: true });
const screenshot = async name => { await page.waitForTimeout(200); return page.screenshot({ path: path.join(evidenceDir, `${name}.png`) }); };
const rows = () => page.locator('.business-data-table tbody tr');
const tableViewport = () => page.locator('.purchase-list-body > [data-slot="table-container"]');
async function settled() {
  await page.waitForFunction(() => !document.querySelector('[data-list-loading], [data-page-loading]'));
  await page.waitForTimeout(150);
}
async function queryAction(name) {
  await page.getByRole('button', { name, exact: true }).click();
  await page.locator('[data-list-loading]').waitFor({ state: 'visible' });
  assert.equal(await page.getByRole('button', { name: '重置', exact: true }).isDisabled(), true);
  assert.equal(await tableViewport().evaluate(el => el.inert), true);
  await settled();
}
async function chooseRemote(trigger, keyword, optionText) {
  await trigger.click();
  const menu = page.locator('[data-remote-search-select-content]').last();
  await menu.locator('input').fill(keyword);
  await page.waitForTimeout(350);
  const option = menu.locator('[data-select-option]').filter({ hasText: optionText }).first();
  await option.waitFor();
  await option.click();
  await menu.waitFor({ state: 'hidden' });
}
async function closeDialog(dialog) {
  await dialog.locator('[data-slot="dialog-close"]').click();
  await dialog.waitFor({ state: 'hidden' });
}
async function inspectDialogTable(dialog) {
  const styles = await dialog.locator('th, td').evaluateAll(elements => elements.map(el => ({
    tag: el.tagName,
    alignment: getComputedStyle(el).textAlign,
    fontSize: getComputedStyle(el).fontSize,
  })));
  assert(styles.length > 0, '弹窗应包含原有明细表格');
  assert(styles.every(style => style.alignment === 'center'), '弹窗表头和数据必须全部居中');
  const headerFont = await dialog.evaluate(el => el.hasAttribute('data-purchase-form-dialog') ? '13px' : '14px');
  assert(styles.filter(style => style.tag === 'TH').every(style => style.fontSize === headerFont), '表单与详情采用各自统一字号');
}
async function inspectLayout(width, height) {
  await page.setViewportSize({ width, height });
  await page.locator('main').evaluate(el => { el.scrollTop = 0; });
  await tableViewport().evaluate(el => { el.scrollTop = 0; el.scrollLeft = 0; });
  await page.waitForTimeout(200);
  const state = await page.evaluate(() => {
    const rect = el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height, right: r.right, bottom: r.bottom }; };
    const viewport = document.querySelector('.purchase-list-body > [data-slot="table-container"]');
    const summary = document.querySelector('[data-list-summary]');
    const controls = [...document.querySelectorAll('[data-list-filter-panel] input, [data-list-filter-panel] [role="combobox"], [data-list-filter-actions] button')];
    const firstRow = viewport.querySelector('tbody tr');
    const pageRoot = document.querySelector('.purchase-pilot-page');
    const amount = firstRow.children[4];
    const arrival = firstRow.children[5];
    const textRect = el => { const range = document.createRange(); range.selectNodeContents(el); return range.getBoundingClientRect(); };
    // 检查实际字形位置，避免父单元格居中但内部块仍受旧规则影响。
    const textCenterOffsets = [...viewport.querySelectorAll('th'), ...firstRow.children].flatMap(cell => {
      const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
      const offsets = [];
      let node;
      while ((node = walker.nextNode())) {
        if (!node.textContent.trim()) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        const text = range.getBoundingClientRect();
        const box = cell.getBoundingClientRect();
        offsets.push(Math.abs((text.left + text.right - box.left - box.right) / 2));
      }
      return offsets;
    });
    return {
      overflow: document.documentElement.scrollWidth - innerWidth,
      mainOverflow: document.querySelector('main').scrollWidth - document.querySelector('main').clientWidth,
      summary: getComputedStyle(summary).backgroundColor,
      metrics: [...summary.querySelectorAll('dl')].map(rect),
      controls: controls.map(rect),
      row: rect(viewport.querySelector('tbody tr')),
      footer: rect(document.querySelector('[data-table-pagination]')),
      verticalOverflow: viewport.scrollHeight - viewport.clientHeight,
      allRowsWithinTable: viewport.querySelector('tbody tr:last-child').getBoundingClientRect().bottom <= viewport.getBoundingClientRect().bottom + 1,
      columnCount: viewport.querySelectorAll('col').length,
      headerAlignment: [...viewport.querySelectorAll('th')].map(el => getComputedStyle(el).textAlign),
      dataAlignment: [...viewport.querySelectorAll('tbody td')].map(el => getComputedStyle(el).textAlign),
      headerFontSizes: [...viewport.querySelectorAll('th')].map(el => getComputedStyle(el).fontSize),
      textCenterOffsets,
      status: [...viewport.querySelectorAll('[data-purchase-status-column]')].map(el => el.children.length),
      amountArrivalGap: textRect(arrival).left - textRect(amount).right,
      statusWidth: firstRow.children[3].getBoundingClientRect().width,
      amountWidth: amount.getBoundingClientRect().width,
      arrivalWidth: arrival.getBoundingClientRect().width,
      supplierWidth: firstRow.children[1].getBoundingClientRect().width,
      tableWidth: viewport.querySelector('table').getBoundingClientRect().width,
      supplierTextWidth: Math.max(...[...viewport.querySelectorAll('tbody tr')].map(row => { const content = row.children[1]?.firstElementChild; return content ? textRect(content).width : 0; })),
      pageWidth: pageRoot.getBoundingClientRect().width,
      mainWidth: pageRoot.parentElement.clientWidth,
      pageLeft: pageRoot.getBoundingClientRect().left,
      mainLeft: pageRoot.parentElement.getBoundingClientRect().left,
    };
  });
  assert(state.overflow <= 1 && state.mainOverflow <= 1, `外层溢出：${JSON.stringify(state)}`);
  assert.equal(state.columnCount, 9);
  assert(state.headerAlignment.every(alignment => alignment === 'center'), '所有列名必须居中');
  assert(state.dataAlignment.every(alignment => alignment === 'center'), '所有数据单元格必须居中');
  assert(state.headerFontSizes.every(size => size === '14px'), '列名应与主数据同为14px');
  assert(state.textCenterOffsets.every(offset => offset <= 1), `字形没有相对所属列居中：${JSON.stringify(state.textCenterOffsets)}`);
  assert(state.status.every(count => count === 1), '状态不得包含重复说明');
  assert(state.amountArrivalGap >= 28, `金额与预计到货应保留至少两侧内边距的间隔：${state.amountArrivalGap}px`);
  assert(state.supplierWidth <= state.tableWidth * .24, '供应商列不能独占多余空间');
  assert(Math.abs(state.pageWidth - state.mainWidth) <= 1 && Math.abs(state.pageLeft - state.mainLeft) <= 1, '页面必须铺满主工作区，不能被表格自然宽度收窄');
  assert(state.statusWidth <= state.amountWidth, '短状态字段不应比金额占更多宽度');
  assert(Math.abs(state.row.height - 64) <= 1, '记录行高应为64px');
  assert(state.controls.every(rect => Math.abs(rect.height - 36) <= 1), '筛选与按钮应为36px');
  assert(state.verticalOverflow <= 1 && state.allRowsWithinTable, '默认10条必须完整展开，不能在表格内部纵向滚动');
  if (width < 760) assert(Math.abs(state.metrics[0].y - state.metrics[1].y) < 1 && state.metrics[2].y > state.metrics[0].y, '窄屏摘要应为两行两列');
  await screenshot(`purchase-${width}x${height}`);
  if (width >= 1440) {
    const clip = await tableViewport().evaluate(el => {
      const heads = el.querySelectorAll('th');
      const start = heads[2].getBoundingClientRect();
      const end = heads[5].getBoundingClientRect();
      return { x: start.left, y: start.top, width: end.right - start.left, height: Math.min(el.clientHeight, 362) };
    });
    await page.screenshot({ path: path.join(evidenceDir, `purchase-columns-${width}.png`), clip });
  }
  const sticky = await tableViewport().evaluate(async el => {
    const first = el.querySelector('tbody [data-purchase-no-column]');
    const last = el.querySelector('tbody [data-purchase-actions-column]');
    const snapshot = () => ({ first: first.getBoundingClientRect().x, last: last.getBoundingClientRect().x, firstBg: getComputedStyle(first).backgroundColor, lastBg: getComputedStyle(last).backgroundColor });
    const before = snapshot();
    el.scrollLeft = el.scrollWidth;
    el.scrollTop = 110;
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    return { before, after: snapshot(), headerY: el.querySelector('th').getBoundingClientRect().y, viewportY: el.getBoundingClientRect().y, scrollLeft: el.scrollLeft, scrollTop: el.scrollTop };
  });
  assert(Math.abs(sticky.before.first - sticky.after.first) < 1 && Math.abs(sticky.before.last - sticky.after.last) < 1, `固定列漂移：${JSON.stringify(sticky)}`);
  assert(Math.abs(sticky.headerY - sticky.viewportY) < 1, '表头纵向滚动失效');
  assert(!sticky.after.firstBg.includes('rgba') && !sticky.after.lastBg.includes('rgba'), '固定列背景不可透明');
  await screenshot(`purchase-${width}x${height}-scroll`);
  check(`${width}x${height} 布局、固定列与表头`, { state, sticky });
}

(async () => {
  try { await fetch(baseUrl, { signal: AbortSignal.timeout(1500) }); }
  catch {
    const target = new URL(baseUrl);
    const environment = { ...process.env, VITE_USE_MOCK_AUTH: 'true', VITE_USE_MOCK_API: 'true' };
    // 服务与本次验证同生命周期，避免依赖上一次终端会话是否仍在运行。
    testServer = spawn(process.execPath, [path.resolve('node_modules/vite/bin/vite.js'), '--host', target.hostname, '--port', target.port, '--strictPort'], { env: environment, stdio: 'ignore' });
    for (let attempt = 0; attempt < 60; attempt++) {
      try { if ((await fetch(baseUrl)).ok) break; } catch { /* 等待本机服务就绪。 */ }
      await new Promise(resolve => setTimeout(resolve, 250));
    }
  }
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${baseUrl}/purchase/orders`, { waitUntil: 'domcontentloaded' });
    if (page.url().includes('/login')) {
      await page.locator('#username').fill('admin');
      await page.locator('#password').fill('123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'));
      await page.goto(`${baseUrl}/purchase/orders`, { waitUntil: 'domcontentloaded' });
    }
    await page.getByRole('heading', { name: '采购订单', exact: true }).waitFor();
    await settled();
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin', '写操作验证仅允许Mock会话');
    assert.equal(await rows().count(), 10);
    const navigationType = await page.evaluate(() => ({
      primary: parseFloat(getComputedStyle(document.querySelector('.app-sidebar nav button')).fontSize),
      secondary: parseFloat(getComputedStyle(document.querySelector('.app-sidebar__submenu-link')).fontSize),
      body: parseFloat(getComputedStyle(document.querySelector('.business-data-table tbody td')).fontSize),
      number: parseFloat(getComputedStyle(document.querySelector('.business-data-table [data-order-number]')).fontSize),
      weight: getComputedStyle(document.querySelector('.business-data-table tbody td')).fontWeight,
      lineHeight: getComputedStyle(document.querySelector('.business-data-table tbody td')).lineHeight,
    }));
    assert(navigationType.primary >= navigationType.body && navigationType.secondary >= navigationType.body, '导航字号不得小于业务正文');
    assert.equal(navigationType.body, 14, '列表主文字应为14px');
    assert.equal(navigationType.number, 14, '单号应与主文字同字号');
    assert.equal(navigationType.weight, '400');
    assert.equal(navigationType.lineHeight, '22px');
    const bell = page.locator('[data-notification-trigger]');
    await bell.waitFor();
    await page.mouse.move(0, 0);
    await bell.evaluate(el => el.blur());
    await page.waitForTimeout(200);
    const bellStyle = await bell.evaluate(el => {
      const badge = el.querySelector('[data-notification-count]');
      const icon = el.querySelector('svg');
      return { iconSize: icon.getBoundingClientRect().width, color: getComputedStyle(badge).color, badgeHeight: badge.getBoundingClientRect().height, font: getComputedStyle(badge).fontSize, background: getComputedStyle(el).backgroundColor, border: getComputedStyle(el).borderTopWidth };
    });
    assert.equal(bellStyle.iconSize, 18);
    assert.equal(bellStyle.color, 'rgb(255, 255, 255)', '数字徽标必须为清晰白字');
    assert.equal(bellStyle.badgeHeight, 18);
    assert.equal(bellStyle.font, '10px');
    assert.equal(bellStyle.background, 'rgba(0, 0, 0, 0)', '铃铛默认必须透明');
    assert.equal(bellStyle.border, '0px', '铃铛默认不应出现边框');
    const bellRect = await bell.boundingBox();
    await page.screenshot({ path: path.join(evidenceDir, 'notification-trigger.png'), clip: { x: bellRect.x - 8, y: bellRect.y - 8, width: bellRect.width + 20, height: bellRect.height + 16 } });
    await bell.click();
    await page.locator('[data-notification-popover]').getByRole('heading', { name: '待处理事项', exact: true }).waitFor();
    await page.mouse.move(0, 0);
    await page.waitForTimeout(200);
    assert.equal(await bell.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(237, 241, 247)', '通知打开时应保留浅底选中态');
    await screenshot('notification-popover');
    await page.keyboard.press('Escape');
    await page.locator('[data-notification-popover]').waitFor({ state: 'hidden' });
    assert.equal(await bell.evaluate(el => document.activeElement === el), true, '通知关闭后焦点应返回铃铛');
    await bell.evaluate(el => el.blur());
    await page.mouse.move(0, 0);
    await page.waitForTimeout(200);
    assert.equal(await bell.evaluate(el => getComputedStyle(el).backgroundColor), 'rgba(0, 0, 0, 0)', '通知关闭并移开焦点后应恢复透明');
    assert.deepEqual(await page.locator('[data-list-summary] dd').allTextContents(), ['1', '2', '3', '4']);
    for (const [width, height] of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) await inspectLayout(width, height);
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.locator('main').evaluate(el => { el.scrollTop = 0; });
    await tableViewport().evaluate(el => { el.scrollTop = 0; el.scrollLeft = 0; });

    const input = page.locator('[data-list-filter-panel] input');
    await input.focus();
    await page.waitForTimeout(200);
    const focus = await input.evaluate(el => ({ shadow: getComputedStyle(el).boxShadow, border: getComputedStyle(el).borderColor, background: getComputedStyle(el).backgroundColor }));
    const focusLayers = focus.shadow.match(/rgba?\([^)]*\)/g) || [];
    assert.equal(focusLayers.length, 1, '聚焦不应叠加多层辉光');
    assert(focusLayers[0].includes('0.03'), `焦点应为极淡中性柔光：${JSON.stringify(focus)}`);
    assert.equal(focus.border, 'rgb(182, 194, 210)', '焦点边框应偏中性，避免鲜蓝描边');
    await screenshot('purchase-soft-focus');
    check('输入框柔光聚焦', focus);

    await page.locator('[data-anchored-select-trigger]').click();
    const statusMenu = page.locator('[data-anchored-select-content]');
    assert.equal(await statusMenu.evaluate(el => getComputedStyle(el).borderRadius), '8px');
    await screenshot('purchase-status-dropdown');
    await statusMenu.getByRole('option', { name: '待审核', exact: true }).click();
    await queryAction('查询');
    assert.equal(await rows().count(), 2);
    assert((await rows().allTextContents()).every(text => text.includes('待审核')));
    await queryAction('重置');

    const remoteTriggers = page.locator('[data-list-filter-panel] [role="combobox"]:not([data-anchored-select-trigger])');
    await remoteTriggers.nth(0).click();
    let remoteMenu = page.locator('[data-remote-search-select-content]');
    await remoteMenu.locator('input').fill('华东');
    await page.waitForTimeout(350);
    await remoteMenu.locator('[data-select-option]').filter({ hasText: '华东饮品' }).waitFor();
    assert.equal(await remoteMenu.evaluate(el => getComputedStyle(el).borderRadius), '8px');
    await screenshot('purchase-search-dropdown');
    await remoteMenu.locator('[data-select-option]').filter({ hasText: '华东饮品' }).click();
    await queryAction('查询');
    assert((await rows().allTextContents()).every(text => text.includes('华东饮品')));
    await remoteTriggers.nth(0).click();
    await page.locator('[data-remote-search-select-content] [aria-current="true"]').waitFor();
    await page.keyboard.press('Escape');
    await queryAction('重置');
    await remoteTriggers.nth(0).click();
    await remoteTriggers.nth(1).click();
    await page.waitForTimeout(180);
    assert.equal(await page.locator('[data-remote-search-select-content]').count(), 1, '下拉应互斥');
    await page.keyboard.press('Escape');
    await input.fill('不存在的采购单');
    await queryAction('查询');
    assert((await rows().innerText()).includes('暂无采购订单'));
    await screenshot('purchase-empty');
    await queryAction('重置');
    await queryAction('刷新');
    await page.locator('[data-slot="pagination-next"]').click();
    await settled();
    assert.equal(await rows().count(), 1);
    await page.locator('[data-slot="pagination-previous"]').click();
    await settled();
    await page.locator('[data-table-pagination] [role="combobox"]').click();
    await page.getByRole('option', { name: '20 条', exact: true }).click();
    await settled();
    assert.equal(await rows().count(), 11);
    await page.locator('[data-table-pagination] [role="combobox"]').click();
    await page.getByRole('option', { name: '10 条', exact: true }).click();
    await settled();
    check('搜索、选中态、互斥、状态筛选、空态、刷新与分页', '通过');

    const draftRow = rows().filter({ has: page.locator('[data-purchase-status-column]', { hasText: '草稿' }) });
    await draftRow.getByRole('button', { name: '处理', exact: true }).click();
    const detail = page.locator('[data-order-workbench]');
    await detail.getByText('业务信息', { exact: true }).waitFor();
    await inspectDialogTable(detail);
    await screenshot('purchase-detail');
    const detailContent = await detail.innerText();
    for (const text of ['履约概览', '订单金额', '累计入库', '预计到货', '业务进度', '已入库明细', '待入库明细', '已完成明细', '业务信息', '供应商', '订单日期', '采购单号', '入库仓库', '制单人', '提交人', '审核人', '备注', '商品明细', '订购数量', '已入库', '待入库', '单价', '金额', '审核时推荐分', '明细备注', '流程记录']) assert(detailContent.includes(text), `详情内容不得删减：${text}`);
    const detailSurface = await detail.evaluate(el => ({ backdrop: getComputedStyle(document.querySelector('[data-slot="dialog-overlay"]')).backdropFilter, cards: [...el.querySelectorAll('.business-detail-workbench-card')].map(card => getComputedStyle(card).borderTopWidth) }));
    assert.equal(detailSurface.backdrop, 'none');
    assert(detailSurface.cards.every(border => border === '0px'), '详情应沿用页面的连续分区，减少嵌套卡片边框');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(200);
    const mobileDetail = await detail.evaluate(el => ({ width: el.getBoundingClientRect().width, left: el.getBoundingClientRect().left, footerBottom: el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom }));
    assert(mobileDetail.width <= 390 && mobileDetail.left >= 0 && mobileDetail.footerBottom <= 844, '手机详情不得溢出，底部操作应可见');
    await screenshot('purchase-detail-mobile');
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(200);
    const detailSize = await detail.boundingBox();
    assert(Math.abs((detailSize.x + detailSize.width / 2) - (224 + (1440 - 224) / 2)) < 2, '详情应相对工作区居中');
    await detail.getByRole('button', { name: '提交采购单', exact: true }).click();
    const confirm = page.locator('[data-confirm-dialog]');
    await confirm.waitFor();
    assert.equal(await detail.evaluate(el => el.inert), true);
    await screenshot('purchase-confirm');
    await confirm.getByRole('button', { name: '取消', exact: true }).click();
    await confirm.waitFor({ state: 'hidden' });
    assert.equal(await detail.evaluate(el => el.inert), false);
    await detail.getByRole('button', { name: '编辑', exact: true }).click();
    const form = page.locator('[data-purchase-form-dialog]');
    await form.getByRole('heading', { name: '编辑采购单', exact: true }).waitFor();
    assert((await form.locator('[role="combobox"]').first().innerText()).includes('文仪'));
    await form.locator('textarea').fill('采购外观试点回显验证');
    await screenshot('purchase-edit');
    await form.getByRole('button', { name: '保存修改', exact: true }).click();
    await form.waitFor({ state: 'hidden' });
    await settled();
    await draftRow.getByRole('button', { name: '处理', exact: true }).click();
    await detail.getByText('采购外观试点回显验证', { exact: true }).waitFor();
    await closeDialog(detail);
    check('详情、工作区居中、二次确认inert、编辑保存回显', '通过');

    await page.getByRole('button', { name: '新增采购单', exact: true }).click();
    await form.waitFor();
    await form.getByRole('button', { name: '保存草稿', exact: true }).click();
    await form.getByText('请添加至少一个产品', { exact: true }).waitFor();
    assert.equal(await form.locator('[role="combobox"][aria-invalid="true"]').count(), 2);
    await form.locator('[role="combobox"][aria-invalid="true"]').first().hover();
    await page.waitForTimeout(200);
    const invalidBorders = await form.locator('[role="combobox"][aria-invalid="true"]').evaluateAll(elements => elements.map(el => ({ actual: getComputedStyle(el).borderColor, expected: getComputedStyle(el).getPropertyValue('--destructive').trim() })));
    const resolvedDanger = await form.evaluate(el => { const probe = document.createElement('i'); probe.style.color = 'var(--destructive)'; el.append(probe); const color = getComputedStyle(probe).color; probe.remove(); return color; });
    assert(invalidBorders.every(border => border.actual === 'rgb(207, 154, 162)'), `错误态应保留柔和浅红细边：${JSON.stringify({ invalidBorders, resolvedDanger })}`);
    await screenshot('purchase-create-invalid');
    await closeDialog(form);
    await page.getByRole('button', { name: '新增采购单', exact: true }).click();
    await form.waitFor();
    await chooseRemote(form.locator('[role="combobox"]').nth(0), '华东', '华东饮品');
    await chooseRemote(form.locator('[role="combobox"]').nth(1), '华东', '华东中心仓');
    await form.getByRole('button', { name: '打开日期选择器', exact: true }).click();
    await page.locator('.order-date-picker__content').waitFor();
    await screenshot('purchase-date-dropdown');
    await page.locator('.order-date-picker__day:not(.is-outside)').last().click();
    await form.getByRole('button', { name: '添加产品', exact: true }).click();
    await form.locator('[data-purchase-form-items] [role="combobox"]').click();
    remoteMenu = page.locator('[data-remote-search-select-content]');
    await remoteMenu.locator('[data-select-option]').first().waitFor();
    await remoteMenu.locator('[data-select-option]').first().click();
    await form.locator('[data-purchase-form-items] input[type="number"]').nth(0).fill('2');
    await form.locator('textarea').fill('采购外观试点新增验证');
    await form.locator('textarea').focus();
    await page.waitForTimeout(200);
    const formFocus = await form.locator('textarea').evaluate(el => getComputedStyle(el).boxShadow);
    assert.equal((formFocus.match(/rgba?\([^)]*\)/g) || []).length, 1);
    assert(formFocus.includes('0.03'), '弹窗输入框也应使用相同的极淡柔光');
    await screenshot('purchase-create');
    const numeric = await form.locator('.order-line-table input[type="number"]').evaluateAll(inputs => inputs.map(input => ({ width: input.getBoundingClientRect().width, cap: input.classList.contains('price-input') ? 104 : 80, appearance: getComputedStyle(input).appearance, spinner: getComputedStyle(input, '::-webkit-inner-spin-button').display })));
    assert(numeric.every(input => input.width <= input.cap + 1 && input.width >= 48 && input.appearance === 'textfield'), JSON.stringify(numeric));
    await inspectDialogTable(form);
    await page.setViewportSize({ width: 1115, height: 640 });
    await page.waitForTimeout(200);
    const dialogGeometry = await form.evaluate(el => {
      const area = el.querySelector('[data-slot="scroll-area-viewport"]');
      return { footerBottom: el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom, height: el.getBoundingClientRect().height, scrollHeight: area.scrollHeight, clientHeight: area.clientHeight };
    });
    assert(dialogGeometry.footerBottom <= 640 && dialogGeometry.scrollHeight > dialogGeometry.clientHeight, `长表单必须正文滚动、底部可见：${JSON.stringify(dialogGeometry)}`);
    await screenshot('purchase-create-low-height');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(200);
    assert(await form.evaluate(el => el.getBoundingClientRect().left >= 0 && el.getBoundingClientRect().right <= innerWidth && el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect().bottom <= innerHeight), '手机新增弹窗与底部动作不得溢出');
    await screenshot('purchase-create-mobile');
    await form.getByRole('button', { name: '保存草稿', exact: true }).click();
    await form.waitFor({ state: 'hidden' });
    await settled();
    check('新增校验、搜索、日期选择、数量输入、低高度滚动与保存', dialogGeometry);

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.getByRole('button', { name: '工作台', exact: true }).click();
    await page.locator('[data-page-loading]').waitFor({ state: 'visible' });
    await page.waitForURL(url => url.pathname === '/dashboard');
    await settled();
    assert.equal(await page.locator('.purchase-pilot-theme').count(), 0);
    const oldSidebar = await page.locator('.app-sidebar').evaluate(el => getComputedStyle(el).backgroundColor);
    assert.notEqual(oldSidebar, 'rgb(247, 248, 250)');
    await screenshot('dashboard-original-theme');
    await page.goto(`${baseUrl}/dashboard`);
    await page.locator('.app-shell:not(.purchase-pilot-theme)').waitFor();
    await settled();
    assert.equal(await page.locator('.purchase-pilot-theme').count(), 0);
    await screenshot('dashboard-theme-isolation');
    await page.goto(`${baseUrl}/purchase/orders`);
    await page.locator('.purchase-pilot-theme .business-data-table tbody tr').first().waitFor();
    await settled();
    assert.equal(await page.locator('.purchase-pilot-theme').count(), 1);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.locator('[data-anchored-select-trigger]').click();
    const reduced = await page.locator('[data-anchored-select-content]').evaluate(el => ({ animation: getComputedStyle(el).animationDuration, transition: getComputedStyle(el).transitionDuration }));
    const milliseconds = value => value.split(',').map(item => item.trim().endsWith('ms') ? parseFloat(item) : parseFloat(item) * 1000);
    assert([...milliseconds(reduced.animation), ...milliseconds(reduced.transition)].every(value => value <= 1));
    await page.keyboard.press('Escape');
    assert.equal(errors.length, 0, errors.join('\n'));
    check('页面切换加载动效、退出主题恢复、库存回归、减弱动态效果与脚本错误', { oldSidebar, reduced, errors });
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify(results, null, 2));
    console.log(`PURCHASE_STYLE_OK: ${results.length} 组检查通过；截图：${evidenceDir}`);
  } catch (error) {
    if (page) await screenshot('failed').catch(() => undefined);
    fs.writeFileSync(path.join(evidenceDir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2));
    throw error;
  } finally {
    await browser.close();
    testServer?.kill();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
