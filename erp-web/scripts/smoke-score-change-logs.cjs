const fs = require('fs');
const path = require('path');

process.env.SMOKE_BASE_URL = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5397';
process.env.VITE_USE_MOCK_AUTH = 'true';
process.env.VITE_USE_MOCK_API = 'true';

const { runSmoke } = require('./smoke-helpers.cjs');
const screenshotDirectory = path.resolve(__dirname, '..', '..', 'docs', 'qa-screenshots', `${new Date().toISOString().replace(/[:.]/g, '-')}-score-log-sources`);
fs.mkdirSync(screenshotDirectory, { recursive: true });

async function openLogDialog(page, row) {
  await row.locator('[data-row-actions-trigger]').click();
  await page.getByRole('menuitem', { name: '评分变更记录', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '评分变更记录' });
  await dialog.waitFor();
  if (await page.getByRole('dialog').count() !== 1) throw new Error('评分记录打开时出现嵌套弹窗');
  return dialog;
}

runSmoke({
  route: '/purchase/suppliers',
  screenshot: path.join(screenshotDirectory, 'supplier-1440x900-list.png'),
  async test(page) {
    const supplierRow = page.getByRole('row').filter({ hasText: 'S001' }).first();
    await supplierRow.waitFor();
    const dialog = await openLogDialog(page, supplierRow);
    await dialog.getByText('供应商：华东饮品供应链').first().waitFor();
    await dialog.getByText('MOCK-SERVICE-0001').waitFor();
    const userLog = dialog.getByRole('row').filter({ hasText: 'MOCK-SERVICE-0001' });
    await userLog.locator('[data-score-log-sources] summary').click();
    await userLog.getByText('供应商：S001', { exact: true }).waitFor();
    await userLog.getByText('操作人：系统管理员', { exact: true }).waitFor();
    if (await dialog.getByText('MOCK-SERVICE-0002').count()) throw new Error('供应商范围筛选未生效');
    await dialog.locator('[aria-busy="true"]').waitFor({ state: 'hidden' });
    await dialog.getByText('评分记录查询中').waitFor({ state: 'hidden' });
    await page.screenshot({ path: path.join(screenshotDirectory, 'supplier-1440x900-dialog.png') });

    await dialog.getByRole('group', { name: '指标类型' }).getByRole('combobox').click();
    await page.locator('[data-anchored-select-content]').getByText('价格分', { exact: true }).click();
    await dialog.getByRole('button', { name: '查询', exact: true }).click();
    await dialog.getByText('暂无符合条件的评分变更记录').waitFor();
    await dialog.getByRole('button', { name: '重置', exact: true }).click();
    await dialog.getByText('MOCK-SERVICE-0001').waitFor();
    await dialog.getByText('MOCK-SERVICE-0002').waitFor();
    const mergedLog = dialog.getByRole('row').filter({ hasText: 'MOCK-MERGED-0003' });
    await mergedLog.getByText('衍生分校正', { exact: true }).waitFor();
    await mergedLog.getByText('基础指标未变化', { exact: true }).waitFor();
    await mergedLog.getByText('系统：完全入库重算', { exact: true }).waitFor();
    const sourceList = mergedLog.locator('[data-score-log-sources]');
    const sourceTitle = await sourceList.locator('summary').getAttribute('title');
    if (!sourceTitle?.includes('PO-MOCK-11') || !sourceTitle.includes('9007199254741004')) throw new Error('多单来源标题被截断或大整数 ID 丢失精度');
    await sourceList.locator('summary').click();
    if (await sourceList.locator('div[title]').count() !== 12) throw new Error('多单来源没有完整保留 12 条');
    await sourceList.getByText('采购单：ID 9007199254741004', { exact: true }).scrollIntoViewIfNeeded();
    await sourceList.getByText('采购单：ID 9007199254741004', { exact: true }).waitFor();
    const dailyLog = dialog.getByRole('row').filter({ hasText: 'MOCK-DAILY-0004' });
    await dailyLog.getByText('无业务来源', { exact: true }).waitFor();
    await dailyLog.getByText('系统：每日事实校正', { exact: true }).waitFor();
    if (await dialog.getByRole('button', { name: '清除供应商筛选' }).count()) throw new Error('重置后仍保留供应商范围');

    await dialog.getByRole('group', { name: '供应商' }).getByRole('combobox').click();
    await page.locator('[data-remote-search-select-content]').getByRole('button', { name: '晨岛咖啡贸易 · S002' }).click();
    await dialog.getByText('MOCK-SERVICE-0001').waitFor({ state: 'hidden' });
    await dialog.getByText('MOCK-SERVICE-0002').waitFor();

    await dialog.getByRole('group', { name: '供货产品' }).getByRole('combobox').click();
    await page.locator('[data-remote-search-select-content] [data-select-option]').filter({ hasText: '晨岛咖啡贸易' }).first().click();
    await dialog.getByText('暂无符合条件的评分变更记录').waitFor();
    await dialog.getByRole('button', { name: '清除供货产品筛选' }).click();
    await dialog.getByText('MOCK-SERVICE-0002').waitFor();
    await dialog.getByRole('button', { name: '重置', exact: true }).click();
    await dialog.getByText('MOCK-SERVICE-0001').waitFor();
    await dialog.getByRole('button', { name: '关闭', exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });

    await page.goto(`${process.env.SMOKE_BASE_URL}/purchase/supplier-products`, { waitUntil: 'domcontentloaded' });
    const productRow = page.getByRole('row').filter({ hasText: '华东饮品供应链' }).first();
    await productRow.waitFor();
    const productDialog = await openLogDialog(page, productRow);
    await productDialog.getByRole('button', { name: '清除供应商筛选' }).waitFor();
    await productDialog.getByRole('button', { name: '清除供货产品筛选' }).waitFor();
    await productDialog.getByText('暂无符合条件的评分变更记录').waitFor();
    await productDialog.getByRole('button', { name: '清除供货产品筛选' }).click();
    await productDialog.getByText('MOCK-SERVICE-0001').waitFor();
    if (await productDialog.getByText('MOCK-SERVICE-0002').count()) throw new Error('清除产品后供应商筛选未保留');
    await productDialog.getByRole('button', { name: '重置', exact: true }).click();
    await productDialog.getByText('MOCK-SERVICE-0002').waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    const layout = await productDialog.evaluate(element => {
      const rect = element.getBoundingClientRect();
      const close = [...element.querySelectorAll('button')].find(button => button.textContent.trim() === '关闭');
      const closeRect = close?.getBoundingClientRect();
      const table = element.querySelector('[data-slot="table-container"]');
      return {
        dialogTop: rect.top,
        dialogBottom: rect.bottom,
        closeBottom: closeRect?.bottom,
        horizontalOverflow: table ? table.scrollWidth - table.clientWidth : 0,
      };
    });
    if (layout.dialogTop < 0 || layout.dialogBottom > 838 || !layout.closeBottom || layout.closeBottom > 838 || layout.horizontalOverflow <= 0) {
      throw new Error(`中等视口评分记录弹窗布局异常：${JSON.stringify(layout)}`);
    }
    await productDialog.locator('[aria-busy="true"]').waitFor({ state: 'hidden' });
    await productDialog.getByText('评分记录查询中').waitFor({ state: 'hidden' });
    await page.screenshot({ path: path.join(screenshotDirectory, 'supplier-product-1115x838-dialog.png') });
    await productDialog.getByRole('button', { name: '关闭', exact: true }).click();
    await productDialog.waitFor({ state: 'hidden' });

    // 产品没有版本号字段，参考价独立入口只提交价格，不伪造版本号。
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${process.env.SMOKE_BASE_URL}/product/products`, { waitUntil: 'domcontentloaded' });
    const referenceRow = page.getByRole('row').filter({ hasText: 'P000001' }).first();
    await referenceRow.waitFor();
    await referenceRow.locator('[data-row-actions-trigger]').click();
    await page.getByRole('menuitem', { name: '调整参考采购价', exact: true }).click();
    const referenceDialog = page.getByRole('dialog', { name: '调整参考采购价' });
    await referenceDialog.waitFor();
    await referenceDialog.locator('input[type="number"]').fill('-1');
    await referenceDialog.getByRole('button', { name: '确认调整', exact: true }).click();
    await referenceDialog.getByText('参考采购价不能小于 0', { exact: true }).waitFor();
    await referenceDialog.locator('input[type="number"]').fill('37.25');
    await referenceDialog.getByRole('button', { name: '确认调整', exact: true }).click();
    await referenceDialog.waitFor({ state: 'hidden' });
    await referenceRow.getByText('采 ¥37.25', { exact: true }).waitFor();
    console.log('SCORE_CHANGE_LOG_SMOKE_OK');
  },
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
