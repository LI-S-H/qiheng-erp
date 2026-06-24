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
  route: '/sales/customers',
  screenshot: 'smoke-sales.png',
  async test(page) {
    await page.getByRole('heading', { name: '客户管理' }).waitFor();
    await tableRow(page, 'C001').waitFor();
    await assertFixedTableLayout(page, 8);
    await clickRefreshAndAssertLoading(page, 'smoke-sales-customers-refresh.png');

    await page.getByPlaceholder('请输入名称').fill('上海');
    await clickQueryAndAssertLoading(page, 'smoke-sales-customers-query.png');
    await tableRow(page, '上海林间便利连锁').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增客户' }).click();
    const customerDialog = page.getByRole('dialog', { name: '新增客户' });
    await assertRequiredLabels(customerDialog, ['客户名称']);
    await customerDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await customerDialog.innerText()).includes('请输入客户名称')) throw new Error('客户新增缺少必填校验');
    await customerDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'C001').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '客户详情' }).getByText('客户编码').waitFor();
    await page.getByRole('dialog', { name: '客户详情' }).getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '销售订单', exact: true }).click();
    await page.getByRole('heading', { name: '销售订单' }).waitFor();
    await tableRow(page, 'SO202606001').waitFor();
    await assertFixedTableLayout(page, 9);
    await page.getByPlaceholder('如 SO202606001').fill('SO202606001');
    await clickQueryAndAssertLoading(page, 'smoke-sales-orders-query.png');
    await tableRow(page, 'SO202606001').waitFor();
    await clickResetAndAssertLoading(page);

    await tableRow(page, 'SO202606003').getByRole('button', { name: '编辑' }).click();
    const editOrderDialog = page.getByRole('dialog', { name: '编辑销售单' });
    await editOrderDialog.getByText('SO202606003').waitFor();
    await editOrderDialog.getByText('预计发货').waitFor();
    await assertRequiredLabels(editOrderDialog, ['客户', '出库仓库']);
    await editOrderDialog.getByRole('button', { name: '保存修改' }).click();
    await editOrderDialog.waitFor({ state: 'hidden' });

    await tableRow(page, 'SO202606003').getByRole('button', { name: '提交' }).click();
    const submitPreviewDialog = page.getByRole('dialog', { name: '销售单详情' });
    await submitPreviewDialog.getByText('请先核对销售单头和全部销售明细，再提交进入待审核并锁定库存。').waitFor();
    if (await submitPreviewDialog.getByRole('button', { name: '提交销售单' }).isDisabled()) {
      throw new Error('预计发货已维护时提交按钮不应禁用');
    }
    await submitPreviewDialog.getByRole('button', { name: '提交销售单' }).click();
    const submitDialog = page.getByRole('alertdialog', { name: '提交销售单' });
    await submitDialog.getByText('锁定可用库存').waitFor();
    await submitDialog.getByRole('button', { name: '取消' }).click();
    await submitPreviewDialog.getByRole('button', { name: '关闭' }).click();

    await tableRow(page, 'SO202606001').getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '销售单详情' });
    await detailDialog.getByText('销售单号').waitFor();
    await detailDialog.getByRole('button', { name: '关闭' }).click();

    await page.getByRole('button', { name: '新增销售单' }).click();
    const orderDialog = page.getByRole('dialog', { name: '新增销售单草稿' });
    const orderDialogText = await orderDialog.innerText();
    if (!orderDialogText.includes('后端自动生成') || !orderDialogText.includes('保存后为草稿') || !orderDialogText.includes('预计发货')) {
      throw new Error('销售订单新增弹窗缺少系统生成字段或单头预计发货字段');
    }
    await assertRequiredLabels(orderDialog, ['客户', '出库仓库']);
    await orderDialog.getByRole('button', { name: '保存草稿' }).click();
    const requiredText = await orderDialog.innerText();
    if (!requiredText.includes('请选择客户') || !requiredText.includes('请选择出库仓库')) {
      throw new Error('销售订单草稿缺少客户或仓库必填校验');
    }
    await orderDialog.getByRole('button', { name: '取消', exact: true }).click();
    await orderDialog.waitFor({ state: 'hidden' });

    console.log('SMOKE_OK: 销售模块客户和销售订单页面通过');
  },
});
