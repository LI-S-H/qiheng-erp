const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const baseUrl = 'http://127.0.0.1:5173';
const outDir = __dirname;

fs.mkdirSync(outDir, { recursive: true });

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

async function assertPurchaseCreatedByFullText(page) {
  await openRoute(page, '/purchase/orders');

  const state = await page.locator('.erp-data-table tbody tr').evaluateAll(rows => {
    const candidates = rows
      .map(row => {
        const cell = row.querySelectorAll('[data-slot="table-cell"]')[6];
        if (!cell) return null;
        const style = getComputedStyle(cell);
        return {
          text: cell.textContent.trim(),
          overflow: style.overflow,
          textOverflow: style.textOverflow,
          whiteSpace: style.whiteSpace,
          beforeCellWidth: cell.getBoundingClientRect().width,
          beforeScrollWidth: cell.scrollWidth,
          beforeClientWidth: cell.clientWidth,
          cell,
        };
      })
      .filter(Boolean);
    const target = candidates.find(item => item.text.includes('系统管理员'));
    if (!target) return null;

    target.cell.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    const afterCellWidth = target.cell.getBoundingClientRect().width;

    return {
      text: target.text,
      overflow: target.overflow,
      textOverflow: target.textOverflow,
      whiteSpace: target.whiteSpace,
      beforeCellWidth: target.beforeCellWidth,
      afterCellWidth,
      beforeScrollWidth: target.beforeScrollWidth,
      beforeClientWidth: target.beforeClientWidth,
      afterScrollWidth: target.cell.scrollWidth,
      afterClientWidth: target.cell.clientWidth,
    };
  });

  if (!state) {
    throw new Error('采购订单没有找到“系统管理员”创建人单元格');
  }
  if (state.text !== '系统管理员') {
    throw new Error(`创建人未完整显示“系统管理员”: ${JSON.stringify(state)}`);
  }
  if (state.afterScrollWidth > state.afterClientWidth + 1) {
    throw new Error(`创建人完整显示后仍发生水平溢出: ${JSON.stringify(state)}`);
  }
  if (Math.abs(state.beforeCellWidth - state.afterCellWidth) > 1) {
    throw new Error(`创建人单击后宽度发生变化: ${JSON.stringify(state)}`);
  }

  await page.screenshot({ path: path.join(outDir, 'purchase-orders-created-by.png'), fullPage: true });
}

async function expandFirstDetailWithRows(page) {
  const rowCount = await page.locator('[data-stock-bill-id]').count();
  for (let index = 0; index < rowCount; index += 1) {
    const row = page.locator('[data-stock-bill-id]').nth(index);
    const button = row.locator('button').filter({ hasText: /展开明细|收起明细/ }).first();
    if ((await button.textContent()).includes('收起')) {
      await button.click();
      await page.waitForTimeout(250);
    }
    await button.click();
    await page.waitForTimeout(700);
    if ((await page.locator('.stock-bill-detail-row-scroll').count()) > 0) return;
  }
  throw new Error('没有找到包含商品明细的出入库行');
}

async function assertStockBillDetail(page, route, name) {
  await openRoute(page, route);
  await expandFirstDetailWithRows(page);

  const state = await page.locator('.stock-bill-detail-drawer').first().evaluate(drawer => {
    const drawerStyle = getComputedStyle(drawer);
    const mainHead = document.querySelector('.stock-bill-table-scroll [data-slot="table-head"]');
    const heads = [...document.querySelectorAll('.stock-bill-detail-row-scroll [data-slot="table-head"]')];
    const cells = [...document.querySelectorAll('.stock-bill-detail-row-scroll [data-slot="table-cell"]')].slice(0, 24);
    const detailContainer = document.querySelector('.stock-bill-detail-row-scroll [data-slot="table-container"]');
    const detailTable = document.querySelector('.stock-bill-detail-row-scroll [data-slot="table"]');
    const detailHeadStyle = heads[0] ? getComputedStyle(heads[0]) : null;
    const mainHeadStyle = mainHead ? getComputedStyle(mainHead) : null;
    return {
      display: drawerStyle.display,
      dataState: drawer.getAttribute('data-state') || '',
      contentHeightVar: drawerStyle.getPropertyValue('--reka-collapsible-content-height').trim(),
      animationName: drawerStyle.animationName,
      animationDuration: drawerStyle.animationDuration,
      transform: drawerStyle.transform,
      mainHeaderBg: mainHeadStyle?.backgroundColor ?? '',
      detailHeaderBg: detailHeadStyle?.backgroundColor ?? '',
      detailHeaderColor: detailHeadStyle?.color ?? '',
      detailHeaderWeight: detailHeadStyle?.fontWeight ?? '',
      detailContainerHeight: Math.round(detailContainer?.getBoundingClientRect().height ?? 0),
      detailTableHeight: Math.round(detailTable?.getBoundingClientRect().height ?? 0),
      badHeadAlign: heads
        .map((item, index) => ({ index, text: item.textContent.trim(), align: getComputedStyle(item).textAlign }))
        .filter(item => item.align !== 'left'),
      badCellAlign: cells
        .map((item, index) => ({ index, text: item.textContent.trim().slice(0, 30), align: getComputedStyle(item).textAlign }))
        .filter(item => item.align !== 'left'),
    };
  });

  if (state.dataState !== 'open') {
    throw new Error(`${name} 明细抽屉没有进入 Reka Collapsible 展开态: ${JSON.stringify(state)}`);
  }
  if (!/px$/.test(state.contentHeightVar) || Number.parseFloat(state.contentHeightVar) <= 0) {
    throw new Error(`${name} 明细抽屉没有使用组件内容高度变量: ${JSON.stringify(state)}`);
  }
  if (state.transform !== 'none') {
    throw new Error(`${name} 明细抽屉仍在使用 transform，容易和表格布局叠加卡顿: ${JSON.stringify(state)}`);
  }
  if (state.detailTableHeight > 0 && state.detailTableHeight < 260 && state.detailContainerHeight - state.detailTableHeight > 32) {
    throw new Error(`${name} 明细表容器高度远超数据行高度: ${JSON.stringify(state)}`);
  }
  if (state.badHeadAlign.length || state.badCellAlign.length) {
    throw new Error(`${name} 明细表格未左对齐: ${JSON.stringify(state)}`);
  }
  if (!state.detailHeaderBg || state.detailHeaderBg === 'rgba(0, 0, 0, 0)' || state.detailHeaderBg === state.mainHeaderBg) {
    throw new Error(`${name} 明细表头未和主表表头做视觉区分: ${JSON.stringify(state)}`);
  }
  if (Number.parseInt(state.detailHeaderWeight, 10) < 600) {
    throw new Error(`${name} 明细表头字重不足，层级不清晰: ${JSON.stringify(state)}`);
  }

  await page.screenshot({ path: path.join(outDir, `${name}-detail-left-align.png`), fullPage: true });

  await page.locator('button').filter({ hasText: '收起明细' }).first().click();
  await page.waitForTimeout(50);
  const leaveState = await page.evaluate(() => {
    const drawer = document.querySelector('.stock-bill-detail-drawer[data-state="closed"]');
    const drawerStyle = drawer ? getComputedStyle(drawer) : null;
    return {
      hasDrawer: Boolean(drawer),
      animationName: drawerStyle?.animationName ?? '',
      animationDuration: drawerStyle?.animationDuration ?? '',
      contentHeightVar: drawerStyle?.getPropertyValue('--reka-collapsible-content-height').trim() ?? '',
      transform: drawerStyle?.transform ?? '',
    };
  });

  if (!leaveState.hasDrawer) {
    throw new Error(`${name} 收起明细没有进入 Reka Collapsible 关闭态: ${JSON.stringify(leaveState)}`);
  }
  if (!leaveState.animationDuration.split(',').some(item => Number.parseFloat(item) > 0)) {
    throw new Error(`${name} 收起明细抽屉没有组件动画时间: ${JSON.stringify(leaveState)}`);
  }
  if (leaveState.transform !== 'none') {
    throw new Error(`${name} 收起明细仍在使用 transform，容易和表格布局叠加卡顿: ${JSON.stringify(leaveState)}`);
  }
  await page.locator('.stock-bill-detail-row-scroll').first().waitFor({ state: 'hidden', timeout: 1000 });
}

