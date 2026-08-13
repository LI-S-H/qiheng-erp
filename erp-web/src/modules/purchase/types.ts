import type { PageResult } from '@/shared/types/api';

export type PurchaseStatus = 0 | 1;
export type PurchaseOrderStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PARTIAL_INBOUND' | 'INBOUND_DONE' | 'CANCELLED';

export interface SupplierListItem {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  paymentTerms: string;
  overallScore: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  serviceScore: number;
  avgDeliveryDays: number;
  onTimeRate: number;
  qualifiedRate: number;
  status: PurchaseStatus;
  version: number;
  remark: string;
  createTime: string;
  updateTime: string;
  updatedById?: string | null;
  updatedByName?: string | null;
}

export interface SupplierQuery {
  supplierCode?: string;
  supplierName?: string;
  contactName?: string;
  status?: PurchaseStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface SupplierFormPayload {
  supplierName: string;
  contactName: string;
  contactPhone: string;
  address: string;
  paymentTerms: string;
  overallScore: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  serviceScore: number;
  avgDeliveryDays: number;
  onTimeRate: number;
  qualifiedRate: number;
  status: PurchaseStatus;
  version?: number;
  remark: string;
}

export interface SupplierBatchIdsPayload {
  supplierIds: string[];
  versionBySupplierId: Record<string, number>;
}

export interface SupplierBatchStatusPayload extends SupplierBatchIdsPayload {
  status: PurchaseStatus;
}

export interface SupplierOption {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  status: PurchaseStatus;
}

export interface SupplierProductListItem {
  supplierProductId: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  supplierProductCode: string;
  latestPurchasePrice: number | null;
  minOrderQty: number;
  leadTimeDays: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  aiScore: number;
  lastPurchaseAt: string | null;
  status: PurchaseStatus;
  version: number;
  remark: string;
  createTime: string;
  updateTime: string;
  updatedById?: string | null;
  updatedByName?: string | null;
}

export interface SupplierProductQuery {
  supplierId?: string | 'all';
  supplierName?: string;
  productCode?: string;
  productName?: string;
  status?: PurchaseStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface SupplierProductFormPayload {
  supplierId: string;
  productId: string;
  supplierProductCode: string;
  latestPurchasePrice: number | null;
  minOrderQty: number;
  leadTimeDays: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  aiScore: number;
  status: PurchaseStatus;
  version?: number;
  remark: string;
}

export interface SupplierProductBatchIdsPayload {
  supplierProductIds: string[];
  versionBySupplierProductId: Record<string, number>;
}

export interface SupplierProductBatchStatusPayload extends SupplierProductBatchIdsPayload {
  status: PurchaseStatus;
}

export interface PurchaseOrderItem {
  purchaseOrderItemId: string;
  purchaseOrderId: string;
  purchaseNo: string;
  supplierProductId: string | null;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  quantity: number;
  inboundQty: number;
  unitPrice: number;
  totalAmount: number;
  selectedSupplierScore: number;
  remark: string;
}

export type PurchaseOrderTimelineEvent = 'CREATED' | 'SUBMITTED' | 'APPROVED' | 'INBOUND_CREATED' | 'INBOUND_CONFIRMED';

export interface PurchaseOrderFulfillmentSummary {
  calculationMode: 'AMOUNT_WEIGHTED';
  totalAmount: number;
  inboundAmount: number;
  completionRate: number;
}

export interface PurchaseOrderTimelineItem {
  event: PurchaseOrderTimelineEvent;
  occurredAt: string;
  operatorName: string;
  inboundBillId: string | null;
  inboundBillNo: string | null;
}

export interface PurchaseOrderListItem {
  purchaseOrderId: string;
  purchaseNo: string;
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  warehouseId: string;
  warehouseName: string;
  status: PurchaseOrderStatus;
  totalAmount: number;
  expectedArrivalDate: string | null;
  createdById: string | null;
  createdByName: string;
  submittedAt: string | null;
  submittedById: string | null;
  submittedByName: string;
  approvedById: string | null;
  approvedByName: string;
  approvedAt: string | null;
  createTime: string;
  updateTime: string;
  version: number;
  remark: string;
}

export interface PurchaseOrderDetail extends PurchaseOrderListItem {
  items: PurchaseOrderItem[];
  fulfillmentSummary: PurchaseOrderFulfillmentSummary;
  timeline: PurchaseOrderTimelineItem[];
}

export interface PurchaseOrderSummary {
  draftCount: number;
  submittedCount: number;
  approvedCount: number;
  inboundPendingCount: number;
}

export type PurchaseOrderPage = PageResult<PurchaseOrderListItem>;

export interface PurchaseOrderQuery {
  purchaseNo?: string;
  supplierId?: string | 'all';
  warehouseId?: string | 'all';
  status?: PurchaseOrderStatus | 'all';
  pageNum: number;
  pageSize: number;
}

export interface PurchaseOrderDraftItemPayload {
  purchaseOrderItemId?: string | null;
  supplierProductId: string;
  productId: string;
  /**
   * 前端按所选产品回传的数量精度。服务端不得信任该值，必须按 productId
   * 重新校验并将最终值固化到采购明细快照。
   */
  quantityPrecision: number;
  quantity: number;
  unitPrice: number;
  selectedSupplierScore: number;
  remark: string;
}

export interface PurchaseOrderFormPayload {
  version?: number;
  supplierId: string;
  warehouseId: string;
  expectedArrivalDate?: string | null;
  remark: string;
  items: PurchaseOrderDraftItemPayload[];
}
