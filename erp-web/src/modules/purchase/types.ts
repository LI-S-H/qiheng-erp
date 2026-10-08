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
  overallScore: number | null;
  deliveryScore: number | null;
  qualityScore: number | null;
  priceScore: number | null;
  serviceScore: number | null;
  serviceScoreReason: string;
  avgDeliveryDays: number | null;
  scoreBasisAmount: number | null;
  scoreStatus: 'NOT_READY' | 'READY';
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
  scoreStatus?: 'NOT_READY' | 'READY' | '' | 'all';
  overallScoreMin?: number | null;
  overallScoreMax?: number | null;
  serviceScoreMin?: number | null;
  serviceScoreMax?: number | null;
  /** 金额按 OpenAPI 的 decimal 字符串传输，避免在请求边界损失精度。 */
  scoreBasisAmountMin?: string | null;
  scoreBasisAmountMax?: string | null;
  avgDeliveryDaysMin?: number | null;
  avgDeliveryDaysMax?: number | null;
  pageNum: number;
  pageSize: number;
}

export interface SupplierBasePayload {
  supplierName: string;
  contactName?: string | null;
  contactPhone?: string | null;
  address?: string | null;
  paymentTerms?: string | null;
  status: PurchaseStatus;
  remark: string;
}

export interface SupplierCreatePayload extends SupplierBasePayload {
  serviceScore: number | null;
  serviceScoreReason: string | null;
}

export interface SupplierUpdatePayload extends SupplierBasePayload { version: number; }

export interface SupplierServiceScorePayload {
  version: number;
  serviceScore: number | null;
  reason: string;
}

export type ScoreMetricType = 'SERVICE' | 'DELIVERY' | 'QUALITY' | 'PRICE';
export type ScoreTriggerType = 'PRICE_TRIGGER' | 'SERVICE_TRIGGER' | 'INBOUND_TRIGGER' | 'QUOTE_EXPIRED_TRIGGER' | 'DAILY_TRIGGER' | 'MERGED';

export interface ScoreChangeLogQuery {
  supplierId?: string | null;
  supplierProductId?: string | null;
  metricType?: ScoreMetricType | null;
  triggerType?: ScoreTriggerType | null;
  batchNo?: string | null;
  startTime?: string | null;
  endTime?: string | null;
  pageNum: number;
  pageSize: number;
}

export interface ScoreChangeSource {
  businessType: 'PURCHASE_ORDER' | 'SUPPLIER_PRODUCT' | 'PRODUCT' | 'SUPPLIER';
  businessId: string;
  businessNo: string | null;
}

export interface ScoreChangeLog {
  scoreChangeLogId: string;
  supplierId: string;
  supplierProductId: string | null;
  metricType: ScoreMetricType;
  metricScoreBefore: number | null;
  metricScoreAfter: number | null;
  productRecommendScoreBefore: number | null;
  productRecommendScoreAfter: number | null;
  supplierOverallScoreBefore: number | null;
  supplierOverallScoreAfter: number | null;
  triggerType: ScoreTriggerType;
  batchNo: string;
  ruleVersion: string;
  relatedSources: ScoreChangeSource[];
  operatorType: 'USER' | 'SYSTEM';
  operatorId: string | null;
  operatorName: string;
  reason: string;
  createTime: string;
}

export interface SupplierBatchIdsPayload {
  supplierIds: string[];
  versionBySupplierId: Record<string, number>;
}

/**
 * 供应商批量删除失败明细。
 * 后端部分失败时通过 code=0 + data=SupplierBatchFailure[] 业务级响应,
 * 避免 Map.toString() 拼接到 msg 不可解析。
 */
export interface SupplierBatchFailure {
  supplierId: string;
  reason: string;
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
  quotedPurchasePrice: number | null;
  quotedPriceReason: string;
  quotedPriceUpdatedAt: string | null;
  quoteValidUntil: string | null;
  latestPurchasePrice: number | null;
  minOrderQty: number;
  /** 仅由完全入库事实回写，暂无完整样本时为空。 */
  avgDeliveryDays: number | null;
  qualityScore: number | null;
  priceScore: number | null;
  aiScore: number | null;
  lastPurchaseAt: string | null;
  scoreBasisAmount: number | null;
  scoreStatus: 'NOT_READY' | 'READY';
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
  productId?: string | 'all';
  supplierName?: string;
  productCode?: string;
  productName?: string;
  status?: PurchaseStatus | '' | 'all';
  scoreStatus?: 'NOT_READY' | 'READY' | '' | 'all';
  quoteStatus?: 'NONE' | 'VALID' | 'EXPIRED' | '' | 'all';
  quoteValidUntilEnd?: string;
  qualityScoreMin?: number | null;
  qualityScoreMax?: number | null;
  priceScoreMin?: number | null;
  priceScoreMax?: number | null;
  aiScoreMin?: number | null;
  aiScoreMax?: number | null;
  scoreBasisAmountMin?: number | null;
  scoreBasisAmountMax?: number | null;
  minOrderQtyMin?: number | null;
  minOrderQtyMax?: number | null;
  pageNum: number;
  pageSize: number;
}

export interface SupplierProductCreatePayload {
  supplierId: string;
  productId: string;
  quotedPurchasePrice: number | null;
  quoteValidUntil: string | null;
  quoteReason: string | null;
  minOrderQty: number;
  status: PurchaseStatus;
  remark: string;
}

export interface SupplierProductUpdatePayload {
  version: number;
  minOrderQty: number;
  status: PurchaseStatus;
  remark: string;
}

export interface SupplierProductQuotePayload {
  version: number;
  quotedPurchasePrice: number | null;
  quoteValidUntil: string | null;
  reason: string;
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
  selectedSupplierScore: number | null;
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

export type PurchaseOrderReturnCoverage = 'NONE' | 'PARTIAL' | 'FULL';

export interface PurchaseOrderReturnItemOverview {
  purchaseOrderItemId: string;
  orderedQty: number;
  fulfilledQty: number;
  approvedReturnQty: number;
  approvedReturnAmount: number;
}

export interface PurchaseOrderReturnOverview {
  hasReturnOrder: boolean;
  coverage: PurchaseOrderReturnCoverage;
  approvedReturnAmount: number;
  returnOrderCount: number;
  effectiveReturnOrderCount: number;
  /** 仅订单详情返回，用于核对每个来源明细的退货覆盖情况。 */
  items?: PurchaseOrderReturnItemOverview[];
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
  /** 只读退货汇总；列表不返回明细覆盖项。 */
  returnOverview?: PurchaseOrderReturnOverview;
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
