const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, clickQueryAndAssertLoading, clickPaginationAndAssertLoading, clickRefreshAndAssertLoading, clickResetAndAssertLoading } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/product/products',
  screenshot: 'smoke-product-products.png',
  async test(page) {
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P0001').waitFor();
    await assertFixedTableLayout(page, 10);
    await page.getByText('经典原味苏打水', { exact: true }).waitFor();
    await page.getByText('饮料冲调', { exact: true }).first().waitFor();
    await clickRefreshAndAssertLoading(page, 'smoke-product-products-refresh-loading.png');

    await page.getByPlaceholder('请输入产品名称').first().fill('A4');
    await clickQueryAndAssertLoading(page, 'smoke-product-products-query-loading.png');
    await tableRow(page, 'P0007').waitFor();
    await tableRow(page, 'P0001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page, 'smoke-product-products-reset-loading.png');
    await tableRow(page, 'P0001').waitFor();

    const filterPanel = page.locator('.filter-grid--products');
    const categoryTrigger = filterPanel.getByRole('combobox').first();
    const selectCategory = async (label) => {
      await categoryTrigger.click();
      const content = page.locator('[data-anchored-select-content][data-state="open"]');
      if (label === '办公用品') await page.screenshot({ path: 'smoke-product-category-path-options.png', fullPage: true });
      await content.getByText(label, { exact: true }).click();
      await page.getByRole('button', { name: '查询', exact: true }).click();
    };
    await selectCategory('办公用品');
    await tableRow(page, 'P0005').waitFor();
    await tableRow(page, 'P0007').waitFor();
    await tableRow(page, 'P0008').waitFor();
    await tableRow(page, 'P0001').waitFor({ state: 'detached' });
    await selectCategory('办公用品 / 办公纸品');
    await tableRow(page, 'P0007').waitFor();
    await tableRow(page, 'P0008').waitFor();
    await tableRow(page, 'P0005').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await page.getByPlaceholder('请输入品牌名称').fill('森纸');
    await page.getByPlaceholder('请输入完整条码').fill('6901000000073');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'P0007').waitFor();
    await tableRow(page, 'P0008').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    const queryInput = page.getByPlaceholder('请输入产品名称').first();
    await queryInput.click();
    await page.waitForTimeout(250);
    const queryInputShadow = await queryInput.evaluate(element => getComputedStyle(element).boxShadow);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'P0011').waitFor();
    await tableRow(page, 'P0001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'P0001').waitFor();

    const pageSizeTrigger = page.locator('[data-table-pagination]').getByRole('combobox');
    const pageSizeMetrics = await pageSizeTrigger.evaluate(element => ({
      width: element.getBoundingClientRect().width,
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
    }));
    if (pageSizeMetrics.width < 80 || pageSizeMetrics.scrollWidth > pageSizeMetrics.clientWidth) {
      throw new Error(`每页条数下拉框宽度不足：${JSON.stringify(pageSizeMetrics)}`);
    }

    await page.getByRole('button', { name: '新增产品' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增产品' });
    await assertRequiredLabels(createDialog, ['产品名称', '单位名称', '启用状态']);
    const productCodeDisplay = createDialog.locator('[data-product-code-display]');
    if (await productCodeDisplay.inputValue() !== '保存后由系统生成' || !await productCodeDisplay.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('新增产品的产品编码必须由系统生成并以只读方式提示');
    }
    if (!(await createDialog.innerText()).includes('系统生成，创建后不可修改')) {
      throw new Error('产品编码缺少系统生成和不可修改说明');
    }
    if (await createDialog.locator('[data-currency-prefix]').count() !== 2) {
      throw new Error('参考采购价和参考销售价必须显示人民币前缀');
    }

    const formSelects = createDialog.getByRole('combobox');
    const categorySelect = formSelects.nth(0);
    const unitSelect = formSelects.nth(1);
    await categorySelect.click();
    if (await page.locator('[data-anchored-select-content][data-state="open"]').count() !== 1) {
      throw new Error('打开产品分类后应且仅应存在一个下拉弹层');
    }
    await page.keyboard.press('Escape');
    await page.locator('[data-anchored-select-content][data-state="open"]').waitFor({ state: 'hidden' });
    await unitSelect.click();
    await page.locator('[data-anchored-select-content][data-state="open"]').waitFor();
    const openDropdownCount = await page.locator('[data-anchored-select-content][data-state="open"]').count();
    if (openDropdownCount !== 1 || await categorySelect.getAttribute('aria-expanded') !== 'false' || await unitSelect.getAttribute('aria-expanded') !== 'true') {
      throw new Error(`下拉弹层未互斥：open=${openDropdownCount}`);
    }
    await page.keyboard.press('Escape');

    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    const emptyFormText = await createDialog.innerText();
    if (!emptyFormText.includes('请输入产品名称') || emptyFormText.includes('请输入产品编码')) {
      throw new Error('产品档案新增表单缺少必填校验');
    }
    const remarkInput = createDialog.getByPlaceholder('补充产品采购、销售或仓储注意事项');
    await remarkInput.click();
    await page.waitForTimeout(250);
    const remarkShadow = await remarkInput.evaluate(element => getComputedStyle(element).boxShadow);
    if (remarkShadow !== queryInputShadow || !remarkShadow.includes('10px')) {
      throw new Error(`产品备注与查询输入框焦点辉光不一致：查询=${queryInputShadow} 备注=${remarkShadow}`);
    }
    await page.screenshot({ path: 'smoke-product-remark-focus.png', fullPage: true });
    await createDialog.getByPlaceholder('请输入产品名称').fill('系统编码测试产品');
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    await createDialog.waitFor({ state: 'detached' });
    await page.getByText('产品已创建', { exact: true }).waitFor();
    await page.getByPlaceholder('请输入产品名称').first().fill('系统编码测试产品');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const uncategorizedRow = tableRow(page, 'P0016');
    await uncategorizedRow.waitFor();
    await uncategorizedRow.getByText('未分类', { exact: true }).waitFor();
    await clickResetAndAssertLoading(page);

    const referencedRow = tableRow(page, 'P0001');
    await referencedRow.getByRole('button', { name: '删除' }).click();
    const referencedDelete = page.getByRole('alertdialog', { name: '删除产品' });
    if (!(await referencedDelete.innerText()).includes('已被库存或业务单据引用的产品将无法删除')) {
      throw new Error('删除产品确认未说明业务引用保护');
    }
    await referencedDelete.getByRole('button', { name: '删除', exact: true }).click();
    await page.getByText('产品已被库存或业务单据引用，无法删除', { exact: true }).waitFor();
    await referencedRow.waitFor();

    const editableRow = tableRow(page, 'P0002');
    await editableRow.getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑产品' });
    const editCodeDisplay = editDialog.locator('[data-product-code-display]');
    if (await editCodeDisplay.inputValue() !== 'P0002' || !await editCodeDisplay.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('编辑产品时产品编码必须只读且保持原值');
    }
    await editDialog.getByLabel('停用').click();
    await editDialog.getByRole('button', { name: '保存', exact: true }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '确认停用产品' });
    const stopText = await stopDialog.innerText();
    if (!stopText.includes('不能用于新建采购单或销售单') || !stopText.includes('历史业务数据不受影响')) {
      throw new Error(`产品停用风险说明不完整：${stopText}`);
    }
    if (await page.locator('[data-slot="dialog-content"][inert]').count() !== 1) {
      throw new Error('停用确认出现后，底层编辑弹窗未冻结');
    }
    await stopDialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('dialog', { name: '编辑产品' }).getByRole('button', { name: '取消', exact: true }).click();

    const removableRow = tableRow(page, 'P0010');
    await removableRow.getByRole('button', { name: '删除' }).click();
    await page.getByRole('alertdialog', { name: '删除产品' }).getByRole('button', { name: '删除', exact: true }).click();
    await removableRow.waitFor({ state: 'detached' });

    await clickPaginationAndAssertLoading(page, '下一页');
    const disabledCategoryRow = tableRow(page, 'P0013');
    await disabledCategoryRow.getByRole('button', { name: '启用' }).click();
    await page.getByRole('alertdialog', { name: '启用产品' }).getByRole('button', { name: '启用', exact: true }).click();
    await page.getByText('停用分类下不能保存启用产品', { exact: true }).waitFor();
    await disabledCategoryRow.getByText('停用', { exact: true }).waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P0001').waitFor();
    const filterColumns = await page.locator('.filter-grid--products').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`产品档案筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-product-products-1115.png', fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P0001').waitFor();
  },
});
