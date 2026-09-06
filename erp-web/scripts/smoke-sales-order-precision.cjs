const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 销售订单明细数量小数位显示专项冒烟。
 *
 * 明细在详情弹窗里，需要先打开详情再断言。
 * 同一张订单放入两种精度的产品，验证订单数量、锁定数量、已出库和未出库四列都按明细精度渲染。
 * 改造前这四列是裸插值，12.50 会显示成 12.5、10.00 会显示成 10。
 */

const ORDER_ID = '1949000000000000002';

const ORDER_ROW = {
  salesOrderId: ORDER_ID,
  salesNo: 'SO202607180002',
  status: 'APPROVED',
  customerId: '1960000000000000001',
  customerCode: 'C000001',
  customerName: '示例连锁超市',
  warehouseId: '1930000000000000001',
  warehouseName: '华东中心仓',
  totalAmount: '1000.00',
  itemCount: 2,
  lockedAt: '2026-07-18 11:00:00',
  expectedDeliveryDate: '2026-07-20',
  createdById: '1900000000000000001',
  createdByName: '管理员',
  submittedById: '1900000000000000001',
  submittedByName: '管理员',
  approvedById: '1900000000000000001',
  approvedByName: '管理员',
  createTime: '2026-07-18 10:00:00',
  updateTime: '2026-07-18 11:00:00',
  version: 0,
  remark: '',
};

/** 精度 2：25.50 / 0.50 / 10.00 都必须保留两位。 */
const WEIGHED_ITEM = {
  salesOrderItemId: '1949100000000000001',
  salesOrderId: ORDER_ID,
  salesNo: 'SO202607180002',
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  unitName: 'kg',
  quantityPrecision: 2,
  quantity: 25.5,
  lockedQty: 0.5,
  outboundQty: 10,
  unitPrice: '7.90',
  totalAmount: '201.45',
  remark: '',
};

/** 精度 0：必须显示整数。 */
const DISCRETE_ITEM = {
  salesOrderItemId: '1949100000000000002',
  salesOrderId: ORDER_ID,
  salesNo: 'SO202607180002',
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  unitName: '箱',
  quantityPrecision: 0,
  quantity: 86,
  lockedQty: 18,
  outboundQty: 20,
  unitPrice: '48.00',
  totalAmount: '4128.00',
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
  data: { ...ORDER_ROW, items: [WEIGHED_ITEM, DISCRETE_ITEM] },
};

runSmoke({
  route: '/sales/orders',
  screenshot: 'smoke-sales-order-precision.png',
  async setupPage(page) {
    await page.route('**/api/sales/orders**', route => {
      const url = new URL(route.request().url());
      const isDetail = /\/api\/sales\/orders\/[^/]+$/.test(url.pathname);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(isDetail ? ORDER_DETAIL : ORDER_PAGE),
      });
    });
  },
  async test(page) {
    await tableRow(page, 'C000001').waitFor();

    // 打开详情弹窗才能看到明细数量
    await tableRow(page, 'C000001').getByRole('button', { name: '查看' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.waitFor();
    await dialog.getByText('P000012').first().waitFor();

    const dialogText = await dialog.innerText();
    for (const expected of ['25.50', '0.50', '10.00']) {
      if (!dialogText.includes(expected)) {
        throw new Error(`kg 明细未按精度 2 渲染，期望出现 ${expected}，实际弹窗文本：${JSON.stringify(dialogText)}`);
      }
    }
    // 精度 0 的整数不能被补成 86.00
    if (dialogText.includes('86.00') || dialogText.includes('18.00')) {
      throw new Error(`精度 0 的明细被错误补上小数位：${JSON.stringify(dialogText)}`);
    }
    if (!dialogText.includes('86') || !dialogText.includes('18')) {
      throw new Error(`箱装明细数量缺失：${JSON.stringify(dialogText)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 销售订单明细数量按产品精度固定位数渲染');
});
