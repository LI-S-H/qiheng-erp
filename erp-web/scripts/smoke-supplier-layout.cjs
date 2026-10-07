const path = require('path');
const fs = require('fs');
const { runSmoke } = require('./smoke-helpers.cjs');

const output = path.resolve(
  __dirname,
  '..',
  'docs',
  'qa-screenshots',
  '2026-09-18-supplier-layout',
);
fs.mkdirSync(output, { recursive: true });

function assertBusinessContentCentered(page, dialogName) {
  return page.getByRole('dialog', { name: dialogName }).evaluate((dialog) => {
    const main = document.querySelector('main');
    const dialogRect = dialog.getBoundingClientRect();
    const mainRect = main?.getBoundingClientRect();
    const dialogCenter = dialogRect.left + dialogRect.width / 2;
    const mainCenter = mainRect ? mainRect.left + mainRect.width / 2 : NaN;
    return {
      placement: dialog.getAttribute('data-slot'),
      centerOffset: Math.abs(dialogCenter - mainCenter),
      top: dialogRect.top,
    };
  }).then((state) => {
    if (state.placement !== 'dialog-content' || state.centerOffset > 2 || state.top < 0) {
      throw new Error(`${dialogName} 未居中于业务主内容区：${JSON.stringify(state)}`);
    }
  });
}

async function assertListLayout(page, { expectedFieldCount, expectedFilterSizes, actionClass, toolbarClass }) {
  const state = await page.locator('[data-list-filter-panel]').evaluate((panel, currentActionClass) => {
    const grid = panel.querySelector('[data-filter-layout]');
    const fields = [...grid.children].filter((child) => !child.classList.contains('list-filter-panel__actions'));
    const actionCell = document.querySelector(`tbody .${currentActionClass}`);
    const actionHead = document.querySelector(`thead .${currentActionClass}`);
    const viewport = actionCell?.closest('[data-slot="table-container"]');
    return {
      filterLayout: grid?.getAttribute('data-filter-layout'),
      filterDisplay: grid ? getComputedStyle(grid).display : '',
      fieldCount: fields.length,
      filterSizes: fields.map((field) => field.getAttribute('data-filter-size')),
      actionSticky: actionCell ? getComputedStyle(actionCell).position : '',
      actionEdge: actionCell?.getAttribute('data-table-sticky-edge'),
      headerEdge: actionHead?.getAttribute('data-table-sticky-edge'),
      hasOverflow: viewport ? viewport.scrollWidth > viewport.clientWidth + 2 : false,
    };
  }, actionClass);
  if (state.filterLayout !== 'content' || state.filterDisplay !== 'flex' || state.fieldCount !== expectedFieldCount
    || JSON.stringify(state.filterSizes) !== JSON.stringify(expectedFilterSizes)
    || state.actionSticky !== 'sticky' || state.actionEdge !== 'end' || state.headerEdge !== 'end') {
    throw new Error(`筛选区或固定操作列结构异常：${JSON.stringify(state)}`);
  }

  const toolbarState = await page.locator(`.${toolbarClass}`).evaluate((toolbar) => {
    const title = toolbar.querySelector('.table-toolbar__title');
    const right = toolbar.querySelector('[class$="toolbar__right"]');
    const titleRect = title?.getBoundingClientRect();
    const rightRect = right?.getBoundingClientRect();
    return {
      hasTitle: Boolean(title?.textContent?.trim()),
      hasRight: Boolean(right),
      actionsOnRight: Boolean(titleRect && rightRect && rightRect.left >= titleRect.left),
    };
  });
  if (!toolbarState.hasTitle || !toolbarState.hasRight || !toolbarState.actionsOnRight) {
    throw new Error(`工具栏提示与右侧操作区异常：${JSON.stringify(toolbarState)}`);
  }

  const edgeState = await page.locator(`[data-slot="table-container"]:has(.${actionClass})`).evaluate(async (viewport, currentActionClass) => {
    const read = () => {
      const actionCell = viewport.querySelector(`tbody .${currentActionClass}`);
      const actionRow = actionCell?.closest('[data-slot="table-row"]');
      const pseudo = actionCell ? getComputedStyle(actionCell, '::after') : null;
      const viewportRect = viewport.getBoundingClientRect();
      const actionRect = actionCell?.getBoundingClientRect();
      return {
        canScrollEnd: viewport.getAttribute('data-scroll-end'),
        pseudoOpacity: pseudo ? Number.parseFloat(pseudo.opacity) : 0,
        actionRightOffset: actionRect ? Math.abs(viewportRect.right - actionRect.right) : Number.POSITIVE_INFINITY,
        actionRow,
      };
    };
    viewport.scrollLeft = 0;
    viewport.dispatchEvent(new Event('scroll'));
    await new Promise(resolve => setTimeout(resolve, 100));
    const start = read();
    viewport.scrollLeft = Math.floor((viewport.scrollWidth - viewport.clientWidth) / 2);
    viewport.dispatchEvent(new Event('scroll'));
    await new Promise(resolve => setTimeout(resolve, 100));
    const middle = read();
    viewport.scrollLeft = viewport.scrollWidth;
    viewport.dispatchEvent(new Event('scroll'));
    await new Promise(resolve => setTimeout(resolve, 100));
    const end = read();
    return {
      start: { ...start, actionRow: undefined },
      middle: { ...middle, actionRow: undefined },
      end: { ...end, actionRow: undefined },
    };
  }, actionClass);
  const viewport = page.locator(`[data-slot="table-container"]:has(.${actionClass})`);
  const actionCell = viewport.locator(`tbody .${actionClass}`).first();
  await viewport.evaluate((element) => {
    element.scrollLeft = 0;
    element.dispatchEvent(new Event('scroll'));
  });
  await page.waitForTimeout(120);
  await actionCell.hover();
  await page.waitForTimeout(180);
  const hoverOpacity = await actionCell.evaluate((element) =>
    Number.parseFloat(getComputedStyle(element, '::after').opacity));
  if (edgeState.start.canScrollEnd !== 'true' || edgeState.start.pseudoOpacity < 0.7
    || hoverOpacity < 0.95 || edgeState.middle.canScrollEnd !== 'true'
    || edgeState.middle.pseudoOpacity < 0.7 || edgeState.end.canScrollEnd === 'true'
    || edgeState.end.pseudoOpacity > 0.05 || edgeState.start.actionRightOffset > 2
    || edgeState.middle.actionRightOffset > 2 || edgeState.end.actionRightOffset > 2) {
    throw new Error(`固定操作列未显示出入库同款滚动边缘阴影：${JSON.stringify({ ...edgeState, hoverOpacity })}`);
  }
}