async function findSourceGeneratedPendingRow(page) {
  const rowCount = await page.locator('[data-stock-bill-id]').count();
  for (let index = 0; index < rowCount; index += 1) {
    const row = page.locator('[data-stock-bill-id]').nth(index);
    const text = await row.textContent();
    if (text && text.includes('待确认') && text.includes('来源生成')) return row;
  }
  return null;
}

async function assertSourceGeneratedPendingStartsAtZero(page, route, name) {
  await openRoute(page, route);
  const row = await findSourceGeneratedPendingRow(page);
  if (!row) throw new Error(`${name} 没有找到来源生成的待确认单`);

  const expandButton = row.locator('button').filter({ hasText: /展开明细|收起明细/ }).first();
  if ((await expandButton.textContent()).includes('收起')) {
    await expandButton.click();
    await page.waitForTimeout(250);
  }
  await expandButton.click();
  await page.waitForTimeout(700);

  const quantityState = await page.locator('.stock-bill-detail-row-scroll [data-stock-bill-expanded-item-id]').first().evaluate(itemRow => {
    const cells = itemRow.querySelectorAll('[data-slot="table-cell"]');
    return {
      productCode: cells[0]?.textContent.trim() || '',
      quantityText: cells[3]?.textContent.trim() || '',
      remainingText: cells[6]?.textContent.trim() || '',
    };
  });

  if (!/^0(\.0+)?\s*\S+/.test(quantityState.quantityText)) {
    throw new Error(`${name} 来源生成待确认单的本次数量不是 0: ${JSON.stringify(quantityState)}`);
  }
  if (/^0(\.0+)?\s*\S+/.test(quantityState.remainingText)) {
    throw new Error(`${name} 来源生成待确认单的剩余数量不应随初始本次数量归零: ${JSON.stringify(quantityState)}`);
  }

  const actionText = route.includes('inbound') ? '确认入库' : '确认出库';
  await row.locator('button').filter({ hasText: actionText }).first().click();
  const dialog = page.locator('[role="dialog"]').last();
  await dialog.waitFor({ timeout: 10000 });
  await dialog.locator('button').filter({ hasText: actionText }).last().click();
  await page.getByText(/请先填写本次(入库|出库)数量/).first().waitFor({ timeout: 5000 });
  await dialog.locator('button').filter({ hasText: '关闭' }).first().click();
}

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  try {
    await assertPurchaseCreatedByFullText(page);
    await assertStockBillDetail(page, '/warehouse/inbound-bills', 'warehouse-inbound-bills');
    await assertStockBillDetail(page, '/warehouse/outbound-bills', 'warehouse-outbound-bills');
    await assertSourceGeneratedPendingStartsAtZero(page, '/warehouse/inbound-bills', 'warehouse-inbound-bills');
    await assertSourceGeneratedPendingStartsAtZero(page, '/warehouse/outbound-bills', 'warehouse-outbound-bills');
    console.log(`BROWSER_QA_OK ${outDir}`);
  } finally {
    await browser.close();
  }
}

run().catch(error => {
  console.error(error);
  process.exit(1);
});
