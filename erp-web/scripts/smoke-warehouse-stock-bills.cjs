const fs = require('fs');
const path = require('path');
const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertSharedListChrome,
} = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/warehouse-stock-bills',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

const inboundColumnStorageKey = 'erp.warehouse.stock-bills.table-columns.inbound.v1';
const outboundColumnStorageKey = 'erp.warehouse.stock-bills.table-columns.outbound.v1';

async function openColumnMenu(page) {
  await page.locator('[data-stock-bill-column-trigger]').click();
  await page.locator('[data-stock-bill-column-menu]').waitFor();
}

async function waitColumnLayoutSettled(page) {
  await page.locator('.stock-bill-table-scroll[data-column-layout-state="idle"]').waitFor();
}

async function resetCurrentColumnPreference(page) {
  await openColumnMenu(page);
  await page.locator('[data-stock-bill-column-reset]').click();
  await waitColumnLayoutSettled(page);
}

async function assertStockBillColumnPreferences(page) {
  await page.evaluate(([inboundKey, outboundKey]) => {
    localStorage.removeItem(inboundKey);
    localStorage.removeItem(outboundKey);
  }, [inboundColumnStorageKey, outboundColumnStorageKey]);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '入库单' }).waitFor();
  const inboundRow = tableRow(page, 'IB202606140001');
  await inboundRow.waitFor();
  await assertFixedTableLayout(page, 12);

  await openColumnMenu(page);
  const columnMenu = page.locator('[data-stock-bill-column-menu]');
  const columnMenuText = await columnMenu.innerText();
  if (columnMenuText.includes('固定关键列') || columnMenuText.includes('单号与展开')) {
    throw new Error(`字段选择菜单不应展示无作用的固定关键列：${columnMenuText}`);
  }
  for (const optionalLabel of ['录入方式', '来源类型', '来源单号', '来源对象', '仓库', '负责人', '创建时间']) {
    const item = page.getByRole('menuitemcheckbox', { name: optionalLabel, exact: true });
    await item.waitFor();
    if (await item.isDisabled()) throw new Error(`可选字段“${optionalLabel}”不应被禁用`);
  }
  if (await columnMenu.getByRole('menuitemcheckbox').count() !== 7
    || !columnMenuText.includes('按需精简列表') || !columnMenuText.includes('恢复默认字段')) {
    throw new Error(`字段选择菜单结构或说明不完整：${columnMenuText}`);
  }
  const triggerLabel = await page.locator('[data-stock-bill-column-trigger]').getAttribute('aria-label');
  if (triggerLabel !== '选择显示字段，当前 7/7') throw new Error(`字段选择触发器缺少当前状态说明：${triggerLabel}`);
  const checkboxVisuals = await columnMenu.locator('[data-stock-bill-column-key]').evaluateAll(items => items.map(item => {
    const indicator = item.querySelector('[data-slot="dropdown-menu-checkbox-item-indicator"]');
    const style = indicator ? getComputedStyle(indicator) : null;
    return {
      width: indicator?.getBoundingClientRect().width ?? 0,
      height: indicator?.getBoundingClientRect().height ?? 0,
      borderWidth: style?.borderTopWidth ?? '0px',
      borderRadius: style?.borderRadius ?? '0px',
    };
  }));
  if (checkboxVisuals.length !== 7 || checkboxVisuals.some(item => Math.abs(item.width - 16) > 0.5 || Math.abs(item.height - 16) > 0.5
    || item.borderWidth !== '1px' || Number.parseFloat(item.borderRadius) < 4)) {
    throw new Error(`字段选择未使用统一方框勾选样式：${JSON.stringify(checkboxVisuals)}`);
  }

  const keyboardItem = page.locator('[data-stock-bill-column-key="sourceType"]');
  await keyboardItem.focus();
  if (!await keyboardItem.evaluate(element => document.activeElement === element)) {
    throw new Error('字段选择菜单项无法获得键盘焦点');
  }
  await page.keyboard.press('Space');
  await page.keyboard.press('Space');
  await waitColumnLayoutSettled(page);
  if (await keyboardItem.getAttribute('aria-checked') !== 'true' || !await columnMenu.isVisible()) {
    throw new Error('字段快速键盘切换后状态或菜单焦点上下文异常');
  }

  await page.screenshot({ path: screenshotPath('stock-bill-column-menu.png'), fullPage: true });
  const tableViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
  const scrollBeforeColumnChange = await tableViewport.evaluate(element => {
    element.scrollLeft = Math.min(320, Math.max(0, element.scrollWidth - element.clientWidth));
    return element.scrollLeft;
  });
  await page.locator('[data-stock-bill-column-key="entryMode"]').click();
  const leavingTable = page.locator('.stock-bill-table-scroll[data-column-layout-state="leaving"]');
  await leavingTable.waitFor();
  const preservedScrollLeft = Number(await leavingTable.getAttribute('data-column-layout-scroll-left'));
  await page.locator('[data-stock-bill-column-key="createTime"]').click();
  await page.keyboard.press('Escape');
  await waitColumnLayoutSettled(page);

  const idleVisual = await page.locator('.stock-bill-list-table').first().evaluate(element => ({
    opacity: getComputedStyle(element).opacity,
    transform: getComputedStyle(element).transform,
  }));
  await page.waitForTimeout(160);
  const settledVisual = await page.locator('.stock-bill-list-table').first().evaluate(element => ({
    opacity: getComputedStyle(element).opacity,
    transform: getComputedStyle(element).transform,
  }));
  if (JSON.stringify(idleVisual) !== JSON.stringify(settledVisual)
    || idleVisual.opacity !== '1' || idleVisual.transform !== 'none') {
    throw new Error(`字段布局进入 idle 后仍有二次过渡：${JSON.stringify({ idleVisual, settledVisual })}`);
  }

  const scrollAfterColumnChange = await tableViewport.evaluate(element => {
    const scrollLeft = element.scrollLeft;
    element.scrollLeft = Number.MAX_SAFE_INTEGER;
    const maxScrollLeft = element.scrollLeft;
    element.scrollLeft = scrollLeft;
    return { scrollLeft, maxScrollLeft };
  });
  const expectedScrollLeft = Math.min(preservedScrollLeft, scrollAfterColumnChange.maxScrollLeft);
  if (Math.abs(scrollAfterColumnChange.scrollLeft - expectedScrollLeft) > 1) {
    throw new Error(`字段切换后横向滚动位置未正确恢复：${JSON.stringify({ scrollBeforeColumnChange, preservedScrollLeft, scrollAfterColumnChange })}`);
  }
  await page.getByText('显示字段已更新', { exact: true }).waitFor();

  const inboundHeader = await outerHeaderText(page);
  if (inboundHeader.includes('录入方式') || inboundHeader.includes('创建时间')) {
    throw new Error('入库单可选列隐藏后仍出现在表头中');
  }
  await assertFixedTableLayout(page, 10);
  const inboundPreference = await page.evaluate(key => JSON.parse(localStorage.getItem(key) || '{}'), inboundColumnStorageKey);
  if (inboundPreference.entryMode !== false || inboundPreference.createTime !== false) {
    throw new Error(`入库单列偏好未正确持久化：${JSON.stringify(inboundPreference)}`);
  }

  await inboundRow.getByRole('button', { name: '展开明细' }).click();
  const inboundHostColspan = await inboundRow.locator('xpath=following-sibling::tr[1]/td[1]').getAttribute('colspan');
  if (inboundHostColspan !== '10') throw new Error(`隐藏列后展开明细 colspan 未同步，当前为 ${inboundHostColspan}`);
  await page.screenshot({ path: path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-07-14-143053-stock-bill-column-visibility', 'inbound-custom-columns.png'), fullPage: true });
  await inboundRow.getByRole('button', { name: '收起明细' }).click();
  await page.waitForTimeout(280);

  await page.locator('[data-menu-path="/warehouse/outbound-bills"]').click();
  await page.getByRole('heading', { name: '出库单' }).waitFor();
  await tableRow(page, 'OB202606140002').waitFor();
  await assertFixedTableLayout(page, 12);
  await openColumnMenu(page);
  await page.locator('[data-stock-bill-column-key="sourceNo"]').click();
  await page.keyboard.press('Escape');
  await waitColumnLayoutSettled(page);
  await assertFixedTableLayout(page, 11);

  const isolatedPreferences = await page.evaluate(([inboundKey, outboundKey]) => ({
    inbound: JSON.parse(localStorage.getItem(inboundKey) || '{}'),
    outbound: JSON.parse(localStorage.getItem(outboundKey) || '{}'),
  }), [inboundColumnStorageKey, outboundColumnStorageKey]);
  if (isolatedPreferences.inbound.entryMode !== false || isolatedPreferences.inbound.sourceNo !== true
    || isolatedPreferences.outbound.sourceNo !== false || isolatedPreferences.outbound.entryMode !== true) {
    throw new Error(`入库/出库列偏好未隔离：${JSON.stringify(isolatedPreferences)}`);
  }

  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: '出库单' }).waitFor();
  await tableRow(page, 'OB202606140002').waitFor();
  if ((await outerHeaderText(page)).includes('来源单号')) throw new Error('出库单列偏好刷新后未保留');
  await resetCurrentColumnPreference(page);
  await assertFixedTableLayout(page, 12);

  await page.locator('[data-menu-path="/warehouse/inbound-bills"]').click();
  await page.getByRole('heading', { name: '入库单' }).waitFor();
  await tableRow(page, 'IB202606140001').waitFor();
  if ((await outerHeaderText(page)).includes('录入方式')) throw new Error('返回入库单后未恢复其独立列偏好');
  await resetCurrentColumnPreference(page);
  await assertFixedTableLayout(page, 12);
}

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

