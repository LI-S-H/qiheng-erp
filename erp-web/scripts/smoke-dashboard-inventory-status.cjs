const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/dashboard-states');

async function waitForDashboardSettled(page) {
  await page.locator('[data-dashboard-refresh-status]').getByText(/更新于/).waitFor();
  // 刷新时间写入后，库存卡片请求仍可能处于同一轮全局加载计数中。
  // 等待最短遮罩时长结束，再截取实际界面而非过渡中的加载遮罩。
  await page.waitForTimeout(350);
  await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
}

const allowedPayload = {
  code: 0,
  message: 'success',
  data: {
    distribution: [
      { status: 'NORMAL', recordCount: 4 },
      { status: 'LOW_STOCK', recordCount: 1 },
      { status: 'NO_AVAILABLE', recordCount: 0 },
      { status: 'OUT_OF_STOCK', recordCount: 0 },
    ],
    riskPreview: {
      items: [{
        stockId: '1940000000000000016',
        productCode: 'P000012',
        productName: '东北长粒香大米',
        warehouseName: '南京备货仓',
        unitName: 'kg',
        quantityPrecision: 2,
        availableQty: 12.5,
        safetyStockQty: 15.5,
        severity: 'LOW_STOCK',
      }],
      hasMore: false,
    },
    access: { state: 'ALLOWED' },
  },
};

const deniedPayload = {
  code: 0,
  message: 'success',
  data: {
    distribution: [],
    riskPreview: { items: [], hasMore: false },
    access: { state: 'DENIED' },
  },
};

const emptyPayload = {
  code: 0,
  message: 'success',
  data: {
    distribution: [
      { status: 'NORMAL', recordCount: 0 },
      { status: 'LOW_STOCK', recordCount: 0 },
      { status: 'NO_AVAILABLE', recordCount: 0 },
      { status: 'OUT_OF_STOCK', recordCount: 0 },
    ],
    riskPreview: { items: [], hasMore: false },
    access: { state: 'EMPTY' },
  },
};

function mockInventoryStatus(payload) {
  return async page => {
    await page.route('**/api/dashboard/inventory-status**', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    }));
  };
}

runSmoke({
  route: '/dashboard',
  screenshot: 'smoke-dashboard-inventory-status.png',
  setupPage: mockInventoryStatus(allowedPayload),
  async test(page) {
    const panel = page.locator('.inventory-status-panel');
    await panel.waitFor();
    await waitForDashboardSettled(page);
    await panel.getByText('12.50 kg', { exact: true }).waitFor();
    await panel.getByText('15.50 kg', { exact: true }).waitFor();
    if (await panel.getByText('12.5 kg', { exact: true }).count()) {
      throw new Error('精度为 2 的风险库存不应省略末尾小数位');
    }
    for (const nameSelector of ['[data-dashboard-inventory-product-name]', '[data-dashboard-inventory-warehouse-name]']) {
      const name = panel.locator(nameSelector);
      await name.evaluate(element => {
        element.style.width = '36px';
        element.style.maxWidth = '36px';
        window.dispatchEvent(new Event('resize'));
      });
      await page.waitForFunction(selector => document.querySelector(selector)?.getAttribute('data-overflowing') === 'true', nameSelector);
      await name.hover();
      if (await name.getAttribute('title') !== null || await name.getAttribute('data-overflowing') !== 'true') {
        throw new Error(`工作台库存长文本未使用统一 OverflowTooltip：${nameSelector}`);
      }
    }
  },
}).then(() => runSmoke({
  route: '/dashboard',
  screenshot: 'smoke-dashboard-inventory-status-denied.png',
  setupPage: mockInventoryStatus(deniedPayload),
  async test(page) {
    const panel = page.locator('.inventory-status-panel');
    await waitForDashboardSettled(page);
    await panel.getByText('您暂无库存查看权限', { exact: true }).waitFor();
    if (await panel.locator('.inventory-status-panel__donut').count()
      || await panel.getByText('风险仓储商品', { exact: true }).count()) {
      throw new Error('无库存权限时必须完整替换饼图和风险商品区域');
    }
    if (await panel.locator('.dashboard-empty-panel--stacked').count()) {
      throw new Error('库存权限提示必须保持横向布局');
    }
    if (await page.getByText('库存状态加载失败', { exact: true }).count()) {
      throw new Error('无仓储权限应降级为权限提示，不能显示接口加载失败');
    }
    await panel.screenshot({ path: path.join(screenshotDirectory, 'inventory-denied-panel.png') });
  },
})).then(() => runSmoke({
  route: '/dashboard',
  viewport: { width: 1115, height: 838 },
  screenshot: 'smoke-dashboard-inventory-status-empty.png',
  setupPage: mockInventoryStatus(emptyPayload),
  async test(page) {
    const panel = page.locator('.inventory-status-panel');
    await waitForDashboardSettled(page);
    const state = panel.locator('[data-dashboard-inventory-state]');
    await state.getByText('暂无库存记录', { exact: true }).waitFor();
    if (await panel.locator('.inventory-status-panel__donut').count()
      || await panel.getByText('风险仓储商品', { exact: true }).count()) {
      throw new Error('库存为空时必须完整替换饼图和风险商品区域');
    }
    if (await panel.locator('.dashboard-empty-panel--stacked').count()) {
      throw new Error('库存空态必须保持横向布局');
    }
    const layout = await state.evaluate(element => ({
      height: element.getBoundingClientRect().height,
      width: element.getBoundingClientRect().width,
    }));
    if (layout.height < 360 || layout.width < 600) {
      throw new Error(`库存空态未覆盖原内容区：${JSON.stringify(layout)}`);
    }
    await panel.screenshot({ path: path.join(screenshotDirectory, 'inventory-empty-panel.png') });
  },
})).then(() => {
  console.log('SMOKE_OK: 风险预览按产品精度显示，无仓储权限正常降级');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
