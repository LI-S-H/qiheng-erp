import type { PageResult } from '@/shared/types/api';

export type StockBillType = 'PURCHASE_IN' | 'SALES_OUT' | 'PURCHASE_RETURN' | 'SALES_RETURN' | 'ADJUST_IN' | 'ADJUST_OUT';
export type StockBillSourceType = 'PURCHASE_ORDER' | 'SALES_ORDER' | 'PURCHASE_RETURN_ORDER' | 'SALES_RETURN_ORDER' | 'STOCK_ADJUST';
export type StockBillStatus = 'DRAFT' | 'CONFIRMED' | 'CANCELLED';

export interface StockBillListItem {
  stockBillId: string;
  billNo: string;
  billType: StockBillType;
  sourceType: StockBillSourceType;
  sourceId: string | null;
  sourceNo: string;
  warehouseId: string;
  warehouseName: string;
  status: StockBillStatus;
  itemCount: number;
  confirmedById: string | null;
  confirmedByName: string;
  confirmedAt: string | null;
  createdById: string | null;
  createdByName: string;
  createdAt: string;
  updatedAt: string;
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
  quantity: number;
  qualifiedQty: number;
  defectiveQty: number;
  beforeQty: number;
  changeQty: number;
  afterQty: number;
  createdAt: string;
  updatedAt: string;
  remark: string;
}

export interface StockBillDetail extends StockBillListItem {
  remark: string;
  items: StockBillItem[];
}

export interface StockBillSummary {
  stockBillCount: number;
  inboundCount: number;
  outboundCount: number;
  confirmedCount: number;
}

export interface StockBillPage extends PageResult<StockBillListItem> {
  summary: StockBillSummary;
}

export interface StockBillQuery {
  billNo?: string;
  sourceNo?: string;
  warehouseId?: string | 'all';
  billType?: StockBillType | 'all';
  status?: StockBillStatus | 'all';
  pageNum: number;
  pageSize: number;
}
