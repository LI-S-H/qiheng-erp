import type { PageResult } from '@/shared/types/api';

export type ReturnType = 'SALES_RETURN' | 'PURCHASE_RETURN';
export type ReturnHandlingType = 'REFUND' | 'EXCHANGE' | 'OTHER';
export type ReturnReasonCode =
  | 'QUALITY_ISSUE'
  | 'DAMAGED'
  | 'WRONG_ITEM'
  | 'QUANTITY_ERROR'
  | 'SPEC_MISMATCH'
  | 'NO_LONGER_NEEDED'
  | 'OTHER';
export type ReturnOrderStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PARTIAL_EXECUTED' | 'COMPLETED' | 'CANCELLED';

export interface ReturnOrderListItem {
  returnOrderId: string;
  returnNo: string;
  returnType: ReturnType;
  sourceOrderId: string;
  sourceOrderNo: string;
  partyId: string;
  partyCode: string;
  partyName: string;
  warehouseId: string;
  warehouseName: string;
  expectedExecutionDate: string | null;
  handlingType: ReturnHandlingType;
  reasonCode: ReturnReasonCode;
  returnReason: string;
  totalAmount: number;
  status: ReturnOrderStatus;
  statusReason: string;
  createdById: string | null;
  createdByName: string;
  submittedAt: string | null;
  approvedById: string | null;
  approvedByName: string;
  approvedAt: string | null;
  createTime: string;
  updateTime: string;
  remark: string;
  version: number;
}

export interface ReturnOrderItem {
  returnOrderItemId: string;
  returnOrderId: string;
  sourceOrderItemId: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  sourceFulfilledQty: number;
  requestedQty: number;
  approvedQty: number;
  processedQty: number;
  unitPrice: number;
  totalAmount: number;
  createTime: string;
  updateTime: string;
  remark: string;
}

export interface ReturnOrderDetail extends ReturnOrderListItem {
  items: ReturnOrderItem[];
}

export type ReturnOrderPage = PageResult<ReturnOrderListItem>;

export interface ReturnOrderQuery {
  returnNo?: string;
  sourceOrderNo?: string;
  partyId?: string | 'all';
  warehouseId?: string | 'all';
  status?: ReturnOrderStatus | 'all';
  pageNum: number;
  pageSize: number;
}

export interface ReturnOrderDraftItemPayload {
  sourceOrderItemId: string;
  requestedQty: number;
  remark: string;
}

export interface ReturnOrderFormPayload {
  sourceOrderId: string;
  warehouseId: string;
  expectedExecutionDate: string | null;
  handlingType: ReturnHandlingType;
  reasonCode: ReturnReasonCode;
  returnReason: string;
  remark: string;
  items: ReturnOrderDraftItemPayload[];
}

export interface ReturnOrderCreateRequest extends ReturnOrderFormPayload {
  returnType: ReturnType;
}

export interface ReturnOrderUpdateRequest extends ReturnOrderFormPayload {
  version: number;
}

export interface ReturnOrderApprovePayload {
  version: number;
  items: Array<{
    returnOrderItemId: string;
    approvedQty: number;
  }>;
}

export interface ReturnOrderReasonActionPayload {
  version: number;
  reason: string;
}

export interface ReturnableSourceOrder {
  sourceOrderId: string;
  sourceOrderNo: string;
  partyId: string;
  partyCode: string;
  partyName: string;
  warehouseId: string;
  warehouseName: string;
  fulfilledItemCount: number;
  totalAvailableReturnQty: number;
}

export type ReturnableSourceOrderPage = PageResult<ReturnableSourceOrder>;

export interface ReturnableSourceOrderItem {
  sourceOrderItemId: string;
  productId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  sourceFulfilledQty: number;
  occupiedQty: number;
  availableReturnQty: number;
  unitPrice: number;
}

export interface ReturnSelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface ReturnOrderService {
  listReturns: (query: ReturnOrderQuery) => Promise<ReturnOrderPage>;
  getReturnDetail: (returnOrderId: string) => Promise<ReturnOrderDetail>;
  createReturn: (payload: ReturnOrderFormPayload) => Promise<ReturnOrderDetail>;
  updateReturn: (returnOrderId: string, payload: ReturnOrderUpdateRequest) => Promise<ReturnOrderDetail>;
  deleteReturn: (returnOrderId: string, version: number) => Promise<void>;
  submitReturn: (returnOrderId: string, version: number) => Promise<void>;
  approveReturn: (returnOrderId: string, payload: ReturnOrderApprovePayload) => Promise<void>;
  cancelReturn: (returnOrderId: string, payload: ReturnOrderReasonActionPayload) => Promise<void>;
  searchPartyOptions: (keyword: string) => Promise<ReturnSelectOption[]>;
  searchWarehouseOptions: (keyword: string) => Promise<ReturnSelectOption[]>;
  searchSourceOrders: (keyword: string) => Promise<ReturnableSourceOrder[]>;
  listSourceItems: (sourceOrderId: string) => Promise<ReturnableSourceOrderItem[]>;
}

export interface ReturnOrderPageConfig {
  returnType: ReturnType;
  title: string;
  description: string;
  listTitle: string;
  emptyText: string;
  partyLabel: string;
  partyAllLabel: string;
  partySearchPlaceholder: string;
  warehouseLabel: string;
  returnNoPlaceholder: string;
  sourceOrderLabel: string;
  sourceOrderPlaceholder: string;
  sourceOrderSearchPlaceholder: string;
  fulfilledQuantityLabel: string;
  executionDateLabel: string;
  createTitle: string;
  createButtonLabel: string;
  editTitle: string;
  detailTitle: string;
  approvedStatusLabel: string;
  partialStatusLabel: string;
  approvedHint: string;
  partialHint: string;
  approvalResultDescription: string;
  /** 销售来源能力尚未部署时保留查询页，但禁止创建与状态流转。 */
  backendEnabled?: boolean;
  backendUnavailableMessage?: string;
  permissions: {
    query: string;
    create: string;
    manage: string;
  };
  service: ReturnOrderService;
}
