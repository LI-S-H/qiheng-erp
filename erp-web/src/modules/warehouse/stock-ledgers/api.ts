import { getResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { assertQuantityPrecision } from '@/shared/utils/qty';
import { getStockLedgerSourceType } from './types';
import type {
  StockLedgerDetail,
  StockLedgerBillType,
  StockLedgerEntryMode,
  StockLedgerItem,
  StockLedgerListItem,
  StockLedgerPage,
  StockLedgerQuery,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const billTypes = new Set<StockLedgerBillType>([
  'PURCHASE_IN', 'SALES_OUT', 'PURCHASE_RETURN', 'SALES_RETURN', 'ADJUST_IN', 'ADJUST_OUT',
]);
const entryModes = new Set<StockLedgerEntryMode>([
  'SOURCE_GENERATED', 'MANUAL_SUPPLEMENT', 'MANUAL_ADJUSTMENT',
]);

const stockLedgerSeed: StockLedgerDetail[] = [
  { stockLedgerId: '1950000000000000001', billNo: 'SL202607180001', billType: 'PURCHASE_IN', entryMode: 'SOURCE_GENERATED', sourceType: 'PURCHASE_ORDER', sourceId: '1949000000000000001', sourceNo: 'PO202607180001', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', itemCount: 2, confirmedById: '1900000000000000001', confirmedByName: '管理员', confirmedAt: '2026-07-18 10:20:00', createTime: '2026-07-18 10:20:00', items: [{ stockLedgerItemId: '1950100000000000001', stockLedgerId: '1950000000000000001', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 0, beforeQty: 86, qualifiedQty: 20, defectiveQty: 0, changeQty: 20, afterQty: 106, remark: '' }] },
  { stockLedgerId: '1950000000000000002', billNo: 'SL202607180002', billType: 'SALES_OUT', entryMode: 'SOURCE_GENERATED', sourceType: 'SALES_ORDER', sourceId: '1949000000000000002', sourceNo: 'SO202607180002', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', itemCount: 1, confirmedById: '1900000000000000002', confirmedByName: '仓库员', confirmedAt: '2026-07-18 11:05:00', createTime: '2026-07-18 11:05:00', items: [{ stockLedgerItemId: '1950100000000000002', stockLedgerId: '1950000000000000002', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', unitName: '盒', quantityPrecision: 0, beforeQty: 27, qualifiedQty: 0, defectiveQty: 0, changeQty: -8, afterQty: 19, remark: '' }] },
  { stockLedgerId: '1950000000000000003', billNo: 'SL202607170003', billType: 'ADJUST_IN', entryMode: 'MANUAL_ADJUSTMENT', sourceType: 'STOCK_ADJUST', sourceId: null, sourceNo: 'ADJ202607170003', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', itemCount: 1, confirmedById: '1900000000000000001', confirmedByName: '管理员', confirmedAt: '2026-07-17 16:30:00', createTime: '2026-07-17 16:30:00', items: [{ stockLedgerItemId: '1950100000000000003', stockLedgerId: '1950000000000000003', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', unitName: '箱', quantityPrecision: 0, beforeQty: 10, qualifiedQty: 0, defectiveQty: 0, changeQty: 3, afterQty: 13, remark: '盘点补差' }] },
  { stockLedgerId: '1950000000000000004', billNo: 'SL202607160004', billType: 'PURCHASE_RETURN', entryMode: 'SOURCE_GENERATED', sourceType: 'PURCHASE_RETURN_ORDER', sourceId: '1949000000000000004', sourceNo: 'PR202607160004', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', itemCount: 1, confirmedById: '1900000000000000002', confirmedByName: '仓库员', confirmedAt: '2026-07-16 14:15:00', createTime: '2026-07-16 14:15:00', items: [{ stockLedgerItemId: '1950100000000000004', stockLedgerId: '1950000000000000004', productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', unitName: '卷', quantityPrecision: 0, beforeQty: 42, qualifiedQty: 0, defectiveQty: 0, changeQty: -2, afterQty: 40, remark: '' }] },
  { stockLedgerId: '1950000000000000005', billNo: 'SL202607150005', billType: 'SALES_RETURN', entryMode: 'SOURCE_GENERATED', sourceType: 'SALES_RETURN_ORDER', sourceId: '1949000000000000005', sourceNo: 'SR202607150005', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', itemCount: 1, confirmedById: '1900000000000000001', confirmedByName: '管理员', confirmedAt: '2026-07-15 09:45:00', createTime: '2026-07-15 09:45:00', items: [{ stockLedgerItemId: '1950100000000000005', stockLedgerId: '1950000000000000005', productId: '1920000000000000008', productCode: 'P000008', productName: '海盐苏打饼干', unitName: '箱', quantityPrecision: 0, beforeQty: 20, qualifiedQty: 4, defectiveQty: 0, changeQty: 4, afterQty: 24, remark: '' }] },
  { stockLedgerId: '1950000000000000006', billNo: 'SL202607140006', billType: 'ADJUST_OUT', entryMode: 'MANUAL_ADJUSTMENT', sourceType: 'STOCK_ADJUST', sourceId: null, sourceNo: 'ADJ202607140006', warehouseId: '1930000000000000004', warehouseName: '西南中心仓', itemCount: 2, confirmedById: '1900000000000000002', confirmedByName: '仓库员', confirmedAt: '2026-07-14 17:20:00', createTime: '2026-07-14 17:20:00', items: [{ stockLedgerItemId: '1950100000000000006', stockLedgerId: '1950000000000000006', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', unitName: '卷', quantityPrecision: 0, beforeQty: 25, qualifiedQty: 0, defectiveQty: 0, changeQty: -5, afterQty: 20, remark: '盘点调整' }] },
];

function normalizeEnum<T extends string>(value: unknown, values: Set<T>, fieldName: string): T {
  if (typeof value !== 'string' || !values.has(value as T)) {
    throw new Error(`接口字段 ${fieldName} 的枚举值非法`);
  }
  return value as T;
}

function normalizeStockLedgerItem(item: StockLedgerItem): StockLedgerItem {
  return {
    stockLedgerItemId: normalizeStringId(item.stockLedgerItemId, 'stockLedgerItemId'),
    stockLedgerId: normalizeStringId(item.stockLedgerId, 'stockLedgerId'),
    productId: normalizeStringId(item.productId, 'productId'),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    quantityPrecision: assertQuantityPrecision(item.quantityPrecision),
    beforeQty: normalizeFiniteNumber(item.beforeQty, 'beforeQty'),
    qualifiedQty: normalizeFiniteNumber(item.qualifiedQty, 'qualifiedQty'),
    defectiveQty: normalizeFiniteNumber(item.defectiveQty, 'defectiveQty'),
    changeQty: normalizeFiniteNumber(item.changeQty, 'changeQty'),
    afterQty: normalizeFiniteNumber(item.afterQty, 'afterQty'),
    remark: String(item.remark ?? ''),
  };
}

function normalizeStockLedgerListItem(item: StockLedgerListItem): StockLedgerListItem {
  const billType = normalizeEnum(item.billType, billTypes, 'billType');

  return {
    stockLedgerId: normalizeStringId(item.stockLedgerId, 'stockLedgerId'),
    billNo: String(item.billNo),
    billType,
    entryMode: normalizeEnum(item.entryMode, entryModes, 'entryMode'),
    sourceType: getStockLedgerSourceType(billType),
    sourceId: normalizeNullableStringId(item.sourceId, 'sourceId'),
    sourceNo: String(item.sourceNo),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    warehouseName: String(item.warehouseName),
    itemCount: normalizeFiniteNumber(item.itemCount, 'itemCount'),
    confirmedById: normalizeNullableStringId(item.confirmedById, 'confirmedById'),
    confirmedByName: String(item.confirmedByName ?? ''),
    confirmedAt: String(item.confirmedAt),
    createTime: String(item.createTime),
  };
}

function normalizeStockLedgerDetail(item: StockLedgerDetail): StockLedgerDetail {
  return {
    ...normalizeStockLedgerListItem(item),
    items: Array.isArray(item.items) ? item.items.map(normalizeStockLedgerItem) : [],
  };
}

function normalizeStockLedgerPage(page: StockLedgerPage): StockLedgerPage {
  if (!Array.isArray(page.records)) throw new Error('接口字段 records 必须为数组');
  return {
    records: page.records.map(normalizeStockLedgerListItem),
    total: normalizeFiniteNumber(page.total, 'total'),
    pageNum: normalizeFiniteNumber(page.pageNum, 'pageNum'),
    pageSize: normalizeFiniteNumber(page.pageSize, 'pageSize'),
  };
}

function matchesLedger(item: StockLedgerListItem, params: StockLedgerQuery) {
  const billNo = params.billNo?.trim().toLocaleLowerCase();
  const sourceNo = params.sourceNo?.trim().toLocaleLowerCase();
  return (!billNo || item.billNo.toLocaleLowerCase().includes(billNo))
    && (!sourceNo || item.sourceNo.toLocaleLowerCase().includes(sourceNo))
    && (!params.sourceType || params.sourceType === 'all' || getStockLedgerSourceType(item.billType) === params.sourceType)
    && (!params.warehouseId || params.warehouseId === 'all' || item.warehouseId === params.warehouseId)
    && (!params.billType || params.billType === 'all' || item.billType === params.billType)
    && (!params.entryMode || params.entryMode === 'all' || item.entryMode === params.entryMode);
}

function filterMockLedgers(params: StockLedgerQuery): StockLedgerPage {
  const records = stockLedgerSeed.filter(item => matchesLedger(item, params));
  const start = (params.pageNum - 1) * params.pageSize;
  return normalizeStockLedgerPage({
    records: records.slice(start, start + params.pageSize).map(({ items, ...item }) => item),
    total: records.length,
    pageNum: params.pageNum,
    pageSize: params.pageSize,
  });
}

/** 查询已确认库存事实；该资源没有新建、编辑、提交、确认或取消操作。 */
export function listStockLedgers(params: StockLedgerQuery) {
  if (useMockApi) return Promise.resolve(filterMockLedgers(params));
  const { billNo, sourceNo, sourceType, warehouseId, billType, entryMode, ...rest } = params;
  return getResult<StockLedgerPage>('/warehouse/stock-bills', {
    ...rest,
    ...(billNo?.trim() ? { billNo: billNo.trim() } : {}),
    ...(sourceNo?.trim() ? { sourceNo: sourceNo.trim() } : {}),
    ...(sourceType && sourceType !== 'all' ? { sourceType } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(billType && billType !== 'all' ? { billType } : {}),
    ...(entryMode && entryMode !== 'all' ? { entryMode } : {}),
  }).then(normalizeStockLedgerPage);
}

/** 获取单条只读库存流水及全部产品变动明细；仅在用户展开该流水时调用。 */
export function getStockLedgerDetail(stockLedgerId: string) {
  if (useMockApi) {
    const item = stockLedgerSeed.find(candidate => candidate.stockLedgerId === stockLedgerId);
    return item ? Promise.resolve(normalizeStockLedgerDetail(item)) : Promise.reject(new Error('库存流水不存在'));
  }
  return getResult<StockLedgerDetail>(`/warehouse/stock-bills/${stockLedgerId}`).then(normalizeStockLedgerDetail);
}
