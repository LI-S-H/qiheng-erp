const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  clickQueryAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

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

    await page.getByRole('button', { name: '采购订单', exact: true }).click();
    await page.getByRole('heading', { name: '采购订单' }).waitFor();
    await tableRow(page, 'PO202606001').waitFor();
    await assertFixedTableLayout(page, 9);
    await page.getByPlaceholder('如 PO202606001').fill('PO202606001');
    await clickQueryAndAssertLoading(page, 'smoke-purchase-orders-query.png');
    await tableRow(page, 'PO202606001').waitFor();
    await clickResetAndAssertLoading(page);

    await tableRow(page, 'PO202606001').getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '采购单详情' });
    await detailDialog.getByText('采购单号').waitFor();
    await detailDialog.getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '新增采购单' }).click();
    const orderDialog = page.getByRole('dialog', { name: '新增采购单草稿' });
    await assertRequiredLabels(orderDialog, ['供应商', '入库仓库']);
    await orderDialog.getByRole('button', { name: '保存草稿' }).click();
    if (!(await orderDialog.innerText()).includes('请选择供应商') || !(await orderDialog.innerText()).includes('请选择入库仓库')) {
      throw new Error('采购订单草稿缺少供应商或仓库必填校验');
    }
    await orderDialog.getByRole('button', { name: '取消', exact: true }).click();

    console.log('SMOKE_OK: 采购模块供应商、供货产品、采购订单页面通过');
  },
});
