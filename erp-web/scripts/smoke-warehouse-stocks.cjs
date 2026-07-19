const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertSharedListChrome,
  clickQueryAndAssertLoading,
  clickPaginationAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

async function selectRemoteFilter(page, index, keyword, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.getByPlaceholder('输入仓库编码或名称').fill(keyword);
  await content.getByText(label, { exact: true }).click();
}

runSmoke({
  route: '/warehouse/stocks',
  screenshot: 'smoke-warehouse-stocks.png',
  async test(page) {
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '库存数据汇总', filterLabel: '库存筛选' });
    const desktopFilterState = await page.getByRole('search', { name: '库存筛选' }).evaluate(element => ({
      layout: element.querySelector('[data-filter-layout]')?.getAttribute('data-filter-layout'),
      widths: [...element.querySelectorAll('[data-filter-size]')]
        .map(field => Number(field.getBoundingClientRect().width.toFixed(1))),
      overflow: element.scrollWidth - element.clientWidth,
    }));
    if (desktopFilterState.layout !== 'content' || desktopFilterState.widths.join(',') !== '280,220,220,168,168'
      || desktopFilterState.overflow > 1) {
      throw new Error(`库存筛选桌面内容宽度异常：${JSON.stringify(desktopFilterState)}`);
    }
    await tableRow(page, 'P000001').waitFor();
    await assertFixedTableLayout(page, 10);

    const normalRow = tableRow(page, 'P000001');
    const warningRow = tableRow(page, 'P000002');
    const riskVisuals = await Promise.all([normalRow, warningRow].map(row => row.locator('[data-slot="table-cell"]').first().evaluate(element => ({
      background: getComputedStyle(element).backgroundColor,
      marker: getComputedStyle(element).boxShadow,
    }))));
    if (await normalRow.getAttribute('data-stock-risk') !== 'normal'
      || await warningRow.getAttribute('data-stock-risk') !== 'warning'
      || riskVisuals[0].background === riskVisuals[1].background
      || !riskVisuals[0].marker.includes('inset') || !riskVisuals[1].marker.includes('inset')) {
      throw new Error(`正常与低库存行缺少统一浅色背景或左侧状态标识：${JSON.stringify(riskVisuals)}`);
    }
    const warningBackground = riskVisuals[1].background;
    await warningRow.hover();
    const warningHoverBackground = await warningRow.locator('[data-slot="table-cell"]').first().evaluate(element => getComputedStyle(element).backgroundColor);
    if (warningHoverBackground === warningBackground) throw new Error('低库存行悬停后缺少可辨认的背景反馈');
    await page.screenshot({ path: 'smoke-warehouse-stocks-risk-contrast.png', fullPage: true });

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['本页仓库\n4', '本页产品\n9', '本页低库存\n4', '本页已锁定\n5']) {
      if (!summaryText.includes(expected)) throw new Error(`库存摘要不正确：缺少 ${expected}`);
    }
    const paginationText = await page.locator('[data-table-pagination]').innerText();
    if (!paginationText.includes('不统计总数') || paginationText.includes('共 15 条')) {
      throw new Error(`库存分页应使用无总数模式，当前为：${paginationText}`);
    }
    const simplePaginationState = await page.locator('[data-table-pagination]').evaluate(element => ({
      previous: Boolean(element.querySelector('[data-slot="pagination-previous"] svg[data-icon="inline-start"]')),
      next: Boolean(element.querySelector('[data-slot="pagination-next"] svg[data-icon="inline-end"]')),
    }));
    if (!simplePaginationState.previous || !simplePaginationState.next) {
      throw new Error(`库存无总数分页未复用统一箭头样式：${JSON.stringify(simplePaginationState)}`);
    }
    await clickRefreshAndAssertLoading(page, 'smoke-warehouse-stocks-refresh-loading.png');

    await page.getByPlaceholder('请输入产品名称').fill('复印纸');
    await clickQueryAndAssertLoading(page, 'smoke-warehouse-stocks-query-loading.png');
    await tableRow(page, 'P000026').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page, 'smoke-warehouse-stocks-reset-loading.png');

    await selectRemoteFilter(page, 0, 'WH002', 'WH002 华南中心仓');
    await clickQueryAndAssertLoading(page);
    const warehouseRows = page.locator('tbody tr');
    if (await warehouseRows.count() !== 3) throw new Error('仓库筛选未返回预期的 3 条库存记录');
    for (const text of await warehouseRows.allInnerTexts()) {
      if (!text.includes('WH002')) throw new Error(`仓库筛选混入其他仓库：${text}`);
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '低库存');
    await selectFilter(page, 2, '部分锁定');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'P000021').waitFor();
    await tableRow(page, 'P000002').waitFor({ state: 'detached' });
    await tableRow(page, 'P000033').waitFor({ state: 'detached' });
    const lowRows = page.locator('tbody tr');
    if (await lowRows.count() !== 6) throw new Error('库存健康与占用情况未按独立维度执行 AND 组合');
    await clickResetAndAssertLoading(page);

    await page.getByPlaceholder('如 P000001').fill('P000044');
    await clickQueryAndAssertLoading(page);
    const lockedOutRow = tableRow(page, 'P000044');
    await lockedOutRow.waitFor();
    const lockedOutText = await lockedOutRow.innerText();
    if (!lockedOutText.includes('8') || !lockedOutText.includes('0') || !lockedOutText.includes('无可用库存') || !lockedOutText.includes('全部锁定')) {
      throw new Error(`库存数量或状态展示不正确：${lockedOutText}`);
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 1, '零库存');
    await clickQueryAndAssertLoading(page);
    const outOfStockRow = tableRow(page, 'P000027');
    await outOfStockRow.waitFor();
    if (await page.locator('tbody tr').count() !== 1) throw new Error('零库存筛选应只返回当前库存为 0 的记录');
    const criticalVisual = await outOfStockRow.locator('[data-slot="table-cell"]').first().evaluate(element => ({
      risk: element.parentElement?.getAttribute('data-stock-risk'),
      background: getComputedStyle(element).backgroundColor,
      marker: getComputedStyle(element).boxShadow,
    }));
    if (criticalVisual.risk !== 'critical' || criticalVisual.background === riskVisuals[0].background
      || criticalVisual.background === riskVisuals[1].background || !criticalVisual.marker.includes('inset')) {
      throw new Error(`零库存行缺少清晰红色背景与左侧风险标识：${JSON.stringify(criticalVisual)}`);
    }
    await page.screenshot({ path: 'smoke-warehouse-stocks-zero-contrast.png', fullPage: true });
    await clickResetAndAssertLoading(page);

    await clickPaginationAndAssertLoading(page, '下一页');
    await tableRow(page, 'P000037').waitFor();
    await tableRow(page, 'P000001').waitFor({ state: 'detached' });
    await clickPaginationAndAssertLoading(page, '上一页');
    await tableRow(page, 'P000001').waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
    const mediumFilterState = await page.getByRole('search', { name: '库存筛选' }).evaluate(element => {
      const actions = element.querySelector('.list-filter-panel__actions');
      return {
        widths: [...element.querySelectorAll('[data-filter-size]')]
          .map(field => Number(field.getBoundingClientRect().width.toFixed(1))),
        overflow: element.scrollWidth - element.clientWidth,
        actionsReachable: Boolean(actions && actions.getBoundingClientRect().right <= element.getBoundingClientRect().right + 1),
      };
    });
    if (mediumFilterState.widths.join(',') !== '280,220,220,168,168'
      || mediumFilterState.overflow > 1 || !mediumFilterState.actionsReachable) {
      throw new Error(`库存筛选中等视口布局异常：${JSON.stringify(mediumFilterState)}`);
    }
    await page.screenshot({ path: 'smoke-warehouse-stocks-1115.png', fullPage: true });

    await page.setViewportSize({ width: 390, height: 844 });
    const mobileFilterState = await page.getByRole('search', { name: '库存筛选' }).evaluate(element => {
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
      throw new Error(`库存筛选移动端布局异常：${JSON.stringify(mobileFilterState)}`);
    }
    await page.screenshot({ path: 'smoke-warehouse-stocks-390.png', fullPage: true });

    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000001').waitFor();
  },
}).then(() => {
  console.log('SMOKE_OK: 库存余额、库存健康、占用情况、组合筛选、分页与加载反馈通过');
});
