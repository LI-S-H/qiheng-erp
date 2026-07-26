const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertSharedListChrome,
  clickQueryAndAssertLoading,
  clickResetAndAssertLoading,
  clickRefreshAndAssertLoading,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-warehouse-stock-ledgers.png',
  async test(page) {
    await page.getByRole('heading', { name: '库存流水' }).waitFor();
    if (new URL(page.url()).pathname !== '/warehouse/stock-bills') {
      throw new Error(`库存流水路由不应跳转到入库单：${page.url()}`);
    }
    await assertSharedListChrome(page, { summaryLabel: '库存流水数据汇总', filterLabel: '库存流水筛选' });
    const firstRow = tableRow(page, 'SL202607180001');
    await firstRow.waitFor();
    await assertFixedTableLayout(page, 9);
    const firstRowText = await firstRow.innerText();
    for (const expected of ['采购入库', '采购订单', 'PO202607180001', '系统生成', '华东中心仓', '管理员']) {
      if (!firstRowText.includes(expected)) throw new Error(`库存流水列表缺少 ${expected}：${firstRowText}`);
    }
    if (!(await firstRow.getByRole('button', { name: /展开.*2 条变动明细/ }).count())) {
      throw new Error('库存流水展开按钮缺少明细数量的无障碍说明');
    }
    const ledgerColumnState = await firstRow.evaluate((row) => {
      const table = row.closest('[data-slot="table"]');
      return {
        headers: [...(table?.querySelectorAll('[data-slot="table-head"]') || [])].map(cell => cell.textContent?.trim()),
        firstColumnWidth: Math.round(table?.querySelector('col')?.getBoundingClientRect().width || 0),
        entryModeMuted: row.querySelector('[data-stock-ledger-entry-mode]')?.classList.contains('text-muted-foreground'),
        sourceTypeMuted: row.querySelector('[data-stock-ledger-source-type]')?.classList.contains('text-muted-foreground'),
      };
    });
    if (ledgerColumnState.headers.slice(0, 5).join('|') !== '流水号|出入库类型|录入方式|来源业务类型|来源业务单号'
      || ledgerColumnState.firstColumnWidth !== 220
      || !ledgerColumnState.entryModeMuted
      || !ledgerColumnState.sourceTypeMuted) {
      throw new Error(`库存流水字段顺序、颜色或流水号列宽未与出入库单统一：${JSON.stringify(ledgerColumnState)}`);
    }
    if (await page.getByRole('button', { name: /新增|编辑|提交|确认|取消/ }).count()) {
      throw new Error('库存流水页面不应提供写操作入口');
    }

    const expandButton = firstRow.getByRole('button', { name: /展开.*明细/ });
    await expandButton.click();
    const detail = page.locator('[data-stock-ledger-detail-id="1950000000000000001"]');
    await detail.waitFor();
    if (!(await detail.innerText()).includes('P000001') || !(await detail.innerText()).includes('+20')) {
      throw new Error('库存流水展开明细未展示产品和正负变动数量');
    }
    const purchaseItem = detail.locator('[data-stock-ledger-expanded-item-id="1950100000000000001"]');
    if (await purchaseItem.locator('[data-stock-ledger-quality="qualified"]').innerText() !== '20'
      || await purchaseItem.locator('[data-stock-ledger-quality="defective"]').innerText() !== '0') {
      throw new Error('采购入库流水明细未展示合格数量或不合格数量');
    }
    const purchaseDetailTableContainer = detail.locator('.warehouse-detail-table-scroll [data-slot="table-container"]');
    const detailHorizontalOverflow = await purchaseDetailTableContainer.evaluate(element => element.scrollWidth - element.clientWidth);
    if (detailHorizontalOverflow > 2) throw new Error(`库存流水展开明细在常规桌面宽度下仍需要横向滚动：${detailHorizontalOverflow}px`);
    const detailLayout = await purchaseDetailTableContainer.evaluate((element) => {
      const card = element.closest('.stock-ledger-detail-card');
      const table = element.querySelector('table');
      const dataCell = table?.querySelector('tbody [data-slot="table-cell"]');
      return {
        cardWidth: Math.round(card?.getBoundingClientRect().width || 0),
        tableWidth: Math.round(table?.getBoundingClientRect().width || 0),
        trailingGap: Math.round(element.clientWidth - (table?.getBoundingClientRect().width || 0)),
        paddingBottom: getComputedStyle(element).paddingBottom,
        dataRowHeight: Math.round(table?.querySelector('tbody tr')?.getBoundingClientRect().height || 0),
        headerRowHeight: Math.round(table?.querySelector('thead tr')?.getBoundingClientRect().height || 0),
        dataCell: dataCell ? {
          height: getComputedStyle(dataCell).height,
          minHeight: getComputedStyle(dataCell).minHeight,
          paddingTop: getComputedStyle(dataCell).paddingTop,
          paddingBottom: getComputedStyle(dataCell).paddingBottom,
        } : null,
      };
    });
    if (detailLayout.cardWidth > 1024 || detailLayout.tableWidth > 1020
      || detailLayout.trailingGap > 1 || detailLayout.paddingBottom !== '0px'
      || Math.abs(detailLayout.dataRowHeight - detailLayout.headerRowHeight) > 1
      || detailLayout.dataCell?.paddingTop !== '12px'
      || detailLayout.dataCell?.paddingBottom !== '12px') {
      throw new Error(`库存流水展开明细宽度未收敛：${JSON.stringify(detailLayout)}`);
    }
    await page.setViewportSize({ width: 1115, height: 838 });
    await page.waitForTimeout(100);
    const mainTableViewport = page.locator('.data-panel > [data-slot="scroll-area"] [data-slot="table-container"]').first();
    const keyColumn = firstRow.locator('.stock-ledger-key-column');
    const detailCard = detail.locator('.stock-ledger-detail-card');
    const stickyPosition = await mainTableViewport.evaluate((viewport) => ({
      leftBefore: viewport.querySelector('[data-stock-ledger-id="1950000000000000001"] .stock-ledger-key-column')?.getBoundingClientRect().left,
      detailCardLeftBefore: viewport.querySelector('[data-stock-ledger-detail-id="1950000000000000001"] .stock-ledger-detail-card')?.getBoundingClientRect().left,
      maxScrollLeft: viewport.scrollWidth - viewport.clientWidth,
    }));
    const scrollSamples = [];
    for (const ratio of [0.25, 0.5, 0.75, 1]) {
      const sample = await mainTableViewport.evaluate((viewport, nextRatio) => new Promise((resolve) => {
        viewport.scrollLeft = (viewport.scrollWidth - viewport.clientWidth) * nextRatio;
        requestAnimationFrame(() => requestAnimationFrame(() => resolve({
          maxScrollLeft: viewport.scrollWidth - viewport.clientWidth,
          scrollLeft: viewport.scrollLeft,
          detailCardLeft: viewport.querySelector('[data-stock-ledger-detail-id="1950000000000000001"] .stock-ledger-detail-card')?.getBoundingClientRect().left,
          detailCardWidth: viewport.querySelector('[data-stock-ledger-detail-id="1950000000000000001"] .stock-ledger-detail-card')?.getBoundingClientRect().width,
          viewportWidth: viewport.getBoundingClientRect().width,
        })));
      }), ratio);
      scrollSamples.push(sample);
    }
    await page.screenshot({ path: 'smoke-warehouse-stock-ledgers-horizontal.png', fullPage: true });
    stickyPosition.leftAfter = await keyColumn.evaluate(element => element.getBoundingClientRect().left);
    await mainTableViewport.evaluate((viewport) => {
      viewport.scrollLeft = 0;
      viewport.dispatchEvent(new Event('scroll'));
    });
    if (stickyPosition.maxScrollLeft <= 2
      || Math.abs(stickyPosition.leftAfter - stickyPosition.leftBefore) > 1
      || scrollSamples.some(sample => Math.abs(sample.detailCardLeft - stickyPosition.detailCardLeftBefore) > 1
        || Math.abs(sample.maxScrollLeft - stickyPosition.maxScrollLeft) > 1
        || sample.detailCardWidth > sample.viewportWidth + 1
        || sample.scrollLeft > stickyPosition.maxScrollLeft + 1)) {
      throw new Error(`库存流水展开明细表未在拖动过程中固定在主表滚动区域：${JSON.stringify({ stickyPosition, scrollSamples })}`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await firstRow.getByRole('button', { name: /收起.*明细/ }).click();

    const salesOutRow = tableRow(page, 'SL202607180002');
    await salesOutRow.getByRole('button', { name: /展开.*明细/ }).click();
    const salesOutDetail = page.locator('[data-stock-ledger-detail-id="1950000000000000002"]');
    await salesOutDetail.waitFor();
    const salesOutItem = salesOutDetail.locator('[data-stock-ledger-expanded-item-id="1950100000000000002"]');
    if (await salesOutItem.locator('[data-stock-ledger-quality="qualified"]').innerText() !== '-'
      || await salesOutItem.locator('[data-stock-ledger-quality="defective"]').innerText() !== '-') {
      throw new Error('销售出库流水明细的合格数量和不合格数量未显示为 -');
    }
    await salesOutRow.getByRole('button', { name: /收起.*明细/ }).click();

    await clickRefreshAndAssertLoading(page);
    await page.getByPlaceholder('如 SL202607180001').fill('SL202607180002');
    await clickQueryAndAssertLoading(page);
    await tableRow(page, 'SL202607180002').waitFor();
    await tableRow(page, 'SL202607180001').waitFor({ state: 'detached' });
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 0, '销售订单');
    await clickQueryAndAssertLoading(page);
    const sourceTypeRows = page.locator('[data-stock-ledger-id]');
    if (await sourceTypeRows.count() !== 1 || !(await sourceTypeRows.first().innerText()).includes('SL202607180002')) {
      throw new Error('库存流水来源业务类型筛选未生效');
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 2, '销售出库');
    await clickQueryAndAssertLoading(page);
    const filteredRows = page.locator('[data-stock-ledger-id]');
    if (await filteredRows.count() !== 1 || !(await filteredRows.first().innerText()).includes('SL202607180002')) {
      throw new Error('库存流水出入库类型筛选未生效');
    }
    await clickResetAndAssertLoading(page);

    await selectFilter(page, 3, '人工调整');
    await clickQueryAndAssertLoading(page);
    const adjustmentRows = page.locator('[data-stock-ledger-id]');
    if (await adjustmentRows.count() !== 2) {
      throw new Error('库存流水录入方式筛选未返回预期的人工调整记录');
    }
    for (const expected of ['SL202607170003', 'SL202607140006']) {
      if (!await tableRow(page, expected).count()) throw new Error(`库存流水录入方式筛选缺少 ${expected}`);
    }
    await clickResetAndAssertLoading(page);

    await page.locator('[data-menu-path="/warehouse/stock-bills"]').click();
    await page.getByRole('heading', { name: '库存流水' }).waitFor();
    if (new URL(page.url()).pathname !== '/warehouse/stock-bills') {
      throw new Error(`菜单未进入库存流水路由：${page.url()}`);
    }
    await firstRow.getByRole('button', { name: /展开.*明细/ }).click();
    await page.locator('[data-stock-ledger-detail-id="1950000000000000001"]').waitFor();
    await page.waitForTimeout(300);
  },
}).then(() => {
  console.log('SMOKE_OK: 库存流水独立路由、只读边界、查询筛选和菜单跳转通过');
});
