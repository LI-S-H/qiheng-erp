const { runSmoke } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/roles',
  screenshot: 'smoke-system-role-actions.png',
  viewport: { width: 1440, height: 900 },
  async test(page) {
    await page.getByRole('heading', { name: '角色管理' }).waitFor();

    const trigger = page.locator('[data-row-actions-trigger]').first();
    await trigger.waitFor();
    const triggerAttributes = await trigger.evaluate(element => ({
      text: element.textContent?.trim(),
      title: element.getAttribute('title'),
    }));
    if (triggerAttributes.text !== '更多' || triggerAttributes.title !== null) {
      throw new Error(`角色行更多触发器未遵循统一样式：${JSON.stringify(triggerAttributes)}`);
    }

    await trigger.click();
    const menu = page.locator('[role="menu"][data-state="open"]');
    for (const label of ['查看权限', '权限配置', '删除角色']) {
      await menu.getByRole('menuitem', { name: label, exact: true }).waitFor();
    }

    await menu.getByRole('menuitem', { name: '查看权限', exact: true }).click();
    const preview = page.locator('[role="dialog"]', {
      has: page.locator('[data-permission-preview-sidebar]'),
    });
    await preview.waitFor();
    const moduleButtons = preview.locator('[data-permission-preview-sidebar] button');
    if (await moduleButtons.count() < 1) {
      throw new Error('权限矩阵未展示可切换的模块列表');
    }
    await preview.locator('[data-permission-preview-content]').waitFor();
    if (await moduleButtons.count() > 1) {
      await moduleButtons.nth(1).click();
      await preview.locator('[data-permission-preview-content] [data-overflow-tooltip]').first().waitFor();
    }
    await page.screenshot({ path: 'smoke-system-role-permission-matrix.png' });
    await preview.getByRole('button', { name: '关闭', exact: true }).click();
    await preview.waitFor({ state: 'hidden' });

    await trigger.click();
    await page.locator('[role="menu"][data-state="open"]')
      .getByRole('menuitem', { name: '权限配置', exact: true })
      .click();
    const config = page.getByRole('dialog', { name: '权限配置' });
    await config.waitFor();
    await page.keyboard.press('Escape');
    await config.waitFor({ state: 'hidden' });

    const overflowingText = page.locator('[data-overflow-tooltip][data-overflowing="true"]').first();
    if (await overflowingText.count()) {
      const fullText = await overflowingText.textContent();
      await overflowingText.hover();
      await page.locator('[data-slot="tooltip-content"]').filter({ hasText: fullText || '' }).waitFor();
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 角色更多菜单、权限入口与长文本提示通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
