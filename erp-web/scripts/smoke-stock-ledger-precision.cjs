const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 库存流水数量小数位显示专项冒烟。
 *
 * 流水明细在展开行里，需要先点开展开按钮再断言。
 * 同一张流水凭证里放入两种精度的产品，验证变动前/合格/变动/变动后各列都按明细精度渲染。
 */

const LEDGER_ID = '1950000000000000001';

const LEDGER_ROW = {
  stockLedgerId: LEDGER_ID,
  billNo: 'SL202607180001',
  billType: 'PURCHASE_IN',
  entryMode: 'SOURCE_GENERATED',
  sourceId: '1949000000000000001',
  sourceNo: 'PO202607180001',
  warehouseId: '1930000000000000001',
  warehouseName: '华东中心仓',
  itemCount: 2,
  confirmedById: '1900000000000000001',
  confirmedByName: '管理员',
  confirmedAt: '2026-07-18 10:20:00',
  createTime: '2026-07-18 10:20:00',
};

/** 精度 2：每一列都必须保留两位小数。 */
const WEIGHED_ITEM = {
  stockLedgerItemId: '1950100000000000001',
  stockLedgerId: LEDGER_ID,
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  unitName: 'kg',
  quantityPrecision: 2,
  beforeQty: 10.5,
  qualifiedQty: 4.25,
  defectiveQty: 0.5,
  changeQty: 4.75,
  afterQty: 15.25,
  remark: '',
};

/** 精度 0：每一列都必须是整数。 */
const DISCRETE_ITEM = {
  stockLedgerItemId: '1950100000000000002',
  stockLedgerId: LEDGER_ID,
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  unitName: '箱',
  quantityPrecision: 0,
  beforeQty: 86,
  qualifiedQty: 20,
  defectiveQty: 0,
  changeQty: 20,
  afterQty: 106,
  remark: '',
};

const LEDGER_PAGE = {
  code: 0,
  message: 'ok',
  data: { records: [LEDGER_ROW], total: 1, hasNext: false, pageNum: 1, pageSize: 10 },
};

const LEDGER_DETAIL = {
  code: 0,
  message: 'ok',
  data: { ...LEDGER_ROW, items: [WEIGHED_ITEM, DISCRETE_ITEM] },
};

runSmoke({
  route: '/warehouse/stock-bills',
  screenshot: 'smoke-stock-ledger-precision.png',
  async setupPage(page) {
    // 列表与详情共用前缀，按是否带路径参数分流
    await page.route('**/api/warehouse/stock-bills**', route => {
      const url = new URL(route.request().url());
      const isDetail = /\/api\/warehouse\/stock-bills\/[^/]+$/.test(url.pathname);
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(isDetail ? LEDGER_DETAIL : LEDGER_PAGE),
      });
    });
  },
  async test(page) {
    // 列表里的流水号会被格式化成 SL-20260718-0001，用原样呈现的来源单号定位更稳
    await tableRow(page, 'PO202607180001').waitFor();

    await tableRow(page, 'PO202607180001').getByRole('button', { name: /展开/ }).click();
    const detail = page.locator('[data-stock-ledger-expanded-item-id="1950100000000000001"]');
    await detail.waitFor();

    const weighed = await detail.allInnerTexts();
    for (const expected of ['10.50', '4.25', '0.50', '15.25']) {
      if (!weighed.join(' ').includes(expected)) {
        throw new Error(`kg 明细未按精度 2 渲染，期望出现 ${expected}，实际：${JSON.stringify(weighed)}`);
      }
    }

    const discreteRow = page.locator('[data-stock-ledger-expanded-item-id="1950100000000000002"]');
    const discrete = (await discreteRow.allInnerTexts()).join(' ');
    for (const expected of ['86', '20', '106']) {
      if (!discrete.includes(expected)) {
        throw new Error(`箱装明细未按精度 0 渲染，期望出现 ${expected}，实际：${JSON.stringify(discrete)}`);
      }
    }
    if (/\b\d+\.\d/.test(discrete)) {
      throw new Error(`精度 0 的明细不应出现小数：${JSON.stringify(discrete)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 库存流水明细数量按产品精度固定位数渲染');
});
