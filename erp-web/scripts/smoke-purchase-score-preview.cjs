const fs = require('fs');
const path = require('path');
const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/purchase-score-preview');
fs.mkdirSync(screenshotDirectory, { recursive: true });

// 在 Vite 模块响应中注入测试故障，运行真实页面与真实 handler；不修改产品 Mock 行为。
async function setupScenario(page) {
  await page.route('**/src/modules/purchase/api.ts*', async route => {
    const response = await route.fetch();
    let source = await response.text();
    const signature = /export function getSupplierProductDetail\([^)]*\)\s*\{/;
    if (!signature.test(source)) throw new Error('评分预览函数注入入口不存在');
    source = source.replace(signature, match => `${match}
      window.__previewCalls ??= [];
      window.__previewCalls.push(supplierProductId);
      const scenario = window.__previewScenario;
      if (scenario === '404' && window.__previewCalls.length === 1) return Promise.reject(new Error('供货关系不存在'));
      if (scenario === 'timeout' && window.__previewCalls.length === 1) return new Promise((_, reject) => setTimeout(() => reject(new Error('请求超时')), 1800));
      if (scenario === 'zero-null') {
        const product = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
        return Promise.resolve(normalizeSupplierProduct({ ...product, scoreStatus: window.__previewCalls.length === 1 ? 'READY' : 'NOT_READY', aiScore: window.__previewCalls.length === 1 ? 0 : null }));
      }
      if (scenario === 'delayed') return new Promise(resolve => setTimeout(() => resolve(normalizeSupplierProduct({ ...mockSupplierProducts.find(item => item.supplierProductId === supplierProductId), scoreStatus: 'READY', aiScore: 17 })), 1200));
    `);
    const orderSignature = /export function getPurchaseOrderDetail\([^)]*\)\s*\{/;
    source = source.replace(orderSignature, match => `${match}
      if (window.__previewScenario === 'duplicate') {
        const order = structuredClone(mockOrders.find(item => item.purchaseOrderId === purchaseOrderId));
        order.items = [order.items[0], { ...order.items[0], purchaseOrderItemId: '1999999999999999901' }];
        return Promise.resolve(normalizeOrderDetail(order));
      }
    `);
    await route.fulfill({ response, body: source });
  });
}

async function openEditor(page, scenario, purchaseOnly = false) {
  await tableRow(page, 'PO-20260700-5').waitFor();
  await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
  await page.evaluate(async ({ scenario, purchaseOnly }) => {
    window.__previewScenario = scenario;
    window.__previewCalls = [];
    const { useAuthStore } = await import('/src/modules/auth/stores/authStore.ts');
    if (purchaseOnly) {
      useAuthStore().user.isAdmin = false;
      useAuthStore().user.permissionCodes = ['purchase:query', 'purchase:create'];
    }
  }, { scenario, purchaseOnly });
  const row = tableRow(page, 'PO-20260700-5');
  await row.getByRole('button', { name: '处理', exact: true }).click();
  await page.getByRole('dialog', { name: '采购单详情' }).getByRole('button', { name: '编辑', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '编辑采购单' });
  await dialog.waitFor({ state: 'visible', timeout: 1000 });
  return dialog;
}

async function runCase(name, test) {
  await runSmoke({ route: '/purchase/orders', screenshot: path.join(screenshotDirectory, `${name}.png`), setupPage: setupScenario, test });
}

(async () => {
  await runCase('purchase-only', async page => {
    const dialog = await openEditor(page, 'normal', true);
    if (await page.evaluate(() => window.__previewCalls.length) !== 0) throw new Error('无supplier:query权限仍请求供货评分');
    const scores = await dialog.locator('[data-purchase-form-items] tbody tr td:nth-child(4)').allTextContents();
    if (!scores.length || scores.some(score => score.trim() !== '—')) throw new Error(`采购-only显示旧分数：${scores}`);
    if (await dialog.getByRole('button', { name: '保存修改' }).isDisabled()) throw new Error('采购-only编辑被评分权限阻塞');
  });
  for (const failure of ['404', 'timeout']) {
    await runCase(failure, async page => {
      const dialog = await openEditor(page, failure);
      await page.getByText('部分当前推荐分暂不可用，不影响编辑；审核时由后端冻结评分', { exact: true }).waitFor();
      const scores = await dialog.locator('[data-purchase-form-items] tbody tr td:nth-child(4)').allTextContents();
      if (scores[0].trim() !== '—') throw new Error(`失败关系仍显示旧评分：${scores}`);
      if (await dialog.getByRole('button', { name: '保存修改' }).isDisabled()) throw new Error('评分失败阻塞编辑');
    });
  }
  await runCase('duplicate', async page => {
    const dialog = await openEditor(page, 'duplicate');
    await page.waitForFunction(() => window.__previewCalls.length > 0);
    const calls = await page.evaluate(() => window.__previewCalls);
    if (calls.length !== 1) throw new Error(`重复关系重复补查：${JSON.stringify(calls)}`);
    if (await dialog.locator('[data-purchase-form-items] tbody tr').count() !== 2) throw new Error('重复关系测试未真正加载两行');
  });
  await runCase('zero-null', async page => {
    const dialog = await openEditor(page, 'zero-null');
    await dialog.locator('[data-purchase-form-items] tbody tr td:nth-child(4)').first().getByText('0.0', { exact: true }).waitFor();
    const scores = await dialog.locator('[data-purchase-form-items] tbody tr td:nth-child(4)').allTextContents();
    if (scores[1]?.trim() !== '—') throw new Error(`null评分被假填0：${scores}`);
  });
  await runCase('closed-old-request', async page => {
    const dialog = await openEditor(page, 'delayed');
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
    const next = await openEditor(page, 'zero-null');
    await page.waitForTimeout(1500);
    const scores = await next.locator('[data-purchase-form-items] tbody tr td:nth-child(4)').allTextContents();
    if (scores[0]?.trim() !== '0.0' || scores[1]?.trim() !== '—') throw new Error(`关闭后的旧评分污染新表单：${scores}`);
  });
  console.log('SMOKE_OK: 采购评分预览权限、故障降级、去重、null/零及旧请求隔离通过');
})().catch(error => { console.error(error); process.exitCode = 1; });
