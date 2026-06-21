import type { PageResult } from '@/shared/types/api';

export type StockBillType = 'PURCHASE_IN' | 'SALES_OUT' | 'PURCHASE_RETURN' | 'SALES_RETURN' | 'ADJUST_IN' | 'ADJUST_OUT';
export type StockBillSourceType = 'PURCHASE_ORDER' | 'SALES_ORDER' | 'PURCHASE_RETURN_ORDER' | 'SALES_RETURN_ORDER' | 'STOCK_ADJUST';
export type StockBillDirection = 'INBOUND' | 'OUTBOUND';
export type StockBillStatus = 'DRAFT' | 'PENDING_CONFIRM' | 'CONFIRMED' | 'CANCELLED';
export type StockBillEntryMode = 'SOURCE_GENERATED' | 'MANUAL_SUPPLEMENT' | 'MANUAL_ADJUSTMENT';
export type ManualStockBillType = StockBillType;

export interface StockBillListItem {
  stockBillId: string;
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceId: string | null;
  sourceNo: string;
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
  createTime: string;
  updateTime: string;
}

export interface StockBillItem {
  stockBillItemId: string;
  stockBillId: string;
  billNo: string;
  sourceItemId: string | null;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  planQty: number | null;
  processedQty: number | null;
  pendingQty: number | null;
  quantity: number;
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
  inboundCount: number;
  outboundCount: number;
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
  stockBillItemId?: string;
  productId: string;
  quantity: number;
  qualifiedQty: number;
  defectiveQty: number;
  remark: string;
}

export interface StockBillCreatePayload {
  billType: ManualStockBillType;
  sourceNo: string;
  warehouseId: string;
  manualReason: string;
  items: StockBillDraftItemPayload[];
  remark: string;
}

export interface StockBillUpdatePayload {
  warehouseId: string;
  sourceNo: string;
  manualReason: string;
  items: StockBillDraftItemPayload[];
  remark: string;
}
