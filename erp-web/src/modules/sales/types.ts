import type { PageResult } from '@/shared/types/api';

export type CustomerStatus = 0 | 1;
export type SalesOrderStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PARTIAL_OUTBOUND' | 'OUTBOUND_DONE' | 'CANCELLED';

export interface CustomerListItem {
  customerId: string;
  customerCode: string;
  customerName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  creditLimit: number;
  status: CustomerStatus;
  remark: string;
  createTime: string;
  updateTime: string;
}

export interface CustomerQuery {
  customerCode?: string;
  customerName?: string;
  contactName?: string;
  status?: CustomerStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface CustomerFormPayload {
  customerName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  creditLimit: number;
  status: CustomerStatus;
  remark: string;
}

export interface CustomerBatchIdsPayload {
  customerIds: string[];
}

export interface CustomerBatchStatusPayload extends CustomerBatchIdsPayload {
  status: CustomerStatus;
}

export interface CustomerOption {
  customerId: string;
  customerCode: string;
  customerName: string;
  status: CustomerStatus;
}

export interface SalesOrderItem {
  salesOrderItemId: string;
  salesOrderId: string;
  salesNo: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantity: number;
  lockedQty: number;
  outboundQty: number;
  unitPrice: number;
  totalAmount: number;
  remark: string;
}

export interface SalesOrderListItem {
  salesOrderId: string;
  salesNo: string;
  customerId: string;
  customerCode: string;
  customerName: string;
  warehouseId: string;
  warehouseName: string;
  status: SalesOrderStatus;
  totalAmount: number;
  expectedDeliveryDate: string | null;
  lockedAt: string | null;
  createdById: string | null;
  createdByName: string;
  submittedAt: string | null;
  approvedById: string | null;
  approvedByName: string;
  approvedAt: string | null;
  createTime: string;
  updateTime: string;
  remark: string;
  items: SalesOrderItem[];
}

export interface SalesOrderSummary {
  draftCount: number;
  submittedCount: number;
  approvedCount: number;
  outboundPendingCount: number;
}

export interface SalesOrderPage extends PageResult<SalesOrderListItem> {
  summary: SalesOrderSummary;
}

export interface SalesOrderQuery {
  salesNo?: string;
  customerId?: string | 'all';
  warehouseId?: string | 'all';
  status?: SalesOrderStatus | 'all';
  pageNum: number;
  pageSize: number;
}

export interface SalesOrderDraftItemPayload {
  salesOrderItemId?: string | null;
  productId: string;
  quantity: number;
  unitPrice: number;
  remark: string;
}

export interface SalesOrderFormPayload {
  customerId: string;
  warehouseId: string;
  expectedDeliveryDate?: string | null;
  remark: string;
  items: SalesOrderDraftItemPayload[];
}
