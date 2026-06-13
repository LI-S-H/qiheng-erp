const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, clickQueryAndAssertLoading } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/system/users',
  screenshot: 'smoke-system-users.png',
  async test(page) {
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
    await assertFixedTableLayout(page, 8);
    await page.getByText('系统管理员').first().waitFor();
    await page.getByText('purchase01').waitFor();

    const bodyText = await page.locator('body').innerText();
    if (bodyText.includes('总部') || !bodyText.includes('行政部')) {
      throw new Error('用户页部门名称必须与部门管理统一为“行政部”');
    }

    const bodyFontSize = await page.locator('body').evaluate(element => Number.parseFloat(getComputedStyle(element).fontSize));
    if (bodyFontSize !== 14) throw new Error(`页面正文基准字号应为 14px，当前为 ${bodyFontSize}px`);

    await page.setViewportSize({ width: 933, height: 838 });
    const filterGrid = page.locator('.filter-grid--users');
    const filterColumns = await filterGrid.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length);
    if (filterColumns !== 2) throw new Error('用户筛选区在中等宽度下未保持规整两列布局');

    const selectTriggers = filterGrid.getByRole('combobox');
    if (await selectTriggers.count() !== 3) throw new Error('用户筛选项必须使用统一下拉框组件');

    const typography = await page.evaluate(() => {
      const fontSize = (selector) => getComputedStyle(document.querySelector(selector)).fontSize;
      return {
        label: fontSize('.filter-grid--users [data-slot="label"]'),
        input: fontSize('.filter-grid--users [data-slot="input"]'),
        select: fontSize('.filter-grid--users [data-anchored-select-trigger]'),
        queryButton: fontSize('.filter-actions [data-slot="button"]'),
      };
    });
    if (typography.label !== '14px' || typography.input !== '14px' || typography.select !== '14px' || typography.queryButton !== '13px') {
      throw new Error(`筛选字段应为 14px、按钮应为 13px：${JSON.stringify(typography)}`);
    }

    const adminTypography = await tableRow(page, 'admin').evaluate(row => {
      const cells = row.querySelectorAll('td');
      const username = cells[1]?.querySelector('.font-medium');
      return {
        username: username ? getComputedStyle(username).fontSize : '',
        department: cells[2] ? getComputedStyle(cells[2]).fontSize : '',
      };
    });
    if (adminTypography.username !== '14px' || adminTypography.department !== '14px') {
      throw new Error(`表格主文字字号不一致：${JSON.stringify(adminTypography)}`);
    }

    const buttonTypography = await page.evaluate(() => [...document.querySelectorAll('[data-slot="button"]')]
      .filter(element => element.getAttribute('role') !== 'combobox' && element.textContent?.trim() && element.getBoundingClientRect().width > 0)
      .map(element => ({
        text: element.textContent.trim(),
        fontSize: getComputedStyle(element).fontSize,
        fontWeight: getComputedStyle(element).fontWeight,
      })));
    const inconsistentButton = buttonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '400');
    if (inconsistentButton) throw new Error(`按钮文字未统一为 13px 常规字重：${JSON.stringify(inconsistentButton)}`);

    const departmentTrigger = selectTriggers.first();
    await departmentTrigger.click();
    const anchoredContent = page.locator('[data-anchored-select-content]');
    await anchoredContent.waitFor();
    await page.waitForTimeout(180);
    const anchoredPosition = await page.evaluate(() => {
      const trigger = document.querySelector('.filter-grid--users [data-anchored-select-trigger]')?.getBoundingClientRect();
      const content = document.querySelector('[data-anchored-select-content]')?.getBoundingClientRect();
      return trigger && content ? {
        triggerBottom: trigger.bottom,
        triggerWidth: trigger.width,
        contentTop: content.top,
        contentWidth: content.width,
      } : null;
    });
    if (!anchoredPosition || anchoredPosition.contentTop < anchoredPosition.triggerBottom || Math.abs(anchoredPosition.contentWidth - anchoredPosition.triggerWidth) > 1) {
      throw new Error(`筛选下拉框未固定在触发框正下方：${JSON.stringify(anchoredPosition)}`);
    }
    await page.screenshot({ path: 'smoke-user-filter-dropdown.png', fullPage: true });
    await anchoredContent.getByText('全部部门', { exact: true }).click();

    const roleTrigger = selectTriggers.nth(1);
    const statusTrigger = selectTriggers.nth(2);
    const selectFilter = async (trigger, label) => {
      await trigger.click();
      const content = page.locator('[data-anchored-select-content][data-state="open"]');
      await content.waitFor();
      await content.getByText(label, { exact: true }).click();
    };

    await selectFilter(statusTrigger, '停用');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'admin').waitFor({ state: 'detached' });
    await tableRow(page, 'sales_stop').waitFor();
    if (await tableRow(page, 'admin').count()) throw new Error('用户停用状态筛选未生效');

    await selectFilter(statusTrigger, '启用');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'sales_stop').waitFor({ state: 'detached' });
    await tableRow(page, 'admin').waitFor();
    if (await tableRow(page, 'sales_stop').count()) throw new Error('用户启用状态筛选未生效');

    await selectFilter(roleTrigger, '业务主管');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'admin').waitFor({ state: 'detached' });
    await tableRow(page, 'purchase01').waitFor();
    if (await tableRow(page, 'admin').count() || await tableRow(page, 'sales_stop').count()) {
      throw new Error('用户角色与启用状态组合筛选未按 AND 条件生效');
    }

    await selectFilter(statusTrigger, '停用');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor({ state: 'detached' });
    await tableRow(page, 'sales_stop').waitFor();
    if (await tableRow(page, 'purchase01').count()) throw new Error('用户角色与停用状态组合筛选未生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    const pagination = page.locator('[data-table-pagination]');
    const paginationState = await pagination.evaluate(element => {
      const currentPage = element.querySelector('[data-current-page]');
      const pager = element.querySelector('[data-slot="pagination-content"]');
      const style = currentPage ? getComputedStyle(currentPage) : null;
      const containerRect = element.getBoundingClientRect();
      const pagerRect = pager?.getBoundingClientRect();
      return {
        height: element.getBoundingClientRect().height,
        fontSize: getComputedStyle(element).fontSize,
        currentPageBorderWidth: style?.borderTopWidth,
        currentPageBackground: style?.backgroundColor,
        hasPageSizeSelect: Boolean(element.querySelector('[data-slot="select-trigger"]')),
        hasJumpInput: Boolean(element.querySelector('[data-slot="input"]')),
        centerOffset: pagerRect ? Math.abs((pagerRect.left + pagerRect.width / 2) - (containerRect.left + containerRect.width / 2)) : null,
      };
    });
    if (paginationState.height !== 52 || paginationState.fontSize !== '12px' || paginationState.currentPageBorderWidth !== '0px'
      || paginationState.currentPageBackground !== 'rgba(0, 0, 0, 0)' || !paginationState.hasPageSizeSelect || paginationState.hasJumpInput
      || paginationState.centerOffset === null || paginationState.centerOffset > 1) {
      throw new Error(`分页组件未保持居中或缺少每页条数选择：${JSON.stringify(paginationState)}`);
    }

    const pageSizeTrigger = pagination.getByRole('combobox');
    await pageSizeTrigger.click();
    await page.getByRole('option', { name: '20 条', exact: true }).click();
    await pageSizeTrigger.getByText('20 条', { exact: true }).waitFor();

    const batchEnable = page.getByRole('button', { name: '批量启用' });
    await batchEnable.locator('..').hover();
    await page.getByText('请先选择账号', { exact: true }).waitFor();
    await page.screenshot({ path: 'smoke-system-users-hover.png', fullPage: true });

    const refreshButton = page.getByRole('button', { name: '刷新', exact: true });
    const refreshBackground = await refreshButton.evaluate(element => getComputedStyle(element).backgroundColor);
    await refreshButton.hover();
    await page.waitForTimeout(180);
    const refreshHoverBackground = await refreshButton.evaluate(element => getComputedStyle(element).backgroundColor);
    if (refreshBackground === refreshHoverBackground) throw new Error('工具栏按钮悬停状态未产生视觉变化');

    const usernameInput = page.getByPlaceholder('请输入登录账号');
    await usernameInput.click();
    await page.waitForTimeout(250);
    const focusShadow = await usernameInput.evaluate(element => getComputedStyle(element).boxShadow);
    if (!focusShadow.includes('10px')) throw new Error(`输入框焦点态未使用柔和模糊辉光：${focusShadow}`);

    const productMenu = page.getByRole('button', { name: '产品中心', exact: true });
    const productSubmenu = productMenu.locator('..').locator('.submenu-collapse');
    await productMenu.click();
    await page.waitForTimeout(220);
    const expandedHeight = await productSubmenu.evaluate(element => element.getBoundingClientRect().height);
    if (expandedHeight < 60) throw new Error('侧栏子菜单展开动画未完成');
    await productMenu.click();
    await page.waitForTimeout(220);
    const collapsedHeight = await productSubmenu.evaluate(element => element.getBoundingClientRect().height);
    if (collapsedHeight > 1) throw new Error(`侧栏子菜单未平滑收回：${collapsedHeight}px`);

    await usernameInput.fill('admin');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor({ state: 'detached' });
    await tableRow(page, 'admin').waitFor();
    if (await tableRow(page, 'purchase01').count()) throw new Error('用户账号筛选未生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    await page.getByPlaceholder('请输入用户姓名').fill('采购主管');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor();
    await tableRow(page, 'admin').waitFor({ state: 'detached' });
    await page.getByPlaceholder('请输入登录账号').fill('purchase');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor();
    if (await tableRow(page, 'admin').count()) throw new Error('用户账号与姓名筛选未按 AND 条件生效');
    await page.getByRole('button', { name: '重置', exact: true }).click();

    await page.setViewportSize({ width: 933, height: 460 });
    await page.getByRole('button', { name: '新增用户' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增用户' });
    await createDialog.waitFor();
    await assertRequiredLabels(createDialog, ['登录账号', '用户姓名', '初始密码', '所属部门', '绑定角色', '启用状态']);
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    const createValidationText = await createDialog.innerText();
    for (const message of ['请输入登录账号', '请输入用户姓名', '请输入初始密码', '请选择所属部门', '请选择用户角色']) {
      if (!createValidationText.includes(message)) throw new Error(`新增用户缺少必填校验提示：${message}`);
    }
    const dialogScrollState = await createDialog.evaluate(element => {
      const style = getComputedStyle(element);
      element.scrollTop = element.scrollHeight;
      return {
        overflowY: style.overflowY,
        maxHeight: style.maxHeight,
        clientHeight: element.clientHeight,
        scrollHeight: element.scrollHeight,
        scrollable: element.scrollHeight > element.clientHeight,
        reachedBottom: element.scrollTop > 0,
      };
    });
    if (dialogScrollState.overflowY !== 'auto' || !dialogScrollState.scrollable || !dialogScrollState.reachedBottom) {
      throw new Error(`长弹窗未提供可用的纵向滚动条：${JSON.stringify(dialogScrollState)}`);
    }
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 933, height: 838 });

    const adminRow = tableRow(page, 'admin');
    await adminRow.getByRole('button', { name: '编辑' }).click();
    await page.getByRole('dialog', { name: '编辑用户' }).waitFor();
    await page.keyboard.press('Escape');

    await adminRow.getByRole('button', { name: '角色', exact: true }).click();
    await page.getByRole('dialog', { name: '角色绑定' }).waitFor();
    await page.keyboard.press('Escape');

    await adminRow.getByRole('checkbox').click();
    await page.getByText('已选 1 项').waitFor();
    await page.getByRole('button', { name: '重置密码' }).click();
    await page.getByRole('dialog', { name: '批量重置密码' }).waitFor();
    await page.keyboard.press('Escape');

    await page.getByRole('button', { name: '角色管理' }).click();
    await page.locator('[data-page-loading]').waitFor({ state: 'visible' });
    await page.waitForURL(/system\/roles/);
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.getByRole('heading', { name: '角色管理' }).waitFor();
    if (await page.getByRole('heading', { name: '用户管理' }).count()) {
      throw new Error('导航高亮切换后仍残留旧页面内容');
    }

    await page.getByRole('button', { name: '用户管理' }).click();
    await page.locator('[data-page-loading]').waitFor({ state: 'visible' });
    await page.waitForURL(/system\/users/);
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.getByRole('heading', { name: '用户管理' }).waitFor();

    const sidebarColor = await page.locator('aside').evaluate(element => getComputedStyle(element).backgroundColor);
    const headerColor = await page.locator('header').evaluate(element => getComputedStyle(element).backgroundColor);
    if (sidebarColor !== headerColor) throw new Error('侧栏与顶栏背景色未统一');
    await page.screenshot({ path: 'smoke-system-users-933.png', fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 用户管理与导航切换通过');
}).catch(error => {
  console.error(error);
  process.exit(1);
});
