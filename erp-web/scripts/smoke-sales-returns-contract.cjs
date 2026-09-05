process.env.SMOKE_BASE_URL = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
process.env.VITE_USE_MOCK_API = 'false';
process.env.VITE_USE_MOCK_AUTH = 'true';

const path = require('path');
const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

const requests = [];
const sourceOrderId = '211000000000000001';
const sourceItemId = '211100000000000001';

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
    unitPrice: '48.00',
    totalAmount: '96.00',
    createTime: '2026-07-19 10:00:00',
    updateTime: '2026-07-19 10:00:00',
    remark: '',
  };
}

function detail(id, no, status, reasonCode = 'QUALITY_ISSUE') {
  return {
    returnOrderId: id,
    returnNo: no,
    returnType: 'SALES_RETURN',
    sourceOrderId,
    sourceOrderNo: 'SO202607900',
    partyId: '212000000000000001',
    partyCode: 'C001',
    partyName: '华东商贸有限公司',
    warehouseId: '200000000000000101',
    warehouseName: '南京备货仓',
    expectedExecutionDate: '2026-07-30',
    handlingType: 'REFUND',
    reasonCode,
    returnReason: '客户反馈异常',
    totalAmount: '96.00',
    status,
    statusReason: '',
    createdById: '190000000000000001',
    createdByName: '销售主管',
    submittedAt: status === 'DRAFT' ? null : '2026-07-19 10:30:00',
    approvedById: status === 'APPROVED' ? '190000000000000001' : null,
    approvedByName: status === 'APPROVED' ? '销售主管' : '',
    approvedAt: status === 'APPROVED' ? '2026-07-19 11:00:00' : null,
    createTime: '2026-07-19 10:00:00',
    updateTime: '2026-07-19 10:00:00',
    remark: '',
    version: 3,
    items: [item(id, `${id}01`)],
  };
}

const records = [
  // 模拟历史库中仍存在的旧值，验证前端不会因此阻断整个退货页面。
  detail('301', 'SR202607901', 'DRAFT', 'QUALITY'),
  detail('302', 'SR202607902', 'SUBMITTED'),
  detail('303', 'SR202607903', 'APPROVED'),
];

const smokeUser = {
  userId: '1900000000000000001',
  username: 'admin',
  realName: '系统管理员',
  deptId: '1900000000000000100',
  deptName: '行政部',
  isAdmin: true,
  roleCodes: ['SUPER_ADMIN'],
  permissionCodes: [],
};

function ok(data) {
  return { code: 0, message: 'success', data };
}

async function bodyOf(request) {
  const text = request.postData() || '';
  return text ? JSON.parse(text) : null;
}

async function openRowDetail(page, returnNo) {
  await tableRow(page, returnNo).getByRole('button', { name: '处理', exact: true }).click();
}

async function confirmPreviewAction(page, returnNo, actionLabel, nestedTitle, confirmLabel) {
  await openRowDetail(page, returnNo);
  const preview = page.getByRole('dialog', { name: '销售退货详情' });
  await preview.getByRole('button', { name: actionLabel, exact: true }).click();
  const nested = page.getByRole('alertdialog', { name: nestedTitle });
  await nested.getByRole('button', { name: confirmLabel, exact: true }).click();
  await nested.waitFor({ state: 'hidden' });
  await preview.waitFor({ state: 'hidden' });
}

