import type { AxiosRequestConfig } from 'axios';
import { getResult, http, postResult } from '@/api/http';
import type { PageResult, Result } from '@/shared/types/api';
import { normalizeBinaryStatus, normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { normalizeMoneyNumber, serializeMoney } from '@/shared/utils/money';
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
  PurchaseOrderReturnOverview,
  PurchaseOrderStatus,
  ScoreChangeLog,
  ScoreChangeLogQuery,
  SupplierBatchFailure,
  SupplierBatchIdsPayload,
  SupplierBatchStatusPayload,
  SupplierCreatePayload,
  SupplierUpdatePayload,
  SupplierServiceScorePayload,
  SupplierListItem,
  SupplierOption,
  SupplierProductBatchIdsPayload,
  SupplierProductBatchStatusPayload,
  SupplierProductCreatePayload,
  SupplierProductUpdatePayload,
  SupplierProductQuotePayload,
  SupplierProductListItem,
  SupplierProductQuery,
  SupplierQuery,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';
const remoteOptionRequestConfig = { skipPageLoading: true } as const;

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
  serviceScoreReason: item[10] == null ? '' : '历史模拟服务评分',
  avgDeliveryDays: item[11],
  scoreBasisAmount: null,
  scoreStatus: 'NOT_READY' as const,
  status: item[14],
  version: 0,
  remark: index < 3 ? '常用供应商，可用于采购建议候选' : '',
  createTime: `2026-06-${String(2 + index).padStart(2, '0')} 09:10:00`,
  updateTime: `2026-06-${String(12 + (index % 4)).padStart(2, '0')} 15:30:00`,
  updatedById: '1900000000000000002',
  updatedByName: '采购主管',
  referenced: item[15],
}));

type SupplierProductSeed = [string, string, number | null, number, number, number, number, string | null, 0 | 1, boolean];

