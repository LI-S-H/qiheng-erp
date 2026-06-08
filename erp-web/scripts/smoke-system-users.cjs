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
    await page.goto('http://127.0.0.1:5173/system/users', { waitUntil: 'domcontentloaded' });

    if (page.url().includes('/login')) {
      await page.locator('input').nth(0).fill('admin');
      await page.locator('input').nth(1).fill('123456');
      await page.getByRole('button', { name: /登录|进入/ }).click();
      await page.waitForURL(/dashboard|system\/users/, { timeout: 10000 });
      await page.goto('http://127.0.0.1:5173/system/users', { waitUntil: 'domcontentloaded' });
    }

    await page.getByRole('heading', { name: '用户管理' }).waitFor({ timeout: 10000 });
    await page.getByText('系统管理员').first().waitFor({ timeout: 10000 });
    await page.getByText('purchase01').waitFor({ timeout: 10000 });

    await page.getByPlaceholder('账号 姓名 部门').fill('admin');
    await page.getByRole('button', { name: '查询' }).click();
    await page.getByRole('cell', { name: /admin/ }).waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const filterSelects = page.locator('.filter-panel .el-select');
    await filterSelects.nth(1).click();
    await page.getByRole('option', { name: '超级管理员' }).click();
    await filterSelects.nth(2).click();
    await page.getByRole('option', { name: '启用' }).click();
    await page.getByRole('button', { name: '查询' }).click();
    await page.getByRole('cell', { name: /admin/ }).waitFor({ timeout: 5000 });
    await filterSelects.nth(2).hover();
    await filterSelects.nth(2).locator('.el-select__caret').last().click();
    await page.getByRole('button', { name: '查询' }).click();
    await page.getByRole('cell', { name: /admin/ }).waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: '重置', exact: true }).click();

    await page.getByRole('button', { name: '新增用户' }).click();
    await page.getByRole('dialog', { name: '新增用户' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    const firstRowActions = page.locator('.row-actions').first();

    await firstRowActions.getByRole('button', { name: '编辑' }).click();
    await page.getByRole('dialog', { name: '编辑用户' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    await firstRowActions.getByRole('button', { name: '角色绑定' }).click();
    await page.getByRole('dialog', { name: '角色绑定' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');

    await page.locator('.el-table__body-wrapper .el-checkbox').first().click();
    await page.getByRole('button', { name: '重置密码' }).click();
    await page.getByRole('dialog', { name: '批量重置密码' }).waitFor({ timeout: 5000 });
    await page.keyboard.press('Escape');
    await page.mouse.move(40, 40);
    await page.waitForTimeout(300);

    const rowActionIconCount = await page.locator('.row-actions .el-icon').count();
    if (rowActionIconCount !== 0) {
      throw new Error('行内编辑和角色绑定按钮不应显示图标');
    }

    const paginationBarBox = await page.locator('.pagination-bar').boundingBox();
    const paginationTotalBox = await page.locator('.pagination-total').boundingBox();
    const paginationSizesBox = await page.locator('.pagination-sizes').boundingBox();
    const paginationPagerBox = await page.locator('.pagination-pager').boundingBox();
    const paginationJumperBox = await page.locator('.pagination-jumper').boundingBox();
    if (!paginationBarBox || !paginationTotalBox || !paginationSizesBox || !paginationPagerBox || !paginationJumperBox) {
      throw new Error('分页区域布局没有正常渲染');
    }

    const paginationCenter = paginationBarBox.x + paginationBarBox.width / 2;
    const sizesCenter = paginationSizesBox.x + paginationSizesBox.width / 2;
    const pagerCenter = paginationPagerBox.x + paginationPagerBox.width / 2;
    const sizesToPagerGap = paginationPagerBox.x - (paginationSizesBox.x + paginationSizesBox.width);
    const jumperRight = paginationJumperBox.x + paginationJumperBox.width;
    const toolbarBox = await page.locator('.table-toolbar').boundingBox();
    const toolbarActionsBox = await page.locator('.table-toolbar__actions').boundingBox();
    if (!toolbarBox || !toolbarActionsBox) {
      throw new Error('表格工具栏没有正常渲染');
    }
    if (toolbarBox.x + toolbarBox.width - (toolbarActionsBox.x + toolbarActionsBox.width) > toolbarBox.width * 0.04) {
      throw new Error('表格工具栏操作按钮需要保持右对齐');
    }
    if (paginationTotalBox.x > paginationBarBox.x + paginationBarBox.width * 0.25) {
      throw new Error('分页总数没有放在偏左位置');
    }
    if (
      sizesCenter >= paginationCenter ||
      sizesToPagerGap < 8 ||
      sizesToPagerGap > paginationBarBox.width * 0.08
    ) {
      throw new Error('每页条数选择需要放在页码控件左边并保持合适距离');
    }
    if (Math.abs(pagerCenter - paginationCenter) > paginationBarBox.width * 0.04) {
      throw new Error('页码控件需要放回分页区域中间');
    }
    if (paginationBarBox.x + paginationBarBox.width - jumperRight > paginationBarBox.width * 0.08) {
      throw new Error('跳页控件需要靠右显示');
    }

    const firstRowCells = page.locator('.user-table .el-table__body-wrapper tbody tr.el-table__row').first().locator('td');
    const deptCellLayout = await firstRowCells.nth(2).locator('.cell').evaluate(element => ({
      justifyContent: window.getComputedStyle(element).justifyContent,
    }));
    if (deptCellLayout.justifyContent !== 'center') {
      throw new Error('部门列内容需要水平居中');
    }

    const roleCellBox = await firstRowCells.nth(3).boundingBox();
    const roleTagsLayout = await firstRowCells.nth(3).locator('.role-tags').evaluate(element => ({
      justifyContent: window.getComputedStyle(element).justifyContent,
    }));
    if (!roleCellBox || roleCellBox.width < 240 || roleCellBox.height > 70 || roleTagsLayout.justifyContent !== 'center') {
      throw new Error('角色列需要加宽、收紧行高并保持标签居中');
    }

    const roleTagStyle = await page.locator('.role-tags .el-tag').first().evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        display: style.display,
        alignItems: style.alignItems,
        justifyContent: style.justifyContent,
        width: element.getBoundingClientRect().width,
        height: element.getBoundingClientRect().height,
        fontSize: style.fontSize,
      };
    });
    if (
      !roleTagStyle.display.includes('flex') ||
      roleTagStyle.alignItems !== 'center' ||
      roleTagStyle.justifyContent !== 'center' ||
      roleTagStyle.width < 96 ||
      roleTagStyle.width > 112 ||
      roleTagStyle.height < 31 ||
      roleTagStyle.height > 34 ||
      roleTagStyle.fontSize !== '13px'
    ) {
      throw new Error('表格标签需要保持内容自适应并居中');
    }

    const roleButtonStyle = await firstRowActions.getByRole('button', { name: '角色绑定' }).evaluate(element => {
      const style = window.getComputedStyle(element);
      return {
        color: style.color,
        backgroundColor: style.backgroundColor,
        borderColor: style.borderColor,
      };
    });
    if (
      roleButtonStyle.color !== 'rgb(211, 205, 23)' ||
      roleButtonStyle.backgroundColor !== 'rgb(236, 241, 203)'
    ) {
      throw new Error('角色绑定按钮需要使用黄色浅底风格');
    }

    const bodyText = await page.locator('body').innerText();
    await page.screenshot({ path: 'smoke-system-users.png', fullPage: true });
    await browser.close();

    if (!bodyText.includes('用户管理') || !bodyText.includes('账号列表')) {
      throw new Error('页面关键内容没有渲染出来');
    }
    if (!bodyText.includes('共 4 条') || !bodyText.includes('条/页') || !bodyText.includes('前往')) {
      throw new Error('分页没有渲染为中文友好文案');
    }

    if (errors.length > 0) {
      throw new Error(errors.join('\n'));
    }

    console.log('SMOKE_OK: 用户管理页面渲染和基础按钮交互通过');
  } catch (error) {
    const bodyText = await page.locator('body').innerText().catch(() => '');
    await page.screenshot({ path: 'smoke-system-users-failed.png', fullPage: true }).catch(() => undefined);
    await browser.close();
    console.error('URL:', page.url());
    console.error('BODY:', bodyText.slice(0, 2000));
    console.error('BROWSER_ERRORS:', errors.join('\n') || 'none');
    throw error;
  }
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
