const { chromium } = require('playwright');

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1366, height: 860 } });
  const errors = [];

  page.on('console', message => {
    if (['error', 'warning'].includes(message.type())) {
      errors.push(`[console:${message.type()}] ${message.text()}`);
    }
  });
  page.on('pageerror', error => {
    errors.push(`[pageerror] ${error.message}`);
  });

  try {
    await page.goto('http://127.0.0.1:5173/system/roles', { waitUntil: 'domcontentloaded' });

    if (page.url().includes('/login')) {
      await page.locator('input').nth(0).fill('admin');
      await page.locator('input').nth(1).fill('123456');
      await page.getByRole('button', { name: /登录|进入/ }).click();
      await page.waitForURL(/dashboard|system\/roles/, { timeout: 10000 });
      await page.goto('http://127.0.0.1:5173/system/roles', { waitUntil: 'domcontentloaded' });
    }

    await page.getByRole('heading', { name: '角色管理' }).waitFor({ timeout: 10000 });
    await page.getByText('SUPER_ADMIN').waitFor({ timeout: 10000 });
    await page.getByText('业务主管').waitFor({ timeout: 10000 });
    await page.getByRole('columnheader', { name: '绑定用户数' }).waitFor({ timeout: 10000 });
    await page.getByRole('cell', { name: '1' }).first().waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '权限配置' }).first().waitFor({ timeout: 10000 });

    await page.getByPlaceholder('如 SUPER_ADMIN').fill('BUSINESS_MANAGER');
    await page.getByRole('button', { name: '查询' }).click();
    await page.getByRole('cell', { name: /BUSINESS_MANAGER/ }).waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const filterSelect = page.locator('.filter-panel .el-select').first();
    await filterSelect.click();
    await page.getByRole('option', { name: '停用' }).click();
    await page.getByRole('button', { name: '查询' }).click();
    await page.getByRole('cell', { name: /AI_ANALYST/ }).waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: '重置', exact: true }).click();

    await page.getByRole('button', { name: '新增角色' }).click();
    await page.getByRole('dialog', { name: '新增角色' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    const firstRowActions = page.locator('.row-actions').first();
    await firstRowActions.getByRole('button', { name: '编辑' }).click();
    await page.getByRole('dialog', { name: '编辑角色' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    await firstRowActions.getByRole('button', { name: '权限配置' }).click();
    await page.getByRole('dialog', { name: '权限配置' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: '查看明细' }).first().click();
    await page.getByRole('dialog', { name: '权限码明细' }).waitFor({ timeout: 5000 });
    await page.getByText('该角色使用全部权限通配符').waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: '关闭', exact: true }).click();

    await page.locator('.el-table__body-wrapper .el-checkbox').first().click();
    await page.getByRole('button', { name: '批量停用' }).click();
    await page.getByRole('dialog', { name: '批量停用' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');
    await page.mouse.move(40, 40);
    await page.waitForTimeout(300);

    const paginationBarBox = await page.locator('.pagination-bar').boundingBox();
    const paginationTotalBox = await page.locator('.pagination-total').boundingBox();
    const paginationSizesBox = await page.locator('.pagination-sizes').boundingBox();
    const paginationPagerBox = await page.locator('.pagination-pager').boundingBox();
    const paginationJumperBox = await page.locator('.pagination-jumper').boundingBox();
    if (!paginationBarBox || !paginationTotalBox || !paginationSizesBox || !paginationPagerBox || !paginationJumperBox) {
      throw new Error('角色分页区域布局没有正常渲染');
    }

    const paginationCenter = paginationBarBox.x + paginationBarBox.width / 2;
    const pagerCenter = paginationPagerBox.x + paginationPagerBox.width / 2;
    const sizesToPagerGap = paginationPagerBox.x - (paginationSizesBox.x + paginationSizesBox.width);
    const jumperRight = paginationJumperBox.x + paginationJumperBox.width;
    const toolbarBox = await page.locator('.table-toolbar').boundingBox();
    const toolbarActionsBox = await page.locator('.table-toolbar__actions').boundingBox();
    if (!toolbarBox || !toolbarActionsBox) {
      throw new Error('角色表格工具栏没有正常渲染');
    }
    if (toolbarBox.x + toolbarBox.width - (toolbarActionsBox.x + toolbarActionsBox.width) > toolbarBox.width * 0.04) {
      throw new Error('角色表格工具栏操作按钮需要保持右对齐');
    }
    if (paginationTotalBox.x > paginationBarBox.x + paginationBarBox.width * 0.25) {
      throw new Error('角色分页总数没有放在偏左位置');
    }
    if (sizesToPagerGap < 8 || sizesToPagerGap > paginationBarBox.width * 0.08) {
      throw new Error('角色每页条数选择需要放在页码控件左边并保持合适距离');
    }
    if (Math.abs(pagerCenter - paginationCenter) > paginationBarBox.width * 0.04) {
      throw new Error('角色页码控件需要放在分页区域中间');
    }
    if (paginationBarBox.x + paginationBarBox.width - jumperRight > paginationBarBox.width * 0.08) {
      throw new Error('角色跳页控件需要靠右显示');
    }

    const rowActionIconCount = await page.locator('.row-actions .el-icon').count();
    if (rowActionIconCount !== 0) {
      throw new Error('角色行内编辑和权限配置按钮不应显示图标');
    }
    const foldedPermissionCount = await page.locator('.permission-summary .el-tag', { hasText: /^\+\d+$/ }).count();
    if (foldedPermissionCount !== 0) {
      throw new Error('权限码需要完整展示，不能使用 +N 折叠标记');
    }
    const permissionTagStyle = await page.locator('.permission-summary .el-tag').first().evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        color: style.color,
        backgroundColor: style.backgroundColor,
        borderColor: style.borderTopColor,
        minWidth: style.minWidth,
        height: element.getBoundingClientRect().height,
        fontSize: style.fontSize,
      };
    });
    if (
      permissionTagStyle.color !== 'rgb(20, 88, 212)' ||
      permissionTagStyle.backgroundColor !== 'rgb(231, 240, 255)' ||
      permissionTagStyle.borderColor !== 'rgb(91, 149, 255)' ||
      permissionTagStyle.minWidth !== '102px' ||
      permissionTagStyle.height < 31 ||
      permissionTagStyle.height > 34 ||
      permissionTagStyle.fontSize !== '13px'
    ) {
      throw new Error('角色管理权限码标签需要使用用户管理角色标签同款蓝色框');
    }

    const activeUserCountStyle = await page.locator('.user-count-tag').first().evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        color: style.color,
        backgroundColor: style.backgroundColor,
        borderColor: style.borderTopColor,
      };
    });
    if (
      activeUserCountStyle.color !== 'rgb(47, 111, 237)' ||
      activeUserCountStyle.backgroundColor !== 'rgb(244, 248, 255)' ||
      activeUserCountStyle.borderColor !== 'rgb(185, 210, 255)'
    ) {
      throw new Error('绑定用户数大于 0 时需要使用编辑按钮同款蓝色框');
    }

    const emptyUserCountStyle = await page.locator('.user-count-tag.is-empty').first().evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        color: style.color,
        backgroundColor: style.backgroundColor,
        borderColor: style.borderTopColor,
      };
    });
    if (
      emptyUserCountStyle.color !== 'rgb(139, 149, 165)' ||
      emptyUserCountStyle.backgroundColor !== 'rgb(255, 255, 255)' ||
      emptyUserCountStyle.borderColor !== 'rgb(215, 221, 231)'
    ) {
      throw new Error('绑定用户数为 0 时需要使用灰色空状态框');
    }

    const actionButtonStyle = await firstRowActions.getByRole('button', { name: '权限配置' }).evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        color: style.color,
        backgroundColor: style.backgroundColor,
      };
    });
    if (actionButtonStyle.color !== 'rgb(217, 74, 74)' || actionButtonStyle.backgroundColor !== 'rgb(255, 242, 242)') {
      throw new Error('权限配置按钮需要使用浅红色操作风格');
    }

    await page.screenshot({ path: 'smoke-system-roles.png', fullPage: true });

    if (errors.length > 0) {
      throw new Error(errors.join('\n'));
    }

    console.log('SMOKE_OK: 角色管理页面渲染和基础按钮交互通过');
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