async function selectRemoteOption(page, dialog, index, label) {
  await dialog.getByRole('combobox').nth(index).click();
  const options = page.locator('[data-remote-search-select-content]:visible');
  await options.locator('[data-select-option]').filter({ hasText: label }).first().click();
}

async function clickButton(page, name) {
  await page.getByRole('button', { name, exact: true }).click();
  await page.waitForTimeout(250);
}

async function waitListSettled(page) {
  await page.locator('[data-list-loading]').waitFor({ state: 'hidden', timeout: 5000 }).catch(() => undefined);
}

async function assertContentFilterLayout(page, expectedWidths) {
  const filter = page.locator('[data-list-filter-panel]');
  const layout = await filter.locator('[data-filter-layout]').getAttribute('data-filter-layout');
  if (layout !== 'content') throw new Error(`出入库单筛选区未启用内容适配布局：${layout}`);

  const metrics = await filter.evaluate((element) => {
    const panel = element.getBoundingClientRect();
    const fields = [...element.querySelectorAll('[data-filter-size]')].map((field) => {
      const rect = field.getBoundingClientRect();
      return { width: Math.round(rect.width), right: rect.right };
    });
    const actions = element.querySelector('.filter-actions')?.getBoundingClientRect();
    return {
      panelRight: panel.right,
      widths: fields.map(field => field.width),
      overflow: fields.some(field => field.right > panel.right + 1),
      actionsRight: actions?.right ?? null,
      panelOverflow: element.scrollWidth - element.clientWidth,
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    };
  });

  if (metrics.widths.join(',') !== expectedWidths.join(',')) {
    throw new Error(`出入库单筛选项宽度不符合内容契约：${metrics.widths.join(',')}`);
  }
  if (metrics.overflow || metrics.actionsRight === null || metrics.actionsRight > metrics.panelRight + 1
    || metrics.panelOverflow > 1 || metrics.pageOverflow > 1) {
    throw new Error(`出入库单筛选区出现横向溢出或操作区不可达：${JSON.stringify(metrics)}`);
  }
}

async function assertMobileContentFilterLayout(page) {
  const metrics = await page.locator('[data-list-filter-panel]').evaluate((element) => {
    const grid = element.querySelector('[data-filter-layout]');
    const gridWidth = grid?.getBoundingClientRect().width ?? 0;
    const widths = [...element.querySelectorAll('[data-filter-size]')]
      .map(field => Math.round(field.getBoundingClientRect().width));
    const actions = element.querySelector('.filter-actions')?.getBoundingClientRect();
    return {
      gridWidth: Math.round(gridWidth),
      widths,
      actionsWidth: Math.round(actions?.width ?? 0),
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    };
  });
  if (metrics.widths.some(width => Math.abs(width - metrics.gridWidth) > 1)
    || Math.abs(metrics.actionsWidth - metrics.gridWidth) > 1 || metrics.pageOverflow > 1) {
    throw new Error(`出入库单移动端筛选布局异常：${JSON.stringify(metrics)}`);
  }
}

async function selectRowAction(page, row, billNo, actionLabel) {
  const trigger = row.getByRole('button', { name: `更多 ${billNo} 操作` });
  await trigger.focus();
  await page.keyboard.press('Enter');
  const menu = page.getByRole('menu');
  await menu.getByRole('menuitem', { name: actionLabel, exact: true }).waitFor();
  await menu.getByRole('menuitem', { name: actionLabel, exact: true }).click();
}

