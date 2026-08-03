const fs = require('fs');
const path = require('path');
const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  assertSharedListChrome,
  assertContentSizedFilter,
  clickQueryAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-07-14-141759-sales-order-compact-table',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

async function assertDialogCenteredInAppContent(dialog) {
  const position = await dialog.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const sidebarWidth = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-shell-sidebar-width')) || 0;
    return {
      actualCenter: rect.left + rect.width / 2,
      expectedCenter: sidebarWidth + (window.innerWidth - sidebarWidth) / 2,
    };
  });
  if (Math.abs(position.actualCenter - position.expectedCenter) > 2) {
    throw new Error(`销售订单详情未在业务内容区居中：${JSON.stringify(position)}`);
  }
}

async function selectRowAction(page, row, salesNo, actionLabel) {
  const trigger = row.getByRole('button', { name: `更多 ${salesNo} 操作` });
  await trigger.focus();
  await page.keyboard.press('Enter');
  const item = page.getByRole('menuitem', { name: actionLabel, exact: true });
  await item.waitFor();
  await item.click();
}

runSmoke({
  route: '/sales/customers',
  screenshot: screenshotPath('sales-orders-normal.png'),
  async test(page) {
    await page.getByRole('heading', { name: '客户管理' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '客户数据汇总', filterLabel: '客户筛选' });
    await assertContentSizedFilter(page, [220, 220, 220, 168]);
    await tableRow(page, 'C001').waitFor();
    await assertFixedTableLayout(page, 8);
    await clickRefreshAndAssertLoading(page, screenshotPath('sales-customers-refresh.png'));

    await page.getByPlaceholder('请输入名称').fill('上海');
    await clickQueryAndAssertLoading(page, screenshotPath('sales-customers-query.png'));
    await tableRow(page, '上海林间便利连锁').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增客户' }).click();
    const customerDialog = page.getByRole('dialog', { name: '新增客户' });
    await assertRequiredLabels(customerDialog, ['客户名称']);
    await customerDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await customerDialog.innerText()).includes('请输入客户名称')) throw new Error('客户新增缺少必填校验');
    await customerDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'C001').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '客户详情' }).getByText('客户编码').waitFor();
    await page.getByRole('dialog', { name: '客户详情' }).getByText('最后维护人').waitFor();
    await page.getByRole('dialog', { name: '客户详情' }).getByRole('button', { name: '关闭' }).click();

    await page.locator('[data-menu-path="/sales/orders"]').click();
    await page.getByRole('heading', { name: '销售订单' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '销售订单数据汇总', filterLabel: '销售订单筛选' });
    await assertContentSizedFilter(page, [220, 280, 280, 168]);
    await tableRow(page, 'SO202607001').waitFor();
    for (const summaryLabel of ['本页待审核', '本页待出库', '本页出库未完成']) {
      await page.getByText(summaryLabel, { exact: true }).waitFor();
    }
    await assertFixedTableLayout(page, 9);
    const desktopTableState = await page.locator('[data-slot="table-container"]').first().evaluate((element) => {
      const row = element.querySelector('tbody [data-slot="table-row"]');
      const actionCell = row?.lastElementChild;
      const actionGroup = actionCell?.firstElementChild;
      return {
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
        actionPosition: actionCell ? getComputedStyle(actionCell).position : '',
        actionOverflow: actionCell ? actionCell.scrollWidth - actionCell.clientWidth : 0,
        actionWhiteSpace: actionGroup ? getComputedStyle(actionGroup).whiteSpace : '',
      };
    });
    if (desktopTableState.scrollWidth > desktopTableState.clientWidth + 1 || desktopTableState.stickyCount !== 0
      || desktopTableState.actionPosition !== 'static' || desktopTableState.actionOverflow > 1
      || desktopTableState.actionWhiteSpace !== 'nowrap') {
      throw new Error(`1440px 销售订单未实现无固定列紧凑布局：${JSON.stringify(desktopTableState)}`);
    }
    if (!await tableRow(page, 'SO202607001').locator('[data-slot="table-cell"]').nth(1).locator('[title]').count()) {
      throw new Error('销售订单客户名称省略后缺少完整信息提示');
    }
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.screenshot({ path: screenshotPath('sales-orders-compact-1440.png'), fullPage: true });
    await page.getByPlaceholder('如 SO202606001').fill('SO202607001');
    await clickQueryAndAssertLoading(page, screenshotPath('sales-orders-query.png'));
    await tableRow(page, 'SO202607001').waitFor();
    await clickResetAndAssertLoading(page);

    const draftOrderRow = tableRow(page, 'SO202607003');
    if (!(await draftOrderRow.innerText()).includes('待提交') || !(await draftOrderRow.innerText()).includes('未锁定')) {
      throw new Error('销售草稿缺少待提交或未锁定提示');
    }
    await draftOrderRow.getByRole('button', { name: '更多 SO202607003 操作' }).focus();
    await page.keyboard.press('Enter');
    const draftMenu = page.getByRole('menu');
    for (const expected of ['编辑销售单', '提交销售单', '取消销售单']) {
      await draftMenu.getByRole('menuitem', { name: expected, exact: true }).waitFor();
    }
    await page.waitForTimeout(180);
    await page.screenshot({ path: screenshotPath('sales-orders-row-actions.png'), fullPage: true });
    await draftMenu.getByRole('menuitem', { name: '编辑销售单', exact: true }).click();
    const editOrderDialog = page.getByRole('dialog', { name: '编辑销售单' });
    await editOrderDialog.getByText('SO202607003').waitFor();
    await editOrderDialog.getByText('预计发货', { exact: true }).waitFor();
    await assertRequiredLabels(editOrderDialog, ['客户', '出库仓库']);
    await editOrderDialog.getByRole('button', { name: '保存修改' }).click();
    await editOrderDialog.waitFor({ state: 'hidden' });

    await selectRowAction(page, tableRow(page, 'SO202607003'), 'SO202607003', '提交销售单');
    const submitPreviewDialog = page.getByRole('dialog', { name: '销售单详情' });
    await submitPreviewDialog.getByText('请先核对销售单头和全部销售明细，再提交进入待审核并锁定库存。').waitFor();
    if (await submitPreviewDialog.getByRole('button', { name: '提交销售单' }).isDisabled()) {
      throw new Error('预计发货已维护时提交按钮不应禁用');
    }
    await submitPreviewDialog.getByRole('button', { name: '提交销售单' }).click();
    const submitDialog = page.getByRole('alertdialog', { name: '提交销售单' });
    await submitDialog.getByText('锁定可用库存').waitFor();
    await submitDialog.getByRole('button', { name: '取消' }).click();
    await submitPreviewDialog.getByRole('button', { name: '关闭' }).click();

    const submittedOrderRow = tableRow(page, 'SO202607004');
    const submittedBadgeText = await submittedOrderRow.locator('[data-slot="badge"]').innerText();
    if (submittedBadgeText !== '待审核' || !(await submittedOrderRow.innerText()).includes('等待审核') || !(await submittedOrderRow.innerText()).includes('已锁定')) {
      throw new Error(`待审核销售单状态名称或库存锁定提示不一致：${submittedBadgeText}`);
    }
    await submittedOrderRow.getByRole('button', { name: '更多 SO202607004 操作' }).click();
    const submittedMenu = page.getByRole('menu');
    for (const expected of ['编辑销售单', '审核销售单', '取消销售单']) {
      await submittedMenu.getByRole('menuitem', { name: expected, exact: true }).waitFor();
    }
    await submittedMenu.getByRole('menuitem', { name: '审核销售单', exact: true }).click();
    const approvePreviewDialog = page.getByRole('dialog', { name: '销售单详情' });
    await approvePreviewDialog.getByText('审核通过后将生成待确认销售出库单').waitFor();
    await approvePreviewDialog.getByRole('button', { name: '关闭' }).click();

    await tableRow(page, 'SO202607001').getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '销售单详情' });
    await detailDialog.getByText('销售单号').waitFor();
    await assertDialogCenteredInAppContent(detailDialog);
    await detailDialog.getByText('每日坚果混合装', { exact: true }).waitFor();
    await page.waitForTimeout(250);
    await page.screenshot({ path: screenshotPath('sales-orders-detail-app-content-center.png'), fullPage: true });
    if (await detailDialog.locator('[data-overflow-tooltip]').count() === 0) {
      throw new Error('销售明细备注未接入统一的溢出内容提示');
    }
    await detailDialog.getByRole('button', { name: '关闭' }).click();
    const readonlyStatusExpectations = {
      SO202607001: { statusLabel: '待出库', expectedTexts: ['等待出库', '已锁定'] },
      SO202607002: { statusLabel: '部分出库', expectedTexts: ['出库处理中', '已锁定'] },
      SO202607006: { statusLabel: '已出库', expectedTexts: ['流程完成', '已出库'] },
      SO202607007: { statusLabel: '已取消', expectedTexts: ['流程终止', '已释放'] },
    };
    for (const [readonlyOrder, { statusLabel, expectedTexts }] of Object.entries(readonlyStatusExpectations)) {
      const readonlyRow = tableRow(page, readonlyOrder);
      const badgeText = await readonlyRow.locator('[data-slot="badge"]').innerText();
      if (badgeText !== statusLabel) {
        throw new Error(`销售单 ${readonlyOrder} 状态标签应为 ${statusLabel}，实际为 ${badgeText}`);
      }
      const readonlyText = await readonlyRow.innerText();
      for (const expected of expectedTexts) {
        if (!readonlyText.includes(expected)) throw new Error(`销售单 ${readonlyOrder} 缺少状态或库存提示：${expected}`);
      }
      if (await readonlyRow.getByRole('button', { name: `更多 ${readonlyOrder} 操作` }).count()) {
        throw new Error(`不可编辑销售单 ${readonlyOrder} 不应显示编辑、流转或取消菜单`);
      }
    }

    await page.getByRole('button', { name: '新增销售单' }).click();
    const orderDialog = page.getByRole('dialog', { name: '新增销售单草稿' });
    const orderDialogText = await orderDialog.innerText();
    if (!orderDialogText.includes('后端自动生成') || !orderDialogText.includes('保存后为草稿') || !orderDialogText.includes('预计发货')) {
      throw new Error('销售订单新增弹窗缺少系统生成字段或单头预计发货字段');
    }
    await assertRequiredLabels(orderDialog, ['客户', '出库仓库']);
    await orderDialog.getByRole('button', { name: '保存草稿' }).click();
    const requiredText = await orderDialog.innerText();
    if (!requiredText.includes('请选择客户') || !requiredText.includes('请选择出库仓库')) {
      throw new Error('销售订单草稿缺少客户或仓库必填校验');
    }
    await orderDialog.getByRole('button', { name: '取消', exact: true }).click();
    await orderDialog.waitFor({ state: 'hidden' });

    await page.setViewportSize({ width: 1115, height: 838 });
    await assertContentSizedFilter(page, [220, 280, 280, 168]);
    const tableViewport = page.locator('[data-slot="table-container"]').first();
    await tableViewport.evaluate((element) => {
      element.scrollLeft = element.scrollWidth;
      element.dispatchEvent(new Event('scroll'));
    });
    await page.waitForTimeout(100);
    const narrowLayout = await tableViewport.evaluate((element) => {
      const container = element.getBoundingClientRect();
      const salesHeader = element.querySelector('thead [data-sales-no-column]')?.getBoundingClientRect();
      const actionsHeader = element.querySelector('thead [data-sales-actions-column]')?.getBoundingClientRect();
      return {
        overflow: element.scrollWidth - element.clientWidth,
        scrollLeft: element.scrollLeft,
        stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
        salesHeaderLeft: salesHeader ? salesHeader.left - container.left : null,
        actionsHeaderRight: actionsHeader ? container.right - actionsHeader.right : null,
      };
    });
    if (narrowLayout.overflow <= 2 || narrowLayout.scrollLeft <= 2 || narrowLayout.stickyCount !== 0
      || narrowLayout.salesHeaderLeft === null || narrowLayout.salesHeaderLeft >= 0
      || narrowLayout.actionsHeaderRight === null || Math.abs(narrowLayout.actionsHeaderRight) > 2) {
      throw new Error(`较窄视口下销售订单未自然滚动或仍存在固定列：${JSON.stringify(narrowLayout)}`);
    }
    await page.screenshot({ path: screenshotPath('sales-orders-1115.png'), fullPage: true });

  },
}).then(() => runSmoke({
  route: '/sales/orders',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: screenshotPath('sales-orders-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '销售订单' }).waitFor();
    const row = tableRow(page, 'SO202607003');
    const compactCellState = await row.evaluate((element) => {
      const updateCell = element.children[7];
      const actionCell = element.children[8];
      const actionGroup = actionCell.firstElementChild;
      return {
        updateOverflow: getComputedStyle(updateCell).overflow,
        actionPosition: getComputedStyle(actionCell).position,
        actionOverflow: actionCell.scrollWidth - actionCell.clientWidth,
        actionWhiteSpace: actionGroup ? getComputedStyle(actionGroup).whiteSpace : '',
      };
    });
    if (compactCellState.updateOverflow !== 'hidden' || compactCellState.actionPosition !== 'static'
      || compactCellState.actionOverflow > 1 || compactCellState.actionWhiteSpace !== 'nowrap') {
      throw new Error(`销售更新时间或操作列紧凑布局异常：${JSON.stringify(compactCellState)}`);
    }
    const trigger = row.getByRole('button', { name: '更多 SO202607003 操作' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menuItem = page.getByRole('menuitem', { name: '提交销售单', exact: true });
    await menuItem.waitFor();
    const menuMotion = await page.getByRole('menu').evaluate((element) => {
      const style = getComputedStyle(element);
      return { duration: style.animationDuration, transition: style.transitionDuration };
    });
    const durationValues = [menuMotion.duration, menuMotion.transition].flatMap(value => value.split(',')).map(value => {
      const text = value.trim();
      return text.endsWith('ms') ? Number.parseFloat(text) : Number.parseFloat(text) * 1000;
    });
    if (durationValues.some(value => value > 1)) throw new Error(`减少动态效果模式下销售行菜单仍有长动画：${JSON.stringify(menuMotion)}`);
    await page.keyboard.press('Escape');
    await menuItem.waitFor({ state: 'hidden' });
    if (!await trigger.evaluate(element => document.activeElement === element)) throw new Error('销售行菜单关闭后焦点未返回触发器');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 销售订单页面发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 销售订单关键列、状态与库存反馈、行操作、业务保护及减弱动效通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
