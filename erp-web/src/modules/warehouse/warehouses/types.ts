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
  createTime: string;
  updateTime: string;
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

export interface WarehouseFormPayload {
  warehouseName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  status: WarehouseStatus;
  remark: string;
}

export type WarehouseCreatePayload = WarehouseFormPayload;
export type WarehouseUpdatePayload = WarehouseFormPayload;

export interface WarehouseBatchIdsPayload {
  warehouseIds: string[];
}

export interface WarehouseBatchStatusPayload extends WarehouseBatchIdsPayload {
  status: WarehouseStatus;
}
