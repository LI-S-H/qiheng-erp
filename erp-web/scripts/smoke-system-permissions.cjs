const path = require('node:path');
const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, clickQueryAndAssertLoading, clickPaginationAndAssertLoading, clickRefreshAndAssertLoading, clickResetAndAssertLoading } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/permissions',
  screenshot: 'smoke-system-permissions.png',
  viewport: { width: 1115, height: 838 },
  async test(page) {
    await page.getByRole('heading', { name: '权限码配置' }).waitFor();
    await page.getByText('system:user:query', { exact: true }).waitFor();
    await assertFixedTableLayout(page, 10);
    await clickRefreshAndAssertLoading(page, 'smoke-system-permissions-refresh-loading.png');

    const filterComboboxes = page.locator('.filter-panel').getByRole('combobox');
    await filterComboboxes.nth(0).click();
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('智能助手', { exact: true }).click();
    await clickQueryAndAssertLoading(page, path.resolve(__dirname, '..', 'smoke-query-loading.png'));
    await tableRow(page, 'system:user:query').waitFor({ state: 'detached' });
    await tableRow(page, 'ai:query:stock').waitFor();
    await clickResetAndAssertLoading(page, 'smoke-system-permissions-reset-loading.png');
    await tableRow(page, 'system:user:query').waitFor();

    await page.getByPlaceholder('如 product:query').fill('system:user:query');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'system:user:query').waitFor();
    await tableRow(page, 'system:user:manage').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);
    await page.getByPlaceholder('请输入权限名称').first().fill('用户查询');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'system:user:query').waitFor();
    await tableRow(page, 'system:role:query').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

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
    await clickPaginationAndAssertLoading(page, '下一页');
    await page.getByText('purchase:create', { exact: true }).waitFor();
    const secondPageWidths = await readColumnWidths();
    if (firstPageWidths.some((width, index) => Math.abs(width - secondPageWidths[index]) > 0.5)) {
      throw new Error(`权限码翻页后列宽变化：第一页=${firstPageWidths.join(',')} 第二页=${secondPageWidths.join(',')}`);
    }
    await clickPaginationAndAssertLoading(page, '上一页');
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

    await tableRow(page, 'system:user:query').getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑权限码' });
    const editDialogContent = page.locator('[data-slot="dialog-content"]').last();
    await editDialog.getByLabel('停用').click();
    await editDialog.getByRole('button', { name: '保存', exact: true }).click();
    const editStopConfirm = page.getByRole('alertdialog', { name: '确认停用权限码' });
    await editStopConfirm.waitFor();
    const editStopWarning = await editStopConfirm.innerText();
    for (const message of ['已与角色关联', '绑定这些角色的用户', '无法执行对应操作', '权限会话']) {
      if (!editStopWarning.includes(message)) throw new Error(`权限码停用说明缺少影响信息：${message}`);
    }
    if (!(await editDialogContent.evaluate(element => element.hasAttribute('inert')))) {
      throw new Error('警告弹窗打开时，底层编辑弹窗未进入 inert 状态');
    }
    const modalLayerState = await page.evaluate(() => {
      const alert = document.querySelector('[data-slot="alert-dialog-content"]');
      const alertOverlay = document.querySelector('[data-slot="alert-dialog-overlay"]');
      const dialog = document.querySelector('[data-slot="dialog-content"]');
      const input = dialog?.querySelector('input:not([disabled])');
      if (!alert || !alertOverlay || !dialog || !input) return null;
      const inputRect = input.getBoundingClientRect();
      const hit = document.elementFromPoint(inputRect.left + inputRect.width / 2, inputRect.top + inputRect.height / 2);
      return {
        alertZIndex: Number(getComputedStyle(alert).zIndex),
        overlayZIndex: Number(getComputedStyle(alertOverlay).zIndex),
        dialogZIndex: Number(getComputedStyle(dialog).zIndex),
        overlayPointerEvents: getComputedStyle(alertOverlay).pointerEvents,
        hitEditDialog: hit === dialog || dialog.contains(hit),
      };
    });
    if (!modalLayerState
      || modalLayerState.overlayPointerEvents !== 'auto'
      || modalLayerState.overlayZIndex <= modalLayerState.dialogZIndex
      || modalLayerState.alertZIndex <= modalLayerState.overlayZIndex
      || modalLayerState.hitEditDialog) {
      throw new Error(`警告弹窗未完全阻断底层编辑弹窗：${JSON.stringify(modalLayerState)}`);
    }
    const footerSpacing = await editStopConfirm.locator('[data-confirm-dialog-footer]').evaluate((footer) => {
      const button = footer.querySelector('button');
      if (!button) return null;
      const footerRect = footer.getBoundingClientRect();
      const buttonRect = button.getBoundingClientRect();
      return {
        top: Math.round(buttonRect.top - footerRect.top),
        bottom: Math.round(footerRect.bottom - buttonRect.bottom),
      };
    });
    if (!footerSpacing || footerSpacing.top < 18 || footerSpacing.bottom < 18) {
      throw new Error(`确认弹窗按钮区上下留白不足：${JSON.stringify(footerSpacing)}`);
    }
    await editStopConfirm.screenshot({ path: path.resolve(__dirname, '..', 'smoke-permission-stop-confirm.png') });
    await editStopConfirm.getByRole('button', { name: '取消', exact: true }).click();
    await editDialog.getByPlaceholder('请输入权限名称').click();
    await editDialog.getByRole('button', { name: '取消', exact: true }).click();

    await tableRow(page, 'system:user:query').getByRole('checkbox').click();
    await page.getByRole('button', { name: '批量停用' }).click();
    const confirm = page.getByRole('alertdialog', { name: '批量停用' });
    await confirm.waitFor();
    const batchStopWarning = await confirm.innerText();
    if (!batchStopWarning.includes('绑定这些角色的用户') || !batchStopWarning.includes('权限会话')) {
      throw new Error(`批量停用未说明角色和用户影响：${batchStopWarning}`);
    }
    await confirm.getByRole('button', { name: '停用', exact: true }).click();
    await tableRow(page, 'system:user:query').getByText('停用', { exact: true }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 权限码筛选、校验、引用保护、批量状态与分页通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
