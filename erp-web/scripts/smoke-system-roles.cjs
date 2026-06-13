const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, clickQueryAndAssertLoading } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/roles',
  screenshot: 'smoke-system-roles.png',
  viewport: { width: 1115, height: 520 },
  async test(page) {
    await page.getByRole('heading', { name: '角色管理' }).waitFor();
    await assertFixedTableLayout(page, 8);
    await page.getByText('SUPER_ADMIN').waitFor();
    await page.getByText('业务主管').waitFor();

    await page.getByPlaceholder('如 SUPER_ADMIN').fill('BUSINESS_MANAGER');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SUPER_ADMIN').waitFor({ state: 'detached' });
    await tableRow(page, 'BUSINESS_MANAGER').waitFor();
    if (await tableRow(page, 'SUPER_ADMIN').count()) throw new Error('角色编码筛选未生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const statusTrigger = page.locator('.filter-panel').getByRole('combobox');
    await statusTrigger.click();
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('停用', { exact: true }).click();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'SUPER_ADMIN').waitFor({ state: 'detached' });
    await tableRow(page, 'AI_ANALYST').waitFor();
    if (await tableRow(page, 'SUPER_ADMIN').count()) throw new Error('角色停用状态筛选未生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const roleButtonTypography = await page.evaluate(() => [...document.querySelectorAll('[data-slot="button"]')]
      .filter(element => element.getAttribute('role') !== 'combobox' && element.textContent?.trim() && element.getBoundingClientRect().width > 0)
      .map(element => ({ text: element.textContent.trim(), fontSize: getComputedStyle(element).fontSize, fontWeight: getComputedStyle(element).fontWeight })));
    const inconsistentRoleButton = roleButtonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '400');
    if (inconsistentRoleButton) throw new Error(`角色页按钮文字未统一：${JSON.stringify(inconsistentRoleButton)}`);

    await page.setViewportSize({ width: 1115, height: 520 });
    await page.getByRole('button', { name: '新增角色' }).click();
    const createRoleDialog = page.getByRole('dialog', { name: '新增角色' });
    await createRoleDialog.waitFor();
    await assertRequiredLabels(createRoleDialog, ['角色编码', '角色名称', '启用状态', '权限码']);
    await createRoleDialog.getByRole('button', { name: '保存', exact: true }).click();
    const roleValidationText = await createRoleDialog.innerText();
    for (const message of ['请输入角色编码', '请输入角色名称', '请选择权限码']) {
      if (!roleValidationText.includes(message)) throw new Error(`新增角色缺少必填校验提示：${message}`);
    }
    const roleDialogFrame = await createRoleDialog.evaluate(element => ({
      clientHeight: element.clientHeight,
      display: getComputedStyle(element).display,
      maxHeight: getComputedStyle(element).maxHeight,
      overflowY: getComputedStyle(element).overflowY,
    }));
    const roleScrollRoot = await createRoleDialog.locator('[data-slot="scroll-area"]').first().evaluate(element => ({
      clientHeight: element.clientHeight,
      flex: getComputedStyle(element).flex,
      minHeight: getComputedStyle(element).minHeight,
    }));
    const roleDialogScroll = await createRoleDialog.locator('[data-slot="scroll-area-viewport"]').first().evaluate(element => {
      element.scrollTop = element.scrollHeight;
      return {
        tagName: element.tagName,
        className: element.className,
        inlineStyle: element.getAttribute('style'),
        parentHeight: element.parentElement?.clientHeight,
        clientHeight: element.clientHeight,
        scrollHeight: element.scrollHeight,
        scrollable: element.scrollHeight > element.clientHeight,
        reachedBottom: element.scrollTop > 0,
      };
    });
    if (!roleDialogScroll.scrollable || !roleDialogScroll.reachedBottom) {
      throw new Error(`角色长弹窗内部滚动未生效：${JSON.stringify({ roleDialogFrame, roleScrollRoot, roleDialogScroll })}`);
    }
    await page.screenshot({ path: 'smoke-role-dialog.png', fullPage: true });
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 1440, height: 900 });

    const adminRow = tableRow(page, 'SUPER_ADMIN');
    await adminRow.getByRole('button', { name: '编辑' }).click();
    await page.getByRole('dialog', { name: '编辑角色' }).waitFor();
    await page.keyboard.press('Escape');

    await adminRow.getByRole('button', { name: '权限配置' }).click();
    await page.getByRole('dialog', { name: '权限配置' }).waitFor();
    await page.keyboard.press('Escape');

    await adminRow.getByRole('button', { name: /查看明细/ }).click();
    const previewDialog = page.getByRole('dialog', { name: '权限码明细' });
    await previewDialog.waitFor();
    await page.getByText('该角色使用全部权限通配符').waitFor();
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await previewDialog.waitFor({ state: 'hidden' });

    await adminRow.getByRole('checkbox').click();
    await page.getByText('已选 1 项').waitFor();
    await page.getByRole('button', { name: '批量停用' }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '批量停用' });
    await stopDialog.waitFor();
    await stopDialog.getByRole('button', { name: '取消' }).click();
    await stopDialog.waitFor({ state: 'hidden' });

    const summaryItems = page.locator('.summary-strip .summary-item');
    if (await summaryItems.count() !== 4) throw new Error('角色摘要栏未按统一结构渲染');
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '角色管理' }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 角色管理页面交互通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