async function assertDetailToggleMotion(page, row, item, collapseScreenshotPath) {
  const stockBillId = await row.getAttribute('data-stock-bill-id');
  if (!stockBillId) throw new Error('明细触发行缺少稳定的单据标识');
  const adjacentRow = row.locator('xpath=following-sibling::tr[@data-stock-bill-id][1]');
  if (await adjacentRow.count() !== 1) throw new Error('动效测试需要一条相邻业务行作为布局稳定基准');
  const expandButton = row.getByRole('button', { name: '展开明细' });
  // Playwright 点击被滚动容器遮挡的元素时会自动滚动；先稳定视口，避免把测试工具滚动误判为展开抖动。
  await expandButton.scrollIntoViewIfNeeded();
  await page.waitForTimeout(32);
  const rowTopBeforeExpand = await row.evaluate(element => element.getBoundingClientRect().top);
  const adjacentTopBeforeExpand = await adjacentRow.evaluate(element => element.getBoundingClientRect().top);
  await expandButton.click();
  const drawer = page.locator(`[data-stock-bill-detail-id="${stockBillId}"]`);
  const motion = await drawer.evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      name: style.animationName,
      duration: style.animationDuration,
      contain: style.contain,
      transform: style.transform,
      inlineSize: style.inlineSize,
    };
  });
  const motionDuration = motion.duration.endsWith('ms')
    ? Number.parseFloat(motion.duration)
    : Number.parseFloat(motion.duration) * 1000;
  if (!motion.name.includes('stock-bill-collapsible-down') || motionDuration < 100 || motionDuration > 300) {
    throw new Error(`展开明细缺少适度反馈动画：${JSON.stringify(motion)}`);
  }
  if (!motion.contain.includes('inline-size') || motion.transform !== 'none' || Number.parseFloat(motion.inlineSize) <= 0) {
    throw new Error(`展开明细未隔离横向尺寸或仍使用位移合成层：${JSON.stringify(motion)}`);
  }
  const expandSamples = [];
  for (let index = 0; index < 7; index += 1) {
    await page.waitForTimeout(35);
    expandSamples.push(await adjacentRow.evaluate(element => element.getBoundingClientRect().top));
  }
  for (let index = 1; index < expandSamples.length; index += 1) {
    if (expandSamples[index] + 2 < expandSamples[index - 1]) {
      throw new Error(`展开过程中相邻行发生反向回跳：${JSON.stringify(expandSamples)}`);
    }
  }
  await item.waitFor();
  await page.waitForTimeout(250);
  const rowTopAfterExpand = await row.evaluate(element => element.getBoundingClientRect().top);
  if (Math.abs(rowTopAfterExpand - rowTopBeforeExpand) > 2) {
    throw new Error(`展开明细导致触发行跳动：before=${rowTopBeforeExpand}, after=${rowTopAfterExpand}`);
  }
  const openState = await drawer.evaluate((element) => ({
    state: element.getAttribute('data-state'),
    height: element.getBoundingClientRect().height,
    hidden: element.hasAttribute('hidden'),
    visibility: getComputedStyle(element).visibility,
  }));
  if (openState.state !== 'open' || openState.height < 40 || openState.hidden || openState.visibility === 'hidden') {
    throw new Error(`展开动画结束态异常：${JSON.stringify(openState)}`);
  }
  const adjacentTopAfterExpand = await adjacentRow.evaluate(element => element.getBoundingClientRect().top);
  if (adjacentTopAfterExpand <= adjacentTopBeforeExpand + 20) throw new Error('展开明细未正常推开相邻业务行');
  await page.waitForTimeout(170);
  const adjacentTopAfterExpandStable = await adjacentRow.evaluate(element => element.getBoundingClientRect().top);
  if (Math.abs(adjacentTopAfterExpandStable - adjacentTopAfterExpand) > 2) {
    throw new Error(`展开结束后相邻行在稳定窗口继续跳动：${adjacentTopAfterExpand} -> ${adjacentTopAfterExpandStable}`);
  }

  const tableViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
  await tableViewport.evaluate((element) => {
    element.scrollLeft = Math.min(420, Math.max(0, element.scrollWidth - element.clientWidth));
    element.dispatchEvent(new Event('scroll'));
  });
  await page.waitForTimeout(32);

  async function captureCollapseLayout(time) {
    const [mainState, viewportState, tableState, rowState, drawerState, adjacentTop] = await Promise.all([
      page.locator('main').first().evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      tableViewport.evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      page.locator('.stock-bill-table-scroll [data-slot="table"]').first().evaluate(element => ({
        x: element.getBoundingClientRect().x,
        width: element.getBoundingClientRect().width,
      })),
      row.evaluate((element) => {
        const keyCell = element.querySelector('.stock-bill-key-column');
        const actionCell = element.querySelector('.stock-bill-actions-column');
        return {
          keyX: keyCell?.getBoundingClientRect().x ?? null,
          actionRight: actionCell?.getBoundingClientRect().right ?? null,
        };
      }),
      drawer.evaluate(element => ({
        state: element.getAttribute('data-state'),
        height: element.getBoundingClientRect().height,
        x: element.getBoundingClientRect().x,
        width: element.getBoundingClientRect().width,
        hidden: element.hasAttribute('hidden'),
        cardX: element.querySelector('.stock-bill-detail-card')?.getBoundingClientRect().x ?? null,
        cardWidth: element.querySelector('.stock-bill-detail-card')?.getBoundingClientRect().width ?? null,
        detailViewportClientWidth: element.querySelector('.warehouse-detail-table-scroll [data-slot="table-container"]')?.clientWidth ?? null,
        detailViewportScrollWidth: element.querySelector('.warehouse-detail-table-scroll [data-slot="table-container"]')?.scrollWidth ?? null,
      })),
      adjacentRow.evaluate(element => element.getBoundingClientRect().top),
    ]);
    return { time, mainState, viewportState, tableState, rowState, drawerState, adjacentTop };
  }

  function assertHorizontalStable(baseline, sample) {
    const checks = [
      ['main.clientWidth', baseline.mainState.clientWidth, sample.mainState.clientWidth],
      ['main.scrollWidth', baseline.mainState.scrollWidth, sample.mainState.scrollWidth],
      ['main.scrollLeft', baseline.mainState.scrollLeft, sample.mainState.scrollLeft],
      ['viewport.clientWidth', baseline.viewportState.clientWidth, sample.viewportState.clientWidth],
      ['viewport.scrollWidth', baseline.viewportState.scrollWidth, sample.viewportState.scrollWidth],
      ['viewport.scrollLeft', baseline.viewportState.scrollLeft, sample.viewportState.scrollLeft],
      ['table.x', baseline.tableState.x, sample.tableState.x],
      ['table.width', baseline.tableState.width, sample.tableState.width],
      ['keyCell.x', baseline.rowState.keyX, sample.rowState.keyX],
      ['actionCell.right', baseline.rowState.actionRight, sample.rowState.actionRight],
      ['drawer.x', baseline.drawerState.x, sample.drawerState.x],
      ['drawer.width', baseline.drawerState.width, sample.drawerState.width],
    ];
    if (!sample.drawerState.hidden) {
      checks.push(
        ['detailCard.x', baseline.drawerState.cardX, sample.drawerState.cardX],
        ['detailCard.width', baseline.drawerState.cardWidth, sample.drawerState.cardWidth],
        ['detailViewport.clientWidth', baseline.drawerState.detailViewportClientWidth, sample.drawerState.detailViewportClientWidth],
        ['detailViewport.scrollWidth', baseline.drawerState.detailViewportScrollWidth, sample.drawerState.detailViewportScrollWidth],
      );
    }
    const changed = checks.find(([, expected, actual]) => (
      expected === null || actual === null || Math.abs(expected - actual) > 0.75
    ));
    if (changed) throw new Error(`收起明细第 ${sample.time}ms 发生横向位移：${changed[0]} ${changed[1]} -> ${changed[2]}`);
  }

  const collapseBaseline = await captureCollapseLayout(-1);
  await row.getByRole('button', { name: '收起明细' }).click();
  const collapseStartedAt = Date.now();
  const collapseSamples = [];
  for (const targetTime of [0, 24, 64, 110, 170, 230, 320, 420]) {
    const waitTime = targetTime - (Date.now() - collapseStartedAt);
    if (waitTime > 0) await page.waitForTimeout(waitTime);
    collapseSamples.push(await captureCollapseLayout(targetTime));
    if (collapseScreenshotPath && targetTime === 110) {
      await page.screenshot({ path: collapseScreenshotPath, fullPage: true });
    }
  }
  collapseSamples.forEach(sample => assertHorizontalStable(collapseBaseline, sample));
  for (let index = 1; index < collapseSamples.length; index += 1) {
    if (collapseSamples[index].adjacentTop > collapseSamples[index - 1].adjacentTop + 2) {
      throw new Error(`收起过程中相邻行发生反向回跳：${JSON.stringify(collapseSamples.map(sample => sample.adjacentTop))}`);
    }
  }
  await item.waitFor({ state: 'hidden' });
  await page.waitForTimeout(180);
  const closedState = await drawer.evaluate((element) => ({
    state: element.getAttribute('data-state'),
    height: element.getBoundingClientRect().height,
    hidden: element.hasAttribute('hidden'),
    visibility: getComputedStyle(element).visibility,
  }));
  if (closedState.state !== 'closed' || closedState.height > 1 || (!closedState.hidden && closedState.visibility !== 'hidden')) {
    throw new Error(`收起动画结束态异常：${JSON.stringify(closedState)}`);
  }
  const rowTopAfterCollapse = await row.evaluate(element => element.getBoundingClientRect().top);
  if (Math.abs(rowTopAfterCollapse - rowTopBeforeExpand) > 2) {
    throw new Error(`收起明细导致触发行跳动：before=${rowTopBeforeExpand}, after=${rowTopAfterCollapse}`);
  }
  const adjacentTopAfterCollapse = await adjacentRow.evaluate(element => element.getBoundingClientRect().top);
  if (Math.abs(adjacentTopAfterCollapse - adjacentTopBeforeExpand) > 2) {
    throw new Error(`收起结束后相邻行未回到基准位置：before=${adjacentTopBeforeExpand}, after=${adjacentTopAfterCollapse}`);
  }
  await page.waitForTimeout(220);
  const adjacentTopAfterCollapseStable = await adjacentRow.evaluate(element => element.getBoundingClientRect().top);
  if (Math.abs(adjacentTopAfterCollapseStable - adjacentTopAfterCollapse) > 2) {
    throw new Error(`收起结束后相邻行在稳定窗口继续跳动：${adjacentTopAfterCollapse} -> ${adjacentTopAfterCollapseStable}`);
  }
}

