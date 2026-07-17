import { getResult, postResult, http } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { getMockProductCategoryScope, getMockProductCategorySnapshot } from '../categories/api';
import type {
  ProductBatchIdsPayload,
  ProductBatchStatusPayload,
  ProductFormPayload,
  ProductListItem,
  ProductQuery,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const mockCategoryNames: Record<string, string> = {
  '1910000000000000101': '饮料冲调',
  '1910000000000000102': '休闲零食',
  '1910000000000000111': '粮油调味',
  '1910000000000000104': '书写文具',
  '1910000000000000105': '办公纸品',
  '1910000000000000107': '清洁用品',
  '1910000000000000108': '家居耗材',
  '1910000000000000110': '电脑周边',
};

const productSeed = [
  ['P000001', '经典原味苏打水', '1910000000000000101', '清泉', '箱', '330ml×24罐', '6901000000011', 36, 49.9, 12, 1, 0, '常备产品，关注安全库存', '2026-06-01 09:20:00', '2026-06-28 07:01:39'],
  ['P000002', '速溶黑咖啡', '1910000000000000101', '晨岛', '盒', '2g×50条', '6901000000028', 42, 69, 8, 1, 0, '', '2026-06-02 09:20:00', '2026-06-27 18:50:03'],
  ['P000007', '每日坚果混合装', '1910000000000000102', '谷仓', '盒', '25g×30袋', '6901000000073', 68, 99, 6, 1, 0, '常备产品，关注安全库存', '2026-06-07 09:20:00', '2026-06-27 18:50:03'],
  ['P000008', '海盐苏打饼干', '1910000000000000102', '谷仓', '箱', '1kg×6袋', '6901000000080', 58, 78, 5, 1, 0, '', '2026-06-08 09:20:00', '2026-06-27 18:50:03'],
  ['P000021', '中性签字笔', '1910000000000000104', '文仪', '盒', '0.5mm 黑色 12支', '6901000000219', 12.5, 19.9, 30, 1, 0, '常备办公用品', '2026-06-03 11:20:00', '2026-06-27 18:50:03'],
  ['P000022', '彩色便利贴', '1910000000000000104', '文仪', '本', '76mm×76mm 400张', '6901000000226', 6.8, 12, 20, 1, 0, '', '2026-06-04 11:20:00', '2026-06-27 18:50:03'],
  ['P000026', 'A4复印纸', '1910000000000000105', '森纸', '箱', '70g 500张×8包', '6901000000264', 92, 119, 15, 1, 0, '常备办公用品', '2026-06-08 11:20:00', '2026-06-27 18:50:03'],
  ['P000027', '热敏标签纸', '1910000000000000105', '森纸', '卷', '80mm×60mm', '6901000000271', 8.5, 15, 40, 1, 0, '', '2026-06-09 11:20:00', '2026-06-27 18:50:03'],
  ['P000033', '浓缩洗衣液', '1910000000000000107', '净量', '瓶', '2kg', '6901000000332', 28, 45, 10, 1, 0, '', '2026-06-06 12:20:00', '2026-06-27 18:50:03'],
  ['P000034', '厨房清洁湿巾', '1910000000000000107', '净量', '包', '40片', '6901000000349', 9.6, 16.9, 18, 1, 0, '', '2026-06-07 12:20:00', '2026-06-27 18:50:03'],
  ['P000037', '加厚垃圾袋', '1910000000000000108', '居安', '卷', '45cm×50cm 30只', '6901000000370', 5.2, 9.9, 25, 1, 0, '', '2026-06-01 13:20:00', '2026-06-27 18:50:03'],
  ['P000038', '无痕粘钩', '1910000000000000108', '居安', '卡', '6只装', '6901000000387', 7.8, 13.9, 15, 1, 0, '', '2026-06-02 13:20:00', '2026-06-27 18:50:03'],
  ['P000043', 'USB-C扩展坞', '1910000000000000110', '拓联', '个', '8合1 灰色', '6901000000431', 126, 199, 4, 0, 0, '所属分类已停用，保留历史数据', '2026-06-07 13:20:00', '2026-06-27 18:50:03'],
  ['P000044', '无线办公鼠标', '1910000000000000110', '拓联', '个', '2.4G 静音版', '6901000000448', 39, 69, 8, 0, 0, '所属分类已停用，保留历史数据', '2026-06-08 13:20:00', '2026-06-28 15:50:13'],
  ['P000012', '东北长粒香大米', '1910000000000000111', '谷仓', 'kg', '散装称重', '6901000000127', 5.2, 7.9, 20.5, 1, 2, '按重量计量，允许两位小数', '2026-06-03 10:20:00', '2026-06-27 18:50:03'],
] as const;

let mockProducts: Array<ProductListItem & { referenced: boolean }> = productSeed.map((item, index) => ({
  productId: (1920000000000000000n + BigInt(item[0].slice(1))).toString(),
  productCode: item[0],
  productName: item[1],
  categoryId: item[2],
  categoryName: mockCategoryNames[item[2]] || null,
  brandName: item[3],
  unitName: item[4],
  quantityPrecision: item[11],
  specification: item[5],
  barcode: item[6],
  referencePurchasePrice: item[7],
  referenceSalePrice: item[8],
  safetyStockQty: item[9],
  status: item[10],
  remark: item[12],
  createTime: item[13],
  updateTime: item[14],
  referenced: index < 8,
}));

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

let nextMockProductSequence = 46;

function generateMockProductCode() {
  const productCode = `P${String(nextMockProductSequence).padStart(6, '0')}`;
  nextMockProductSequence += 1;
  return productCode;
}

function ensureEnabledCategory(payload: Pick<ProductFormPayload, 'categoryId' | 'status'>) {
  if (payload.status === 1 && payload.categoryId && getMockProductCategorySnapshot(payload.categoryId)?.status === 0) {
    throw new Error('停用分类下不能保存启用产品');
  }
}

function normalizeProduct(item: ProductListItem): ProductListItem {
  const quantityPrecision = normalizeFiniteNumber(item.quantityPrecision, 'quantityPrecision');
  if (!Number.isInteger(quantityPrecision) || quantityPrecision < 0 || quantityPrecision > 2) {
    throw new Error('接口字段 quantityPrecision 必须是 0 到 2 的整数');
  }
  return {
    ...item,
    productId: normalizeStringId(item.productId, 'productId'),
    categoryId: normalizeNullableStringId(item.categoryId, 'categoryId'),
    status: normalizeBinaryStatus(item.status),
    quantityPrecision,
    referencePurchasePrice: normalizeFiniteNumber(item.referencePurchasePrice, 'referencePurchasePrice'),
    referenceSalePrice: normalizeFiniteNumber(item.referenceSalePrice, 'referenceSalePrice'),
    safetyStockQty: normalizeFiniteNumber(item.safetyStockQty, 'safetyStockQty'),
  };
}

function normalizeProductPage(page: PageResult<ProductListItem>): PageResult<ProductListItem> {
  return {
    ...page,
    records: page.records.map(normalizeProduct),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

export function getMockProductSnapshot(productId: string): ProductListItem | undefined {
  const product = mockProducts.find(item => item.productId === productId);
  if (!product) return undefined;
  const { referenced: _, ...snapshot } = product;
  return normalizeProduct({ ...snapshot });
}

function filterProducts(params: ProductQuery): PageResult<ProductListItem> {
  let filtered = [...mockProducts];
  const productCode = params.productCode?.trim().toLocaleLowerCase();
  const productName = params.productName?.trim().toLocaleLowerCase();
  const brandName = params.brandName?.trim().toLocaleLowerCase();
  const barcode = params.barcode?.trim();
  if (productCode) filtered = filtered.filter(item => item.productCode.toLocaleLowerCase().includes(productCode));
  if (productName) filtered = filtered.filter(item => item.productName.toLocaleLowerCase().includes(productName));
  if (brandName) filtered = filtered.filter(item => item.brandName.toLocaleLowerCase().includes(brandName));
  if (barcode) filtered = filtered.filter(item => item.barcode === barcode);
  if (params.categoryId && params.categoryId !== 'all') {
    const categoryScope = getMockProductCategoryScope(params.categoryId);
    filtered = filtered.filter(item => Boolean(item.categoryId && categoryScope.has(item.categoryId)));
  }
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => a.productCode.localeCompare(b.productCode));
  const total = filtered.length;
  const start = (params.pageNum - 1) * params.pageSize;
  return {
    records: filtered.slice(start, start + params.pageSize).map(({ referenced: _, ...item }) => item),
    total,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
  };
}

export function listProducts(params: ProductQuery) {
  if (useMockApi) return Promise.resolve(normalizeProductPage(filterProducts(params)));
  const { status, categoryId, productCode, productName, brandName, barcode, ...rest } = params;
  return getResult<PageResult<ProductListItem>>('/products', {
    ...rest,
    ...(productCode?.trim() ? { productCode: productCode.trim() } : {}),
    ...(productName?.trim() ? { productName: productName.trim() } : {}),
    ...(brandName?.trim() ? { brandName: brandName.trim() } : {}),
    ...(barcode?.trim() ? { barcode: barcode.trim() } : {}),
    ...(categoryId && categoryId !== 'all' ? { categoryId } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }).then(normalizeProductPage);
}

export function createProduct(payload: ProductFormPayload) {
  if (useMockApi) {
    ensureEnabledCategory(payload);
    const timestamp = nowText();
    const created: ProductListItem & { referenced: boolean } = {
      productId: String(Date.now()),
      productCode: generateMockProductCode(),
      ...payload,
      categoryName: payload.categoryId ? getMockProductCategorySnapshot(payload.categoryId)?.categoryName || null : null,
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
    };
    mockProducts = [...mockProducts, created];
    return Promise.resolve(normalizeProduct(created));
  }
  return postResult<ProductListItem, ProductFormPayload>('/products', payload).then(normalizeProduct);
}

export async function updateProduct(productId: string, payload: ProductFormPayload) {
  if (useMockApi) {
    ensureEnabledCategory(payload);
    mockProducts = mockProducts.map(item => item.productId === productId
      ? { ...item, ...payload, categoryName: payload.categoryId ? getMockProductCategorySnapshot(payload.categoryId)?.categoryName || null : null, updateTime: nowText() }
      : item);
    const product = mockProducts.find(item => item.productId === productId);
    return product ? normalizeProduct(product) : null;
  }
  const response = await http.put(`/products/${productId}`, payload);
  return normalizeProduct(response.data.data as ProductListItem);
}

export async function updateProductStatus(productId: string, status: ProductListItem['status']) {
  if (useMockApi) {
    const target = mockProducts.find(item => item.productId === productId);
    ensureEnabledCategory({ categoryId: target?.categoryId || null, status });
    mockProducts = mockProducts.map(item => item.productId === productId ? { ...item, status, updateTime: nowText() } : item);
    return null;
  }
  const response = await http.patch(`/products/${productId}/status`, { status });
  return response.data.data as null;
}

export async function deleteProduct(productId: string) {
  if (useMockApi) {
    const target = mockProducts.find(item => item.productId === productId);
    if (target?.referenced) throw new Error('产品已被库存或业务单据引用，无法删除');
    mockProducts = mockProducts.filter(item => item.productId !== productId);
    return null;
  }
  const response = await http.delete(`/products/${productId}`);
  return response.data.data as null;
}

export async function batchUpdateProductStatus(payload: ProductBatchStatusPayload) {
  if (useMockApi) {
    if (payload.status === 1) {
      const invalid = mockProducts.some(item => payload.productIds.includes(item.productId)
        && item.categoryId && getMockProductCategorySnapshot(item.categoryId)?.status === 0);
      if (invalid) throw new Error('所选产品中存在停用分类下的产品，无法启用');
    }
    mockProducts = mockProducts.map(item => payload.productIds.includes(item.productId)
      ? { ...item, status: payload.status, updateTime: nowText() }
      : item);
    return null;
  }
  const response = await http.patch('/products/batch/status', payload);
  return response.data.data as null;
}

export function batchDeleteProducts(payload: ProductBatchIdsPayload) {
  if (useMockApi) {
    if (mockProducts.some(item => payload.productIds.includes(item.productId) && item.referenced)) {
      return Promise.reject(new Error('所选产品中存在已被库存或业务单据引用的数据'));
    }
    mockProducts = mockProducts.filter(item => !payload.productIds.includes(item.productId));
    return Promise.resolve(null);
  }
  return postResult<null, ProductBatchIdsPayload>('/products/batch/delete', payload);
}
