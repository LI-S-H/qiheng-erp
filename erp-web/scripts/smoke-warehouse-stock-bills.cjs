const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertDialogScrollGutter,
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

async function selectDialogOption(page, dialog, index, label) {
  await dialog.getByRole('combobox').nth(index).click();
  const content = page.locator('[data-anchored-select-content][data-state="open"]');
  await content.getByText(label, { exact: true }).click();
  await content.waitFor({ state: 'hidden' });
  await page.waitForTimeout(180);
}

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-warehouse-stock-bills.png',
  async test(page) {
    await page.getByRole('heading', { name: '出入库记录' }).waitFor();
    await tableRow(page, 'SB202606140001').waitFor();
    await assertFixedTableLayout(page, 10);

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
    await selectFilter(page, 3, '已确认');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SB202606140001').waitFor();
    await tableRow(page, 'SB202606130006').waitFor();
    if (await page.locator('tbody tr').count() !== 2) throw new Error('出入库类型与状态未按 AND 组合筛选');
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 2, '系统自动录入');
    await clickQueryAndAssertLoading(page);
    if (await page.locator('tbody [data-manual-entry-marker]').count() !== 0) throw new Error('系统自动录入筛选混入人工凭证');
    await tableRow(page, 'SB202606140001').waitFor();
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 2, '人工调整');
    await clickQueryAndAssertLoading(page);
    const adjustmentRows = page.locator('tbody tr');
    if (await adjustmentRows.count() === 0) throw new Error('人工调整筛选未返回库存调整凭证');
    for (const row of await adjustmentRows.all()) {
      await row.locator('[data-manual-entry-marker]').getByText('人工录入', { exact: true }).waitFor();
    }
    await clickResetAndAssertLoading(page);

    const confirmedRow = tableRow(page, 'SB202606140001');
    await confirmedRow.getByRole('button', { name: '详情' }).click();
    const confirmedDialog = page.getByRole('dialog', { name: '出入库凭证详情' });
    await confirmedDialog.getByText('PO202606001', { exact: true }).waitFor();
    const confirmedText = await confirmedDialog.innerText();
    for (const expected of ['P0001', '经典原味苏打水', 'P0002', '速溶黑咖啡', '+30', '+12']) {
      if (!confirmedText.includes(expected)) throw new Error(`已确认入库详情缺少 ${expected}`);
    }
    if (await confirmedRow.locator('[data-manual-entry-marker]').count() !== 0) throw new Error('来源生成凭证不应显示人工录入标记');
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

    await page.getByRole('button', { name: '新增出入库' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增出入库凭证' });
    await page.setViewportSize({ width: 1115, height: 838 });
    await createDialog.getByRole('button', { name: '添加产品' }).click();
    await assertDialogScrollGutter(createDialog, 8, true);
    await page.screenshot({ path: 'smoke-warehouse-stock-bills-dialog-scroll-gutter.png', fullPage: true });
    await createDialog.getByRole('button', { name: '删除产品明细' }).last().click();
    await page.setViewportSize({ width: 1440, height: 900 });
    const generatedFields = createDialog.locator('input[readonly]');
    if (!(await generatedFields.first().inputValue()).includes('系统生成')) throw new Error('新增调整的流水号必须由系统生成');
    await selectDialogOption(page, createDialog, 1, 'WH001 华东中心仓');
    await selectDialogOption(page, createDialog, 2, 'P0002 速溶黑咖啡（盒）');
    const integerQuantity = createDialog.getByRole('spinbutton').first();
    if (await integerQuantity.getAttribute('step') !== '1') throw new Error('盒装产品数量步长必须为 1');
    await integerQuantity.fill('1.5');
    await createDialog.getByRole('button', { name: '保存草稿' }).click();
    if (!(await createDialog.innerText()).includes('数量必须是整数')) throw new Error('盒装产品未拒绝小数数量');
    await integerQuantity.fill('2');
    await createDialog.getByPlaceholder('说明盘点差异或调整依据').fill('盘点补录测试');
    const draftColumns = await createDialog.locator('.draft-item-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length);
    if (draftColumns !== 4) throw new Error(`库存调整明细在宽屏下应为四列，当前为 ${draftColumns} 列`);
    await page.screenshot({ path: 'smoke-warehouse-stock-bills-create.png', fullPage: true });
    await createDialog.getByRole('button', { name: '保存草稿' }).click();

    const createdRow = tableRow(page, 'SB202606140016');
    await createdRow.getByText('调整入库', { exact: true }).waitFor();
    const createdText = await createdRow.innerText();
    if (!createdText.includes('库存调整单') || !createdText.includes('草稿')) throw new Error('新增库存调整未形成草稿流水');
    const createdBillNo = (await createdRow.locator('code').first().innerText()).trim();

    await createdRow.getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑出入库草稿' });
    if (await editDialog.locator('input[readonly]').first().inputValue() !== createdBillNo) throw new Error('编辑草稿未显示系统生成的流水号');
    await editDialog.getByRole('spinbutton').fill('3');
    await editDialog.getByRole('button', { name: '保存草稿' }).click();

    const editedRow = tableRow(page, createdBillNo);
    await editedRow.getByRole('button', { name: '确认', exact: true }).click();
    const confirmDialog = page.getByRole('alertdialog', { name: '确认出入库' });
    const confirmText = await confirmDialog.innerText();
    if (!confirmText.includes('立即更新库存余额') || !confirmText.includes('不能再编辑或直接取消')) throw new Error('确认出入库未说明库存与状态影响');
    await confirmDialog.getByRole('button', { name: '确认执行', exact: true }).click();
    await editedRow.getByText('已确认', { exact: true }).waitFor();
    if (await editedRow.getByRole('button', { name: '编辑' }).count() !== 0
      || await editedRow.getByRole('button', { name: '确认', exact: true }).count() !== 0
      || await editedRow.getByRole('button', { name: '取消', exact: true }).count() !== 0) {
      throw new Error('已确认凭证仍允许编辑或再次变更状态');
    }
    await editedRow.getByRole('button', { name: '详情' }).click();
    const adjustedDialog = page.getByRole('dialog', { name: '出入库凭证详情' });
    const adjustedText = await adjustedDialog.innerText();
    if (!adjustedText.includes('+3') || !adjustedText.includes('盘点补录测试')) throw new Error('编辑后的调整数量未在确认凭证中生效');
    await adjustedDialog.getByRole('button', { name: 'Close' }).click();

    await page.getByRole('button', { name: '新增出入库' }).click();
    const kgDialog = page.getByRole('dialog', { name: '新增出入库凭证' });
    await selectDialogOption(page, kgDialog, 1, 'WH001 华东中心仓');
    await selectDialogOption(page, kgDialog, 2, 'P0015 散装东北大米（kg）');
    const kgQuantity = kgDialog.getByRole('spinbutton').first();
    if (await kgQuantity.getAttribute('step') !== '0.01') throw new Error('kg 产品数量步长必须为 0.01');
    await kgQuantity.fill('1.234');
    await kgDialog.getByPlaceholder('说明盘点差异或调整依据').fill('称重盘点测试');
    await kgDialog.getByRole('button', { name: '保存草稿' }).click();
    if (!(await kgDialog.innerText()).includes('最多保留 2 位小数')) throw new Error('kg 产品未拒绝三位小数');
    await kgQuantity.fill('1.25');
    await kgDialog.getByRole('button', { name: '保存草稿' }).click();
    const kgRow = tableRow(page, 'SB202606140017');
    await kgRow.locator('[data-manual-entry-marker]').getByText('人工录入', { exact: true }).waitFor();
    await kgRow.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('alertdialog', { name: '取消出入库草稿' }).getByRole('button', { name: '确认取消', exact: true }).click();

    await page.getByRole('button', { name: '库存管理', exact: true }).click();
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await page.getByPlaceholder('如 P0001').fill('P0002');
    await clickQueryAndAssertLoading(page);
    const changedStockRow = tableRow(page, 'P0002');
    if ((await changedStockRow.getByRole('cell').nth(3).innerText()).trim() !== '10') throw new Error('确认调整入库后库存余额未从 7 更新为 10');
    await page.getByRole('button', { name: '出入库记录', exact: true }).click();
    await page.getByRole('heading', { name: '出入库记录' }).waitFor();
    await tableRow(page, createdBillNo).waitFor();

    await page.getByRole('button', { name: '新增出入库' }).click();
    const cancelCreateDialog = page.getByRole('dialog', { name: '新增出入库凭证' });
    await selectDialogOption(page, cancelCreateDialog, 0, '库存调整出库');
    await selectDialogOption(page, cancelCreateDialog, 1, 'WH001 华东中心仓');
    await selectDialogOption(page, cancelCreateDialog, 2, 'P0003 每日坚果混合装（盒）');
    await cancelCreateDialog.getByRole('spinbutton').fill('1');
    await cancelCreateDialog.getByPlaceholder('说明盘点差异或调整依据').fill('取消流程测试');
    await cancelCreateDialog.getByRole('button', { name: '保存草稿' }).click();
    const cancelRow = tableRow(page, 'SB202606140018');
    const cancelBillNo = (await cancelRow.locator('code').first().innerText()).trim();
    await cancelRow.getByRole('button', { name: '取消', exact: true }).click();
    const cancelDialog = page.getByRole('alertdialog', { name: '取消出入库草稿' });
    if (!(await cancelDialog.innerText()).includes('取消后不改变库存')) throw new Error('取消草稿未说明库存不变');
    await cancelDialog.getByRole('button', { name: '确认取消', exact: true }).click();
    await tableRow(page, cancelBillNo).getByText('已取消', { exact: true }).waitFor();

    await page.getByRole('button', { name: '新增出入库' }).click();
    const supplementDialog = page.getByRole('dialog', { name: '新增出入库凭证' });
    await selectDialogOption(page, supplementDialog, 0, '补录销售退货入库');
    await supplementDialog.getByPlaceholder('填写线下单据、送货单或退货单号').fill('SRO-OFFLINE-001');
    await supplementDialog.getByPlaceholder('说明未登记原业务单据的原因').fill('线下退货单遗漏登记');
    const readonlyValues = await supplementDialog.locator('input[readonly]').evaluateAll(elements => elements.map(element => element.value));
    if (!readonlyValues.includes('系统管理员') || !(await supplementDialog.innerText()).includes('不允许代填')) {
      throw new Error('补录负责人未按当前登录用户只读展示');
    }
    await selectDialogOption(page, supplementDialog, 1, 'WH001 华东中心仓');
    await selectDialogOption(page, supplementDialog, 2, 'P0001 经典原味苏打水（箱）');
    const supplementQuantities = supplementDialog.getByRole('spinbutton');
    await supplementQuantities.nth(0).fill('2');
    await supplementQuantities.nth(1).fill('2');
    await supplementDialog.getByRole('button', { name: '保存草稿' }).click();
    const supplementRow = page.getByRole('row').filter({ hasText: 'SRO-OFFLINE-001' }).first();
    await supplementRow.locator('[data-manual-entry-marker]').getByText('人工录入', { exact: true }).waitFor();
    await supplementRow.getByText('系统管理员', { exact: true }).first().waitFor();
    await supplementRow.getByRole('button', { name: '详情' }).click();
    const supplementDetail = page.getByRole('dialog', { name: '出入库凭证详情' });
    const supplementText = await supplementDetail.innerText();
    for (const expected of ['手工补录', 'SRO-OFFLINE-001', '线下退货单遗漏登记', '系统管理员']) {
      if (!supplementText.includes(expected)) throw new Error(`补录详情缺少 ${expected}`);
    }
    await supplementDetail.getByRole('button', { name: 'Close' }).click();

    await selectFilter(page, 2, '人工补录');
    await clickQueryAndAssertLoading(page);
    const supplementFilteredRow = page.getByRole('row').filter({ hasText: 'SRO-OFFLINE-001' }).first();
    await supplementFilteredRow.locator('[data-manual-entry-marker]').getByText('人工录入', { exact: true }).waitFor();
    for (const rowText of await page.locator('tbody tr').allInnerTexts()) {
      if (!rowText.includes('SRO-OFFLINE-001')) throw new Error(`人工补录筛选混入其他录入方式：${rowText}`);
    }
    await clickResetAndAssertLoading(page);

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
  console.log('SMOKE_OK: 出入库记录筛选、新增调整、编辑草稿、确认取消、凭证明细与加载反馈通过');
});
