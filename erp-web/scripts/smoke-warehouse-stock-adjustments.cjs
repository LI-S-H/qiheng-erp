const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  clickQueryAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

async function selectDialogOption(page, dialog, index, label) {
  await dialog.getByRole('combobox').nth(index).click();
  const content = page.locator('[data-anchored-select-content][data-state="open"]');
  await content.getByText(label, { exact: true }).click();
  await content.waitFor({ state: 'hidden' });
  await page.waitForTimeout(180);
}

runSmoke({
  route: '/warehouse/stock-adjustments',
  screenshot: 'smoke-warehouse-stock-adjustments.png',
  async test(page) {
    await page.getByRole('heading', { name: '库存调整', exact: true }).waitFor();
    await tableRow(page, 'SB202606140003').waitFor();
    await assertFixedTableLayout(page, 10);

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['调整单据\n3', '调整入库\n2', '调整出库\n1', '已确认\n2']) {
      if (!summaryText.includes(expected)) throw new Error(`库存调整摘要不正确：缺少 ${expected}`);
    }
    const initialRows = page.locator('tbody tr');
    if (await initialRows.count() !== 3) throw new Error('库存调整页面混入了非调整凭证');
    for (const row of await initialRows.all()) {
      const rowText = await row.innerText();
      if (!rowText.includes('库存调整单') || !rowText.includes('人工录入')) {
        throw new Error(`库存调整记录来源标识不完整：${rowText}`);
      }
    }
    await clickRefreshAndAssertLoading(page, 'smoke-warehouse-stock-adjustments-refresh-loading.png');

    await page.getByPlaceholder('如 ADJ202606001').fill('ADJ202606002');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SB202606130008').waitFor();
    if (await page.locator('tbody tr').count() !== 1) throw new Error('调整单号筛选未独立生效');
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '调整出库');
    await selectFilter(page, 2, '已确认');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SB202606130008').waitFor();
    if (await page.locator('tbody tr').count() !== 1) throw new Error('调整方向与状态未按 AND 组合筛选');
    await clickResetAndAssertLoading(page);

    const draftRow = tableRow(page, 'SB202606140003');
    await draftRow.getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '库存调整详情' });
    const detailText = await detailDialog.innerText();
    for (const expected of ['ADJ202606001', '人工调整', '库存盘点调整', '系统管理员']) {
      if (!detailText.includes(expected)) throw new Error(`库存调整详情缺少 ${expected}`);
    }
    await detailDialog.getByRole('button', { name: 'Close' }).click();

    await draftRow.getByRole('button', { name: '取消', exact: true }).click();
    const cancelDialog = page.getByRole('alertdialog', { name: '取消库存调整草稿' });
    if (!(await cancelDialog.innerText()).includes('取消后不改变库存')) throw new Error('取消调整未说明库存影响');
    await cancelDialog.getByRole('button', { name: '确认取消', exact: true }).click();
    await draftRow.getByText('已取消', { exact: true }).waitFor();

    await page.getByRole('button', { name: '新增调整', exact: true }).click();
    const createDialog = page.getByRole('dialog', { name: '新增库存调整' });
    const typeTrigger = createDialog.getByRole('combobox').first();
    await typeTrigger.click();
    const typeContent = page.locator('[data-anchored-select-content][data-state="open"]');
    if (await typeContent.getByText('补录采购入库', { exact: true }).count()) throw new Error('库存调整页面不应开放人工补录类型');
    await typeContent.getByText('库存调整入库', { exact: true }).click();
    await selectDialogOption(page, createDialog, 1, 'WH001 华东中心仓');
    await selectDialogOption(page, createDialog, 2, 'P0002 速溶黑咖啡（盒）');
    const generatedValues = await createDialog.locator('input[readonly]').evaluateAll(elements => elements.map(element => element.value));
    if (generatedValues.filter(value => value.includes('系统生成')).length < 2) throw new Error('流水号和调整单号必须由系统生成');
    await createDialog.getByRole('spinbutton').fill('2');
    await createDialog.getByPlaceholder('说明盘点差异或调整依据').fill('华东中心仓盘盈复核');
    await createDialog.getByRole('button', { name: '保存草稿' }).click();

    const createdRow = tableRow(page, 'SB202606140016');
    await createdRow.getByText('调整入库', { exact: true }).waitFor();
    await createdRow.getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑库存调整草稿' });
    await editDialog.getByRole('spinbutton').fill('3');
    await editDialog.getByRole('button', { name: '保存草稿' }).click();

    await createdRow.getByRole('button', { name: '确认', exact: true }).click();
    const confirmDialog = page.getByRole('alertdialog', { name: '确认库存调整' });
    if (!(await confirmDialog.innerText()).includes('立即更新库存余额')) throw new Error('确认调整未说明库存影响');
    await confirmDialog.getByRole('button', { name: '确认执行', exact: true }).click();
    await createdRow.getByText('已确认', { exact: true }).waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '库存调整', exact: true }).waitFor();
    const filterColumns = await page.locator('.filter-grid--stock-adjustments').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`库存调整筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-warehouse-stock-adjustments-1115.png', fullPage: true });
  },
}).then(() => {
  console.log('SMOKE_OK: 库存调整专用查询、新增、编辑、确认、取消、详情和响应式布局通过');
});
