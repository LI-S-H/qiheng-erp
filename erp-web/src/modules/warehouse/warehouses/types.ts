export type WarehouseStatus = 0 | 1;

export interface WarehouseListItem {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  status: WarehouseStatus;
  remark: string;
  createdAt: string;
  updatedAt: string;
}

export interface WarehouseQuery {
  warehouseCode?: string;
  warehouseName?: string;
  contactName?: string;
  contactPhone?: string;
  status?: WarehouseStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface WarehouseCreatePayload {
  warehouseCode: string;
  warehouseName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  status: WarehouseStatus;
  remark: string;
}

export type WarehouseUpdatePayload = Omit<WarehouseCreatePayload, 'warehouseCode'>;

export interface WarehouseBatchIdsPayload {
  warehouseIds: string[];
}

export interface WarehouseBatchStatusPayload extends WarehouseBatchIdsPayload {
  status: WarehouseStatus;
}
