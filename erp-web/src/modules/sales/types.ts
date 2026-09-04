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
  version: number;
  remark: string;
  createTime: string;
  updateTime: string;
  /** 最近一次创建、编辑或启停操作的维护人快照；历史数据可为空。 */
  updatedById: string | null;
  updatedByName: string | null;
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
  version?: number;
  remark: string;
}

export interface CustomerBatchIdsPayload {
  customerIds: string[];
  versionByCustomerId: Record<string, number>;
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
  quantityPrecision: number;
  quantity: number;
  lockedQty: number;
  outboundQty: number;
  unitPrice: number;
  totalAmount: number;
  remark: string;
}

export type SalesOrderReturnCoverage = 'NONE' | 'PARTIAL' | 'FULL';

export interface SalesOrderReturnItemOverview {
  salesOrderItemId: string;
  orderedQty: number;
  fulfilledQty: number;
  approvedReturnQty: number;
  approvedReturnAmount: number;
}

export interface SalesOrderReturnOverview {
  hasReturnOrder: boolean;
  coverage: SalesOrderReturnCoverage;
  approvedReturnAmount: number;
  returnOrderCount: number;
  effectiveReturnOrderCount: number;
  /** 仅订单详情返回，用于核对每个来源明细的退货覆盖情况。 */
  items?: SalesOrderReturnItemOverview[];
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
  /** 提交操作人快照；销售后端实现前由 Mock 契约提供。 */
  submittedById: string | null;
  submittedByName: string | null;
  approvedById: string | null;
  approvedByName: string;
  approvedAt: string | null;
  createTime: string;
  updateTime: string;
  version: number;
  /** 只读退货汇总；列表不返回明细覆盖项。 */
  returnOverview?: SalesOrderReturnOverview;
  remark: string;
}

export interface SalesOrderDetail extends SalesOrderListItem {
  items: SalesOrderItem[];
}

export interface SalesOrderSummary {
  draftCount: number;
  submittedCount: number;
  approvedCount: number;
  outboundPendingCount: number;
}

export type SalesOrderPage = PageResult<SalesOrderListItem>;

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
  version?: number;
  customerId: string;
  warehouseId: string;
  expectedDeliveryDate?: string | null;
  remark: string;
  items: SalesOrderDraftItemPayload[];
}
