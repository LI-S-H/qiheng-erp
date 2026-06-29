const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  clickQueryAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectRemoteOption(page, dialog, comboboxIndex, keyword, optionText) {
  await dialog.getByRole('combobox').nth(comboboxIndex).click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.locator('input').fill(keyword);
  await content.getByText(optionText).first().click();
}

runSmoke({
  route: '/purchase/suppliers',
  screenshot: 'smoke-purchase.png',
  async test(page) {
    await page.getByRole('heading', { name: '供应商管理' }).waitFor();
    await tableRow(page, 'S001').waitFor();
    await assertFixedTableLayout(page, 10);
    await clickRefreshAndAssertLoading(page, 'smoke-purchase-supplier-refresh.png');

    await page.getByPlaceholder('请输入名称').fill('华东');
    await clickQueryAndAssertLoading(page, 'smoke-purchase-supplier-query.png');
    await tableRow(page, '华东饮品供应链').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增供应商' }).click();
    const supplierDialog = page.getByRole('dialog', { name: '新增供应商' });
    await assertRequiredLabels(supplierDialog, ['供应商名称']);
    await supplierDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await supplierDialog.innerText()).includes('请输入供应商名称')) throw new Error('供应商新增缺少必填校验');
    await supplierDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'S001').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '供应商详情' }).getByText('供应商编码').waitFor();
    await page.getByRole('dialog', { name: '供应商详情' }).getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '供货产品', exact: true }).click();
    await page.getByRole('heading', { name: '供货产品' }).waitFor();
    await tableRow(page, 'HD-SD330').waitFor();
    await assertFixedTableLayout(page, 10);
    await page.getByPlaceholder('请输入产品名称').fill('苏打水');
    await clickQueryAndAssertLoading(page, 'smoke-purchase-supplier-products-query.png');
    await tableRow(page, '经典原味苏打水').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增供货产品' }).click();
    const supplierProductDialog = page.getByRole('dialog', { name: '新增供货产品' });
    await assertRequiredLabels(supplierProductDialog, ['供应商', '产品']);
    await supplierProductDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'HD-SD330').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '供货产品详情' }).getByText('最近采购价').waitFor();
    await page.getByRole('dialog', { name: '供货产品详情' }).getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '采购订单', exact: true }).click();
    await page.getByRole('heading', { name: '采购订单' }).waitFor();
    await tableRow(page, 'PO202606001').waitFor();
    await assertFixedTableLayout(page, 9);
    await page.getByPlaceholder('如 PO202606001').fill('PO202606001');
    await clickQueryAndAssertLoading(page, 'smoke-purchase-orders-query.png');
    await tableRow(page, 'PO202606001').waitFor();
    await clickResetAndAssertLoading(page);

    await tableRow(page, 'PO202606003').getByRole('button', { name: '编辑' }).click();
    const editOrderDialog = page.getByRole('dialog', { name: '编辑采购单' });
    await editOrderDialog.getByText('PO202606003').waitFor();
    await editOrderDialog.getByText('预计到货').waitFor();
    await editOrderDialog.getByText('示例：2026-06-30').waitFor();
    if ((await editOrderDialog.innerText()).includes('明细预计到货')) throw new Error('采购明细不应再展示预计到货字段');
    await assertRequiredLabels(editOrderDialog, ['供应商', '入库仓库']);
    await editOrderDialog.getByRole('button', { name: '保存修改' }).click();
    await editOrderDialog.waitFor({ state: 'hidden' });

    await tableRow(page, 'PO202606003').getByRole('button', { name: '提交' }).click();
    const submitPreviewDialog = page.getByRole('dialog', { name: '采购单详情' });
    await submitPreviewDialog.getByText('请先核对采购单头和全部采购明细，再提交进入待审核。').waitFor();
    if (await submitPreviewDialog.getByRole('button', { name: '提交采购单' }).isDisabled()) {
      throw new Error('预计到货已维护时提交按钮不应禁用');
    }
    await submitPreviewDialog.getByRole('button', { name: '提交采购单' }).click();
    const submitDialog = page.getByRole('alertdialog', { name: '提交采购单' });
    await submitDialog.getByText('提交后进入待审核状态').waitFor();
    await submitDialog.getByRole('button', { name: '取消' }).click();
    await submitPreviewDialog.getByRole('button', { name: '关闭' }).click();

    await tableRow(page, 'PO202606001').getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '采购单详情' });
    await detailDialog.getByText('采购单号').waitFor();
    await detailDialog.getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '新增采购单' }).click();
    const orderDialog = page.getByRole('dialog', { name: '新增采购单草稿' });
    const orderDialogText = await orderDialog.innerText();
    if (!orderDialogText.includes('后端自动生成') || !orderDialogText.includes('保存后为草稿') || !orderDialogText.includes('预计到货') || !orderDialogText.includes('示例：2026-06-30')) {
      throw new Error('采购订单新增弹窗缺少系统生成字段或单头预计到货字段');
    }
    await selectRemoteOption(page, orderDialog, 0, 'S001', 'S001');
    await selectRemoteOption(page, orderDialog, 1, 'WH001', 'WH001');
    await orderDialog.getByRole('combobox').nth(2).click();
    const productContent = page.locator('[data-remote-search-select-content]').last();
    await productContent.locator('input').fill('P0001');
    /*
    if (!supplierOptionsText.includes('S001 华东饮品供应链') || supplierOptionsText.includes('S002 晨岛咖啡贸易')) {
      throw new Error('先选择产品后，供应商下拉未按供货关系过滤');
    }
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('S001 华东饮品供应链').click();
    */
    await productContent.getByText('P0001').waitFor();
    const productOptionsText = await productContent.innerText();
    if (!productOptionsText.includes('P0001 经典原味苏打水') || productOptionsText.includes('P0002 速溶黑咖啡')) {
      throw new Error('选择供应商后，产品下拉未按该供应商供货范围过滤');
    }
    await orderDialog.getByRole('button', { name: '取消', exact: true }).click();

    await page.getByRole('button', { name: '新增采购单' }).click();
    const requiredOrderDialog = page.getByRole('dialog', { name: '新增采购单草稿' });
    await assertRequiredLabels(requiredOrderDialog, ['供应商', '入库仓库']);
    await requiredOrderDialog.getByRole('button', { name: '保存草稿' }).click();
    if (!(await requiredOrderDialog.innerText()).includes('请选择供应商') || !(await requiredOrderDialog.innerText()).includes('请选择入库仓库')) {
      throw new Error('采购订单草稿缺少供应商或仓库必填校验');
    }
    await requiredOrderDialog.getByRole('button', { name: '取消', exact: true }).click();

    console.log('SMOKE_OK: 采购模块供应商、供货产品、采购订单页面通过');
  },
});
