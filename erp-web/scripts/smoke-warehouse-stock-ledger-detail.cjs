const { runSmoke } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-warehouse-stock-ledger-detail.png',
  async test(page) {
    const globalLoading = page.locator('[data-page-loading]');
    const rows = page.locator('[data-stock-ledger-id]');
    await rows.first().waitFor();
    await globalLoading.waitFor({ state: 'hidden', timeout: 5000 });

    await page.route('**/api/warehouse/stock-bills/*', async route => {
      const response = await route.fetch();
      await new Promise(resolve => setTimeout(resolve, 180));
      await route.fulfill({ response, body: await response.body() });
    });

    const row = rows.first();
    const stockLedgerId = await row.getAttribute('data-stock-ledger-id');
    await row.getByRole('button', { name: /展开.*明细/ }).click();
    await page.locator(`[data-stock-ledger-detail-loading-id="${stockLedgerId}"]`).waitFor();

    if (await globalLoading.isVisible()) {
      throw new Error('展开库存流水明细不应触发全局页面遮罩');
    }

    const detailError = page.locator(`[data-stock-ledger-detail-error-id="${stockLedgerId}"]`);
    const detailLoading = page.locator(`[data-stock-ledger-detail-loading-id="${stockLedgerId}"]`);
    await detailLoading.waitFor({ state: 'detached', timeout: 5000 });
    if (await detailError.count()) {
      throw new Error(`库存流水详情字段契约错误：${await detailError.innerText()}`);
    }

    const detailItems = page.locator('[data-stock-ledger-expanded-item-id]');
    const emptyState = page.getByText('暂无变动明细');
    if ((await detailItems.count()) === 0 && (await emptyState.count()) === 0) {
      throw new Error('明细展开后既未显示条目，也未显示空状态');
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 库存流水详情包含 itemCount，展开仅使用行内加载状态');
});