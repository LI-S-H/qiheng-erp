const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

/**
 * 产品档案数量小数位显示专项冒烟。
 *
 * 拦截产品分页接口，用同一页里两条不同精度的产品验证安全库存列：
 * 称重类（精度 2）必须保留两位小数，离散类（精度 0）必须是整数且不带小数点。
 */

/** 精度 2：安全库存 20.5 必须显示为 20.50，不能被去尾零成 20.5。 */
const WEIGHED_PRODUCT = {
  productId: '1920000000000000012',
  productCode: 'P000012',
  productName: '东北长粒香大米',
  categoryId: '1910000000000000111',
  categoryName: '粮油',
  brandName: '谷仓',
  unitName: 'kg',
  quantityPrecision: 2,
  specification: '散装称重',
  barcode: '6901000000127',
  referencePurchasePrice: '5.20',
  referenceSalePrice: '7.90',
  safetyStockQty: 20.5,
  status: 1,
  remark: '按重量计量，允许两位小数',
  createTime: '2026-06-03 10:20:00',
  updateTime: '2026-06-27 18:50:03',
};

/** 精度 0：安全库存 12 必须显示为 12，不能出现 12.00。 */
const DISCRETE_PRODUCT = {
  productId: '1920000000000000001',
  productCode: 'P000001',
  productName: '经典原味苏打水',
  categoryId: '1910000000000000101',
  categoryName: '饮料',
  brandName: '清泉',
  unitName: '箱',
  quantityPrecision: 0,
  specification: '24 罐/箱',
  barcode: '6901000000011',
  referencePurchasePrice: '36.00',
  referenceSalePrice: '48.00',
  safetyStockQty: 12,
  status: 1,
  remark: '',
  createTime: '2026-06-01 09:00:00',
  updateTime: '2026-06-20 10:00:00',
};

const PRODUCT_PAGE = {
  code: 0,
  message: 'ok',
  data: {
    records: [WEIGHED_PRODUCT, DISCRETE_PRODUCT],
    total: 2,
    pageNum: 1,
    pageSize: 10,
  },
};

runSmoke({
  route: '/product/products',
  screenshot: 'smoke-product-precision.png',
  async setupPage(page) {
    // 只拦接口：/product/products 是页面路由，/api/products 才是接口
    await page.route('**/api/products?**', route => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PRODUCT_PAGE),
    }));
  },
  async test(page) {
    await page.getByRole('heading', { name: '产品档案' }).waitFor();
    await tableRow(page, 'P000012').waitFor();

    const weighed = await tableRow(page, 'P000012').locator('[data-slot="table-cell"]').allInnerTexts();
    if (!weighed.includes('20.50')) {
      throw new Error(`kg 产品安全库存未按精度 2 渲染，期望 20.50，实际：${JSON.stringify(weighed)}`);
    }

    const discrete = await tableRow(page, 'P000001').locator('[data-slot="table-cell"]').allInnerTexts();
    if (!discrete.includes('12')) {
      throw new Error(`箱装产品安全库存未按精度 0 渲染，期望 12，实际：${JSON.stringify(discrete)}`);
    }
    if (discrete.some(text => text === '12.00' || text === '12.0')) {
      throw new Error(`精度 0 的安全库存不应补小数位：${JSON.stringify(discrete)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 产品档案安全库存按产品精度固定位数渲染');
});
