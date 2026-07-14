const fs = require('fs');
const path = require('path');
const { runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels, assertDialogScrollGutter, assertSharedListChrome, assertContentSizedFilter, clickQueryAndAssertLoading, clickPaginationAndAssertLoading, clickRefreshAndAssertLoading, clickResetAndAssertLoading } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-07-14-140728-product-compact-table',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);
const columnPreferenceStorageKey = 'erp.product.products.table-columns.v1';

fs.mkdirSync(screenshotDirectory, { recursive: true });

runSmoke({
  route: '/product/products',
  screenshot: screenshotPath('product-products-normal.png'),
  async test(page) {
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '产品档案数据汇总', filterLabel: '产品档案筛选' });
    await assertContentSizedFilter(page, [220, 220, 220, 168, 220, 220]);
    await tableRow(page, 'P000001').waitFor();
    await assertFixedTableLayout(page, 10);
    await page.getByText('经典原味苏打水', { exact: true }).waitFor();
    await page.getByText('饮料冲调', { exact: true }).first().waitFor();
    await page.evaluate(key => localStorage.setItem(key, JSON.stringify({ version: 1, visible: ['category'] })), columnPreferenceStorageKey);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
    await assertFixedTableLayout(page, 10);
    const storedColumns = await page.evaluate(key => localStorage.getItem(key), columnPreferenceStorageKey);
    if (storedColumns !== null) throw new Error(`产品档案未清理废弃列偏好：${storedColumns}`);
    if (await page.locator('[data-product-column-trigger], [data-product-column-menu]').count()) {
      throw new Error('产品档案仍保留列显示入口或菜单');
    }
    for (const header of ['产品编码', '产品名称', '分类', '品牌 / 规格', '单位', '参考价格', '安全库存', '状态', '操作']) {
      await page.getByRole('columnheader', { name: header, exact: true }).waitFor();
    }
    const desktopTableState = await page.locator('[data-slot="table-container"]').first().evaluate((element) => {
      const firstRow = element.querySelector('tbody [data-slot="table-row"]');
      const actionCell = firstRow?.lastElementChild;
      const actionGroup = actionCell?.firstElementChild;
      return {
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
        actionWhiteSpace: actionGroup ? getComputedStyle(actionGroup).whiteSpace : '',
        actionOverflow: actionCell ? actionCell.scrollWidth - actionCell.clientWidth : 0,
      };
    });
    if (desktopTableState.scrollWidth > desktopTableState.clientWidth + 1 || desktopTableState.stickyCount !== 0
      || desktopTableState.actionOverflow > 1 || desktopTableState.actionWhiteSpace !== 'nowrap') {
      throw new Error(`1440px 产品档案未实现无固定列紧凑布局：${JSON.stringify(desktopTableState)}`);
    }
    const productNameCell = tableRow(page, 'P000001').locator('[data-slot="table-cell"]').nth(2);
    if (!await productNameCell.locator('[title]').count()) throw new Error('产品名称和条码省略后缺少完整信息提示');
    await page.screenshot({ path: screenshotPath('product-products-compact-1440.png'), fullPage: true });
    await clickRefreshAndAssertLoading(page, screenshotPath('product-products-refresh-loading.png'));

    await page.getByPlaceholder('请输入产品名称').first().fill('A4');
    await clickQueryAndAssertLoading(page, screenshotPath('product-products-query-loading.png'));
    await tableRow(page, 'P000007').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page, screenshotPath('product-products-reset-loading.png'));
    await tableRow(page, 'P000001').waitFor();

    const filterPanel = page.locator('[data-filter-layout="content"]');
    const categoryTrigger = filterPanel.getByRole('combobox').first();
    const selectCategory = async (label) => {
      await categoryTrigger.click();
      const content = page.locator('[data-anchored-select-content][data-state="open"]');
      if (label === '办公用品') await page.screenshot({ path: screenshotPath('product-category-path-options.png'), fullPage: true });
      await content.getByText(label, { exact: true }).click();
      await page.getByRole('button', { name: '查询', exact: true }).click();
    };
    await selectCategory('办公用品');
    await tableRow(page, 'P000005').waitFor();
    await tableRow(page, 'P000007').waitFor();
    await tableRow(page, 'P000008').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await selectCategory('办公用品 / 办公纸品');
    await tableRow(page, 'P000007').waitFor();
    await tableRow(page, 'P000008').waitFor();
    await tableRow(page, 'P000005').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await page.getByPlaceholder('请输入品牌名称').fill('森纸');
    await page.getByPlaceholder('请输入完整条码').fill('6901000000073');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'P000007').waitFor();
    await tableRow(page, 'P000008').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    const queryInput = page.getByPlaceholder('请输入产品名称').first();
    await queryInput.click();
    await page.waitForTimeout(250);
    const queryInputShadow = await queryInput.evaluate(element => getComputedStyle(element).boxShadow);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'P000011').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'P000001').waitFor();

    const pageSizeTrigger = page.locator('[data-table-pagination]').getByRole('combobox');
    const pageSizeMetrics = await pageSizeTrigger.evaluate(element => ({
      width: element.getBoundingClientRect().width,
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
    }));
    if (pageSizeMetrics.width < 80 || pageSizeMetrics.scrollWidth > pageSizeMetrics.clientWidth) {
      throw new Error(`每页条数下拉框宽度不足：${JSON.stringify(pageSizeMetrics)}`);
    }

    await page.getByRole('button', { name: '新增产品' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增产品' });
    await assertRequiredLabels(createDialog, ['产品名称', '单位名称', '启用状态']);
    await assertDialogScrollGutter(createDialog);
    const productCodeDisplay = createDialog.locator('[data-product-code-display]');
    if (await productCodeDisplay.inputValue() !== '保存后由系统生成' || !await productCodeDisplay.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('新增产品的产品编码必须由系统生成并以只读方式提示');
    }
    if (!(await createDialog.innerText()).includes('系统生成，创建后不可修改')) {
      throw new Error('产品编码缺少系统生成和不可修改说明');
    }
    if (await createDialog.locator('[data-currency-prefix]').count() !== 2) {
      throw new Error('参考采购价和参考销售价必须显示人民币前缀');
    }

    const formSelects = createDialog.getByRole('combobox');
    const categorySelect = formSelects.nth(0);
    const unitSelect = formSelects.nth(1);
    await categorySelect.click();
    if (await page.locator('[data-anchored-select-content][data-state="open"]').count() !== 1) {
      throw new Error('打开产品分类后应且仅应存在一个下拉弹层');
    }
    await page.keyboard.press('Escape');
    await page.locator('[data-anchored-select-content][data-state="open"]').waitFor({ state: 'hidden' });
    await unitSelect.click();
    await page.locator('[data-anchored-select-content][data-state="open"]').waitFor();
    const openDropdownCount = await page.locator('[data-anchored-select-content][data-state="open"]').count();
    if (openDropdownCount !== 1 || await categorySelect.getAttribute('aria-expanded') !== 'false' || await unitSelect.getAttribute('aria-expanded') !== 'true') {
      throw new Error(`下拉弹层未互斥：open=${openDropdownCount}`);
    }
    await page.keyboard.press('Escape');

    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    const emptyFormText = await createDialog.innerText();
    if (!emptyFormText.includes('请输入产品名称') || emptyFormText.includes('请输入产品编码')) {
      throw new Error('产品档案新增表单缺少必填校验');
    }
    const remarkInput = createDialog.getByPlaceholder('补充产品采购、销售或仓储注意事项');
    await remarkInput.click();
    await page.waitForTimeout(250);
    const remarkShadow = await remarkInput.evaluate(element => getComputedStyle(element).boxShadow);
    if (remarkShadow !== queryInputShadow || !remarkShadow.includes('10px')) {
      throw new Error(`产品备注与查询输入框焦点辉光不一致：查询=${queryInputShadow} 备注=${remarkShadow}`);
    }
    await page.screenshot({ path: screenshotPath('product-remark-focus.png'), fullPage: true });
    await createDialog.getByPlaceholder('请输入产品名称').fill('系统编码测试产品');
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    await createDialog.waitFor({ state: 'detached' });
    await page.getByText('产品已创建', { exact: true }).waitFor();
    await page.getByPlaceholder('请输入产品名称').first().fill('系统编码测试产品');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const uncategorizedRow = tableRow(page, 'P000016');
    await uncategorizedRow.waitFor();
    await uncategorizedRow.getByText('未分类', { exact: true }).waitFor();
    await clickResetAndAssertLoading(page);

    const referencedRow = tableRow(page, 'P000001');
    const referencedActions = referencedRow.getByRole('button', { name: '更多 P000001 操作' });
    await referencedActions.focus();
    await page.keyboard.press('Enter');
    const referencedMenu = page.getByRole('menu');
    await referencedMenu.getByRole('menuitem', { name: '停用产品', exact: true }).waitFor();
    await page.waitForTimeout(140);
    await page.screenshot({ path: screenshotPath('product-products-row-actions.png'), fullPage: true });
    await referencedMenu.getByRole('menuitem', { name: '删除产品', exact: true }).click();
    const referencedDelete = page.getByRole('alertdialog', { name: '删除产品' });
    if (!(await referencedDelete.innerText()).includes('已被库存或业务单据引用的产品将无法删除')) {
      throw new Error('删除产品确认未说明业务引用保护');
    }
    await referencedDelete.getByRole('button', { name: '删除', exact: true }).click();
    await page.getByText('产品已被库存或业务单据引用，无法删除', { exact: true }).waitFor();
    await referencedRow.waitFor();

    const editableRow = tableRow(page, 'P000002');
    await editableRow.getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑产品' });
    const editCodeDisplay = editDialog.locator('[data-product-code-display]');
    if (await editCodeDisplay.inputValue() !== 'P000002' || !await editCodeDisplay.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('编辑产品时产品编码必须只读且保持原值');
    }
    await editDialog.getByLabel('停用').click();
    await editDialog.getByRole('button', { name: '保存', exact: true }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '确认停用产品' });
    const stopText = await stopDialog.innerText();
    if (!stopText.includes('不能用于新建采购单或销售单') || !stopText.includes('历史业务数据不受影响')) {
      throw new Error(`产品停用风险说明不完整：${stopText}`);
    }
    if (await page.locator('[data-slot="dialog-content"][inert]').count() !== 1) {
      throw new Error('停用确认出现后，底层编辑弹窗未冻结');
    }
    await stopDialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('dialog', { name: '编辑产品' }).getByRole('button', { name: '取消', exact: true }).click();

    const removableRow = tableRow(page, 'P000010');
    await removableRow.getByRole('button', { name: '更多 P000010 操作' }).click();
    await page.getByRole('menuitem', { name: '删除产品', exact: true }).click();
    await page.getByRole('alertdialog', { name: '删除产品' }).getByRole('button', { name: '删除', exact: true }).click();
    await removableRow.waitFor({ state: 'detached' });

    await clickPaginationAndAssertLoading(page, '下一页');
    const disabledCategoryRow = tableRow(page, 'P000013');
    await disabledCategoryRow.getByRole('button', { name: '更多 P000013 操作' }).click();
    await page.getByRole('menuitem', { name: '启用产品', exact: true }).click();
    await page.getByRole('alertdialog', { name: '启用产品' }).getByRole('button', { name: '启用', exact: true }).click();
    await page.getByText('停用分类下不能保存启用产品', { exact: true }).waitFor();
    await disabledCategoryRow.getByText('停用', { exact: true }).waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
    await assertContentSizedFilter(page, [220, 220, 220, 168, 220, 220]);
    const tableContainer = page.locator('[data-slot="table-container"]').first();
    const narrowBefore = await tableContainer.evaluate(element => ({
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
    }));
    await tableContainer.evaluate(element => {
      element.scrollLeft = element.scrollWidth;
      element.dispatchEvent(new Event('scroll'));
    });
    await page.waitForTimeout(100);
    const narrowLayout = await tableContainer.evaluate(element => {
      const container = element.getBoundingClientRect();
      const codeHeader = element.querySelector('thead [data-product-code-column]')?.getBoundingClientRect();
      const actionHeader = element.querySelector('thead th:last-child')?.getBoundingClientRect();
      return {
        scrolled: element.scrollLeft,
        codeLeft: codeHeader ? codeHeader.left - container.left : null,
        actionRight: actionHeader ? container.right - actionHeader.right : null,
      };
    });
    if (narrowBefore.scrollWidth <= narrowBefore.clientWidth + 2 || narrowBefore.stickyCount !== 0 || narrowLayout.scrolled <= 2
      || narrowLayout.codeLeft === null || narrowLayout.codeLeft >= 0
      || narrowLayout.actionRight === null || Math.abs(narrowLayout.actionRight) > 2) {
      throw new Error(`较窄视口下产品表格未自然滚动或仍存在固定列：${JSON.stringify({ narrowBefore, narrowLayout })}`);
    }
    await page.screenshot({ path: screenshotPath('product-products-1115.png'), fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
  },
}).then(() => runSmoke({
  route: '/product/products',
  reducedMotion: 'reduce',
  viewport: { width: 1280, height: 720 },
  screenshot: screenshotPath('product-products-reduced-motion.png'),
  async test(page) {
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P000001').waitFor();

    if (await page.locator('[data-product-column-trigger], [data-product-column-menu]').count()) {
      throw new Error('减少动态效果模式下仍存在产品列显示入口');
    }

    const row = tableRow(page, 'P000001');
    await row.getByRole('button', { name: '更多 P000001 操作' }).focus();
    await page.keyboard.press('Enter');
    await page.getByRole('menuitem', { name: '停用产品', exact: true }).waitFor();
    await page.keyboard.press('Escape');

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    if (overflow > 1) throw new Error(`1280px 产品档案页面发生横向溢出：${overflow}`);
  },
})).then(() => {
  console.log('SMOKE_OK: 产品档案关键列、列偏好、行操作、业务保护与减弱动效通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
