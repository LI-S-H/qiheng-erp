const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/dashboard-states');

async function waitForDashboardSettled(page) {
  await page.locator('[data-dashboard-refresh-status]').getByText(/更新于/).waitFor();
  // 刷新时间先于部分卡片请求完成；避免截图撞上全局加载遮罩的淡出阶段。
  await page.waitForTimeout(350);
  await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
}

const inventoryDeniedPayload = {
  code: 0,
  message: 'success',
  data: {
    distribution: [],
    riskPreview: { items: [], hasMore: false },
    access: { state: 'DENIED' },
  },
};

function mockOverviewState(states, trendPermissions, inventoryPayload, denyMetrics = false) {
  return async page => {
    // Mock API 不发送 HTTP，必须在真实模块返回点注入夹具，不能用默认数据冒充状态回归。
    await page.route('**/src/modules/dashboard/api.ts*', async route => {
      const response = await route.fetch();
      let source = await response.text();
      const overviewReturn = 'return normalizeOverview(mockOverview);';
      if (!source.includes(overviewReturn)) throw new Error('工作台状态 Mock 注入入口不存在');
      source = source.replace(overviewReturn, `
        const stateData = structuredClone(mockOverview);
        Object.entries(${JSON.stringify(states)}).forEach(([key, state]) => { stateData.access[key] = { state }; });
        Object.assign(stateData, { pendingCount: 0, todos: [], orderStages: [], topProducts: [], supplierPerformance: [], trend: [], trendPermissions: ${JSON.stringify(trendPermissions)} });
        if (${denyMetrics}) stateData.metrics.forEach(metric => {
          Object.assign(metric, { value: null, changeRate: null, compareText: null, comparisonState: null });
          stateData.access.metrics[metric.key] = { state: 'DENIED' };
        });
        window.__dashboardStateFixtureApplied = true;
        return normalizeOverview(stateData);`);
      if (inventoryPayload) {
        const inventoryReturn = 'return normalizeInventoryStatus(mockInventoryStatus);';
        if (!source.includes(inventoryReturn)) throw new Error('库存状态 Mock 注入入口不存在');
        source = source.replace(inventoryReturn, `return normalizeInventoryStatus(${JSON.stringify(inventoryPayload.data)});`);
      }
      await route.fulfill({ response, body: source });
    });
    await page.route('**/api/dashboard/overview', async route => {
      const response = await route.fetch();
      const payload = await response.json();
      const data = payload.data;
      Object.entries(states).forEach(([key, state]) => {
        data.access[key] = { state };
      });
      data.todos = [];
      data.orderStages = [];
      data.topProducts = [];
      data.supplierPerformance = [];
      data.trend = [];
      data.pendingCount = 0;
      data.trendPermissions = trendPermissions;
      if (denyMetrics) {
        data.metrics.forEach(metric => {
          metric.value = null;
          metric.changeRate = null;
          metric.compareText = null;
          metric.comparisonState = null;
          data.access.metrics[metric.key] = { state: 'DENIED' };
        });
      }
      await page.evaluate(() => { window.__dashboardStateFixtureApplied = true; });
      await route.fulfill({ response, body: JSON.stringify(payload) });
    });
    if (inventoryPayload) {
      await page.route('**/api/dashboard/inventory-status**', route => route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(inventoryPayload),
      }));
    }
  };
}