const supplierProductSeed: SupplierProductSeed[] = [
  ['S001', 'P000001', 35.2, 10, 96.1, 88.4, 92.3, '2026-06-13 10:20:00', 1, true],
  ['S002', 'P000002', 40.5, 8, 93.4, 84.2, 88.9, '2026-06-10 11:20:00', 1, true],
  ['S003', 'P000007', 68, 6, 95.2, 87.6, 91.8, '2026-06-11 14:10:00', 1, true],
  ['S003', 'P000008', 58, 5, 95.2, 87.6, 91.4, '2026-06-09 09:40:00', 1, true],
  ['S004', 'P000021', null, 20, 90.5, 88.1, 87.2, null, 1, false],
  ['S005', 'P000026', 92, 12, 97.2, 91.6, 94.8, '2026-06-12 13:50:00', 1, true],
  ['S006', 'P000043', 126, 2, 82.4, 84.6, 81.3, null, 0, false],
  ['S001', 'P000002', 40.5, 8, 96.1, 88.4, 91.4, '2026-06-14 09:12:00', 0, true],
  ['S003', 'P000033', 28, 6, 95.2, 87.6, 90.1, '2026-06-13 15:28:00', 0, true],
  ['S004', 'P000038', 7.8, 20, 90.5, 88.1, 86.8, '2026-06-12 17:36:00', 0, true],
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
    quotedPurchasePrice: null,
    quotedPriceReason: '',
    quotedPriceUpdatedAt: null,
    quoteValidUntil: null,
    latestPurchasePrice: item[2],
    minOrderQty: item[3],
    avgDeliveryDays: null,
    qualityScore: item[4],
    priceScore: item[5],
    aiScore: item[6],
    lastPurchaseAt: item[7],
    scoreBasisAmount: null,
    scoreStatus: 'NOT_READY' as const,
    status: item[8],
    version: 0,
    remark: index < 3 ? '采购建议优先候选' : '',
    createTime: `2026-06-${String(3 + index).padStart(2, '0')} 10:00:00`,
    updateTime: `2026-06-${String(12 + (index % 4)).padStart(2, '0')} 16:10:00`,
    updatedById: '1900000000000000002',
    updatedByName: '采购主管',
    referenced: item[9],
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
let nextScoreChangeLogSequence = 5;
const mockScoreChangeLogs: ScoreChangeLog[] = [{
  scoreChangeLogId: '2017000000000000001',
  supplierId: mockSuppliers[0].supplierId,
  supplierProductId: null,
  metricType: 'SERVICE',
  metricScoreBefore: 90,
  metricScoreAfter: mockSuppliers[0].serviceScore,
  productRecommendScoreBefore: null,
  productRecommendScoreAfter: null,
  supplierOverallScoreBefore: null,
  supplierOverallScoreAfter: null,
  triggerType: 'SERVICE_TRIGGER',
  batchNo: 'MOCK-SERVICE-0001',
  ruleVersion: 'MANUAL',
  relatedSources: [{ businessType: 'SUPPLIER', businessId: mockSuppliers[0].supplierId, businessNo: mockSuppliers[0].supplierCode }],
  operatorType: 'USER',
  operatorId: '1900000000000000001',
  operatorName: '系统管理员',
  reason: '历史模拟服务分调整',
  createTime: '2026-09-20 09:30:00',
}, {
  scoreChangeLogId: '2017000000000000002',
  supplierId: mockSuppliers[1].supplierId,
  supplierProductId: null,
  metricType: 'SERVICE',
  metricScoreBefore: 85.5,
  metricScoreAfter: mockSuppliers[1].serviceScore,
  productRecommendScoreBefore: null,
  productRecommendScoreAfter: null,
  supplierOverallScoreBefore: null,
  supplierOverallScoreAfter: null,
  triggerType: 'SERVICE_TRIGGER',
  batchNo: 'MOCK-SERVICE-0002',
  ruleVersion: 'MANUAL',
  relatedSources: [{ businessType: 'SUPPLIER', businessId: mockSuppliers[1].supplierId, businessNo: mockSuppliers[1].supplierCode }],
  operatorType: 'USER',
  operatorId: '1900000000000000001',
  operatorName: '系统管理员',
  reason: '历史模拟服务分调整',
  createTime: '2026-09-19 10:10:00',
}, {
  scoreChangeLogId: '2017000000000000003',
  supplierId: mockSuppliers[1].supplierId,
  supplierProductId: null,
  metricType: 'QUALITY',
  metricScoreBefore: 90,
  metricScoreAfter: 90,
  productRecommendScoreBefore: null,
  productRecommendScoreAfter: null,
  supplierOverallScoreBefore: 88,
  supplierOverallScoreAfter: 89,
  triggerType: 'INBOUND_TRIGGER',
  batchNo: 'MOCK-MERGED-0003',
  ruleVersion: 'MOCK-RULE',
  relatedSources: Array.from({ length: 12 }, (_, index) => ({
    businessType: 'PURCHASE_ORDER' as const,
    businessId: (9007199254740993n + BigInt(index)).toString(),
    businessNo: index === 11 ? null : `PO-MOCK-${String(index + 1).padStart(2, '0')}`,
  })),
  operatorType: 'SYSTEM',
  operatorId: null,
  operatorName: '完全入库重算',
  reason: '完全入库合并重算，共 12 张采购单；衍生分校正',
  createTime: '2026-09-18 10:10:00',
}, {
  scoreChangeLogId: '2017000000000000004',
  supplierId: mockSuppliers[1].supplierId,
  supplierProductId: null,
  metricType: 'DELIVERY',
  metricScoreBefore: 88,
  metricScoreAfter: 89,
  productRecommendScoreBefore: null,
  productRecommendScoreAfter: null,
  supplierOverallScoreBefore: 89,
  supplierOverallScoreAfter: 89.2,
  triggerType: 'DAILY_TRIGGER',
  batchNo: 'MOCK-DAILY-0004',
  ruleVersion: 'MOCK-RULE',
  relatedSources: [],
  operatorType: 'SYSTEM',
  operatorId: null,
  operatorName: '每日事实校正',
  reason: '每日事实校正，业务日期 2026-09-17',
  createTime: '2026-09-17 00:10:00',
}];

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
    const supplierProduct = mockSupplierProducts.find(item => item.productCode === line[0]);
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
    }, true);
  });
  const historicalTimes: Record<string, { createTime: string; updateTime: string; submittedAt: string; approvedAt: string | null; remark: string }> = {
    PO202606001: { createTime: '2026-06-13 09:10:00', updateTime: '2026-06-14 09:12:00', submittedAt: '2026-06-13 09:10:00', approvedAt: '2026-06-13 10:00:00', remark: '对应 IB202606140001，已确认入库' },
    PO202606002: { createTime: '2026-06-12 14:20:00', updateTime: '2026-06-13 15:28:00', submittedAt: '2026-06-12 14:20:00', approvedAt: '2026-06-12 15:00:00', remark: '对应 IB202606130006，待确认入库' },
    PO202606003: { createTime: '2026-06-11 16:10:00', updateTime: '2026-06-12 17:36:00', submittedAt: '2026-06-11 16:10:00', approvedAt: '2026-06-11 16:40:00', remark: '对应 IB202606120009，待确认入库' },
    PO202606004: { createTime: '2026-06-10 11:00:00', updateTime: '2026-06-11 09:00:00', submittedAt: '2026-06-10 11:00:00', approvedAt: null, remark: '历史取消采购单，未生成入库工作单' },
  };
  const auditTime = historicalTimes[purchaseNo];
  const timestamp = auditTime?.createTime || '2026-07-12 10:30:00';
  const submittedAt = submitted ? (auditTime?.submittedAt || timestamp) : null;
  const approvedAt = status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE'
    ? (auditTime?.approvedAt || '2026-07-13 09:20:00')
    : null;
  const totalAmount = items.reduce((sum, item) => sum + item.totalAmount, 0);
  const inboundBillId = `3010000000000000${purchaseNo.slice(-3)}`;
  const inboundBillNo = `IB${purchaseNo.slice(2)}`;
  const timeline: PurchaseOrderDetail['timeline'] = [
    { event: 'CREATED', occurredAt: timestamp, operatorName: createdByName, inboundBillId: null, inboundBillNo: null },
  ];
  if (submittedAt) timeline.push({ event: 'SUBMITTED', occurredAt: submittedAt, operatorName: createdByName, inboundBillId: null, inboundBillNo: null });
  if (approvedAt) {
    timeline.push({ event: 'APPROVED', occurredAt: approvedAt, operatorName: '采购主管', inboundBillId: null, inboundBillNo: null });
    timeline.push({ event: 'INBOUND_CREATED', occurredAt: approvedAt, operatorName: '系统', inboundBillId, inboundBillNo });
  }
  if (status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE') {
    timeline.push({ event: 'INBOUND_CONFIRMED', occurredAt: auditTime?.updateTime || approvedAt!, operatorName: '仓库管理员', inboundBillId, inboundBillNo });
  }
  return {
    purchaseOrderId,
    purchaseNo,
    supplierId: supplier.supplierId,
    supplierCode: supplier.supplierCode,
    supplierName: supplier.supplierName,
    warehouseId: warehouse.warehouseId,
    warehouseName: warehouse.warehouseName,
    status,
    totalAmount,
    expectedArrivalDate,
    createdById: createdByName === '系统管理员' ? '1900000000000000001' : '1900000000000000002',
    createdByName,
    submittedAt,
    submittedById: submitted ? (createdByName === '系统管理员' ? '1900000000000000001' : '1900000000000000002') : null,
    submittedByName: submitted ? createdByName : '',
    approvedById: status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE' ? '1900000000000000002' : null,
    approvedByName: status === 'APPROVED' || status === 'PARTIAL_INBOUND' || status === 'INBOUND_DONE' ? '采购主管' : '',
    approvedAt,
    createTime: timestamp,
    updateTime: auditTime?.updateTime || timestamp,
    version: 0,
    remark: auditTime?.remark || '',
    items,
    fulfillmentSummary: buildFulfillmentSummary(items),
    timeline,
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
    contactName: String(item.contactName ?? ''),
    contactPhone: String(item.contactPhone ?? ''),
    address: String(item.address ?? ''),
    paymentTerms: String(item.paymentTerms ?? ''),
    overallScore: normalizeNullableFiniteNumber(item.overallScore, 'overallScore'),
    deliveryScore: normalizeNullableFiniteNumber(item.deliveryScore, 'deliveryScore'),
    qualityScore: normalizeNullableFiniteNumber(item.qualityScore, 'qualityScore'),
    priceScore: normalizeNullableFiniteNumber(item.priceScore, 'priceScore'),
    serviceScore: normalizeNullableFiniteNumber(item.serviceScore, 'serviceScore'),
    serviceScoreReason: item.serviceScoreReason || '',
    avgDeliveryDays: normalizeNullableFiniteNumber(item.avgDeliveryDays, 'avgDeliveryDays'),
    scoreBasisAmount: normalizeMoneyNumber(item.scoreBasisAmount, 'scoreBasisAmount', true, useMockApi),
    scoreStatus: item.scoreStatus === 'READY' ? 'READY' : 'NOT_READY',
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
    updatedById: normalizeNullableStringId(item.updatedById, 'updatedById'),
    updatedByName: item.updatedByName || null,
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
    quotedPurchasePrice: normalizeMoneyNumber(item.quotedPurchasePrice, 'quotedPurchasePrice', true, useMockApi),
    quotedPriceReason: item.quotedPriceReason || '',
    quotedPriceUpdatedAt: item.quotedPriceUpdatedAt || null,
    quoteValidUntil: item.quoteValidUntil || null,
    latestPurchasePrice: normalizeMoneyNumber(item.latestPurchasePrice, 'latestPurchasePrice', true, useMockApi),
    minOrderQty: normalizeFiniteNumber(item.minOrderQty, 'minOrderQty'),
    avgDeliveryDays: normalizeNullableFiniteNumber(item.avgDeliveryDays, 'avgDeliveryDays'),
    qualityScore: normalizeNullableFiniteNumber(item.qualityScore, 'qualityScore'),
    priceScore: normalizeNullableFiniteNumber(item.priceScore, 'priceScore'),
    aiScore: normalizeNullableFiniteNumber(item.aiScore, 'aiScore'),
    lastPurchaseAt: item.lastPurchaseAt || null,
    scoreBasisAmount: normalizeMoneyNumber(item.scoreBasisAmount, 'scoreBasisAmount', true, useMockApi),
    scoreStatus: item.scoreStatus === 'READY' ? 'READY' : 'NOT_READY',
    status: normalizeBinaryStatus(item.status),
    version: normalizeFiniteNumber(item.version, 'version'),
    updatedById: normalizeNullableStringId(item.updatedById, 'updatedById'),
    updatedByName: item.updatedByName || null,
  };
}

