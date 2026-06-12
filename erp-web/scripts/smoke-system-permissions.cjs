const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/permissions',
  screenshot: 'smoke-system-permissions.png',
  viewport: { width: 1115, height: 838 },
  async test(page) {
    await page.getByRole('heading', { name: '权限码配置' }).waitFor();
    await page.getByText('system:user:query', { exact: true }).waitFor();
    await assertFixedTableLayout(page, 9);

    const filterComboboxes = page.locator('.filter-panel').getByRole('combobox');
    await filterComboboxes.nth(0).click();
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('智能助手', { exact: true }).click();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'system:user:query').waitFor({ state: 'detached' });
    await tableRow(page, 'ai:query:stock').waitFor();
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await tableRow(page, 'system:user:query').waitFor();

    const buttonTypography = await page.evaluate(() => [...document.querySelectorAll('[data-slot="button"]')]
      .filter(element => element.getAttribute('role') !== 'combobox' && element.textContent?.trim() && element.getBoundingClientRect().width > 0)
      .map(element => ({ text: element.textContent.trim(), fontSize: getComputedStyle(element).fontSize, fontWeight: getComputedStyle(element).fontWeight })));
    const inconsistentButton = buttonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '400');
    if (inconsistentButton) throw new Error(`权限码页按钮文字未统一：${JSON.stringify(inconsistentButton)}`);

    const pagination = page.locator('[data-table-pagination]');
    await pagination.getByRole('combobox').waitFor();
    const paginationAlignment = await pagination.evaluate(element => {
      const center = element.children[1].getBoundingClientRect();
      const container = element.getBoundingClientRect();
      return Math.abs((center.left + center.right) / 2 - (container.left + container.right) / 2);
    });
    if (paginationAlignment > 2) throw new Error(`分页组件未居中，偏移 ${paginationAlignment}px`);

    const readColumnWidths = () => page.locator('[data-slot="table"]').first().locator('thead th')
      .evaluateAll(cells => cells.map(cell => Math.round(cell.getBoundingClientRect().width * 10) / 10));
    const firstPageWidths = await readColumnWidths();
    await pagination.getByText('下一页', { exact: true }).click();
    await page.getByText('purchase:create', { exact: true }).waitFor();
    const secondPageWidths = await readColumnWidths();
    if (firstPageWidths.some((width, index) => Math.abs(width - secondPageWidths[index]) > 0.5)) {
      throw new Error(`权限码翻页后列宽变化：第一页=${firstPageWidths.join(',')} 第二页=${secondPageWidths.join(',')}`);
    }
    await pagination.getByText('上一页', { exact: true }).click();
    await page.getByText('system:user:query', { exact: true }).waitFor();

    await page.getByRole('button', { name: '新增权限码' }).click();
    const dialog = page.getByRole('dialog', { name: '新增权限码' });
    await assertRequiredLabels(dialog, ['权限码', '权限名称', '所属模块', '操作类型', '启用状态']);
    await dialog.getByRole('button', { name: '保存', exact: true }).click();
    const emptyValidation = await dialog.innerText();
    for (const message of ['请输入权限码', '请输入权限名称', '请选择所属模块']) {
      if (!emptyValidation.includes(message)) throw new Error(`新增权限码缺少必填校验：${message}`);
    }
    await dialog.getByPlaceholder('例如 system:user:query').fill('INVALID CODE');
    await dialog.getByPlaceholder('请输入权限名称').fill('测试权限');
    await dialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await dialog.innerText()).includes('使用小写字母、数字和冒号分段')) {
      throw new Error('权限码格式校验未生效');
    }
    const dialogOverflow = await dialog.evaluate(element => ({
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      overflowY: getComputedStyle(element).overflowY,
    }));
    if (dialogOverflow.scrollHeight > dialogOverflow.clientHeight && dialogOverflow.overflowY === 'hidden') {
      throw new Error('权限码弹窗内容超高时无法滚动');
    }
    await page.keyboard.press('Escape');

    await tableRow(page, 'system:user:query').getByRole('button', { name: '删除' }).click();
    await page.getByText(/已被 2 个角色引用/).waitFor();

    await tableRow(page, 'system:user:query').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量停用' }).click();
    const confirm = page.getByRole('alertdialog', { name: '批量停用' });
    await confirm.waitFor();
    await confirm.getByRole('button', { name: '停用', exact: true }).click();
    await tableRow(page, 'system:user:query').getByText('停用', { exact: true }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 权限码筛选、校验、引用保护、批量状态与分页通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
