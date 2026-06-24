const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
} = require('./smoke-helpers.cjs');

async function selectFilter(page, index, label) {
  const trigger = page.locator('.filter-panel').getByRole('combobox').nth(index);
  await trigger.click();
  await page.locator('[data-anchored-select-content][data-state="open"]').getByText(label, { exact: true }).click();
}

async function clickButton(page, name) {
  await page.getByRole('button', { name, exact: true }).click();
  await page.waitForTimeout(250);
}

async function waitListSettled(page) {
  await page.locator('[data-list-loading]').waitFor({ state: 'hidden', timeout: 5000 }).catch(() => undefined);
}

async function outerHeaderText(page) {
  return page.locator('[data-slot="table"]').first().evaluate(element => element.querySelector(':scope > thead')?.innerText ?? '');
}

async function outerBodyText(page) {
  return page.locator('[data-slot="table"]').first().evaluate(element => element.querySelector(':scope > tbody')?.innerText ?? '');
}

async function assertStockBillTableUsable(page, billNo) {
  const row = tableRow(page, billNo);
  const layout = await row.evaluate((element) => {
    const cells = Array.from(element.children);
    const createdAt = cells[10];
    const actions = cells[11];
    const createdAtRect = createdAt.getBoundingClientRect();
    const actionsRect = actions.getBoundingClientRect();
    return {
      createdAtOverflow: createdAt.scrollWidth - createdAt.clientWidth,
      actionsOverflow: actions.scrollWidth - actions.clientWidth,
      overlap: createdAtRect.right - actionsRect.left,
      actionsText: actions.innerText,
    };
  });
  if (layout.createdAtOverflow > 1 || layout.actionsOverflow > 1 || layout.overlap > 1) {
    throw new Error(`创建时间和操作列布局异常：${JSON.stringify(layout)}`);
  }

  const tableViewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
  const scrollState = await tableViewport.evaluate((element) => {
    const header = element.querySelector('thead th');
    const headerStyle = header ? getComputedStyle(header) : null;
    return {
      horizontallyScrollable: element.scrollWidth > element.clientWidth + 4,
      height: Math.round(element.getBoundingClientRect().height),
      rowCount: element.querySelectorAll('tbody > tr').length,
      overflowX: getComputedStyle(element).overflowX,
      headerPosition: headerStyle ? headerStyle.position : '',
      headerBackground: headerStyle ? headerStyle.backgroundColor : '',
    };
  });
  const shouldShowPageHeight = scrollState.rowCount >= 8;
  const transparentHeader = !scrollState.headerBackground || scrollState.headerBackground === 'transparent' || /rgba\([^)]*,\s*0\)/.test(scrollState.headerBackground);
  if (!scrollState.horizontallyScrollable || !['auto', 'scroll'].includes(scrollState.overflowX) || (shouldShowPageHeight && scrollState.height < 420) || scrollState.headerPosition !== 'sticky' || transparentHeader) {
    throw new Error(`主表滚动容器高度、横向滚动或固定表头异常：${JSON.stringify(scrollState)}`);
  }
}

async function assertDetailFieldGrid(dialog) {
  const columns = await dialog.locator('.detail-field-grid').evaluate(element =>
    getComputedStyle(element).gridTemplateColumns.split(' ').length,
  );
  if (columns !== 3) throw new Error(`详情字段桌面布局应为 3 列，当前为 ${columns} 列`);
}

async function assertDistinctTypeBadges(page, labels) {
  const classes = await page.locator('[data-stock-bill-id]').evaluateAll((rows, expectedLabels) => {
    const result = {};
    for (const row of rows) {
      const rowText = row.innerText;
      const label = expectedLabels.find(item => rowText.includes(item));
      if (!label) continue;
      const badge = Array.from(row.querySelectorAll('[data-slot="badge"]')).find(item => item.textContent?.includes(label));
      if (badge) result[label] = badge.className;
    }
    return result;
  }, labels);
  for (const label of labels) {
    if (!classes[label]) throw new Error(`列表缺少类型标签：${label}`);
  }
  if (new Set(Object.values(classes)).size !== labels.length) {
    throw new Error(`入库/出库类型标签颜色未明显区分：${JSON.stringify(classes)}`);
  }
}

