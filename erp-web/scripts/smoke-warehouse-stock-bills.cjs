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

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-warehouse-stock-bills.png',
  async test(page) {
    await page.getByRole('heading', { name: '出入库记录' }).waitFor();
    await tableRow(page, 'SB202606140001').waitFor();
    await assertFixedTableLayout(page, 9);

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['流水记录\n15', '入库记录\n8', '出库记录\n7', '已确认\n10']) {
      if (!summaryText.includes(expected)) throw new Error(`出入库摘要不正确：缺少 ${expected}`);
    }
    await clickRefreshAndAssertLoading(page, 'smoke-warehouse-stock-bills-refresh-loading.png');

    await page.getByPlaceholder('如 SB202606140001').fill('SB202606140002');
    await clickQueryAndAssertLoading(page, 'smoke-warehouse-stock-bills-query-loading.png');
    await tableRow(page, 'SB202606140002').waitFor();
    if (await page.locator('tbody tr').count() !== 1) throw new Error('流水号筛选未独立生效');
    await clickResetAndAssertLoading(page, 'smoke-warehouse-stock-bills-reset-loading.png');

    await page.getByPlaceholder('如 PO202606001').fill('PO202606001');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SB202606140001').waitFor();
    await tableRow(page, 'SB202606140002').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 0, 'WH001 华东中心仓');
    await clickQueryAndAssertLoading(page);
    const warehouseRows = page.locator('tbody tr');
    if (await warehouseRows.count() !== 3) throw new Error('仓库筛选未返回预期的 3 条流水');
    for (const rowText of await warehouseRows.allInnerTexts()) {
      if (!rowText.includes('华东中心仓')) throw new Error(`仓库筛选混入其他仓库：${rowText}`);
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '采购入库');
    await selectFilter(page, 2, '已确认');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SB202606140001').waitFor();
    await tableRow(page, 'SB202606130006').waitFor();
    if (await page.locator('tbody tr').count() !== 2) throw new Error('出入库类型与状态未按 AND 组合筛选');
    await clickResetAndAssertLoading(page);

    const confirmedRow = tableRow(page, 'SB202606140001');
    await confirmedRow.getByRole('button', { name: '详情' }).click();
    const confirmedDialog = page.getByRole('dialog', { name: '出入库凭证详情' });
    await confirmedDialog.getByText('PO202606001', { exact: true }).waitFor();
    const confirmedText = await confirmedDialog.innerText();
    for (const expected of ['P0001', '经典原味苏打水', 'P0002', '速溶黑咖啡', '+30', '+12']) {
      if (!confirmedText.includes(expected)) throw new Error(`已确认入库详情缺少 ${expected}`);
    }
    await page.waitForTimeout(180);
    await page.screenshot({ path: 'smoke-warehouse-stock-bills-detail.png', fullPage: true });
    await confirmedDialog.getByRole('button', { name: 'Close' }).click();

    await page.getByPlaceholder('如 SB202606140001').fill('SB202606140003');
    await clickQueryAndAssertLoading(page);
    const draftRow = tableRow(page, 'SB202606140003');
    await draftRow.getByRole('button', { name: '详情' }).click();
    const draftDialog = page.getByRole('dialog', { name: '出入库凭证详情' });
    const draftText = await draftDialog.innerText();
    if (!draftText.includes('草稿') || !draftText.includes('未确认')) throw new Error('草稿流水详情状态不正确');
    const draftItem = draftDialog.locator('[data-stock-bill-item-id]').first();
    if ((await draftItem.getByRole('cell').nth(6).innerText()).trim() !== '0') throw new Error('草稿流水不应形成实际库存变动');
    await draftDialog.getByRole('button', { name: 'Close' }).click();
    await clickResetAndAssertLoading(page);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'SB202606120011').waitFor();
    await tableRow(page, 'SB202606140001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'SB202606140001').waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出入库记录' }).waitFor();
    await tableRow(page, 'SB202606140001').waitFor();
    const filterColumns = await page.locator('.filter-grid--stock-bills').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`出入库筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-warehouse-stock-bills-1115.png', fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出入库记录' }).waitFor();
    await tableRow(page, 'SB202606140001').waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 出入库记录独立筛选、组合查询、凭证明细、分页与加载反馈通过');
});
