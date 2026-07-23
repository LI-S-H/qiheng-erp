const fs = require('fs');
const path = require('path');
const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, assertSharedListChrome, assertContentSizedFilter, clickQueryAndAssertLoading, clickRefreshAndAssertLoading, clickResetAndAssertLoading } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/system-users',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

runSmoke({
  route: '/system/users',
  screenshot: screenshotPath('system-users-normal.png'),
  async test(page) {
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '用户数据汇总', filterLabel: '用户筛选' });
    await assertContentSizedFilter(page, [220, 220, 220, 220, 168]);
    const inactiveSubmenu = page.locator('[data-menu-path="/system/roles"]');
    const activeSubmenu = page.locator('[data-menu-path="/system/users"]');
    const readSubmenuContrast = submenu => submenu.evaluate((element) => {
      const parseRgb = (value) => {
        const canvas = document.createElement('canvas');
        canvas.width = 1;
        canvas.height = 1;
        const context = canvas.getContext('2d');
        context.fillStyle = value;
        context.fillRect(0, 0, 1, 1);
        return [...context.getImageData(0, 0, 1, 1).data].slice(0, 3);
      };
      const luminance = value => parseRgb(value)
        .map(channel => channel / 255)
        .map(channel => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
        .reduce((total, channel, index) => total + channel * [0.2126, 0.7152, 0.0722][index], 0);
      const foreground = getComputedStyle(element).color;
      const background = getComputedStyle(element.closest('aside')).backgroundColor;
      const lighter = Math.max(luminance(foreground), luminance(background));
      const darker = Math.min(luminance(foreground), luminance(background));
      return {
        foreground,
        background,
        contrast: (lighter + 0.05) / (darker + 0.05),
        outlineStyle: getComputedStyle(element).outlineStyle,
        ariaCurrent: element.getAttribute('aria-current'),
      };
    });

    const inactiveState = await readSubmenuContrast(inactiveSubmenu);
    if (inactiveState.contrast < 4.5) {
      throw new Error(`侧栏未激活子菜单文字对比度不足：${JSON.stringify(inactiveState)}`);
    }
    await inactiveSubmenu.hover();
    const hoverState = await readSubmenuContrast(inactiveSubmenu);
    if (hoverState.contrast < 4.5) {
      throw new Error(`侧栏子菜单悬停文字对比度不足：${JSON.stringify(hoverState)}`);
    }
    await inactiveSubmenu.focus();
    const focusState = await readSubmenuContrast(inactiveSubmenu);
    if (focusState.contrast < 4.5 || focusState.outlineStyle === 'none') {
      throw new Error(`侧栏子菜单键盘焦点不可辨认：${JSON.stringify(focusState)}`);
    }
    const activeState = await readSubmenuContrast(activeSubmenu);
    if (activeState.contrast < 4.5 || activeState.ariaCurrent !== 'page') {
      throw new Error(`侧栏激活子菜单状态不可辨认：${JSON.stringify(activeState)}`);
    }
    await page.screenshot({ path: screenshotPath('system-users-sidebar-submenu.png'), fullPage: true });
    await assertFixedTableLayout(page, 8);
    await page.getByText('系统管理员').first().waitFor();
    await page.getByText('purchase01').waitFor();
    await clickRefreshAndAssertLoading(page, screenshotPath('system-users-refresh-loading.png'));

    const bodyText = await page.locator('body').innerText();
    if (bodyText.includes('总部') || !bodyText.includes('行政部')) {
      throw new Error('用户页部门名称必须与部门管理统一为“行政部”');
    }

    const bodyFontSize = await page.locator('body').evaluate(element => Number.parseFloat(getComputedStyle(element).fontSize));
    if (bodyFontSize !== 14) throw new Error(`页面正文基准字号应为 14px，当前为 ${bodyFontSize}px`);

    await page.setViewportSize({ width: 933, height: 838 });
    await assertContentSizedFilter(page, [220, 220, 220, 220, 168]);
    const filterGrid = page.locator('[data-filter-layout="content"]');

    const selectTriggers = filterGrid.getByRole('combobox');
    if (await selectTriggers.count() !== 3) throw new Error('用户筛选项必须使用统一下拉框组件');

    const typography = await page.evaluate(() => {
      const fontSize = (selector) => getComputedStyle(document.querySelector(selector)).fontSize;
      return {
        label: fontSize('[data-filter-layout="content"] [data-slot="label"]'),
        input: fontSize('[data-filter-layout="content"] [data-slot="input"]'),
        select: fontSize('[data-filter-layout="content"] [data-anchored-select-trigger]'),
        queryButton: fontSize('.filter-actions [data-slot="button"]'),
      };
    });
    if (typography.label !== '13px' || typography.input !== '14px' || typography.select !== '14px' || typography.queryButton !== '13px') {
      throw new Error(`筛选标签应为 13px、字段应为 14px、按钮应为 13px：${JSON.stringify(typography)}`);
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
    const inconsistentButton = buttonTypography.find(button => button.fontSize !== '13px' || button.fontWeight !== '500');
    if (inconsistentButton) throw new Error(`按钮文字未统一为 13px 中等字重：${JSON.stringify(inconsistentButton)}`);

    const departmentTrigger = selectTriggers.first();
    await departmentTrigger.click();
    const anchoredContent = page.locator('[data-anchored-select-content]');
    await anchoredContent.waitFor();
    await page.waitForTimeout(180);
    const anchoredPosition = await page.evaluate(() => {
      const trigger = document.querySelector('[data-filter-layout="content"] [data-anchored-select-trigger]')?.getBoundingClientRect();
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
    await page.screenshot({ path: screenshotPath('system-users-filter-dropdown.png'), fullPage: true });
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
    const activeFilters = page.locator('[data-active-user-filters]');
    await activeFilters.getByText('已生效 1 个条件', { exact: true }).waitFor();
    await activeFilters.getByText('状态：停用', { exact: true }).waitFor();

    await selectFilter(statusTrigger, '启用');
    await activeFilters.getByText('状态：停用', { exact: true }).waitFor();
    if (await activeFilters.getByText('状态：启用', { exact: true }).count()) {
      throw new Error('尚未提交的新筛选值不应提前显示为已生效条件');
    }
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'sales_stop').waitFor({ state: 'detached' });
    await tableRow(page, 'admin').waitFor();
    if (await tableRow(page, 'sales_stop').count()) throw new Error('用户启用状态筛选未生效');
    await activeFilters.getByText('状态：启用', { exact: true }).waitFor();

    await selectFilter(roleTrigger, '采购员');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'admin').waitFor({ state: 'detached' });
    await tableRow(page, 'purchase01').waitFor();
    if (await tableRow(page, 'admin').count() || await tableRow(page, 'sales_stop').count()) {
      throw new Error('用户角色与启用状态组合筛选未按 AND 条件生效');
    }
    await activeFilters.getByText('已生效 2 个条件', { exact: true }).waitFor();
    await activeFilters.getByText('角色：采购员', { exact: true }).waitFor();

    await selectFilter(statusTrigger, '停用');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor({ state: 'detached' });
    await tableRow(page, 'sales_stop').waitFor();
    if (await tableRow(page, 'purchase01').count()) throw new Error('用户角色与停用状态组合筛选未生效');
    await selectFilter(roleTrigger, '超级管理员');
    await activeFilters.getByText('角色：采购员', { exact: true }).waitFor();
    if (await activeFilters.getByText('角色：超级管理员', { exact: true }).count()) {
      throw new Error('未查询的角色草稿不应提前显示为已生效条件');
    }
    await activeFilters.getByRole('button', { name: '移除筛选条件：状态：停用' }).click();
    await page.locator('[data-list-loading]').waitFor({ state: 'visible', timeout: 1000 });
    await page.locator('[data-list-loading]').waitFor({ state: 'hidden', timeout: 5000 });
    await tableRow(page, 'purchase01').waitFor();
    await tableRow(page, 'sales_stop').waitFor();
    await activeFilters.getByText('已生效 1 个条件', { exact: true }).waitFor();
    await roleTrigger.getByText('采购员', { exact: true }).waitFor();
    if (await activeFilters.getByText('状态：停用', { exact: true }).count()) {
      throw new Error('移除状态条件后仍显示旧筛选标签');
    }
    if (await tableRow(page, 'admin').count()) {
      throw new Error('移除状态条件时误提交了未查询的超级管理员角色草稿');
    }
    await clickResetAndAssertLoading(page, screenshotPath('system-users-reset-loading.png'));
    await activeFilters.waitFor({ state: 'hidden' });

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
    await page.screenshot({ path: screenshotPath('system-users-hover.png'), fullPage: true });

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
    if (!focusShadow || focusShadow === 'none') throw new Error(`输入框焦点态缺少清晰反馈：${focusShadow}`);

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
    if (await activeFilters.count()) throw new Error('尚未查询的账号输入不应显示为已生效条件');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor({ state: 'detached' });
    await tableRow(page, 'admin').waitFor();
    if (await tableRow(page, 'purchase01').count()) throw new Error('用户账号筛选未生效');
    await activeFilters.getByText('账号：admin', { exact: true }).waitFor();
    await clickResetAndAssertLoading(page);
    await activeFilters.waitFor({ state: 'hidden' });

    await page.getByPlaceholder('请输入用户姓名').fill('采购主管');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor();
    await tableRow(page, 'admin').waitFor({ state: 'detached' });
    await page.getByPlaceholder('请输入登录账号').fill('purchase');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'purchase01').waitFor();
    if (await tableRow(page, 'admin').count()) throw new Error('用户账号与姓名筛选未按 AND 条件生效');
    await clickResetAndAssertLoading(page);

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

    const rowActionsTrigger = adminRow.getByRole('button', { name: '更多 admin 操作' });
    await rowActionsTrigger.focus();
    await page.keyboard.press('Enter');
    const rowActionsMenu = page.getByRole('menu');
    await rowActionsMenu.waitFor();
    for (const actionName of ['绑定角色', '重置密码', '停用账号', '删除用户']) {
      await rowActionsMenu.getByRole('menuitem', { name: actionName, exact: true }).waitFor();
    }
    await page.screenshot({ path: screenshotPath('system-users-row-actions.png'), fullPage: true });
    await rowActionsMenu.getByRole('menuitem', { name: '绑定角色', exact: true }).click();
    await page.getByRole('dialog', { name: '角色绑定' }).waitFor();
    await page.keyboard.press('Escape');

    await rowActionsTrigger.click();
    await page.getByRole('menuitem', { name: '重置密码', exact: true }).click();
    await page.getByRole('dialog', { name: '重置密码' }).waitFor();
    await page.keyboard.press('Escape');

    await adminRow.getByRole('checkbox').click();
    await page.getByText('已选 1 项').waitFor();
    await page.getByRole('button', { name: '重置密码' }).click();
    await page.getByRole('dialog', { name: '批量重置密码' }).waitFor();
    await page.keyboard.press('Escape');

    await page.locator('[data-menu-path="/system/roles"]').click();
    await page.locator('[data-page-loading]').waitFor({ state: 'visible' });
    await page.waitForURL(/system\/roles/);
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.getByRole('heading', { name: '角色管理' }).waitFor();
    if (await page.getByRole('heading', { name: '用户管理' }).count()) {
      throw new Error('导航高亮切换后仍残留旧页面内容');
    }

    await page.locator('[data-menu-path="/system/users"]').click();
    await page.locator('[data-page-loading]').waitFor({ state: 'visible' });
    await page.waitForURL(/system\/users/);
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.getByRole('heading', { name: '用户管理' }).waitFor();

    const sidebarColor = await page.locator('aside').evaluate(element => getComputedStyle(element).backgroundColor);
    const headerColor = await page.locator('header').evaluate(element => getComputedStyle(element).backgroundColor);
    if (sidebarColor !== headerColor) throw new Error('侧栏与顶栏背景色未统一');
    await page.screenshot({ path: screenshotPath('system-users-933.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
  },
}).then(() => runSmoke({
  route: '/system/users',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: screenshotPath('system-users-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
    await page.getByPlaceholder('请输入登录账号').fill('admin');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.locator('[data-list-loading]').waitFor({ state: 'hidden', timeout: 5000 });
    await page.locator('[data-active-user-filters]').getByText('账号：admin', { exact: true }).waitFor();

    const adminRow = tableRow(page, 'admin');
    const trigger = adminRow.getByRole('button', { name: '更多 admin 操作' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu');
    await menu.waitFor();
    const menuMotion = await menu.evaluate(element => {
      const style = getComputedStyle(element);
      return { animationName: style.animationName, animationDuration: style.animationDuration };
    });
    const durations = menuMotion.animationDuration.split(',').map(value => (
      value.trim().endsWith('ms') ? Number.parseFloat(value) : Number.parseFloat(value) * 1000
    ));
    if (durations.some(duration => duration > 1)) {
      throw new Error(`减少动态效果模式下行操作菜单仍有长动画：${JSON.stringify(menuMotion)}`);
    }
    await page.keyboard.press('Escape');
    await menu.waitFor({ state: 'hidden' });

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 用户管理页面发生横向溢出：${overflow}`);
  },
})).then(() => {
  return runSmoke({
    route: '/system/users',
    viewport: { width: 1280, height: 720 },
    screenshot: screenshotPath('system-users-query-failure.png'),
    async test(page) {
      const moduleUrlPattern = '**/src/modules/system/users/api.ts*';
      const originalStatement = 'if (useMockApi) return Promise.resolve(mockFilterUsers(params));';
      const patchedStatement = 'if (useMockApi) { if (sessionStorage.getItem("__force_system_user_list_failure__") === "true") return Promise.reject(new Error("forced system user list failure")); return Promise.resolve(mockFilterUsers(params)); }';

      await page.route(moduleUrlPattern, async (route) => {
        const response = await route.fetch();
        const body = await response.text();
        if (!body.includes(originalStatement)) {
          throw new Error('未找到用户列表 Mock 注入点，无法验证查询失败状态');
        }
        await route.fulfill({ response, body: body.replace(originalStatement, patchedStatement) });
      });
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.getByRole('heading', { name: '用户管理' }).waitFor();
      await tableRow(page, 'admin').waitFor();

      const statusTrigger = page.locator('[data-filter-layout="content"]').getByRole('combobox').nth(2);
      const selectStatus = async (label) => {
        await statusTrigger.click();
        const content = page.locator('[data-anchored-select-content][data-state="open"]');
        await content.waitFor();
        await content.getByText(label, { exact: true }).click();
      };

      await selectStatus('停用');
      await page.getByRole('button', { name: '查询', exact: true }).click();
      await tableRow(page, 'admin').waitFor({ state: 'detached' });
      await tableRow(page, 'sales_stop').waitFor();
      const activeFilters = page.locator('[data-active-user-filters]');
      await activeFilters.getByText('状态：停用', { exact: true }).waitFor();

      await page.evaluate(() => sessionStorage.setItem('__force_system_user_list_failure__', 'true'));
      await selectStatus('启用');
      await page.getByRole('button', { name: '查询', exact: true }).click();
      await page.waitForTimeout(450);
      await page.getByRole('button', { name: '查询', exact: true }).waitFor();

      await activeFilters.getByText('状态：停用', { exact: true }).waitFor();
      if (await activeFilters.getByText('状态：启用', { exact: true }).count()) {
        throw new Error('查询失败后错误地把新条件标记为已生效');
      }
      await tableRow(page, 'sales_stop').waitFor();
      if (await tableRow(page, 'admin').count()) {
        throw new Error('查询失败后旧的停用账号结果被错误替换');
      }
      await page.evaluate(() => sessionStorage.removeItem('__force_system_user_list_failure__'));
    },
  });
}).then(() => {
  console.log('SMOKE_OK: 用户管理筛选快照、失败回退、行操作、导航切换与减弱动效通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
