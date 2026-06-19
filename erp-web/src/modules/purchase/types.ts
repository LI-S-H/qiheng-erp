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
  remark: string;
  createTime: string;
  updateTime: string;
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
  remark: string;
}

export interface SupplierBatchIdsPayload {
  supplierIds: string[];
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
  supplierProductCode: string;
  latestPurchasePrice: number;
  minOrderQty: number;
  leadTimeDays: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  aiScore: number;
  lastPurchaseAt: string | null;
  status: PurchaseStatus;
  remark: string;
  createTime: string;
  updateTime: string;
}

export interface SupplierProductQuery {
  supplierId?: string | 'all';
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
  latestPurchasePrice: number;
  minOrderQty: number;
  leadTimeDays: number;
  deliveryScore: number;
  qualityScore: number;
  priceScore: number;
  aiScore: number;
  status: PurchaseStatus;
  remark: string;
}

export interface SupplierProductBatchIdsPayload {
  supplierProductIds: string[];
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
  quantity: number;
  inboundQty: number;
  unitPrice: number;
  totalAmount: number;
  selectedSupplierScore: number;
  expectedArrivalDate: string | null;
  remark: string;
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
  approvedById: string | null;
  approvedByName: string;
  approvedAt: string | null;
  createTime: string;
  updateTime: string;
  remark: string;
  items: PurchaseOrderItem[];
}

export interface PurchaseOrderSummary {
  orderCount: number;
  draftCount: number;
  approvedCount: number;
  inboundPendingCount: number;
}

export interface PurchaseOrderPage extends PageResult<PurchaseOrderListItem> {
  summary: PurchaseOrderSummary;
}

export interface PurchaseOrderQuery {
  purchaseNo?: string;
  supplierId?: string | 'all';
  warehouseId?: string | 'all';
  status?: PurchaseOrderStatus | 'all';
  pageNum: number;
  pageSize: number;
}

export interface PurchaseOrderDraftItemPayload {
  supplierProductId?: string | null;
  productId: string;
  quantity: number;
  unitPrice: number;
  selectedSupplierScore: number;
  expectedArrivalDate?: string | null;
  remark: string;
}

export interface PurchaseOrderFormPayload {
  supplierId: string;
  warehouseId: string;
  expectedArrivalDate?: string | null;
  remark: string;
  items: PurchaseOrderDraftItemPayload[];
}
