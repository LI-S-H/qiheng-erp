import { getResult, http, postResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { listProducts } from '@/modules/product/products/api';
import { listWarehouses } from '../warehouses/api';
import { applyMockWarehouseStockChange, getMockWarehouseStock } from '../stocks/api';
import type {
  StockBillCreatePayload,
  StockBillDetail,
  StockBillDraftItemPayload,
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
const billStatuses: StockBillStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED'];
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

interface MockBillSeed {
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceNo: string;
  warehouseIndex: number;
  warehouseName: string;
  status: StockBillStatus;
  createTime: string;
  items: Array<[string, string, string, number, number, number]>;
}

const billSeed: MockBillSeed[] = [
  { billNo: 'SB202606140001', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606001', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 09:12:00', items: [['P0001', '经典原味苏打水', '箱', 30, 12, 42], ['P0002', '速溶黑咖啡', '盒', 12, 5, 17]] },
  { billNo: 'SB202606140002', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606001', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 10:05:00', items: [['P0001', '经典原味苏打水', '箱', 8, 94, 86]] },
  { billNo: 'SB202606140003', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606001', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'DRAFT', createTime: '2026-06-14 10:30:00', items: [['P0007', 'A4复印纸', '箱', 3, 13, 13]] },
  { billNo: 'SB202606140004', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606001', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'CONFIRMED', createTime: '2026-06-14 11:15:00', items: [['P0008', '热敏标签纸', '卷', 10, 0, 10]] },
  { billNo: 'SB202606130005', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606001', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'CANCELLED', createTime: '2026-06-13 16:42:00', items: [['P0003', '每日坚果混合装', '盒', 2, 31, 31]] },
  { billNo: 'SB202606130006', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606002', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'CONFIRMED', createTime: '2026-06-13 15:28:00', items: [['P0003', '每日坚果混合装', '盒', 20, 11, 31], ['P0009', '浓缩洗衣液', '瓶', 9, 0, 9]] },
  { billNo: 'SB202606130007', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606002', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 14:50:00', items: [['P0010', '厨房清洁湿巾', '包', 12, 60, 48]] },
  { billNo: 'SB202606130008', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606002', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 13:20:00', items: [['P0011', '加厚垃圾袋', '卷', 3, 28, 25]] },
  { billNo: 'SB202606120009', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606003', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'DRAFT', createTime: '2026-06-12 17:36:00', items: [['P0012', '无痕粘钩', '包', 20, 11, 11]] },
  { billNo: 'SB202606120010', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606002', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'CONFIRMED', createTime: '2026-06-12 16:18:00', items: [['P0004', '海盐苏打饼干', '箱', 4, 15, 19]] },
  { billNo: 'SB202606120011', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606002', warehouseIndex: 6, warehouseName: '西安中转仓', status: 'CONFIRMED', createTime: '2026-06-12 14:45:00', items: [['P0006', '彩色便利贴', '本', 7, 70, 63]] },
  { billNo: 'SB202606110012', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606003', warehouseIndex: 7, warehouseName: '杭州电商仓', status: 'CONFIRMED', createTime: '2026-06-11 18:05:00', items: [['P0014', '无线办公鼠标', '个', 6, 14, 8]] },
  { billNo: 'SB202606110013', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606003', warehouseIndex: 8, warehouseName: '南京备货仓', status: 'CONFIRMED', createTime: '2026-06-11 15:32:00', items: [['P0013', 'USB-C扩展坞', '个', 2, 15, 17]] },
  { billNo: 'SB202606100014', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606004', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CANCELLED', createTime: '2026-06-10 11:25:00', items: [['P0005', '中性签字笔', '盒', 20, 42.5, 42.5]] },
  { billNo: 'SB202606100015', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606004', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'DRAFT', createTime: '2026-06-10 09:40:00', items: [['P0007', 'A4复印纸', '箱', 5, 13, 13]] },
];

function buildMockBills(): StockBillDetail[] {
  return billSeed.map((seed, billIndex) => {
    const stockBillId = `1950000000000000${String(billIndex + 1).padStart(3, '0')}`;
    const confirmed = seed.status === 'CONFIRMED';
    const direction = inboundTypes.has(seed.billType) ? 1 : -1;
    const timestamp = seed.createTime;
    const items: StockBillItem[] = seed.items.map((item, itemIndex) => {
      const [productCode, productName, unitName, quantity, beforeQty, confirmedAfterQty] = item;
      const changeQty = confirmed ? direction * quantity : 0;
      const afterQty = confirmed ? confirmedAfterQty : beforeQty;
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
        quantity,
        qualifiedQty: isQualityInbound ? quantity - defectiveQty : 0,
        defectiveQty,
        beforeQty,
        changeQty,
        afterQty,
        createTime: timestamp,
        updateTime: timestamp,
        remark: defectiveQty > 0 ? '含 1 个不合格品，已记录质检结果' : '',
      };
    });
    return {
      stockBillId,
      billNo: seed.billNo,
      billType: seed.billType,
      sourceType: seed.sourceType,
      sourceId: `1980000000000000${String(billIndex + 1).padStart(3, '0')}`,
      sourceNo: seed.sourceNo,
      entryMode: seed.sourceType === 'STOCK_ADJUST' ? 'MANUAL_ADJUSTMENT' : 'SOURCE_GENERATED',
      warehouseId: `1930000000000000${String(seed.warehouseIndex).padStart(3, '0')}`,
      warehouseName: seed.warehouseName,
      status: seed.status,
      itemCount: items.length,
      confirmedById: confirmed ? '1900000000000000001' : null,
      confirmedByName: confirmed ? '系统管理员' : '',
      confirmedAt: confirmed ? timestamp : null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      responsibleById: '1900000000000000001',
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
    entryMode: normalizeEnum(item.entryMode, entryModes, 'entryMode'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    warehouseName: String(item.warehouseName),
    status: normalizeEnum(item.status, billStatuses, 'status'),
    itemCount: normalizeFiniteNumber(item.itemCount, 'itemCount'),
    confirmedById: normalizeNullableStringId(item.confirmedById, 'confirmedById'),
    confirmedByName: String(item.confirmedByName),
    confirmedAt: item.confirmedAt ? String(item.confirmedAt) : null,
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    createdByName: String(item.createdByName),
    responsibleById: normalizeStringId(item.responsibleById, 'responsibleById'),
    responsibleByName: String(item.responsibleByName),
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
    stockBillCount: normalizeFiniteNumber(summary.stockBillCount, 'stockBillCount'),
    inboundCount: normalizeFiniteNumber(summary.inboundCount, 'inboundCount'),
    outboundCount: normalizeFiniteNumber(summary.outboundCount, 'outboundCount'),
    confirmedCount: normalizeFiniteNumber(summary.confirmedCount, 'confirmedCount'),
  };
}

function buildSummary(records: StockBillDetail[]): StockBillSummary {
  return {
    stockBillCount: records.length,
    inboundCount: records.filter(item => inboundTypes.has(item.billType)).length,
    outboundCount: records.filter(item => !inboundTypes.has(item.billType)).length,
    confirmedCount: records.filter(item => item.status === 'CONFIRMED').length,
  };
}

function filterMockBills(params: StockBillQuery): StockBillPage {
  const billNo = params.billNo?.trim().toLocaleLowerCase();
  const sourceNo = params.sourceNo?.trim().toLocaleLowerCase();
  const filtered = mockBills.filter(item => {
    if (billNo && !item.billNo.toLocaleLowerCase().includes(billNo)) return false;
    if (sourceNo && !item.sourceNo.toLocaleLowerCase().includes(sourceNo)) return false;
    if (params.warehouseId && params.warehouseId !== 'all' && item.warehouseId !== params.warehouseId) return false;
    if (params.billType && params.billType !== 'all' && item.billType !== params.billType) return false;
    if (params.entryMode && params.entryMode !== 'all' && item.entryMode !== params.entryMode) return false;
    return !params.status || params.status === 'all' || item.status === params.status;
  }).sort((a, b) => b.createTime.localeCompare(a.createTime) || b.billNo.localeCompare(a.billNo));
  const start = (params.pageNum - 1) * params.pageSize;
  return {
    records: filtered.slice(start, start + params.pageSize).map(normalizeStockBill),
    total: filtered.length,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
    summary: normalizeSummary(buildSummary(filtered)),
  };
}

function normalizeStockBillPage(page: StockBillPage): StockBillPage {
  return {
    records: page.records.map(normalizeStockBill),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
    summary: normalizeSummary(page.summary),
  };
}

function nowText() {
  return new Date().toISOString().slice(0, 19).replace('T', ' ');
}

function dateKey() {
  return billSeed[0].billNo.slice(2, 10);
}

function requireDraft(stockBillId: string) {
  const bill = mockBills.find(item => item.stockBillId === stockBillId);
  if (!bill) throw new Error('出入库流水不存在');
  if (bill.status !== 'DRAFT') throw new Error('只有草稿状态的出入库流水可以操作');
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

function nextBillIdentity() {
  const sequence = String(nextMockBillSequence).padStart(4, '0');
  nextMockBillSequence += 1;
  return { billNo: `SB${dateKey()}${sequence}`, sourceNo: `ADJ${dateKey()}${sequence}` };
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
    const identity = nextBillIdentity();
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
    const created: StockBillDetail = {
      stockBillId,
      billNo: identity.billNo,
      billType: payload.billType,
      sourceType: sourceTypeByBillType[payload.billType],
      sourceId: null,
      sourceNo: isAdjustment ? identity.sourceNo : sourceNo,
      entryMode: isAdjustment ? 'MANUAL_ADJUSTMENT' : 'MANUAL_SUPPLEMENT',
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      status: 'DRAFT',
      itemCount: items.length,
      confirmedById: null,
      confirmedByName: '',
      confirmedAt: null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      responsibleById: '1900000000000000001',
      responsibleByName: '系统管理员',
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
    validateDraftItems(payload.items, current.billType, new Map(current.items.map(item => [item.productId, item.quantityPrecision])));
    const sourceGenerated = current.entryMode === 'SOURCE_GENERATED';
    if (sourceGenerated && (payload.items.length !== current.items.length
      || payload.items.some(item => !item.stockBillItemId || !current.items.some(existing => existing.stockBillItemId === item.stockBillItemId && existing.productId === item.productId)))) {
      throw new Error('业务单据生成的草稿不能增删或更换产品');
    }
    const { products } = await loadMockMasterData();
    const timestamp = nowText();
    const items = payload.items.map((item, index) => {
      const existing = current.items.find(candidate => candidate.stockBillItemId === item.stockBillItemId);
      const product = products.find(option => option.productId === item.productId);
      if (!product && !existing) throw new Error('产品不存在或已停用');
      const snapshot = sourceGenerated ? existing : product || existing;
      if (!snapshot) throw new Error('产品不存在');
      const currentStock = getMockWarehouseStock(current.warehouseId, item.productId);
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
    const updated = { ...current, sourceNo, manualReason, items, itemCount: items.length, remark: payload.remark.trim(), updateTime: timestamp };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? updated : item);
    return normalizeStockBillDetail(updated);
  }
  const response = await http.put(`/warehouse/stock-bills/${stockBillId}`, payload);
  return normalizeStockBillDetail(response.data.data as StockBillDetail);
}

export async function confirmStockBill(stockBillId: string) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.stockBillId === stockBillId);
    if (!existing) throw new Error('出入库流水不存在');
    if (existing.status === 'CONFIRMED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CANCELLED') throw new Error('已取消的出入库流水不能确认');
    const current = existing;
    validateDraftItems(current.items, current.billType);
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
      return { ...item, beforeQty: result.beforeQty, changeQty: direction * item.quantity, afterQty: result.afterQty, updateTime: timestamp };
    });
    const confirmed: StockBillDetail = {
      ...current,
      status: 'CONFIRMED',
      confirmedById: '1900000000000000001',
      confirmedByName: '系统管理员',
      confirmedAt: timestamp,
      updateTime: timestamp,
      items,
    };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? confirmed : item);
    return normalizeStockBillDetail(confirmed);
  }
  return postResult<StockBillDetail, Record<string, never>>(`/warehouse/stock-bills/${stockBillId}/confirm`, {}).then(normalizeStockBillDetail);
}

export async function cancelStockBill(stockBillId: string) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.stockBillId === stockBillId);
    if (!existing) throw new Error('出入库流水不存在');
    if (existing.status === 'CANCELLED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CONFIRMED') throw new Error('已确认凭证不能直接取消，请创建反向库存调整');
    const current = existing;
    const timestamp = nowText();
    const cancelled: StockBillDetail = { ...current, status: 'CANCELLED', updateTime: timestamp };
    mockBills = mockBills.map(item => item.stockBillId === stockBillId ? cancelled : item);
    return normalizeStockBillDetail(cancelled);
  }
  return postResult<StockBillDetail, Record<string, never>>(`/warehouse/stock-bills/${stockBillId}/cancel`, {}).then(normalizeStockBillDetail);
}

export function listStockBills(params: StockBillQuery) {
  if (useMockApi) return Promise.resolve(filterMockBills(params));
  const { billNo, sourceNo, warehouseId, billType, entryMode, status, ...rest } = params;
  return getResult<StockBillPage>('/warehouse/stock-bills', {
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
    return detail ? Promise.resolve(normalizeStockBillDetail(detail)) : Promise.reject(new Error('出入库流水不存在'));
  }
  return getResult<StockBillDetail>(`/warehouse/stock-bills/${stockBillId}`).then(normalizeStockBillDetail);
}
