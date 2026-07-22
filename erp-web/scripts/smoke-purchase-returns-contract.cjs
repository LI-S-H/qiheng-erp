process.env.SMOKE_BASE_URL = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5191';
process.env.VITE_USE_MOCK_API = 'false';

const path = require('path');
const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

const requests = [];
const sourceOrderId = '201000000000000001';
const sourceItemId = '201100000000000001';

function item(returnOrderId, returnOrderItemId) {
  return {
    returnOrderItemId,
    returnOrderId,
    sourceOrderItemId: sourceItemId,
    productId: '200000000000000001',
    productCode: 'P000001',
    productName: '经典原味苏打水',
    unitName: '箱',
    quantityPrecision: 2,
    sourceFulfilledQty: 10,
    availableReturnQty: 6,
    requestedQty: 2,
    approvedQty: 0,
    processedQty: 0,
    unitPrice: 48,
    totalAmount: 96,
    createTime: '2026-07-19 10:00:00',
    updateTime: '2026-07-19 10:00:00',
    remark: '',
  };
}

function detail(id, no, status) {
  return {
    returnOrderId: id,
    returnNo: no,
    returnType: 'PURCHASE_RETURN',
    sourceOrderId,
    sourceOrderNo: 'PO202607900',
    partyId: '202000000000000001',
    partyCode: 'S001',
    partyName: '华东饮品供应链',
    warehouseId: '200000000000000101',
    warehouseName: '南京备货仓',
    expectedExecutionDate: '2026-07-30',
    handlingType: 'REFUND',
    reasonCode: 'QUALITY_ISSUE',
    returnReason: '抽检异常',
    totalAmount: 96,
    status,
    statusReason: '',
    createdById: '190000000000000001',
    createdByName: '采购主管',
    submittedAt: status === 'DRAFT' ? null : '2026-07-19 10:30:00',
    approvedById: status === 'APPROVED' ? '190000000000000001' : null,
    approvedByName: status === 'APPROVED' ? '采购主管' : '',
    approvedAt: status === 'APPROVED' ? '2026-07-19 11:00:00' : null,
    createTime: '2026-07-19 10:00:00',
    updateTime: '2026-07-19 10:00:00',
    remark: '',
    version: 3,
    items: [item(id, `${id}01`)],
  };
}

const records = [
  detail('301', 'PR202607901', 'DRAFT'),
  detail('302', 'PR202607902', 'SUBMITTED'),
  detail('303', 'PR202607903', 'APPROVED'),
];

function ok(data) {
  return { code: 0, message: 'success', data };
}

async function bodyOf(request) {
  const text = request.postData() || '';
  return text ? JSON.parse(text) : null;
}

async function selectRowAction(page, returnNo, actionLabel) {
  await tableRow(page, returnNo).getByRole('button', { name: `更多 ${returnNo} 操作` }).click();
  await page.getByRole('menuitem', { name: actionLabel, exact: true }).click();
}

async function confirmPreviewAction(page, returnNo, actionLabel, nestedTitle, confirmLabel) {
  await selectRowAction(page, returnNo, actionLabel);
  const preview = page.getByRole('dialog', { name: '采购退回详情' });
  await preview.getByRole('button', { name: actionLabel, exact: true }).click();
  const nested = page.getByRole('alertdialog', { name: nestedTitle });
  await nested.getByRole('button', { name: confirmLabel, exact: true }).click();
  await nested.waitFor({ state: 'hidden' });
  await preview.waitFor({ state: 'hidden' });
}

