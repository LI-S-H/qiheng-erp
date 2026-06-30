const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  clickQueryAndAssertLoading,
  clickPaginationAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

async function selectRemoteFilter(page, index, keyword, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.getByPlaceholder('输入仓库编码或名称').fill(keyword);
  await content.getByText(label, { exact: true }).click();
}

runSmoke({
  route: '/warehouse/stocks',
  screenshot: 'smoke-warehouse-stocks.png',
  async test(page) {
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
    await assertFixedTableLayout(page, 10);

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['本页仓库\n4', '本页产品\n9', '本页低库存\n4', '本页已锁定\n7']) {
      if (!summaryText.includes(expected)) throw new Error(`库存摘要不正确：缺少 ${expected}`);
    }
    const paginationText = await page.locator('[data-table-pagination]').innerText();
    if (!paginationText.includes('不统计总数') || paginationText.includes('共 15 条')) {
      throw new Error(`库存分页应使用无总数模式，当前为：${paginationText}`);
    }
    await clickRefreshAndAssertLoading(page, 'smoke-warehouse-stocks-refresh-loading.png');

    await page.getByPlaceholder('请输入产品名称').fill('复印纸');
    await clickQueryAndAssertLoading(page, 'smoke-warehouse-stocks-query-loading.png');
    await tableRow(page, 'P000007').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page, 'smoke-warehouse-stocks-reset-loading.png');

    await selectRemoteFilter(page, 0, 'WH002', 'WH002 华南中心仓');
    await clickQueryAndAssertLoading(page);
    const warehouseRows = page.locator('tbody tr');
    if (await warehouseRows.count() !== 3) throw new Error('仓库筛选未返回预期的 3 条库存记录');
    for (const text of await warehouseRows.allInnerTexts()) {
      if (!text.includes('WH002')) throw new Error(`仓库筛选混入其他仓库：${text}`);
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '低库存');
    await selectFilter(page, 2, '部分锁定');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'P000002').waitFor();
    await tableRow(page, 'P000009').waitFor({ state: 'detached' });
    await tableRow(page, 'P000003').waitFor({ state: 'detached' });
    const lowRows = page.locator('tbody tr');
    if (await lowRows.count() !== 4) throw new Error('库存健康与占用情况未按独立维度执行 AND 组合');
    await clickResetAndAssertLoading(page);

    await page.getByPlaceholder('如 P000001').fill('P000014');
    await clickQueryAndAssertLoading(page);
    const lockedOutRow = tableRow(page, 'P000014');
    await lockedOutRow.waitFor();
    const lockedOutText = await lockedOutRow.innerText();
    if (!lockedOutText.includes('8') || !lockedOutText.includes('0') || !lockedOutText.includes('无可用库存') || !lockedOutText.includes('全部锁定')) {
      throw new Error(`库存数量或状态展示不正确：${lockedOutText}`);
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '零库存');
    await clickQueryAndAssertLoading(page);
    const outOfStockRow = tableRow(page, 'P000008');
    await outOfStockRow.waitFor();
    if (await page.locator('tbody tr').count() !== 1) throw new Error('零库存筛选应只返回当前库存为 0 的记录');
    if (!await outOfStockRow.evaluate(element => element.classList.contains('bg-rose-50/60'))) {
      throw new Error('零库存行未使用浅红色风险背景');
    }
    await clickResetAndAssertLoading(page);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'P000004').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'P000001').waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
    const filterColumns = await page.locator('.filter-grid--stocks').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`库存筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-warehouse-stocks-1115.png', fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 库存余额、库存健康、占用情况、组合筛选、分页与加载反馈通过');
});
