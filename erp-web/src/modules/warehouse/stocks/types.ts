import type { PageResult } from '@/shared/types/api';

export type WarehouseStockState = 'AVAILABLE' | 'LOCKED' | 'LOW_STOCK' | 'OUT_OF_STOCK';

export interface WarehouseStockListItem {
  stockId: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  stockQty: number;
  lockedQty: number;
  availableQty: number;
  safetyStockQty: number;
  updatedAt: string;
}

export interface WarehouseStockSummary {
  stockRecordCount: number;
  warehouseCount: number;
  productCount: number;
  lowStockCount: number;
}

export interface WarehouseStockPage extends PageResult<WarehouseStockListItem> {
  summary: WarehouseStockSummary;
}

export interface WarehouseStockQuery {
  warehouseId?: string | 'all';
  productCode?: string;
  productName?: string;
  stockState?: WarehouseStockState | 'all';
  pageNum: number;
  pageSize: number;
}
