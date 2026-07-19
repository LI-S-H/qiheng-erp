import { getResult, http, postResult } from '@/api/http';
import type { PageResult } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { getMockProductSnapshot, listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import type { WarehouseListItem } from '@/modules/warehouse/warehouses/types';
import type {
  PurchaseOrderFormPayload,
  PurchaseOrderDetail,
  PurchaseOrderItem,
  PurchaseOrderListItem,
  PurchaseOrderPage,
  PurchaseOrderQuery,
  PurchaseOrderStatus,
  SupplierBatchIdsPayload,
  SupplierBatchStatusPayload,
  SupplierFormPayload,
  SupplierListItem,
  SupplierOption,
  SupplierProductBatchIdsPayload,
  SupplierProductBatchStatusPayload,
  SupplierProductFormPayload,
  SupplierProductListItem,
  SupplierProductQuery,
  SupplierQuery,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const supplierSeed = [
  ['S001', '华东饮品供应链', '陆明', '021-6628-1001', '上海市嘉定区安亭镇', '月结30天', 92.6, 94.2, 96.1, 88.4, 91.5, 3.8, 96.5, 98.2, 1, true],
  ['S002', '晨岛咖啡贸易', '林珊', '0571-6628-1002', '杭州市钱塘区', '预付30% 到货结清', 88.3, 89.7, 93.4, 84.2, 86.9, 5.2, 91.1, 96.4, 1, true],
  ['S003', '谷仓食品批发', '周可', '025-6628-1003', '南京市江宁区', '月结45天', 90.8, 92.4, 95.2, 87.6, 89.3, 4.6, 94.8, 97.9, 1, true],
  ['S004', '文仪办公渠道', '朱婷', '020-6628-1004', '广州市天河区', '月结30天', 86.2, 85.4, 90.5, 88.1, 83.6, 6.1, 88.7, 95.2, 1, true],
  ['S005', '森纸纸业集团', '宋元', '0512-6628-1005', '苏州市工业园区', '月结60天', 94.1, 95.8, 97.2, 91.6, 92.4, 3.3, 97.4, 98.9, 1, true],
  ['S006', '拓联数码配件', '陈意', '0755-6628-1006', '深圳市龙华区', '现款现货', 79.8, 78.2, 82.4, 84.6, 75.8, 8.4, 82.1, 90.7, 0, false],
] as const;

let mockSuppliers: Array<SupplierListItem & { referenced: boolean }> = supplierSeed.map((item, index) => ({
  supplierId: `2010000000000000${String(index + 1).padStart(3, '0')}`,
  supplierCode: item[0],
  supplierName: item[1],
  contactName: item[2],
  contactPhone: item[3],
  address: item[4],
  paymentTerms: item[5],
  overallScore: item[6],
  deliveryScore: item[7],
  qualityScore: item[8],
  priceScore: item[9],
  serviceScore: item[10],
  avgDeliveryDays: item[11],
  onTimeRate: item[12],
  qualifiedRate: item[13],
  status: item[14],
  version: 0,
  remark: index < 3 ? '常用供应商，可用于采购建议候选' : '',
  createTime: `2026-06-${String(2 + index).padStart(2, '0')} 09:10:00`,
  updateTime: `2026-06-${String(12 + (index % 4)).padStart(2, '0')} 15:30:00`,
  referenced: item[15],
}));

type SupplierProductSeed = [string, string, string, number, number, number, number, number, number, number, string | null, 0 | 1, boolean];

const supplierProductSeed: SupplierProductSeed[] = [
  ['S001', 'P000001', 'HD-SD330', 35.2, 10, 3, 94.2, 96.1, 88.4, 92.3, '2026-06-13 10:20:00', 1, true],
  ['S002', 'P000002', 'CD-CF50', 40.5, 8, 5, 89.7, 93.4, 84.2, 88.9, '2026-06-10 11:20:00', 1, true],
  ['S003', 'P000007', 'GC-NUT30', 68, 6, 4, 92.4, 95.2, 87.6, 91.8, '2026-06-11 14:10:00', 1, true],
  ['S003', 'P000008', 'GC-CK06', 58, 5, 4, 92.4, 95.2, 87.6, 91.4, '2026-06-09 09:40:00', 1, true],
  ['S004', 'P000021', 'WY-PEN12', 12.5, 20, 6, 85.4, 90.5, 88.1, 87.2, null, 1, false],
  ['S005', 'P000026', 'SZ-A4-70G', 92, 12, 3, 95.8, 97.2, 91.6, 94.8, '2026-06-12 13:50:00', 1, true],
  ['S006', 'P000043', 'TL-HUB8', 126, 2, 8, 78.2, 82.4, 84.6, 81.3, null, 0, false],
  ['S001', 'P000002', 'HD-CF50-HIS', 40.5, 8, 5, 94.2, 96.1, 88.4, 91.4, '2026-06-14 09:12:00', 0, true],
  ['S003', 'P000033', 'GC-LD2K-HIS', 28, 6, 4, 92.4, 95.2, 87.6, 90.1, '2026-06-13 15:28:00', 0, true],
  ['S004', 'P000038', 'WY-HOOK6-HIS', 7.8, 20, 6, 85.4, 90.5, 88.1, 86.8, '2026-06-12 17:36:00', 0, true],
];

let mockSupplierProducts: Array<SupplierProductListItem & { referenced: boolean }> = supplierProductSeed.map((item, index) => {
  const supplier = mockSuppliers.find(s => s.supplierCode === item[0])!;
  const product = mockProductSnapshot(item[1]);
  return {
    supplierProductId: `2011000000000000${String(index + 1).padStart(3, '0')}`,
    supplierId: supplier.supplierId,
    supplierCode: supplier.supplierCode,
    supplierName: supplier.supplierName,
    productId: product.productId,
    productCode: product.productCode,
    productName: product.productName,
    unitName: product.unitName,
    quantityPrecision: product.quantityPrecision,
    supplierProductCode: item[2],
    latestPurchasePrice: item[3],
    minOrderQty: item[4],
    leadTimeDays: item[5],
    deliveryScore: item[6],
    qualityScore: item[7],
    priceScore: item[8],
    aiScore: item[9],
    lastPurchaseAt: item[10],
    status: item[11],
    version: 0,
    remark: index < 3 ? '采购建议优先候选' : '',
    createTime: `2026-06-${String(3 + index).padStart(2, '0')} 10:00:00`,
    updateTime: `2026-06-${String(12 + (index % 4)).padStart(2, '0')} 16:10:00`,
    referenced: item[12],
  };
});

let mockOrders: PurchaseOrderDetail[] = [
  buildOrderSeed('PO202607001', 'S001', 'WH001', 'APPROVED', '2026-07-24', [['HD-SD330', 24, 35.2]], '采购主管', true),
  buildOrderSeed('PO202607002', 'S005', 'WH008', 'PARTIAL_INBOUND', '2026-07-22', [['SZ-A4-70G', 18, 92]], '采购主管', true),
  buildOrderSeed('PO202607003', 'S004', 'WH005', 'DRAFT', '2026-07-28', [['WY-PEN12', 30, 12.5]], '系统管理员', false),
  buildOrderSeed('PO202607004', 'S003', 'WH003', 'SUBMITTED', '2026-07-30', [['GC-NUT30', 16, 68]], '采购主管', true),
  buildOrderSeed('PO202607005', 'S001', 'WH001', 'SUBMITTED', '2026-07-30', [['HD-SD330', 12, 35.2], ['CD-CF50', 10, 40.5]], '采购主管', true),
  buildOrderSeed('PO202607006', 'S004', 'WH005', 'INBOUND_DONE', '2026-07-20', [['WY-PEN12', 20, 12.5]], '采购主管', true),
  buildOrderSeed('PO202607007', 'S002', 'WH001', 'CANCELLED', '2026-07-26', [['CD-CF50', 8, 40.5]], '系统管理员', true),
  buildOrderSeed('PO202606001', 'S001', 'WH001', 'INBOUND_DONE', '2026-06-14', [['HD-SD330', 48, 35.2, '对应 IB202606140001'], ['HD-CF50-HIS', 20, 40.5, '对应 IB202606140001']], '采购主管', true),
  buildOrderSeed('PO202606002', 'S003', 'WH003', 'APPROVED', '2026-06-13', [['GC-NUT30', 60, 68, '对应 IB202606130006，待确认'], ['GC-LD2K-HIS', 27, 28, '对应 IB202606130006，待确认']], '采购主管', true),
  buildOrderSeed('PO202606003', 'S004', 'WH005', 'APPROVED', '2026-06-12', [['WY-HOOK6-HIS', 40, 7.8, '对应 IB202606120009，待确认']], '采购主管', true),
  buildOrderSeed('PO202606004', 'S004', 'WH001', 'CANCELLED', '2026-06-11', [['WY-PEN12', 20, 12.5, '历史取消采购单']], '采购主管', true),
];

let nextSupplierSequence = supplierSeed.length + 1;
let nextSupplierProductSequence = mockSupplierProducts.length + 1;
let nextPurchaseOrderSequence = 8;

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function assertOptimisticVersion(current: number, expected: number | undefined) {
  if (expected !== undefined && current !== expected) throw new Error('数据已被其他人修改，请刷新后重试');
}

function mockProductSnapshot(productCode: string) {
  const productId = (1920000000000000000n + BigInt(productCode.slice(1))).toString();
  const product = getMockProductSnapshot(productId);
  if (!product) throw new Error(`产品 ${productCode} 不存在`);
  return product;
}

function buildOrderSeed(
  purchaseNo: string,
  supplierCode: string,
  warehouseCode: string,
  status: PurchaseOrderStatus,
  expectedArrivalDate: string,
  lines: Array<[string, number, number, string?]>,
  createdByName: string,
  submitted: boolean,
): PurchaseOrderDetail {
  const supplier = mockSuppliers.find(item => item.supplierCode === supplierCode)!;
  const warehouses: Record<string, { warehouseId: string; warehouseName: string }> = {
    WH001: { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    WH002: { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    WH003: { warehouseId: '1930000000000000003', warehouseName: '华北中心仓' },
    WH005: { warehouseId: '1930000000000000005', warehouseName: '武汉中转仓' },
    WH008: { warehouseId: '1930000000000000008', warehouseName: '南京备货仓' },
  };
  const warehouse = warehouses[warehouseCode] || warehouses.WH001;
  const orderIds: Record<string, string> = {
    PO202606001: '2012000000000000101',
    PO202606002: '2012000000000000102',
    PO202606003: '2012000000000000103',
    PO202606004: '2012000000000000104',
  };
  const purchaseOrderId = orderIds[purchaseNo] || `2012000000000000${purchaseNo.slice(-3)}`;
  const itemIds: Record<string, string[]> = {
    PO202606001: ['2012100000000000101', '2012100000000000102'],
    PO202606002: ['2012100000000000103', '2012100000000000104'],
    PO202606003: ['2012100000000000105'],
    PO202606004: ['2012100000000000106'],
    PO202607001: ['2012100000000000001'],
    PO202607002: ['2012100000000000002'],
    PO202607003: ['2012100000000000003'],
    PO202607004: ['2012100000000000004'],
    PO202607005: ['2012100000000000005', '2012100000000000006'],
    PO202607006: ['2012100000000000007'],
    PO202607007: ['2012100000000000008'],
  };
  const items = lines.map((line, index) => {
    const supplierProduct = mockSupplierProducts.find(item => item.supplierProductCode === line[0]);
    const product = supplierProduct || mockSupplierProducts[0];
    return normalizeOrderItem({
      purchaseOrderItemId: itemIds[purchaseNo][index],
      purchaseOrderId,
      purchaseNo,
      supplierProductId: product.supplierProductId,
      productId: product.productId,
      productCode: product.productCode,
    productName: product.productName,
    unitName: product.unitName,
    quantityPrecision: product.quantityPrecision,
      quantity: line[1],
      inboundQty: status === 'PARTIAL_INBOUND' ? Math.floor(line[1] / 2) : status === 'INBOUND_DONE' ? line[1] : 0,
      unitPrice: line[2],
      totalAmount: line[1] * line[2],
      selectedSupplierScore: product.aiScore,
      remark: line[3] || '',
    });
  });
  const historicalTimes: Record<string, { createTime: string; updateTime: string; submittedAt: string; approvedAt: string | null; remark: string }> = {
    PO202606001: { createTime: '2026-06-13 09:10:00', updateTime: '2026-06-14 09:12:00', submittedAt: '2026-06-13 09:10:00', approvedAt: '2026-06-13 10:00:00', remark: '对应 IB202606140001，已确认入库' },
    PO202606002: { createTime: '2026-06-12 14:20:00', updateTime: '2026-06-13 15:28:00', submittedAt: '2026-06-12 14:20:00', approvedAt: '2026-06-12 15:00:00', remark: '对应 IB202606130006，待确认入库' },
    PO202606003: { createTime: '2026-06-11 16:10:00', updateTime: '2026-06-12 17:36:00', submittedAt: '2026-06-11 16:10:00', approvedAt: '2026-06-11 16:40:00', remark: '对应 IB202606120009，待确认入库' },
    PO202606004: { createTime: '2026-06-10 11:00:00', updateTime: '2026-06-11 09:00:00', submittedAt: '2026-06-10 11:00:00', approvedAt: null, remark: '历史取消采购单，未生成入库工作单' },
  };
  const auditTime = historicalTimes[purchaseNo];
  const timestamp = auditTime?.createTime || '2026-07-12 10:30:00';
  return {
    purchaseOrderId,
    purchaseNo,
    supplierId: supplier.supplierId,
    supplierCode: supplier.supplierCode,
    supplierName: supplier.supplierName,
    warehouseId: warehouse.warehouseId,
    warehouseName: warehouse.warehouseName,
    status,
    totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
    expectedArrivalDate,
    createdById: createdByName === '系统管理员' ? '1900000000000000001' : '1900000000000000002',
    createdByName,
    submittedAt: submitted ? (auditTime?.submittedAt || timestamp) : null,
    approvedById: status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE' ? '1900000000000000002' : null,
    approvedByName: status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE' ? '采购主管' : '',
    approvedAt: status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE' ? (auditTime?.approvedAt || '2026-07-13 09:20:00') : null,
    createTime: timestamp,
    updateTime: auditTime?.updateTime || timestamp,
    version: 0,
    remark: auditTime?.remark || '',
    items,
  };
}

function generateCode(prefix: string, sequence: number, width = 3) {
  return `${prefix}${String(sequence).padStart(width, '0')}`;
}

function normalizeSupplier(item: SupplierListItem): SupplierListItem {
  return {
    ...item,
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    supplierCode: String(item.supplierCode),
    supplierName: String(item.supplierName),
    contactName: String(item.contactName),
    contactPhone: String(item.contactPhone),
    address: String(item.address),
    paymentTerms: String(item.paymentTerms),
    overallScore: normalizeFiniteNumber(item.overallScore, 'overallScore'),
    deliveryScore: normalizeFiniteNumber(item.deliveryScore, 'deliveryScore'),
    qualityScore: normalizeFiniteNumber(item.qualityScore, 'qualityScore'),
    priceScore: normalizeFiniteNumber(item.priceScore, 'priceScore'),
    serviceScore: normalizeFiniteNumber(item.serviceScore, 'serviceScore'),
    avgDeliveryDays: normalizeFiniteNumber(item.avgDeliveryDays, 'avgDeliveryDays'),
    onTimeRate: normalizeFiniteNumber(item.onTimeRate, 'onTimeRate'),
    qualifiedRate: normalizeFiniteNumber(item.qualifiedRate, 'qualifiedRate'),
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
    remark: String(item.remark),
  };
}

function normalizeSupplierProduct(item: SupplierProductListItem): SupplierProductListItem {
  return {
    ...item,
    supplierProductId: normalizeStringId(item.supplierProductId, 'supplierProductId'),
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    productId: normalizeStringId(item.productId, 'productId'),
    quantityPrecision: normalizeFiniteNumber(item.quantityPrecision, 'quantityPrecision'),
    latestPurchasePrice: normalizeFiniteNumber(item.latestPurchasePrice, 'latestPurchasePrice'),
    minOrderQty: normalizeFiniteNumber(item.minOrderQty, 'minOrderQty'),
    leadTimeDays: normalizeFiniteNumber(item.leadTimeDays, 'leadTimeDays'),
    deliveryScore: normalizeFiniteNumber(item.deliveryScore, 'deliveryScore'),
    qualityScore: normalizeFiniteNumber(item.qualityScore, 'qualityScore'),
    priceScore: normalizeFiniteNumber(item.priceScore, 'priceScore'),
    aiScore: normalizeFiniteNumber(item.aiScore, 'aiScore'),
    lastPurchaseAt: item.lastPurchaseAt || null,
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
  };
}

function normalizeOrderItem(item: PurchaseOrderItem): PurchaseOrderItem {
  return {
    ...item,
    purchaseOrderItemId: normalizeStringId(item.purchaseOrderItemId, 'purchaseOrderItemId'),
    purchaseOrderId: normalizeStringId(item.purchaseOrderId, 'purchaseOrderId'),
    supplierProductId: normalizeNullableStringId(item.supplierProductId, 'supplierProductId'),
    productId: normalizeStringId(item.productId, 'productId'),
    quantity: normalizeFiniteNumber(item.quantity, 'quantity'),
    inboundQty: normalizeFiniteNumber(item.inboundQty, 'inboundQty'),
    unitPrice: normalizeFiniteNumber(item.unitPrice, 'unitPrice'),
    totalAmount: normalizeFiniteNumber(item.totalAmount, 'totalAmount'),
    selectedSupplierScore: normalizeFiniteNumber(item.selectedSupplierScore, 'selectedSupplierScore'),
  };
}

function normalizeOrder(item: PurchaseOrderListItem): PurchaseOrderListItem {
  return {
    ...item,
    purchaseOrderId: normalizeStringId(item.purchaseOrderId, 'purchaseOrderId'),
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    totalAmount: normalizeFiniteNumber(item.totalAmount, 'totalAmount'),
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    approvedById: normalizeNullableStringId(item.approvedById, 'approvedById'),
    version: normalizeFiniteNumber(item.version, 'version'),
  };
}

function normalizeOrderDetail(item: PurchaseOrderDetail): PurchaseOrderDetail {
  return { ...normalizeOrder(item), items: item.items.map(normalizeOrderItem) };
}

function normalizePage<T>(page: PageResult<T>, mapper: (item: T) => T): PageResult<T> {
  return {
    records: page.records.map(mapper),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

function keywordField(keyword: string, codeField: string, nameField: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { [codeField]: value } : { [nameField]: value };
}

function pageSlice<T>(records: T[], pageNum: number, pageSize: number) {
  return records.slice((pageNum - 1) * pageSize, (pageNum - 1) * pageSize + pageSize);
}

function filterSuppliers(params: SupplierQuery): PageResult<SupplierListItem> {
  let filtered = [...mockSuppliers];
  const supplierCode = params.supplierCode?.trim().toLocaleLowerCase();
  const supplierName = params.supplierName?.trim().toLocaleLowerCase();
  const contactName = params.contactName?.trim().toLocaleLowerCase();
  if (supplierCode) filtered = filtered.filter(item => item.supplierCode.toLocaleLowerCase().includes(supplierCode));
  if (supplierName) filtered = filtered.filter(item => item.supplierName.toLocaleLowerCase().includes(supplierName));
  if (contactName) filtered = filtered.filter(item => item.contactName.toLocaleLowerCase().includes(contactName));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => b.overallScore - a.overallScore);
  return { records: pageSlice(filtered, params.pageNum, params.pageSize).map(({ referenced: _, ...item }) => item), total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

function filterSupplierProducts(params: SupplierProductQuery): PageResult<SupplierProductListItem> {
  let filtered = [...mockSupplierProducts];
  const productCode = params.productCode?.trim().toLocaleLowerCase();
  const productName = params.productName?.trim().toLocaleLowerCase();
  if (params.supplierId && params.supplierId !== 'all') filtered = filtered.filter(item => item.supplierId === params.supplierId);
  if (productCode) filtered = filtered.filter(item => item.productCode.toLocaleLowerCase().includes(productCode));
  if (productName) filtered = filtered.filter(item => item.productName.toLocaleLowerCase().includes(productName));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => b.aiScore - a.aiScore);
  return { records: pageSlice(filtered, params.pageNum, params.pageSize).map(({ referenced: _, ...item }) => item), total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

function resolveOrderSupplierProduct(supplierId: string, line: PurchaseOrderFormPayload['items'][number]) {
  const exact = line.supplierProductId
    ? mockSupplierProducts.find(item => item.supplierProductId === line.supplierProductId && item.supplierId === supplierId && item.productId === line.productId && item.status === 1)
    : null;
  return exact || mockSupplierProducts.find(item => item.supplierId === supplierId && item.productId === line.productId && item.status === 1);
}

function filterOrders(params: PurchaseOrderQuery): PurchaseOrderPage {
  let filtered = [...mockOrders];
  const purchaseNo = params.purchaseNo?.trim().toLocaleLowerCase();
  if (purchaseNo) filtered = filtered.filter(item => item.purchaseNo.toLocaleLowerCase().includes(purchaseNo));
  if (params.supplierId && params.supplierId !== 'all') filtered = filtered.filter(item => item.supplierId === params.supplierId);
  if (params.warehouseId && params.warehouseId !== 'all') filtered = filtered.filter(item => item.warehouseId === params.warehouseId);
  if (params.status && params.status !== 'all') filtered = filtered.filter(item => item.status === params.status);
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime));
  const records = pageSlice(filtered, params.pageNum, params.pageSize).map(({ items: _, ...item }) => item);
  return { records, total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

export function listSuppliers(params: SupplierQuery) {
  if (useMockApi) return Promise.resolve(normalizePage(filterSuppliers(params), normalizeSupplier));
  const { supplierCode, supplierName, contactName, status, ...rest } = params;
  return getResult<PageResult<SupplierListItem>>('/purchase/suppliers', {
    ...rest,
    ...(supplierCode?.trim() ? { supplierCode: supplierCode.trim() } : {}),
    ...(supplierName?.trim() ? { supplierName: supplierName.trim() } : {}),
    ...(contactName?.trim() ? { contactName: contactName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }).then(page => normalizePage(page, normalizeSupplier));
}

export async function searchSupplierOptions(keyword = '', pageSize = 10): Promise<SupplierOption[]> {
  const page = await listSuppliers({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'supplierCode', 'supplierName'),
  });
  return page.records.map(item => ({
    supplierId: item.supplierId,
    supplierCode: item.supplierCode,
    supplierName: item.supplierName,
    status: item.status,
  }));
}

export function createSupplier(payload: SupplierFormPayload) {
  if (useMockApi) {
    const timestamp = nowText();
    const created: SupplierListItem & { referenced: boolean } = {
      supplierId: String(Date.now()),
      supplierCode: generateCode('S', nextSupplierSequence++),
      ...payload,
      version: 0,
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
    };
    mockSuppliers = [...mockSuppliers, created];
    return Promise.resolve(normalizeSupplier(created));
  }
  return postResult<SupplierListItem, SupplierFormPayload>('/purchase/suppliers', payload).then(normalizeSupplier);
}

export async function updateSupplier(supplierId: string, payload: SupplierFormPayload) {
  if (useMockApi) {
    mockSuppliers = mockSuppliers.map(item => {
      if (item.supplierId !== supplierId) return item;
      assertOptimisticVersion(item.version, payload.version);
      return { ...item, ...payload, version: item.version + 1, updateTime: nowText() };
    });
    const supplier = mockSuppliers.find(item => item.supplierId === supplierId);
    return supplier ? normalizeSupplier(supplier) : null;
  }
  const response = await http.put(`/purchase/suppliers/${supplierId}`, payload);
  return normalizeSupplier(response.data.data as SupplierListItem);
}

export async function updateSupplierStatus(supplierId: string, status: 0 | 1, version: number) {
  if (useMockApi) {
    const timestamp = nowText();
    mockSuppliers = mockSuppliers.map(item => {
      if (item.supplierId !== supplierId) return item;
      assertOptimisticVersion(item.version, version);
      return { ...item, status, version: item.version + 1, updateTime: timestamp };
    });
    if (status === 0) mockSupplierProducts = mockSupplierProducts.map(item => item.supplierId === supplierId ? { ...item, status: 0, version: item.version + 1, updateTime: timestamp } : item);
    return null;
  }
  const response = await http.patch(`/purchase/suppliers/${supplierId}/status`, { status, version });
  return response.data.data as null;
}

export function deleteSupplier(supplierId: string, version: number) {
  if (useMockApi) {
    const target = mockSuppliers.find(item => item.supplierId === supplierId);
    if (target) assertOptimisticVersion(target.version, version);
    if (target?.referenced) return Promise.reject(new Error('供应商已被供货产品或采购订单引用，无法删除'));
    mockSuppliers = mockSuppliers.filter(item => item.supplierId !== supplierId);
    return Promise.resolve(null);
  }
  return http.delete(`/purchase/suppliers/${supplierId}`, { data: { version } }).then(response => response.data.data as null);
}

export function batchUpdateSupplierStatus(payload: SupplierBatchStatusPayload) {
  if (useMockApi) {
    const timestamp = nowText();
    payload.supplierIds.forEach(supplierId => {
      const item = mockSuppliers.find(candidate => candidate.supplierId === supplierId);
      if (item) assertOptimisticVersion(item.version, payload.versionBySupplierId[supplierId]);
    });
    mockSuppliers = mockSuppliers.map(item => payload.supplierIds.includes(item.supplierId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: timestamp } : item);
    return Promise.resolve(null);
  }
  return http.patch('/purchase/suppliers/batch/status', payload).then(response => response.data.data as null);
}

export function batchDeleteSuppliers(payload: SupplierBatchIdsPayload) {
  if (useMockApi) {
    payload.supplierIds.forEach(supplierId => {
      const item = mockSuppliers.find(candidate => candidate.supplierId === supplierId);
      if (item) assertOptimisticVersion(item.version, payload.versionBySupplierId[supplierId]);
    });
    if (mockSuppliers.some(item => payload.supplierIds.includes(item.supplierId) && item.referenced)) {
      return Promise.reject(new Error('所选供应商中存在已被业务引用的数据'));
    }
    mockSuppliers = mockSuppliers.filter(item => !payload.supplierIds.includes(item.supplierId));
    return Promise.resolve(null);
  }
  return postResult<null, SupplierBatchIdsPayload>('/purchase/suppliers/batch/delete', payload);
}

export function listSupplierProducts(params: SupplierProductQuery) {
  if (useMockApi) return Promise.resolve(normalizePage(filterSupplierProducts(params), normalizeSupplierProduct));
  const { supplierId, productCode, productName, status, ...rest } = params;
  return getResult<PageResult<SupplierProductListItem>>('/purchase/supplier-products', {
    ...rest,
    ...(supplierId && supplierId !== 'all' ? { supplierId } : {}),
    ...(productCode?.trim() ? { productCode: productCode.trim() } : {}),
    ...(productName?.trim() ? { productName: productName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
  }).then(page => normalizePage(page, normalizeSupplierProduct));
}

export function createSupplierProduct(payload: SupplierProductFormPayload) {
  if (useMockApi) {
    const supplier = mockSuppliers.find(item => item.supplierId === payload.supplierId);
    if (!supplier || supplier.status === 0) return Promise.reject(new Error('请选择启用状态的供应商'));
    const product = mockProductSnapshotById(payload.productId);
    if (mockSupplierProducts.some(item => item.supplierId === payload.supplierId && item.productId === payload.productId)) {
      return Promise.reject(new Error('该供应商已维护此产品的供货关系'));
    }
    const timestamp = nowText();
    const created: SupplierProductListItem & { referenced: boolean } = {
      supplierProductId: `2011000000000009${String(nextSupplierProductSequence++).padStart(2, '0')}`,
      supplierCode: supplier.supplierCode,
      supplierName: supplier.supplierName,
      productCode: product.productCode,
      productName: product.productName,
      unitName: product.unitName,
      quantityPrecision: mockProductSnapshotById(product.productId).quantityPrecision,
      lastPurchaseAt: null,
      createTime: timestamp,
      updateTime: timestamp,
      referenced: false,
      ...payload,
      version: 0,
    };
    mockSupplierProducts = [...mockSupplierProducts, created];
    return Promise.resolve(normalizeSupplierProduct(created));
  }
  return postResult<SupplierProductListItem, SupplierProductFormPayload>('/purchase/supplier-products', payload).then(normalizeSupplierProduct);
}

export async function updateSupplierProduct(supplierProductId: string, payload: SupplierProductFormPayload) {
  if (useMockApi) {
    const supplier = mockSuppliers.find(item => item.supplierId === payload.supplierId);
    const product = mockProductSnapshotById(payload.productId);
    const current = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    if (current) assertOptimisticVersion(current.version, payload.version);
    mockSupplierProducts = mockSupplierProducts.map(item => item.supplierProductId === supplierProductId ? {
      ...item,
      ...payload,
      supplierCode: supplier?.supplierCode || item.supplierCode,
      supplierName: supplier?.supplierName || item.supplierName,
      productCode: product.productCode,
      productName: product.productName,
      unitName: product.unitName,
      version: item.version + 1,
      updateTime: nowText(),
    } : item);
    const result = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    return result ? normalizeSupplierProduct(result) : null;
  }
  const response = await http.put(`/purchase/supplier-products/${supplierProductId}`, payload);
  return normalizeSupplierProduct(response.data.data as SupplierProductListItem);
}

export function deleteSupplierProduct(supplierProductId: string, version: number) {
  if (useMockApi) {
    const target = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    if (target) assertOptimisticVersion(target.version, version);
    if (target?.referenced) return Promise.reject(new Error('供货产品已被采购订单引用，无法删除'));
    mockSupplierProducts = mockSupplierProducts.filter(item => item.supplierProductId !== supplierProductId);
    return Promise.resolve(null);
  }
  return http.delete(`/purchase/supplier-products/${supplierProductId}`, { data: { version } }).then(response => response.data.data as null);
}

export function batchUpdateSupplierProductStatus(payload: SupplierProductBatchStatusPayload) {
  if (useMockApi) {
    const timestamp = nowText();
    payload.supplierProductIds.forEach(supplierProductId => {
      const item = mockSupplierProducts.find(candidate => candidate.supplierProductId === supplierProductId);
      if (item) assertOptimisticVersion(item.version, payload.versionBySupplierProductId[supplierProductId]);
    });
    mockSupplierProducts = mockSupplierProducts.map(item => payload.supplierProductIds.includes(item.supplierProductId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: timestamp } : item);
    return Promise.resolve(null);
  }
  return http.patch('/purchase/supplier-products/batch/status', payload).then(response => response.data.data as null);
}

export function batchDeleteSupplierProducts(payload: SupplierProductBatchIdsPayload) {
  if (useMockApi) {
    payload.supplierProductIds.forEach(supplierProductId => {
      const item = mockSupplierProducts.find(candidate => candidate.supplierProductId === supplierProductId);
      if (item) assertOptimisticVersion(item.version, payload.versionBySupplierProductId[supplierProductId]);
    });
    if (mockSupplierProducts.some(item => payload.supplierProductIds.includes(item.supplierProductId) && item.referenced)) {
      return Promise.reject(new Error('所选供货产品中存在已被采购订单引用的数据'));
    }
    mockSupplierProducts = mockSupplierProducts.filter(item => !payload.supplierProductIds.includes(item.supplierProductId));
    return Promise.resolve(null);
  }
  return postResult<null, SupplierProductBatchIdsPayload>('/purchase/supplier-products/batch/delete', payload);
}

export function listPurchaseOrders(params: PurchaseOrderQuery) {
  if (useMockApi) {
    const page = filterOrders(params);
    return Promise.resolve(normalizePage(page, normalizeOrder));
  }
  const { purchaseNo, supplierId, warehouseId, status, ...rest } = params;
  return getResult<PurchaseOrderPage>('/purchase/orders', {
    ...rest,
    ...(purchaseNo?.trim() ? { purchaseNo: purchaseNo.trim() } : {}),
    ...(supplierId && supplierId !== 'all' ? { supplierId } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(status && status !== 'all' ? { status } : {}),
  }).then(page => normalizePage(page, normalizeOrder));
}

export function getPurchaseOrderDetail(purchaseOrderId: string) {
  if (useMockApi) {
    const order = mockOrders.find(item => item.purchaseOrderId === purchaseOrderId);
    return order ? Promise.resolve(normalizeOrderDetail(order)) : Promise.reject(new Error('采购订单不存在'));
  }
  return getResult<PurchaseOrderDetail>(`/purchase/orders/${purchaseOrderId}`).then(normalizeOrderDetail);
}

export function createPurchaseOrder(payload: PurchaseOrderFormPayload) {
  if (useMockApi) {
    const supplier = mockSuppliers.find(item => item.supplierId === payload.supplierId);
    if (!supplier || supplier.status === 0) return Promise.reject(new Error('请选择启用状态的供应商'));
    const warehouse = mockWarehouseSnapshot(payload.warehouseId);
    const purchaseOrderId = String(Date.now());
    const purchaseNo = `PO202607${String(nextPurchaseOrderSequence++).padStart(3, '0')}`;
    const timestamp = nowText();
    const items = payload.items.map((line, index) => {
      const supplierProduct = resolveOrderSupplierProduct(payload.supplierId, line);
      if (!supplierProduct) throw new Error('当前供应商未维护所选产品的启用供货关系');
      const product = supplierProduct;
      return normalizeOrderItem({
        purchaseOrderItemId: `${purchaseOrderId}${index + 1}`,
        purchaseOrderId,
        purchaseNo,
        supplierProductId: supplierProduct?.supplierProductId || null,
        productId: product.productId,
        productCode: product.productCode,
        productName: product.productName,
        unitName: product.unitName,
        quantityPrecision: mockProductSnapshotById(product.productId).quantityPrecision,
        quantity: line.quantity,
        inboundQty: 0,
        unitPrice: line.unitPrice,
        totalAmount: line.quantity * line.unitPrice,
        selectedSupplierScore: line.selectedSupplierScore,
        remark: line.remark.trim(),
      });
    });
    const created: PurchaseOrderDetail = normalizeOrderDetail({
      purchaseOrderId,
      purchaseNo,
      supplierId: supplier.supplierId,
      supplierCode: supplier.supplierCode,
      supplierName: supplier.supplierName,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      status: 'DRAFT',
      totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
      expectedArrivalDate: payload.expectedArrivalDate || null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      submittedAt: null,
      approvedById: null,
      approvedByName: '',
      approvedAt: null,
      createTime: timestamp,
      updateTime: timestamp,
      version: 0,
      remark: payload.remark.trim(),
      items,
    });
    mockOrders = [created, ...mockOrders];
    return Promise.resolve(created);
  }
  return postResult<PurchaseOrderDetail, PurchaseOrderFormPayload>('/purchase/orders', payload).then(normalizeOrderDetail);
}

export async function updatePurchaseOrder(purchaseOrderId: string, payload: PurchaseOrderFormPayload) {
  if (useMockApi) {
    const existing = mockOrders.find(item => item.purchaseOrderId === purchaseOrderId);
    if (!existing) return Promise.reject(new Error('采购订单不存在'));
    assertOptimisticVersion(existing.version, payload.version);
    if (existing.status !== 'DRAFT' && existing.status !== 'SUBMITTED') return Promise.reject(new Error('仅草稿或已提交采购单可以编辑'));
    const supplier = mockSuppliers.find(item => item.supplierId === payload.supplierId);
    if (!supplier || supplier.status === 0) return Promise.reject(new Error('请选择启用状态的供应商'));
    const warehouse = mockWarehouseSnapshot(payload.warehouseId);
    const timestamp = nowText();
    const items = payload.items.map((line, index) => {
      const supplierProduct = resolveOrderSupplierProduct(payload.supplierId, line);
      if (!supplierProduct) throw new Error('当前供应商未维护所选产品的启用供货关系');
      const product = supplierProduct;
      return normalizeOrderItem({
        purchaseOrderItemId: line.purchaseOrderItemId || `${purchaseOrderId}${index + 1}`,
        purchaseOrderId,
        purchaseNo: existing.purchaseNo,
        supplierProductId: supplierProduct?.supplierProductId || null,
        productId: product.productId,
        productCode: product.productCode,
        productName: product.productName,
        unitName: product.unitName,
        quantityPrecision: mockProductSnapshotById(product.productId).quantityPrecision,
        quantity: line.quantity,
        inboundQty: 0,
        unitPrice: line.unitPrice,
        totalAmount: line.quantity * line.unitPrice,
        selectedSupplierScore: line.selectedSupplierScore,
        remark: line.remark.trim(),
      });
    });
    const updated = normalizeOrderDetail({
      ...existing,
      supplierId: supplier.supplierId,
      supplierCode: supplier.supplierCode,
      supplierName: supplier.supplierName,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      totalAmount: items.reduce((sum, item) => sum + item.totalAmount, 0),
      expectedArrivalDate: payload.expectedArrivalDate || null,
      version: existing.version + 1,
      updateTime: timestamp,
      remark: payload.remark.trim(),
      items,
    });
    mockOrders = mockOrders.map(item => (item.purchaseOrderId === purchaseOrderId ? updated : item));
    return Promise.resolve(updated);
  }
  const response = await http.put(`/purchase/orders/${purchaseOrderId}`, payload);
  return normalizeOrderDetail(response.data.data as PurchaseOrderDetail);
}

export function updatePurchaseOrderStatus(purchaseOrderId: string, action: 'submit' | 'approve' | 'cancel', version: number) {
  if (useMockApi) {
    const timestamp = nowText();
    mockOrders = mockOrders.map(item => {
      if (item.purchaseOrderId !== purchaseOrderId) return item;
      assertOptimisticVersion(item.version, version);
      if (action === 'submit' && item.status !== 'DRAFT') throw new Error('仅草稿采购单可以提交');
      if (action === 'approve' && item.status !== 'SUBMITTED') throw new Error('仅已提交采购单可以审核');
      if (action === 'cancel' && item.status !== 'DRAFT' && item.status !== 'SUBMITTED') throw new Error('仅草稿或已提交采购单可以取消');
      if ((action === 'submit' || action === 'approve') && !item.expectedArrivalDate) throw new Error('提交或审核采购订单前必须维护预计到货日期');
      return {
        ...item,
        status: action === 'submit' ? 'SUBMITTED' : action === 'approve' ? 'APPROVED' : 'CANCELLED',
        submittedAt: action === 'submit' ? timestamp : item.submittedAt,
        approvedById: action === 'approve' ? '1900000000000000001' : item.approvedById,
        approvedByName: action === 'approve' ? '采购主管' : item.approvedByName,
        approvedAt: action === 'approve' ? timestamp : item.approvedAt,
        version: item.version + 1,
        updateTime: timestamp,
      };
    });
    return Promise.resolve(null);
  }
  return postResult<null, { version: number }>(`/purchase/orders/${purchaseOrderId}/${action}`, { version });
}

function mockProductSnapshotById(productId: string) {
  const product = getMockProductSnapshot(productId);
  if (!product) throw new Error(`产品 ${productId} 不存在`);
  return product;
}

function mockWarehouseSnapshot(warehouseId: string): Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'> {
  const warehouses: Record<string, Pick<WarehouseListItem, 'warehouseId' | 'warehouseName'>> = {
    '1930000000000000001': { warehouseId: '1930000000000000001', warehouseName: '华东中心仓' },
    '1930000000000000002': { warehouseId: '1930000000000000002', warehouseName: '华南中心仓' },
    '1930000000000000003': { warehouseId: '1930000000000000003', warehouseName: '华北中心仓' },
    '1930000000000000005': { warehouseId: '1930000000000000005', warehouseName: '武汉中转仓' },
    '1930000000000000008': { warehouseId: '1930000000000000008', warehouseName: '南京备货仓' },
  };
  return warehouses[warehouseId] || { warehouseId, warehouseName: '目标仓库' };
}

export async function listEnabledProductOptions(keyword = '', pageSize = 10) {
  const page = await listProducts({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'productCode', 'productName'),
  });
  return page.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}`, product: item }));
}

export async function listEnabledWarehouseOptions(keyword = '', pageSize = 10) {
  const page = await listWarehouses({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'warehouseCode', 'warehouseName'),
  });
  return page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}`, warehouse: item }));
}