runSmoke({
  route: '/sales/returns',
  screenshot: path.resolve('qa-artifacts/sales-returns/sales-returns-contract.png'),
  autoLogin: false,
  async setupPage(page) {
    await page.addInitScript(() => {
      localStorage.setItem('erp_auth_token', 'smoke-token');
      localStorage.setItem('erp_auth_token_name', 'satoken');
    });
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
      if (method === 'GET' && url.pathname === '/api/auth/me') {
        data = smokeUser;
      } else if (method === 'POST' && url.pathname === '/api/auth/login') {
        data = { token: 'smoke-token', tokenName: 'satoken', user: smokeUser };
      } else if (method === 'GET' && url.pathname === '/api/returns') {
        data = { records, total: records.length, pageNum: 1, pageSize: 10, hasNext: false };
      } else if (method === 'GET' && url.pathname === '/api/sales/customers') {
        data = { records: [{ customerId: '212000000000000001', customerCode: 'C001', customerName: '华东商贸有限公司', contactName: '张经理', contactPhone: '13800000000', address: '南京市', creditLimit: '100000.00', status: 1, version: 0, remark: '', createTime: '2026-07-01 09:00:00', updateTime: '2026-07-01 09:00:00' }], total: 1, pageNum: 1, pageSize: 10, hasNext: false };
      } else if (method === 'GET' && url.pathname === '/api/returns/source-orders') {
        data = [{ sourceOrderId, sourceOrderNo: 'SO202607900', partyId: '212000000000000001', partyCode: 'C001', partyName: '华东商贸有限公司', warehouseId: '200000000000000101', warehouseName: '南京备货仓', fulfilledItemCount: 1, totalAvailableReturnQty: 6 }];
      } else if (method === 'GET' && url.pathname === `/api/returns/source-orders/${sourceOrderId}/items`) {
        // 销售退货来源不读取库存；库存字段为 0 不能阻断仍为 6 的可退数量。
        data = [{ sourceOrderItemId: sourceItemId, productId: '200000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 2, sourceFulfilledQty: 10, stockAvailableQty: 0, occupiedQty: 4, availableReturnQty: 6, unitPrice: '48.00' }];
      } else if (method === 'GET' && /^\/api\/returns\/\d+$/.test(url.pathname)) {
        data = records.find(row => `/api/returns/${row.returnOrderId}` === url.pathname);
      } else if (method === 'POST' && url.pathname === '/api/returns') {
        data = detail('304', 'SR202607904', 'DRAFT');
      } else if (method === 'PUT' && url.pathname === '/api/returns/301') {
        data = records[0];
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok(data)) });
    });
  },
  async test(page) {
    await page.getByRole('heading', { name: '销售退货' }).waitFor();
    await page.getByPlaceholder('如 SR202607001').fill('SR202607901');
    await page.getByPlaceholder('请选择原销售单').fill('SO202607900');
    const filterPanel = page.getByRole('search', { name: '销售退货筛选' });
    await filterPanel.getByRole('combobox').first().click();
    const partyOptions = page.locator('[data-remote-search-select-content]').last();
    await partyOptions.locator('input').fill('C001');
    await partyOptions.getByText('C001 华东商贸有限公司', { exact: true }).click();
    const queryStartedAt = Date.now();
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const pageLoading = page.locator('[data-page-loading]');
    await pageLoading.waitFor({ state: 'visible' });
    const loadingStyle = await pageLoading.evaluate(element => {
      const style = getComputedStyle(element);
      return {
        backgroundColor: style.backgroundColor,
        zIndex: style.zIndex,
        beforeContent: getComputedStyle(element, '::before').content,
        afterContent: getComputedStyle(element, '::after').content,
      };
    });
    if (loadingStyle.backgroundColor === 'rgba(0, 0, 0, 0)' || loadingStyle.zIndex !== '50'
      || loadingStyle.beforeContent !== 'none' || loadingStyle.afterContent !== 'none') {
      throw new Error('销售退货全局加载遮罩必须以不透明中性蒙层完整覆盖页面内容');
    }
    await pageLoading.waitFor({ state: 'hidden' });
    if (Date.now() - queryStartedAt < 240) {
      throw new Error('销售退货全局加载遮罩未保持预期的最短展示时长');
    }

    await openRowDetail(page, 'SR202607901');
    await page.getByRole('dialog', { name: '销售退货详情' }).getByRole('button', { name: '编辑', exact: true }).click();
    const editDialog = page.getByRole('dialog', { name: '编辑销售退货' });
    await editDialog.getByRole('button', { name: '保存修改', exact: true }).click();
    await editDialog.waitFor({ state: 'hidden' });

    await page.getByRole('button', { name: '新增销售退货' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增销售退货草稿' });
    await createDialog.getByRole('combobox').first().click();
    const options = page.locator('[data-remote-search-select-content]').last();
    await options.locator('input').fill('SO202607900');
    await options.getByText('SO202607900', { exact: false }).click();
    const itemRow = createDialog.locator('[data-return-form-items] tbody tr').last();
    await itemRow.getByRole('combobox').click();
    const productOptions = page.locator('[data-remote-search-select-content]').last();
    await productOptions.locator('input').fill('P000001');
    await productOptions.getByText('P000001', { exact: false }).click();
    await itemRow.locator('input[type="number"]').fill('0.29');
    await createDialog.getByRole('button', { name: '保存草稿', exact: true }).click();
    await createDialog.waitFor({ state: 'hidden' });

    await confirmPreviewAction(page, 'SR202607901', '提交销售退货', '提交销售退货', '确认提交');

    await openRowDetail(page, 'SR202607902');
    const approvePreview = page.getByRole('dialog', { name: '销售退货详情' });
    await approvePreview.getByRole('button', { name: '审核销售退货', exact: true }).click();
    const approveDialog = page.getByRole('alertdialog', { name: '审核通过销售退货' });
    await approveDialog.getByRole('button', { name: '审核通过', exact: true }).click();
    await approveDialog.waitFor({ state: 'hidden' });
    await approvePreview.waitFor({ state: 'hidden' });

    await openRowDetail(page, 'SR202607903');
    const cancelPreview = page.getByRole('dialog', { name: '销售退货详情' });
    await cancelPreview.getByRole('button', { name: '取消销售退货', exact: true }).click();
    const cancelDialog = page.getByRole('dialog', { name: '取消销售退货' });
    await cancelDialog.getByPlaceholder('请输入取消原因').fill('客户协商取消');
    await cancelDialog.getByRole('button', { name: '确认取消', exact: true }).click();
    await cancelDialog.waitFor({ state: 'hidden' });
    await cancelPreview.waitFor({ state: 'hidden' });

    await confirmPreviewAction(page, 'SR202607901', '删除草稿', '删除销售退货草稿', '确认删除');

    const find = (method, pathname) => requests.find(request => request.method === method && request.pathname === pathname);
    const listRequest = requests.find(request => request.method === 'GET' && request.pathname === '/api/returns' && request.query.returnNo);
    if (!listRequest || listRequest.query.returnNo !== 'SR202607901' || listRequest.query.sourceOrderNo !== 'SO202607900'
      || listRequest.query.partyId !== '212000000000000001' || 'customerId' in listRequest.query
      || listRequest.query.pageNum !== '1' || listRequest.query.pageSize !== '10' || 'keyword' in listRequest.query) {
      throw new Error(`销售退货列表实际查询参数不符合契约：${JSON.stringify(listRequest)}`);
    }
    const createRequest = find('POST', '/api/returns');
    if (!createRequest || createRequest.body.returnType !== 'SALES_RETURN' || createRequest.body.sourceOrderId !== sourceOrderId
      || createRequest.body.items?.[0]?.sourceOrderItemId !== sourceItemId || createRequest.body.items?.[0]?.requestedQty !== 0.29) {
      throw new Error(`销售退货创建实际请求体不符合契约：${JSON.stringify(createRequest)}`);
    }
    for (const readonly of ['sourceOrderNo', 'partyId', 'partyName', 'warehouseName', 'availableReturnQty', 'approvedQty', 'processedQty', 'status']) {
      if (readonly in createRequest.body) throw new Error(`销售退货创建实际请求体包含只读字段：${readonly}`);
    }
    if (createRequest.body.items.some(item => 'returnOrderItemId' in item)) {
      throw new Error('销售退货创建明细不得提交后端生成的 returnOrderItemId');
    }
    const updateRequest = find('PUT', '/api/returns/301');
    if (!updateRequest || updateRequest.body.version !== 3 || 'returnType' in updateRequest.body) {
      throw new Error(`销售退货更新实际请求体不符合契约：${JSON.stringify(updateRequest)}`);
    }
    if (updateRequest.body.items.some(item => 'returnOrderItemId' in item)) {
      throw new Error('销售退货更新明细应按 sourceOrderItemId 对齐，不得提交 returnOrderItemId');
    }
    const expectedActions = [
      ['POST', '/api/returns/301/submit', body => body.version === 3],
      ['POST', '/api/returns/302/approve', body => body.version === 3 && body.items?.[0]?.approvedQty === 2],
      ['POST', '/api/returns/303/cancel', body => body.version === 3 && body.reason === '客户协商取消'],
      ['DELETE', '/api/returns/301', body => body.version === 3],
    ];
    for (const [method, pathname, verify] of expectedActions) {
      const request = find(method, pathname);
      if (!request || !verify(request.body)) throw new Error(`销售退货动作实际请求不符合契约：${method} ${pathname} ${JSON.stringify(request)}`);
    }
  },
}).then(() => {
  console.log('NETWORK_CONTRACT_OK: 销售退货查询、创建、更新、提交、审核、退回、取消、删除的实际请求均与 OpenAPI 对齐');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
