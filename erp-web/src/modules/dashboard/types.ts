export interface DashboardMetric {
  key: 'MONTH_SALES' | 'MONTH_GROSS_PROFIT' | 'PENDING_ORDERS' | 'STOCK_RISK_SKU';
  label: string;
  value: number | null;
  unit: string;
  changeRate: number | null;
  compareText: string | null;
  status: 'good' | 'watch' | 'risk' | 'neutral';
}

export type DashboardAccessState = 'ALLOWED' | 'EMPTY' | 'DENIED';

export interface DashboardSectionAccess {
  state: DashboardAccessState;
}

export interface DashboardOverviewAccess {
  metrics: Record<DashboardMetric['key'], DashboardSectionAccess>;
  todos: DashboardSectionAccess;
  stockAlerts: DashboardSectionAccess;
  orderStages: DashboardSectionAccess;
  topProducts: DashboardSectionAccess;
  supplierPerformance: DashboardSectionAccess;
}

export interface DashboardTrendPoint {
  date: string;
  salesAmount: number;
  purchaseAmount: number;
  grossMarginAmount: number;
}

export type DashboardTodoDocumentModel =
  | 'PURCHASE_ORDER_APPROVAL'
  | 'SALES_ORDER_APPROVAL'
  | 'PURCHASE_RETURN_APPROVAL'
  | 'SALES_RETURN_APPROVAL'
  | 'INBOUND_CONFIRM'
  | 'OUTBOUND_CONFIRM';

export interface DashboardTodoDocumentItem {
  documentNo: string;
  sourceDocumentNo: string | null;
  counterpartyName: string | null;
  amountFen: number | null;
  waitHours: number | null;
  waitLevel: 'NORMAL' | 'WARNING' | 'OVERDUE' | null;
  documentStatus: string;
}

export interface DashboardTodoStockRiskItem {
  id: string;
  productCode: string;
  productName: string;
  warehouseName: string;
  unitName: string;
  availableQty: number;
  safetyStockQty: number;
  suggestedPurchaseQty: number;
  severity: 'HIGH' | 'MEDIUM';
}

export interface DashboardTodoSystemExceptionItem {
  id: string;
  exceptionNo: string;
  summary: string;
  exceptionType: string;
  sourceModule: string;
  occurredAt: string | null;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
}

export type DashboardTodoDetail =
  | { model: DashboardTodoDocumentModel; items: DashboardTodoDocumentItem[] }
  | { model: 'STOCK_RISK_REVIEW'; items: DashboardTodoStockRiskItem[] }
  | { model: 'SYSTEM_EXCEPTION'; items: DashboardTodoSystemExceptionItem[] };

export interface DashboardTodoItem {
  todoId: string;
  businessType: string;
  businessLabel: string;
  title: string;
  description: string;
  count: number;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  sortWeight: number;
  completionMode: 'AUTO' | 'TRACKED';
  resolveHint: string | null;
  detail: DashboardTodoDetail;
}

export interface DashboardStockAlert {
  stockId: string;
  productId: string;
  productCode: string;
  productName: string;
  warehouseId: string;
  warehouseName: string;
  unitName: string;
  availableQty: number;
  safetyStockQty: number;
  suggestedPurchaseQty: number;
  severity: 'OUT_OF_STOCK' | 'NO_AVAILABLE' | 'LOW_STOCK';
  latestOutboundAt: string | null;
}

export interface DashboardOrderStage {
  stage: string;
  purchaseCount: number;
  salesCount: number;
}

export interface DashboardOrderStagePeriod {
  type: 'CURRENT_CALENDAR_MONTH';
  startAt: string;
  endAtExclusive: string;
}

export interface DashboardTopProduct {
  productId: string;
  productCode: string;
  productName: string;
  salesAmount: number;
  salesQty: number;
  availableQty: number;
}

export interface DashboardSupplierPerformance {
  supplierId: string;
  supplierCode: string;
  supplierName: string;
  deliveryScore: number;
  qualityScore: number;
  onTimeRate: number;
}

export interface DashboardOverview {
  refreshedAt: string;
  metrics: DashboardMetric[];
  trend: DashboardTrendPoint[];
  trendPermissions?: DashboardTrendPermissions | null;
  todos: DashboardTodoItem[];
  stockAlerts: DashboardStockAlert[];
  orderStages: DashboardOrderStage[];
  orderStagePeriod: DashboardOrderStagePeriod;
  orderStagePermissions?: DashboardOrderStagePermissions | null;
  topProducts: DashboardTopProduct[];
  supplierPerformance: DashboardSupplierPerformance[];
  access: DashboardOverviewAccess;
}

/** 经营趋势可见维度标记。旧版本接口不返回该字段时，前端回退为全 true。 */
export interface DashboardTrendPermissions {
  canViewSales: boolean;
  canViewPurchase: boolean;
  canViewGross: boolean;
}

/** 订单流转的列级可见标记，保留用于采购/销售柱形图裁剪。 */
export interface DashboardOrderStagePermissions {
  canViewPurchase: boolean;
  canViewSales: boolean;
}

export interface DashboardNotificationPopover {
  refreshedAt: string;
  pendingCount: number;
  highPriorityCount: number;
  hasMore: boolean;
  items: DashboardTodoItem[];
}