import { getResult } from '@/api/http';
import { normalizeFiniteNumber, normalizeNullableStringId, normalizeStringId } from '@/shared/utils/api-normalizers';
import type {
  DashboardMetric,
  DashboardNotificationPopover,
  DashboardOrderStage,
  DashboardOverview,
  DashboardStockAlert,
  DashboardSupplierPerformance,
  DashboardTodoItem,
  DashboardTopProduct,
  DashboardTrendPoint,
} from './types';

const useMockApi = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK_API === 'true';

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
  metrics: [
    { label: '今日销售额', value: 286430, unit: '元', changeRate: 12.8, compareText: '较昨日', status: 'good' },
    { label: '本月毛利额', value: 842600, unit: '元', changeRate: 6.4, compareText: '较上月同期', status: 'good' },
    { label: '待处理订单', value: 7, unit: '单', changeRate: -8.1, compareText: '较昨日', status: 'watch' },
    { label: '库存风险 SKU', value: 11, unit: '个', changeRate: 18.6, compareText: '较昨日', status: 'risk' },
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
    { date: '06-30', salesAmount: 286430, purchaseAmount: 172600, grossMarginAmount: 92100 },
  ],
  todos: [
    { todoId: 'todo-system-exceptions', businessType: 'SYSTEM_EXCEPTION', businessLabel: '系统', title: '系统异常', description: '当前有 4 条系统异常记录需要关注，主要来自 AI/MCP 工具调用、消息队列死信、第三方回调和定时任务失败。', count: 4, priority: 'HIGH', sortWeight: 10, sourceMode: 'PERSISTED', completionMode: 'TRACKED', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: 'system_exception', occurredAt: '2026-07-01 09:20:00', resolveHint: null, evidence: [
      { itemId: 'AI-MCP-20260701-001', primaryText: 'AI-MCP-20260701-001', secondaryText: 'AI 模块调用库存预测 MCP 工具超时，未生成补货建议，等待后台重试或检查工具连接。', metrics: [{ label: '类型', value: 'MCP超时', tone: 'risk' }, { label: '来源', value: 'AI助手', tone: 'neutral' }, { label: '时间', value: '09:18', tone: 'watch' }] },
      { itemId: 'DLQ-ORDER-STOCK-00023', primaryText: 'DLQ-ORDER-STOCK-00023', secondaryText: '订单审核后生成出库任务的消息进入死信队列，需由后台消费补偿后恢复流程。', metrics: [{ label: '类型', value: '死信队列', tone: 'risk' }, { label: '来源', value: '消息队列', tone: 'neutral' }, { label: '时间', value: '09:05', tone: 'watch' }] },
      { itemId: 'EXT-CALLBACK-20260701-006', primaryText: 'EXT-CALLBACK-20260701-006', secondaryText: '第三方物流回调连续超时，发货状态暂未同步，后台会按回调幂等键重试。', metrics: [{ label: '类型', value: '回调超时', tone: 'watch' }, { label: '来源', value: '物流接口', tone: 'neutral' }, { label: '时间', value: '08:42', tone: 'neutral' }] },
      { itemId: 'JOB-DASHBOARD-SNAPSHOT', primaryText: 'JOB-DASHBOARD-SNAPSHOT', secondaryText: '经营快照定时任务执行失败，本次趋势缓存沿用上一批次数据，等待下一次调度或人工重跑。', metrics: [{ label: '类型', value: '任务失败', tone: 'watch' }, { label: '来源', value: '定时任务', tone: 'neutral' }, { label: '时间', value: '07:30', tone: 'neutral' }] },
    ], route: '/dashboard' },
    { todoId: 'todo-purchase-approve', businessType: 'PURCHASE', businessLabel: '采购', title: '采购单待审核', description: '还有 2 张采购单需要审核，处理后会自动完成待办。', count: 2, priority: 'HIGH', sortWeight: 20, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往采购订单完成审核，审核通过或驳回后该待办自动更新。', evidence: [
      { itemId: 'po-202607004', primaryText: 'PO202607004', secondaryText: '谷仓食品批发 · 每日坚果混合装补货，待采购负责人审核', metrics: [{ label: '金额', value: '￥0.11万', tone: 'neutral' }, { label: '品项', value: '1', tone: 'neutral' }, { label: '等待', value: '3小时', tone: 'watch' }] },
      { itemId: 'po-202607005', primaryText: 'PO202607005', secondaryText: '华东饮品供应链 · 饮品与咖啡补货，待确认采购价格', metrics: [{ label: '金额', value: '￥0.08万', tone: 'neutral' }, { label: '品项', value: '2', tone: 'neutral' }, { label: '等待', value: '2小时', tone: 'neutral' }] },
    ], route: '/purchase/orders' },
    { todoId: 'todo-sales-approve', businessType: 'SALES', businessLabel: '销售', title: '销售单待审核', description: '还有 2 张销售单需要审核，处理后会自动完成待办。', count: 2, priority: 'HIGH', sortWeight: 21, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往销售订单完成审核，审核通过后进入库存锁定和发货准备。', evidence: [
      { itemId: 'so-202607004', primaryText: 'SO202607004', secondaryText: '杭州蓝湖办公采购 · 速溶黑咖啡订单，审核前需复核客户信用', metrics: [{ label: '金额', value: '￥0.07万', tone: 'watch' }, { label: '品项', value: '1', tone: 'neutral' }, { label: '等待', value: '1小时', tone: 'neutral' }] },
      { itemId: 'so-202607005', primaryText: 'SO202607005', secondaryText: '南京星火校园超市 · A4复印纸补货，待销售主管审核放行', metrics: [{ label: '金额', value: '￥0.1万', tone: 'neutral' }, { label: '品项', value: '1', tone: 'neutral' }, { label: '等待', value: '45分钟', tone: 'neutral' }] },
    ], route: '/sales/orders' },
    { todoId: 'todo-stock-risk-review', businessType: 'INVENTORY', businessLabel: '库存', title: '库存异常待复核', description: '还有 11 个 SKU 可用库存低于安全线或已无可用库存，需要复核补货或调拨。', count: 11, priority: 'HIGH', sortWeight: 30, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往库存余额查看低库存 SKU，补货计划生成或库存恢复后自动更新。', evidence: [
      { itemId: 'stock-P000043-W008', primaryText: 'USB-C扩展坞（P000043）', secondaryText: '南京备货仓 · 停用产品仅保留历史库存追溯，不生成补货建议', metrics: [{ label: '可用', value: '0 个', tone: 'risk' }, { label: '安全线', value: '4 个', tone: 'neutral' }, { label: '建议补货', value: '不适用', tone: 'neutral' }] },
      { itemId: 'stock-P000027-W002', primaryText: '热敏标签纸（P000027）', secondaryText: '华南中心仓 · 当前无可用库存，需补货或调拨', metrics: [{ label: '可用', value: '0 卷', tone: 'risk' }, { label: '安全线', value: '40 卷', tone: 'neutral' }, { label: '建议补货', value: '40 卷', tone: 'risk' }] },
    ], route: '/warehouse/stocks' },
    { todoId: 'todo-inbound', businessType: 'WAREHOUSE', businessLabel: '仓储', title: '待确认入库', description: '还有 2 张入库单等待仓库确认。', count: 2, priority: 'MEDIUM', sortWeight: 50, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往入库单完成确认，确认入库后该待办自动更新。', evidence: [
      { itemId: 'ib-202606130006', primaryText: 'IB202606130006', secondaryText: '关联 PO202606002 · 华北中心仓，等待仓库确认入库', metrics: [{ label: '品项', value: '2', tone: 'neutral' }, { label: '预计到货', value: '今日', tone: 'watch' }, { label: '等待', value: '2小时', tone: 'watch' }] },
      { itemId: 'ib-202606120009', primaryText: 'IB202606120009', secondaryText: '关联 PO202606003 · 武汉中转仓，待收货质检', metrics: [{ label: '品项', value: '1', tone: 'neutral' }, { label: '预计到货', value: '今日', tone: 'neutral' }, { label: '等待', value: '1小时', tone: 'neutral' }] },
    ], route: '/warehouse/inbound-bills' },
    { todoId: 'todo-outbound', businessType: 'WAREHOUSE', businessLabel: '仓储', title: '待确认出库', description: '还有 1 张出库单等待发货确认。', count: 1, priority: 'MEDIUM', sortWeight: 51, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往出库单完成确认，确认出库后该待办自动更新。', evidence: [
      { itemId: 'ob-202606100015', primaryText: 'OB202606100015', secondaryText: '关联 SO202606004 · 广州天河门店，待拣货复核', metrics: [{ label: '品项', value: '1', tone: 'neutral' }, { label: '计划发货', value: '今日', tone: 'watch' }, { label: '等待', value: '1小时', tone: 'neutral' }] },
    ], route: '/warehouse/outbound-bills' },
    { todoId: 'todo-credit-review', businessType: 'SALES', businessLabel: '销售', title: '客户信用待复核', description: '有 2 个客户的应收或授信占用触发风险提醒，需要确认是否继续放行订单。', count: 2, priority: 'MEDIUM', sortWeight: 61, sourceMode: 'AGGREGATED', completionMode: 'AUTO', status: 'PENDING', errorCode: null, errorMessage: null, sourceNo: null, occurredAt: null, resolveHint: '前往客户或销售订单核对应收、逾期和授信占用，复核完成后自动更新。', evidence: [
      { itemId: 'credit-C002', primaryText: '杭州蓝湖办公采购（C002）', secondaryText: 'SO202607004 · 授信占用偏高', metrics: [{ label: '授信额度', value: '￥9.0万', tone: 'neutral' }, { label: '已占用', value: '￥8.7万', tone: 'watch' }, { label: '逾期', value: '0天', tone: 'neutral' }] },
      { itemId: 'credit-C003', primaryText: '南京星火校园超市（C003）', secondaryText: 'SO202607005 · 账期客户', metrics: [{ label: '授信额度', value: '￥12.0万', tone: 'neutral' }, { label: '已占用', value: '￥11.6万', tone: 'watch' }, { label: '逾期', value: '3天', tone: 'watch' }] },
    ], route: '/sales/customers' },
  ],
  stockAlerts: [
    { stockId: '1940000000000000013', productId: '1920000000000000043', productCode: 'P000043', productName: 'USB-C扩展坞', warehouseId: '1930000000000000008', warehouseName: '南京备货仓', unitName: '个', availableQty: 0, safetyStockQty: 4, suggestedPurchaseQty: 0, severity: 'HIGH', latestOutboundAt: '2026-06-13 11:55:00' },
    { stockId: '1940000000000000005', productId: '1920000000000000027', productCode: 'P000027', productName: '热敏标签纸', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', unitName: '卷', availableQty: 0, safetyStockQty: 40, suggestedPurchaseQty: 40, severity: 'HIGH', latestOutboundAt: '2026-06-14 08:40:00' },
    { stockId: '1940000000000000012', productId: '1920000000000000044', productCode: 'P000044', productName: '无线办公鼠标', warehouseId: '1930000000000000007', warehouseName: '杭州电商仓', unitName: '个', availableQty: 0, safetyStockQty: 8, suggestedPurchaseQty: 0, severity: 'HIGH', latestOutboundAt: '2026-06-13 13:10:00' },
    { stockId: '1931000000000000002', productId: '1920000000000000002', productCode: 'P000002', productName: '速溶黑咖啡', warehouseId: '1930000000000000001', warehouseName: '华东中心仓', unitName: '盒', availableQty: 7, safetyStockQty: 8, suggestedPurchaseQty: 12, severity: 'MEDIUM', latestOutboundAt: '2026-07-01 10:05:00' },
    { stockId: '1940000000000000004', productId: '1920000000000000026', productCode: 'P000026', productName: 'A4复印纸', warehouseId: '1930000000000000002', warehouseName: '华南中心仓', unitName: '箱', availableQty: 10, safetyStockQty: 15, suggestedPurchaseQty: 20, severity: 'MEDIUM', latestOutboundAt: '2026-06-14 08:45:00' },
    { stockId: '1940000000000000006', productId: '1920000000000000033', productCode: 'P000033', productName: '浓缩洗衣液', warehouseId: '1930000000000000003', warehouseName: '华北中心仓', unitName: '瓶', availableQty: 9, safetyStockQty: 10, suggestedPurchaseQty: 15, severity: 'MEDIUM', latestOutboundAt: '2026-06-13 17:25:00' },
    { stockId: '1940000000000000008', productId: '1920000000000000037', productCode: 'P000037', productName: '加厚垃圾袋', warehouseId: '1930000000000000004', warehouseName: '西南中心仓', unitName: '卷', availableQty: 20, safetyStockQty: 25, suggestedPurchaseQty: 30, severity: 'MEDIUM', latestOutboundAt: '2026-06-13 16:45:00' },
    { stockId: '1940000000000000010', productId: '1920000000000000038', productCode: 'P000038', productName: '无痕粘钩', warehouseId: '1930000000000000005', warehouseName: '武汉中转仓', unitName: '卡', availableQty: 10, safetyStockQty: 15, suggestedPurchaseQty: 20, severity: 'MEDIUM', latestOutboundAt: '2026-06-13 15:18:00' },
  ],
  orderStages: [
    { stage: '草稿', purchaseCount: 1, salesCount: 1 },
    { stage: '待审核', purchaseCount: 2, salesCount: 2 },
    { stage: '已审核', purchaseCount: 3, salesCount: 2 },
    { stage: '部分出入库', purchaseCount: 1, salesCount: 1 },
    { stage: '已完成', purchaseCount: 2, salesCount: 4 },
  ],
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

function normalizeMetric(item: DashboardMetric): DashboardMetric {
  return {
    ...item,
    value: normalizeFiniteNumber(item.value, 'value'),
    changeRate: normalizeFiniteNumber(item.changeRate, 'changeRate'),
  };
}

function normalizeTrendPoint(item: DashboardTrendPoint): DashboardTrendPoint {
  return {
    ...item,
    salesAmount: normalizeFiniteNumber(item.salesAmount, 'salesAmount'),
    purchaseAmount: normalizeFiniteNumber(item.purchaseAmount, 'purchaseAmount'),
    grossMarginAmount: normalizeFiniteNumber(item.grossMarginAmount, 'grossMarginAmount'),
  };
}

function normalizeTodo(item: DashboardTodoItem): DashboardTodoItem {
  const businessType = String(item.businessType || 'SYSTEM');
  const sourceMode = item.sourceMode === 'PERSISTED' ? 'PERSISTED' : 'AGGREGATED';
  const completionMode = item.completionMode === 'TRACKED' ? 'TRACKED' : 'AUTO';
  const status = item.status === 'DONE' || item.status === 'IGNORED' ? item.status : 'PENDING';
  const priorityFallback = item.priority === 'HIGH' ? 100 : item.priority === 'MEDIUM' ? 200 : 300;
  const sourceFallback = completionMode === 'TRACKED' ? 0 : 20;
  return {
    ...item,
    todoId: normalizeStringId(item.todoId, 'todoId'),
    businessType,
    businessLabel: String(item.businessLabel || defaultTodoLabels[businessType] || businessType || '其他'),
    title: String(item.title || ''),
    description: String(item.description || ''),
    count: normalizeFiniteNumber(item.count, 'count'),
    sortWeight: normalizeFiniteNumber(item.sortWeight ?? priorityFallback + sourceFallback, 'sortWeight'),
    sourceMode,
    completionMode,
    status,
    errorCode: item.errorCode ? String(item.errorCode) : null,
    errorMessage: item.errorMessage ? String(item.errorMessage) : null,
    sourceNo: normalizeNullableStringId(item.sourceNo, 'sourceNo'),
    occurredAt: normalizeNullableStringId(item.occurredAt, 'occurredAt'),
    resolveHint: item.resolveHint ? String(item.resolveHint) : null,
    evidence: Array.isArray(item.evidence)
      ? item.evidence.map(evidence => ({
        itemId: normalizeStringId(evidence.itemId, 'itemId'),
        primaryText: String(evidence.primaryText || ''),
        secondaryText: String(evidence.secondaryText || ''),
        metrics: Array.isArray(evidence.metrics)
          ? evidence.metrics.map(metric => ({
            label: String(metric.label || ''),
            value: String(metric.value || ''),
            tone: metric.tone === 'risk' || metric.tone === 'watch' ? metric.tone : 'neutral',
          }))
          : [],
      }))
      : [],
    route: String(item.route || '/dashboard'),
  };
}

function normalizeStockAlert(item: DashboardStockAlert): DashboardStockAlert {
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

function normalizeTopProduct(item: DashboardTopProduct): DashboardTopProduct {
  return {
    ...item,
    productId: normalizeStringId(item.productId, 'productId'),
    salesAmount: normalizeFiniteNumber(item.salesAmount, 'salesAmount'),
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

function normalizeOverview(data: DashboardOverview): DashboardOverview {
  return {
    refreshedAt: String(data.refreshedAt),
    metrics: data.metrics.map(normalizeMetric),
    trend: data.trend.map(normalizeTrendPoint),
    todos: data.todos.map(normalizeTodo),
    stockAlerts: data.stockAlerts.map(normalizeStockAlert),
    orderStages: data.orderStages.map(normalizeOrderStage),
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

function normalizeNotificationPopover(data: DashboardNotificationPopover): DashboardNotificationPopover {
  return {
    refreshedAt: String(data.refreshedAt || ''),
    pendingCount: normalizeFiniteNumber(data.pendingCount, 'pendingCount'),
    highPriorityCount: normalizeFiniteNumber(data.highPriorityCount, 'highPriorityCount'),
    hasMore: Boolean(data.hasMore),
    items: Array.isArray(data.items) ? data.items.slice(0, 8).map(normalizeTodo) : [],
  };
}

export async function getDashboardNotifications() {
  if (useMockApi) {
    await new Promise(resolve => window.setTimeout(resolve, 180));
    const pendingItems = mockOverview.todos
      .filter(item => item.status === 'PENDING')
      .sort((left, right) => left.sortWeight - right.sortWeight);
    return normalizeNotificationPopover({
      refreshedAt: mockOverview.refreshedAt,
      pendingCount: pendingItems.reduce((total, item) => total + item.count, 0),
      highPriorityCount: pendingItems
        .filter(item => item.priority === 'HIGH')
        .reduce((total, item) => total + item.count, 0),
      hasMore: pendingItems.length > 8,
      items: pendingItems.slice(0, 8),
    });
  }

  return getResult<DashboardNotificationPopover>('/dashboard/notifications').then(normalizeNotificationPopover);
}
