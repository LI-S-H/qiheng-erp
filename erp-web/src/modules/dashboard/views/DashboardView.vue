<script setup lang="ts">
import '../dashboard-pilot.css';
import DashboardTrendChart from '../components/DashboardTrendChart.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import { TrendingUp, MessageSquare, FileText, Info, X } from 'lucide-vue-next';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router';
import { toast } from 'vue-sonner';
import {
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  Boxes,
  ChevronRight,
  ClipboardCheck,
  PackageSearch,
  RefreshCw,
  Route,
  ShieldCheck,
  ShoppingCart,
  Truck,
} from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import DashboardEmptyPanel from '@/components/dashboard/DashboardEmptyPanel.vue';
import DashboardInventoryStatusPanel from '../components/DashboardInventoryStatusPanel.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { useDashboardOverviewStore } from '@/stores/dashboardOverviewStore';
import type {
  DashboardAccessState,
  DashboardMetric,
  DashboardOrderStagePermissions,
  DashboardOverview,
  DashboardTodoItem,
  DashboardTodoDocumentItem,
  DashboardTodoStockRiskItem,
  DashboardTodoSystemExceptionItem,
  DashboardTrendPermissions,
  DashboardSupplierPerformance,
} from '../types';

type DetailType = 'todos' | 'products' | 'suppliers';

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const overviewStore = useDashboardOverviewStore();
const loading = ref(false);
const overview = computed<DashboardOverview | null>(() => overviewStore.overview);
const activeDetail = ref<DetailType | null>(null);
const selectedTodoDetailId = ref<string | null>(null);
const trendDayOptions = [7, 15, 30] as const;
const selectedTrendDays = ref<(typeof trendDayOptions)[number]>(7);
const trendTransitioning = ref(false);
let trendAnimationTimer: number | null = null;
const trendSeries = [
  { key: 'salesAmount', label: '销售额', className: 'dashboard-trend--sales', colorClass: 'bg-blue-600' },
  { key: 'purchaseAmount', label: '采购额', className: 'dashboard-trend--purchase', colorClass: 'bg-amber-500' },
  { key: 'grossMarginAmount', label: '毛利额', className: 'dashboard-trend--margin', colorClass: 'bg-emerald-600' },
] as const;

const defaultTrendPermissions: DashboardTrendPermissions = {
  canViewSales: true,
  canViewPurchase: true,
  canViewGross: true,
};

const defaultOrderStagePermissions: DashboardOrderStagePermissions = {
  canViewPurchase: true,
  canViewSales: true,
};

const trendPermissions = computed<DashboardTrendPermissions>(() =>
  overview.value?.trendPermissions ?? defaultTrendPermissions,
);

const orderStagePermissions = computed<DashboardOrderStagePermissions>(() =>
  overview.value?.orderStagePermissions ?? defaultOrderStagePermissions,
);

const canViewAnyTrend = computed(() => {
  const perm = trendPermissions.value;
  return perm.canViewSales || perm.canViewPurchase;
});

const trendAccessState = computed<DashboardAccessState>(() => {
  if (!canViewAnyTrend.value) return 'DENIED';
  return displayedTrend.value.length ? 'ALLOWED' : 'EMPTY';
});

// access 是后端完成权限裁剪后的唯一状态来源；维度权限只用于 ALLOWED 时决定图例与列的展示。
const orderStagesAccessState = computed<DashboardAccessState>(() => overview.value?.access.orderStages.state ?? 'EMPTY');

const todosAccessState = computed<DashboardAccessState>(() => overview.value?.access.todos.state ?? 'EMPTY');
const topProductsAccessState = computed<DashboardAccessState>(() => overview.value?.access.topProducts.state ?? 'EMPTY');
const supplierPerformanceAccessState = computed<DashboardAccessState>(() => overview.value?.access.supplierPerformance.state ?? 'EMPTY');

function emptyPanelState(state: DashboardAccessState): 'EMPTY' | 'DENIED' {
  return state === 'DENIED' ? 'DENIED' : 'EMPTY';
}

const orderStagePeriodDescription = computed(() => {
  const period = overview.value?.orderStagePeriod;
  return period?.type === 'CURRENT_CALENDAR_MONTH'
    ? '当月新建单据按当前状态分布'
    : '当前统计期间新建单据按当前状态分布';
});

const visibleTrendSeries = computed(() => {
  const perm = trendPermissions.value;
  return trendSeries.filter(series => {
    if (series.key === 'salesAmount') return perm.canViewSales;
    if (series.key === 'purchaseAmount') return perm.canViewPurchase;
    return perm.canViewGross;
  });
});

const displayedTrend = computed(() => overview.value?.trend.slice(-selectedTrendDays.value) || []);

const trendSummary = computed(() => {
  const totals = displayedTrend.value.reduce((result, item) => ({
    salesAmount: result.salesAmount + item.salesAmount,
    purchaseAmount: result.purchaseAmount + item.purchaseAmount,
    grossMarginAmount: result.grossMarginAmount + item.grossMarginAmount,
  }), { salesAmount: 0, purchaseAmount: 0, grossMarginAmount: 0 });
  const grossMarginRate = totals.salesAmount > 0
    ? (totals.grossMarginAmount / totals.salesAmount) * 100
    : null;
  const perm = trendPermissions.value;

  const items: Array<{
    key: string;
    label: string;
    value: string;
    hint: string;
    tone: string;
    className: string;
  }> = [];
  if (perm.canViewSales) {
    items.push({
      key: 'sales',
      label: `${selectedTrendDays.value}日销售合计`,
      value: formatCurrency(totals.salesAmount),
      hint: '销售收入累计',
      tone: 'sales',
      className: valueTone(totals.salesAmount),
    });
  }
  if (perm.canViewPurchase) {
    items.push({
      key: 'purchase',
      label: '采购合计',
      value: formatCurrency(totals.purchaseAmount),
      hint: '采购支出累计',
      tone: 'purchase',
      className: valueTone(totals.purchaseAmount),
    });
  }
  if (perm.canViewGross) {
    items.push({
      key: 'margin',
      label: '毛利合计',
      value: formatCurrency(totals.grossMarginAmount),
      hint: totals.grossMarginAmount < 0 ? '当前区间亏损' : '当前区间盈利',
      tone: 'margin',
      className: valueTone(totals.grossMarginAmount),
    });
    items.push({
      key: 'rate',
      label: '区间毛利率',
      value: grossMarginRate === null ? '—' : `${grossMarginRate.toFixed(1)}%`,
      hint: '毛利 ÷ 销售额',
      tone: 'rate',
      className: grossMarginRate === null ? 'is-neutral' : valueTone(grossMarginRate),
    });
  }
  return items;
});

