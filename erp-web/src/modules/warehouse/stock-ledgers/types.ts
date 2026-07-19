import type { PageResult } from '@/shared/types/api';

export type StockLedgerBillType =
  | 'PURCHASE_IN'
  | 'SALES_OUT'
  | 'PURCHASE_RETURN'
  | 'SALES_RETURN'
  | 'ADJUST_IN'
  | 'ADJUST_OUT';

export type StockLedgerSourceType =
  | 'PURCHASE_ORDER'
  | 'SALES_ORDER'
  | 'PURCHASE_RETURN_ORDER'
  | 'SALES_RETURN_ORDER'
  | 'STOCK_ADJUST';

export type StockLedgerEntryMode = 'SOURCE_GENERATED' | 'MANUAL_SUPPLEMENT' | 'MANUAL_ADJUSTMENT';

export interface StockLedgerItem {
  stockLedgerItemId: string;
  stockLedgerId: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  beforeQty: number;
  changeQty: number;
  afterQty: number;
  remark: string;
}

/** 已确认并实际改变库存的只读流水凭证。 */
export interface StockLedgerListItem {
  stockLedgerId: string;
  billNo: string;
  billType: StockLedgerBillType;
  entryMode: StockLedgerEntryMode;
  sourceType: StockLedgerSourceType;
  sourceId: string | null;
  sourceNo: string;
  warehouseId: string;
  warehouseName: string;
  itemCount: number;
  confirmedById: string | null;
  confirmedByName: string;
  confirmedAt: string;
  createTime: string;
}

export interface StockLedgerDetail extends StockLedgerListItem {
  items: StockLedgerItem[];
}

export interface StockLedgerQuery {
  billNo?: string;
  sourceNo?: string;
  warehouseId?: string | 'all';
  billType?: StockLedgerBillType | 'all';
  entryMode?: StockLedgerEntryMode | 'all';
  pageNum: number;
  pageSize: number;
}

export type StockLedgerPage = PageResult<StockLedgerListItem>;