async function outerHeaderText(page) {
  return page.locator('[data-slot="table"]').first().evaluate(element => element.querySelector(':scope > thead')?.innerText ?? '');
}

async function outerBodyText(page) {
  return page.locator('[data-slot="table"]').first().evaluate(element => element.querySelector(':scope > tbody')?.innerText ?? '');
}

async function assertStockBillTableUsable(page, billNo) {
  const row = tableRow(page, billNo);
  const layout = await row.evaluate((element) => {
    const cells = Array.from(element.children);
    const createdAt = cells[10];
    const actions = cells[11];
    const actionsStyle = getComputedStyle(actions);
    return {
      createdAtOverflow: createdAt.scrollWidth - createdAt.clientWidth,
      actionsOverflow: actions.scrollWidth - actions.clientWidth,
      actionsPosition: actionsStyle.position,
      actionsWidth: Math.round(actions.getBoundingClientRect().width),
      actionsText: actions.innerText,
    };
  });
  if (layout.createdAtOverflow > 1 || layout.actionsOverflow > 1 || layout.actionsPosition !== 'sticky' || layout.actionsWidth < 120) {
    throw new Error(`创建时间和操作列布局异常：${JSON.stringify(layout)}`);
  }

  const tableViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
  const scrollState = await tableViewport.evaluate((element) => {
    const header = element.querySelector('thead th');
    const headerStyle = header ? getComputedStyle(header) : null;
    return {
      horizontallyScrollable: element.scrollWidth > element.clientWidth + 4,
      height: Math.round(element.getBoundingClientRect().height),
      rowCount: element.querySelectorAll('tbody > tr').length,
      overflowX: getComputedStyle(element).overflowX,
      headerPosition: headerStyle ? headerStyle.position : '',
      headerBackground: headerStyle ? headerStyle.backgroundColor : '',
    };
  });
  const shouldShowPageHeight = scrollState.rowCount >= 8;
  const transparentHeader = !scrollState.headerBackground || scrollState.headerBackground === 'transparent' || /rgba\([^)]*,\s*0\)/.test(scrollState.headerBackground);
  if (!scrollState.horizontallyScrollable || !['auto', 'scroll'].includes(scrollState.overflowX) || (shouldShowPageHeight && scrollState.height < 420) || scrollState.headerPosition !== 'sticky' || transparentHeader) {
    throw new Error(`主表滚动容器高度、横向滚动或固定表头异常：${JSON.stringify(scrollState)}`);
  }
}

async function assertDetailFieldGrid(dialog) {
  const columns = await dialog.locator('.detail-field-grid').evaluate(element =>
    getComputedStyle(element).gridTemplateColumns.split(' ').length,
  );
  if (columns !== 3) throw new Error(`详情字段桌面布局应为 3 列，当前为 ${columns} 列`);
}

async function assertDistinctTypeBadges(page, labels) {
  const classes = await page.locator('[data-stock-bill-id]').evaluateAll((rows, expectedLabels) => {
    const result = {};
    for (const row of rows) {
      const rowText = row.innerText;
      const label = expectedLabels.find(item => rowText.includes(item));
      if (!label) continue;
      const badge = Array.from(row.querySelectorAll('[data-slot="badge"]')).find(item => item.textContent?.includes(label));
      if (badge) result[label] = badge.className;
    }
    return result;
  }, labels);
  for (const label of labels) {
    if (!classes[label]) throw new Error(`列表缺少类型标签：${label}`);
  }
  if (new Set(Object.values(classes)).size !== labels.length) {
    throw new Error(`入库/出库类型标签颜色未明显区分：${JSON.stringify(classes)}`);
  }
}