function normalizeNullableFiniteNumber(value: unknown, fieldName: string): number | null {
  if (value === null || value === undefined || value === '') return null;
  return normalizeFiniteNumber(value, fieldName);
}

function normalizeScoreChangeLog(item: ScoreChangeLog): ScoreChangeLog {
  const metricTypes = ['SERVICE', 'DELIVERY', 'QUALITY', 'PRICE'];
  const triggerTypes = ['PRICE_TRIGGER', 'SERVICE_TRIGGER', 'INBOUND_TRIGGER', 'QUOTE_EXPIRED_TRIGGER', 'DAILY_TRIGGER', 'MERGED'];
  if (!metricTypes.includes(item.metricType)) throw new Error(`接口字段 metricType 非法：${item.metricType}`);
  if (!triggerTypes.includes(item.triggerType)) throw new Error(`接口字段 triggerType 非法：${item.triggerType}`);
  if (item.operatorType !== 'USER' && item.operatorType !== 'SYSTEM') throw new Error(`接口字段 operatorType 非法：${item.operatorType}`);
  if (!Array.isArray(item.relatedSources)) throw new Error('接口字段 relatedSources 必须为来源数组');
  const sourceTypes = ['PURCHASE_ORDER', 'SUPPLIER_PRODUCT', 'PRODUCT', 'SUPPLIER'];
  const relatedSources = item.relatedSources.map((source, index) => {
    const field = `relatedSources[${index}]`;
    if (!source || typeof source !== 'object' || Array.isArray(source)) throw new Error(`接口字段 ${field} 必须为来源对象`);
    if (!sourceTypes.includes(source.businessType)) throw new Error(`接口字段 ${field}.businessType 非法`);
    if (typeof source.businessId !== 'string' || !/^[1-9]\d*$/.test(source.businessId)) throw new Error(`接口字段 ${field}.businessId 必须为正整数字符串`);
    if (source.businessNo !== null && (typeof source.businessNo !== 'string' || source.businessNo.length > 64)) throw new Error(`接口字段 ${field}.businessNo 必须为编号字符串或 null`);
    return { ...source };
  });
  if (typeof item.operatorName !== 'string' || !item.operatorName.trim() || item.operatorName.length > 100) throw new Error('接口字段 operatorName 必须为操作人或系统场景名称');
  if (item.operatorType === 'USER' && (typeof item.operatorId !== 'string' || !/^[1-9]\d*$/.test(item.operatorId))) throw new Error('人工评分日志必须包含字符串 operatorId');
  if (item.operatorType === 'SYSTEM' && item.operatorId !== null) throw new Error('系统评分日志 operatorId 必须为 null');
  return {
    ...item,
    scoreChangeLogId: normalizeStringId(item.scoreChangeLogId, 'scoreChangeLogId'),
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    supplierProductId: normalizeNullableStringId(item.supplierProductId, 'supplierProductId'),
    metricScoreBefore: normalizeNullableFiniteNumber(item.metricScoreBefore, 'metricScoreBefore'),
    metricScoreAfter: normalizeNullableFiniteNumber(item.metricScoreAfter, 'metricScoreAfter'),
    productRecommendScoreBefore: normalizeNullableFiniteNumber(item.productRecommendScoreBefore, 'productRecommendScoreBefore'),
    productRecommendScoreAfter: normalizeNullableFiniteNumber(item.productRecommendScoreAfter, 'productRecommendScoreAfter'),
    supplierOverallScoreBefore: normalizeNullableFiniteNumber(item.supplierOverallScoreBefore, 'supplierOverallScoreBefore'),
    supplierOverallScoreAfter: normalizeNullableFiniteNumber(item.supplierOverallScoreAfter, 'supplierOverallScoreAfter'),
    relatedSources,
    operatorId: normalizeNullableStringId(item.operatorId, 'operatorId'),
    batchNo: item.batchNo || '',
    ruleVersion: item.ruleVersion || '',
    operatorName: item.operatorName,
    reason: item.reason || '',
  };
}

function normalizeOrderItem(item: PurchaseOrderItem, allowMockMoney = useMockApi): PurchaseOrderItem {
  return {
    ...item,
    purchaseOrderItemId: normalizeStringId(item.purchaseOrderItemId, 'purchaseOrderItemId'),
    purchaseOrderId: normalizeStringId(item.purchaseOrderId, 'purchaseOrderId'),
    supplierProductId: normalizeNullableStringId(item.supplierProductId, 'supplierProductId'),
    productId: normalizeStringId(item.productId, 'productId'),
    quantityPrecision: normalizeQuantityPrecision(item.quantityPrecision),
    quantity: normalizeFiniteNumber(item.quantity, 'quantity'),
    inboundQty: normalizeFiniteNumber(item.inboundQty, 'inboundQty'),
    unitPrice: normalizeMoneyNumber(item.unitPrice, 'unitPrice', false, allowMockMoney)!,
    totalAmount: normalizeMoneyNumber(item.totalAmount, 'totalAmount', false, allowMockMoney)!,
    selectedSupplierScore: normalizeNullableFiniteNumber(item.selectedSupplierScore, 'selectedSupplierScore'),
  };
}

function normalizeQuantityPrecision(value: unknown): number {
  const precision = normalizeFiniteNumber(value, 'quantityPrecision');
  if (!Number.isInteger(precision) || precision < 0 || precision > 2) {
    throw new Error('quantityPrecision 必须为 0～2 的整数');
  }
  return precision;
}

/** Mock 与服务端保持同一边界：请求必须带精度，但最终快照只采信产品档案。 */
function resolveOrderQuantityPrecision(line: PurchaseOrderFormPayload['items'][number], productId: string) {
  normalizeQuantityPrecision(line.quantityPrecision);
  return normalizeQuantityPrecision(mockProductSnapshotById(productId).quantityPrecision);
}

function buildFulfillmentSummary(items: PurchaseOrderItem[]) {
  const totalAmount = items.reduce((sum, item) => sum + item.totalAmount, 0);
  const inboundAmount = items.reduce((sum, item) => sum + Math.min(item.quantity, item.inboundQty) * item.unitPrice, 0);
  return {
    calculationMode: 'AMOUNT_WEIGHTED' as const,
    totalAmount,
    inboundAmount: Number(inboundAmount.toFixed(2)),
    completionRate: totalAmount === 0 ? 0 : Math.round((inboundAmount / totalAmount) * 100),
  };
}