const maxTopProductAmount = computed(() => Math.max(...(overview.value?.topProducts.map(item => item.salesAmount) || [0]), 1));
const sortedTodos = computed(() => {
  const priorityRank: Record<DashboardTodoItem['priority'], number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
  return [...(overview.value?.todos || [])].sort((left, right) => {
    const weightDiff = left.sortWeight - right.sortWeight;
    if (weightDiff !== 0) return weightDiff;
    const priorityDiff = priorityRank[left.priority] - priorityRank[right.priority];
    if (priorityDiff !== 0) return priorityDiff;
    return left.title.localeCompare(right.title, 'zh-CN');
  });
});

const selectedDetailTodo = computed(() => sortedTodos.value.find(todo => todo.todoId === selectedTodoDetailId.value) || null);
const rankedProducts = computed(() => topProductsAccessState.value === 'ALLOWED' ? [...(overview.value?.topProducts || [])].sort((a, b) => b.salesAmount - a.salesAmount) : []);
const visibleTopProducts = computed(() => rankedProducts.value.slice(0, 5));
const rankedSuppliers = computed(() => supplierPerformanceAccessState.value === 'ALLOWED' ? [...(overview.value?.supplierPerformance || [])].sort((a, b) => (b.scoreStatus === 'READY' ? b.overallScore ?? -1 : -1) - (a.scoreStatus === 'READY' ? a.overallScore ?? -1 : -1)) : []);
const visibleSupplierPerformance = computed(() => rankedSuppliers.value.slice(0, 5));
const detailTitle = computed(() => {
  if (activeDetail.value === 'todos' && selectedDetailTodo.value) return `${selectedDetailTodo.value.title}详情`;

  const titles: Record<DetailType, string> = {
    todos: '业务待办详情',
    products: '销售商品排行详情',
    suppliers: '供应商履约详情',
  };
  return activeDetail.value ? titles[activeDetail.value] : '';
});

const detailDescription = computed(() => {
  if (activeDetail.value === 'todos' && selectedDetailTodo.value) {
    return selectedDetailTodo.value.description;
  }

  const descriptions: Record<DetailType, string> = {
    todos: '展示当前用户可见的全部工作台待办。单据状态类待办随业务完成自动消失，系统异常来自数据库记录表，工作台只展示详情与处理建议。',
    products: '展示近 30 日销售额完整排行，主页面默认显示前 5 名。',
    suppliers: '展示核心供应商履约完整排行，主页面默认显示前 5 名。',
  };
  return activeDetail.value ? descriptions[activeDetail.value] : '';
});

async function bootstrapOverview() {
  loading.value = true;
  try {
    await overviewStore.refresh(true);
    openTodoFromRoute();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '工作台数据加载失败');
  } finally {
    loading.value = false;
  }
}

function openTodoFromRoute() {
  const todoId = typeof route.query.todoId === 'string' ? route.query.todoId : '';
  if (!todoId || !overview.value) return;
  if (!overview.value.todos.some(todo => todo.todoId === todoId)) {
    toast.warning('该待办已处理、不可查看或不存在');
    void router.replace({ query: { ...route.query, todoId: undefined } });
    return;
  }
  openDetail('todos', todoId);
}

function formatCurrency(value: number) {
  if (Math.abs(value) >= 10000) return `￥${(value / 10000).toFixed(1)}万`;
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function formatDetailedCurrency(value: number) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(value);
}

function valueTone(value: number) {
  if (value > 0) return 'is-positive';
  if (value < 0) return 'is-negative';
  return 'is-neutral';
}

function metricBarWidth(value: number | null) {
  const numberValue = value ?? Number.NaN;
  if (!Number.isFinite(numberValue)) return '0%';
  return `${Math.min(100, Math.max(0, numberValue))}%`;
}

function formatSupplierMetric(value: number | null, suffix = '') {
  return value == null ? '—' : `${value.toFixed(1)}${suffix}`;
}

function selectTrendDays(days: (typeof trendDayOptions)[number]) {
  if (days === selectedTrendDays.value) return;
  if (trendAnimationTimer) window.clearTimeout(trendAnimationTimer);

  selectedTrendDays.value = days;
  trendTransitioning.value = false;
  window.requestAnimationFrame(() => {
    trendTransitioning.value = true;
  });
  trendAnimationTimer = window.setTimeout(() => {
    trendTransitioning.value = false;
    trendAnimationTimer = null;
  }, 240);
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value);
}

function metricDisplay(metric: DashboardMetric) {
  if (overview.value?.access.metrics[metric.key].state !== 'ALLOWED' || metric.value === null) return '—';
  return metric.unit === '元' ? formatCurrency(metric.value) : `${formatNumber(metric.value)}${metric.unit}`;
}