async function assertDetailDialog(page, { actionClass, workbenchAttr, title, expectedSections }) {
  const actionCell = page.locator(`tbody .${actionClass}`).first();
  const viewAction = actionCell.getByRole('button', { name: '查看', exact: true });
  await viewAction.hover();
  await page.getByRole('tooltip').waitFor({ state: 'visible' });
  await viewAction.click();
  const workbench = page.locator(`[${workbenchAttr}]`);
  await workbench.waitFor();
  await workbench.getByText(title, { exact: true }).waitFor();
  for (const section of expectedSections) {
    await workbench.getByRole('heading', { name: section, exact: true }).waitFor();
  }
  const state = await workbench.evaluate((element) => {
    const footer = element.querySelector('[data-slot="dialog-footer"]')?.getBoundingClientRect();
    const rect = element.getBoundingClientRect();
    return { footerBottom: footer?.bottom || 0, dialogBottom: rect.bottom };
  });
  if (state.footerBottom > state.dialogBottom + 1) {
    throw new Error(`详情弹窗底部操作区不可见：${JSON.stringify(state)}`);
  }
  await workbench.getByRole('button', { name: '关闭', exact: true }).click();
  await workbench.waitFor({ state: 'hidden' });
}

runSmoke({
  route: '/purchase/suppliers',
  screenshot: path.join(output, 'suppliers-1440x900-list.png'),
  async test(page) {
    await page.getByRole('heading', { name: '供应商管理' }).waitFor();
    await assertListLayout(page, {
      expectedFieldCount: 6,
      expectedFilterSizes: ['compact', 'standard', 'compact', 'compact', 'compact', 'wide'],
      actionClass: 'supplier-table__actions',
      toolbarClass: 'supplier-toolbar',
    });
    await page.getByRole('button', { name: '新增供应商', exact: true }).click();
    await assertBusinessContentCentered(page, '新增供应商');
    const createDialog = page.getByRole('dialog', { name: '新增供应商' });
    await createDialog.getByRole('button', { name: '取消', exact: true }).click();
    await createDialog.waitFor({ state: 'hidden' });

    await page.locator('button[data-row-actions-trigger]').first().click();
    await page.getByRole('menuitem', { name: '调整服务分', exact: true }).click();
    await assertBusinessContentCentered(page, '调整服务分');
    const serviceScoreDialog = page.getByRole('dialog', { name: '调整服务分' });
    await serviceScoreDialog.getByRole('button', { name: '取消', exact: true }).click();
    await serviceScoreDialog.waitFor({ state: 'hidden' });
    await assertDetailDialog(page, {
      actionClass: 'supplier-table__actions',
      workbenchAttr: 'data-supplier-detail-workbench',
      title: '供应商档案',
      expectedSections: ['基础资料', '评分与样本', '维护信息'],
    });
  },
}).then(() => runSmoke({
  route: '/purchase/supplier-products',
  screenshot: path.join(output, 'supplier-products-1440x900-list.png'),
  async test(page) {
    await page.getByRole('heading', { name: '供货产品' }).waitFor();
    await assertListLayout(page, {
      expectedFieldCount: 7,
      expectedFilterSizes: ['standard', 'compact', 'standard', 'standard', 'compact', 'compact', 'compact'],
      actionClass: 'supplier-product-table__actions',
      toolbarClass: 'supplier-product-toolbar',
    });
    await page.getByRole('button', { name: '新增供货关系', exact: true }).click();
    await assertBusinessContentCentered(page, '新增供货关系');
    const createDialog = page.getByRole('dialog', { name: '新增供货关系' });
    await createDialog.getByRole('button', { name: '取消', exact: true }).click();
    await createDialog.waitFor({ state: 'hidden' });
    await assertDetailDialog(page, {
      actionClass: 'supplier-product-table__actions',
      workbenchAttr: 'data-supplier-product-detail-workbench',
      title: '供货关系',
      expectedSections: ['关系资料', '报价与成交', '产品评分', '维护信息'],
    });
  },
})).then(() => {
  console.log('SMOKE_OK: 供应商与供货产品的筛选布局、固定操作列阴影和业务区居中弹窗均通过');
}).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