function normalizeReturnOverview(overview: PurchaseOrderReturnOverview): PurchaseOrderReturnOverview {
  if (!['NONE', 'PARTIAL', 'FULL'].includes(overview.coverage)) {
    throw new Error('returnOverview.coverage 必须是 NONE、PARTIAL 或 FULL');
  }
  return {
    ...overview,
    approvedReturnAmount: normalizeMoneyNumber(overview.approvedReturnAmount, 'returnOverview.approvedReturnAmount', false, useMockApi)!,
    returnOrderCount: normalizeFiniteNumber(overview.returnOrderCount, 'returnOverview.returnOrderCount'),
    effectiveReturnOrderCount: normalizeFiniteNumber(overview.effectiveReturnOrderCount, 'returnOverview.effectiveReturnOrderCount'),
    items: overview.items?.map((item) => ({
      ...item,
      purchaseOrderItemId: normalizeStringId(item.purchaseOrderItemId, 'returnOverview.items.purchaseOrderItemId'),
      orderedQty: normalizeFiniteNumber(item.orderedQty, 'returnOverview.items.orderedQty'),
      fulfilledQty: normalizeFiniteNumber(item.fulfilledQty, 'returnOverview.items.fulfilledQty'),
      approvedReturnQty: normalizeFiniteNumber(item.approvedReturnQty, 'returnOverview.items.approvedReturnQty'),
      approvedReturnAmount: normalizeMoneyNumber(item.approvedReturnAmount, 'returnOverview.items.approvedReturnAmount', false, useMockApi)!,
    })),
  };
}

function normalizeOrder(item: PurchaseOrderListItem): PurchaseOrderListItem {
  return {
    ...item,
    purchaseOrderId: normalizeStringId(item.purchaseOrderId, 'purchaseOrderId'),
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    totalAmount: normalizeMoneyNumber(item.totalAmount, 'totalAmount', false, useMockApi)!,
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    submittedById: normalizeNullableStringId(item.submittedById, 'submittedById'),
    submittedByName: String(item.submittedByName || ''),
    approvedById: normalizeNullableStringId(item.approvedById, 'approvedById'),
    version: normalizeFiniteNumber(item.version, 'version'),
    returnOverview: item.returnOverview ? normalizeReturnOverview(item.returnOverview) : undefined,
  };
}

function normalizeOrderDetail(item: PurchaseOrderDetail): PurchaseOrderDetail {
  return {
    ...normalizeOrder(item),
    items: item.items.map(row => normalizeOrderItem(row)),
    fulfillmentSummary: {
      ...item.fulfillmentSummary,
      totalAmount: normalizeMoneyNumber(item.fulfillmentSummary.totalAmount, 'fulfillmentSummary.totalAmount', false, useMockApi)!,
      inboundAmount: normalizeMoneyNumber(item.fulfillmentSummary.inboundAmount, 'fulfillmentSummary.inboundAmount', false, useMockApi)!,
      completionRate: normalizeFiniteNumber(item.fulfillmentSummary.completionRate, 'fulfillmentSummary.completionRate'),
    },
    timeline: item.timeline.map(timelineItem => ({
      ...timelineItem,
      inboundBillId: normalizeNullableStringId(timelineItem.inboundBillId, 'timeline.inboundBillId'),
    })),
  };
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

const MONEY_QUERY_PATTERN = /^\d+(?:\.\d{1,2})?$/;

function optionalMoneyQuery(value: string | null | undefined) {
  const normalized = value?.trim();
  return normalized && MONEY_QUERY_PATTERN.test(normalized) ? normalized : undefined;
}

function parseMoneyQuery(value: string | null | undefined) {
  const normalized = optionalMoneyQuery(value);
  return normalized === undefined ? undefined : Number(normalized);
}

function filterSuppliers(params: SupplierQuery): PageResult<SupplierListItem> {
  let filtered = [...mockSuppliers];
  const supplierCode = params.supplierCode?.trim().toLocaleLowerCase();
  const supplierName = params.supplierName?.trim().toLocaleLowerCase();
  const contactName = params.contactName?.trim().toLocaleLowerCase();
  const scoreBasisAmountMin = parseMoneyQuery(params.scoreBasisAmountMin);
  const scoreBasisAmountMax = parseMoneyQuery(params.scoreBasisAmountMax);
  if (supplierCode) filtered = filtered.filter(item => item.supplierCode.toLocaleLowerCase().includes(supplierCode));
  if (supplierName) filtered = filtered.filter(item => item.supplierName.toLocaleLowerCase().includes(supplierName));
  if (contactName) filtered = filtered.filter(item => item.contactName.toLocaleLowerCase().includes(contactName));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  if (params.scoreStatus !== '' && params.scoreStatus !== 'all' && params.scoreStatus !== undefined) filtered = filtered.filter(item => item.scoreStatus === params.scoreStatus);
  if (params.overallScoreMin != null) filtered = filtered.filter(item => item.overallScore != null && item.overallScore >= params.overallScoreMin!);
  if (params.overallScoreMax != null) filtered = filtered.filter(item => item.overallScore != null && item.overallScore <= params.overallScoreMax!);
  if (params.serviceScoreMin != null) filtered = filtered.filter(item => item.serviceScore != null && item.serviceScore >= params.serviceScoreMin!);
  if (params.serviceScoreMax != null) filtered = filtered.filter(item => item.serviceScore != null && item.serviceScore <= params.serviceScoreMax!);
  if (scoreBasisAmountMin !== undefined) filtered = filtered.filter(item => item.scoreBasisAmount != null && item.scoreBasisAmount >= scoreBasisAmountMin);
  if (scoreBasisAmountMax !== undefined) filtered = filtered.filter(item => item.scoreBasisAmount != null && item.scoreBasisAmount <= scoreBasisAmountMax);
  if (params.avgDeliveryDaysMin != null) filtered = filtered.filter(item => item.avgDeliveryDays != null && item.avgDeliveryDays >= params.avgDeliveryDaysMin!);
  if (params.avgDeliveryDaysMax != null) filtered = filtered.filter(item => item.avgDeliveryDays != null && item.avgDeliveryDays <= params.avgDeliveryDaysMax!);
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime) || b.supplierId.localeCompare(a.supplierId));
  return { records: pageSlice(filtered, params.pageNum, params.pageSize).map(({ referenced: _, ...item }) => item), total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

function filterScoreChangeLogs(params: ScoreChangeLogQuery): PageResult<ScoreChangeLog> {
  let filtered = [...mockScoreChangeLogs];
  if (params.supplierId) filtered = filtered.filter(item => item.supplierId === params.supplierId);
  if (params.supplierProductId) filtered = filtered.filter(item => item.supplierProductId === params.supplierProductId);
  if (params.metricType) filtered = filtered.filter(item => item.metricType === params.metricType);
  if (params.triggerType) filtered = filtered.filter(item => item.triggerType === params.triggerType);
  if (params.batchNo) filtered = filtered.filter(item => item.batchNo === params.batchNo);
  if (params.startTime) filtered = filtered.filter(item => item.createTime.replace(' ', 'T') >= params.startTime!);
  if (params.endTime) filtered = filtered.filter(item => item.createTime.replace(' ', 'T') <= params.endTime!);
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime) || b.scoreChangeLogId.localeCompare(a.scoreChangeLogId));
  return { records: pageSlice(filtered, params.pageNum, params.pageSize), total: filtered.length, pageNum: params.pageNum, pageSize: params.pageSize };
}