function metricCardTone(metric: DashboardMetric) {
  const tones: Record<DashboardMetric['key'], 'info' | 'good' | 'watch' | 'risk'> = {
    MONTH_SALES: 'info',
    MONTH_GROSS_PROFIT: 'good',
    PENDING_ORDERS: 'watch',
    STOCK_RISK_SKU: 'risk',
  };
  return tones[metric.key];
}
function todoIcon(todo: DashboardTodoItem) {
  const icons: Record<string, typeof ShoppingCart> = {
    PURCHASE: ShoppingCart,
    SALES: ClipboardCheck,
    WAREHOUSE: Boxes,
    INVENTORY: Boxes,
    SYSTEM: ShieldCheck,
    SYSTEM_EXCEPTION: AlertTriangle,
    AI: ShieldCheck,
    EXCEPTION: AlertTriangle,
  };
  return icons[todo.businessType] || AlertTriangle;
}

function priorityClass(priority: DashboardTodoItem['priority']) {
  if (priority === 'HIGH') return 'border-rose-200 bg-rose-50 text-rose-700';
  if (priority === 'MEDIUM') return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-slate-200 bg-slate-50 text-slate-600';
}

function priorityText(priority: DashboardTodoItem['priority']) {
  if (priority === 'HIGH') return '高优先级';
  if (priority === 'MEDIUM') return '中优先级';
  return '低优先级';
}

function businessLabel(todo: DashboardTodoItem) {
  const labels: Record<string, string> = {
    PURCHASE: '采购',
    SALES: '销售',
    WAREHOUSE: '仓储',
    INVENTORY: '库存',
    SYSTEM: '系统',
    SYSTEM_EXCEPTION: '系统',
    AI: '智能',
    EXCEPTION: '异常',
  };
  return todo.businessLabel || labels[todo.businessType] || todo.businessType || '其他';
}

function isSystemException(todo: DashboardTodoItem) {
  return todo.detail?.model === 'SYSTEM_EXCEPTION';
}

function isTrackedTodo(todo: DashboardTodoItem) {
  return todo.completionMode === 'TRACKED';
}

function isDocumentDetail(todo: DashboardTodoItem) {
  return todo.detail?.model === 'PURCHASE_ORDER_APPROVAL'
    || todo.detail?.model === 'SALES_ORDER_APPROVAL'
    || todo.detail?.model === 'PURCHASE_RETURN_APPROVAL'
    || todo.detail?.model === 'SALES_RETURN_APPROVAL'
    || todo.detail?.model === 'INBOUND_CONFIRM'
    || todo.detail?.model === 'OUTBOUND_CONFIRM';
}

function documentItems(todo: DashboardTodoItem): DashboardTodoDocumentItem[] {
  const detail = todo.detail;
  return detail && isDocumentDetail(todo) ? detail.items as DashboardTodoDocumentItem[] : [];
}

function stockRiskItems(todo: DashboardTodoItem): DashboardTodoStockRiskItem[] {
  const detail = todo.detail;
  return detail?.model === 'STOCK_RISK_REVIEW' ? detail.items as DashboardTodoStockRiskItem[] : [];
}

function systemExceptionItems(todo: DashboardTodoItem): DashboardTodoSystemExceptionItem[] {
  const detail = todo.detail;
  return detail?.model === 'SYSTEM_EXCEPTION' ? detail.items as DashboardTodoSystemExceptionItem[] : [];
}

function documentLabels(todo: DashboardTodoItem) {
  switch (todo.detail?.model) {
    case 'PURCHASE_RETURN_APPROVAL': return { document: '退货单号', source: '原采购单号', counterparty: '供应商' };
    case 'SALES_RETURN_APPROVAL': return { document: '退货单号', source: '原销售单号', counterparty: '客户' };
    case 'PURCHASE_ORDER_APPROVAL': return { document: '采购单号', source: '', counterparty: '供应商' };
    case 'SALES_ORDER_APPROVAL': return { document: '销售单号', source: '', counterparty: '客户' };
    case 'INBOUND_CONFIRM': return { document: '入库单号', source: '来源单号', counterparty: '入库仓库' };
    case 'OUTBOUND_CONFIRM': return { document: '出库单号', source: '来源单号', counterparty: '出库仓库' };
    default: return { document: '单据号', source: '来源单号', counterparty: '业务对象' };
  }
}

function todoDocumentStatusText(status: string) {
  const labels: Record<string, string> = { SUBMITTED: '待审核', PENDING_CONFIRM: '待确认' };
  return labels[status] || status || '-';
}

function formatTodoAmount(amountFen: number | null) {
  return amountFen == null ? '-' : formatDetailedCurrency(amountFen / 100);
}

function formatTodoWait(waitHours: number | null) {
  if (waitHours == null) return '-';
  if (waitHours < 24) return `${waitHours}小时`;
  const days = Math.floor(waitHours / 24);
  const hours = waitHours % 24;
  return hours > 0 ? `${days}天${hours}小时` : `${days}天`;
}

function todoWaitClass(level: 'NORMAL' | 'WARNING' | 'OVERDUE' | null) {
  if (level === 'OVERDUE') return 'text-rose-700';
  if (level === 'WARNING') return 'text-amber-700';
  return 'text-slate-800';
}
function openDetail(type: DetailType, todoId?: string) {
  if (type === 'todos' && todosAccessState.value !== 'ALLOWED') return;
  if (type === 'products' && topProductsAccessState.value !== 'ALLOWED') return;
  if (type === 'suppliers' && supplierPerformanceAccessState.value !== 'ALLOWED') return;
  activeDetail.value = type;
  selectedTodoDetailId.value = type === 'todos' ? todoId || sortedTodos.value[0]?.todoId || null : null;
}

function setDetailDialogOpen(open: boolean) {
  if (open) return;
  activeDetail.value = null;
  selectedTodoDetailId.value = null;
  if (route.query.todoId) {
    void router.replace({ query: { ...route.query, todoId: undefined } });
  }
}

