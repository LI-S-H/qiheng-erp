const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 数量小数位显示专项冒烟。
 *
 * 用固定的接口响应覆盖两件事：
 * 1. 数量按产品 `quantityPrecision` 固定位数渲染（精度 2 显示两位，精度 0 显示整数），
 *    而不是沿用旧的去尾零/最多四位规则；
 * 2. 分页汇总取后端返回值，前端不再按当前页 records 重算。
 *
 * 汇总值故意与 records 重算结果不一致，只有真正读后端字段才能通过。
 */

/** 精度 2 的称重类产品，两位小数必须完整保留。 */
const WEIGHED_ROW = {
  stockId: '1940000000000000016',
  warehouseId: '1930000000000000008',
  warehouseCode: 'WH008',
  warehouseName: '南京备货仓',
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  unitName: 'kg',
  quantityPrecision: 2,
  stockQty: 25.5,
  lockedQty: 0.5,
  availableQty: 25,
  safetyStockQty: 20.5,
  version: 0,
  updateTime: '2026-07-03 10:30:00',
};

/** 精度 0 的离散类产品，必须显示整数且不带小数点。 */
const DISCRETE_ROW = {
  stockId: '1940000000000000001',
  warehouseId: '1930000000000000001',
  warehouseCode: 'WH001',
  warehouseName: '华东中心仓',
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  unitName: '箱',
  quantityPrecision: 0,
  stockQty: 86,
  lockedQty: 18,
  availableQty: 68,
  safetyStockQty: 12,
  version: 0,
  updateTime: '2026-06-14 09:20:00',
};

/** 与两条 records 重算结果均不相同，用于证明汇总来自后端。 */
const BACKEND_SUMMARY = {
  warehouseCount: 7,
  productCount: 42,
  lowStockCount: 5,
  noAvailableCount: 3,
  lockedCount: 6,
};

const STOCK_PAGE = {
  code: 0,
  message: 'ok',
  data: {
    records: [WEIGHED_ROW, DISCRETE_ROW],
    total: 2,
    hasNext: false,
    pageNum: 1,
    pageSize: 10,
    summary: BACKEND_SUMMARY,
  },
};

async function cellTexts(page, productCode) {
  return tableRow(page, productCode).locator('[data-slot="table-cell"]').allInnerTexts();
}

runSmoke({
  route: '/warehouse/stocks',
  screenshot: 'smoke-warehouse-stock-precision.png',
  async setupPage(page) {
    // 只拦接口请求：/warehouse/stocks 同时也是页面路由，拦错会让浏览器直接显示 JSON
    await page.route('**/api/warehouse/stocks**', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(STOCK_PAGE),
    }));
  },
  async test(page) {
    await page.getByRole('heading', { name: '库存管理' }).waitFor();
    await tableRow(page, 'P000012').waitFor();

    // 精度 2：库存、锁定、可用、安全库存四列都必须是两位小数
    const weighed = await cellTexts(page, 'P000012');
    for (const expected of ['25.50', '0.50', '25.00', '20.50']) {
      if (!weighed.includes(expected)) {
        throw new Error(`kg 产品未按精度 2 渲染，期望出现 ${expected}，实际单元格：${JSON.stringify(weighed)}`);
      }
    }

    // 精度 0：必须是整数，且不能出现小数点
    const discrete = await cellTexts(page, 'P000001');
    for (const expected of ['86', '18', '68', '12']) {
      if (!discrete.includes(expected)) {
        throw new Error(`箱装产品未按精度 0 渲染，期望出现 ${expected}，实际单元格：${JSON.stringify(discrete)}`);
      }
    }
    const discreteQtyCells = discrete.slice(3, 7);
    if (discreteQtyCells.some(text => text.includes('.'))) {
      throw new Error(`精度 0 的数量列不应出现小数点：${JSON.stringify(discreteQtyCells)}`);
    }

    // 汇总必须是后端返回值，而不是按当前两条 records 重算
    const summaryText = await page.getByRole('region', { name: '库存数据汇总' }).innerText();
    for (const expected of ['7', '42', '5', '6']) {
      if (!summaryText.includes(expected)) {
        throw new Error(`汇总未使用后端返回值，期望包含 ${expected}，实际：${JSON.stringify(summaryText)}`);
      }
    }
    if (summaryText.includes('2 ') && !summaryText.includes('42')) {
      throw new Error(`汇总疑似仍按当前页 records 重算：${JSON.stringify(summaryText)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 库存数量按产品精度固定位数渲染，分页汇总取后端返回值');
});
