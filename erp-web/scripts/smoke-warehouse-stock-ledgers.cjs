const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertSharedListChrome,
  clickQueryAndAssertLoading,
  clickResetAndAssertLoading,
  clickRefreshAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-warehouse-stock-ledgers.png',
  async test(page) {
    await page.getByRole('heading', { name: '库存流水' }).waitFor();
    if (new URL(page.url()).pathname !== '/warehouse/stock-bills') {
      throw new Error(`库存流水路由不应跳转到入库单：${page.url()}`);
    }
    await assertSharedListChrome(page, { summaryLabel: '库存流水数据汇总', filterLabel: '库存流水筛选' });
    const firstRow = tableRow(page, 'SL202607180001');
    await firstRow.waitFor();
    await assertFixedTableLayout(page, 8);
    const firstRowText = await firstRow.innerText();
    for (const expected of ['采购入库', '采购订单', 'PO202607180001', '华东中心仓', '管理员']) {
      if (!firstRowText.includes(expected)) throw new Error(`库存流水列表缺少 ${expected}：${firstRowText}`);
    }
    if (!(await firstRow.getByRole('button', { name: /展开.*2 条变动明细/ }).count())) {
      throw new Error('库存流水展开按钮缺少明细数量的无障碍说明');
    }
    if (await page.getByRole('button', { name: /新增|编辑|提交|确认|取消/ }).count()) {
      throw new Error('库存流水页面不应提供写操作入口');
    }

    const expandButton = firstRow.getByRole('button', { name: /展开明细/ });
    await expandButton.click();
    const detail = page.locator('[data-stock-ledger-detail-id="1950000000000000001"]');
    await detail.waitFor();
    if (!(await detail.innerText()).includes('P000001') || !(await detail.innerText()).includes('+20')) {
      throw new Error('库存流水展开明细未展示产品和正负变动数量');
    }
    await firstRow.getByRole('button', { name: /收起明细/ }).click();

    await clickRefreshAndAssertLoading(page);
    await page.getByPlaceholder('如 SL202607180001').fill('SL202607180002');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SL202607180002').waitFor();
    await tableRow(page, 'SL202607180001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '销售出库');
    await clickQueryAndAssertLoading(page);
    const filteredRows = page.locator('[data-stock-ledger-id]');
    if (await filteredRows.count() !== 1 || !(await filteredRows.first().innerText()).includes('SL202607180002')) {
      throw new Error('库存流水出入库类型筛选未生效');
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 2, '人工调整');
    await clickQueryAndAssertLoading(page);
    const adjustmentRows = page.locator('[data-stock-ledger-id]');
    if (await adjustmentRows.count() !== 2) {
      throw new Error('库存流水录入方式筛选未返回预期的人工调整记录');
    }
    for (const expected of ['SL202607170003', 'SL202607140006']) {
      if (!await tableRow(page, expected).count()) throw new Error(`库存流水录入方式筛选缺少 ${expected}`);
    }
    await clickResetAndAssertLoading(page);

    await page.locator('[data-menu-path="/warehouse/stock-bills"]').click();
    await page.getByRole('heading', { name: '库存流水' }).waitFor();
    if (new URL(page.url()).pathname !== '/warehouse/stock-bills') {
      throw new Error(`菜单未进入库存流水路由：${page.url()}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 库存流水独立路由、只读边界、查询筛选和菜单跳转通过');
});
