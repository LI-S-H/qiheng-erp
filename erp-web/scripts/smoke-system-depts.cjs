const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/depts',
  screenshot: 'smoke-system-depts.png',
  async test(page) {
    await page.getByRole('heading', { name: '部门管理' }).waitFor();
    await assertFixedTableLayout(page, 7);
    await page.getByText('无上级部门').first().waitFor();
    await page.getByText('采购跟单组', { exact: true }).waitFor();
    await page.getByText('采购部 / 采购跟单组', { exact: true }).waitFor();

    const treeToggle = page.getByRole('button', { name: '收起当前部门' }).first();
    const toggleStyle = await treeToggle.evaluate(element => {
      const style = getComputedStyle(element);
      return { background: style.backgroundColor, border: style.borderTopWidth };
    });
    if (toggleStyle.background !== 'rgba(0, 0, 0, 0)' || toggleStyle.border !== '0px') {
      throw new Error('部门树展开控件必须保持纯箭头、无底色和边框');
    }

    await page.getByPlaceholder('如 采购部').fill('采购部');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, '行政部').waitFor({ state: 'detached' });
    await tableRow(page, '采购部').waitFor();
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const statusTrigger = page.locator('.filter-panel').getByRole('combobox');
    await statusTrigger.click();
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('停用', { exact: true }).click();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, '行政部').waitFor({ state: 'detached' });
    await tableRow(page, '华南销售组').waitFor();
    if (await tableRow(page, '行政部').count()) throw new Error('部门停用状态筛选未生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const deptButtonTypography = await page.evaluate(() => [...document.querySelectorAll('[data-slot="button"]')]
      .filter(element => element.getAttribute('role') !== 'combobox' && element.textContent?.trim() && element.getBoundingClientRect().width > 0)
      .map(element => ({ text: element.textContent.trim(), fontSize: getComputedStyle(element).fontSize, fontWeight: getComputedStyle(element).fontWeight })));
    const inconsistentDeptButton = deptButtonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '400');
    if (inconsistentDeptButton) throw new Error(`部门页按钮文字未统一：${JSON.stringify(inconsistentDeptButton)}`);

    await page.getByRole('button', { name: '新增部门' }).click();
    const createDeptDialog = page.getByRole('dialog', { name: '新增部门' });
    await assertRequiredLabels(createDeptDialog, ['上级部门', '部门名称', '状态']);
    await createDeptDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await createDeptDialog.innerText()).includes('请输入部门名称')) {
      throw new Error('新增部门缺少部门名称必填校验');
    }
    await page.keyboard.press('Escape');

    const purchaseRow = tableRow(page, '采购部');
    await purchaseRow.getByRole('checkbox').click();
    await page.getByText('已选 3 项').waitFor();
    await page.getByRole('button', { name: '批量停用' }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '批量停用' });
    await stopDialog.waitFor();
    const layerState = await stopDialog.evaluate(element => ({
      dialogZIndex: getComputedStyle(element).zIndex,
      overlayZIndex: getComputedStyle(document.querySelector('[data-slot="alert-dialog-overlay"]')).zIndex,
    }));
    if (Number(layerState.dialogZIndex) <= Number(layerState.overlayZIndex)) {
      throw new Error(`确认框层级错误：content=${layerState.dialogZIndex}, overlay=${layerState.overlayZIndex}`);
    }
    await page.getByRole('button', { name: '停用', exact: true }).click();
    await tableRow(page, '采购部').getByText('停用').waitFor();
    await tableRow(page, '供应商维护组').getByText('停用').waitFor();
    await tableRow(page, '采购跟单组').getByText('停用').waitFor();

    await tableRow(page, '采购跟单组').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量启用' }).click();
    await page.getByText(/请先启用.*采购部/).waitFor();
    if (await page.getByRole('alertdialog', { name: '批量启用' }).count()) {
      throw new Error('上级停用时不应允许单独启用下级部门');
    }

    await tableRow(page, '采购部').getByRole('button', { name: '删除' }).click();
    await page.getByText('该部门存在下级部门，请先调整层级').waitFor();
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '部门管理' }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 部门树、级联状态与删除约束通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
