const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  assertDialogScrollGutter,
  assertSharedListChrome,
  clickQueryAndAssertLoading,
  clickPaginationAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

runSmoke({
  route: '/warehouse/warehouses',
  screenshot: 'smoke-warehouse-warehouses.png',
  async test(page) {
    await page.getByRole('heading', { name: '仓库管理' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '仓库数据汇总', filterLabel: '仓库筛选' });
    const desktopFilterState = await page.getByRole('search', { name: '仓库筛选' }).evaluate(element => ({
      layout: element.querySelector('[data-filter-layout]')?.getAttribute('data-filter-layout'),
      widths: [...element.querySelectorAll('[data-filter-size]')]
        .map(field => Number(field.getBoundingClientRect().width.toFixed(1))),
      overflow: element.scrollWidth - element.clientWidth,
    }));
    if (desktopFilterState.layout !== 'content' || desktopFilterState.widths.join(',') !== '168,220,168,168,168'
      || desktopFilterState.overflow > 1) {
      throw new Error(`仓库筛选桌面内容宽度异常：${JSON.stringify(desktopFilterState)}`);
    }
    await tableRow(page, 'WH001').waitFor();
    const warehouseRemark = tableRow(page, 'WH001').locator('[data-overflow-tooltip]');
    if (await warehouseRemark.count() !== 1) throw new Error('仓库名称下方备注未接入统一溢出提示');
    if (await warehouseRemark.getAttribute('data-overflowing') !== 'true') throw new Error('仓库备注被省略后未标记为可查看完整内容');
    await warehouseRemark.hover();
    const warehouseRemarkTooltip = page.locator('[data-slot="tooltip-content"]');
    await warehouseRemarkTooltip.getByText('华东区域日常收发与调拨仓库', { exact: true }).waitFor();
    const describedBy = await warehouseRemark.getAttribute('aria-describedby');
    if (!describedBy || await page.locator(`#${describedBy}`).count() !== 1) throw new Error('仓库备注完整提示缺少无障碍描述关联');
    await page.screenshot({ path: 'smoke-warehouse-remark-tooltip.png', fullPage: true });
    await page.mouse.move(0, 0);
    await assertFixedTableLayout(page, 9);
    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['本页启用\n8', '本页停用\n2', '联系方式完整\n10', '联系方式待补\n0']) {
      if (!summaryText.includes(expected)) throw new Error(`仓库摘要不正确：缺少 ${expected}`);
    }
    const paginationText = await page.locator('[data-table-pagination]').innerText();
    if (!paginationText.includes('不统计总数') || paginationText.includes('共 12 条')) {
      throw new Error(`仓库分页应使用无总数模式，当前为：${paginationText}`);
    }
    await clickRefreshAndAssertLoading(page, 'smoke-warehouse-refresh-loading.png');

    await page.getByPlaceholder('请输入仓库名称').first().fill('华东');
    await clickQueryAndAssertLoading(page, 'smoke-warehouse-query-loading.png');
    await tableRow(page, 'WH001').waitFor();
    await tableRow(page, 'WH002').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page, 'smoke-warehouse-reset-loading.png');

    await page.getByPlaceholder('请输入联系人').first().fill('林敏');
    await page.getByPlaceholder('请输入联系电话').first().fill('020');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await tableRow(page, 'WH002').waitFor();
    await tableRow(page, 'WH001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'WH011').waitFor();
    await tableRow(page, 'WH001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'WH001').waitFor();

    await page.getByRole('button', { name: '新增仓库' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增仓库' });
    await assertRequiredLabels(createDialog, ['仓库名称', '启用状态']);
    await assertDialogScrollGutter(createDialog);
    const generatedCode = createDialog.locator('[data-warehouse-code]');
    if (await generatedCode.inputValue() !== '保存后由系统生成' || !await generatedCode.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('新增仓库的仓库编码必须由系统生成并以只读方式提示');
    }
    if (!(await createDialog.innerText()).includes('系统生成，创建后不可修改')) {
      throw new Error('仓库编码缺少系统生成和不可修改说明');
    }
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    const emptyFormText = await createDialog.innerText();
    if (!emptyFormText.includes('请输入仓库名称') || emptyFormText.includes('请输入仓库编码')) {
      throw new Error('仓库新增表单缺少必填校验');
    }

    await createDialog.getByPlaceholder('请输入仓库名称').fill('苏州测试仓');
    await createDialog.getByPlaceholder('请输入联系人').fill('顾青');
    await createDialog.getByPlaceholder('请输入联系电话').fill('0512-5558-1013');
    await createDialog.getByPlaceholder('请输入仓库地址').fill('苏州市相城区测试路 13 号');
    await createDialog.getByRole('button', { name: '保存', exact: true }).click();
    await createDialog.waitFor({ state: 'detached' });
    await page.getByText('仓库已创建', { exact: true }).waitFor();

    await page.getByPlaceholder('如 WH001').first().fill('WH013');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const createdRow = tableRow(page, 'WH013');
    await createdRow.waitFor();
    await createdRow.getByText('苏州测试仓', { exact: true }).waitFor();
    await clickResetAndAssertLoading(page);

    const referencedRow = tableRow(page, 'WH001');
    await referencedRow.getByRole('button', { name: '删除' }).click();
    const referencedDelete = page.getByRole('alertdialog', { name: '删除仓库' });
    if (!(await referencedDelete.innerText()).includes('库存余额、入库单、出库单或库存流水')) {
      throw new Error('删除仓库确认未说明库存和流水引用保护');
    }
    await referencedDelete.getByRole('button', { name: '删除', exact: true }).click();
    await page.getByText('仓库存在库存余额、入库单、出库单或库存流水，无法删除', { exact: true }).waitFor();
    await referencedRow.waitFor();

    const editableRow = tableRow(page, 'WH002');
    await editableRow.getByRole('button', { name: '编辑' }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑仓库' });
    const codeInput = editDialog.locator('[data-warehouse-code]');
    if (await codeInput.inputValue() !== 'WH002' || !await codeInput.evaluate(element => element.hasAttribute('readonly'))) {
      throw new Error('编辑仓库时仓库编码必须保持只读');
    }
    await editDialog.getByLabel('停用').click();
    await editDialog.getByRole('button', { name: '保存', exact: true }).click();
    const stopDialog = page.getByRole('alertdialog', { name: '确认停用仓库' });
    const stopText = await stopDialog.innerText();
    if (!stopText.includes('新建采购、销售、退货或库存调整业务') || !stopText.includes('历史单据和现有库存不受影响')) {
      throw new Error(`仓库停用风险说明不完整：${stopText}`);
    }
    if (await page.locator('[data-slot="dialog-content"][inert]').count() !== 1) {
      throw new Error('仓库停用确认出现后，底层编辑弹窗未冻结');
    }
    await stopDialog.getByRole('button', { name: '取消', exact: true }).click();
    await editDialog.getByRole('button', { name: '取消', exact: true }).click();

    const removableRow = tableRow(page, 'WH010');
    await removableRow.getByRole('button', { name: '删除' }).click();
    await page.getByRole('alertdialog', { name: '删除仓库' }).getByRole('button', { name: '删除', exact: true }).click();
    await removableRow.waitFor({ state: 'detached' });

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '仓库管理' }).waitFor();
    await tableRow(page, 'WH001').waitFor();
    const mediumFilterState = await page.getByRole('search', { name: '仓库筛选' }).evaluate(element => {
      const actions = element.querySelector('.list-filter-panel__actions');
      return {
        widths: [...element.querySelectorAll('[data-filter-size]')]
          .map(field => Number(field.getBoundingClientRect().width.toFixed(1))),
        overflow: element.scrollWidth - element.clientWidth,
        actionsReachable: Boolean(actions && actions.getBoundingClientRect().right <= element.getBoundingClientRect().right + 1),
      };
    });
    if (mediumFilterState.widths.join(',') !== '168,220,168,168,168'
      || mediumFilterState.overflow > 1 || !mediumFilterState.actionsReachable) {
      throw new Error(`仓库筛选中等视口布局异常：${JSON.stringify(mediumFilterState)}`);
    }
    await page.screenshot({ path: 'smoke-warehouse-warehouses-1115.png', fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    const mobileFilterState = await page.getByRole('search', { name: '仓库筛选' }).evaluate(element => {
      const grid = element.querySelector('[data-filter-layout]');
      const gridWidth = grid.getBoundingClientRect().width;
      return {
        widths: [...element.querySelectorAll('[data-filter-size]')]
          .map(field => Number(field.getBoundingClientRect().width.toFixed(1))),
        gridWidth: Number(gridWidth.toFixed(1)),
        pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      };
    });
    if (mobileFilterState.widths.some(width => Math.abs(width - mobileFilterState.gridWidth) > 1)
      || mobileFilterState.pageOverflow > 1) {
      throw new Error(`仓库筛选移动端布局异常：${JSON.stringify(mobileFilterState)}`);
    }
    await page.screenshot({ path: 'smoke-warehouse-warehouses-390.png', fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '仓库管理' }).waitFor();
    await tableRow(page, 'WH001').waitFor();
  },
});
