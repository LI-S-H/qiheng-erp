import type { PageResult } from '@/shared/types/api';

export type StockBillType = 'PURCHASE_IN' | 'SALES_OUT' | 'PURCHASE_RETURN' | 'SALES_RETURN' | 'ADJUST_IN' | 'ADJUST_OUT';
export type StockBillSourceType = 'PURCHASE_ORDER' | 'SALES_ORDER' | 'PURCHASE_RETURN_ORDER' | 'SALES_RETURN_ORDER' | 'STOCK_ADJUST';
export type StockBillDirection = 'INBOUND' | 'OUTBOUND';
export type StockBillStatus = 'DRAFT' | 'PENDING_CONFIRM' | 'CONFIRMED' | 'CANCELLED';
export type StockBillEntryMode = 'SOURCE_GENERATED' | 'MANUAL_SUPPLEMENT' | 'MANUAL_ADJUSTMENT';
export type ManualStockBillType = StockBillType;

export interface StockBillListItem {
  workBillId: string;
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceId: string | null;
  sourceNo: string;
  sourcePartyId: string | null;
  sourcePartyName: string;
  entryMode: StockBillEntryMode;
  warehouseId: string;
  warehouseName: string;
  status: StockBillStatus;
  itemCount: number;
  quantitySummary: string;
  totalCurrentQty: number | null;
  quantityUnitName: string;
  confirmedById: string | null;
  confirmedByName: string;
  confirmedAt: string | null;
  createdById: string | null;
  createdByName: string;
  responsibleById: string;
  responsibleByName: string;
  version: number;
  createTime: string;
  updateTime: string;
}

export interface StockBillItem {
  workBillItemId: string;
  workBillId: string;
  billNo: string;
  sourceItemId: string | null;
  stockBillItemId: string | null;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  planQty: number | null;
  processedQty: number | null;
  pendingQty: number | null;
  currentQty: number;
  qualifiedQty: number;
  defectiveQty: number;
  beforeQty: number;
  changeQty: number;
  afterQty: number;
  createTime: string;
  updateTime: string;
  remark: string;
}

export interface StockBillDetail extends StockBillListItem {
  manualReason: string;
  remark: string;
  items: StockBillItem[];
}

export interface StockBillSummary {
  sourceGeneratedCount: number;
  pendingCount: number;
  confirmedCount: number;
  cancelledCount: number;
}

export interface StockBillPage extends PageResult<StockBillListItem> {
  summary: StockBillSummary;
}

export interface StockBillQuery {
  direction?: StockBillDirection | 'all';
  billNo?: string;
  sourceNo?: string;
  warehouseId?: string | 'all';
  billType?: StockBillType | 'all';
  entryMode?: StockBillEntryMode | 'all';
  status?: StockBillStatus | 'all';
  pageNum: number;
  pageSize: number;
}

export interface StockBillDraftItemPayload {
  workBillItemId?: string;
  sourceItemId?: string;
  productId: string;
  currentQty: number;
  qualifiedQty: number;
  defectiveQty: number;
  remark?: string;
}

export interface StockBillCreatePayload {
  billType: ManualStockBillType;
  sourceNo: string;
  sourceId?: string;
  warehouseId: string;
  sourcePartyId?: string;
  sourcePartyName?: string;
  manualReason: string;
  items: StockBillDraftItemPayload[];
  remark?: string;
}

export interface StockBillUpdatePayload {
  version: number;
  warehouseId?: string;
  sourceNo?: string;
  manualReason?: string;
  items: StockBillDraftItemPayload[];
  remark?: string;
}

/**
 * 入库/出库页面的工作单模型。现有 StockBill* 名称保留为页面兼容名称，
 * 接口契约中应将它与确认后只读的库存流水模型分开。
 */
export type WarehouseWorkBillListItem = StockBillListItem;
export type WarehouseWorkBillItem = Omit<StockBillItem, 'beforeQty' | 'changeQty' | 'afterQty'>;
export type WarehouseWorkBillDetail = Omit<StockBillDetail, 'items'> & { items: WarehouseWorkBillItem[] };

/** 确认工作单后生成的只读库存流水，不用于新建或编辑工作单。 */