async function assertStatePanels(page, expected) {
  if (!await page.evaluate(() => window.__dashboardStateFixtureApplied === true)) {
    throw new Error('工作台状态夹具未应用，不能以默认数据证明 EMPTY / DENIED');
  }
  const expectations = [
    ['[data-dashboard-trend-empty]', expected.trend],
    ['[data-dashboard-todos-state]', expected.todos],
    ['[data-dashboard-order-empty]', expected.orders],
    ['[data-dashboard-top-products-state]', expected.products],
    ['[data-dashboard-supplier-performance-state]', expected.suppliers],
  ];
  for (const [selector, text] of expectations) {
    const panel = page.locator(selector);
    await panel.getByText(text, { exact: true }).waitFor();
    const stateLayout = await panel.locator('.dashboard-empty-panel').evaluate(element => ({
      height: element.parentElement?.getBoundingClientRect().height ?? 0,
      titleSize: Number.parseFloat(getComputedStyle(element.querySelector('strong')).fontSize),
      descriptionSize: Number.parseFloat(getComputedStyle(element.querySelector('p')).fontSize),
      iconSize: Math.round(element.querySelector('.dashboard-empty-panel__icon').getBoundingClientRect().width),
      contentTop: element.querySelector('.dashboard-empty-panel__content').getBoundingClientRect().top,
      contentHeight: element.querySelector('.dashboard-empty-panel__content').getBoundingClientRect().height,
      panelTop: element.getBoundingClientRect().top,
      panelHeight: element.getBoundingClientRect().height,
      layout: element.classList.contains('dashboard-empty-panel--stacked'),
      textAlign: getComputedStyle(element.querySelector('.dashboard-empty-panel__body')).textAlign,
    }));
    if (stateLayout.height < 180) throw new Error(`${selector} 状态层高度不足：${stateLayout.height}`);
    if (stateLayout.titleSize < 16 || stateLayout.descriptionSize < 14 || stateLayout.iconSize < 40) {
      throw new Error(`${selector} 状态提示字号或图标尺寸不足：${JSON.stringify(stateLayout)}`);
    }
    const contentCenter = stateLayout.contentTop + stateLayout.contentHeight / 2;
    const panelCenter = stateLayout.panelTop + stateLayout.panelHeight / 2;
    if (!stateLayout.layout || stateLayout.textAlign !== 'center'
      || contentCenter > panelCenter - 12 || contentCenter < panelCenter - 40) {
      throw new Error(`${selector} 权限提示未采用居中纵向布局或未上移：${JSON.stringify(stateLayout)}`);
    }
  }
  for (const buttonName of ['查看详情业务待办', '查看详情销售商品排行', '查看详情供应商履约']) {
    if (await page.getByRole('button', { name: buttonName }).count()) {
      throw new Error(`非 ALLOWED 状态不应保留详情入口：${buttonName}`);
    }
  }
}

runSmoke({
  route: '/dashboard',
  viewport: { width: 1440, height: 1800 },
  screenshot: path.join(screenshotDirectory, 'dashboard-denied-1440x1800.png'),
  setupPage: mockOverviewState({
    todos: 'DENIED', orderStages: 'DENIED', topProducts: 'DENIED', supplierPerformance: 'DENIED',
  }, { canViewSales: false, canViewPurchase: false, canViewGross: false }, inventoryDeniedPayload, true),
  async test(page) {
    await page.getByRole('heading', { name: '工作台' }).waitFor();
    await waitForDashboardSettled(page);
    await assertStatePanels(page, {
      trend: '您暂无查看权限', todos: '您暂无查看权限', orders: '您暂无查看权限', products: '您暂无查看权限', suppliers: '您暂无查看权限',
    });
    if (await page.locator('.dashboard-trend-chart, .dashboard-todo, .dashboard-stage, .dashboard-rank, .dashboard-supplier').count()) {
      throw new Error('无权限时不应保留经营趋势、待办、流转或排行内容');
    }
    if (await page.locator('.dashboard-metric strong').filter({ hasText: '无权限' }).count() !== 4) {
      throw new Error('无权限场景的四项经营指标必须显示无权限');
    }
    const inventoryPanel = page.locator('.inventory-status-panel');
    await inventoryPanel.getByText('您暂无库存查看权限', { exact: true }).waitFor();
    if (await inventoryPanel.locator('.inventory-status-panel__donut').count()
      || await inventoryPanel.getByText('风险仓储商品', { exact: true }).count()) {
      throw new Error('无库存权限时不应保留库存饼图或风险商品区域');
    }
  },
}).then(() => runSmoke({
  route: '/dashboard',
  viewport: { width: 1115, height: 838 },
  screenshot: path.join(screenshotDirectory, 'dashboard-empty-1115x838.png'),
  setupPage: mockOverviewState({
    todos: 'EMPTY', orderStages: 'EMPTY', topProducts: 'EMPTY', supplierPerformance: 'EMPTY',
  }, { canViewSales: true, canViewPurchase: true, canViewGross: true }),
  async test(page) {
    await page.getByRole('heading', { name: '工作台' }).waitFor();
    await waitForDashboardSettled(page);
    await assertStatePanels(page, {
      trend: '暂无经营趋势数据', todos: '暂无任务待办', orders: '暂无订单流转数据', products: '暂无销售商品排行', suppliers: '暂无供应商履约数据',
    });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1115px 状态卡片发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 工作台 EMPTY / DENIED 状态层覆盖内容区且详情入口已隐藏');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
