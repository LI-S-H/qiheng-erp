import { getResult, http, postResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { getMockProductSnapshot } from '@/modules/product/products/api';
import { getMockWarehouseSnapshot } from '../warehouses/api';
import { applyMockWarehouseStockChange, getMockWarehouseStock } from '../stocks/api';
import { requiresStockBillQualityCheck } from './types';
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

function billCollectionEndpoint(direction: StockBillDirection) {
  return direction === 'INBOUND'
    ? '/warehouse/inbound-bills'
    : '/warehouse/outbound-bills';
}

function billResourceEndpoint(direction: StockBillDirection, stockBillId: string, action?: 'submit' | 'confirm' | 'cancel') {
  const collection = direction === 'INBOUND' ? '/warehouse/inbound-bills' : '/warehouse/outbound-bills';
  return `${collection}/${stockBillId}${action ? `/${action}` : ''}`;
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
      return `${item.productCode} ${formatQty(item.currentQty, item.unitName)}`;
    });
    return {
      quantitySummary: `本次 ${items.length} 条明细：${itemSummaries.join('；')}${items.length > 2 ? ' 等' : ''}`,
      totalCurrentQty: items.reduce((sum, item) => sum + item.currentQty, 0),
      quantityUnitName: '',
    };
  }
  const totalCurrentQty = items.reduce((sum, item) => sum + item.currentQty, 0);
  return {
    quantitySummary: `本次 ${formatQty(totalCurrentQty, singleUnit)}`,
    totalCurrentQty,
    quantityUnitName: singleUnit,
  };
}

interface MockBillItemSeed {
  itemId: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  planQty: number | null;
  processedQty: number | null;
  pendingQty: number | null;
  currentQty: number;
  defectiveQty?: number;
  createTime?: string;
  updateTime?: string;
  remark?: string;
}

interface MockBillSeed {
  billId: string;
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceNo: string;
  sourcePartyId?: string | null;
  sourcePartyName: string;
  warehouseId: string;
  warehouseName: string;
  status: StockBillStatus;
  entryMode?: StockBillEntryMode;
  createTime: string;
  updateTime?: string;
  confirmedAt?: string;
  manualReason?: string;
  remark?: string;
  items: MockBillItemSeed[];
}