runSmoke({
  route: '/purchase/returns',
  screenshot: path.resolve('qa-artifacts/purchase-returns/purchase-returns-contract.png'),
  async setupPage(page) {
    await page.route('**/api/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (!url.pathname.startsWith('/api/')) {
        await route.continue();
        return;
      }
      const method = request.method();
      const body = await bodyOf(request);
      requests.push({ method, pathname: url.pathname, query: Object.fromEntries(url.searchParams), body });

      let data = null;
      if (method === 'GET' && url.pathname === '/api/purchase/returns') {
        data = { records, total: records.length, pageNum: 1, pageSize: 10, hasNext: false };
      } else if (method === 'GET' && url.pathname === '/api/purchase/suppliers') {
        data = { records: [{ supplierId: '202000000000000001', supplierCode: 'S001', supplierName: '华东饮品供应链', contactName: '张经理', contactPhone: '13800000000', address: '南京市', paymentTerms: '月结 30 天', overallScore: 90, deliveryScore: 90, qualityScore: 92, priceScore: 88, serviceScore: 90, avgDeliveryDays: 3, onTimeRate: 98, qualifiedRate: 99, status: 1, version: 0, remark: '', createTime: '2026-07-01 09:00:00', updateTime: '2026-07-01 09:00:00' }], total: 1, pageNum: 1, pageSize: 10, hasNext: false };
      } else if (method === 'GET' && url.pathname === '/api/purchase/returns/source-orders') {
        data = { records: [{ sourceOrderId, sourceOrderNo: 'PO202607900', partyId: '202000000000000001', partyCode: 'S001', partyName: '华东饮品供应链', warehouseId: '200000000000000101', warehouseName: '南京备货仓', fulfilledItemCount: 1, totalAvailableReturnQty: 6 }], total: 1, pageNum: 1, pageSize: 10, hasNext: false };
      } else if (method === 'GET' && url.pathname === `/api/purchase/returns/source-orders/${sourceOrderId}/items`) {
        data = [{ sourceOrderItemId: sourceItemId, productId: '200000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 2, sourceFulfilledQty: 10, occupiedQty: 4, availableReturnQty: 6, unitPrice: 48 }];
      } else if (method === 'GET' && /^\/api\/purchase\/returns\/\d+$/.test(url.pathname)) {
        data = records.find(row => `/api/purchase/returns/${row.returnOrderId}` === url.pathname);
      } else if (method === 'POST' && url.pathname === '/api/purchase/returns') {
        data = detail('304', 'PR202607904', 'DRAFT');
      } else if (method === 'PUT' && url.pathname === '/api/purchase/returns/301') {
        data = records[0];
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok(data)) });
    });
  },
  async test(page) {
    await page.getByRole('heading', { name: '采购退回' }).waitFor();
    await page.getByPlaceholder('如 PR202607001').fill('PR202607901');
    await page.getByPlaceholder('请选择原采购单').fill('PO202607900');
    const filterPanel = page.getByRole('search', { name: '采购退回筛选' });
    await filterPanel.getByRole('combobox').first().click();
    await page.locator('[data-remote-search-select-content]').last().getByText('S001 华东饮品供应链', { exact: true }).click();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.locator('[data-list-loading]').waitFor({ state: 'hidden' });

    await selectRowAction(page, 'PR202607901', '编辑采购退回');
    const editDialog = page.getByRole('dialog', { name: '编辑采购退回' });
    await editDialog.getByRole('button', { name: '保存修改', exact: true }).click();
    await editDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '新增采购退回' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增采购退回草稿' });
    await createDialog.getByRole('combobox').first().click();
    const options = page.locator('[data-remote-search-select-content]').last();
    await options.getByText('PO202607900', { exact: false }).click();
    const itemRow = createDialog.getByRole('checkbox').first().locator('xpath=ancestor::tr');
    await itemRow.getByRole('checkbox').click();
    await itemRow.locator('input[type="number"]').fill('0.29');
    await createDialog.getByRole('button', { name: '保存草稿', exact: true }).click();
    await createDialog.waitFor({ state: 'hidden' });

    await confirmPreviewAction(page, 'PR202607901', '提交采购退回', '提交采购退回', '确认提交');

    await selectRowAction(page, 'PR202607902', '审核采购退回');
    const approvePreview = page.getByRole('dialog', { name: '采购退回详情' });
    await approvePreview.getByRole('button', { name: '审核采购退回', exact: true }).click();
    const approveDialog = page.getByRole('alertdialog', { name: '审核通过采购退回' });
    await approveDialog.getByRole('button', { name: '审核通过', exact: true }).click();
    await approveDialog.waitFor({ state: 'hidden' });
    await approvePreview.waitFor({ state: 'hidden' });

    await selectRowAction(page, 'PR202607902', '审核退回');
    const rejectPreview = page.getByRole('dialog', { name: '采购退回详情' });
    await rejectPreview.getByRole('button', { name: '审核退回', exact: true }).click();
    const rejectDialog = page.getByRole('dialog', { name: '审核退回采购退回' });
    await rejectDialog.getByPlaceholder('请输入审核退回原因').fill('数量依据需补充');
    await rejectDialog.getByRole('button', { name: '确认退回', exact: true }).click();
    await rejectDialog.waitFor({ state: 'hidden' });
    await rejectPreview.waitFor({ state: 'hidden' });

    await selectRowAction(page, 'PR202607903', '取消采购退回');
    const cancelPreview = page.getByRole('dialog', { name: '采购退回详情' });
    await cancelPreview.getByRole('button', { name: '取消采购退回', exact: true }).click();
    const cancelDialog = page.getByRole('dialog', { name: '取消采购退回' });
    await cancelDialog.getByPlaceholder('请输入取消原因').fill('供应商协商取消');
    await cancelDialog.getByRole('button', { name: '确认取消', exact: true }).click();
    await cancelDialog.waitFor({ state: 'hidden' });
    await cancelPreview.waitFor({ state: 'hidden' });

    await confirmPreviewAction(page, 'PR202607901', '删除草稿', '删除采购退回草稿', '确认删除');

    const find = (method, pathname) => requests.find(request => request.method === method && request.pathname === pathname);
    const listRequest = requests.find(request => request.method === 'GET' && request.pathname === '/api/purchase/returns' && request.query.returnNo);
    if (!listRequest || listRequest.query.returnNo !== 'PR202607901' || listRequest.query.sourceOrderNo !== 'PO202607900'
      || listRequest.query.supplierId !== '202000000000000001' || 'partyId' in listRequest.query
      || listRequest.query.pageNum !== '1' || listRequest.query.pageSize !== '10' || 'keyword' in listRequest.query) {
      throw new Error(`采购退回列表实际查询参数不符合契约：${JSON.stringify(listRequest)}`);
    }
    const createRequest = find('POST', '/api/purchase/returns');
    if (!createRequest || createRequest.body.returnType !== 'PURCHASE_RETURN' || createRequest.body.sourceOrderId !== sourceOrderId
      || createRequest.body.items?.[0]?.sourceOrderItemId !== sourceItemId || createRequest.body.items?.[0]?.requestedQty !== 0.29) {
      throw new Error(`采购退回创建实际请求体不符合契约：${JSON.stringify(createRequest)}`);
    }
    for (const readonly of ['sourceOrderNo', 'partyId', 'partyName', 'warehouseName', 'availableReturnQty', 'approvedQty', 'processedQty', 'status']) {
      if (readonly in createRequest.body) throw new Error(`采购退回创建实际请求体包含只读字段：${readonly}`);
    }
    if (createRequest.body.items.some(item => 'returnOrderItemId' in item)) {
      throw new Error('采购退回创建明细不得提交后端生成的 returnOrderItemId');
    }
    const updateRequest = find('PUT', '/api/purchase/returns/301');
    if (!updateRequest || updateRequest.body.version !== 3 || 'returnType' in updateRequest.body) {
      throw new Error(`采购退回更新实际请求体不符合契约：${JSON.stringify(updateRequest)}`);
    }
    if (updateRequest.body.items.some(item => 'returnOrderItemId' in item)) {
      throw new Error('采购退回更新明细应按 sourceOrderItemId 对齐，不得提交 returnOrderItemId');
    }
    const expectedActions = [
      ['POST', '/api/purchase/returns/301/submit', body => body.version === 3],
      ['POST', '/api/purchase/returns/302/approve', body => body.version === 3 && body.items?.[0]?.approvedQty === 2],
      ['POST', '/api/purchase/returns/302/reject', body => body.version === 3 && body.reason === '数量依据需补充'],
      ['POST', '/api/purchase/returns/303/cancel', body => body.version === 3 && body.reason === '供应商协商取消'],
      ['DELETE', '/api/purchase/returns/301', body => body.version === 3],
    ];
    for (const [method, pathname, verify] of expectedActions) {
      const request = find(method, pathname);
      if (!request || !verify(request.body)) throw new Error(`采购退回动作实际请求不符合契约：${method} ${pathname} ${JSON.stringify(request)}`);
    }
  },
}).then(() => {
  console.log('NETWORK_CONTRACT_OK: 采购退回查询、创建、更新、提交、审核、退回、取消、删除的实际请求均与 OpenAPI 对齐');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