async function assertExpandedDetailTable(page, direction) {
  const detail = page.locator('.warehouse-detail-table-scroll').first();
  const state = await detail.evaluate((element) => {
    const card = element.closest('.stock-bill-detail-card');
    const headerText = element.querySelector('thead')?.innerText ?? '';
    return {
      headerText,
      cardWidth: card ? Math.round(card.getBoundingClientRect().width) : 0,
      tableWidth: Math.round(element.querySelector('[data-slot="table"]')?.getBoundingClientRect().width ?? 0),
      alignments: [...element.querySelectorAll('[data-slot="table-head"], [data-slot="table-cell"]')]
        .map(cell => getComputedStyle(cell).textAlign),
    };
  });
  const qtyLabel = direction === 'INBOUND' ? '入库量' : '出库量';
  const pendingLabel = direction === 'INBOUND' ? '剩余未入库' : '剩余未出库';
  for (const expected of ['产品编码', '产品名称', '单位', qtyLabel, '合格数量', '不合格数量', pendingLabel, '备注']) {
    if (!state.headerText.includes(expected)) throw new Error(`展开明细表头缺少字段：${expected}`);
  }
  if (state.headerText.includes('质检')) throw new Error('展开明细不应再使用“质检”汇总列');
  if (state.cardWidth > 1040 || state.tableWidth > 1040) {
    throw new Error(`展开明细表格过宽，字段间距会被拉开：${JSON.stringify(state)}`);
  }
  if (state.alignments.length < 16 || state.alignments.some(alignment => alignment !== 'center')) {
    throw new Error(`展开明细表头和数据单元格必须全部居中：${JSON.stringify(state)}`);
  }
}

