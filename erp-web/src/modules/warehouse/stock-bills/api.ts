import { getResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  StockBillDetail,
  StockBillItem,
  StockBillListItem,
  StockBillPage,
  StockBillQuery,
  StockBillSourceType,
  StockBillStatus,
  StockBillSummary,
  StockBillType,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

const billTypes: StockBillType[] = ['PURCHASE_IN', 'SALES_OUT', 'PURCHASE_RETURN', 'SALES_RETURN', 'ADJUST_IN', 'ADJUST_OUT'];
const sourceTypes: StockBillSourceType[] = ['PURCHASE_ORDER', 'SALES_ORDER', 'PURCHASE_RETURN_ORDER', 'SALES_RETURN_ORDER', 'STOCK_ADJUST'];
const billStatuses: StockBillStatus[] = ['DRAFT', 'CONFIRMED', 'CANCELLED'];
const inboundTypes = new Set<StockBillType>(['PURCHASE_IN', 'SALES_RETURN', 'ADJUST_IN']);

interface MockBillSeed {
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceNo: string;
  warehouseIndex: number;
  warehouseName: string;
  status: StockBillStatus;
  createdAt: string;
  items: Array<[string, string, string, number, number, number]>;
}

const billSeed: MockBillSeed[] = [
  { billNo: 'SB202606140001', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606001', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createdAt: '2026-06-14 09:12:00', items: [['P0001', '经典原味苏打水', '箱', 30, 12, 42], ['P0002', '速溶黑咖啡', '盒', 12, 5, 17]] },
  { billNo: 'SB202606140002', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606001', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CONFIRMED', createdAt: '2026-06-14 10:05:00', items: [['P0001', '经典原味苏打水', '箱', 8, 94, 86]] },
  { billNo: 'SB202606140003', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606001', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'DRAFT', createdAt: '2026-06-14 10:30:00', items: [['P0007', 'A4复印纸', '箱', 3, 13, 13]] },
  { billNo: 'SB202606140004', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606001', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'CONFIRMED', createdAt: '2026-06-14 11:15:00', items: [['P0008', '热敏标签纸', '卷', 10, 0, 10]] },
  { billNo: 'SB202606130005', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606001', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'CANCELLED', createdAt: '2026-06-13 16:42:00', items: [['P0003', '每日坚果混合装', '盒', 2, 31, 31]] },
  { billNo: 'SB202606130006', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606002', warehouseIndex: 3, warehouseName: '华北中心仓', status: 'CONFIRMED', createdAt: '2026-06-13 15:28:00', items: [['P0003', '每日坚果混合装', '盒', 20, 11, 31], ['P0009', '浓缩洗衣液', '瓶', 9, 0, 9]] },
  { billNo: 'SB202606130007', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606002', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createdAt: '2026-06-13 14:50:00', items: [['P0010', '厨房清洁湿巾', '包', 12, 60, 48]] },
  { billNo: 'SB202606130008', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606002', warehouseIndex: 4, warehouseName: '西南中心仓', status: 'CONFIRMED', createdAt: '2026-06-13 13:20:00', items: [['P0011', '加厚垃圾袋', '卷', 3, 28, 25]] },
  { billNo: 'SB202606120009', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606003', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'DRAFT', createdAt: '2026-06-12 17:36:00', items: [['P0012', '无痕粘钩', '包', 20, 11, 11]] },
  { billNo: 'SB202606120010', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606002', warehouseIndex: 5, warehouseName: '武汉中转仓', status: 'CONFIRMED', createdAt: '2026-06-12 16:18:00', items: [['P0004', '海盐苏打饼干', '箱', 4, 15, 19]] },
  { billNo: 'SB202606120011', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606002', warehouseIndex: 6, warehouseName: '西安中转仓', status: 'CONFIRMED', createdAt: '2026-06-12 14:45:00', items: [['P0006', '彩色便利贴', '本', 7, 70, 63]] },
  { billNo: 'SB202606110012', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606003', warehouseIndex: 7, warehouseName: '杭州电商仓', status: 'CONFIRMED', createdAt: '2026-06-11 18:05:00', items: [['P0014', '无线办公鼠标', '个', 6, 14, 8]] },
  { billNo: 'SB202606110013', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606003', warehouseIndex: 8, warehouseName: '南京备货仓', status: 'CONFIRMED', createdAt: '2026-06-11 15:32:00', items: [['P0013', 'USB-C扩展坞', '个', 2, 15, 17]] },
  { billNo: 'SB202606100014', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606004', warehouseIndex: 1, warehouseName: '华东中心仓', status: 'CANCELLED', createdAt: '2026-06-10 11:25:00', items: [['P0005', '中性签字笔', '盒', 20, 42.5, 42.5]] },
  { billNo: 'SB202606100015', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606004', warehouseIndex: 2, warehouseName: '华南中心仓', status: 'DRAFT', createdAt: '2026-06-10 09:40:00', items: [['P0007', 'A4复印纸', '箱', 5, 13, 13]] },
];

function buildMockBills(): StockBillDetail[] {
  return billSeed.map((seed, billIndex) => {
    const stockBillId = `1950000000000000${String(billIndex + 1).padStart(3, '0')}`;
    const confirmed = seed.status === 'CONFIRMED';
    const direction = inboundTypes.has(seed.billType) ? 1 : -1;
    const timestamp = seed.createdAt;
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
        quantity,
        qualifiedQty: isQualityInbound ? quantity - defectiveQty : 0,
        defectiveQty,
        beforeQty,
        changeQty,
        afterQty,
        createdAt: timestamp,
        updatedAt: timestamp,
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
      warehouseId: `1930000000000000${String(seed.warehouseIndex).padStart(3, '0')}`,
      warehouseName: seed.warehouseName,
      status: seed.status,
      itemCount: items.length,
      confirmedById: confirmed ? '1900000000000000001' : null,
      confirmedByName: confirmed ? '系统管理员' : '',
      confirmedAt: confirmed ? timestamp : null,
      createdById: '1900000000000000001',
      createdByName: '系统管理员',
      createdAt: timestamp,
      updatedAt: timestamp,
      remark: seed.status === 'CANCELLED' ? '业务单据取消，库存未发生变化' : '',
      items,
    };
  });
}

const mockBills = buildMockBills();

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
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    warehouseName: String(item.warehouseName),
    status: normalizeEnum(item.status, billStatuses, 'status'),
    itemCount: normalizeFiniteNumber(item.itemCount, 'itemCount'),
    confirmedById: normalizeNullableStringId(item.confirmedById, 'confirmedById'),
    confirmedByName: String(item.confirmedByName),
    confirmedAt: item.confirmedAt ? String(item.confirmedAt) : null,
    createdById: normalizeNullableStringId(item.createdById, 'createdById'),
    createdByName: String(item.createdByName),
    createdAt: String(item.createdAt),
    updatedAt: String(item.updatedAt),
  };
}

function normalizeStockBillItem(item: StockBillItem): StockBillItem {
  const quantityFields = ['quantity', 'qualifiedQty', 'defectiveQty', 'beforeQty', 'changeQty', 'afterQty'] as const;
  const quantities = Object.fromEntries(quantityFields.map(field => [field, normalizeFiniteNumber(item[field], field)]));
  if (quantities.quantity < 0 || quantities.qualifiedQty < 0 || quantities.defectiveQty < 0 || quantities.beforeQty < 0 || quantities.afterQty < 0) {
    throw new Error('出入库明细数量不符合非负数约束');
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
    quantity: quantities.quantity,
    qualifiedQty: quantities.qualifiedQty,
    defectiveQty: quantities.defectiveQty,
    beforeQty: quantities.beforeQty,
    changeQty: quantities.changeQty,
    afterQty: quantities.afterQty,
    createdAt: String(item.createdAt),
    updatedAt: String(item.updatedAt),
    remark: String(item.remark),
  };
}

function normalizeStockBillDetail(detail: StockBillDetail): StockBillDetail {
  return { ...normalizeStockBill(detail), remark: String(detail.remark), items: detail.items.map(normalizeStockBillItem) };
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
    return !params.status || params.status === 'all' || item.status === params.status;
  }).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.billNo.localeCompare(a.billNo));
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

export function listStockBills(params: StockBillQuery) {
  if (useMockApi) return Promise.resolve(filterMockBills(params));
  const { billNo, sourceNo, warehouseId, billType, status, ...rest } = params;
  return getResult<StockBillPage>('/warehouse/stock-bills', {
    ...rest,
    ...(billNo?.trim() ? { billNo: billNo.trim() } : {}),
    ...(sourceNo?.trim() ? { sourceNo: sourceNo.trim() } : {}),
    ...(warehouseId && warehouseId !== 'all' ? { warehouseId } : {}),
    ...(billType && billType !== 'all' ? { billType } : {}),
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