function filterSupplierProducts(params: SupplierProductQuery): PageResult<SupplierProductListItem> {
  let filtered = [...mockSupplierProducts];
  const supplierName = params.supplierName?.trim().toLocaleLowerCase();
  const productCode = params.productCode?.trim().toLocaleLowerCase();
  const productName = params.productName?.trim().toLocaleLowerCase();
  if (params.supplierId && params.supplierId !== 'all') filtered = filtered.filter(item => item.supplierId === params.supplierId);
  if (params.productId && params.productId !== 'all') filtered = filtered.filter(item => item.productId === params.productId);
  if (supplierName) filtered = filtered.filter(item => item.supplierName.toLocaleLowerCase().includes(supplierName));
  if (productCode) filtered = filtered.filter(item => item.productCode.toLocaleLowerCase().includes(productCode));
  if (productName) filtered = filtered.filter(item => item.productName.toLocaleLowerCase().includes(productName));
  if (params.status !== '' && params.status !== 'all' && params.status !== undefined) filtered = filtered.filter(item => item.status === params.status);
  if (params.scoreStatus !== '' && params.scoreStatus !== 'all' && params.scoreStatus !== undefined) filtered = filtered.filter(item => item.scoreStatus === params.scoreStatus);
  if (params.quoteValidUntilEnd) filtered = filtered.filter(item => item.quoteValidUntil != null && item.quoteValidUntil <= params.quoteValidUntilEnd!);
  if (params.qualityScoreMin != null) filtered = filtered.filter(item => item.qualityScore != null && item.qualityScore >= params.qualityScoreMin!);
  if (params.qualityScoreMax != null) filtered = filtered.filter(item => item.qualityScore != null && item.qualityScore <= params.qualityScoreMax!);
  if (params.priceScoreMin != null) filtered = filtered.filter(item => item.priceScore != null && item.priceScore >= params.priceScoreMin!);
  if (params.priceScoreMax != null) filtered = filtered.filter(item => item.priceScore != null && item.priceScore <= params.priceScoreMax!);
  if (params.aiScoreMin != null) filtered = filtered.filter(item => item.aiScore != null && item.aiScore >= params.aiScoreMin!);
  if (params.aiScoreMax != null) filtered = filtered.filter(item => item.aiScore != null && item.aiScore <= params.aiScoreMax!);
  if (params.scoreBasisAmountMin != null) filtered = filtered.filter(item => item.scoreBasisAmount != null && item.scoreBasisAmount >= params.scoreBasisAmountMin!);
  if (params.scoreBasisAmountMax != null) filtered = filtered.filter(item => item.scoreBasisAmount != null && item.scoreBasisAmount <= params.scoreBasisAmountMax!);
  if (params.minOrderQtyMin != null) filtered = filtered.filter(item => item.minOrderQty >= params.minOrderQtyMin!);
  if (params.minOrderQtyMax != null) filtered = filtered.filter(item => item.minOrderQty <= params.minOrderQtyMax!);
  if (params.quoteStatus === 'NONE') filtered = filtered.filter(item => item.quotedPurchasePrice == null);
  if (params.quoteStatus === 'VALID') {
    const today = new Date().toISOString().slice(0, 10);
    filtered = filtered.filter(item => item.quotedPurchasePrice != null && item.quoteValidUntil != null && item.quoteValidUntil >= today);
  }
  if (params.quoteStatus === 'EXPIRED') {
    const today = new Date().toISOString().slice(0, 10);
    filtered = filtered.filter(item => item.quotedPurchasePrice != null && item.quoteValidUntil != null && item.quoteValidUntil < today);
  }
  filtered.sort((a, b) => b.createTime.localeCompare(a.createTime) || b.supplierProductId.localeCompare(a.supplierProductId));
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

export function listSuppliers(params: SupplierQuery, requestConfig?: AxiosRequestConfig) {
  if (useMockApi) return Promise.resolve(normalizePage(filterSuppliers(params), normalizeSupplier));
  const {
    supplierCode, supplierName, contactName, status, scoreStatus, overallScoreMin, overallScoreMax,
    serviceScoreMin, serviceScoreMax, scoreBasisAmountMin, scoreBasisAmountMax, avgDeliveryDaysMin, avgDeliveryDaysMax,
    ...rest
  } = params;
  const optionalNumber = (value: number | null | undefined) => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
  const scoreBasisAmountMinValue = optionalMoneyQuery(scoreBasisAmountMin);
  const scoreBasisAmountMaxValue = optionalMoneyQuery(scoreBasisAmountMax);
  return getResult<PageResult<SupplierListItem>>('/purchase/suppliers', {
    ...rest,
    ...(supplierCode?.trim() ? { supplierCode: supplierCode.trim() } : {}),
    ...(supplierName?.trim() ? { supplierName: supplierName.trim() } : {}),
    ...(contactName?.trim() ? { contactName: contactName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
    ...(scoreStatus !== '' && scoreStatus !== 'all' && scoreStatus !== undefined ? { scoreStatus } : {}),
    ...(optionalNumber(overallScoreMin) !== undefined ? { overallScoreMin: optionalNumber(overallScoreMin) } : {}),
    ...(optionalNumber(overallScoreMax) !== undefined ? { overallScoreMax: optionalNumber(overallScoreMax) } : {}),
    ...(optionalNumber(serviceScoreMin) !== undefined ? { serviceScoreMin: optionalNumber(serviceScoreMin) } : {}),
    ...(optionalNumber(serviceScoreMax) !== undefined ? { serviceScoreMax: optionalNumber(serviceScoreMax) } : {}),
    ...(scoreBasisAmountMinValue !== undefined ? { scoreBasisAmountMin: scoreBasisAmountMinValue } : {}),
    ...(scoreBasisAmountMaxValue !== undefined ? { scoreBasisAmountMax: scoreBasisAmountMaxValue } : {}),
    ...(optionalNumber(avgDeliveryDaysMin) !== undefined ? { avgDeliveryDaysMin: optionalNumber(avgDeliveryDaysMin) } : {}),
    ...(optionalNumber(avgDeliveryDaysMax) !== undefined ? { avgDeliveryDaysMax: optionalNumber(avgDeliveryDaysMax) } : {}),
  }, requestConfig).then(page => normalizePage(page, normalizeSupplier));
}

export function listScoreChangeLogs(params: ScoreChangeLogQuery) {
  const optionalId = (value: string | null | undefined, field: string) => {
    const id = value?.trim();
    if (id && !/^\d+$/.test(id)) throw new Error(`${field} 必须为数字 ID 字符串`);
    return id || undefined;
  };
  const query: ScoreChangeLogQuery = {
    pageNum: params.pageNum,
    pageSize: params.pageSize,
    ...(optionalId(params.supplierId, 'supplierId') ? { supplierId: optionalId(params.supplierId, 'supplierId') } : {}),
    ...(optionalId(params.supplierProductId, 'supplierProductId') ? { supplierProductId: optionalId(params.supplierProductId, 'supplierProductId') } : {}),
    ...(params.metricType ? { metricType: params.metricType } : {}),
    ...(params.triggerType ? { triggerType: params.triggerType } : {}),
    ...(params.batchNo?.trim() ? { batchNo: params.batchNo.trim() } : {}),
    ...(params.startTime?.trim() ? { startTime: params.startTime.trim() } : {}),
    ...(params.endTime?.trim() ? { endTime: params.endTime.trim() } : {}),
  };
  if (useMockApi) return Promise.resolve(normalizePage(filterScoreChangeLogs(query), normalizeScoreChangeLog));
  return getResult<PageResult<ScoreChangeLog>>('/purchase/score-change-logs', { ...query })
    .then(page => normalizePage(page, normalizeScoreChangeLog));
}

/** 详情页始终按主键读取，避免列表分页数据滞后。 */
export function getSupplierDetail(supplierId: string) {
  if (useMockApi) {
    const supplier = mockSuppliers.find(item => item.supplierId === supplierId);
    if (!supplier) return Promise.reject(new Error('供应商不存在'));
    return Promise.resolve(normalizeSupplier(supplier));
  }
  return getResult<SupplierListItem>(`/purchase/suppliers/${supplierId}`, undefined, remoteOptionRequestConfig)
    .then(normalizeSupplier);
}

export async function searchSupplierOptions(keyword = '', pageSize = 10): Promise<SupplierOption[]> {
  const page = await listSuppliers({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'supplierCode', 'supplierName'),
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({
    supplierId: item.supplierId,
    supplierCode: item.supplierCode,
    supplierName: item.supplierName,
    status: item.status,
  }));
}

/** 列表精确筛选允许检索全部供应商，不能复用建档时仅启用供应商的选项。 */
export async function searchSupplierFilterOptions(keyword = '', pageSize = 10): Promise<SupplierOption[]> {
  const page = await listSuppliers({
    pageNum: 1,
    pageSize,
    ...keywordField(keyword, 'supplierCode', 'supplierName'),
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({
    supplierId: item.supplierId,
    supplierCode: item.supplierCode,
    supplierName: item.supplierName,
    status: item.status,
  }));
}

export function createSupplier(payload: SupplierCreatePayload) {
  if (useMockApi) {
    const timestamp = nowText();
    const created: SupplierListItem & { referenced: boolean } = {
      supplierId: String(Date.now()),
      supplierCode: generateCode('S', nextSupplierSequence++),
      ...payload,
      contactName: payload.contactName?.trim() ?? '',
      contactPhone: payload.contactPhone?.trim() ?? '',
      address: payload.address?.trim() ?? '',
      paymentTerms: payload.paymentTerms?.trim() ?? '',
      overallScore: null,
      deliveryScore: null,
      qualityScore: null,
      priceScore: null,
      avgDeliveryDays: null,
      scoreBasisAmount: null,
      scoreStatus: 'NOT_READY',
      serviceScoreReason: payload.serviceScore == null ? '' : (payload.serviceScoreReason || ''),
      version: 0,
      createTime: timestamp,
      updateTime: timestamp,
      updatedById: '1900000000000000001',
      updatedByName: '系统管理员',
      referenced: false,
    };
    mockSuppliers = [...mockSuppliers, created];
    return Promise.resolve(normalizeSupplier(created));
  }
  return postResult<SupplierListItem, SupplierCreatePayload>('/purchase/suppliers', payload).then(normalizeSupplier);
}

export async function updateSupplier(supplierId: string, payload: SupplierUpdatePayload) {
  if (useMockApi) {
    mockSuppliers = mockSuppliers.map(item => {
      if (item.supplierId !== supplierId) return item;
      assertOptimisticVersion(item.version, payload.version);
      return {
        ...item,
        ...payload,
        contactName: payload.contactName?.trim() ?? '',
        contactPhone: payload.contactPhone?.trim() ?? '',
        address: payload.address?.trim() ?? '',
        paymentTerms: payload.paymentTerms?.trim() ?? '',
        version: item.version + 1,
        updateTime: nowText(),
        updatedById: '1900000000000000001',
        updatedByName: '系统管理员',
      };
    });
    const supplier = mockSuppliers.find(item => item.supplierId === supplierId);
    return supplier ? normalizeSupplier(supplier) : null;
  }
  const response = await http.put(`/purchase/suppliers/${supplierId}`, payload);
  return normalizeSupplier(response.data.data as SupplierListItem);
}

export async function updateSupplierServiceScore(supplierId: string, payload: SupplierServiceScorePayload) {
  if (useMockApi) {
    const item = mockSuppliers.find(candidate => candidate.supplierId === supplierId);
    if (!item) return Promise.reject(new Error('供应商不存在'));
    assertOptimisticVersion(item.version, payload.version);
    const previousScore = item.serviceScore;
    item.serviceScore = payload.serviceScore;
    item.serviceScoreReason = payload.serviceScore == null ? '' : payload.reason.trim();
    item.version += 1;
    item.updateTime = nowText();
    if (previousScore !== payload.serviceScore) {
      const sequence = nextScoreChangeLogSequence++;
      mockScoreChangeLogs.unshift({
        scoreChangeLogId: `201700000000000000${sequence}`,
        supplierId,
        supplierProductId: null,
        metricType: 'SERVICE',
        metricScoreBefore: previousScore,
        metricScoreAfter: payload.serviceScore,
        productRecommendScoreBefore: null,
        productRecommendScoreAfter: null,
        supplierOverallScoreBefore: null,
        supplierOverallScoreAfter: null,
        triggerType: 'SERVICE_TRIGGER',
        batchNo: `MOCK-SERVICE-${String(sequence).padStart(4, '0')}`,
        ruleVersion: 'MANUAL',
        relatedSources: [{ businessType: 'SUPPLIER', businessId: supplierId, businessNo: item.supplierCode }],
        operatorType: 'USER',
        operatorId: '1900000000000000001',
        operatorName: '系统管理员',
        reason: payload.reason.trim(),
        createTime: item.updateTime,
      });
    }
    return normalizeSupplier(item);
  }
  const response = await http.put(`/purchase/suppliers/${supplierId}/service-score`, payload);
  return normalizeSupplier(response.data.data as SupplierListItem);
}

export async function updateSupplierStatus(supplierId: string, status: 0 | 1, version: number) {
  if (useMockApi) {
    const timestamp = nowText();
    mockSuppliers = mockSuppliers.map(item => {
      if (item.supplierId !== supplierId) return item;
      assertOptimisticVersion(item.version, version);
      return { ...item, status, version: item.version + 1, updateTime: timestamp, updatedById: '1900000000000000001', updatedByName: '系统管理员' };
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
    return Promise.resolve([] as SupplierBatchFailure[]);
  }
  return http.delete<Result<SupplierBatchFailure[]>>(`/purchase/suppliers/${supplierId}`, { data: { version } })
    .then(response => response.data.data ?? []);
}

export function batchUpdateSupplierStatus(payload: SupplierBatchStatusPayload) {
  if (useMockApi) {
    const timestamp = nowText();
    payload.supplierIds.forEach(supplierId => {
      const item = mockSuppliers.find(candidate => candidate.supplierId === supplierId);
      if (item) assertOptimisticVersion(item.version, payload.versionBySupplierId[supplierId]);
    });
    mockSuppliers = mockSuppliers.map(item => payload.supplierIds.includes(item.supplierId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: timestamp, updatedById: '1900000000000000001', updatedByName: '系统管理员' } : item);
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
    return Promise.resolve([] as SupplierBatchFailure[]);
  }
  return postResult<SupplierBatchFailure[], SupplierBatchIdsPayload>('/purchase/suppliers/batch/delete', payload)
    .then(data => data ?? []);
}

export function listSupplierProducts(params: SupplierProductQuery, requestConfig?: AxiosRequestConfig) {
  if (useMockApi) return Promise.resolve(normalizePage(filterSupplierProducts(params), normalizeSupplierProduct));
  const {
    supplierId, productId, supplierName, productCode, productName, status, scoreStatus, quoteStatus,
    quoteValidUntilEnd, qualityScoreMin, qualityScoreMax, priceScoreMin, priceScoreMax,
    aiScoreMin, aiScoreMax, scoreBasisAmountMin, scoreBasisAmountMax, minOrderQtyMin, minOrderQtyMax,
    pageNum, pageSize,
  } = params;
  const optionalNumber = (value: number | null | undefined) => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
  const optionalDate = (value: string | undefined) => value?.trim() || undefined;
  return getResult<PageResult<SupplierProductListItem>>('/purchase/supplier-products', {
    pageNum,
    pageSize,
    ...(supplierId && supplierId !== 'all' ? { supplierId } : {}),
    ...(productId && productId !== 'all' ? { productId } : {}),
    ...(supplierName?.trim() ? { supplierName: supplierName.trim() } : {}),
    ...(productCode?.trim() ? { productCode: productCode.trim() } : {}),
    ...(productName?.trim() ? { productName: productName.trim() } : {}),
    ...(status !== '' && status !== 'all' && status !== undefined ? { status } : {}),
    ...(scoreStatus !== '' && scoreStatus !== 'all' && scoreStatus !== undefined ? { scoreStatus } : {}),
    ...(quoteStatus !== '' && quoteStatus !== 'all' && quoteStatus !== undefined ? { quoteStatus } : {}),
    ...(optionalDate(quoteValidUntilEnd) ? { quoteValidUntilEnd: optionalDate(quoteValidUntilEnd) } : {}),
    ...(optionalNumber(qualityScoreMin) !== undefined ? { qualityScoreMin: optionalNumber(qualityScoreMin) } : {}),
    ...(optionalNumber(qualityScoreMax) !== undefined ? { qualityScoreMax: optionalNumber(qualityScoreMax) } : {}),
    ...(optionalNumber(priceScoreMin) !== undefined ? { priceScoreMin: optionalNumber(priceScoreMin) } : {}),
    ...(optionalNumber(priceScoreMax) !== undefined ? { priceScoreMax: optionalNumber(priceScoreMax) } : {}),
    ...(optionalNumber(aiScoreMin) !== undefined ? { aiScoreMin: optionalNumber(aiScoreMin) } : {}),
    ...(optionalNumber(aiScoreMax) !== undefined ? { aiScoreMax: optionalNumber(aiScoreMax) } : {}),
    ...(optionalNumber(scoreBasisAmountMin) !== undefined ? { scoreBasisAmountMin: optionalNumber(scoreBasisAmountMin) } : {}),
    ...(optionalNumber(scoreBasisAmountMax) !== undefined ? { scoreBasisAmountMax: optionalNumber(scoreBasisAmountMax) } : {}),
    ...(optionalNumber(minOrderQtyMin) !== undefined ? { minOrderQtyMin: optionalNumber(minOrderQtyMin) } : {}),
    ...(optionalNumber(minOrderQtyMax) !== undefined ? { minOrderQtyMax: optionalNumber(minOrderQtyMax) } : {}),
  }, requestConfig).then(page => normalizePage(page, normalizeSupplierProduct));
}

/** 日志筛选复用供货关系列表的远程分页查询，避免把前 100 条当作全部选项。 */
export async function searchSupplierProductLogOptions(keyword = '', supplierId?: string | null, pageSize = 10) {
  const page = await listSupplierProducts({
    pageNum: 1,
    pageSize,
    ...(supplierId ? { supplierId } : {}),
    ...keywordField(keyword, 'productCode', 'productName'),
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({
    supplierProductId: item.supplierProductId,
    supplierName: item.supplierName,
    productCode: item.productCode,
    productName: item.productName,
  }));
}

/** 详情页始终按主键读取，避免报价或评分刚调整后仍展示旧列表数据。 */
export function getSupplierProductDetail(supplierProductId: string) {
  if (useMockApi) {
    const supplierProduct = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    if (!supplierProduct) return Promise.reject(new Error('供货关系不存在'));
    return Promise.resolve(normalizeSupplierProduct(supplierProduct));
  }
  return getResult<SupplierProductListItem>(`/purchase/supplier-products/${supplierProductId}`, undefined, remoteOptionRequestConfig)
    .then(normalizeSupplierProduct);
}

export function createSupplierProduct(payload: SupplierProductCreatePayload) {
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
      updatedById: '1900000000000000001',
      updatedByName: '系统管理员',
      referenced: false,
      ...payload,
      quotedPriceReason: payload.quotedPurchasePrice == null ? '' : (payload.quoteReason || ''),
      quotedPriceUpdatedAt: payload.quotedPurchasePrice == null ? null : timestamp,
      latestPurchasePrice: null,
      avgDeliveryDays: null,
      qualityScore: null,
      priceScore: null,
      aiScore: null,
      scoreBasisAmount: null,
      scoreStatus: 'NOT_READY',
      version: 0,
    };
    mockSupplierProducts = [...mockSupplierProducts, created];
    return Promise.resolve(normalizeSupplierProduct(created));
  }
  const request = { ...payload, quotedPurchasePrice: serializeMoney(payload.quotedPurchasePrice, '报价', true) };
  return postResult<SupplierProductListItem, typeof request>('/purchase/supplier-products', request).then(normalizeSupplierProduct);
}

export async function updateSupplierProduct(supplierProductId: string, payload: SupplierProductUpdatePayload) {
  if (useMockApi) {
    const current = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    if (current) assertOptimisticVersion(current.version, payload.version);
    mockSupplierProducts = mockSupplierProducts.map(item => item.supplierProductId === supplierProductId ? {
      ...item,
      ...payload,
      version: item.version + 1,
      updateTime: nowText(),
      updatedById: '1900000000000000001',
      updatedByName: '系统管理员',
    } : item);
    const result = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    return result ? normalizeSupplierProduct(result) : null;
  }
  const response = await http.put(`/purchase/supplier-products/${supplierProductId}`, payload);
  return normalizeSupplierProduct(response.data.data as SupplierProductListItem);
}

export async function updateSupplierProductQuote(supplierProductId: string, payload: SupplierProductQuotePayload) {
  if (useMockApi) {
    const current = mockSupplierProducts.find(item => item.supplierProductId === supplierProductId);
    if (!current) return Promise.reject(new Error('供货关系不存在'));
    assertOptimisticVersion(current.version, payload.version);
    current.quotedPurchasePrice = payload.quotedPurchasePrice;
    current.quoteValidUntil = payload.quotedPurchasePrice == null ? null : payload.quoteValidUntil;
    current.quotedPriceReason = payload.quotedPurchasePrice == null ? '' : payload.reason.trim();
    current.quotedPriceUpdatedAt = payload.quotedPurchasePrice == null ? null : nowText();
    current.version += 1;
    current.updateTime = nowText();
    return normalizeSupplierProduct(current);
  }
  const request = { ...payload, quotedPurchasePrice: serializeMoney(payload.quotedPurchasePrice, '报价', true) };
  const response = await http.put(`/purchase/supplier-products/${supplierProductId}/quote`, request);
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
    mockSupplierProducts = mockSupplierProducts.map(item => payload.supplierProductIds.includes(item.supplierProductId) ? { ...item, status: payload.status, version: item.version + 1, updateTime: timestamp, updatedById: '1900000000000000001', updatedByName: '系统管理员' } : item);
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

export function listPurchaseOrders(params: PurchaseOrderQuery, requestConfig?: AxiosRequestConfig) {
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
  }, requestConfig).then(page => normalizePage(page, normalizeOrder));
}

export function getPurchaseOrderDetail(purchaseOrderId: string) {
  if (useMockApi) {
    const order = mockOrders.find(item => item.purchaseOrderId === purchaseOrderId);
    return order ? Promise.resolve(normalizeOrderDetail(order)) : Promise.reject(new Error('采购订单不存在'));
  }
  return getResult<PurchaseOrderDetail>(`/purchase/orders/${purchaseOrderId}`, undefined, { skipPageLoading: true }).then(normalizeOrderDetail);
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
        quantityPrecision: resolveOrderQuantityPrecision(line, product.productId),
        quantity: line.quantity,
        inboundQty: 0,
        unitPrice: line.unitPrice,
        totalAmount: line.quantity * line.unitPrice,
        selectedSupplierScore: line.selectedSupplierScore ?? null,
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
      submittedById: null,
      submittedByName: '',
      approvedById: null,
      approvedByName: '',
      approvedAt: null,
      createTime: timestamp,
      updateTime: timestamp,
      version: 0,
      remark: payload.remark.trim(),
      items,
      fulfillmentSummary: buildFulfillmentSummary(items),
      timeline: [{ event: 'CREATED', occurredAt: timestamp, operatorName: '系统管理员', inboundBillId: null, inboundBillNo: null }],
    });
    mockOrders = [created, ...mockOrders];
    return Promise.resolve(created);
  }
  const request = { ...payload, items: payload.items.map(({ selectedSupplierScore: _currentScore, ...item }) => ({ ...item, unitPrice: serializeMoney(item.unitPrice, '采购单价') })) };
  return postResult<PurchaseOrderDetail, typeof request>('/purchase/orders', request).then(normalizeOrderDetail);
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
        quantityPrecision: resolveOrderQuantityPrecision(line, product.productId),
        quantity: line.quantity,
        inboundQty: 0,
        unitPrice: line.unitPrice,
        totalAmount: line.quantity * line.unitPrice,
        selectedSupplierScore: line.selectedSupplierScore ?? null,
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
      fulfillmentSummary: buildFulfillmentSummary(items),
    });
    mockOrders = mockOrders.map(item => (item.purchaseOrderId === purchaseOrderId ? updated : item));
    return Promise.resolve(updated);
  }
  const request = { ...payload, items: payload.items.map(({ selectedSupplierScore: _currentScore, ...item }) => ({ ...item, unitPrice: serializeMoney(item.unitPrice, '采购单价') })) };
  const response = await http.put(`/purchase/orders/${purchaseOrderId}`, request);
  return normalizeOrderDetail(response.data.data as PurchaseOrderDetail);
}

