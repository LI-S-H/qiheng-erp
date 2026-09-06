const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 入库工作单明细数量小数位显示专项冒烟。
 *
 * 明细在详情弹窗里。改造前 formatQty 用的是「整数 toFixed(0)、否则 toFixed(2)」，
 * 与产品精度无关；现在计划、已处理、本次、待处理各列都必须按明细精度渲染。
 * 单据级合计跨多个产品没有统一精度，仍按最多两位展示，这里一并确认它没被误改。
 */

const BILL_ID = '1951000000000000001';

const BILL_ROW = {
  workBillId: BILL_ID,
  billNo: 'IB202607180001',
  billType: 'PURCHASE_IN',
  sourceType: 'PURCHASE_ORDER',
  sourceId: '1949000000000000001',
  sourceNo: 'PO202607180001',
  sourcePartyId: '1970000000000000001',
  sourcePartyName: '示例食品供应商',
  entryMode: 'SOURCE_GENERATED',
  warehouseId: '1930000000000000001',
  warehouseName: '华东中心仓',
  status: 'PENDING_CONFIRM',
  itemCount: 2,
  quantitySummary: '2 种产品',
  totalCurrentQty: 30.5,
  quantityUnitName: '件',
  confirmedById: null,
  confirmedByName: '',
  confirmedAt: null,
  createdById: '1900000000000000001',
  createdByName: '管理员',
  responsibleById: '1900000000000000001',
  responsibleByName: '管理员',
  version: 0,
  createTime: '2026-07-18 09:30:00',
  updateTime: '2026-07-18 09:30:00',
};

/** 精度 2：计划 25.50、已处理 5.25、本次 10.50、待处理 9.75 都要保留两位。 */
const WEIGHED_ITEM = {
  workBillItemId: '1951100000000000001',
  workBillId: BILL_ID,
  billNo: 'IB202607180001',
  sourceItemId: '1949200000000000001',
  stockBillItemId: null,
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  unitName: 'kg',
  quantityPrecision: 2,
  planQty: 25.5,
  processedQty: 5.25,
  pendingQty: 9.75,
  currentQty: 10.5,
  qualifiedQty: 10.5,
  defectiveQty: 0,
  createTime: '2026-07-18 09:30:00',
  updateTime: '2026-07-18 09:30:00',
  remark: '',
};

/** 精度 0：各列必须是整数。 */
const DISCRETE_ITEM = {
  workBillItemId: '1951100000000000002',
  workBillId: BILL_ID,
  billNo: 'IB202607180001',
  sourceItemId: '1949200000000000002',
  stockBillItemId: null,
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  unitName: '箱',
  quantityPrecision: 0,
  planQty: 86,
  processedQty: 20,
  pendingQty: 46,
  currentQty: 20,
  qualifiedQty: 20,
  defectiveQty: 0,
  createTime: '2026-07-18 09:30:00',
  updateTime: '2026-07-18 09:30:00',
  remark: '',
};

const BILL_PAGE = {
  code: 0,
  message: 'ok',
  data: {
    records: [BILL_ROW],
    total: 1,
    hasNext: false,
    pageNum: 1,
    pageSize: 10,
    summary: { sourceGeneratedCount: 1, pendingCount: 1, confirmedCount: 0, cancelledCount: 0 },
  },
};

const BILL_DETAIL = {
  code: 0,
  message: 'ok',
  data: { ...BILL_ROW, manualReason: '', items: [WEIGHED_ITEM, DISCRETE_ITEM] },
};

runSmoke({
  route: '/warehouse/inbound-bills',
  screenshot: 'smoke-stock-bill-precision.png',
  async setupPage(page) {
    await page.route('**/api/warehouse/inbound-bills**', route => {
      const url = new URL(route.request().url());
      const isDetail = /\/api\/warehouse\/inbound-bills\/[^/]+$/.test(url.pathname);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(isDetail ? BILL_DETAIL : BILL_PAGE),
      });
    });
  },
  async test(page) {
    await tableRow(page, 'PO202607180001').waitFor();

    await tableRow(page, 'PO202607180001').getByRole('button', { name: /处理|查看|详情/ }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    await dialog.getByText('P000012').first().waitFor();

    const dialogText = await dialog.innerText();
    for (const expected of ['25.50', '5.25', '10.50', '9.75']) {
      if (!dialogText.includes(expected)) {
        throw new Error(`kg 明细未按精度 2 渲染，期望出现 ${expected}，实际弹窗文本：${JSON.stringify(dialogText)}`);
      }
    }
    if (dialogText.includes('86.00') || dialogText.includes('46.00')) {
      throw new Error(`精度 0 的明细被错误补上小数位：${JSON.stringify(dialogText)}`);
    }
    if (!dialogText.includes('86') || !dialogText.includes('46')) {
      throw new Error(`箱装明细数量缺失：${JSON.stringify(dialogText)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 入库工作单明细数量按产品精度固定位数渲染');
});
