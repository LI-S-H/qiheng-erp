const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 采购订单明细数量小数位显示专项冒烟。
 *
 * 明细在详情弹窗里。改造前订单数量与已入库数量是裸插值，
 * 称重类产品的 25.50 会被显示成 25.5、10.00 会被显示成 10。
 */

const ORDER_ID = '1949000000000000001';

const ORDER_ROW = {
  purchaseOrderId: ORDER_ID,
  purchaseNo: 'PO202607180001',
  status: 'APPROVED',
  supplierId: '1970000000000000001',
  supplierCode: 'S000001',
  supplierName: '示例食品供应商',
  warehouseId: '1930000000000000001',
  warehouseName: '华东中心仓',
  totalAmount: '1000.00',
  itemCount: 2,
  expectedArrivalDate: '2026-07-20',
  createdById: '1900000000000000001',
  createdByName: '管理员',
  submittedById: '1900000000000000001',
  submittedByName: '管理员',
  approvedById: '1900000000000000001',
  approvedByName: '管理员',
  createTime: '2026-07-18 09:00:00',
  updateTime: '2026-07-18 10:00:00',
  version: 0,
  remark: '',
};

/** 精度 2：数量与已入库都必须保留两位。 */
const WEIGHED_ITEM = {
  purchaseOrderItemId: '1949200000000000001',
  purchaseOrderId: ORDER_ID,
  purchaseNo: 'PO202607180001',
  supplierProductId: '1980000000000000001',
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  unitName: 'kg',
  quantityPrecision: 2,
  quantity: 25.5,
  inboundQty: 10,
  unitPrice: '5.20',
  totalAmount: '132.60',
  selectedSupplierScore: 90,
  remark: '',
};

/** 精度 0：必须显示整数。 */
const DISCRETE_ITEM = {
  purchaseOrderItemId: '1949200000000000002',
  purchaseOrderId: ORDER_ID,
  purchaseNo: 'PO202607180001',
  supplierProductId: '1980000000000000002',
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  unitName: '箱',
  quantityPrecision: 0,
  quantity: 86,
  inboundQty: 20,
  unitPrice: '36.00',
  totalAmount: '3096.00',
  selectedSupplierScore: 88,
  remark: '',
};

const ORDER_PAGE = {
  code: 0,
  message: 'ok',
  data: { records: [ORDER_ROW], total: 1, pageNum: 1, pageSize: 10 },
};

const ORDER_DETAIL = {
  code: 0,
  message: 'ok',
  data: {
    ...ORDER_ROW,
    items: [WEIGHED_ITEM, DISCRETE_ITEM],
    // 详情归一化会直接读这两个字段，缺失会抛错并把弹窗关掉
    fulfillmentSummary: { totalAmount: '1000.00', inboundAmount: '0.00', completionRate: 0 },
    timeline: [],
  },
};

runSmoke({
  route: '/purchase/orders',
  screenshot: 'smoke-purchase-order-precision.png',
  async setupPage(page) {
    await page.route('**/api/purchase/orders**', route => {
      const url = new URL(route.request().url());
      const isDetail = /\/api\/purchase\/orders\/[^/]+$/.test(url.pathname);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(isDetail ? ORDER_DETAIL : ORDER_PAGE),
      });
    });
  },
  async test(page) {
    await tableRow(page, 'S000001').waitFor();

    // 操作列文案随可执行动作在“处理”和“查看”之间变化
    await tableRow(page, 'S000001').getByRole('button', { name: /处理|查看/ }).click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    await dialog.getByText('P000012').first().waitFor();

    const dialogText = await dialog.innerText();
    for (const expected of ['25.50', '10.00']) {
      if (!dialogText.includes(expected)) {
        throw new Error(`kg 明细未按精度 2 渲染，期望出现 ${expected}，实际弹窗文本：${JSON.stringify(dialogText)}`);
      }
    }
    if (dialogText.includes('86.00') || dialogText.includes('20.00')) {
      throw new Error(`精度 0 的明细被错误补上小数位：${JSON.stringify(dialogText)}`);
    }
    if (!dialogText.includes('86')) {
      throw new Error(`箱装明细数量缺失：${JSON.stringify(dialogText)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 采购订单明细数量按产品精度固定位数渲染');
});
