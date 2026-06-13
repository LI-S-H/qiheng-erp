const path = require('node:path');
const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, clickQueryAndAssertLoading, clickRefreshAndAssertLoading } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/product/categories',
  screenshot: 'smoke-product-categories.png',
  async test(page) {
    await page.getByRole('heading', { name: '产品分类' }).waitFor();
    await assertFixedTableLayout(page, 7);
    await page.getByText('无上级分类').first().waitFor();
    await page.getByText('饮料冲调', { exact: true }).waitFor();
    await page.getByText('食品饮料 / 饮料冲调', { exact: true }).waitFor();
    await clickRefreshAndAssertLoading(page, 'smoke-product-categories-refresh-loading.png');

    const treeToggle = page.getByRole('button', { name: '收起当前分类' }).first();
    const toggleStyle = await treeToggle.evaluate(element => {
      const style = getComputedStyle(element);
      return { background: style.backgroundColor, border: style.borderTopWidth };
    });
    if (toggleStyle.background !== 'rgba(0, 0, 0, 0)' || toggleStyle.border !== '0px') {
      throw new Error('分类树展开控件必须保持纯箭头、无底色和边框');
    }

    await page.getByPlaceholder('如 食品饮料').fill('办公用品');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, '食品饮料').waitFor({ state: 'detached' });
    await tableRow(page, '办公用品').waitFor();
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await tableRow(page, '书写文具').waitFor();

    const statusTrigger = page.locator('.filter-panel').getByRole('combobox');
    await statusTrigger.click();
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('停用', { exact: true }).click();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, '办公用品').waitFor({ state: 'detached' });
    await tableRow(page, '电子配件').waitFor();
    await tableRow(page, '电脑周边').waitFor();
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const buttonTypography = await page.evaluate(() => [...document.querySelectorAll('[data-slot="button"]')]
      .filter(element => element.getAttribute('role') !== 'combobox' && element.textContent?.trim() && element.getBoundingClientRect().width > 0)
      .map(element => ({ text: element.textContent.trim(), fontSize: getComputedStyle(element).fontSize, fontWeight: getComputedStyle(element).fontWeight })));
    const inconsistentButton = buttonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '400');
    if (inconsistentButton) throw new Error(`分类页按钮文字未统一：${JSON.stringify(inconsistentButton)}`);

    await page.getByRole('button', { name: '新增分类' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增分类' });
    const parentCategorySelect = createDialog.getByRole('combobox').first();
    await parentCategorySelect.click();
    const categoryDropdown = page.locator('[data-tree-select-content][data-state="open"]');
    await categoryDropdown.waitFor();
    const dropdownLayer = await categoryDropdown.evaluate(element => Number(getComputedStyle(element).zIndex));
    const dialogLayer = await createDialog.evaluate(element => Number(getComputedStyle(element).zIndex));
    if (dropdownLayer <= dialogLayer) throw new Error(`分类下拉层级必须高于业务弹窗：下拉=${dropdownLayer} 弹窗=${dialogLayer}`);
    const dropdownScrollbar = categoryDropdown.locator('[data-slot="scroll-area-scrollbar"]');
    if (await dropdownScrollbar.count()) {
      const dropdownScrollbarBox = await dropdownScrollbar.boundingBox();
      if (dropdownScrollbarBox && dropdownScrollbarBox.width > 6) {
        throw new Error(`分类下拉滚动条应为细样式，当前宽度为 ${dropdownScrollbarBox.width}`);
      }
    }
    await page.waitForTimeout(180);
    await page.screenshot({ path: 'smoke-product-category-thin-scrollbar.png', fullPage: true });
    await page.keyboard.press('Escape');
    await categoryDropdown.waitFor({ state: 'detached' });
    await assertRequiredLabels(createDialog, ['上级分类', '分类名称', '状态']);
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await createDialog.innerText()).includes('请输入分类名称')) throw new Error('新增分类缺少分类名称必填校验');
    await createDialog.getByPlaceholder('请输入分类名称').fill('食品饮料');
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await createDialog.innerText()).includes('同级分类名称已存在')) throw new Error('新增分类缺少同级重名校验');
    await createDialog.getByRole('button', { name: '取消', exact: true }).click();
    await createDialog.waitFor({ state: 'detached' });

    const foodRow = tableRow(page, '食品饮料');
    await foodRow.getByRole('checkbox').click();
    await page.getByText('已选 3 项').waitFor();
    await page.getByRole('button', { name: '批量停用' }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '批量停用' });
    await stopDialog.waitFor();
    const stopWarning = await stopDialog.innerText();
    if (!stopWarning.includes('下级分类会同步停用') || !stopWarning.includes('关联产品也将停用') || !stopWarning.includes('采购或销售')) {
      throw new Error(`分类停用警告未说明级联和产品影响：${stopWarning}`);
    }
    if (/\d+\s*个下级分类|\d+\s*个关联产品/.test(stopWarning)) {
      throw new Error(`分类停用不应由前端计算精确影响数量：${stopWarning}`);
    }
    if (await stopDialog.locator('[data-confirm-dialog-icon]').count() !== 1) {
      throw new Error('分类停用确认弹窗缺少风险图标');
    }
    await stopDialog.screenshot({ path: path.resolve(__dirname, '..', 'smoke-product-category-stop-confirm.png') });
    await page.getByRole('button', { name: '停用', exact: true }).click();
    await tableRow(page, '食品饮料').getByText('停用').waitFor();
    await tableRow(page, '饮料冲调').getByText('停用').waitFor();

    await tableRow(page, '饮料冲调').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量启用' }).click();
    await page.getByText(/请先启用.*食品饮料/).waitFor();
    if (await page.getByRole('alertdialog', { name: '批量启用' }).count()) {
      throw new Error('上级停用时不应允许单独启用下级分类');
    }

    await tableRow(page, '食品饮料').getByRole('button', { name: '删除' }).click();
    await page.getByText('该分类存在下级分类，请先调整层级').waitFor();
    await tableRow(page, '书写文具').getByRole('button', { name: '删除' }).click();
    await page.getByText('该分类已关联产品，请先调整产品分类').waitFor();

    await tableRow(page, '食品饮料').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量启用' }).click();
    await page.getByRole('alertdialog', { name: '批量启用' }).getByRole('button', { name: '启用', exact: true }).click();
    await tableRow(page, '食品饮料').getByText('启用').waitFor();

    await tableRow(page, '办公用品').getByRole('button', { name: '编辑' }).click();
    const officeDialog = page.getByRole('dialog', { name: '编辑分类' });
    await officeDialog.getByLabel('停用').click();
    await officeDialog.getByRole('button', { name: '保存', exact: true }).click();
    const editStopDialog = page.getByRole('alertdialog', { name: '确认停用分类' });
    const editStopWarning = await editStopDialog.innerText();
    if (!editStopWarning.includes('下级分类会同步停用') || !editStopWarning.includes('关联产品也将停用')) {
      throw new Error(`编辑停用分类未展示完整影响：${editStopWarning}`);
    }
    if (/\d+\s*个下级分类|\d+\s*个关联产品/.test(editStopWarning)) {
      throw new Error(`编辑停用分类不应由前端计算精确影响数量：${editStopWarning}`);
    }
    await editStopDialog.getByRole('button', { name: '确认停用' }).click();
    await tableRow(page, '办公用品').getByText('停用').waitFor();
    await tableRow(page, '书写文具').getByText('停用').waitFor();
    await tableRow(page, '办公用品').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量启用' }).click();
    await page.getByRole('alertdialog', { name: '批量启用' }).getByRole('button', { name: '启用', exact: true }).click();
    await tableRow(page, '办公用品').getByText('启用').waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品分类' }).waitFor();
    const filterColumns = await page.locator('.filter-grid--depts').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`产品分类筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-product-categories-1115.png', fullPage: true });

    await page.setViewportSize({ width: 900, height: 420 });
    await tableRow(page, '电子配件').getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑分类' });
    await editDialog.waitFor();
    const dialogBounds = await editDialog.boundingBox();
    if (!dialogBounds || dialogBounds.y < 16 || dialogBounds.y + dialogBounds.height > 404) {
      throw new Error(`低高度视口下分类弹窗超出可访问区域：${JSON.stringify(dialogBounds)}`);
    }
    await editDialog.getByRole('button', { name: '保存', exact: true }).waitFor();
    await page.keyboard.press('Escape');

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品分类' }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 产品分类树、停用风险确认、级联状态与删除约束通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
