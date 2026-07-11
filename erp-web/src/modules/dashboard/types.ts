export interface DashboardMetric {
  label: string;
  value: number;
  unit: string;
  changeRate: number;
  compareText: string;
  status: 'good' | 'watch' | 'risk' | 'neutral';
}

export interface DashboardTrendPoint {
  date: string;
  salesAmount: number;
  purchaseAmount: number;
  grossMarginAmount: number;
}

export interface DashboardTodoItem {
  todoId: string;
  businessType: string;
  businessLabel: string;
  title: string;
  description: string;
  count: number;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
  sortWeight: number;
  sourceMode: 'AGGREGATED' | 'PERSISTED';
  completionMode: 'AUTO' | 'TRACKED';
  status: 'PENDING' | 'DONE' | 'IGNORED';
  errorCode: string | null;
  errorMessage: string | null;
  sourceNo: string | null;
  occurredAt: string | null;
  resolveHint: string | null;
  evidence: DashboardTodoEvidence[];
  route: string;
}

export interface DashboardTodoEvidence {
  itemId: string;
  primaryText: string;
  secondaryText: string;
  metrics: DashboardTodoEvidenceMetric[];
}

export interface DashboardTodoEvidenceMetric {
  label: string;
  value: string;
  tone: 'neutral' | 'watch' | 'risk';
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
  severity: 'HIGH' | 'MEDIUM';
  latestOutboundAt: string | null;
}

export interface DashboardOrderStage {
  stage: string;
  purchaseCount: number;
  salesCount: number;
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
  todos: DashboardTodoItem[];
  stockAlerts: DashboardStockAlert[];
  orderStages: DashboardOrderStage[];
  topProducts: DashboardTopProduct[];
  supplierPerformance: DashboardSupplierPerformance[];
}

export interface DashboardNotificationPopover {
  refreshedAt: string;
  pendingCount: number;
  highPriorityCount: number;
  hasMore: boolean;
  items: DashboardTodoItem[];
}
