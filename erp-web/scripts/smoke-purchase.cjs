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
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/purchase-orders',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

async function selectRemoteOption(page, dialog, comboboxIndex, keyword, optionText) {
  await dialog.getByRole('combobox').nth(comboboxIndex).click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.locator('input').fill(keyword);
  await content.getByText(optionText).first().click();
}

async function selectRowAction(page, row, purchaseNo, actionLabel) {
  const trigger = row.getByRole('button', { name: `更多 ${purchaseNo} 操作` });
  await trigger.focus();
  await page.keyboard.press('Enter');
  const item = page.getByRole('menuitem', { name: actionLabel, exact: true });
  await item.waitFor();
  await item.click();
}

async function assertSupplierProductsNavigation(page) {
  const supplierProductsLink = page.locator('[data-menu-path="/purchase/supplier-products"]');
  const supplierLink = page.locator('[data-menu-path="/purchase/suppliers"]');
  const purchaseMenu = page.getByRole('button', { name: '采购业务', exact: true });

  const href = await supplierProductsLink.getAttribute('href');
  if (href !== '/purchase/supplier-products') {
    throw new Error(`供货产品入口缺少稳定链接目标：${href}`);
  }

  await purchaseMenu.click();
  const collapsedMenu = supplierProductsLink.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " submenu-collapse ")][1]');
  await page.waitForTimeout(32);
  const collapsedState = await collapsedMenu.evaluate(element => ({
    ariaHidden: element.getAttribute('aria-hidden'),
    className: element.className,
    inertAttribute: element.getAttribute('inert'),
    inertProperty: element.inert,
    pointerEvents: getComputedStyle(element).pointerEvents,
  }));
  if (collapsedState.ariaHidden !== 'true' || !collapsedState.inertProperty || collapsedState.pointerEvents !== 'none') {
    throw new Error(`采购业务收起后未立即禁用子菜单交互：${JSON.stringify(collapsedState)}`);
  }
  await page.waitForTimeout(280);
  if (await supplierProductsLink.isVisible()) throw new Error('采购业务收起动画结束后仍暴露子菜单入口');
  await purchaseMenu.focus();
  await page.keyboard.press('Enter');
  await supplierProductsLink.waitFor({ state: 'visible' });
  await supplierProductsLink.focus();
  await page.keyboard.press('Enter');
  await page.waitForURL(url => url.pathname === '/purchase/supplier-products');
  await page.getByRole('heading', { name: '供货产品' }).waitFor();
  await assertSharedListChrome(page, { summaryLabel: '供货产品数据汇总', filterLabel: '供货产品筛选' });
  await assertContentSizedFilter(page, [280, 220, 220, 168]);
  if (await supplierProductsLink.getAttribute('aria-current') !== 'page') {
    throw new Error('供货产品跳转后未标记当前页面');
  }
  await page.locator('[data-page-loading]').waitFor({ state: 'hidden' }).catch(() => undefined);
  await page.screenshot({ path: path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-07-14-144627-supplier-products-navigation', 'supplier-products-keyboard-navigation.png'), fullPage: true });

  await page.getByRole('button', { name: '工作台', exact: true }).click();
  await page.waitForURL(url => url.pathname === '/dashboard');
  await purchaseMenu.click();
  await page.waitForTimeout(280);
  await purchaseMenu.click();
  await supplierProductsLink.waitFor({ state: 'visible' });
  await supplierProductsLink.click();
  await page.waitForURL(url => url.pathname === '/purchase/supplier-products');
  await page.getByRole('heading', { name: '供货产品' }).waitFor();

  await supplierLink.click();
  await page.waitForURL(url => url.pathname === '/purchase/suppliers');
  await page.getByRole('heading', { name: '供应商管理' }).waitFor();
}

runSmoke({
  route: '/purchase/suppliers',
  screenshot: screenshotPath('purchase-orders-normal.png'),
  async test(page) {
    await assertSupplierProductsNavigation(page);
    await page.getByRole('heading', { name: '供应商管理' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '供应商数据汇总', filterLabel: '供应商筛选' });
    await assertContentSizedFilter(page, [220, 220, 220, 168]);
    await tableRow(page, 'S001').waitFor();
    await assertFixedTableLayout(page, 10);
    await clickRefreshAndAssertLoading(page, screenshotPath('purchase-supplier-refresh.png'));

    await page.getByPlaceholder('请输入名称').fill('华东');
    await clickQueryAndAssertLoading(page, screenshotPath('purchase-supplier-query.png'));
    await tableRow(page, '华东饮品供应链').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增供应商' }).click();
    const supplierDialog = page.getByRole('dialog', { name: '新增供应商' });
    await assertRequiredLabels(supplierDialog, ['供应商名称']);
    await supplierDialog.getByRole('button', { name: '保存', exact: true }).click();
    if (!(await supplierDialog.innerText()).includes('请输入供应商名称')) throw new Error('供应商新增缺少必填校验');
    await supplierDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'S001').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '供应商详情' }).getByText('供应商编码').waitFor();
    await page.getByRole('dialog', { name: '供应商详情' }).getByRole('button', { name: '关闭' }).click();

    await page.locator('[data-menu-path="/purchase/supplier-products"]').click();
    await page.getByRole('heading', { name: '供货产品' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '供货产品数据汇总', filterLabel: '供货产品筛选' });
    await assertContentSizedFilter(page, [280, 220, 220, 168]);
    await tableRow(page, 'HD-SD330').waitFor();
    const nutSupplierProduct = tableRow(page, 'GC-NUT30');
    await nutSupplierProduct.getByText('P000007', { exact: true }).waitFor();
    await nutSupplierProduct.getByText('每日坚果混合装', { exact: true }).waitFor();
    await assertFixedTableLayout(page, 10);
    await page.getByPlaceholder('请输入产品名称').fill('苏打水');
    await clickQueryAndAssertLoading(page, screenshotPath('purchase-supplier-products-query.png'));
    await tableRow(page, '经典原味苏打水').waitFor();
    await clickResetAndAssertLoading(page);

    await page.getByRole('button', { name: '新增供货产品' }).click();
    const supplierProductDialog = page.getByRole('dialog', { name: '新增供货产品' });
    await assertRequiredLabels(supplierProductDialog, ['供应商', '产品']);
    await supplierProductDialog.getByRole('button', { name: '取消', exact: true }).click();
    await tableRow(page, 'HD-SD330').getByRole('button', { name: '详情' }).click();
    await page.getByRole('dialog', { name: '供货产品详情' }).getByText('最近采购价').waitFor();
    await page.getByRole('dialog', { name: '供货产品详情' }).getByRole('button', { name: '关闭' }).click();

    await page.locator('[data-menu-path="/purchase/orders"]').click();
    await page.getByRole('heading', { name: '采购订单' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '采购订单数据汇总', filterLabel: '采购订单筛选' });
    await assertContentSizedFilter(page, [220, 280, 280, 168]);
    await tableRow(page, 'PO202607001').waitFor();
    for (const summaryLabel of ['本页待审核', '本页待入库', '本页入库未完成']) {
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
      throw new Error(`1440px 采购订单未实现无固定列紧凑布局：${JSON.stringify(desktopTableState)}`);
    }
    await page.getByPlaceholder('如 PO202606001').fill('PO202607001');
    await clickQueryAndAssertLoading(page, screenshotPath('purchase-orders-query.png'));
    await tableRow(page, 'PO202607001').waitFor();
    await clickResetAndAssertLoading(page);

    const draftOrderRow = tableRow(page, 'PO202607003');
    if (!(await draftOrderRow.innerText()).includes('待提交')) throw new Error('采购草稿缺少下一步状态提示');
    await draftOrderRow.getByRole('button', { name: '更多 PO202607003 操作' }).focus();
    await page.keyboard.press('Enter');
    const draftMenu = page.getByRole('menu');
    for (const expected of ['编辑采购单', '提交采购单', '取消采购单']) {
      await draftMenu.getByRole('menuitem', { name: expected, exact: true }).waitFor();
    }
    await page.waitForTimeout(180);
    await page.screenshot({ path: screenshotPath('purchase-orders-row-actions.png'), fullPage: true });
    await draftMenu.getByRole('menuitem', { name: '编辑采购单', exact: true }).click();
    const editOrderDialog = page.getByRole('dialog', { name: '编辑采购单' });
    await editOrderDialog.getByText('PO202607003').waitFor();
    await editOrderDialog.getByText('预计到货', { exact: true }).waitFor();
    await editOrderDialog.getByText('示例：2026-06-30').waitFor();
    if ((await editOrderDialog.innerText()).includes('明细预计到货')) throw new Error('采购明细不应再展示预计到货字段');
    await assertRequiredLabels(editOrderDialog, ['供应商', '入库仓库']);
    await editOrderDialog.getByRole('button', { name: '保存修改' }).click();
    await editOrderDialog.waitFor({ state: 'hidden' });

    await selectRowAction(page, tableRow(page, 'PO202607003'), 'PO202607003', '提交采购单');
    const submitPreviewDialog = page.getByRole('dialog', { name: '采购单详情' });
    await submitPreviewDialog.getByText('请先核对采购单头和全部采购明细，再提交进入待审核。').waitFor();
    if (await submitPreviewDialog.getByRole('button', { name: '提交采购单' }).isDisabled()) {
      throw new Error('预计到货已维护时提交按钮不应禁用');
    }
    await submitPreviewDialog.getByRole('button', { name: '提交采购单' }).click();
    const submitDialog = page.getByRole('alertdialog', { name: '提交采购单' });
    await submitDialog.getByText('提交后进入待审核状态').waitFor();
    await submitDialog.getByRole('button', { name: '取消' }).click();
    await submitPreviewDialog.getByRole('button', { name: '关闭' }).click();

    const submittedOrderRow = tableRow(page, 'PO202607004');
    const submittedBadgeText = await submittedOrderRow.locator('[data-slot="badge"]').innerText();
    if (submittedBadgeText !== '待审核' || !(await submittedOrderRow.innerText()).includes('等待审核')) {
      throw new Error(`待审核采购单状态名称不一致：${submittedBadgeText}`);
    }
    await submittedOrderRow.getByRole('button', { name: '更多 PO202607004 操作' }).click();
    const submittedMenu = page.getByRole('menu');
    for (const expected of ['编辑采购单', '审核采购单', '取消采购单']) {
      await submittedMenu.getByRole('menuitem', { name: expected, exact: true }).waitFor();
    }
    await submittedMenu.getByRole('menuitem', { name: '审核采购单', exact: true }).click();
    const approvePreviewDialog = page.getByRole('dialog', { name: '采购单详情' });
    await approvePreviewDialog.getByText('审核通过后将生成待确认入库单').waitFor();
    await approvePreviewDialog.getByRole('button', { name: '关闭' }).click();

    await tableRow(page, 'PO202607001').getByRole('button', { name: '详情' }).click();
    const detailDialog = page.getByRole('dialog', { name: '采购单详情' });
    await detailDialog.getByText('采购单号').waitFor();
    await detailDialog.getByText('经典原味苏打水', { exact: true }).waitFor();
    if (await detailDialog.locator('[data-overflow-tooltip]').count() === 0) {
      throw new Error('采购明细备注未接入统一的溢出内容提示');
    }
    await detailDialog.getByRole('button', { name: '关闭' }).click();
    const readonlyStatusExpectations = {
      PO202607001: ['待入库', '等待入库'],
      PO202607002: ['部分入库', '入库处理中'],
      PO202607006: ['已入库', '流程完成'],
      PO202607007: ['已取消', '流程终止'],
    };
    for (const [readonlyOrder, [statusLabel, statusHint]] of Object.entries(readonlyStatusExpectations)) {
      const readonlyRow = tableRow(page, readonlyOrder);
      const badgeText = await readonlyRow.locator('[data-slot="badge"]').innerText();
      if (badgeText !== statusLabel) {
        throw new Error(`采购单 ${readonlyOrder} 状态标签应为 ${statusLabel}，实际为 ${badgeText}`);
      }
      if (!(await readonlyRow.innerText()).includes(statusHint)) {
        throw new Error(`采购单 ${readonlyOrder} 缺少状态提示：${statusHint}`);
      }
      if (await readonlyRow.getByRole('button', { name: `更多 ${readonlyOrder} 操作` }).count()) {
        throw new Error(`不可编辑采购单 ${readonlyOrder} 不应显示编辑、流转或取消菜单`);
      }
    }

    await page.getByRole('button', { name: '新增采购单' }).click();
    const orderDialog = page.getByRole('dialog', { name: '新增采购单草稿' });
    const orderDialogText = await orderDialog.innerText();
    if (!orderDialogText.includes('后端自动生成') || !orderDialogText.includes('保存后为草稿') || !orderDialogText.includes('预计到货') || !orderDialogText.includes('示例：2026-06-30')) {
      throw new Error('采购订单新增弹窗缺少系统生成字段或单头预计到货字段');
    }
    await selectRemoteOption(page, orderDialog, 0, 'S001', 'S001');
    await selectRemoteOption(page, orderDialog, 1, 'WH001', 'WH001');
    await orderDialog.getByRole('combobox').nth(2).click();
    const productContent = page.locator('[data-remote-search-select-content]').last();
    await productContent.locator('input').fill('P000001');
    /*
    if (!supplierOptionsText.includes('S001 华东饮品供应链') || supplierOptionsText.includes('S002 晨岛咖啡贸易')) {
      throw new Error('先选择产品后，供应商下拉未按供货关系过滤');
    }
    await page.locator('[data-anchored-select-content][data-state="open"]').getByText('S001 华东饮品供应链').click();
    */
    await productContent.getByText('P000001').waitFor();
    const productOptionsText = await productContent.innerText();
    if (!productOptionsText.includes('P000001 经典原味苏打水') || productOptionsText.includes('P000002 速溶黑咖啡')) {
      throw new Error('选择供应商后，产品下拉未按该供应商供货范围过滤');
    }
    await orderDialog.getByRole('button', { name: '取消', exact: true }).click();

    await page.getByRole('button', { name: '新增采购单' }).click();
    const requiredOrderDialog = page.getByRole('dialog', { name: '新增采购单草稿' });
    await assertRequiredLabels(requiredOrderDialog, ['供应商', '入库仓库']);
    await requiredOrderDialog.getByRole('button', { name: '保存草稿' }).click();
    if (!(await requiredOrderDialog.innerText()).includes('请选择供应商') || !(await requiredOrderDialog.innerText()).includes('请选择入库仓库')) {
      throw new Error('采购订单草稿缺少供应商或仓库必填校验');
    }
    await requiredOrderDialog.getByRole('button', { name: '取消', exact: true }).click();

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
      const purchaseHeader = element.querySelector('thead [data-purchase-no-column]')?.getBoundingClientRect();
      const purchaseCell = element.querySelector('tbody [data-purchase-no-column]')?.getBoundingClientRect();
      const actionsHeader = element.querySelector('thead [data-purchase-actions-column]')?.getBoundingClientRect();
      return {
        overflow: element.scrollWidth - element.clientWidth,
        scrollLeft: element.scrollLeft,
        stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
        purchaseHeaderLeft: purchaseHeader ? purchaseHeader.left - container.left : null,
        purchaseCellLeft: purchaseCell ? purchaseCell.left - container.left : null,
        actionsHeaderRight: actionsHeader ? container.right - actionsHeader.right : null,
      };
    });
    if (narrowLayout.overflow <= 2 || narrowLayout.scrollLeft <= 2 || narrowLayout.stickyCount !== 0
      || narrowLayout.purchaseHeaderLeft === null || narrowLayout.purchaseHeaderLeft >= 0
      || narrowLayout.purchaseCellLeft === null || narrowLayout.purchaseCellLeft >= 0
      || narrowLayout.actionsHeaderRight === null || Math.abs(narrowLayout.actionsHeaderRight) > 2) {
      throw new Error(`较窄视口下采购订单未自然滚动或仍存在固定列：${JSON.stringify(narrowLayout)}`);
    }
    await page.screenshot({ path: screenshotPath('purchase-orders-1115.png'), fullPage: true });

  },
}).then(() => runSmoke({
  route: '/purchase/orders',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: screenshotPath('purchase-orders-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '采购订单' }).waitFor();
    const row = tableRow(page, 'PO202607003');
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
      throw new Error(`采购更新时间或操作列紧凑布局异常：${JSON.stringify(compactCellState)}`);
    }
    const trigger = row.getByRole('button', { name: '更多 PO202607003 操作' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menuItem = page.getByRole('menuitem', { name: '提交采购单', exact: true });
    await menuItem.waitFor();
    const menuMotion = await page.getByRole('menu').evaluate((element) => {
      const style = getComputedStyle(element);
      return { duration: style.animationDuration, transition: style.transitionDuration };
    });
    const durationValues = [menuMotion.duration, menuMotion.transition].flatMap(value => value.split(',')).map(value => {
      const text = value.trim();
      return text.endsWith('ms') ? Number.parseFloat(text) : Number.parseFloat(text) * 1000;
    });
    if (durationValues.some(value => value > 1)) throw new Error(`减少动态效果模式下采购行菜单仍有长动画：${JSON.stringify(menuMotion)}`);
    await page.keyboard.press('Escape');
    await menuItem.waitFor({ state: 'hidden' });
    if (!await trigger.evaluate(element => document.activeElement === element)) throw new Error('采购行菜单关闭后焦点未返回触发器');
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 采购订单页面发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 采购订单无固定列紧凑布局、状态反馈、行操作、业务保护与减弱动效通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