// 工作台详情弹窗中"前往完成"按钮的受控导航映射。
// 仅 AUTO 类型允许跳转到对应业务页面;铃铛不再使用该映射，统一进入 /dashboard。
const TODO_NAVIGATION: Record<string, RouteLocationRaw> = {
  'todo-purchase-approve': { path: '/purchase/orders', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-sales-approve': { path: '/sales/orders', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-purchase-return-approve': { path: '/purchase/returns', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-sales-return-approve': { path: '/sales/returns', query: { status: 'SUBMITTED', from: 'dashboard' } },
  'todo-inbound': { path: '/warehouse/inbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' } },
  'todo-outbound': { path: '/warehouse/outbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' } },
  'todo-stock-risk-review': { path: '/warehouse/stocks', query: { riskOnly: 'true', from: 'dashboard' } },
};

function goToTodoRoute(todo: DashboardTodoItem) {
  if (todo.completionMode === 'TRACKED') return;
  const target = TODO_NAVIGATION[todo.todoId] ?? { path: '/dashboard' };
  activeDetail.value = null;
  void router.push(target);
}

let initialHandled = false;

onMounted(async () => {
  await bootstrapOverview();
  initialHandled = true;
});

watch(() => route.query.todoId, () => {
  if (initialHandled) openTodoFromRoute();
}, { flush: 'post' });

onBeforeUnmount(() => {
  if (trendAnimationTimer) window.clearTimeout(trendAnimationTimer);
});

const todoFilter = ref('全部');
const todoCategories = ['全部', '采购', '销售', '仓储', '库存', '系统'];
const filteredTodos = computed(() => todosAccessState.value === 'ALLOWED' ? sortedTodos.value.filter(todo => todoFilter.value === '全部' || businessLabel(todo) === todoFilter.value) : []);
const todoCategoryCount = (category: string) => todosAccessState.value === 'ALLOWED' ? sortedTodos.value.filter(todo => category === '全部' || businessLabel(todo) === category).length : 0;
const stageKind = ref<'purchaseCount' | 'salesCount'>('purchaseCount');
const stageOptions = computed(() => [
  ...(orderStagePermissions.value.canViewPurchase ? [{ key: 'purchaseCount' as const, label: '采购订单' }] : []),
  ...(orderStagePermissions.value.canViewSales ? [{ key: 'salesCount' as const, label: '销售订单' }] : []),
]);
const stageTotal = computed(() => (overview.value?.orderStages || []).reduce((sum, stage) => sum + stage[stageKind.value], 0));
const stageColors = ['#b9c5d9', '#e4b966', '#80b1ee', '#66bdbe', '#70b69d', '#d8dee7'];
watch(stageOptions, options => { if (!options.some(option => option.key === stageKind.value) && options[0]) stageKind.value = options[0].key; }, { immediate: true });
const shortcutItems = computed(() => [
  { label: '采购订单', path: '/purchase/orders', permission: 'purchase:query', icon: ShoppingCart },
  { label: '销售订单', path: '/sales/orders', permission: 'sales:query', icon: FileText },
  { label: '入库确认', path: '/warehouse/inbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' }, permission: 'warehouse:query', icon: Truck },
  { label: '出库确认', path: '/warehouse/outbound-bills', query: { status: 'PENDING_CONFIRM', from: 'dashboard' }, permission: 'warehouse:query', icon: Boxes },
  { label: '库存余额', path: '/warehouse/stocks', permission: 'warehouse:query', icon: PackageSearch },
  { label: '智能助手', path: '/ai/assistant', permission: 'ai:assistant:use', icon: MessageSquare },
].filter(item => authStore.hasPermission(item.permission)));
function supplierRank(supplier: DashboardSupplierPerformance) {
  if (supplier.scoreStatus !== 'READY' || supplier.overallScore === null) return null;
  return rankedSuppliers.value.filter(item => item.scoreStatus === 'READY' && item.overallScore !== null && item.overallScore > supplier.overallScore!).length + 1;
}
const supplierScoreFields = [
  { key: 'deliveryScore', label: '交期评分' }, { key: 'qualityScore', label: '质量评分' },
  { key: 'priceScore', label: '价格评分' }, { key: 'serviceScore', label: '服务评分' },
] as const;
const metricIcon = (key: DashboardMetric['key']) => ({ MONTH_SALES: TrendingUp, MONTH_GROSS_PROFIT: BarChart3, PENDING_ORDERS: ClipboardCheck, STOCK_RISK_SKU: Boxes })[key];
function switchTodoCategory(event: KeyboardEvent) {
  if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  const index = todoCategories.indexOf(todoFilter.value);
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? todoCategories.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + todoCategories.length) % todoCategories.length;
  todoFilter.value = todoCategories[next];
  (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button')[next]?.focus();
}
// 概览清空或刷新后权限收紧时，立即清除详情，避免展示上一会话的记录。
watch(overview, current => {
  if (!current || (activeDetail.value === 'todos' && current.access.todos.state !== 'ALLOWED') || (activeDetail.value === 'products' && current.access.topProducts.state !== 'ALLOWED') || (activeDetail.value === 'suppliers' && current.access.supplierPerformance.state !== 'ALLOWED')) setDetailDialogOpen(false);
});
</script>