export function updatePurchaseOrderStatus(purchaseOrderId: string, action: 'submit' | 'approve' | 'cancel', version: number) {
  if (useMockApi) {
    const timestamp = nowText();
    mockOrders = mockOrders.map(item => {
      if (item.purchaseOrderId !== purchaseOrderId) return item;
      if (action === 'cancel' && item.status === 'CANCELLED') return item;
      assertOptimisticVersion(item.version, version);
      if (action === 'submit' && item.status !== 'DRAFT') throw new Error('仅草稿采购单可以提交');
      if (action === 'approve' && item.status !== 'SUBMITTED') throw new Error('仅已提交采购单可以审核');
      if (action === 'cancel' && item.status !== 'DRAFT' && item.status !== 'SUBMITTED' && item.status !== 'APPROVED') {
        throw new Error('仅草稿、已提交或未入库的已审核采购单可以取消');
      }
      if (action === 'cancel' && item.status === 'APPROVED' && item.items.some(line => line.inboundQty > 0)) {
        throw new Error('已发生入库事实的采购订单不允许取消');
      }
      if ((action === 'submit' || action === 'approve') && !item.expectedArrivalDate) throw new Error('提交或审核采购订单前必须维护预计到货日期');
      const timeline = [...item.timeline];
      if (action === 'submit') {
        timeline.push({ event: 'SUBMITTED', occurredAt: timestamp, operatorName: '系统管理员', inboundBillId: null, inboundBillNo: null });
      }
      if (action === 'approve') {
        const inboundBillId = `3010000000000000${item.purchaseNo.slice(-3)}`;
        const inboundBillNo = `IB${item.purchaseNo.slice(2)}`;
        timeline.push({ event: 'APPROVED', occurredAt: timestamp, operatorName: '采购主管', inboundBillId: null, inboundBillNo: null });
        timeline.push({ event: 'INBOUND_CREATED', occurredAt: timestamp, operatorName: '系统', inboundBillId, inboundBillNo });
      }
      return {
        ...item,
        status: action === 'submit' ? 'SUBMITTED' : action === 'approve' ? 'APPROVED' : 'CANCELLED',
        submittedAt: action === 'submit' ? timestamp : item.submittedAt,
        submittedById: action === 'submit' ? '1900000000000000001' : item.submittedById,
        submittedByName: action === 'submit' ? '系统管理员' : item.submittedByName,
        approvedById: action === 'approve' ? '1900000000000000001' : item.approvedById,
        approvedByName: action === 'approve' ? '采购主管' : item.approvedByName,
        approvedAt: action === 'approve' ? timestamp : item.approvedAt,
        version: item.version + 1,
        updateTime: timestamp,
        timeline,
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
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}`, product: item }));
}

/** 列表精确筛选允许检索全部产品，不能复用建档时仅启用产品的选项。 */
export async function listProductFilterOptions(keyword = '', pageSize = 10) {
  const page = await listProducts({
    pageNum: 1,
    pageSize,
    ...keywordField(keyword, 'productCode', 'productName'),
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}` }));
}

/** 仅读取启用供货产品总数，用于采购单明细的可添加行上限判断。 */
export async function getEnabledSupplierProductTotal(supplierId: string) {
  if (!supplierId) return 0;
  const page = await listSupplierProducts({ pageNum: 1, pageSize: 1, status: 1, supplierId }, remoteOptionRequestConfig);
  return page.total;
}

export async function listEnabledWarehouseOptions(keyword = '', pageSize = 10) {
  const page = await listWarehouses({
    pageNum: 1,
    pageSize,
    status: 1,
    ...keywordField(keyword, 'warehouseCode', 'warehouseName'),
  }, remoteOptionRequestConfig);
  return page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}`, warehouse: item }));
}