runSmoke({
  route: '/warehouse/inbound-bills',
  screenshot: screenshotPath('warehouse-inbound-normal.png'),
  async test(page) {
    await assertStockBillColumnPreferences(page);
    await page.getByRole('heading', { name: '入库单' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '入库单数据汇总', filterLabel: '入库单筛选' });
    await assertContentFilterLayout(page, [220, 220, 280, 168, 168, 168]);
    await tableRow(page, 'IB202606140001').waitFor();
    await assertFixedTableLayout(page, 12);
    await assertStockBillTableUsable(page, 'IB202606140001');
    await assertDistinctTypeBadges(page, ['采购入库', '销售退货入库', '调整入库']);
    const initialInboundRow = tableRow(page, 'IB202606140001');
    const initialInboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '经典原味苏打水' });
    await assertDetailToggleMotion(page, initialInboundRow, initialInboundItem, path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-07-14-134409-stock-bill-collapse-stability', 'inbound-collapse-110ms.png'));
    const inboundHeaderText = await outerHeaderText(page);
    for (const expected of ['入库单号', '类型', '录入方式', '来源类型', '来源单号', '来源对象', '仓库', '入库量', '状态', '负责人', '创建时间', '操作']) {
      if (!inboundHeaderText.includes(expected)) throw new Error(`入库单列表表头缺少独立列：${expected}`);
    }
    for (const forbidden of ['入库单号 / 商品', '类型 / 来源', '往来方', '供应商/客户', '来源对象 / 仓库', '状态 / 操作']) {
      if (inboundHeaderText.includes(forbidden)) throw new Error(`入库单列表不应使用混合表头：${forbidden}`);
    }

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['本页待确认', '本页已确认', '本页已取消', '本页系统生成']) {
      if (!summaryText.includes(expected)) throw new Error(`入库单摘要缺少 ${expected}`);
    }
    await clickButton(page, '刷新');

    await page.getByPlaceholder('如 IB202606140001').fill('IB202606130006');
    await clickButton(page, '查询');
    const pendingInboundRow = tableRow(page, 'IB202606130006');
    await pendingInboundRow.waitFor();
    await assertStockBillTableUsable(page, 'IB202606130006');
    const pendingInboundText = await pendingInboundRow.innerText();
    for (const expected of ['采购入库', '系统生成', '采购订单', '谷仓食品批发', '2 条商品', '待确认']) {
      if (!pendingInboundText.includes(expected)) throw new Error(`待确认入库单列表缺少 ${expected}`);
    }
    for (const forbidden of ['P000007', 'P000033', '本次', '计划', '已处理', '剩余未入库']) {
      if (pendingInboundText.includes(forbidden)) throw new Error(`入库单列表不应展示订单进度字段：${forbidden}`);
    }
    const inboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '每日坚果混合装' });
    if (await inboundItem.isVisible().catch(() => false)) throw new Error('入库单明细默认应收起');
    await pendingInboundRow.getByRole('button', { name: '展开明细' }).click();
    await inboundItem.waitFor();
    await waitListSettled(page);
    await assertExpandedDetailTable(page, 'INBOUND');
    if (await inboundItem.locator('[data-overflow-tooltip]').count() === 0) throw new Error('入库单明细备注未接入统一溢出提示');
    const inboundItemText = await inboundItem.innerText();
    for (const expected of ['P000007', '每日坚果混合装', '0 盒', '40 盒']) {
      if (!inboundItemText.includes(expected)) throw new Error(`入库单商品子行缺少 ${expected}`);
    }
    await page.screenshot({ path: screenshotPath('warehouse-inbound-expanded.png'), fullPage: true });

    await pendingInboundRow.getByRole('button', { name: '详情' }).click();
    const inboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const inboundDetailText = await inboundDetail.innerText();
    for (const expected of ['来源对象', '采购数量', '累计已入库', '本次入库数量', '剩余未入库', '每日坚果混合装']) {
      if (!inboundDetailText.includes(expected)) throw new Error(`入库单详情缺少 ${expected}`);
    }
    await assertDetailFieldGrid(inboundDetail);
    await page.waitForTimeout(250);
    await page.screenshot({ path: screenshotPath('warehouse-inbound-detail.png'), fullPage: true });
    await inboundDetail.getByRole('button', { name: 'Close' }).click();

    await pendingInboundRow.getByRole('button', { name: '更多 IB202606130006 操作' }).click();
    const pendingActionsMenu = page.getByRole('menu');
    for (const expected of ['编辑入库单', '确认入库', '取消入库单']) {
      await pendingActionsMenu.getByRole('menuitem', { name: expected, exact: true }).waitFor();
    }
    await page.screenshot({ path: screenshotPath('warehouse-inbound-row-actions.png'), fullPage: true });
    await pendingActionsMenu.getByRole('menuitem', { name: '编辑入库单', exact: true }).click();
    const editInbound = page.getByRole('dialog', { name: '编辑入库单' });
    const editInboundText = await editInbound.innerText();
    for (const expected of ['采购数量', '累计已入库', '本次入库数量', '剩余未入库']) {
      if (!editInboundText.includes(expected)) throw new Error(`入库编辑弹窗缺少 ${expected}`);
    }
    for (const expected of ['P000007', '每日坚果混合装']) {
      if (!editInboundText.includes(expected)) throw new Error(`入库编辑弹窗产品快照缺少 ${expected}`);
    }
    const editInboundValues = await editInbound.locator('input').evaluateAll(inputs => inputs.map(input => input.value).join('\n'));
    for (const expected of ['0']) {
      if (!editInboundValues.includes(expected)) throw new Error(`入库编辑弹窗输入数据缺少 ${expected}`);
    }
    if (await editInbound.getByRole('button', { name: '添加产品' }).isVisible().catch(() => false)) {
      throw new Error('系统生成的待确认入库单不应允许新增产品');
    }
    const pendingEditComboboxCount = await editInbound.getByRole('combobox').count();
    if (pendingEditComboboxCount !== 0) throw new Error(`待确认入库单不应暴露仓库或产品选择器，当前 ${pendingEditComboboxCount} 个`);
    await page.waitForTimeout(350);
    await page.screenshot({ path: screenshotPath('warehouse-inbound-edit.png'), fullPage: true });
    await editInbound.getByRole('button', { name: 'Close' }).click();
    await editInbound.waitFor({ state: 'hidden' });
    // 模拟用户通过右上角关闭后等待请求收尾，再次编辑同一张单据。
    await page.waitForTimeout(800);
    await selectRowAction(page, pendingInboundRow, 'IB202606130006', '编辑入库单');
    const reopenedInbound = page.getByRole('dialog', { name: '编辑入库单' });
    await reopenedInbound.locator('.stock-bill-product-snapshot').first().waitFor();
    const reopenedProducts = await reopenedInbound.locator('.stock-bill-product-snapshot').allTextContents();
    if (reopenedProducts.length !== 2 || !reopenedProducts.some(text => text.includes('P000007')) || !reopenedProducts.some(text => text.includes('P000033'))) {
      throw new Error(`关闭后再次编辑时产品明细丢失：${JSON.stringify(reopenedProducts)}`);
    }
    await reopenedInbound.getByRole('button', { name: '关闭' }).click();

    await clickButton(page, '重置');
    await page.getByPlaceholder('如 IB202606140001').fill('IB202607010002');
    await clickButton(page, '查询');
    const manualInboundRow = tableRow(page, 'IB202607010002');
    await manualInboundRow.waitFor();
    await selectRowAction(page, manualInboundRow, 'IB202607010002', '编辑入库单');
    const manualInboundEdit = page.getByRole('dialog', { name: '编辑入库单' });
    const sourceOrderSelect = manualInboundEdit.getByRole('combobox').nth(1);
    if (!await sourceOrderSelect.isEnabled()) throw new Error('人工补录采购入库草稿的来源单号应支持搜索选择');
    const sourcePartySelect = manualInboundEdit.getByRole('combobox').nth(2);
    if (!await sourcePartySelect.isEnabled()) throw new Error('人工补录采购入库草稿的来源对象应可编辑');
    await manualInboundEdit.getByRole('button', { name: '手动输入' }).click();
    const manualSourceNo = manualInboundEdit.getByPlaceholder('填写线下单据号');
    if (!await manualSourceNo.isEditable()) throw new Error('人工补录采购入库草稿的来源单号应支持手工输入');
    await manualSourceNo.fill('MANUAL-PO-20260701-UPDATED');
    await manualInboundEdit.getByRole('button', { name: '保存修改' }).click();
    await page.getByText('入库单已保存', { exact: true }).last().waitFor();
    await manualInboundEdit.waitFor({ state: 'hidden' });
    if (!(await manualInboundRow.innerText()).includes('MANUAL-PO-20260701-UPDATED')) {
      throw new Error('人工补录采购入库草稿保存后未回显新的来源单号');
    }
    await clickButton(page, '重置');
    await pendingInboundRow.waitFor();

    await selectRowAction(page, pendingInboundRow, 'IB202606130006', '确认入库');
    const confirmInboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const confirmInboundDetailText = await confirmInboundDetail.innerText();
    if (!confirmInboundDetailText.includes('请先核对完整单头和产品明细') || !confirmInboundDetailText.includes('每日坚果混合装')) {
      throw new Error('列表确认入库必须先打开详情并展示完整明细');
    }
    await confirmInboundDetail.getByRole('button', { name: '确认入库' }).click();
    await page.getByText(/请先填写本次入库数量/).waitFor();
    await confirmInboundDetail.getByRole('button', { name: '关闭' }).click();
    await clickButton(page, '重置');
    await page.getByPlaceholder('如 IB202606140001').fill('IB202606140003');
    await clickButton(page, '查询');
    const draftInboundRow = tableRow(page, 'IB202606140003');
    await draftInboundRow.waitFor();
    if (!(await draftInboundRow.innerText()).includes('草稿')) throw new Error('草稿入库单状态缺失');
    await selectRowAction(page, draftInboundRow, 'IB202606140003', '编辑入库单');
    const editDraftInbound = page.getByRole('dialog', { name: '编辑入库单' });
    const draftInboundText = await editDraftInbound.innerText();
    if (!draftInboundText.includes('添加产品')) throw new Error('手工草稿入库单应允许维护产品明细');
    for (const expected of ['来源仓库', '华南中心仓']) {
      if (!draftInboundText.includes(expected)) throw new Error(`调整入库编辑弹窗缺少 ${expected}`);
    }
    const draftEditComboboxCount = await editDraftInbound.getByRole('combobox').count();
    if (draftEditComboboxCount < 2) throw new Error(`草稿入库单应允许选择仓库和产品，当前选择器 ${draftEditComboboxCount} 个`);
    const draftSourceWarehouseSelect = editDraftInbound.getByRole('combobox').nth(1);
    if (!await draftSourceWarehouseSelect.isEnabled()) throw new Error('调整入库草稿的来源仓库应可选择');
    await selectRemoteOption(page, editDraftInbound, 1, '华东中心仓');
    if (!(await editDraftInbound.innerText()).includes('华东中心仓')) throw new Error('调整入库编辑时选择来源仓库后未即时回显');
    await editDraftInbound.getByRole('button', { name: '保存修改' }).click();
    await page.getByText('入库单已保存', { exact: true }).last().waitFor();
    await editDraftInbound.waitFor({ state: 'hidden' });
    if (!(await tableRow(page, 'IB202606140003').innerText()).includes('华东中心仓')) {
      throw new Error('调整入库保存后未回写所选来源仓库快照');
    }
    await clickButton(page, '新增入库单');
    const createInbound = page.getByRole('dialog', { name: '新增入库单' });
    if (!(await createInbound.innerText()).includes('来源仓库')) throw new Error('新建调整入库单缺少来源仓库字段');
    await selectRemoteOption(page, createInbound, 1, '华东中心仓');
    if (!(await createInbound.innerText()).includes('华东中心仓')) throw new Error('新建调整入库单选择来源仓库后未显示');
    await createInbound.getByRole('button', { name: '关闭' }).click();
    await page.waitForTimeout(350);
    await page.screenshot({ path: screenshotPath('warehouse-inbound-draft-edit.png'), fullPage: true });
    await selectRowAction(page, draftInboundRow, 'IB202606140003', '提交确认');
    const submitInboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const submitInboundDetailText = await submitInboundDetail.innerText();
    if (!submitInboundDetailText.includes('请先核对完整单头和产品明细') || !submitInboundDetailText.includes('提交确认') || !submitInboundDetailText.includes('库存盘点调整')) {
      throw new Error('列表提交确认必须先打开详情并展示完整草稿信息');
    }
    await submitInboundDetail.getByRole('button', { name: '提交确认' }).click();
    const submitInbound = page.getByRole('alertdialog', { name: '提交入库单' });
    if (!(await submitInbound.innerText()).includes('不改变库存')) throw new Error('提交确认弹窗应说明不改变库存');
    await submitInbound.getByRole('button', { name: '取消' }).click();
    await submitInboundDetail.getByRole('button', { name: '关闭' }).click();
    await clickButton(page, '重置');

    await selectFilter(page, 1, '采购入库');
    await selectFilter(page, 3, '待确认');
    await clickButton(page, '查询');
    const filteredText = await outerBodyText(page);
    if (!filteredText.includes('采购入库') || !filteredText.includes('待确认')) throw new Error('入库类型与状态筛选未生效');

    await page.locator('[data-menu-path="/warehouse/outbound-bills"]').click();
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await clickButton(page, '重置');
    await page.getByPlaceholder('如 OB202606140002').fill('OB202607010002');
    await clickButton(page, '查询');
    const draftAdjustmentOutboundRow = tableRow(page, 'OB202607010002');
    await draftAdjustmentOutboundRow.waitFor();
    await selectRowAction(page, draftAdjustmentOutboundRow, 'OB202607010002', '编辑出库单');
    const draftAdjustmentOutboundEdit = page.getByRole('dialog', { name: '编辑出库单' });
    const draftOutboundSourceWarehouseSelect = draftAdjustmentOutboundEdit.getByRole('combobox').nth(1);
    if (!await draftOutboundSourceWarehouseSelect.isEnabled()) throw new Error('调整出库草稿的来源仓库应可选择');
    await selectRemoteOption(page, draftAdjustmentOutboundEdit, 1, '华南中心仓');
    await draftAdjustmentOutboundEdit.getByRole('button', { name: '保存修改' }).click();
    await page.getByText('出库单已保存', { exact: true }).last().waitFor();
    await draftAdjustmentOutboundEdit.waitFor({ state: 'hidden' });
    if (!(await draftAdjustmentOutboundRow.innerText()).includes('华南中心仓')) {
      throw new Error('调整出库保存后未回写所选来源仓库快照');
    }
    await clickButton(page, '重置');
    await tableRow(page, 'OB202606140002').waitFor();
    await assertFixedTableLayout(page, 12);
    await assertDistinctTypeBadges(page, ['销售出库', '采购退货出库', '调整出库']);
    const outboundHeaderText = await outerHeaderText(page);
    for (const expected of ['出库单号', '类型', '录入方式', '来源类型', '来源单号', '来源对象', '仓库', '出库量', '状态', '负责人', '创建时间', '操作']) {
      if (!outboundHeaderText.includes(expected)) throw new Error(`出库单列表表头缺少独立列：${expected}`);
    }
    for (const forbidden of ['出库单号 / 商品', '类型 / 来源', '往来方', '客户/供应商', '来源对象 / 仓库', '状态 / 操作']) {
      if (outboundHeaderText.includes(forbidden)) throw new Error(`出库单列表不应使用混合表头：${forbidden}`);
    }
    const outboundRow = tableRow(page, 'OB202606140002');
    await assertStockBillTableUsable(page, 'OB202606140002');
    const outboundText = await outboundRow.innerText();
    for (const expected of ['上海星河便利店', '销售出库', '销售订单', '8 箱']) {
      if (!outboundText.includes(expected)) throw new Error(`出库单列表缺少 ${expected}`);
    }
    for (const forbidden of ['本次', '计划', '已处理', '剩余未出库']) {
      if (outboundText.includes(forbidden)) throw new Error(`出库单列表不应展示订单进度字段：${forbidden}`);
    }
    const outboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '经典原味苏打水' });
    if (await outboundItem.isVisible().catch(() => false)) throw new Error('出库单明细默认应收起');
    await assertDetailToggleMotion(page, outboundRow, outboundItem, path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-07-14-134409-stock-bill-collapse-stability', 'outbound-collapse-110ms.png'));
    await outboundRow.getByRole('button', { name: '展开明细' }).click();
    await outboundItem.waitFor();
    await waitListSettled(page);
    await assertExpandedDetailTable(page, 'OUTBOUND');
    const outboundItemText = await outboundItem.innerText();
    for (const expected of ['P000001', '经典原味苏打水', '0 箱', '8 箱']) {
      if (!outboundItemText.includes(expected)) throw new Error(`出库单商品子行缺少 ${expected}`);
    }
    await outboundRow.getByRole('button', { name: '详情' }).click();
    const outboundDetail = page.getByRole('dialog', { name: '出库单详情' });
    const outboundDetailText = await outboundDetail.innerText();
    for (const expected of ['来源对象', '销售数量', '累计已出库', '本次出库数量', '剩余未出库']) {
      if (!outboundDetailText.includes(expected)) throw new Error(`出库单详情缺少 ${expected}`);
    }
    await assertDetailFieldGrid(outboundDetail);
    await page.waitForTimeout(250);
    await page.screenshot({ path: screenshotPath('warehouse-outbound-detail.png'), fullPage: true });
    await outboundDetail.getByRole('button', { name: 'Close' }).click();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '出库单数据汇总', filterLabel: '出库单筛选' });
    await tableRow(page, 'OB202606140002').waitFor();
    const compactOutboundRow = tableRow(page, 'OB202606140002');
    const compactOutboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '经典原味苏打水' });
    await assertDetailToggleMotion(page, compactOutboundRow, compactOutboundItem, path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-07-14-134409-stock-bill-collapse-stability', 'outbound-collapse-1115-110ms.png'));
    await assertContentFilterLayout(page, [220, 220, 280, 168, 168, 168]);
    const tableViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
    await tableViewport.evaluate((element) => {
      element.scrollLeft = element.scrollWidth;
      element.dispatchEvent(new Event('scroll'));
    });
    await page.waitForTimeout(100);
    const stickyLayout = await tableViewport.evaluate((element) => {
      const container = element.getBoundingClientRect();
      const keyHeader = element.querySelector('thead .stock-bill-key-column')?.getBoundingClientRect();
      const keyCell = element.querySelector('tbody .stock-bill-key-column')?.getBoundingClientRect();
      const actionHeader = element.querySelector('thead .stock-bill-actions-column')?.getBoundingClientRect();
      const normalHeader = element.querySelector('thead [data-slot="table-head"]:not(.stock-bill-key-column):not(.stock-bill-actions-column)');
      const keyHeaderStyle = keyHeader ? getComputedStyle(element.querySelector('thead .stock-bill-key-column')) : null;
      const normalHeaderStyle = normalHeader ? getComputedStyle(normalHeader) : null;
      const keyHeaderHit = keyHeader
        ? document.elementFromPoint(keyHeader.left + keyHeader.width / 2, keyHeader.top + keyHeader.height / 2)
        : null;
      return {
        overflow: element.scrollWidth - element.clientWidth,
        scrolled: element.scrollLeft,
        canScrollStart: element.getAttribute('data-scroll-start'),
        keyHeaderLeft: keyHeader ? keyHeader.left - container.left : null,
        keyCellLeft: keyCell ? keyCell.left - container.left : null,
        actionHeaderRight: actionHeader ? container.right - actionHeader.right : null,
        keyHeaderText: keyHeaderHit?.closest('.stock-bill-key-column')?.textContent?.trim() || '',
        keyHeaderZIndex: keyHeaderStyle?.zIndex ?? null,
        normalHeaderZIndex: normalHeaderStyle?.zIndex ?? null,
      };
    });
    if (stickyLayout.overflow <= 2 || stickyLayout.scrolled <= 2 || stickyLayout.canScrollStart !== 'true'
      || stickyLayout.keyHeaderLeft === null || Math.abs(stickyLayout.keyHeaderLeft) > 2
      || stickyLayout.keyCellLeft === null || Math.abs(stickyLayout.keyCellLeft) > 2
      || stickyLayout.actionHeaderRight === null || Math.abs(stickyLayout.actionHeaderRight) > 2
      || stickyLayout.keyHeaderText !== '出库单号'
      || Number(stickyLayout.keyHeaderZIndex) <= Number(stickyLayout.normalHeaderZIndex)) {
      throw new Error(`仓储宽表单号列或操作列未保持可见：${JSON.stringify(stickyLayout)}`);
    }
    if (await outboundRow.getByRole('button', { name: '更多 OB202606140002 操作' }).count()) {
      throw new Error('已确认出库单不应显示编辑、确认或取消菜单');
    }
    const cancelledOutboundRow = tableRow(page, 'OB202606130005');
    if (await cancelledOutboundRow.getByRole('button', { name: '更多 OB202606130005 操作' }).count()) {
      throw new Error('已取消出库单不应显示编辑、确认或取消菜单');
    }
    const pendingOutboundRow = tableRow(page, 'OB202606100015');
    const pendingOutboundTrigger = pendingOutboundRow.getByRole('button', { name: '更多 OB202606100015 操作' });
    await pendingOutboundTrigger.focus();
    await page.keyboard.press('Enter');
    const confirmOutboundMenuItem = page.getByRole('menuitem', { name: '确认出库', exact: true });
    await confirmOutboundMenuItem.waitFor();
    await page.keyboard.press('Escape');
    await confirmOutboundMenuItem.waitFor({ state: 'hidden' });
    if (!await pendingOutboundTrigger.evaluate(element => document.activeElement === element)) {
      throw new Error('Escape 关闭行操作菜单后焦点未返回原触发器');
    }
    await page.screenshot({ path: screenshotPath('warehouse-outbound-1115.png'), fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await tableRow(page, 'OB202606140002').waitFor();
    await assertMobileContentFilterLayout(page);
    await page.screenshot({ path: screenshotPath('warehouse-outbound-390.png'), fullPage: true });
  },
}).then(() => runSmoke({
  route: '/warehouse/inbound-bills',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: screenshotPath('warehouse-stock-bills-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '入库单' }).waitFor();
    await openColumnMenu(page);
    await page.locator('[data-stock-bill-column-key="entryMode"]').click();
    await waitColumnLayoutSettled(page);
    const reducedColumnMotion = await page.locator('.stock-bill-table-scroll').evaluate(element => ({
      state: element.getAttribute('data-column-layout-state'),
      duration: getComputedStyle(element.querySelector('.stock-bill-list-table')).transitionDuration,
    }));
    const reducedColumnDuration = reducedColumnMotion.duration.endsWith('ms')
      ? Number.parseFloat(reducedColumnMotion.duration)
      : Number.parseFloat(reducedColumnMotion.duration) * 1000;
    if (reducedColumnMotion.state !== 'idle' || reducedColumnDuration > 0.1) {
      throw new Error(`减少动态效果模式下字段切换仍有动画：${JSON.stringify(reducedColumnMotion)}`);
    }
    await page.keyboard.press('Escape');
    await resetCurrentColumnPreference(page);
    const row = tableRow(page, 'IB202606140001');
    await row.waitFor();
    await row.getByRole('button', { name: '展开明细' }).click();
    const drawer = page.locator('[data-stock-bill-detail-id]').first();
    const duration = await drawer.evaluate((element) => {
      const value = getComputedStyle(element).animationDuration;
      return value.endsWith('ms') ? Number.parseFloat(value) : Number.parseFloat(value) * 1000;
    });
    if (duration > 1) throw new Error(`减少动态效果模式下明细展开仍有长动画：${duration}ms`);
    const reducedViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
    await reducedViewport.evaluate((element) => {
      element.scrollLeft = Math.min(320, Math.max(0, element.scrollWidth - element.clientWidth));
    });
    const reducedBaseline = await Promise.all([
      reducedViewport.evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      row.evaluate(element => ({
        keyX: element.querySelector('.stock-bill-key-column')?.getBoundingClientRect().x ?? null,
        actionRight: element.querySelector('.stock-bill-actions-column')?.getBoundingClientRect().right ?? null,
      })),
    ]);
    await row.getByRole('button', { name: '收起明细' }).click();
    await page.waitForTimeout(32);
    const reducedCollapsed = await Promise.all([
      reducedViewport.evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      row.evaluate(element => ({
        keyX: element.querySelector('.stock-bill-key-column')?.getBoundingClientRect().x ?? null,
        actionRight: element.querySelector('.stock-bill-actions-column')?.getBoundingClientRect().right ?? null,
      })),
      drawer.evaluate(element => ({
        height: element.getBoundingClientRect().height,
        hidden: element.hasAttribute('hidden'),
      })),
    ]);
    if (JSON.stringify(reducedBaseline) !== JSON.stringify(reducedCollapsed.slice(0, 2))) {
      throw new Error(`减少动态效果模式下收起明细发生横向位移：${JSON.stringify({ reducedBaseline, reducedCollapsed })}`);
    }
    if (reducedCollapsed[2].height > 1 || !reducedCollapsed[2].hidden) {
      throw new Error(`减少动态效果模式下明细未快速稳定：${JSON.stringify(reducedCollapsed[2])}`);
    }
    if (await row.getByRole('button', { name: '更多 IB202606140001 操作' }).count()) {
      throw new Error('已确认入库单不应显示编辑、确认或取消菜单');
    }
    const pendingRow = tableRow(page, 'IB202606130006');
    await pendingRow.getByRole('button', { name: '更多 IB202606130006 操作' }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('menuitem', { name: '确认入库', exact: true }).waitFor();
    await page.keyboard.press('Escape');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 仓储单据页面发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 入库单/出库单关键列、行操作、明细动效、业务保护与响应式布局通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