async function assertExpandedDetailTable(page, direction) {
  const detail = page.locator('.stock-bill-detail-row-scroll').first();
  const state = await detail.evaluate((element) => {
    const card = element.closest('.stock-bill-detail-card');
    const headerText = element.querySelector('thead')?.innerText ?? '';
    return {
      headerText,
      cardWidth: card ? Math.round(card.getBoundingClientRect().width) : 0,
      tableWidth: Math.round(element.querySelector('[data-slot="table"]')?.getBoundingClientRect().width ?? 0),
    };
  });
  const qtyLabel = direction === 'INBOUND' ? '入库量' : '出库量';
  const pendingLabel = direction === 'INBOUND' ? '剩余未入库' : '剩余未出库';
  for (const expected of ['产品编码', '产品名称', '单位', qtyLabel, '合格数量', '不合格数量', pendingLabel, '备注']) {
    if (!state.headerText.includes(expected)) throw new Error(`展开明细表头缺少字段：${expected}`);
  }
  if (state.headerText.includes('质检')) throw new Error('展开明细不应再使用“质检”汇总列');
  if (state.cardWidth > 1040 || state.tableWidth > 1040) {
    throw new Error(`展开明细表格过宽，字段间距会被拉开：${JSON.stringify(state)}`);
  }
}

