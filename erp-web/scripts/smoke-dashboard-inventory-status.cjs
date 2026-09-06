const { runSmoke } = require('./smoke-helpers.cjs');

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
    await panel.getByText('12.50 kg', { exact: true }).waitFor();
    await panel.getByText('15.50 kg', { exact: true }).waitFor();
    if (await panel.getByText('12.5 kg', { exact: true }).count()) {
      throw new Error('精度为 2 的风险库存不应省略末尾小数位');
    }
  },
}).then(() => runSmoke({
  route: '/dashboard',
  screenshot: 'smoke-dashboard-inventory-status-denied.png',
  setupPage: mockInventoryStatus(deniedPayload),
  async test(page) {
    const panel = page.locator('.inventory-status-panel');
    await panel.getByText('您暂无库存查看权限', { exact: true }).waitFor();
    if (await page.getByText('库存状态加载失败', { exact: true }).count()) {
      throw new Error('无仓储权限应降级为权限提示，不能显示接口加载失败');
    }
  },
})).then(() => {
  console.log('SMOKE_OK: 风险预览按产品精度显示，无仓储权限正常降级');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});