const billSeed: MockBillSeed[] = [
  { billId: '1932000000000000001', billNo: 'IB202607010001', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202607010001', sourcePartyName: '华北中心仓', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', status: 'CONFIRMED', createTime: '2026-07-01 08:45:00', updateTime: '2026-07-01 09:12:00', confirmedAt: '2026-07-01 09:12:00', manualReason: '月末盘点发现库存盘盈', remark: '盘盈16盒每日坚果混合装', items: [{ itemId: '1932100000000000001', productId: '1920000000000000007', productCode: 'P000007', productName: '每日坚果混合装', unitName: '盒', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 16, createTime: '2026-07-01 08:45:00', updateTime: '2026-07-16 18:18:51', remark: '库存盘盈调整' }] },
  { billId: '1932000000000000002', billNo: 'IB202607010002', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: '', sourcePartyId: '1910000000000000001', sourcePartyName: '华东食品供应商', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'DRAFT', entryMode: 'MANUAL_SUPPLEMENT', createTime: '2026-07-01 09:00:00', manualReason: '线下采购入库补录', items: [{ itemId: '1932100000000000002', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 6, remark: '线下采购补录' }] },
  { billId: '1933000000000000001', billNo: 'OB202607010001', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202607010002', sourcePartyName: '华东中心仓', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-07-01 09:30:00', updateTime: '2026-07-01 10:05:00', confirmedAt: '2026-07-01 10:05:00', manualReason: '月末盘点发现库存盘亏', remark: '盘亏10盒速溶黑咖啡', items: [{ itemId: '1933100000000000001', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', unitName: '盒', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 10, createTime: '2026-07-01 09:30:00', updateTime: '2026-07-16 18:18:51', remark: '库存盘亏调整' }] },
  { billId: '1933000000000000002', billNo: 'OB202607010002', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202607010003', sourcePartyId: '1930000000000000001', sourcePartyName: '华东中心仓', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'DRAFT', entryMode: 'MANUAL_ADJUSTMENT', createTime: '2026-07-01 10:15:00', manualReason: '盘点差异待复核', remark: '草稿调整出库', items: [{ itemId: '1933100000000000002', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', unitName: '盒', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 2, remark: '待复核差异' }] },
  { billId: '1950000000000000001', billNo: 'IB202606140001', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606001', sourcePartyName: '华东饮品供应链', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 09:12:00', items: [{ itemId: '1960000000000001001', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 0, planQty: 48, processedQty: 0, pendingQty: 18, currentQty: 30, defectiveQty: 1 }, { itemId: '1960000000000001002', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', unitName: '盒', quantityPrecision: 0, planQty: 20, processedQty: 0, pendingQty: 8, currentQty: 12 }] },
  { billId: '1950000000000000002', billNo: 'OB202606140002', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606001', sourcePartyName: '上海星河便利店', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'CONFIRMED', createTime: '2026-06-14 10:05:00', items: [{ itemId: '1960000000000002001', productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', unitName: '箱', quantityPrecision: 0, planQty: 8, processedQty: 0, pendingQty: 0, currentQty: 8 }] },
  { billId: '1950000000000000003', billNo: 'IB202606140003', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606001', sourcePartyName: '华南中心仓', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', status: 'DRAFT', createTime: '2026-06-14 10:30:00', items: [{ itemId: '1960000000000003001', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', unitName: '箱', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 3 }] },
  { billId: '1950000000000000004', billNo: 'IB202606140004', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606001', sourcePartyName: '广州天河门店', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', status: 'CONFIRMED', createTime: '2026-06-14 11:15:00', items: [{ itemId: '1960000000000004001', productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', unitName: '卷', quantityPrecision: 0, planQty: 10, processedQty: 0, pendingQty: 0, currentQty: 10, defectiveQty: 1 }] },
  { billId: '1950000000000000005', billNo: 'OB202606130005', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606001', sourcePartyName: '谷仓食品批发', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', status: 'CANCELLED', createTime: '2026-06-13 16:42:00', items: [{ itemId: '1960000000000005001', productId: '1920000000000000007', productCode: 'P000007', productName: '每日坚果混合装', unitName: '盒', quantityPrecision: 0, planQty: 6, processedQty: 2, pendingQty: 2, currentQty: 2 }] },
  { billId: '1950000000000000006', billNo: 'IB202606130006', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606002', sourcePartyName: '谷仓食品批发', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', status: 'PENDING_CONFIRM', createTime: '2026-06-13 15:28:00', items: [{ itemId: '1960000000000006001', productId: '1920000000000000007', productCode: 'P000007', productName: '每日坚果混合装', unitName: '盒', quantityPrecision: 0, planQty: 60, processedQty: 20, pendingQty: 40, currentQty: 20 }, { itemId: '1960000000000006002', productId: '1920000000000000033', productCode: 'P000033', productName: '浓缩洗衣液', unitName: '瓶', quantityPrecision: 0, planQty: 27, processedQty: 9, pendingQty: 18, currentQty: 9 }] },
  { billId: '1950000000000000007', billNo: 'OB202606130007', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606002', sourcePartyName: '成都青柠商贸', warehouseId: '1930000000000000004', warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 14:50:00', items: [{ itemId: '1960000000000007001', productId: '1920000000000000034', productCode: 'P000034', productName: '厨房清洁湿巾', unitName: '包', quantityPrecision: 0, planQty: 12, processedQty: 0, pendingQty: 0, currentQty: 12 }] },
  { billId: '1950000000000000008', billNo: 'OB202606130008', billType: 'ADJUST_OUT', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606002', sourcePartyName: '西南中心仓', warehouseId: '1930000000000000004', warehouseName: '西南中心仓', status: 'CONFIRMED', createTime: '2026-06-13 13:20:00', items: [{ itemId: '1960000000000008001', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', unitName: '卷', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 3 }] },
  { billId: '1950000000000000009', billNo: 'IB202606120009', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606003', sourcePartyName: '文仪办公渠道', warehouseId: '1930000000000000005', warehouseName: '武汉中转仓', status: 'PENDING_CONFIRM', createTime: '2026-06-12 17:36:00', items: [{ itemId: '1960000000000009001', productId: '1920000000000000038', productCode: 'P000038', productName: '无痕粘钩', unitName: '卡', quantityPrecision: 0, planQty: 40, processedQty: 0, pendingQty: 40, currentQty: 20 }] },
  { billId: '1950000000000000010', billNo: 'IB202606120010', billType: 'SALES_RETURN', sourceType: 'SALES_RETURN_ORDER', sourceNo: 'SRO202606002', sourcePartyName: '武汉江岸客户', warehouseId: '1930000000000000005', warehouseName: '武汉中转仓', status: 'CONFIRMED', createTime: '2026-06-12 16:18:00', items: [{ itemId: '1960000000000010001', productId: '1920000000000000008', productCode: 'P000008', productName: '海盐苏打饼干', unitName: '箱', quantityPrecision: 0, planQty: 4, processedQty: 0, pendingQty: 0, currentQty: 4, defectiveQty: 1 }] },
  { billId: '1950000000000000011', billNo: 'OB202606120011', billType: 'PURCHASE_RETURN', sourceType: 'PURCHASE_RETURN_ORDER', sourceNo: 'PRO202606002', sourcePartyName: '森纸纸业集团', warehouseId: '1930000000000000006', warehouseName: '西安中转仓', status: 'CONFIRMED', createTime: '2026-06-12 14:45:00', items: [{ itemId: '1960000000000011001', productId: '1920000000000000022', productCode: 'P000022', productName: '彩色便利贴', unitName: '本', quantityPrecision: 0, planQty: 7, processedQty: 0, pendingQty: 0, currentQty: 7 }] },
  { billId: '1950000000000000012', billNo: 'OB202606110012', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606003', sourcePartyName: '杭州电商客户', warehouseId: '1930000000000000007', warehouseName: '杭州电商仓', status: 'CONFIRMED', createTime: '2026-06-11 18:05:00', items: [{ itemId: '1960000000000012001', productId: '1920000000000000044', productCode: 'P000044', productName: '无线办公鼠标', unitName: '个', quantityPrecision: 0, planQty: 6, processedQty: 0, pendingQty: 0, currentQty: 6 }] },
  { billId: '1950000000000000013', billNo: 'IB202606110013', billType: 'ADJUST_IN', sourceType: 'STOCK_ADJUST', sourceNo: 'ADJ202606003', sourcePartyName: '南京备货仓', warehouseId: '1930000000000000008', warehouseName: '南京备货仓', status: 'CONFIRMED', createTime: '2026-06-11 15:32:00', items: [{ itemId: '1960000000000013001', productId: '1920000000000000043', productCode: 'P000043', productName: 'USB-C扩展坞', unitName: '个', quantityPrecision: 0, planQty: null, processedQty: null, pendingQty: null, currentQty: 2 }] },
  { billId: '1950000000000000014', billNo: 'IB202606100014', billType: 'PURCHASE_IN', sourceType: 'PURCHASE_ORDER', sourceNo: 'PO202606004', sourcePartyName: '文仪办公渠道', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', status: 'CANCELLED', createTime: '2026-06-10 11:25:00', items: [{ itemId: '1960000000000014001', productId: '1920000000000000021', productCode: 'P000021', productName: '中性签字笔', unitName: '盒', quantityPrecision: 0, planQty: 20, processedQty: 0, pendingQty: 0, currentQty: 20 }] },
  { billId: '1950000000000000015', billNo: 'OB202606100015', billType: 'SALES_OUT', sourceType: 'SALES_ORDER', sourceNo: 'SO202606004', sourcePartyName: '广州天河门店', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', status: 'PENDING_CONFIRM', createTime: '2026-06-10 09:40:00', items: [{ itemId: '1960000000000015001', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', unitName: '箱', quantityPrecision: 0, planQty: 10, processedQty: 0, pendingQty: 10, currentQty: 5 }] },
];

function buildMockBills(): StockBillDetail[] {
  return billSeed.map((seed) => {
    const stockBillId = seed.billId;
    const confirmed = seed.status === 'CONFIRMED';
    const direction = inboundTypes.has(seed.billType) ? 1 : -1;
    const timestamp = seed.createTime;
    const items: StockBillItem[] = seed.items.map((item) => {
      const sourceGeneratedWaiting = seed.sourceType !== 'STOCK_ADJUST' && seed.status === 'PENDING_CONFIRM';
      const currentQuantity = sourceGeneratedWaiting ? 0 : item.currentQty;
      const currentPendingQty = sourceGeneratedWaiting && item.planQty !== null && item.processedQty !== null
        ? Math.max(0, item.planQty - item.processedQty)
        : item.pendingQty;
      const requiresQualityCheck = requiresStockBillQualityCheck(seed.billType);
      const defectiveQty = requiresQualityCheck ? item.defectiveQty || 0 : 0;
      return {
        workBillItemId: item.itemId,
        workBillId: stockBillId,
        billNo: seed.billNo,
        sourceItemId: null,
        stockBillItemId: null,
        productId: item.productId,
        productCode: item.productCode,
        productName: item.productName,
        unitName: item.unitName,
        quantityPrecision: item.quantityPrecision,
        planQty: item.planQty,
        processedQty: item.processedQty,
        pendingQty: currentPendingQty,
        currentQty: currentQuantity,
        qualifiedQty: requiresQualityCheck ? currentQuantity - defectiveQty : 0,
        defectiveQty,
        createTime: item.createTime || timestamp,
        updateTime: item.updateTime || timestamp,
        remark: item.remark || (defectiveQty > 0 ? '含 1 个不合格品，已记录质检结果' : ''),
      };
    });
    const quantitySummary = buildQuantitySummary(items);
    return {
      workBillId: stockBillId,
      billNo: seed.billNo,
      billType: seed.billType,
      sourceType: seed.sourceType,
      sourceId: null,
      sourceNo: seed.sourceNo,
      sourcePartyId: seed.sourcePartyId ?? null,
      sourcePartyName: seed.sourcePartyName,
      entryMode: seed.entryMode ?? (seed.sourceType === 'STOCK_ADJUST' ? 'MANUAL_ADJUSTMENT' : 'SOURCE_GENERATED'),
      warehouseId: seed.warehouseId,
      warehouseName: seed.warehouseName,
      status: seed.status,
      itemCount: items.length,
      ...quantitySummary,
      confirmedById: confirmed ? '1900000000000000004' : null,
      confirmedByName: confirmed ? '仓管主管' : '',
      confirmedAt: confirmed ? seed.confirmedAt || timestamp : null,
      createdById: '1900000000000000004',
      createdByName: '仓管主管',
      responsibleById: '1900000000000000004',
      version: 0,
      responsibleByName: '仓管主管',
      createTime: timestamp,
      updateTime: seed.updateTime || timestamp,
      remark: seed.remark || (seed.status === 'CANCELLED' ? '业务单据取消，库存未发生变化' : ''),
      manualReason: seed.manualReason || (seed.sourceType === 'STOCK_ADJUST' ? '库存盘点调整' : ''),
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
    workBillId: normalizeStringId(item.workBillId, 'workBillId'),
    billNo: String(item.billNo),
    billType: normalizeEnum(item.billType, billTypes, 'billType'),
    sourceType: normalizeEnum(item.sourceType, sourceTypes, 'sourceType'),
    sourceId: normalizeNullableStringId(item.sourceId, 'sourceId'),
    sourceNo: String(item.sourceNo),
    sourcePartyId: normalizeNullableStringId(item.sourcePartyId, 'sourcePartyId'),
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
  const quantityFields = ['currentQty', 'qualifiedQty', 'defectiveQty'] as const;
  const quantities = Object.fromEntries(quantityFields.map(field => [field, normalizeFiniteNumber(item[field], field)]));
  if (quantities.currentQty < 0 || quantities.qualifiedQty < 0 || quantities.defectiveQty < 0) {
    throw new Error('出入库明细数量不符合非负数约束');
  }
  const quantityPrecision = normalizeFiniteNumber(item.quantityPrecision, 'quantityPrecision');
  if (!Number.isInteger(quantityPrecision) || quantityPrecision < 0 || quantityPrecision > 2) {
    throw new Error('接口字段 quantityPrecision 必须是 0 到 2 的整数');
  }
  return {
    ...item,
    workBillItemId: normalizeStringId(item.workBillItemId, 'workBillItemId'),
    workBillId: normalizeStringId(item.workBillId, 'workBillId'),
    billNo: String(item.billNo),
    sourceItemId: normalizeNullableStringId(item.sourceItemId, 'sourceItemId'),
    stockBillItemId: normalizeNullableStringId(item.stockBillItemId, 'stockBillItemId'),
    productId: normalizeStringId(item.productId, 'productId'),
    productCode: String(item.productCode),
    productName: String(item.productName),
    unitName: String(item.unitName),
    quantityPrecision,
    planQty: nullableNumber(item.planQty, 'planQty'),
    processedQty: nullableNumber(item.processedQty, 'processedQty'),
    pendingQty: nullableNumber(item.pendingQty, 'pendingQty'),
    currentQty: quantities.currentQty,
    qualifiedQty: quantities.qualifiedQty,
    defectiveQty: quantities.defectiveQty,
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
    sourceGeneratedCount: normalizeFiniteNumber(summary.sourceGeneratedCount, 'sourceGeneratedCount'),
    pendingCount: normalizeFiniteNumber(summary.pendingCount, 'pendingCount'),
    confirmedCount: normalizeFiniteNumber(summary.confirmedCount, 'confirmedCount'),
    cancelledCount: normalizeFiniteNumber(summary.cancelledCount, 'cancelledCount'),
  };
}

function buildSummary(records: StockBillListItem[]): StockBillSummary {
  return {
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
    summary: normalizeSummary(page.summary),
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
  const bill = mockBills.find(item => item.workBillId === stockBillId);
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
  const requiresQualityCheck = requiresStockBillQualityCheck(billType);
  items.forEach(item => {
    if (!item.productId) throw new Error('请选择产品');
    if (!Number.isFinite(item.currentQty) || item.currentQty <= 0) throw new Error('出入库数量必须大于 0');
    const precision = precisionByProduct?.get(item.productId) ?? 2;
    if (!matchesPrecision(item.currentQty, precision) || !matchesPrecision(item.qualifiedQty, precision) || !matchesPrecision(item.defectiveQty, precision)) {
      throw new Error(`产品数量最多保留 ${precision} 位小数`);
    }
    if (!Number.isFinite(item.qualifiedQty) || item.qualifiedQty < 0 || !Number.isFinite(item.defectiveQty) || item.defectiveQty < 0) {
      throw new Error('合格数量和不合格数量不能小于 0');
    }
    if (requiresQualityCheck && Math.abs(item.qualifiedQty + item.defectiveQty - item.currentQty) > 0.0001) {
      throw new Error('需要质检的单据，合格数量与不合格数量之和必须等于本次数量');
    }
    if (!requiresQualityCheck && (item.qualifiedQty !== 0 || item.defectiveQty !== 0)) {
      throw new Error('不涉及质检的单据，合格数量和不合格数量必须为 0');
    }
  });
}

function normalizeDraftItemQualityQty(item: StockBillDraftItemPayload, billType: StockBillType): StockBillDraftItemPayload {
  if (requiresStockBillQualityCheck(billType)) return item;
  return { ...item, qualifiedQty: 0, defectiveQty: 0 };
}

async function loadMockMasterData(productIds: string[]) {
  const warehouses = getMockWarehouseSnapshot().filter(item => item.status === 1);
  const products = [...new Set(productIds)].map((productId) => {
    const product = getMockProductSnapshot(productId);
    if (!product || product.status !== 1) throw new Error(`产品 ${productId} 不存在或已停用`);
    return product;
  });
  return { warehouses, products };
}

function nextBillIdentity(billType: StockBillType) {
  const sequence = String(nextMockBillSequence).padStart(4, '0');
  nextMockBillSequence += 1;
  return { billNo: `${billDirection(billType) === 'INBOUND' ? 'IB' : 'OB'}${dateKey()}${sequence}`, sourceNo: `ADJ${dateKey()}${sequence}` };
}

export async function createStockBill(direction: StockBillDirection, payload: StockBillCreatePayload) {
  if (billDirection(payload.billType) !== direction) {
    throw new Error('入库页面只能创建入库单，出库页面只能创建出库单');
  }
  payload = {
    ...payload,
    items: payload.items.map(item => normalizeDraftItemQualityQty(item, payload.billType)),
  };
  if (useMockApi) {
    const { warehouses, products } = await loadMockMasterData(payload.items.map(item => item.productId));
    validateDraftItems(payload.items, payload.billType, new Map(products.map(item => [item.productId, item.quantityPrecision])));
    const warehouse = warehouses.find(item => item.warehouseId === payload.warehouseId);
    if (!warehouse) throw new Error('只能选择启用状态的仓库');
    const isAdjustment = adjustmentTypes.has(payload.billType);
    const sourceNo = isAdjustment ? '' : payload.sourceNo.trim();
    const sourceId = isAdjustment ? '' : payload.sourceId?.trim() ?? '';
    const manualReason = payload.manualReason.trim();
    if (!isAdjustment && Boolean(sourceNo) !== Boolean(sourceId)) {
      throw new Error('来源单据ID和来源单号必须同时存在或同时留空');
    }
    if (sourceNo.length > 64) throw new Error('原业务单号不能超过 64 个字符');
    if (!payload.sourcePartyId.trim() || !payload.sourcePartyName.trim()) throw new Error('来源对象ID和名称不能为空');
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
        workBillItemId: `${stockBillId}${index + 1}`,
        workBillId: stockBillId,
        billNo: identity.billNo,
        sourceItemId: null,
        stockBillItemId: null,
        productId: product.productId,
        productCode: product.productCode,
        productName: product.productName,
        unitName: product.unitName,
        quantityPrecision: product.quantityPrecision,
        planQty: null,
        processedQty: null,
        pendingQty: null,
        currentQty: item.currentQty,
        qualifiedQty: requiresStockBillQualityCheck(payload.billType) ? item.qualifiedQty : 0,
        defectiveQty: requiresStockBillQualityCheck(payload.billType) ? item.defectiveQty : 0,
        createTime: timestamp,
        updateTime: timestamp,
        remark: item.remark?.trim() ?? '',
      } satisfies StockBillItem;
    });
    const quantitySummary = buildQuantitySummary(items);
    const created: StockBillDetail = {
      workBillId: stockBillId,
      billNo: identity.billNo,
      billType: payload.billType,
      sourceType: sourceTypeByBillType[payload.billType],
      sourceId: sourceId || null,
      sourceNo: isAdjustment ? identity.sourceNo : sourceNo,
      sourcePartyId: payload.sourcePartyId,
      sourcePartyName: payload.sourcePartyName.trim(),
      entryMode: isAdjustment ? 'MANUAL_ADJUSTMENT' : 'MANUAL_SUPPLEMENT',
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      status: 'DRAFT',
      itemCount: items.length,
      ...quantitySummary,
      confirmedById: null,
      confirmedByName: '',
      confirmedAt: null,
      createdById: '1900000000000000004',
      createdByName: '仓管主管',
      responsibleById: '1900000000000000004',
      responsibleByName: '仓管主管',
      version: 0,
      createTime: timestamp,
      updateTime: timestamp,
      manualReason,
      remark: payload.remark?.trim() ?? '',
      items,
    };
    mockBills = [created, ...mockBills];
    return normalizeStockBillDetail(created);
  }
  return postResult<StockBillDetail, StockBillCreatePayload>(billCollectionEndpoint(direction), payload)
    .then(normalizeStockBillDetail);
}

export async function updateStockBill(direction: StockBillDirection, stockBillId: string, payload: StockBillUpdatePayload, billType?: StockBillType) {
  if (billType) {
    payload = {
      ...payload,
      items: payload.items.map(item => normalizeDraftItemQualityQty(item, billType)),
    };
  }
  if (useMockApi) {
    const current = requireDraft(stockBillId);
    payload = {
      ...payload,
      items: payload.items.map(item => normalizeDraftItemQualityQty(item, current.billType)),
    };
    assertOptimisticVersion(current.version, payload.version);
    validateDraftItems(payload.items, current.billType, new Map(current.items.map(item => [item.productId, item.quantityPrecision])));
    const sourceGenerated = current.entryMode === 'SOURCE_GENERATED';
    const structureLocked = sourceGenerated || current.status === 'PENDING_CONFIRM';
    if (structureLocked && (payload.items.length !== current.items.length
      || !payload.items.every(item => current.items.some(existing => existing.productId === item.productId)))) {
      throw new Error('系统生成单或待确认单不能增删或更换产品');
    }
    const { warehouses, products } = await loadMockMasterData(payload.items.map(item => item.productId));
    const warehouseChanged = Boolean(payload.warehouseId && payload.warehouseId !== current.warehouseId);
    if (warehouseChanged && current.status !== 'DRAFT') throw new Error('待确认单不能修改仓库');
    const warehouse = warehouseChanged ? warehouses.find(item => item.warehouseId === payload.warehouseId) : warehouses.find(item => item.warehouseId === current.warehouseId);
    if (!warehouse) throw new Error('只能选择启用状态的仓库');
    const timestamp = nowText();
    const items = payload.items.map((item, index) => {
      const existing = current.items.find(candidate => candidate.productId === item.productId);
      const product = products.find(option => option.productId === item.productId);
      if (!product && !existing) throw new Error('产品不存在或已停用');
      const snapshot = sourceGenerated ? existing : product || existing;
      if (!snapshot) throw new Error('产品不存在');
      const currentStock = getMockWarehouseStock(warehouse.warehouseId, item.productId);
      return {
        workBillItemId: `${stockBillId}${Date.now()}${index + 1}`,
        workBillId: stockBillId,
        billNo: current.billNo,
        sourceItemId: existing?.sourceItemId || null,
        stockBillItemId: null,
        productId: item.productId,
        productCode: snapshot.productCode,
        productName: snapshot.productName,
        unitName: snapshot.unitName,
        quantityPrecision: snapshot.quantityPrecision,
        planQty: existing?.planQty ?? null,
        processedQty: existing?.processedQty ?? null,
        pendingQty: !existing || existing.planQty === null || existing.processedQty === null
          ? null
          : Math.max(0, existing.planQty - existing.processedQty - item.currentQty),
        currentQty: item.currentQty,
        qualifiedQty: requiresStockBillQualityCheck(current.billType) ? item.qualifiedQty : 0,
        defectiveQty: requiresStockBillQualityCheck(current.billType) ? item.defectiveQty : 0,
        createTime: existing?.createTime || timestamp,
        updateTime: timestamp,
        remark: item.remark?.trim() ?? '',
      } satisfies StockBillItem;
    });
    const isSupplement = current.entryMode === 'MANUAL_SUPPLEMENT';
    const supplementSourceEditable = isSupplement && current.status === 'DRAFT';
    const adjustmentSourceWarehouseEditable = current.entryMode === 'MANUAL_ADJUSTMENT' && current.status === 'DRAFT';
    const sourceId = supplementSourceEditable && payload.sourceId !== undefined
      ? (payload.sourceId.trim() || null)
      : current.sourceId;
    const sourceNo = supplementSourceEditable && payload.sourceNo !== undefined
      ? payload.sourceNo.trim()
      : current.sourceNo;
    const sourcePartyId = supplementSourceEditable || adjustmentSourceWarehouseEditable
      ? (payload.sourcePartyId ?? current.sourcePartyId)
      : current.sourcePartyId;
    const sourceWarehouse = adjustmentSourceWarehouseEditable
      ? warehouses.find(item => item.warehouseId === sourcePartyId)
      : null;
    if (adjustmentSourceWarehouseEditable && !sourceWarehouse) throw new Error('只能选择启用状态的来源仓库');
    const sourcePartyName = sourceWarehouse?.warehouseName ?? (supplementSourceEditable
      ? (payload.sourcePartyName ?? current.sourcePartyName)
      : current.sourcePartyName);
    const manualReason = sourceGenerated ? current.manualReason
      : (payload.manualReason === undefined ? current.manualReason : payload.manualReason.trim());
    if (supplementSourceEditable && Boolean(sourceId) !== Boolean(sourceNo)) {
      throw new Error('来源单据ID和来源单号必须同时存在或同时留空');
    }
    if (sourceNo.length > 64) throw new Error('原业务单号不能超过 64 个字符');
    if (!sourceGenerated && !manualReason) throw new Error(current.entryMode === 'MANUAL_ADJUSTMENT' ? '请填写调整原因' : '请填写补录原因');
    if (manualReason.length > 500) throw new Error('补录或调整原因不能超过 500 个字符');
    const updated = {
      ...current,
      warehouseId: warehouse.warehouseId,
      warehouseName: warehouse.warehouseName,
      sourcePartyId,
      sourcePartyName,
      sourceId,
      sourceNo,
      manualReason,
      items,
      itemCount: items.length,
      ...buildQuantitySummary(items),
      remark: payload.remark?.trim() ?? '',
      version: current.version + 1,
      updateTime: timestamp,
    };
    mockBills = mockBills.map(item => item.workBillId === stockBillId ? updated : item);
    return normalizeStockBillDetail(updated);
  }
  const response = await http.put(billResourceEndpoint(direction, stockBillId), payload);
  return normalizeStockBillDetail(response.data.data as StockBillDetail);
}

export async function confirmStockBill(direction: StockBillDirection, stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.workBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'CONFIRMED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CANCELLED') throw new Error('已取消的入库单或出库单不能确认');
    if (existing.status !== 'PENDING_CONFIRM') throw new Error('草稿必须先提交为待确认后才能确认入库/出库');
    const current = existing;
    validateDraftItems(current.items.map(i => ({ productId: i.productId, currentQty: i.currentQty, qualifiedQty: i.qualifiedQty, defectiveQty: i.defectiveQty, remark: i.remark })), current.billType);
    current.items.forEach(item => {
      if (current.entryMode !== 'SOURCE_GENERATED' || item.planQty === null || item.processedQty === null) return;
      const remainingBefore = Math.max(0, item.planQty - item.processedQty);
      if (item.currentQty > remainingBefore) throw new Error(`产品 ${item.productCode} 的本次数量不能超过来源剩余数量`);
    });
    const { warehouses, products } = await loadMockMasterData(existing.items.map(item => item.productId));
    const warehouse = warehouses.find(item => item.warehouseId === current.warehouseId);
    if (!warehouse) throw new Error('当前仓库已停用，不能确认出入库');
    const direction = inboundTypes.has(current.billType) ? 1 : -1;
    current.items.forEach(item => {
      const stock = getMockWarehouseStock(current.warehouseId, item.productId);
      const afterQty = (stock?.stockQty || 0) + direction * item.currentQty;
      const afterLockedQty = (stock?.lockedQty || 0) - (current.billType === 'SALES_OUT' ? item.currentQty : 0);
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
        changeQty: direction * item.currentQty,
        lockedChangeQty: current.billType === 'SALES_OUT' ? -item.currentQty : 0,
      });
      return {
        ...item,
        updateTime: timestamp,
      };
    });
    const confirmed: StockBillDetail = {
      ...current,
      status: 'CONFIRMED',
      ...buildQuantitySummary(items),
      confirmedById: '1900000000000000004',
      confirmedByName: '仓管主管',
      confirmedAt: timestamp,
      version: current.version + 1,
      updateTime: timestamp,
      items,
    };
    mockBills = mockBills.map(item => item.workBillId === stockBillId ? confirmed : item);
    return normalizeStockBillDetail(confirmed);
  }
  return postResult<StockBillDetail, { version: number }>(billResourceEndpoint(direction, stockBillId, 'confirm'), { version }).then(normalizeStockBillDetail);
}

export async function submitStockBill(direction: StockBillDirection, stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.workBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'PENDING_CONFIRM') return normalizeStockBillDetail(existing);
    if (existing.status !== 'DRAFT') throw new Error('只有草稿状态的入库单/出库单可以提交确认');
    validateDraftItems(existing.items.map(i => ({ productId: i.productId, currentQty: i.currentQty, qualifiedQty: i.qualifiedQty, defectiveQty: i.defectiveQty, remark: i.remark })), existing.billType);
    const timestamp = nowText();
    const submitted: StockBillDetail = { ...existing, status: 'PENDING_CONFIRM', version: existing.version + 1, updateTime: timestamp };
    mockBills = mockBills.map(item => item.workBillId === stockBillId ? submitted : item);
    return normalizeStockBillDetail(submitted);
  }
  return postResult<StockBillDetail, { version: number }>(billResourceEndpoint(direction, stockBillId, 'submit'), { version }).then(normalizeStockBillDetail);
}

export async function cancelStockBill(direction: StockBillDirection, stockBillId: string, version: number) {
  if (useMockApi) {
    const existing = mockBills.find(item => item.workBillId === stockBillId);
    if (!existing) throw new Error('入库单或出库单不存在');
    assertOptimisticVersion(existing.version, version);
    if (existing.status === 'CANCELLED') return normalizeStockBillDetail(existing);
    if (existing.status === 'CONFIRMED') throw new Error('已确认凭证不能直接取消，请创建反向库存调整');
    const current = existing;
    const timestamp = nowText();
    const cancelled: StockBillDetail = { ...current, status: 'CANCELLED', version: current.version + 1, updateTime: timestamp };
    mockBills = mockBills.map(item => item.workBillId === stockBillId ? cancelled : item);
    return normalizeStockBillDetail(cancelled);
  }
  return postResult<StockBillDetail, { version: number }>(billResourceEndpoint(direction, stockBillId, 'cancel'), { version }).then(normalizeStockBillDetail);
}

export function listStockBills(params: StockBillQuery) {
  if (params.direction !== 'INBOUND' && params.direction !== 'OUTBOUND') {
    throw new Error('工作单查询必须指定入库或出库方向；库存流水请使用 listStockLedgers');
  }
  if (useMockApi) return Promise.resolve(filterMockBills(params));
  const { direction, billNo, sourceNo, warehouseId, billType, entryMode, status, ...rest } = params;
  const endpoint = direction === 'INBOUND' ? '/warehouse/inbound-bills' : '/warehouse/outbound-bills';
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

export function getStockBillDetail(direction: StockBillDirection, stockBillId: string) {
  if (useMockApi) {
    const detail = mockBills.find(item => item.workBillId === stockBillId);
    return detail ? Promise.resolve(normalizeStockBillDetail(detail)) : Promise.reject(new Error('入库单或出库单不存在'));
  }
  return getResult<StockBillDetail>(billResourceEndpoint(direction, stockBillId)).then(normalizeStockBillDetail);
}