runSmoke({
  route: '/warehouse/inbound-bills',
  screenshot: 'smoke-warehouse-inbound-bills.png',
  async test(page) {
    await page.getByRole('heading', { name: '入库单' }).waitFor();
    await tableRow(page, 'IB202606140001').waitFor();
    await assertFixedTableLayout(page, 12);
    await assertStockBillTableUsable(page, 'IB202606140001');
    await assertDistinctTypeBadges(page, ['采购入库', '销售退货入库', '调整入库']);
    const inboundHeaderText = await outerHeaderText(page);
    for (const expected of ['入库单号', '类型', '录入方式', '来源类型', '来源单号', '供应商', '仓库', '入库量', '状态', '负责人', '创建时间', '操作']) {
      if (!inboundHeaderText.includes(expected)) throw new Error(`入库单列表表头缺少独立列：${expected}`);
    }
    for (const forbidden of ['入库单号 / 商品', '类型 / 来源', '往来方', '供应商/客户', '供应商 / 仓库', '状态 / 操作']) {
      if (inboundHeaderText.includes(forbidden)) throw new Error(`入库单列表不应使用混合表头：${forbidden}`);
    }

    const summaryText = await page.locator('.summary-strip').innerText();
    for (const expected of ['本页待确认', '本页已确认', '本页已取消', '本页来源生成']) {
      if (!summaryText.includes(expected)) throw new Error(`入库单摘要缺少 ${expected}`);
    }
    await clickButton(page, '刷新');

    await page.getByPlaceholder('如 IB202606140001').fill('IB202606130006');
    await clickButton(page, '查询');
    const pendingInboundRow = tableRow(page, 'IB202606130006');
    await pendingInboundRow.waitFor();
    await assertStockBillTableUsable(page, 'IB202606130006');
    const pendingInboundText = await pendingInboundRow.innerText();
    for (const expected of ['采购入库', '来源生成', '采购订单', '谷仓食品批发', '2 条商品', '待确认']) {
      if (!pendingInboundText.includes(expected)) throw new Error(`待确认入库单列表缺少 ${expected}`);
    }
    for (const forbidden of ['P0003', 'P0009', '本次', '计划', '已处理', '剩余未入库']) {
      if (pendingInboundText.includes(forbidden)) throw new Error(`入库单列表不应展示订单进度字段：${forbidden}`);
    }
    const inboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '每日坚果混合装' });
    if (await inboundItem.isVisible().catch(() => false)) throw new Error('入库单明细默认应收起');
    await pendingInboundRow.getByRole('button', { name: '展开明细' }).click();
    await inboundItem.waitFor();
    await waitListSettled(page);
    await assertExpandedDetailTable(page, 'INBOUND');
    const inboundItemText = await inboundItem.innerText();
    for (const expected of ['P0003', '每日坚果混合装', '0 盒', '40 盒']) {
      if (!inboundItemText.includes(expected)) throw new Error(`入库单商品子行缺少 ${expected}`);
    }
    await pendingInboundRow.getByRole('button', { name: '收起明细' }).click();
    await inboundItem.waitFor({ state: 'hidden' });
    await pendingInboundRow.getByRole('button', { name: '展开明细' }).click();
    await inboundItem.waitFor();
    await page.screenshot({ path: 'smoke-warehouse-inbound-expanded.png', fullPage: true });

    await pendingInboundRow.getByRole('button', { name: '详情' }).click();
    const inboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const inboundDetailText = await inboundDetail.innerText();
    for (const expected of ['供应商', '采购数量', '累计已入库', '本次入库数量', '剩余未入库', '每日坚果混合装']) {
      if (!inboundDetailText.includes(expected)) throw new Error(`入库单详情缺少 ${expected}`);
    }
    await assertDetailFieldGrid(inboundDetail);
    await page.waitForTimeout(250);
    await page.screenshot({ path: 'smoke-warehouse-inbound-detail.png', fullPage: true });
    await inboundDetail.getByRole('button', { name: 'Close' }).click();

    await pendingInboundRow.getByRole('button', { name: '编辑' }).click();
    const editInbound = page.getByRole('dialog', { name: '编辑入库单' });
    const editInboundText = await editInbound.innerText();
    for (const expected of ['采购数量', '累计已入库', '本次入库数量', '剩余未入库']) {
      if (!editInboundText.includes(expected)) throw new Error(`入库编辑弹窗缺少 ${expected}`);
    }
    for (const expected of ['P0003', '每日坚果混合装']) {
      if (!editInboundText.includes(expected)) throw new Error(`入库编辑弹窗产品快照缺少 ${expected}`);
    }
    const editInboundValues = await editInbound.locator('input').evaluateAll(inputs => inputs.map(input => input.value).join('\n'));
    for (const expected of ['0']) {
      if (!editInboundValues.includes(expected)) throw new Error(`入库编辑弹窗输入数据缺少 ${expected}`);
    }
    if (await editInbound.getByRole('button', { name: '添加产品' }).isVisible().catch(() => false)) {
      throw new Error('来源生成的待确认入库单不应允许新增产品');
    }
    const pendingEditComboboxCount = await editInbound.getByRole('combobox').count();
    if (pendingEditComboboxCount !== 0) throw new Error(`待确认入库单不应暴露仓库或产品选择器，当前 ${pendingEditComboboxCount} 个`);
    await page.waitForTimeout(350);
    await page.screenshot({ path: 'smoke-warehouse-inbound-edit.png', fullPage: true });
    await editInbound.getByRole('button', { name: '关闭' }).click();

    await pendingInboundRow.getByRole('button', { name: '确认入库' }).click();
    const confirmInboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const confirmInboundDetailText = await confirmInboundDetail.innerText();
    if (!confirmInboundDetailText.includes('请先核对完整单头和产品明细') || !confirmInboundDetailText.includes('每日坚果混合装')) {
      throw new Error('列表确认入库必须先打开详情并展示完整明细');
    }
    await confirmInboundDetail.getByRole('button', { name: '确认入库' }).click();
    await page.getByText(/请先填写本次入库数量/).waitFor();
    await confirmInboundDetail.getByRole('button', { name: '关闭' }).click();
    await clickButton(page, '重置');
    await page.getByPlaceholder('如 IB202606140001').fill('IB202606140003');
    await clickButton(page, '查询');
    const draftInboundRow = tableRow(page, 'IB202606140003');
    await draftInboundRow.waitFor();
    if (!(await draftInboundRow.innerText()).includes('草稿')) throw new Error('草稿入库单状态缺失');
    await draftInboundRow.getByRole('button', { name: '编辑' }).click();
    const editDraftInbound = page.getByRole('dialog', { name: '编辑入库单' });
    if (!((await editDraftInbound.innerText()).includes('添加产品'))) throw new Error('手工草稿入库单应允许维护产品明细');
    const draftEditComboboxCount = await editDraftInbound.getByRole('combobox').count();
    if (draftEditComboboxCount < 2) throw new Error(`草稿入库单应允许选择仓库和产品，当前选择器 ${draftEditComboboxCount} 个`);
    await page.waitForTimeout(350);
    await page.screenshot({ path: 'smoke-warehouse-inbound-draft-edit.png', fullPage: true });
    await editDraftInbound.getByRole('button', { name: '关闭' }).click();
    await draftInboundRow.getByRole('button', { name: '提交确认' }).click();
    const submitInboundDetail = page.getByRole('dialog', { name: '入库单详情' });
    const submitInboundDetailText = await submitInboundDetail.innerText();
    if (!submitInboundDetailText.includes('请先核对完整单头和产品明细') || !submitInboundDetailText.includes('提交确认') || !submitInboundDetailText.includes('库存盘点调整')) {
      throw new Error('列表提交确认必须先打开详情并展示完整草稿信息');
    }
    await submitInboundDetail.getByRole('button', { name: '提交确认' }).click();
    const submitInbound = page.getByRole('alertdialog', { name: '提交入库单' });
    if (!(await submitInbound.innerText()).includes('不改变库存')) throw new Error('提交确认弹窗应说明不改变库存');
    await submitInbound.getByRole('button', { name: '取消' }).click();
    await submitInboundDetail.getByRole('button', { name: '关闭' }).click();
    await clickButton(page, '重置');

    await selectFilter(page, 1, '采购入库');
    await selectFilter(page, 3, '待确认');
    await clickButton(page, '查询');
    const filteredText = await outerBodyText(page);
    if (!filteredText.includes('采购入库') || !filteredText.includes('待确认')) throw new Error('入库类型与状态筛选未生效');

    await page.getByRole('button', { name: '出库单', exact: true }).click();
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await tableRow(page, 'OB202606140002').waitFor();
    await assertFixedTableLayout(page, 12);
    await assertDistinctTypeBadges(page, ['销售出库', '采购退货出库', '调整出库']);
    const outboundHeaderText = await outerHeaderText(page);
    for (const expected of ['出库单号', '类型', '录入方式', '来源类型', '来源单号', '客户', '仓库', '出库量', '状态', '负责人', '创建时间', '操作']) {
      if (!outboundHeaderText.includes(expected)) throw new Error(`出库单列表表头缺少独立列：${expected}`);
    }
    for (const forbidden of ['出库单号 / 商品', '类型 / 来源', '往来方', '客户/供应商', '客户 / 仓库', '状态 / 操作']) {
      if (outboundHeaderText.includes(forbidden)) throw new Error(`出库单列表不应使用混合表头：${forbidden}`);
    }
    const outboundRow = tableRow(page, 'OB202606140002');
    await assertStockBillTableUsable(page, 'OB202606140002');
    const outboundText = await outboundRow.innerText();
    for (const expected of ['上海星河便利店', '销售出库', '销售订单', '8 箱']) {
      if (!outboundText.includes(expected)) throw new Error(`出库单列表缺少 ${expected}`);
    }
    for (const forbidden of ['本次', '计划', '已处理', '剩余未出库']) {
      if (outboundText.includes(forbidden)) throw new Error(`出库单列表不应展示订单进度字段：${forbidden}`);
    }
    const outboundItem = page.locator('[data-stock-bill-expanded-item-id]').filter({ hasText: '经典原味苏打水' });
    if (await outboundItem.isVisible().catch(() => false)) throw new Error('出库单明细默认应收起');
    await outboundRow.getByRole('button', { name: '展开明细' }).click();
    await outboundItem.waitFor();
    await waitListSettled(page);
    await assertExpandedDetailTable(page, 'OUTBOUND');
    const outboundItemText = await outboundItem.innerText();
    for (const expected of ['P0001', '经典原味苏打水', '0 箱', '8 箱']) {
      if (!outboundItemText.includes(expected)) throw new Error(`出库单商品子行缺少 ${expected}`);
    }
    await outboundRow.getByRole('button', { name: '收起明细' }).click();
    await outboundItem.waitFor({ state: 'hidden' });
    await outboundRow.getByRole('button', { name: '展开明细' }).click();
    await outboundItem.waitFor();
    await outboundRow.getByRole('button', { name: '详情' }).click();
    const outboundDetail = page.getByRole('dialog', { name: '出库单详情' });
    const outboundDetailText = await outboundDetail.innerText();
    for (const expected of ['客户', '销售数量', '累计已出库', '本次出库数量', '剩余未出库']) {
      if (!outboundDetailText.includes(expected)) throw new Error(`出库单详情缺少 ${expected}`);
    }
    await assertDetailFieldGrid(outboundDetail);
    await page.waitForTimeout(250);
    await page.screenshot({ path: 'smoke-warehouse-outbound-detail.png', fullPage: true });
    await outboundDetail.getByRole('button', { name: 'Close' }).click();

    await page.setViewportSize({ width: 1115, height: 838 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await tableRow(page, 'OB202606140002').waitFor();
    const filterColumns = await page.locator('.filter-grid--stock-bills').evaluate(element =>
      getComputedStyle(element).gridTemplateColumns.split(' ').length,
    );
    if (filterColumns !== 2) throw new Error(`入库/出库筛选区在中等宽度下应为两列，当前为 ${filterColumns} 列`);
    await page.screenshot({ path: 'smoke-warehouse-outbound-1115.png', fullPage: true });
  },
}).then(() => {
  console.log('SMOKE_OK: 入库单/出库单列表、详情、编辑、确认弹窗和响应式布局通过');
});