<template>
  <section class="page-shell dashboard-page dashboard-pilot">
    <div class="dashboard-heading">
      <div><span class="dashboard-eyebrow">业务总览</span><h1 class="page-title">工作台</h1><p class="page-description">聚合销售、采购、库存和履约风险，优先处理会影响收入和交付的事项</p></div>
      <div class="dashboard-heading-actions"><span data-dashboard-refresh-status aria-live="polite">{{ loading ? '正在同步经营数据…' : overview ? `更新于 ${overview.refreshedAt}` : '等待加载经营数据' }}</span><Button size="sm" variant="outline" :disabled="loading" @click="bootstrapOverview"><RefreshCw :class="{ 'animate-spin': loading }" />{{ loading ? '刷新中' : '刷新' }}</Button></div>
    </div>
    <div class="dashboard-content" :aria-busy="loading">
      <div :inert="loading && !!overview" class="dashboard-rendered-data">
      <template v-if="overview">
        <p v-if="overviewStore.refreshError" class="dashboard-refresh-error" role="status">刷新失败，当前显示上次数据；请重试。</p>
        <section class="dashboard-metrics" aria-label="核心指标">
          <div v-for="metric in overview.metrics" :key="metric.key" class="dashboard-metric" :class="`dashboard-metric--${metricCardTone(metric)}`">
            <div class="dashboard-metric__head"><component :is="metricIcon(metric.key)" /><span>{{ metric.label }}</span></div><strong>{{ metricDisplay(metric) }}</strong>
            <div class="dashboard-metric__comparison" v-if="overview.access.metrics[metric.key].state === 'ALLOWED'">
              <template v-if="metric.comparisonState === 'AVAILABLE' && metric.changeRate !== null"><span :class="{ 'is-risk': metric.status === 'risk' }"><ArrowUpRight v-if="metric.changeRate >= 0" /><ArrowDownRight v-else />{{ Math.abs(metric.changeRate).toFixed(1) }}%</span><small>{{ metric.compareText }}</small></template>
              <small v-else>{{ metric.comparisonState === 'UNAVAILABLE' ? '对比数据暂不可用' : '暂无可比基线' }}</small>
            </div><small v-else class="dashboard-metric__denied">{{ overview.access.metrics[metric.key].state === 'DENIED' ? '暂无查看权限' : '暂无数据' }}</small>
          </div>
        </section>
        <nav v-if="shortcutItems.length" class="dashboard-shortcuts" aria-label="常用入口"><span>常用入口</span><div><RouterLink v-for="item in shortcutItems" :key="item.path" :to="{ path: item.path, query: item.query }"><component :is="item.icon" />{{ item.label }}</RouterLink></div></nav>
        <div class="dashboard-primary-grid">
          <section class="dashboard-panel dashboard-todos-panel">
            <header class="dashboard-panel-heading"><div><h2>业务待办 <span class="dashboard-counter">{{ todoCategoryCount('全部') }}</span></h2><p>按交付影响与优先级排序</p></div><span class="dashboard-section-hint">查看依据后前往处理</span></header>
            <div v-if="todosAccessState === 'ALLOWED'" class="dashboard-todo-tabs" role="tablist" aria-label="待办分类" @keydown="switchTodoCategory"><button v-for="category in todoCategories" :key="category" role="tab" :aria-selected="todoFilter === category" :tabindex="todoFilter === category ? 0 : -1" @click="todoFilter = category">{{ category }}<small>{{ todoCategoryCount(category) }}</small></button></div>
            <div v-if="todosAccessState !== 'ALLOWED'" class="dashboard-empty-region" data-dashboard-todos-state><DashboardEmptyPanel :state="emptyPanelState(todosAccessState)" :title="todosAccessState === 'DENIED' ? '您暂无查看权限' : '当前没有待处理事项'" description="工作台按当前登录用户权限展示业务待办。" cover layout="stacked" /></div>
            <Transition v-else name="dashboard-content-swap" mode="out-in"><div :key="todoFilter" class="dashboard-todo-list" role="tabpanel" :aria-label="`${todoFilter}待办`"><button v-for="todo in filteredTodos" :key="todo.todoId" class="dashboard-todo-row" :aria-label="`${todo.title}，${todo.count}项，查看详情`" @click="openDetail('todos', todo.todoId)"><span class="dashboard-todo-tile" :class="`dashboard-todo-tile--${todo.businessType.toLowerCase()}`"><component :is="todoIcon(todo)" /></span><span class="dashboard-todo-copy"><strong>{{ todo.title }}</strong><small :title="todo.description">{{ todo.description }}</small></span><Badge variant="outline" :class="priorityClass(todo.priority)">{{ priorityText(todo.priority) }}</Badge><strong class="dashboard-todo-count">{{ todo.count }}</strong><ChevronRight /></button><p v-if="!filteredTodos.length" class="dashboard-small-empty">当前分类没有待处理事项</p></div></Transition>
            <footer class="dashboard-panel-foot"><span>{{ filteredTodos.length }} 类待办 · {{ filteredTodos.reduce((sum, todo) => sum + todo.count, 0) }} 项事项</span><span>随业务状态自动更新</span></footer>
          </section>
          <section class="dashboard-panel dashboard-stages-panel">
            <header class="dashboard-panel-heading"><div><h2>订单阶段</h2><p>{{ orderStagePeriodDescription }}</p></div><Route class="dashboard-heading-icon" /></header>
            <template v-if="orderStagesAccessState === 'ALLOWED' && stageOptions.length"><div class="dashboard-segmented" role="group" aria-label="订单类型"><button v-for="option in stageOptions" :key="option.key" :aria-pressed="stageKind === option.key" @click="stageKind = option.key">{{ option.label }}</button></div><Transition name="dashboard-content-swap" mode="out-in"><div :key="stageKind" class="dashboard-stages-content"><div class="dashboard-stage-total"><span>本月订单</span><strong>{{ stageTotal }}</strong><small>单</small></div><div class="dashboard-stage-stack" aria-hidden="true"><span v-for="(stage, index) in overview.orderStages" :key="stage.stage" :style="{ flex: stage[stageKind], background: stageColors[index % stageColors.length] }" /></div><div class="dashboard-stages"><div v-for="(stage, index) in overview.orderStages" :key="stage.stage" class="dashboard-stage-row"><i :style="{ background: stageColors[index % stageColors.length] }" /><span>{{ stage.stage }}</span><strong>{{ stage[stageKind] }}</strong><small>单</small></div></div></div></Transition></template>
            <div v-else class="dashboard-empty-region" data-dashboard-order-empty><DashboardEmptyPanel :state="emptyPanelState(orderStagesAccessState)" :title="orderStagesAccessState === 'DENIED' ? '您暂无查看权限' : '暂无订单流转数据'" description="当前统计期间新建单据的当前状态分布。" cover layout="stacked" /></div>
            <footer class="dashboard-panel-foot"><span>按当前状态分布</span><span>含取消订单</span></footer>
          </section>
        </div>
        <div class="dashboard-secondary-grid">
          <section class="dashboard-panel dashboard-trend-panel"><header class="dashboard-panel-heading"><div><h2>经营趋势</h2><p>销售、采购与毛利变化</p></div><div v-if="trendAccessState === 'ALLOWED'" class="dashboard-segmented" role="group" aria-label="经营趋势天数选择"><button v-for="days in trendDayOptions" :key="days" :aria-pressed="selectedTrendDays === days" @click="selectTrendDays(days)">{{ days }}天</button></div></header>
            <div v-if="trendAccessState !== 'ALLOWED'" class="dashboard-empty-region" data-dashboard-trend-empty><DashboardEmptyPanel :state="emptyPanelState(trendAccessState)" :title="trendAccessState === 'DENIED' ? '您暂无查看权限' : '暂无经营趋势数据'" description="按当前销售与采购权限显示可见趋势。" cover layout="stacked" /></div>
            <template v-else><div class="dashboard-trend-summary" data-dashboard-trend-summary :class="{ 'is-transitioning': trendTransitioning }" aria-live="polite"><div v-for="item in trendSummary" :key="item.key" :class="`dashboard-trend-summary__item dashboard-trend-summary__item--${item.tone}`"><small>{{ item.label }}</small><strong :class="item.className">{{ item.value }}</strong></div></div><DashboardTrendChart :key="selectedTrendDays" :points="displayedTrend" :series="visibleTrendSeries" /><div class="dashboard-trend-footer"><div class="dashboard-trend-legend"><span v-for="series in visibleTrendSeries" :key="series.key" :class="series.className">{{ series.label }}</span></div><span>每日数值 · 元</span></div><div class="dashboard-trend-values-scroll" tabindex="0" role="region" aria-label="每日金额，左右滚动查看更多日期"><table class="dashboard-trend-values"><thead><tr><th class="dashboard-trend-values__label dashboard-trend-values__head">日期 / 元</th><th v-for="point in displayedTrend" :key="point.date" class="dashboard-trend-values__head">{{ point.date.slice(-5) }}</th></tr></thead><tbody><tr v-for="series in visibleTrendSeries" :key="series.key"><th scope="row" class="dashboard-trend-values__label">{{ series.label }}</th><td v-for="point in displayedTrend" :key="point.date" :class="{ 'is-negative': point[series.key] < 0 }">{{ point[series.key].toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</td></tr></tbody></table></div></template>
          </section>
          <DashboardInventoryStatusPanel />
        </div>
        <div class="dashboard-ranking-grid">
          <section class="dashboard-panel"><header class="dashboard-panel-heading"><div><h2>销售商品排行</h2><p>按近 30 日销售额排序</p></div><Button v-if="topProductsAccessState === 'ALLOWED'" variant="ghost" size="sm" class="dashboard-rank-action" aria-label="查看详情销售商品排行" @click="openDetail('products')">全部商品 <ChevronRight /></Button></header><div v-if="topProductsAccessState !== 'ALLOWED'" class="dashboard-empty-region" data-dashboard-top-products-state><DashboardEmptyPanel :state="emptyPanelState(topProductsAccessState)" :title="topProductsAccessState === 'DENIED' ? '您暂无查看权限' : '暂无销售商品排行'" description="按当前权限显示销售商品数据。" cover layout="stacked" /></div><div v-else class="dashboard-rank-table-scroll"><table class="dashboard-rank-table"><thead><tr><th>商品</th><th>销售额</th><th>销量</th><th>可用库存</th></tr></thead><tbody><tr v-for="(product, index) in visibleTopProducts" :key="product.productId"><td><div class="dashboard-rank-identity"><span class="dashboard-rank-no" :class="{ 'is-top': index < 3 }">{{ String(index + 1).padStart(2, '0') }}</span><div><strong>{{ product.productName }}</strong><small>{{ product.productCode }}</small></div></div></td><td>{{ formatDetailedCurrency(product.salesAmount) }}</td><td>{{ formatNumber(product.salesQty) }}</td><td>{{ formatNumber(product.availableQty) }}</td></tr></tbody></table></div></section>
          <section class="dashboard-panel"><header class="dashboard-panel-heading"><div><h2>供应商履约</h2><p>综合评分由高到低 · 质量与交货周期</p></div><Button v-if="supplierPerformanceAccessState === 'ALLOWED'" variant="ghost" size="sm" class="dashboard-rank-action" aria-label="查看详情供应商履约" @click="openDetail('suppliers')">完整排行 <ChevronRight /></Button></header><div v-if="supplierPerformanceAccessState !== 'ALLOWED'" class="dashboard-empty-region" data-dashboard-supplier-performance-state><DashboardEmptyPanel :state="emptyPanelState(supplierPerformanceAccessState)" :title="supplierPerformanceAccessState === 'DENIED' ? '您暂无查看权限' : '暂无供应商履约数据'" description="按当前权限显示供应商履约数据。" cover layout="stacked" /></div><div v-else class="dashboard-rank-table-scroll"><table class="dashboard-rank-table dashboard-supplier-table"><thead><tr><th>供应商</th><th>质量评分</th><th>平均交期</th><th>综合评分</th></tr></thead><tbody><tr v-for="supplier in visibleSupplierPerformance" :key="supplier.supplierId"><td><div class="dashboard-rank-identity"><span class="dashboard-rank-no" :class="{ 'is-top': supplierRank(supplier) !== null && supplierRank(supplier)! < 4 }">{{ supplierRank(supplier) === null ? '—' : String(supplierRank(supplier)).padStart(2, '0') }}</span><div><strong>{{ supplier.supplierName }}</strong><small>{{ supplier.supplierCode }}</small></div></div></td><td>{{ supplier.scoreStatus === 'READY' ? formatSupplierMetric(supplier.qualityScore) : '—' }}</td><td>{{ formatSupplierMetric(supplier.avgDeliveryDays, ' 天') }}</td><td class="dashboard-score">{{ supplier.scoreStatus === 'READY' ? formatSupplierMetric(supplier.overallScore) : '未形成评分' }}</td></tr></tbody></table></div></section>
        </div>
        <footer class="dashboard-footer"><span>数据刷新时间：{{ overview.refreshedAt }}</span><span>工作台按当前登录用户权限返回可见模块数据</span></footer>
        <Dialog :open="activeDetail !== null" @update:open="setDetailDialogOpen"><DialogContent class="dashboard-detail-drawer" :show-close-button="false" :class="{ 'dashboard-detail-drawer--todo': activeDetail === 'todos' }"><DialogHeader class="dashboard-drawer-heading"><div><span class="dashboard-eyebrow">{{ activeDetail === 'todos' ? `${selectedDetailTodo ? businessLabel(selectedDetailTodo) : '业务'} / 待办详情` : '经营 / 完整排行' }}</span><DialogTitle>{{ detailTitle }}</DialogTitle><DialogDescription>{{ detailDescription }}</DialogDescription></div><Button variant="ghost" size="icon-sm" aria-label="关闭详情" @click="setDetailDialogOpen(false)"><X /></Button></DialogHeader>
          <DialogScrollArea class="dashboard-drawer-scroll" content-class="dashboard-drawer-body">
            <template v-if="activeDetail === 'todos' && selectedDetailTodo"><div class="dashboard-detail-overview"><div><small>待办事项</small><strong>{{ selectedDetailTodo.count }}</strong></div><div><small>处理优先级</small><Badge variant="outline" :class="priorityClass(selectedDetailTodo.priority)">{{ priorityText(selectedDetailTodo.priority) }}</Badge></div><div><small>处理方式</small><strong>{{ isTrackedTodo(selectedDetailTodo) ? '系统追踪' : '业务完成后更新' }}</strong></div></div>
              <div class="dashboard-detail-heading"><h3>{{ isSystemException(selectedDetailTodo) ? '异常记录' : selectedDetailTodo.detail?.model === 'STOCK_RISK_REVIEW' ? '风险商品' : '单据明细' }}</h3><small>共 {{ selectedDetailTodo.count }} 项 · 以下展示部分依据</small></div>
              <template v-if="isSystemException(selectedDetailTodo)"><article v-for="item in systemExceptionItems(selectedDetailTodo)" :key="item.id" class="dashboard-record"><header><strong>{{ item.exceptionNo }}</strong><Badge variant="outline" :class="priorityClass(item.severity)">{{ priorityText(item.severity) }}</Badge></header><p>{{ item.summary || '暂无异常摘要' }}</p><dl><div><dt>异常类型</dt><dd>{{ item.exceptionType }}</dd></div><div><dt>来源模块</dt><dd>{{ item.sourceModule }}</dd></div><div><dt>发生时间</dt><dd>{{ item.occurredAt || '—' }}</dd></div></dl></article></template>
              <template v-else-if="isDocumentDetail(selectedDetailTodo)"><article v-for="item in documentItems(selectedDetailTodo)" :key="item.documentNo" class="dashboard-record"><header><strong>{{ item.documentNo }}</strong><Badge variant="outline" class="dashboard-document-status">{{ todoDocumentStatusText(item.documentStatus) }}</Badge></header><p>{{ item.counterpartyName || '—' }}</p><dl><div><dt>{{ documentLabels(selectedDetailTodo).document }}</dt><dd>{{ item.documentNo }}</dd></div><div v-if="documentLabels(selectedDetailTodo).source"><dt>{{ documentLabels(selectedDetailTodo).source }}</dt><dd>{{ item.sourceDocumentNo || '—' }}</dd></div><div><dt>{{ documentLabels(selectedDetailTodo).counterparty }}</dt><dd>{{ item.counterpartyName || '—' }}</dd></div><div><dt>单据金额</dt><dd class="dashboard-money">{{ formatTodoAmount(item.amountFen) }}</dd></div><div><dt>已等待</dt><dd :class="todoWaitClass(item.waitLevel)">{{ formatTodoWait(item.waitHours) }}<span v-if="item.waitLevel === 'OVERDUE'"> · 已超时</span><span v-else-if="item.waitLevel === 'WARNING'"> · 需关注</span></dd></div></dl></article></template>
              <template v-else-if="selectedDetailTodo.detail?.model === 'STOCK_RISK_REVIEW'"><article v-for="item in stockRiskItems(selectedDetailTodo)" :key="item.id" class="dashboard-record"><header><strong>{{ item.productName }}</strong><Badge variant="outline" :class="priorityClass(item.severity)">{{ item.severity === 'HIGH' ? '高风险' : '中风险' }}</Badge></header><p>{{ item.productCode }} · {{ item.warehouseName }}</p><dl><div><dt>仓库</dt><dd>{{ item.warehouseName }}</dd></div><div><dt>可用库存</dt><dd class="dashboard-risk-text">{{ formatNumber(item.availableQty) }} {{ item.unitName }}</dd></div><div><dt>安全库存</dt><dd>{{ formatNumber(item.safetyStockQty) }} {{ item.unitName }}</dd></div><div><dt>建议采购量</dt><dd>{{ formatNumber(item.suggestedPurchaseQty) }} {{ item.unitName }}</dd></div></dl></article></template>
              <p v-if="!selectedDetailTodo.detail || selectedDetailTodo.detail.items.length === 0" class="dashboard-small-empty">暂无可展示的依据记录，请查看处理指引。</p><div class="dashboard-resolve"><Info /><div><h3>{{ isSystemException(selectedDetailTodo) ? '排查指引' : '处理指引' }}</h3><p>{{ selectedDetailTodo.resolveHint || (isSystemException(selectedDetailTodo) ? '请根据异常类型、来源模块与发生时间定位原因；工作台仅展示记录。' : '请前往对应业务页面核对完整单据。') }}</p></div></div>
            </template>
            <template v-else-if="activeDetail === 'products'"><div class="dashboard-ranking-intro"><strong>按销售金额由高到低</strong><small>{{ rankedProducts.length }} 件上榜商品 · 当前可见范围</small></div><article v-for="(product, index) in rankedProducts" :key="product.productId" class="dashboard-product-card"><span class="dashboard-rank-no" :class="{ 'is-top': index < 3 }">{{ String(index + 1).padStart(2, '0') }}</span><div class="dashboard-product-card__main"><header><div><h3>{{ product.productName }}</h3><small>{{ product.productCode }}</small></div><div class="dashboard-rank-amount"><small>净销售额</small><strong>{{ formatDetailedCurrency(product.salesAmount) }}</strong></div></header><div class="dashboard-rank-bar" aria-hidden="true"><i :style="{ width: `${Math.max(0, product.salesAmount) / maxTopProductAmount * 100}%` }" /></div><dl><div><dt>净销量</dt><dd>{{ formatNumber(product.salesQty) }}</dd></div><div><dt>可用库存</dt><dd>{{ formatNumber(product.availableQty) }}</dd></div></dl></div></article></template>
            <template v-else-if="activeDetail === 'suppliers'"><div class="dashboard-ranking-intro"><strong>按综合评分由高到低</strong><small>同分并列 · 未形成综合评分不参与排名</small></div><article v-for="supplier in rankedSuppliers" :key="supplier.supplierId" class="dashboard-record dashboard-supplier-card"><header><span class="dashboard-rank-no" :class="{ 'is-top': supplierRank(supplier) !== null && supplierRank(supplier)! < 4 }">{{ supplierRank(supplier) === null ? '—' : String(supplierRank(supplier)).padStart(2, '0') }}</span><div class="dashboard-supplier-identity"><strong>{{ supplier.supplierName }}</strong><small>{{ supplier.supplierCode }}</small></div><div class="dashboard-supplier-overall"><small>综合评分</small><strong>{{ supplier.scoreStatus === 'READY' ? formatSupplierMetric(supplier.overallScore) : '—' }}</strong></div></header><div class="dashboard-supplier-score-grid"><div v-for="field in supplierScoreFields" :key="field.key"><span>{{ field.label }}</span><strong>{{ supplier.scoreStatus === 'READY' ? formatSupplierMetric(supplier[field.key]) : '—' }}</strong><i aria-hidden="true"><b :style="{ width: supplier.scoreStatus === 'READY' ? metricBarWidth(supplier[field.key]) : '0%' }" /></i></div></div><dl><div><dt>平均交期</dt><dd>{{ formatSupplierMetric(supplier.avgDeliveryDays, ' 天') }}</dd></div><div><dt>评分依据金额</dt><dd>{{ supplier.scoreBasisAmount === null ? '—' : formatDetailedCurrency(supplier.scoreBasisAmount) }}</dd></div></dl><p v-if="supplier.scoreStatus === 'NOT_READY'">未形成评分</p></article></template>
          </DialogScrollArea>
          <footer class="dashboard-drawer-footer"><span>{{ activeDetail === 'todos' ? selectedDetailTodo?.completionMode === 'TRACKED' ? '系统记录仅供查看' : '处理仍在对应业务页面完成' : '数据按当前账号权限展示' }}</span><Button variant="outline" @click="setDetailDialogOpen(false)">关闭</Button><Button v-if="activeDetail === 'todos' && selectedDetailTodo?.completionMode === 'AUTO' && TODO_NAVIGATION[selectedDetailTodo.todoId]" @click="goToTodoRoute(selectedDetailTodo)">前往完成 <ArrowUpRight /></Button></footer>
        </DialogContent></Dialog>
      </template>
      <div v-else-if="overviewStore.refreshError && !loading" class="dashboard-bootstrap-error"><DashboardEmptyPanel state="EMPTY" title="经营数据加载失败" :description="overviewStore.refreshError" /><Button variant="outline" @click="bootstrapOverview">重新加载</Button></div>
      <div v-else class="dashboard-skeleton" data-dashboard-skeleton :aria-busy="loading"><div class="dashboard-metrics"><div v-for="index in 4" :key="index" class="dashboard-metric"><span class="dashboard-skeleton-line" /><span class="dashboard-skeleton-line" /></div></div><div class="dashboard-primary-grid"><div v-for="index in 2" :key="index" class="dashboard-panel"><div v-for="row in 5" :key="row" class="dashboard-skeleton__todo" /></div></div><div class="dashboard-secondary-grid"><div v-for="index in 2" :key="index" class="dashboard-panel dashboard-skeleton-chart" /></div><div class="dashboard-ranking-grid"><div v-for="index in 2" :key="index" class="dashboard-panel dashboard-skeleton-chart" /></div></div>
      </div>
      <ListLoadingOverlay :visible="loading && !!overview" :initial-delay="0" label="正在同步经营数据" />
    </div>
  </section>
</template>
