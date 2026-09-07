import { getResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import { normalizeMoneyNumber } from '@/shared/utils/money';
import { assertQuantityPrecision } from '@/shared/utils/qty';
import type {
  DashboardAccessState,
  DashboardMetric,
  DashboardInventoryStatus,
  DashboardInventoryRiskPreviewItem,
  DashboardOverviewAccess,
  DashboardSectionAccess,
  DashboardOrderStage,
  DashboardOrderStagePeriod,
  DashboardOrderStagePermissions,
  DashboardOverview,
  DashboardStockAlert,
  DashboardSupplierPerformance,
  DashboardTodoItem,
  DashboardTopProduct,
  DashboardTrendPermissions,
  DashboardTrendPoint,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

function formatMockDateTime(value: Date) {
  const pad = (part: number) => String(part).padStart(2, '0');
  return [value.getFullYear(), pad(value.getMonth() + 1), pad(value.getDate())].join('-') + ' 00:00:00';
}

function currentMockOrderStagePeriod(): DashboardOrderStagePeriod {
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const nextMonthStart = new Date(now.getFullYear(), now.getMonth() + 1, 1);
  return {
    type: 'CURRENT_CALENDAR_MONTH',
    startAt: formatMockDateTime(monthStart),
    endAtExclusive: formatMockDateTime(nextMonthStart),
  };
}

const defaultTodoLabels: Record<string, string> = {
  PURCHASE: '采购',
  SALES: '销售',
  WAREHOUSE: '仓储',
  SYSTEM: '系统',
  SYSTEM_EXCEPTION: '系统',
  INVENTORY: '库存',
  EXCEPTION: '异常',
  AI: '智能',
};

const mockOverview: DashboardOverview = {
  refreshedAt: '2026-06-30 09:30:00',
  access: {
    metrics: {
      MONTH_SALES: { state: 'ALLOWED' },
      MONTH_GROSS_PROFIT: { state: 'ALLOWED' },
      PENDING_ORDERS: { state: 'ALLOWED' },
      STOCK_RISK_SKU: { state: 'ALLOWED' },
    },
    todos: { state: 'ALLOWED' },
    stockAlerts: { state: 'ALLOWED' },
    orderStages: { state: 'ALLOWED' },
    topProducts: { state: 'ALLOWED' },
    supplierPerformance: { state: 'ALLOWED' },
  },
  metrics: [
    { key: 'MONTH_SALES', label: '本月销售额', value: 286430, unit: '元', changeRate: 12.8, compareText: '较上月', status: 'good' },
    { key: 'MONTH_GROSS_PROFIT', label: '本月毛利额', value: -842600, unit: '元', changeRate: 6.4, compareText: '较上月', status: 'good' },
    { key: 'PENDING_ORDERS', label: '待处理订单', value: 7, unit: '单', changeRate: -8.1, compareText: '较昨日', status: 'good' },
    { key: 'STOCK_RISK_SKU', label: '库存风险 SKU', value: 11, unit: '个', changeRate: 18.6, compareText: '较上月', status: 'risk' },
  ],
  trend: [
    { date: '06-01', salesAmount: 186200, purchaseAmount: 112400, grossMarginAmount: 54200 },
    { date: '06-02', salesAmount: 193600, purchaseAmount: 116800, grossMarginAmount: 56800 },
    { date: '06-03', salesAmount: 201400, purchaseAmount: 121300, grossMarginAmount: 59300 },
    { date: '06-04', salesAmount: 198700, purchaseAmount: 119600, grossMarginAmount: 58100 },
    { date: '06-05', salesAmount: 208900, purchaseAmount: 126500, grossMarginAmount: 62400 },
    { date: '06-06', salesAmount: 214300, purchaseAmount: 129800, grossMarginAmount: 64700 },
    { date: '06-07', salesAmount: 205600, purchaseAmount: 122700, grossMarginAmount: 61200 },
    { date: '06-08', salesAmount: 211800, purchaseAmount: 128200, grossMarginAmount: 63500 },
    { date: '06-09', salesAmount: 219500, purchaseAmount: 133100, grossMarginAmount: 66200 },
    { date: '06-10', salesAmount: 226300, purchaseAmount: 137600, grossMarginAmount: 69100 },
    { date: '06-11', salesAmount: 221700, purchaseAmount: 134200, grossMarginAmount: 67400 },
    { date: '06-12', salesAmount: 232600, purchaseAmount: 141500, grossMarginAmount: 71300 },
    { date: '06-13', salesAmount: 238900, purchaseAmount: 144600, grossMarginAmount: 73200 },
    { date: '06-14', salesAmount: 229400, purchaseAmount: 139800, grossMarginAmount: 70400 },
    { date: '06-15', salesAmount: 236700, purchaseAmount: 143900, grossMarginAmount: 72900 },
    { date: '06-16', salesAmount: 241200, purchaseAmount: 147300, grossMarginAmount: 74800 },
    { date: '06-17', salesAmount: 249800, purchaseAmount: 151600, grossMarginAmount: 78100 },
    { date: '06-18', salesAmount: 245300, purchaseAmount: 149200, grossMarginAmount: 76200 },
    { date: '06-19', salesAmount: 253600, purchaseAmount: 154700, grossMarginAmount: 79600 },
    { date: '06-20', salesAmount: 260400, purchaseAmount: 158900, grossMarginAmount: 82300 },
    { date: '06-21', salesAmount: 248100, purchaseAmount: 150600, grossMarginAmount: 77500 },
    { date: '06-22', salesAmount: 255900, purchaseAmount: 156300, grossMarginAmount: 80700 },
    { date: '06-23', salesAmount: 263500, purchaseAmount: 160200, grossMarginAmount: 83500 },
    { date: '06-24', salesAmount: 218000, purchaseAmount: 132000, grossMarginAmount: 68200 },
    { date: '06-25', salesAmount: 244000, purchaseAmount: 146500, grossMarginAmount: 75600 },
    { date: '06-26', salesAmount: 231800, purchaseAmount: 125400, grossMarginAmount: 72400 },
    { date: '06-27', salesAmount: 269200, purchaseAmount: 158600, grossMarginAmount: 86200 },
    { date: '06-28', salesAmount: 252700, purchaseAmount: 141300, grossMarginAmount: 80100 },
    { date: '06-29', salesAmount: 276900, purchaseAmount: 167800, grossMarginAmount: 88400 },
    { date: '06-30', salesAmount: 286430, purchaseAmount: 378530, grossMarginAmount: -92100 },
  ],
  todos: [
    { todoId: 'todo-system-exception', businessType: 'SYSTEM', businessLabel: '系统', title: '系统异常待处理', description: '当前有 2 条系统异常记录需要关注。', count: 2, priority: 'HIGH', sortWeight: 10, completionMode: 'TRACKED', resolveHint: null, detail: { model: 'SYSTEM_EXCEPTION', items: [
      { id: 'AI-MCP-20260701-001', exceptionNo: 'AI-MCP-20260701-001', summary: '库存预测工具调用超时，未生成补货建议。', exceptionType: 'MCP超时', sourceModule: 'AI助手', occurredAt: '2026-07-01 09:18:00', severity: 'HIGH' },
      { id: 'DLQ-ORDER-STOCK-00023', exceptionNo: 'DLQ-ORDER-STOCK-00023', summary: '订单审核后的出库任务进入死信队列。', exceptionType: '死信队列', sourceModule: '消息队列', occurredAt: '2026-07-01 09:05:00', severity: 'MEDIUM' },
    ] } },
    { todoId: 'todo-purchase-return-approve', businessType: 'PURCHASE', businessLabel: '采购', title: '采购退货待审核', description: '还有 1 张采购退货单需要审核。', count: 1, priority: 'HIGH', sortWeight: 20, completionMode: 'AUTO', resolveHint: '前往采购退货单完成审核，审核通过后将生成对应出库工作单。', detail: { model: 'PURCHASE_RETURN_APPROVAL', items: [
      { documentNo: 'PR202607002', sourceDocumentNo: 'PO202606001', counterpartyName: '华东饮品供应链', amountFen: 1865600, waitHours: 52, waitLevel: 'WARNING', documentStatus: 'SUBMITTED' },
    ] } },
    { todoId: 'todo-purchase-approve', businessType: 'PURCHASE', businessLabel: '采购', title: '采购单待审核', description: '还有 2 张采购单需要审核。', count: 2, priority: 'HIGH', sortWeight: 21, completionMode: 'AUTO', resolveHint: '前往采购订单完成审核，审核通过或驳回后该待办自动更新。', detail: { model: 'PURCHASE_ORDER_APPROVAL', items: [
      { documentNo: 'PO202607004', sourceDocumentNo: null, counterpartyName: '谷仓食品批发', amountFen: 10800, waitHours: 27, waitLevel: 'WARNING', documentStatus: 'SUBMITTED' },
      { documentNo: 'PO202607005', sourceDocumentNo: null, counterpartyName: '华东饮品供应链', amountFen: 8600, waitHours: 3, waitLevel: 'NORMAL', documentStatus: 'SUBMITTED' },
    ] } },
    { todoId: 'todo-sales-return-approve', businessType: 'SALES', businessLabel: '销售', title: '销售退货待审核', description: '还有 1 张销售退货单需要审核。', count: 1, priority: 'HIGH', sortWeight: 22, completionMode: 'AUTO', resolveHint: '前往销售退货单完成审核，审核通过后将生成对应入库工作单。', detail: { model: 'SALES_RETURN_APPROVAL', items: [
      { documentNo: 'SR202607002', sourceDocumentNo: 'SO202606001', counterpartyName: '上海星河便利店', amountFen: 1234000, waitHours: 76, waitLevel: 'OVERDUE', documentStatus: 'SUBMITTED' },
    ] } },
    { todoId: 'todo-sales-approve', businessType: 'SALES', businessLabel: '销售', title: '销售单待审核', description: '还有 2 张销售单需要审核。', count: 2, priority: 'HIGH', sortWeight: 23, completionMode: 'AUTO', resolveHint: '前往销售订单完成审核，审核通过后进入库存锁定和发货准备。', detail: { model: 'SALES_ORDER_APPROVAL', items: [
      { documentNo: 'SO202607004', sourceDocumentNo: null, counterpartyName: '杭州蓝湖办公采购', amountFen: 7000, waitHours: 18, waitLevel: 'NORMAL', documentStatus: 'SUBMITTED' },
    ] } },
    { todoId: 'todo-stock-risk-review', businessType: 'INVENTORY', businessLabel: '库存', title: '库存异常待复核', description: '还有 2 个 SKU 可用库存低于安全线。', count: 2, priority: 'HIGH', sortWeight: 30, completionMode: 'AUTO', resolveHint: '前往库存余额查看低库存 SKU，补货计划生成或库存恢复后自动更新。', detail: { model: 'STOCK_RISK_REVIEW', items: [
      { id: 'stock-P000043-W008', productCode: 'P000043', productName: 'USB-C扩展坞', warehouseName: '南京备货仓', unitName: '个', availableQty: 0, safetyStockQty: 4, suggestedPurchaseQty: 0, severity: 'HIGH' },
      { id: 'stock-P000027-W002', productCode: 'P000027', productName: '热敏标签纸', warehouseName: '华南中心仓', unitName: '卷', availableQty: 0, safetyStockQty: 40, suggestedPurchaseQty: 40, severity: 'HIGH' },
    ] } },
    { todoId: 'todo-inbound', businessType: 'WAREHOUSE', businessLabel: '仓储', title: '待确认入库', description: '还有 2 张入库单等待仓库确认。', count: 2, priority: 'MEDIUM', sortWeight: 50, completionMode: 'AUTO', resolveHint: '前往入库单完成确认，确认入库后该待办自动更新。', detail: { model: 'INBOUND_CONFIRM', items: [
      { documentNo: 'IN202606130006', sourceDocumentNo: 'SR202606002', counterpartyName: '华北中心仓', amountFen: null, waitHours: 2, waitLevel: 'NORMAL', documentStatus: 'PENDING_CONFIRM' },
    ] } },
    { todoId: 'todo-outbound', businessType: 'WAREHOUSE', businessLabel: '仓储', title: '待确认出库', description: '还有 1 张出库单等待发货确认。', count: 1, priority: 'MEDIUM', sortWeight: 51, completionMode: 'AUTO', resolveHint: '前往出库单完成确认，确认出库后该待办自动更新。', detail: { model: 'OUTBOUND_CONFIRM', items: [
      { documentNo: 'OUT202606100015', sourceDocumentNo: 'PR202606004', counterpartyName: '广州天河门店', amountFen: null, waitHours: 1, waitLevel: 'NORMAL', documentStatus: 'PENDING_CONFIRM' },
    ] } },
  ],  stockAlerts: [
    { stockId: '1940000000000000013', productId: '1920000000000000043', productCode: 'P000043', productName: 'USB-C扩展坞', warehouseId: '1930000000000000008', warehouseName: '南京备货仓', unitName: '个', availableQty: 0, safetyStockQty: 4, suggestedPurchaseQty: 0, severity: 'NO_AVAILABLE', latestOutboundAt: '2026-06-13 11:55:00' },
    { stockId: '1940000000000000005', productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', unitName: '卷', availableQty: 0, safetyStockQty: 40, suggestedPurchaseQty: 40, severity: 'OUT_OF_STOCK', latestOutboundAt: '2026-06-14 08:40:00' },
    { stockId: '1940000000000000012', productId: '1920000000000000044', productCode: 'P000044', productName: '无线办公鼠标', warehouseId: '1930000000000000007', warehouseName: '杭州电商仓', unitName: '个', availableQty: 0, safetyStockQty: 8, suggestedPurchaseQty: 0, severity: 'NO_AVAILABLE', latestOutboundAt: '2026-06-13 13:10:00' },
    { stockId: '1931000000000000002', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', unitName: '盒', availableQty: 7, safetyStockQty: 8, suggestedPurchaseQty: 12, severity: 'LOW_STOCK', latestOutboundAt: '2026-07-01 10:05:00' },
    { stockId: '1940000000000000004', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', unitName: '箱', availableQty: 10, safetyStockQty: 15, suggestedPurchaseQty: 20, severity: 'LOW_STOCK', latestOutboundAt: '2026-06-14 08:45:00' },
    { stockId: '1940000000000000006', productId: '1920000000000000033', productCode: 'P000033', productName: '浓缩洗衣液', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', unitName: '瓶', availableQty: 9, safetyStockQty: 10, suggestedPurchaseQty: 15, severity: 'LOW_STOCK', latestOutboundAt: '2026-06-13 17:25:00' },
    { stockId: '1940000000000000008', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', warehouseId: '1930000000000000004', warehouseName: '西南中心仓', unitName: '卷', availableQty: 20, safetyStockQty: 25, suggestedPurchaseQty: 30, severity: 'LOW_STOCK', latestOutboundAt: '2026-06-13 16:45:00' },
    { stockId: '1940000000000000010', productId: '1920000000000000038', productCode: 'P000038', productName: '无痕粘钩', warehouseId: '1930000000000000005', warehouseName: '武汉中转仓', unitName: '卡', availableQty: 10, safetyStockQty: 15, suggestedPurchaseQty: 20, severity: 'LOW_STOCK', latestOutboundAt: '2026-06-13 15:18:00' },
  ],
  orderStages: [
    { stage: '草稿', purchaseCount: 1, salesCount: 1 },
    { stage: '待审核', purchaseCount: 2, salesCount: 2 },
    { stage: '已审核', purchaseCount: 3, salesCount: 2 },
    { stage: '部分出入库', purchaseCount: 1, salesCount: 1 },
    { stage: '已完成', purchaseCount: 2, salesCount: 4 },
    { stage: '已取消', purchaseCount: 0, salesCount: 0 },
  ],
  orderStagePeriod: currentMockOrderStagePeriod(),
  topProducts: [
    { productId: '1920000000000000001', productCode: 'P000001', productName: '经典原味苏打水', salesAmount: 126800, salesQty: 360, availableQty: 122 },
    { productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', salesAmount: 98400, salesQty: 220, availableQty: 24 },
    { productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', salesAmount: 87600, salesQty: 194, availableQty: 7 },
    { productId: '1920000000000000043', productCode: 'P000043', productName: 'USB-C扩展坞', salesAmount: 75800, salesQty: 72, availableQty: 0 },
    { productId: '1920000000000000007', productCode: 'P000007', productName: '每日坚果混合装', salesAmount: 69400, salesQty: 128, availableQty: 31 },
    { productId: '1920000000000000008', productCode: 'P000008', productName: '海盐苏打饼干', salesAmount: 53600, salesQty: 104, availableQty: 39 },
    { productId: '1920000000000000021', productCode: 'P000021', productName: '中性签字笔', salesAmount: 43800, salesQty: 410, availableQty: 30 },
    { productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', salesAmount: 31200, salesQty: 84, availableQty: 0 },
  ],
  supplierPerformance: [
    { supplierId: '2010000000000000001', supplierCode: 'S001', supplierName: '华东饮品供应链', deliveryScore: 94.2, qualityScore: 96.1, onTimeRate: 96.5 },
    { supplierId: '2010000000000000005', supplierCode: 'S005', supplierName: '森纸纸业集团', deliveryScore: 95.8, qualityScore: 97.2, onTimeRate: 97.4 },
    { supplierId: '2010000000000000003', supplierCode: 'S003', supplierName: '谷仓食品批发', deliveryScore: 92.4, qualityScore: 95.2, onTimeRate: 94.8 },
    { supplierId: '2010000000000000002', supplierCode: 'S002', supplierName: '晨岛咖啡贸易', deliveryScore: 89.7, qualityScore: 93.4, onTimeRate: 91.1 },
    { supplierId: '2010000000000000004', supplierCode: 'S004', supplierName: '文仪办公渠道', deliveryScore: 85.4, qualityScore: 90.5, onTimeRate: 88.7 },
    { supplierId: '2010000000000000006', supplierCode: 'S006', supplierName: '拓联数码配件', deliveryScore: 78.2, qualityScore: 82.4, onTimeRate: 82.1 },
  ],
};

const dashboardMetricKeys = ['MONTH_SALES', 'MONTH_GROSS_PROFIT', 'PENDING_ORDERS', 'STOCK_RISK_SKU'] as const;
const mockInventoryStatus: DashboardInventoryStatus = {
  distribution: [
    { status: 'NORMAL', recordCount: 13 },
    { status: 'LOW_STOCK', recordCount: 7 },
    { status: 'NO_AVAILABLE', recordCount: 2 },
    { status: 'OUT_OF_STOCK', recordCount: 0 },
  ],
  riskPreview: {
    items: [
      { stockId: '1940000000000000013', productCode: 'P000043', productName: 'USB-C扩展坞', warehouseName: '南京备货仓', unitName: '个', quantityPrecision: 0, availableQty: 0, safetyStockQty: 4, severity: 'NO_AVAILABLE' },
      { stockId: '1940000000000000005', productCode: 'P000027', productName: '热敏标签纸', warehouseName: '华南中心仓', unitName: '卷', quantityPrecision: 0, availableQty: 0, safetyStockQty: 40, severity: 'OUT_OF_STOCK' },
      { stockId: '1931000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', warehouseName: '华东中心仓', unitName: '盒', quantityPrecision: 0, availableQty: 7, safetyStockQty: 8, severity: 'LOW_STOCK' },
    ],
    hasMore: false,
  },
  access: { state: 'ALLOWED' },
};

function normalizeMetric(item: DashboardMetric): DashboardMetric {
  if (!dashboardMetricKeys.includes(item.key)) {
    throw new Error(`dashboard metric key is invalid: ${String(item.key)}`);
  }
  return {
    ...item,
    value: item.value == null ? null : normalizeFiniteNumber(item.value, 'value'),
    changeRate: item.changeRate == null ? null : normalizeFiniteNumber(item.changeRate, 'changeRate'),
    compareText: item.compareText == null ? null : String(item.compareText),
  };
}

function normalizeTrendPoint(item: DashboardTrendPoint): DashboardTrendPoint {
  return {
    ...item,
    salesAmount: normalizeMoneyNumber(item.salesAmount, 'salesAmount', false, useMockApi)!,
    purchaseAmount: normalizeMoneyNumber(item.purchaseAmount, 'purchaseAmount', false, useMockApi)!,
    grossMarginAmount: normalizeMoneyNumber(item.grossMarginAmount, 'grossMarginAmount', false, useMockApi, true)!,
  };
}

function normalizeTodo(item: DashboardTodoItem): DashboardTodoItem {
  const businessType = String(item.businessType || 'SYSTEM');
  const priority = item.priority === 'HIGH' || item.priority === 'LOW' ? item.priority : 'MEDIUM';
  const completionMode = item.completionMode === 'TRACKED' ? 'TRACKED' : 'AUTO';
  const detail = normalizeTodoDetail(item.detail);
  if (detail === null) {
    // 服务端 detail 是正式必填字段；兼容旧实例时保留待办主信息，避免一条脏数据拖垮工作台。
    console.warn('[dashboard] todo detail is unavailable', { todoId: item.todoId });
  }
  return {
    ...item,
    todoId: normalizeStringId(item.todoId, 'todoId'),
    businessType,
    businessLabel: String(item.businessLabel || defaultTodoLabels[businessType] || businessType || '其他'),
    title: String(item.title || ''),
    description: String(item.description || ''),
    count: normalizeFiniteNumber(item.count, 'count'),
    priority,
    sortWeight: normalizeFiniteNumber(item.sortWeight, 'sortWeight'),
    completionMode,
    resolveHint: item.resolveHint ? String(item.resolveHint) : null,
    detail,
  };
}

function normalizeTodoDetail(value: unknown): DashboardTodoItem['detail'] {
  const raw = value as { model?: unknown; items?: unknown } | null;
  if (!raw || !Array.isArray(raw.items)) return null;
  const items = raw.items as unknown[];
  const documentModels = ['PURCHASE_ORDER_APPROVAL', 'SALES_ORDER_APPROVAL', 'PURCHASE_RETURN_APPROVAL', 'SALES_RETURN_APPROVAL', 'INBOUND_CONFIRM', 'OUTBOUND_CONFIRM'] as const;
  if (documentModels.includes(raw.model as typeof documentModels[number])) {
    return {
      model: raw.model as typeof documentModels[number],
      items: items.map(item => {
        const detailItem = normalizeTodoDetailItem(item);
        return {
          documentNo: normalizeStringId(detailItem.documentNo, 'detail.items.documentNo'),
          sourceDocumentNo: normalizeNullableStringId(detailItem.sourceDocumentNo, 'detail.items.sourceDocumentNo'),
          counterpartyName: detailItem.counterpartyName == null ? null : String(detailItem.counterpartyName),
          amountFen: detailItem.amountFen == null ? null : normalizeFiniteNumber(detailItem.amountFen, 'detail.items.amountFen'),
          waitHours: detailItem.waitHours == null ? null : normalizeFiniteNumber(detailItem.waitHours, 'detail.items.waitHours'),
          waitLevel: detailItem.waitLevel === 'NORMAL' || detailItem.waitLevel === 'WARNING' || detailItem.waitLevel === 'OVERDUE' ? detailItem.waitLevel : null,
          documentStatus: String(detailItem.documentStatus || ''),
        };
      }),
    };
  }
  if (raw.model === 'STOCK_RISK_REVIEW') {
    return {
      model: 'STOCK_RISK_REVIEW',
      items: items.map(item => {
        const detailItem = normalizeTodoDetailItem(item);
        return {
          id: normalizeStringId(detailItem.id, 'detail.items.id'), productCode: String(detailItem.productCode || ''), productName: String(detailItem.productName || ''),
          warehouseName: String(detailItem.warehouseName || ''), unitName: String(detailItem.unitName || ''),
          availableQty: normalizeFiniteNumber(detailItem.availableQty, 'detail.items.availableQty'), safetyStockQty: normalizeFiniteNumber(detailItem.safetyStockQty, 'detail.items.safetyStockQty'),
          suggestedPurchaseQty: normalizeFiniteNumber(detailItem.suggestedPurchaseQty, 'detail.items.suggestedPurchaseQty'), severity: detailItem.severity === 'MEDIUM' ? 'MEDIUM' : 'HIGH',
        };
      }),
    };
  }
  if (raw.model === 'SYSTEM_EXCEPTION') {
    return {
      model: 'SYSTEM_EXCEPTION',
      items: items.map(item => {
        const detailItem = normalizeTodoDetailItem(item);
        return {
          id: normalizeStringId(detailItem.id, 'detail.items.id'), exceptionNo: normalizeStringId(detailItem.exceptionNo, 'detail.items.exceptionNo'),
          summary: String(detailItem.summary || ''), exceptionType: String(detailItem.exceptionType || ''), sourceModule: String(detailItem.sourceModule || ''),
          occurredAt: normalizeNullableStringId(detailItem.occurredAt, 'detail.items.occurredAt'), severity: detailItem.severity === 'HIGH' || detailItem.severity === 'LOW' ? detailItem.severity : 'MEDIUM',
        };
      }),
    };
  }
  return null;
}

function normalizeTodoDetailItem(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('dashboard todo detail item is invalid');
  }
  return value as Record<string, unknown>;
}
function normalizeStockAlert(item: DashboardStockAlert): DashboardStockAlert {
  if (item.severity !== 'OUT_OF_STOCK' && item.severity !== 'NO_AVAILABLE' && item.severity !== 'LOW_STOCK') {
    throw new Error(`dashboard stock alert severity is invalid: ${String(item.severity)}`);
  }
  return {
    ...item,
    stockId: normalizeStringId(item.stockId, 'stockId'),
    productId: normalizeStringId(item.productId, 'productId'),
    warehouseId: normalizeStringId(item.warehouseId, 'warehouseId'),
    availableQty: normalizeFiniteNumber(item.availableQty, 'availableQty'),
    safetyStockQty: normalizeFiniteNumber(item.safetyStockQty, 'safetyStockQty'),
    suggestedPurchaseQty: normalizeFiniteNumber(item.suggestedPurchaseQty, 'suggestedPurchaseQty'),
    latestOutboundAt: normalizeNullableStringId(item.latestOutboundAt, 'latestOutboundAt'),
  };
}

function normalizeOrderStage(item: DashboardOrderStage): DashboardOrderStage {
  return {
    ...item,
    purchaseCount: normalizeFiniteNumber(item.purchaseCount, 'purchaseCount'),
    salesCount: normalizeFiniteNumber(item.salesCount, 'salesCount'),
  };
}

function normalizeOrderStagePeriod(value: unknown): DashboardOrderStagePeriod {
  const raw = value as Partial<DashboardOrderStagePeriod> | null;
  if (raw?.type !== 'CURRENT_CALENDAR_MONTH' || typeof raw.startAt !== 'string' || typeof raw.endAtExclusive !== 'string') {
    throw new Error('dashboard orderStagePeriod is invalid');
  }
  return {
    type: raw.type,
    startAt: raw.startAt,
    endAtExclusive: raw.endAtExclusive,
  };
}

function normalizeTopProduct(item: DashboardTopProduct): DashboardTopProduct {
  return {
    ...item,
    productId: normalizeStringId(item.productId, 'productId'),
    salesAmount: normalizeMoneyNumber(item.salesAmount, 'salesAmount', false, useMockApi)!,
    salesQty: normalizeFiniteNumber(item.salesQty, 'salesQty'),
    availableQty: normalizeFiniteNumber(item.availableQty, 'availableQty'),
  };
}

function normalizeSupplierPerformance(item: DashboardSupplierPerformance): DashboardSupplierPerformance {
  return {
    ...item,
    supplierId: normalizeStringId(item.supplierId, 'supplierId'),
    deliveryScore: normalizeFiniteNumber(item.deliveryScore, 'deliveryScore'),
    qualityScore: normalizeFiniteNumber(item.qualityScore, 'qualityScore'),
    onTimeRate: normalizeFiniteNumber(item.onTimeRate, 'onTimeRate'),
  };
}

function normalizeTrendPermissions(value: unknown): DashboardTrendPermissions {
  const raw = (value ?? null) as Partial<DashboardTrendPermissions> | null;
  // 旧版本接口不返回该字段时回退为全 true，向后兼容
  return {
    canViewSales: typeof raw?.canViewSales === 'boolean' ? raw.canViewSales : true,
    canViewPurchase: typeof raw?.canViewPurchase === 'boolean' ? raw.canViewPurchase : true,
    canViewGross: typeof raw?.canViewGross === 'boolean' ? raw.canViewGross : true,
  };
}

function normalizeOrderStagePermissions(value: unknown): DashboardOrderStagePermissions {
  const raw = (value ?? null) as Partial<DashboardOrderStagePermissions> | null;
  return {
    canViewPurchase: typeof raw?.canViewPurchase === 'boolean' ? raw.canViewPurchase : true,
    canViewSales: typeof raw?.canViewSales === 'boolean' ? raw.canViewSales : true,
  };
}

function normalizeSectionAccess(value: unknown, field: string): DashboardSectionAccess {
  const raw = value as Partial<DashboardSectionAccess> | null;
  const state = raw?.state;
  if (state !== 'ALLOWED' && state !== 'EMPTY' && state !== 'DENIED') {
    throw new Error(`dashboard access.${field}.state is invalid`);
  }
  return {
    state: state as DashboardAccessState,
  };
}

function normalizeOverviewAccess(value: unknown): DashboardOverviewAccess {
  const raw = value as Partial<DashboardOverviewAccess> | null;
  if (!raw || !raw.metrics) {
    throw new Error('dashboard access is required');
  }
  return {
    metrics: {
      MONTH_SALES: normalizeSectionAccess(raw.metrics.MONTH_SALES, 'metrics.MONTH_SALES'),
      MONTH_GROSS_PROFIT: normalizeSectionAccess(raw.metrics.MONTH_GROSS_PROFIT, 'metrics.MONTH_GROSS_PROFIT'),
      PENDING_ORDERS: normalizeSectionAccess(raw.metrics.PENDING_ORDERS, 'metrics.PENDING_ORDERS'),
      STOCK_RISK_SKU: normalizeSectionAccess(raw.metrics.STOCK_RISK_SKU, 'metrics.STOCK_RISK_SKU'),
    },
    todos: normalizeSectionAccess(raw.todos, 'todos'),
    stockAlerts: normalizeSectionAccess(raw.stockAlerts, 'stockAlerts'),
    orderStages: normalizeSectionAccess(raw.orderStages, 'orderStages'),
    topProducts: normalizeSectionAccess(raw.topProducts, 'topProducts'),
    supplierPerformance: normalizeSectionAccess(raw.supplierPerformance, 'supplierPerformance'),
  };
}
function normalizeOverview(data: DashboardOverview): DashboardOverview {
  const todos = data.todos.map(normalizeTodo);
  // 后端已写入 pendingCount 时直接采用；mock 或旧服务缺失该字段时，前端按裁剪后的 todos 累加兜底。
  const rawPendingCount = (data as { pendingCount?: unknown }).pendingCount;
  const pendingCount = rawPendingCount === undefined || rawPendingCount === null
    ? todos.reduce((sum, item) => sum + item.count, 0)
    : normalizeFiniteNumber(rawPendingCount, 'pendingCount');
  return {
    refreshedAt: String(data.refreshedAt),
    pendingCount,
    access: normalizeOverviewAccess(data.access),
    metrics: data.metrics.map(normalizeMetric),
    trend: data.trend.map(normalizeTrendPoint),
    trendPermissions: normalizeTrendPermissions(data.trendPermissions),
    todos,
    stockAlerts: data.stockAlerts.map(normalizeStockAlert),
    orderStages: data.orderStages.map(normalizeOrderStage),
    orderStagePeriod: normalizeOrderStagePeriod(data.orderStagePeriod),
    orderStagePermissions: normalizeOrderStagePermissions(data.orderStagePermissions),
    topProducts: data.topProducts.map(normalizeTopProduct),
    supplierPerformance: data.supplierPerformance.map(normalizeSupplierPerformance),
  };
}

export async function getDashboardOverview() {
  if (useMockApi) {
    await new Promise(resolve => window.setTimeout(resolve, 220));
    return normalizeOverview(mockOverview);
  }

  return getResult<DashboardOverview>('/dashboard/overview').then(normalizeOverview);
}

function normalizeInventoryRiskPreviewItem(item: DashboardInventoryRiskPreviewItem): DashboardInventoryRiskPreviewItem {
  if (item.severity !== 'OUT_OF_STOCK' && item.severity !== 'NO_AVAILABLE' && item.severity !== 'LOW_STOCK') {
    throw new Error(`inventory risk preview severity is invalid: ${String(item.severity)}`);
  }
  return {
    ...item,
    stockId: normalizeStringId(item.stockId, 'riskPreview.items.stockId'),
    quantityPrecision: assertQuantityPrecision(item.quantityPrecision, 'riskPreview.items.quantityPrecision'),
    availableQty: normalizeFiniteNumber(item.availableQty, 'riskPreview.items.availableQty'),
    safetyStockQty: normalizeFiniteNumber(item.safetyStockQty, 'riskPreview.items.safetyStockQty'),
  };
}

function normalizeInventoryStatus(data: DashboardInventoryStatus): DashboardInventoryStatus {
  const statuses = ['NORMAL', 'LOW_STOCK', 'NO_AVAILABLE', 'OUT_OF_STOCK'] as const;
  const access = normalizeSectionAccess(data.access, 'inventoryStatus');
  // 无仓储权限时后端刻意不返回分布和风险明细，前端不能把它误判为接口损坏。
  if (access.state === 'DENIED') {
    return {
      distribution: [],
      riskPreview: { items: [], hasMore: false },
      access,
    };
  }
  if (!Array.isArray(data.distribution) || data.distribution.length !== statuses.length) throw new Error('inventory distribution is invalid');
  const distribution = new Map<typeof statuses[number], number>();
  for (const item of data.distribution) {
    if (!statuses.includes(item.status)) throw new Error('inventory distribution status is invalid');
    const recordCount = normalizeFiniteNumber(item.recordCount, 'distribution.recordCount');
    if (!Number.isInteger(recordCount) || recordCount < 0 || distribution.has(item.status)) throw new Error('inventory distribution recordCount is invalid');
    distribution.set(item.status, recordCount);
  }
  if (!data.riskPreview || !Array.isArray(data.riskPreview.items) || data.riskPreview.items.length > 5) throw new Error('inventory risk preview is invalid');
  return {
    distribution: statuses.map(status => ({ status, recordCount: distribution.get(status) ?? 0 })),
    riskPreview: {
      items: data.riskPreview.items.map(normalizeInventoryRiskPreviewItem),
      hasMore: Boolean(data.riskPreview.hasMore),
    },
    access,
  };
}

export async function getDashboardInventoryStatus(warehouseId?: string) {
  if (useMockApi) {
    await new Promise(resolve => window.setTimeout(resolve, 120));
    return normalizeInventoryStatus(mockInventoryStatus);
  }

  return getResult<DashboardInventoryStatus>('/dashboard/inventory-status', warehouseId ? { warehouseId } : undefined, {
    skipPageLoading: true,
    suppressErrorToast: true,
  }).then(normalizeInventoryStatus);
}