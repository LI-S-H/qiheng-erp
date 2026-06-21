import type { PageResult } from '@/shared/types/api';

export type InventoryHealth = 'NORMAL' | 'LOW_STOCK' | 'NO_AVAILABLE' | 'OUT_OF_STOCK';
export type ReservationState = 'UNLOCKED' | 'PARTIALLY_LOCKED' | 'FULLY_LOCKED';

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
  updateTime: string;
}

export interface WarehouseStockSummary {
  warehouseCount: number;
  productCount: number;
  lowStockCount: number;
  noAvailableCount: number;
  lockedCount: number;
}

export interface WarehouseStockPage extends PageResult<WarehouseStockListItem> {
  summary: WarehouseStockSummary;
}

export interface WarehouseStockQuery {
  warehouseId?: string | 'all';
  productCode?: string;
  productName?: string;
  inventoryHealth?: InventoryHealth | 'all';
  reservationState?: ReservationState | 'all';
  pageNum: number;
  pageSize: number;
}
