import { getResult, http, postResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '../warehouses/api';
import { applyMockWarehouseStockChange, getMockWarehouseStock } from '../stocks/api';
import type {
  StockBillCreatePayload,
  StockBillDetail,
  StockBillDraftItemPayload,
  StockBillDirection,
  StockBillEntryMode,
  StockBillItem,
  StockBillListItem,
  StockBillPage,
  StockBillQuery,
  StockBillSourceType,
  StockBillStatus,
  StockBillSummary,
  StockBillType,
  StockBillUpdatePayload,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const billTypes: StockBillType[] = ['PURCHASE_IN', 'SALES_OUT', 'PURCHASE_RETURN', 'SALES_RETURN', 'ADJUST_IN', 'ADJUST_OUT'];
const sourceTypes: StockBillSourceType[] = ['PURCHASE_ORDER', 'SALES_ORDER', 'PURCHASE_RETURN_ORDER', 'SALES_RETURN_ORDER', 'STOCK_ADJUST'];
const billStatuses: StockBillStatus[] = ['DRAFT', 'PENDING_CONFIRM', 'CONFIRMED', 'CANCELLED'];
const entryModes: StockBillEntryMode[] = ['SOURCE_GENERATED', 'MANUAL_SUPPLEMENT', 'MANUAL_ADJUSTMENT'];
const inboundTypes = new Set<StockBillType>(['PURCHASE_IN', 'SALES_RETURN', 'ADJUST_IN']);
const adjustmentTypes = new Set<StockBillType>(['ADJUST_IN', 'ADJUST_OUT']);
const sourceTypeByBillType: Record<StockBillType, StockBillSourceType> = {
  PURCHASE_IN: 'PURCHASE_ORDER',
  SALES_OUT: 'SALES_ORDER',
  PURCHASE_RETURN: 'PURCHASE_RETURN_ORDER',
  SALES_RETURN: 'SALES_RETURN_ORDER',
  ADJUST_IN: 'STOCK_ADJUST',
  ADJUST_OUT: 'STOCK_ADJUST',
};

function billDirection(billType: StockBillType): StockBillDirection {
  return inboundTypes.has(billType) ? 'INBOUND' : 'OUTBOUND';
}

function nullableNumber(value: unknown, fieldName: string): number | null {
  return value === null || value === undefined ? null : normalizeFiniteNumber(value, fieldName);
}

function formatQty(value: number | null, unitName: string) {
  return value === null ? '-' : `${Number.isInteger(value) ? value.toFixed(0) : value.toFixed(2)} ${unitName}`.trim();
}

function buildQuantitySummary(items: StockBillItem[]) {
  if (items.length === 0) return { quantitySummary: '-', totalCurrentQty: null, quantityUnitName: '' };
  const unitNames = new Set(items.map(item => item.unitName));
  const singleUnit = unitNames.size === 1 ? items[0].unitName : '';
  if (!singleUnit) {
    const itemSummaries = items.slice(0, 2).map(item => {
      return `${item.productCode} ${formatQty(item.quantity, item.unitName)}`;
    });
    return {
      quantitySummary: `本次 ${items.length} 条明细：${itemSummaries.join('；')}${items.length > 2 ? ' 等' : ''}`,
      totalCurrentQty: items.reduce((sum, item) => sum + item.quantity, 0),
      quantityUnitName: '',
    };
  }
  const totalCurrentQty = items.reduce((sum, item) => sum + item.quantity, 0);
  return {
    quantitySummary: `本次 ${formatQty(totalCurrentQty, singleUnit)}`,
    totalCurrentQty,
    quantityUnitName: singleUnit,
  };
}

interface MockBillSeed {
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceNo: string;
  sourcePartyName: string;
  warehouseIndex: number;
  warehouseName: string;
  status: StockBillStatus;
  createTime: string;
  items: Array<[string, string, string, number | null, number | null, number | null, number, number]>;
}

const billSeed: MockBillSeed[] = [
  { billNo: 'IB202606140001', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606001', sourcePartyName: '华东饮品供应链', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 09:12:00', items: [['P0001', '经典原味苏打水', '箱', 48, 0, 0, 30, 12], ['P0002', '速溶黑咖啡', '盒', 20, 0, 0, 12, 5]] },
  { billNo: 'OB202606140002', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606001', sourcePartyName: '上海星河便利店', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 10:05:00', items: [['P0001', '经典原味苏打水', '箱', 8, 0, 0, 8, 94]] },
  { billNo: 'IB202606140003', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606001', sourcePartyName: '华南中心仓', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'DRAFT', createTime: '2026-06-14 10:30:00', items: [['P0007', 'A4复印纸', '箱', null, null, null, 3, 13]] },
  { billNo: 'IB202606140004', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606001', sourcePartyName: '广州天河门店', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'CONFIRMED', createTime: '2026-06-14 11:15:00', items: [['P0008', '热敏标签纸', '卷', 10, 0, 0, 10, 0]] },
  { billNo: 'OB202606130005', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606001', sourcePartyName: '谷仓食品批发', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'CANCELLED', createTime: '2026-06-13 16:42:00', items: [['P0003', '每日坚果混合装', '盒', 6, 2, 2, 2, 31]] },
  { billNo: 'IB202606130006', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606002', sourcePartyName: '谷仓食品批发', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'PENDING_CONFIRM', createTime: '2026-06-13 15:28:00', items: [['P0003', '每日坚果混合装', '盒', 60, 20, 20, 20, 11], ['P0009', '浓缩洗衣液', '瓶', 27, 9, 9, 9, 0]] },
  { billNo: 'OB202606130007', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606002', sourcePartyName: '成都青柠商贸', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 14:50:00', items: [['P0010', '厨房清洁湿巾', '包', 12, 0, 0, 12, 60]] },
  { billNo: 'OB202606130008', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606002', sourcePartyName: '西南中心仓', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 13:20:00', items: [['P0011', '加厚垃圾袋', '卷', null, null, null, 3, 28]] },
  { billNo: 'IB202606120009', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606003', sourcePartyName: '文仪办公渠道', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'PENDING_CONFIRM', createTime: '2026-06-12 17:36:00', items: [['P0012', '无痕粘钩', '包', 40, 0, 20, 20, 11]] },
  { billNo: 'IB202606120010', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606002', sourcePartyName: '武汉江岸客户', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'CONFIRMED', createTime: '2026-06-12 16:18:00', items: [['P0004', '海盐苏打饼干', '箱', 4, 0, 0, 4, 15]] },
  { billNo: 'OB202606120011', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606002', sourcePartyName: '森纸纸业集团', warehouseIndex: 6, warehouseName: '西安中转仓', status: 'CONFIRMED', createTime: '2026-06-12 14:45:00', items: [['P0006', '彩色便利贴', '本', 7, 0, 0, 7, 70]] },
  { billNo: 'OB202606110012', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606003', sourcePartyName: '杭州电商客户', warehouseIndex: 7, warehouseName: '杭州电商仓', status: 'CONFIRMED', createTime: '2026-06-11 18:05:00', items: [['P0014', '无线办公鼠标', '个', 6, 0, 0, 6, 14]] },
  { billNo: 'IB202606110013', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606003', sourcePartyName: '南京备货仓', warehouseIndex: 8, warehouseName: '南京备货仓', status: 'CONFIRMED', createTime: '2026-06-11 15:32:00', items: [['P0013', 'USB-C扩展坞', '个', null, null, null, 2, 15]] },
  { billNo: 'IB202606100014', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606004', sourcePartyName: '文仪办公渠道', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CANCELLED', createTime: '2026-06-10 11:25:00', items: [['P0005', '中性签字笔', '盒', 20, 0, 0, 20, 42.5]] },
  { billNo: 'OB202606100015', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606004', sourcePartyName: '广州天河门店', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'PENDING_CONFIRM', createTime: '2026-06-10 09:40:00', items: [['P0007', 'A4复印纸', '箱', 10, 0, 5, 5, 13]] },
];

function buildMockBills(): StockBillDetail[] {
  return billSeed.map((seed, billIndex) => {
    const stockBillId = `1950000000000000${String(billIndex + 1).padStart(3, '0')}`;
    const confirmed = seed.status === 'CONFIRMED';
    const direction = inboundTypes.has(seed.billType) ? 1 : -1;
    const timestamp = seed.createTime;
    const items: StockBillItem[] = seed.items.map((item, itemIndex) => {
      const [productCode, productName, unitName, planQty, processedQty, pendingQty, quantity, beforeQty] = item;
      const sourceGeneratedWaiting = seed.sourceType !== 'STOCK_ADJUST' && seed.status === 'PENDING_CONFIRM';
      const currentQuantity = sourceGeneratedWaiting ? 0 : quantity;
      const currentPendingQty = sourceGeneratedWaiting && planQty !== null && processedQty !== null ? Math.max(0, planQty - processedQty) : pendingQty;
      const changeQty = confirmed ? direction * currentQuantity : 0;
      const afterQty = confirmed ? beforeQty + changeQty : beforeQty;
      const isQualityInbound = seed.billType === 'PURCHASE_IN' || seed.billType === 'SALES_RETURN';
      const defectiveQty = isQualityInbound && confirmed && itemIndex === 0 && billIndex % 3 === 0 ? 1 : 0;
      return {
        stockBillItemId: `1960000000000${String(billIndex + 1).padStart(3, '0')}${String(itemIndex + 1).padStart(3, '0')}`,
        stockBillId,
        billNo: seed.billNo,
        sourceItemId: `1970000000000${String(billIndex + 1).padStart(3, '0')}${String(itemIndex + 1).padStart(3, '0')}`,
        productId: `1920000000000000${productCode.slice(1).padStart(3, '0')}`,
        productCode,
        productName,
        unitName,
        quantityPrecision: 0,
        planQty,
        processedQty,
        pendingQty: currentPendingQty,
        quantity: currentQuantity,
        qualifiedQty: isQualityInbound ? currentQuantity - defectiveQty : 0,
        defectiveQty,
        beforeQty,
        changeQty,
        afterQty,
        createTime: timestamp,
        updateTime: timestamp,
        remark: defectiveQty > 0 ? '含 1 个不合格品，已记录质检结果' : '',
      };
    });
    const quantitySummary = buildQuantitySummary(items);
    return {
      stockBillId,
      billNo: seed.billNo,
      billType: seed.billType,
      sourceType: seed.sourceType,
      sourceId: `1980000000000000${String(billIndex + 1).padStart(3, '0')}`,
      sourceNo: seed.sourceNo,
      sourcePartyName: seed.sourcePartyName,
      entryMode: seed.sourceType === 'STOCK_ADJUST' ? 'MANUAL_ADJUSTMENT' : 'SOURCE_GENERATED',
      warehouseId: `1930000000000000${String(seed.warehouseIndex).padStart(3, '0')}`,
      warehouseName: seed.warehouseName,
      status: seed.status,
      itemCount: items.length,
      ...quantitySummary,
      confirmedById: confirmed ? '1900000000000000001' : null,
      confirmedByName: confirmed ? '系统管理员' : '',
      confirmedAt: confirmed ? timestamp : null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      responsibleById: '1900000000000000001',
      version: 0,
      responsibleByName: '系统管理员',
      createTime: timestamp,
      updateTime: timestamp,
      remark: seed.status === 'CANCELLED' ? '业务单据取消，库存未发生变化' : '',
      manualReason: seed.sourceType === 'STOCK_ADJUST' ? '库存盘点调整' : '',
      items,
    };
  });
}

let mockBills = buildMockBills();
let nextMockBillSequence = mockBills.length + 1;

function normalizeEnum<T extends string>(value: unknown, values: readonly T[], fieldName: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) throw new Error(`接口字段 ${fieldName} 状态非法`);
  return value as T;
}

function normalizeStockBill(item: StockBillListItem): StockBillListItem {
  return {
    ...item,
    stockBillId: normalizeStringId(item.stockBillId, 'stockBillId'),
    billNo: String(item.billNo),
    billType: normalizeEnum(item.billType, billTypes, 'billType'),
    sourceType: normalizeEnum(item.sourceType, sourceTypes, 'sourceType'),
    sourceId: normalizeNullableStringId(item.sourceId, 'sourceId'),
    sourceNo: String(item.sourceNo),
    sourcePartyName: String(item.sourcePartyName),
    entryMode: normalizeEnum(item.entryMode, entryModes, 'entryMode'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    warehouseName: String(item.warehouseName),
    status: normalizeEnum(item.status, billStatuses, 'status'),
    itemCount: normalizeFiniteNumber(item.itemCount, 'itemCount'),
    quantitySummary: String(item.quantitySummary),
    totalCurrentQty: nullableNumber(item.totalCurrentQty, 'totalCurrentQty'),
    quantityUnitName: String(item.quantityUnitName),
    confirmedById: normalizeNullableStringId(item.confirmedById, 'confirmedById'),
    confirmedByName: String(item.confirmedByName),
    confirmedAt: item.confirmedAt ? String(item.confirmedAt) : null,
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    createdByName: String(item.createdByName),
    responsibleById: normalizeStringId(item.responsibleById, 'responsibleById'),
    responsibleByName: String(item.responsibleByName),
    version: normalizeFiniteNumber(item.version, 'version'),
    createTime: String(item.createTime),
    updateTime: String(item.updateTime),
  };
}

function normalizeStockBillItem(item: StockBillItem): StockBillItem {
  const quantityFields = ['quantity', 'qualifiedQty', 'defectiveQty', 'beforeQty', 'changeQty', 'afterQty'] as const;
  const quantities = Object.fromEntries(quantityFields.map(field => [field, normalizeFiniteNumber(item[field], field)]));
  if (quantities.quantity < 0 || quantities.qualifiedQty < 0 || quantities.defectiveQty < 0 || quantities.beforeQty < 0 || quantities.afterQty < 0) {
    throw new Error('出入库明细数量不符合非负数约束');
  }
  const quantityPrecision = normalizeFiniteNumber(item.quantityPrecision, 'quantityPrecision');
  if (!Number.isInteger(quantityPrecision) || quantityPrecision < 0 || quantityPrecision > 2) {
    throw new Error('接口字段 quantityPrecision 必须是 0 到 2 的整数');
  }
  return {
    ...item,
    stockBillItemId: normalizeStringId(item.stockBillItemId, 'stockBillItemId'),
    stockBillId: normalizeStringId(item.stockBillId, 'stockBillId'),
    billNo: String(item.billNo),
    sourceItemId: normalizeNullableStringId(item.sourceItemId, 'sourceItemId'),
    productId: normalizeStringId(item.productId, 'productId'),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    quantityPrecision,
    planQty: nullableNumber(item.planQty, 'planQty'),
    processedQty: nullableNumber(item.processedQty, 'processedQty'),
    pendingQty: nullableNumber(item.pendingQty, 'pendingQty'),
    quantity: quantities.quantity,
    qualifiedQty: quantities.qualifiedQty,
    defectiveQty: quantities.defectiveQty,
    beforeQty: quantities.beforeQty,
    changeQty: quantities.changeQty,
    afterQty: quantities.afterQty,
    createTime: String(item.createTime),
    updateTime: String(item.updateTime),
    remark: String(item.remark),
  };
}

function normalizeStockBillDetail(detail: StockBillDetail): StockBillDetail {
  return { ...normalizeStockBill(detail), manualReason: String(detail.manualReason), remark: String(detail.remark), items: detail.items.map(normalizeStockBillItem) };
}

function normalizeSummary(summary: StockBillSummary): StockBillSummary {
  return {
    inboundCount: normalizeFiniteNumber(summary.inboundCount, 'inboundCount'),
    outboundCount: normalizeFiniteNumber(summary.outboundCount, 'outboundCount'),
    sourceGeneratedCount: normalizeFiniteNumber(summary.sourceGeneratedCount, 'sourceGeneratedCount'),
    pendingCount: normalizeFiniteNumber(summary.pendingCount, 'pendingCount'),
    confirmedCount: normalizeFiniteNumber(summary.confirmedCount, 'confirmedCount'),
    cancelledCount: normalizeFiniteNumber(summary.cancelledCount, 'cancelledCount'),
  };
}

function buildSummary(records: StockBillListItem[]): StockBillSummary {
  return {
    inboundCount: records.filter(item => inboundTypes.has(item.billType)).length,
    outboundCount: records.filter(item => !inboundTypes.has(item.billType)).length,
    sourceGeneratedCount: records.filter(item => item.entryMode === 'SOURCE_GENERATED').length,
    pendingCount: records.filter(item => item.status === 'PENDING_CONFIRM').length,
    confirmedCount: records.filter(item => item.status === 'CONFIRMED').length,
    cancelledCount: records.filter(item => item.status === 'CANCELLED').length,
  };
}

function filterMockBills(params: StockBillQuery): StockBillPage {
  const billNo = params.billNo?.trim().toLocaleLowerCase();
  const sourceNo = params.sourceNo?.trim().toLocaleLowerCase();
  const filtered = mockBills.filter(item => {
    if (params.direction && params.direction !== 'all' && billDirection(item.billType) !== params.direction) return false;
    if (billNo && !item.billNo.toLocaleLowerCase().includes(billNo)) return false;
    if (sourceNo && !item.sourceNo.toLocaleLowerCase().includes(sourceNo)) return false;
    if (params.warehouseId && params.warehouseId !== 'all' && item.warehouseId !== params.warehouseId) return false;
    if (params.billType && params.billType !== 'all' && item.billType !== params.billType) return false;
    if (params.entryMode && params.entryMode !== 'all' && item.entryMode !== params.entryMode) return false;
    return !params.status || params.status === 'all' || item.status === params.status;
  }).sort((a, b) => b.createTime.localeCompare(a.createTime) || b.billNo.localeCompare(a.billNo));
  const start = (params.pageNum - 1) * params.pageSize;
  const records = filtered.slice(start, start + params.pageSize).map(normalizeStockBill);
  return {
    records,
    total: filtered.length,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
    summary: normalizeSummary(buildSummary(records)),
  };
}

function normalizeStockBillPage(page: StockBillPage): StockBillPage {
  const records = page.records.map(normalizeStockBill);
  return {
    records,
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
    summary: normalizeSummary(buildSummary(records)),
  };
}

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function assertOptimisticVersion(current: number, expected: number | undefined) {
  if (expected !== undefined && current !== expected) throw new Error('数据已被其他人修改，请刷新后重试');
}

function dateKey() {
  return billSeed[0].billNo.slice(2, 10);
}

function requireDraft(stockBillId: string) {
  const bill = mockBills.find(item => item.stockBillId === stockBillId);
  if (!bill) throw new Error('入库单或出库单不存在');
  if (bill.status !== 'DRAFT' && bill.status !== 'PENDING_CONFIRM') throw new Error('只有草稿或待确认状态的入库单/出库单可以操作');
  return bill;
}

function matchesPrecision(value: number, precision: number) {
  return Math.abs(value * 10 ** precision - Math.round(value * 10 ** precision)) < 1e-8;
}

function validateDraftItems(items: StockBillDraftItemPayload[], billType: StockBillType, precisionByProduct?: Map<string, number>) {
  if (!items.length) throw new Error('至少添加一条产品明细');
  if (new Set(items.map(item => item.productId)).size !== items.length) throw new Error('同一产品不能重复添加');
  const qualityInbound = billType === 'PURCHASE_IN' || billType === 'SALES_RETURN';
  items.forEach(item => {
    if (!item.productId) throw new Error('请选择产品');
    if (!Number.isFinite(item.quantity) || item.quantity <= 0) throw new Error('出入库数量必须大于 0');
    const precision = precisionByProduct?.get(item.productId) ?? 2;
    if (!matchesPrecision(item.quantity, precision) || !matchesPrecision(item.qualifiedQty, precision) || !matchesPrecision(item.defectiveQty, precision)) {
      throw new Error(`产品数量最多保留 ${precision} 位小数`);
    }
    if (!Number.isFinite(item.qualifiedQty) || item.qualifiedQty < 0 || !Number.isFinite(item.defectiveQty) || item.defectiveQty < 0) {
      throw new Error('合格数量和不合格数量不能小于 0');
    }
    if (qualityInbound && Math.abs(item.qualifiedQty + item.defectiveQty - item.quantity) > 0.0001) {
      throw new Error('采购入库和销售退货的合格数量与不合格数量之和必须等于本次数量');
    }
  });
}

async function loadMockMasterData() {
  const [warehouses, products] = await Promise.all([
    listWarehouses({ status: 1, pageNum: 1, pageSize: 100 }),
    listProducts({ status: 1, pageNum: 1, pageSize: 100 }),
  ]);
  return { warehouses: warehouses.records, products: products.records };
}

function nextBillIdentity(billType: StockBillType) {
  const sequence = String(nextMockBillSequence).padStart(4, '0');
  nextMockBillSequence += 1;
  return { billNo: `${billDirection(billType) === 'INBOUND' ? 'IB' : 'OB'}${dateKey()}${sequence}`, sourceNo: `ADJ${dateKey()}${sequence}` };
}

export async function createStockBill(payload: StockBillCreatePayload) {
  if (useMockApi) {
    const { warehouses, products } = await loadMockMasterData();
    validateDraftItems(payload.items, payload.billType, new Map(products.map(item => [item.productId, item.quantityPrecision])));
    const warehouse = warehouses.find(item => item.warehouseId === payload.warehouseId);
    if (!warehouse) throw new Error('只能选择启用状态的仓库');
    const isAdjustment = adjustmentTypes.has(payload.billType);
    const sourceNo = isAdjustment ? '' : payload.sourceNo.trim();
    const manualReason = payload.manualReason.trim();
    if (!isAdjustment && !sourceNo) throw new Error('手工补录采购、销售或退货凭证时必须填写原业务单号');
    if (sourceNo.length > 64) throw new Error('原业务单号不能超过 64 个字符');
    if (!manualReason) throw new Error(isAdjustment ? '请填写调整原因' : '请填写补录原因');
    if (manualReason.length > 500) throw new Error('补录或调整原因不能超过 500 个字符');
    const identity = nextBillIdentity(payload.billType);
    const timestamp = nowText();
    const stockBillId = String(Date.now());
    const items = payload.items.map((item, index) => {
      const product = products.find(option => option.productId === item.productId);
      if (!product) throw new Error('只能选择启用状态的产品');
      const currentStock = getMockWarehouseStock(warehouse.warehouseId, product.productId);
      return {
        stockBillItemId: `${stockBillId}${index + 1}`,
        stockBillId,
        billNo: identity.billNo,
        sourceItemId: null,
        productId: product.productId,
        productCode: product.productCode,
        productName: product.productName,
        unitName: product.unitName,
        quantityPrecision: product.quantityPrecision,
        planQty: null,
        processedQty: null,
        pendingQty: null,
        quantity: item.quantity,
        qualifiedQty: payload.billType === 'PURCHASE_IN' || payload.billType === 'SALES_RETURN' ? item.qualifiedQty : 0,
        defectiveQty: payload.billType === 'PURCHASE_IN' || payload.billType === 'SALES_RETURN' ? item.defectiveQty : 0,
        beforeQty: currentStock?.stockQty || 0,
        changeQty: 0,
        afterQty: currentStock?.stockQty || 0,
        createTime: timestamp,
        updateTime: timestamp,
        remark: item.remark.trim(),
      } satisfies StockBillItem;
    });
    const quantitySummary = buildQuantitySummary(items);
    const created: StockBillDetail = {
      stockBillId,
      billNo: identity.billNo,
      billType: payload.billType,
      sourceType: sourceTypeByBillType[payload.billType],
      sourceId: null,
      sourceNo: isAdjustment ? identity.sourceNo : sourceNo,
      sourcePartyName: isAdjustment ? warehouse.warehouseName : '手工补录',
      entryMode: isAdjustment ? 'MANUAL_ADJUSTMENT' : 'MANUAL_SUPPLEMENT',
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      status: 'DRAFT',
      itemCount: items.length,
      ...quantitySummary,
      confirmedById: null,
      confirmedByName: '',
      confirmedAt: null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      responsibleById: '1900000000000000001',
      responsibleByName: '系统管理员',
      version: 0,
      createTime: timestamp,
      updateTime: timestamp,
      manualReason,
      remark: payload.remark.trim(),
      items,
    };
    mockBills = [created, ...mockBills];
    return normalizeStockBillDetail(created);
  }
  return postResult<StockBillDetail, StockBillCreatePayload>('/warehouse/stock-bills', payload).then(normalizeStockBillDetail);
}

export async function updateStockBill(stockBillId: string, payload: StockBillUpdatePayload) {
  if (useMockApi) {
    const current = requireDraft(stockBillId);
    assertOptimisticVersion(current.version, payload.version);
    validateDraftItems(payload.items, current.billType, new Map(current.items.map(item => [item.productId, item.quantityPrecision])));
    const sourceGenerated = current.entryMode === 'SOURCE_GENERATED';
    const structureLocked = sourceGenerated || current.status === 'PENDING_CONFIRM';
    if (structureLocked && (payload.items.length !== current.items.length
      || payload.items.some(item => !item.stockBillItemId || !current.items.some(existing => existing.stockBillItemId === item.stockBillItemId && existing.productId === item.productId)))) {
      throw new Error('来源生成单或待确认单不能增删或更换产品');
    }
    const { warehouses, products } = await loadMockMasterData();
    const warehouseChanged = Boolean(payload.warehouseId && payload.warehouseId !== current.warehouseId);
    if (warehouseChanged && current.status !== 'DRAFT') throw new Error('待确认单不能修改仓库');
    const warehouse = warehouseChanged ? warehouses.find(item => item.warehouseId === payload.warehouseId) : warehouses.find(item => item.warehouseId === current.warehouseId);
    if (!warehouse) throw new Error('只能选择启用状态的仓库');
    const timestamp = nowText();
    const items = payload.items.map((item, index) => {
      const existing = current.items.find(candidate => candidate.stockBillItemId === item.stockBillItemId);
      const product = products.find(option => option.productId === item.productId);
      if (!product && !existing) throw new Error('产品不存在或已停用');
      const snapshot = sourceGenerated ? existing : product || existing;
      if (!snapshot) throw new Error('产品不存在');
      const currentStock = getMockWarehouseStock(warehouse.warehouseId, item.productId);
      return {
        stockBillItemId: existing?.stockBillItemId || `${stockBillId}${Date.now()}${index + 1}`,
        stockBillId,
        billNo: current.billNo,
        sourceItemId: existing?.sourceItemId || null,
        productId: item.productId,
        productCode: snapshot.productCode,
        productName: snapshot.productName,
        unitName: snapshot.unitName,
        quantityPrecision: snapshot.quantityPrecision,
        planQty: existing?.planQty ?? null,
        processedQty: existing?.processedQty ?? null,
        pendingQty: !existing || existing.planQty === null || existing.processedQty === null
          ? null
          : Math.max(0, existing.planQty - existing.processedQty - item.quantity),
        quantity: item.quantity,
        qualifiedQty: current.billType === 'PURCHASE_IN' || current.billType === 'SALES_RETURN' ? item.qualifiedQty : 0,
        defectiveQty: current.billType === 'PURCHASE_IN' || current.billType === 'SALES_RETURN' ? item.defectiveQty : 0,
        beforeQty: currentStock?.stockQty || 0,
        changeQty: 0,
        afterQty: currentStock?.stockQty || 0,
        createTime: existing?.createTime || timestamp,
        updateTime: timestamp,
        remark: item.remark.trim(),
      } satisfies StockBillItem;
    });
    const isSupplement = current.entryMode === 'MANUAL_SUPPLEMENT';
    const sourceNo = isSupplement ? payload.sourceNo.trim() : current.sourceNo;
    const manualReason = sourceGenerated ? current.manualReason : payload.manualReason.trim();
    if (isSupplement && !sourceNo) throw new Error('手工补录凭证必须填写原业务单号');
    if (sourceNo.length > 64) throw new Error('原业务单号不能超过 64 个字符');
    if (!sourceGenerated && !manualReason) throw new Error(current.entryMode === 'MANUAL_ADJUSTMENT' ? '请填写调整原因' : '请填写补录原因');
    if (manualReason.length > 500) throw new Error('补录或调整原因不能超过 500 个字符');
    const updated = {
      ...current,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      sourcePartyName: current.entryMode === 'MANUAL_ADJUSTMENT' ? warehouse.warehouseName : current.sourcePartyName,
      sourceNo,
      manualReason,
      items,
      itemCount: items.length,
      ...buildQuantitySummary(items),
      remark: payload.remark.trim(),
      version: current.version + 1,
      updateTime: timestamp,
    };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? updated : item);
    return normalizeStockBillDetail(updated);
  }
  const response = await http.put(`/warehouse/stock-bills/${stockBillId}`, payload);
  return normalizeStockBillDetail(response.data.data as StockBillDetail);
}

export async function confirmStockBill(stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.stockBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'CONFIRMED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CANCELLED') throw new Error('已取消的入库单或出库单不能确认');
    if (existing.status !== 'PENDING_CONFIRM') throw new Error('草稿必须先提交为待确认后才能确认入库/出库');
    const current = existing;
    validateDraftItems(current.items, current.billType);
    current.items.forEach(item => {
      if (current.entryMode !== 'SOURCE_GENERATED' || item.planQty === null || item.processedQty === null) return;
      const remainingBefore = Math.max(0, item.planQty - item.processedQty);
      if (item.quantity > remainingBefore) throw new Error(`产品 ${item.productCode} 的本次数量不能超过来源剩余数量`);
    });
    const { warehouses, products } = await loadMockMasterData();
    const warehouse = warehouses.find(item => item.warehouseId === current.warehouseId);
    if (!warehouse) throw new Error('当前仓库已停用，不能确认出入库');
    const direction = inboundTypes.has(current.billType) ? 1 : -1;
    current.items.forEach(item => {
      const stock = getMockWarehouseStock(current.warehouseId, item.productId);
      const afterQty = (stock?.stockQty || 0) + direction * item.quantity;
      const afterLockedQty = (stock?.lockedQty || 0) - (current.billType === 'SALES_OUT' ? item.quantity : 0);
      if (afterQty < 0) throw new Error(`产品 ${item.productCode} 库存不足，无法确认出库`);
      if (afterLockedQty < 0) throw new Error(`产品 ${item.productCode} 的销售锁定库存不足`);
      if (afterQty < afterLockedQty) throw new Error(`产品 ${item.productCode} 出库后库存不能低于锁定库存`);
    });
    const timestamp = nowText();
    const items = current.items.map(item => {
      const product = products.find(option => option.productId === item.productId);
      const result = applyMockWarehouseStockChange({
        warehouseId: current.warehouseId,
        warehouseCode: warehouse.warehouseCode,
        warehouseName: current.warehouseName,
        productId: item.productId,
        productCode: item.productCode,
        productName: item.productName,
        unitName: item.unitName,
        safetyStockQty: product?.safetyStockQty || 0,
        changeQty: direction * item.quantity,
        lockedChangeQty: current.billType === 'SALES_OUT' ? -item.quantity : 0,
      });
      return {
        ...item,
        beforeQty: result.beforeQty,
        changeQty: direction * item.quantity,
        afterQty: result.afterQty,
        updateTime: timestamp,
      };
    });
    const confirmed: StockBillDetail = {
      ...current,
      status: 'CONFIRMED',
      ...buildQuantitySummary(items),
      confirmedById: '1900000000000000001',
      confirmedByName: '系统管理员',
      confirmedAt: timestamp,
      version: current.version + 1,
      updateTime: timestamp,
      items,
    };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? confirmed : item);
    return normalizeStockBillDetail(confirmed);
  }
  return postResult<StockBillDetail, { version: number }>(`/warehouse/stock-bills/${stockBillId}/confirm`, { version }).then(normalizeStockBillDetail);
}

export async function submitStockBill(stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.stockBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'PENDING_CONFIRM') return normalizeStockBillDetail(existing);
    if (existing.status !== 'DRAFT') throw new Error('只有草稿状态的入库单/出库单可以提交确认');
    validateDraftItems(existing.items, existing.billType);
    const timestamp = nowText();
    const submitted: StockBillDetail = { ...existing, status: 'PENDING_CONFIRM', version: existing.version + 1, updateTime: timestamp };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? submitted : item);
    return normalizeStockBillDetail(submitted);
  }
  return postResult<StockBillDetail, { version: number }>(`/warehouse/stock-bills/${stockBillId}/submit`, { version }).then(normalizeStockBillDetail);
}

export async function cancelStockBill(stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.stockBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'CANCELLED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CONFIRMED') throw new Error('已确认凭证不能直接取消，请创建反向库存调整');
    const current = existing;
    const timestamp = nowText();
    const cancelled: StockBillDetail = { ...current, status: 'CANCELLED', version: current.version + 1, updateTime: timestamp };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? cancelled : item);
    return normalizeStockBillDetail(cancelled);
  }
  return postResult<StockBillDetail, { version: number }>(`/warehouse/stock-bills/${stockBillId}/cancel`, { version }).then(normalizeStockBillDetail);
}

export function listStockBills(params: StockBillQuery) {
  if (useMockApi) return Promise.resolve(filterMockBills(params));
  const { direction, billNo, sourceNo, warehouseId, billType, entryMode, status, ...rest } = params;
  const endpoint = direction === 'INBOUND'
    ? '/warehouse/inbound-bills'
    : direction === 'OUTBOUND'
      ? '/warehouse/outbound-bills'
      : '/warehouse/stock-bills';
  return getResult<StockBillPage>(endpoint, {
    ...rest,
    ...(billNo?.trim() ? { billNo: billNo.trim() } : {}),
    ...(sourceNo?.trim() ? { sourceNo: sourceNo.trim() } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(billType && billType !== 'all' ? { billType } : {}),
    ...(entryMode && entryMode !== 'all' ? { entryMode } : {}),
    ...(status && status !== 'all' ? { status } : {}),
  }).then(normalizeStockBillPage);
}

export function getStockBillDetail(stockBillId: string) {
  if (useMockApi) {
    const detail = mockBills.find(item => item.stockBillId === stockBillId);
    return detail ? Promise.resolve(normalizeStockBillDetail(detail)) : Promise.reject(new Error('入库单或出库单不存在'));
  }
  return getResult<StockBillDetail>(`/warehouse/stock-bills/${stockBillId}`).then(normalizeStockBillDetail);
}
