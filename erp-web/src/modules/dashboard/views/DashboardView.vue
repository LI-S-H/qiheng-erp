<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
import {
  AlertTriangle,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  Boxes,
  ChevronRight,
  Clock3,
  ClipboardCheck,
  PackageSearch,
  RefreshCw,
  Route,
  ShieldCheck,
  ShoppingCart,
  Truck,
} from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Separator } from '@/components/ui/separator';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { getDashboardOverview } from '../api';
import type { DashboardMetric, DashboardOverview, DashboardTodoItem } from '../types';

type DetailType = 'todos' | 'products' | 'suppliers' | 'stockAlerts';

const router = useRouter();
const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const activeDetail = ref<DetailType | null>(null);
const expandedTodoId = ref<string | null>(null);
const pendingCompleteTodo = ref<DashboardTodoItem | null>(null);
const trendDayOptions = [7, 15, 30] as const;
const selectedTrendDays = ref<(typeof trendDayOptions)[number]>(7);
const trendTransitioning = ref(false);
let trendAnimationTimer: number | null = null;
const trendChart = {
  plotWidth: 588,
  offsetX: 34,
  plotHeight: 210,
  valueHeight: 168,
  topPadding: 18,
} as const;
const trendSeries = [
  { key: 'salesAmount', label: '销售额', className: 'dashboard-trend--sales', colorClass: 'bg-blue-600' },
  { key: 'purchaseAmount', label: '采购额', className: 'dashboard-trend--purchase', colorClass: 'bg-amber-500' },
  { key: 'grossMarginAmount', label: '毛利额', className: 'dashboard-trend--margin', colorClass: 'bg-emerald-600' },
] as const;

const displayedTrend = computed(() => overview.value?.trend.slice(-selectedTrendDays.value) || []);

const trendMax = computed(() => {
  const values = displayedTrend.value.flatMap(item => [item.salesAmount, item.purchaseAmount, item.grossMarginAmount]);
  return Math.max(...values, 1);
});

const trendLines = computed(() => {
  const points = displayedTrend.value;
  const xStep = points.length > 1 ? trendChart.plotWidth / (points.length - 1) : trendChart.plotWidth;
  const y = (value: number) => trendChart.plotHeight - (value / trendMax.value) * trendChart.valueHeight - trendChart.topPadding;
  const build = (field: 'salesAmount' | 'purchaseAmount' | 'grossMarginAmount') =>
    points.map((item, index) => `${trendChart.offsetX + index * xStep},${y(item[field])}`).join(' ');
  return {
    sales: build('salesAmount'),
    purchase: build('purchaseAmount'),
    grossMargin: build('grossMarginAmount'),
  };
});

const trendTicks = computed(() => [trendMax.value, trendMax.value * 0.75, trendMax.value * 0.5, trendMax.value * 0.25, 0]);
const trendGridLines = computed(() => trendTicks.value.map(value => trendY(value)));
const trendAxisLabels = computed(() => {
  const points = displayedTrend.value;
  const total = points.length;
  if (total === 0) return [];

  const labelCount = total <= 7 ? total : total <= 15 ? 8 : 15;
  const lastLabelIndex = Math.max(labelCount - 1, 1);
  return Array.from({ length: labelCount }, (_, index) => {
    const pointIndex = labelCount === 1 ? 0 : Math.round(((total - 1) * index) / lastLabelIndex);
    return {
      key: `${points[pointIndex].date}-${index}`,
      date: points[pointIndex].date,
      x: trendChart.offsetX + (trendChart.plotWidth * index) / lastLabelIndex,
    };
  });
});

const maxStageCount = computed(() => {
  const values = overview.value?.orderStages.flatMap(item => [item.purchaseCount, item.salesCount]) || [0];
  return Math.max(...values, 1);
});

const maxTopProductAmount = computed(() => Math.max(...(overview.value?.topProducts.map(item => item.salesAmount) || [0]), 1));
const sortedTodos = computed(() => {
  const priorityRank: Record<DashboardTodoItem['priority'], number> = { HIGH: 0, MEDIUM: 1, LOW: 2 };
  return [...(overview.value?.todos || [])].sort((left, right) => {
    const weightDiff = left.sortWeight - right.sortWeight;
    if (weightDiff !== 0) return weightDiff;

    const priorityDiff = priorityRank[left.priority] - priorityRank[right.priority];
    if (priorityDiff !== 0) return priorityDiff;

    if (left.completionMode !== right.completionMode) return left.completionMode === 'MANUAL' ? -1 : 1;
    return left.title.localeCompare(right.title, 'zh-CN');
  });
});
const visibleTodos = computed(() => sortedTodos.value.slice(0, 8));
const visibleTopProducts = computed(() => overview.value?.topProducts.slice(0, 5) || []);
const visibleSupplierPerformance = computed(() => overview.value?.supplierPerformance.slice(0, 5) || []);
const detailDialogWidth = computed(() => {
  if (activeDetail.value === 'todos') return 'min(740px, calc(100vw - 2rem))';
  if (activeDetail.value === 'stockAlerts') return 'min(780px, calc(100vw - 2rem))';
  return 'min(920px, calc(100vw - 2rem))';
});

const detailTitle = computed(() => {
  const titles: Record<DetailType, string> = {
    todos: '业务待办详情',
    products: '销售商品排行详情',
    suppliers: '供应商履约详情',
    stockAlerts: '库存预警详情',
  };
  return activeDetail.value ? titles[activeDetail.value] : '';
});

const detailDescription = computed(() => {
  const descriptions: Record<DetailType, string> = {
    todos: '展示当前用户可见的全部工作台待办。单据状态类待办随业务完成自动消失，系统异常来自数据库记录表，工作台只展示详情与处理建议。',
    products: '展示近 30 日销售额完整排行，主页面默认显示前 5 名。',
    suppliers: '展示核心供应商履约完整排行，主页面默认显示前 5 名。',
    stockAlerts: '展示全部库存风险 SKU，主页面与详情使用同一批预警数据。',
  };
  return activeDetail.value ? descriptions[activeDetail.value] : '';
});

async function loadOverview() {
  if (loading.value) return;
  loading.value = true;
  try {
    overview.value = await getDashboardOverview();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '工作台数据加载失败');
  } finally {
    loading.value = false;
  }
}

function formatCurrency(value: number) {
  if (value >= 10000) return `￥${(value / 10000).toFixed(1)}万`;
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function formatCompactCurrency(value: number) {
  return value >= 10000 ? `${(value / 10000).toFixed(1)}万` : formatNumber(value);
}

function trendX(index: number) {
  const points = displayedTrend.value;
  return trendChart.offsetX + index * (trendChart.plotWidth / Math.max(points.length - 1, 1));
}

function trendY(value: number) {
  return trendChart.plotHeight - (value / trendMax.value) * trendChart.valueHeight - trendChart.topPadding;
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
  return metric.unit === '元' ? formatCurrency(metric.value) : `${formatNumber(metric.value)}${metric.unit}`;
}

function metricTone(metric: DashboardMetric) {
  const tones: Record<DashboardMetric['status'], string> = {
    good: 'text-emerald-700 bg-emerald-50 border-emerald-200',
    watch: 'text-amber-700 bg-amber-50 border-amber-200',
    risk: 'text-rose-700 bg-rose-50 border-rose-200',
    neutral: 'text-slate-600 bg-slate-50 border-slate-200',
  };
  return tones[metric.status];
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

function statusText(status: DashboardTodoItem['status']) {
  if (status === 'DONE') return '已完成';
  if (status === 'IGNORED') return '已忽略';
  return '待处理';
}

function isManualTodo(todo: DashboardTodoItem) {
  return todo.completionMode === 'MANUAL';
}

function isTrackedTodo(todo: DashboardTodoItem) {
  return todo.completionMode === 'TRACKED' || todo.businessType === 'SYSTEM_EXCEPTION';
}

function todoSourceLabel(todo: DashboardTodoItem) {
  return isTrackedTodo(todo) ? '记录来源' : '来源单号';
}

function todoSourceText(todo: DashboardTodoItem) {
  return isTrackedTodo(todo) ? (todo.sourceNo || 'system_exception') : (todo.sourceNo || '-');
}

function todoOccurredAtLabel(todo: DashboardTodoItem) {
  return isTrackedTodo(todo) ? '最近发生' : '发生时间';
}

function todoEvidenceToneClass(tone: 'neutral' | 'watch' | 'risk') {
  if (tone === 'risk') return 'text-rose-700';
  if (tone === 'watch') return 'text-amber-700';
  return 'text-slate-800';
}

function openDetail(type: DetailType) {
  activeDetail.value = type;
  expandedTodoId.value = null;
}

function toggleTodoEvidence(todo: DashboardTodoItem) {
  expandedTodoId.value = expandedTodoId.value === todo.todoId ? null : todo.todoId;
}

function requestCompleteTodo(todo: DashboardTodoItem) {
  pendingCompleteTodo.value = todo;
}

function setCompleteConfirmOpen(open: boolean) {
  if (!open) pendingCompleteTodo.value = null;
}

function confirmCompleteTodo() {
  const todo = pendingCompleteTodo.value;
  if (!todo) return;

  toast.success(`已确认完成「${todo.title}」处理，后续接入 POST /dashboard/todos/${todo.todoId}/complete。`);
  pendingCompleteTodo.value = null;
}

function goToTodoRoute(todo: DashboardTodoItem) {
  activeDetail.value = null;
  router.push(todo.route || '/dashboard');
}

onMounted(loadOverview);

onBeforeUnmount(() => {
  if (trendAnimationTimer) window.clearTimeout(trendAnimationTimer);
});
</script>

<template>
  <section class="page-shell dashboard-page space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">工作台</h1>
        <p class="page-description">聚合销售、采购、库存和履约风险，优先处理会影响收入和交付的事项</p>
      </div>
      <Button size="sm" variant="outline" :disabled="loading" @click="loadOverview">
        <RefreshCw class="mr-2 h-4 w-4" :class="{ 'animate-spin': loading }" />
        刷新
      </Button>
    </div>

    <div class="relative">
      <ListLoadingOverlay :visible="loading" />

      <div v-if="overview" class="space-y-4">
        <div class="summary-strip dashboard-metrics">
          <div v-for="metric in overview.metrics" :key="metric.label" class="summary-item dashboard-metric">
            <span>{{ metric.label }}</span>
            <strong>{{ metricDisplay(metric) }}</strong>
            <div class="mt-2 flex items-center gap-2">
              <Badge variant="outline" :class="metricTone(metric)">
                <ArrowUpRight v-if="metric.changeRate >= 0" class="mr-1 h-3 w-3" />
                <ArrowDownRight v-else class="mr-1 h-3 w-3" />
                {{ Math.abs(metric.changeRate).toFixed(1) }}%
              </Badge>
              <small class="text-xs text-muted-foreground">{{ metric.compareText }}</small>
            </div>
          </div>
        </div>

        <div class="dashboard-grid">
          <Card class="dashboard-panel dashboard-panel--trend">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base">
                  <BarChart3 class="h-4 w-4 text-primary" />
                  经营趋势
                </CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">近 {{ selectedTrendDays }} 日销售、采购和毛利变化</p>
              </div>
              <div class="dashboard-range-switch" aria-label="经营趋势天数选择">
                <Button
                  v-for="option in trendDayOptions"
                  :key="option"
                  size="sm"
                  :variant="selectedTrendDays === option ? 'default' : 'outline'"
                  @click="selectTrendDays(option)"
                >
                  {{ option }}天
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              <svg
                class="dashboard-trend-chart"
                :class="{ 'is-transitioning': trendTransitioning }"
                viewBox="0 0 700 260"
                role="img"
                :aria-label="`近 ${selectedTrendDays} 日经营趋势`"
              >
                <g class="dashboard-grid-lines">
                  <line v-for="(lineY, index) in trendGridLines" :key="index" x1="34" :y1="lineY" x2="622" :y2="lineY" />
                </g>
                <g class="dashboard-trend-axis">
                  <text v-for="(tick, index) in trendTicks" :key="index" x="638" :y="trendGridLines[index] + 4">{{ formatCompactCurrency(tick) }}</text>
                </g>
                <g :key="selectedTrendDays" class="dashboard-trend-layer">
                  <polyline v-for="series in trendSeries" :key="series.key" :points="trendLines[series.key === 'salesAmount' ? 'sales' : series.key === 'purchaseAmount' ? 'purchase' : 'grossMargin']" class="dashboard-trend" :class="series.className" />
                  <g class="dashboard-trend-points">
                    <g v-for="series in trendSeries" :key="series.key">
                      <circle
                        v-for="(point, index) in displayedTrend"
                        :key="`${series.key}-${point.date}`"
                        :cx="trendX(index)"
                        :cy="trendY(point[series.key])"
                        r="2.8"
                        :class="series.className"
                      />
                    </g>
                  </g>
                  <g class="dashboard-trend-labels">
                    <text v-for="label in trendAxisLabels" :key="label.key" :x="label.x" y="238" text-anchor="middle">{{ label.date }}</text>
                  </g>
                </g>
              </svg>
              <div class="dashboard-legend">
                <span v-for="series in trendSeries" :key="series.key"><i :class="series.colorClass" />{{ series.label }}</span>
              </div>
              <div
                class="dashboard-trend-values"
                :class="{ 'is-transitioning': trendTransitioning }"
                :style="{ '--trend-day-count': displayedTrend.length }"
                aria-label="经营趋势数值明细"
              >
                <div class="dashboard-trend-values__head">指标</div>
                <div v-for="point in displayedTrend" :key="`head-${point.date}`" class="dashboard-trend-values__head">{{ point.date }}</div>
                <template v-for="series in trendSeries" :key="series.key">
                  <div class="dashboard-trend-values__label"><i :class="series.colorClass" />{{ series.label }}</div>
                  <div v-for="point in displayedTrend" :key="`${series.key}-${point.date}`" class="dashboard-trend-values__value">
                    {{ formatCompactCurrency(point[series.key]) }}
                  </div>
                </template>
              </div>
            </CardContent>
          </Card>

          <Card class="dashboard-panel dashboard-panel--todos">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base">
                  <Clock3 class="h-4 w-4 text-primary" />
                  业务待办
                </CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">按交付影响排序</p>
              </div>
              <Button size="sm" variant="outline" aria-label="查看详情业务待办" @click="openDetail('todos')">
                详情
                <ChevronRight class="ml-1 h-4 w-4" />
              </Button>
            </CardHeader>
            <CardContent class="dashboard-todos-list space-y-2">
              <button
                v-for="todo in visibleTodos"
                :key="todo.todoId"
                type="button"
                class="dashboard-todo"
                @click="openDetail('todos')"
              >
                <span class="dashboard-todo__icon">
                  <component :is="todoIcon(todo)" class="h-4 w-4" />
                </span>
                <span class="min-w-0 flex-1 text-left">
                  <span class="flex items-center gap-2">
                    <strong class="truncate">{{ todo.title }}</strong>
                    <Badge variant="outline" :class="priorityClass(todo.priority)">{{ priorityText(todo.priority) }}</Badge>
                  </span>
                  <small>{{ todo.description }}</small>
                </span>
                <span class="dashboard-todo__count">{{ todo.count }}</span>
              </button>
            </CardContent>
          </Card>
        </div>

        <div class="dashboard-grid dashboard-grid--three">
          <Card class="dashboard-panel">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base">
                  <Route class="h-4 w-4 text-primary" />
                  订单流转
                </CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">采购与销售单据状态分布</p>
              </div>
            </CardHeader>
            <CardContent class="space-y-3">
              <div class="dashboard-stage-legend">
                <span><i class="bg-amber-500" />采购单</span>
                <span><i class="bg-blue-600" />销售单</span>
              </div>
              <div v-for="stage in overview.orderStages" :key="stage.stage" class="dashboard-stage">
                <div class="flex items-center justify-between text-xs">
                  <span class="font-medium text-slate-700">{{ stage.stage }}</span>
                  <span class="dashboard-stage__counts">
                    <span><i class="bg-amber-500" />{{ stage.purchaseCount }}</span>
                    <span><i class="bg-blue-600" />{{ stage.salesCount }}</span>
                  </span>
                </div>
                <div class="dashboard-stage__bars">
                  <span class="bg-amber-500" :style="{ width: `${(stage.purchaseCount / maxStageCount) * 100}%` }" />
                  <span class="bg-blue-600" :style="{ width: `${(stage.salesCount / maxStageCount) * 100}%` }" />
                </div>
              </div>
            </CardContent>
          </Card>

          <Card class="dashboard-panel">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base">
                  <PackageSearch class="h-4 w-4 text-primary" />
                  销售商品排行
                </CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">按近 30 日销售额排序</p>
              </div>
              <Button size="sm" variant="outline" aria-label="查看详情销售商品排行" @click="openDetail('products')">
                详情
                <ChevronRight class="ml-1 h-4 w-4" />
              </Button>
            </CardHeader>
            <CardContent class="space-y-2.5">
              <div v-for="product in visibleTopProducts" :key="product.productId" class="dashboard-rank">
                <div class="flex items-center justify-between gap-3">
                  <div class="min-w-0">
                    <strong class="block truncate text-[13px]">{{ product.productName }}</strong>
                    <small class="text-xs text-muted-foreground">{{ product.productCode }} · {{ formatNumber(product.salesQty) }} 件</small>
                  </div>
                  <span class="shrink-0 text-[13px] font-semibold tabular-nums">{{ formatCurrency(product.salesAmount) }}</span>
                </div>
                <div class="dashboard-rank__bar"><span :style="{ width: `${(product.salesAmount / maxTopProductAmount) * 100}%` }" /></div>
              </div>
            </CardContent>
          </Card>

          <Card class="dashboard-panel">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base">
                  <Truck class="h-4 w-4 text-primary" />
                  供应商履约
                </CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">核心供应商交付与质量表现</p>
              </div>
              <Button size="sm" variant="outline" aria-label="查看详情供应商履约" @click="openDetail('suppliers')">
                详情
                <ChevronRight class="ml-1 h-4 w-4" />
              </Button>
            </CardHeader>
            <CardContent class="space-y-2.5">
              <div v-for="supplier in visibleSupplierPerformance" :key="supplier.supplierId" class="dashboard-supplier">
                <div class="min-w-0">
                  <strong class="block truncate text-[13px]">{{ supplier.supplierName }}</strong>
                  <small class="text-xs text-muted-foreground">{{ supplier.supplierCode }} · 准时率 {{ supplier.onTimeRate.toFixed(1) }}%</small>
                </div>
                <div class="dashboard-supplier__scores">
                  <span>交付 {{ supplier.deliveryScore.toFixed(1) }}</span>
                  <span>质量 {{ supplier.qualityScore.toFixed(1) }}</span>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        <Card class="dashboard-panel">
          <CardHeader class="dashboard-panel__header">
            <div>
              <CardTitle class="flex items-center gap-2 text-base">
                <AlertTriangle class="h-4 w-4 text-amber-600" />
                库存预警
              </CardTitle>
              <p class="mt-1 text-xs text-muted-foreground">优先补足高销量、低可用库存的 SKU</p>
            </div>
            <Button size="sm" variant="outline" aria-label="查看详情库存预警" @click="openDetail('stockAlerts')">
              详情
              <ChevronRight class="ml-1 h-4 w-4" />
            </Button>
          </CardHeader>
          <CardContent>
            <div class="dashboard-table-scroll">
              <Table class="business-data-table min-w-[980px] table-fixed">
                <colgroup>
                  <col class="w-[220px]" />
                  <col class="w-[170px]" />
                  <col class="w-[110px]" />
                  <col class="w-[110px]" />
                  <col class="w-[110px]" />
                  <col class="w-[120px]" />
                  <col class="w-[140px]" />
                </colgroup>
                <TableHeader>
                  <TableRow>
                    <TableHead>产品</TableHead>
                    <TableHead>仓库</TableHead>
                    <TableHead>可用库存</TableHead>
                    <TableHead>安全库存</TableHead>
                    <TableHead>建议补货</TableHead>
                    <TableHead>风险等级</TableHead>
                    <TableHead>最近出库</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-for="alert in overview.stockAlerts" :key="alert.stockId">
                    <TableCell>
                      <div class="flex flex-col items-center gap-1 text-center">
                        <code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ alert.productCode }}</code>
                        <span class="max-w-full truncate font-medium" :title="alert.productName">{{ alert.productName }}</span>
                      </div>
                    </TableCell>
                    <TableCell>{{ alert.warehouseName }}</TableCell>
                    <TableCell class="font-semibold text-rose-700 tabular-nums">{{ formatNumber(alert.availableQty) }} {{ alert.unitName }}</TableCell>
                    <TableCell class="tabular-nums">{{ formatNumber(alert.safetyStockQty) }} {{ alert.unitName }}</TableCell>
                    <TableCell class="font-medium tabular-nums">{{ formatNumber(alert.suggestedPurchaseQty) }} {{ alert.unitName }}</TableCell>
                    <TableCell>
                      <Badge variant="outline" :class="alert.severity === 'HIGH' ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-amber-200 bg-amber-50 text-amber-700'">
                        {{ alert.severity === 'HIGH' ? '高风险' : '需关注' }}
                      </Badge>
                    </TableCell>
                    <TableCell class="text-xs text-muted-foreground">{{ alert.latestOutboundAt || '-' }}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          </CardContent>
        </Card>

        <div class="dashboard-footer">
          <span>数据刷新时间：{{ overview.refreshedAt }}</span>
          <Separator orientation="vertical" class="h-4" />
          <span>工作台按当前登录用户权限返回可见模块数据</span>
        </div>

        <Dialog :open="activeDetail !== null" @update:open="value => { if (!value) activeDetail = null; }">
          <DialogContent
            class="dashboard-detail-dialog !max-w-none !gap-4 !overflow-hidden !bg-white !p-0"
            :style="{ width: detailDialogWidth, maxWidth: detailDialogWidth }"
          >
            <DialogHeader class="dashboard-detail-hero">
              <div class="min-w-0">
                <DialogTitle class="text-lg">{{ detailTitle }}</DialogTitle>
                <DialogDescription class="mt-2 leading-6">{{ detailDescription }}</DialogDescription>
              </div>
            </DialogHeader>

            <div v-if="activeDetail === 'todos'" class="dashboard-detail-scroll dashboard-detail-list">
              <div v-for="todo in sortedTodos" :key="todo.todoId" class="dashboard-detail-todo" :class="{ 'dashboard-detail-todo--system': isTrackedTodo(todo) }">
                <span class="dashboard-todo__icon">
                  <component :is="todoIcon(todo)" class="h-4 w-4" />
                </span>
                <div class="dashboard-detail-todo__body">
                  <div class="dashboard-detail-row-title">
                    <strong>{{ todo.title }}</strong>
                    <Badge variant="outline" :class="priorityClass(todo.priority)">{{ priorityText(todo.priority) }}</Badge>
                    <Badge variant="outline" class="border-slate-200 bg-slate-50 text-slate-600">{{ businessLabel(todo) }}</Badge>
                    <Badge v-if="isManualTodo(todo)" variant="outline" class="border-blue-200 bg-blue-50 text-blue-700">
                      需人工处理
                    </Badge>
                    <Badge v-else-if="isTrackedTodo(todo)" variant="outline" class="border-slate-200 bg-slate-50 text-slate-700">
                      数据库记录
                    </Badge>
                  </div>
                  <p>{{ todo.description }}</p>
                  <div v-if="isManualTodo(todo) || isTrackedTodo(todo)" class="dashboard-detail-todo__meta">
                    <span><small>状态</small><strong>{{ statusText(todo.status) }}</strong></span>
                    <span><small>{{ todoSourceLabel(todo) }}</small><strong>{{ todoSourceText(todo) }}</strong></span>
                    <span><small>{{ todoOccurredAtLabel(todo) }}</small><strong>{{ todo.occurredAt || '-' }}</strong></span>
                  </div>
                  <div v-else class="dashboard-detail-todo__summary">
                    <span><small>待处理数量</small><strong>{{ todo.count }}</strong></span>
                    <span><small>完成方式</small><strong>处理对应业务后自动完成</strong></span>
                  </div>
                  <div v-if="todo.evidence.length > 0 && expandedTodoId === todo.todoId" class="dashboard-detail-evidence">
                    <div v-for="item in todo.evidence" :key="item.itemId" class="dashboard-detail-evidence__row">
                      <div class="min-w-0">
                        <strong>{{ item.primaryText }}</strong>
                        <small>{{ item.secondaryText }}</small>
                      </div>
                      <div class="dashboard-detail-evidence__metrics">
                        <span v-for="metric in item.metrics" :key="`${item.itemId}-${metric.label}`">
                          <small>{{ metric.label }}</small>
                          <strong :class="todoEvidenceToneClass(metric.tone)">{{ metric.value }}</strong>
                        </span>
                      </div>
                    </div>
                  </div>
                  <div v-if="todo.errorMessage || todo.resolveHint" class="dashboard-detail-todo__error">
                    <code v-if="todo.errorCode">{{ todo.errorCode }}</code>
                    <p v-if="todo.errorMessage">{{ todo.errorMessage }}</p>
                    <small v-if="todo.resolveHint">处理建议：{{ todo.resolveHint }}</small>
                  </div>
                </div>
                <div class="dashboard-detail-action">
                  <span class="dashboard-detail-count">{{ todo.count }}</span>
                  <Button v-if="todo.evidence.length > 0" size="sm" variant="outline" @click="toggleTodoEvidence(todo)">
                    {{ expandedTodoId === todo.todoId ? '收起详情' : '查看详情' }}
                  </Button>
                  <Button
                    v-if="!isTrackedTodo(todo)"
                    size="sm"
                    :variant="isManualTodo(todo) ? 'default' : 'outline'"
                    @click="isManualTodo(todo) ? requestCompleteTodo(todo) : goToTodoRoute(todo)"
                  >
                    {{ isManualTodo(todo) ? '完成处理' : '前往完成' }}
                  </Button>
                </div>
              </div>
            </div>

            <div v-else-if="activeDetail === 'products'" class="dashboard-detail-scroll dashboard-detail-list">
              <div v-for="(product, index) in overview.topProducts" :key="product.productId" class="dashboard-detail-rank">
                <div class="dashboard-detail-rank__index" :class="{ 'is-top': index < 3 }">TOP {{ index + 1 }}</div>
                <div class="dashboard-detail-rank__body">
                  <div class="dashboard-detail-row-title">
                    <strong>{{ product.productName }}</strong>
                    <code>{{ product.productCode }}</code>
                  </div>
                  <div class="dashboard-detail-rank__bar">
                    <span :style="{ width: `${(product.salesAmount / maxTopProductAmount) * 100}%` }" />
                  </div>
                </div>
                <div class="dashboard-detail-metrics">
                  <span><small>销售额</small><strong>{{ formatCurrency(product.salesAmount) }}</strong></span>
                  <span><small>销量</small><strong>{{ formatNumber(product.salesQty) }}</strong></span>
                  <span><small>可用库存</small><strong>{{ formatNumber(product.availableQty) }}</strong></span>
                </div>
              </div>
            </div>

            <div v-else-if="activeDetail === 'suppliers'" class="dashboard-detail-scroll dashboard-detail-list">
              <div v-for="(supplier, index) in overview.supplierPerformance" :key="supplier.supplierId" class="dashboard-detail-rank dashboard-detail-rank--supplier">
                <div class="dashboard-detail-rank__index" :class="{ 'is-top': index < 3 }">TOP {{ index + 1 }}</div>
                <div class="dashboard-detail-rank__body">
                  <div class="dashboard-detail-row-title">
                    <strong>{{ supplier.supplierName }}</strong>
                    <code>{{ supplier.supplierCode }}</code>
                  </div>
                  <small>准时率 {{ supplier.onTimeRate.toFixed(1) }}%</small>
                </div>
                <div class="dashboard-detail-metrics">
                  <span><small>交付</small><strong>{{ supplier.deliveryScore.toFixed(1) }}</strong></span>
                  <span><small>质量</small><strong>{{ supplier.qualityScore.toFixed(1) }}</strong></span>
                  <span><small>准时率</small><strong>{{ supplier.onTimeRate.toFixed(1) }}%</strong></span>
                </div>
              </div>
            </div>

            <div v-else-if="activeDetail === 'stockAlerts'" class="dashboard-detail-scroll dashboard-detail-list dashboard-detail-list--stock">
              <div v-for="alert in overview.stockAlerts" :key="alert.stockId" class="dashboard-detail-stock">
                <div class="dashboard-detail-stock__head">
                  <div class="dashboard-detail-row-title">
                    <strong>{{ alert.productName }}</strong>
                    <code>{{ alert.productCode }}</code>
                  </div>
                  <Badge variant="outline" :class="alert.severity === 'HIGH' ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-amber-200 bg-amber-50 text-amber-700'">
                    {{ alert.severity === 'HIGH' ? '高风险' : '需关注' }}
                  </Badge>
                </div>
                <p>{{ alert.warehouseName }} · 最近出库 {{ alert.latestOutboundAt || '-' }}</p>
                <div class="dashboard-detail-stock__metrics">
                  <span><small>可用库存</small><strong>{{ formatNumber(alert.availableQty) }} {{ alert.unitName }}</strong></span>
                  <span><small>安全库存</small><strong>{{ formatNumber(alert.safetyStockQty) }} {{ alert.unitName }}</strong></span>
                  <span><small>建议补货</small><strong>{{ formatNumber(alert.suggestedPurchaseQty) }} {{ alert.unitName }}</strong></span>
                </div>
              </div>
            </div>
          </DialogContent>
        </Dialog>

        <AlertDialog :open="!!pendingCompleteTodo" @update:open="setCompleteConfirmOpen">
          <AlertDialogContent size="sm">
            <AlertDialogHeader>
              <AlertDialogTitle>确认完成异常处理</AlertDialogTitle>
              <AlertDialogDescription>
                请确认已完成补偿、重试或人工修复后再完成处理。完成后该异常待办会在下一次工作台刷新时从待处理列表移除。
              </AlertDialogDescription>
            </AlertDialogHeader>
            <div v-if="pendingCompleteTodo" class="dashboard-confirm-card">
              <strong>{{ pendingCompleteTodo.title }}</strong>
              <span>{{ pendingCompleteTodo.sourceNo || pendingCompleteTodo.todoId }}</span>
              <small v-if="pendingCompleteTodo.errorCode">{{ pendingCompleteTodo.errorCode }}</small>
              <p v-if="pendingCompleteTodo.errorMessage">{{ pendingCompleteTodo.errorMessage }}</p>
            </div>
            <AlertDialogFooter>
              <AlertDialogCancel>取消</AlertDialogCancel>
              <AlertDialogAction @click="confirmCompleteTodo">确认完成</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </div>
  </section>
</template>

<style scoped>
.dashboard-page {
  min-width: 0;
}

.dashboard-metric strong {
  font-variant-numeric: tabular-nums;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.75fr) minmax(320px, 0.65fr);
  gap: 16px;
}

.dashboard-grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.dashboard-panel {
  overflow: hidden;
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.dashboard-panel--trend {
  min-height: 430px;
}

.dashboard-panel--todos {
  display: flex;
  min-height: 430px;
  flex-direction: column;
}

.dashboard-panel--todos .dashboard-panel__header {
  min-height: 58px;
  padding-bottom: 8px;
}

.dashboard-panel--todos :deep([data-slot="card-content"]) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.dashboard-panel__header {
  display: flex;
  min-height: 74px;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
}

.dashboard-panel__header > div:first-child {
  min-width: 0;
}

.dashboard-panel__header > [data-slot="button"] {
  flex: 0 0 auto;
  min-width: 70px;
  justify-content: center;
  padding-inline: 10px;
  white-space: nowrap;
}

.dashboard-range-switch {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 48%, white);
  padding: 3px;
}

.dashboard-range-switch [data-slot="button"] {
  height: 28px;
  min-width: 48px;
  padding-inline: 10px;
}

.dashboard-trend-chart {
  width: 100%;
  height: 300px;
}

.dashboard-trend-layer {
  transform-origin: 50% 50%;
  will-change: opacity, transform;
}

.dashboard-trend-chart.is-transitioning .dashboard-trend-layer {
  animation: dashboard-trend-soft-enter 240ms ease-out;
}

.dashboard-grid-lines line {
  stroke: color-mix(in srgb, var(--border) 80%, transparent);
  stroke-width: 1;
}

.dashboard-trend {
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 3;
}

.dashboard-trend--sales {
  stroke: #2563eb;
}

.dashboard-trend--purchase {
  stroke: #f59e0b;
}

.dashboard-trend--margin {
  stroke: #059669;
}

.dashboard-trend-labels text {
  fill: #344054;
  font-size: 9px;
  font-weight: 600;
}

.dashboard-trend-axis text {
  fill: #475467;
  font-size: 10px;
  font-weight: 600;
}

.dashboard-trend-points text {
  paint-order: stroke;
  stroke: white;
  stroke-width: 4px;
  fill: #344054;
  font-size: 11px;
  font-weight: 600;
}

circle.dashboard-trend--sales {
  fill: #2563eb;
  stroke: white;
  stroke-width: 2px;
}

circle.dashboard-trend--purchase {
  fill: #f59e0b;
  stroke: white;
  stroke-width: 2px;
}

circle.dashboard-trend--margin {
  fill: #059669;
  stroke: white;
  stroke-width: 2px;
}

.dashboard-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
  color: #475467;
  font-size: 12px;
}

.dashboard-legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dashboard-legend i {
  width: 18px;
  height: 3px;
  border-radius: 999px;
}

.dashboard-trend-values {
  display: grid;
  grid-template-columns: minmax(70px, 0.8fr) repeat(var(--trend-day-count, 7), minmax(64px, 1fr));
  gap: 0;
  overflow-x: auto;
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 28%, transparent);
  font-size: 12px;
}

.dashboard-trend-values.is-transitioning {
  animation: dashboard-trend-table-enter 220ms ease-out;
}

.dashboard-trend-values > div {
  min-width: 0;
  padding: 8px 7px;
  border-right: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
  text-align: center;
  white-space: nowrap;
}

.dashboard-trend-values__head {
  color: #667085;
  font-weight: 600;
}

.dashboard-trend-values__label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #475467;
  font-weight: 600;
}

.dashboard-trend-values__label i {
  width: 14px;
  height: 3px;
  border-radius: 999px;
}

.dashboard-trend-values__value {
  color: #172033;
  font-variant-numeric: tabular-nums;
}

@keyframes dashboard-trend-soft-enter {
  0% {
    opacity: 0.62;
    transform: translateY(3px);
  }

  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes dashboard-trend-table-enter {
  0% {
    opacity: 0.68;
    transform: translateY(3px);
  }

  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

.dashboard-todo {
  display: flex;
  width: 100%;
  min-height: 54px;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 2px);
  background: color-mix(in srgb, var(--muted) 28%, transparent);
  padding: 8px 10px;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.dashboard-todos-list {
  justify-content: center;
  padding-top: 0;
  padding-bottom: 12px;
}

.dashboard-todo:hover {
  border-color: color-mix(in srgb, var(--primary) 22%, var(--border));
  box-shadow: 0 10px 24px rgb(15 23 42 / 7%);
  transform: translateY(-1px);
}

.dashboard-todo small {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-todo__icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: white;
  color: var(--primary);
  box-shadow: inset 0 0 0 1px var(--border);
}

.dashboard-todo__count {
  min-width: 30px;
  color: #172033;
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
  text-align: right;
}

.dashboard-stage__bars {
  display: grid;
  gap: 5px;
  margin-top: 8px;
}

.dashboard-stage-legend,
.dashboard-stage__counts {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.dashboard-stage-legend {
  color: #475467;
  font-size: 12px;
}

.dashboard-stage-legend span,
.dashboard-stage__counts span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}

.dashboard-stage-legend i,
.dashboard-stage__counts i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.dashboard-stage__counts {
  color: #344054;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.dashboard-stage__bars span,
.dashboard-rank__bar span {
  display: block;
  height: 7px;
  min-width: 8px;
  border-radius: 999px;
}

.dashboard-stage__bars span:first-child {
  opacity: 0.75;
}

.dashboard-rank__bar {
  overflow: hidden;
  height: 7px;
  margin-top: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted) 70%, transparent);
}

.dashboard-rank__bar span {
  background: #2563eb;
}

.dashboard-supplier {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid var(--border);
  padding-bottom: 12px;
}

.dashboard-supplier:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.dashboard-supplier__scores {
  display: grid;
  flex: 0 0 auto;
  gap: 4px;
  color: #475467;
  font-size: 12px;
  text-align: right;
}

.dashboard-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #667085;
  font-size: 12px;
}

.dashboard-table-scroll {
  width: 100%;
  overflow-x: auto;
}

.dashboard-detail-scroll {
  box-sizing: border-box;
  width: auto;
  max-height: min(62vh, 560px);
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 8px;
  scrollbar-gutter: stable;
}

.dashboard-detail-dialog {
  width: min(920px, calc(100vw - 2rem));
  max-width: min(920px, calc(100vw - 2rem));
  gap: 16px;
  padding: 0;
  overflow: hidden;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--muted) 48%, white) 0%, white 168px);
}

.dashboard-detail-hero {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 22px 24px 0;
}

.dashboard-detail-dialog > .dashboard-detail-scroll,
.dashboard-detail-dialog > .dashboard-detail-list {
  width: auto;
  margin: 0 24px 24px;
  background: white;
}

.dashboard-detail-list {
  display: grid;
  gap: 10px;
  border: 0;
  background: transparent !important;
  padding-right: 12px;
  scrollbar-gutter: stable;
}

.dashboard-detail-todo,
.dashboard-detail-rank,
.dashboard-detail-stock {
  display: flex;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  padding: 12px 14px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.dashboard-detail-todo {
  align-items: flex-start;
}

.dashboard-detail-todo__body {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 8px;
}

.dashboard-detail-list--stock {
  gap: 12px;
}

.dashboard-detail-stock {
  display: grid;
  align-items: stretch;
}

.dashboard-detail-stock__head {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.dashboard-detail-stock p {
  margin: -4px 0 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.dashboard-detail-stock__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 24%, transparent);
}

.dashboard-detail-stock__metrics span {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 9px 10px;
  border-right: 1px solid var(--border);
}

.dashboard-detail-stock__metrics span:last-child {
  border-right: 0;
}

.dashboard-detail-stock__metrics small {
  color: #667085;
  font-size: 11px;
}

.dashboard-detail-stock__metrics strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-todo p {
  margin: 0;
  color: #667085;
  font-size: 12px;
  line-height: 1.5;
}

.dashboard-detail-todo__meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 24%, transparent);
}

.dashboard-detail-todo__summary {
  display: grid;
  grid-template-columns: 0.42fr 1fr;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 22%, transparent);
}

.dashboard-detail-todo__summary span {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 7px 8px;
  border-right: 1px solid var(--border);
}

.dashboard-detail-todo__summary span:last-child {
  border-right: 0;
}

.dashboard-detail-todo__summary small,
.dashboard-detail-evidence__metrics small {
  color: #667085;
  font-size: 11px;
}

.dashboard-detail-todo__summary strong {
  overflow: hidden;
  color: #172033;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-evidence {
  display: grid;
  gap: 8px;
}

.dashboard-detail-evidence__row {
  display: grid;
  grid-template-columns: minmax(170px, 0.9fr) minmax(260px, 1.3fr);
  gap: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: white;
  padding: 9px 10px;
}

.dashboard-detail-evidence__row strong {
  display: block;
  overflow: hidden;
  color: #172033;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-evidence__row small {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  color: #667085;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-todo--system .dashboard-detail-evidence__row {
  grid-template-columns: minmax(190px, 0.8fr) minmax(300px, 1.35fr);
}

.dashboard-detail-todo--system .dashboard-detail-evidence__row small {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
}

.dashboard-detail-evidence__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.dashboard-detail-evidence__metrics span {
  display: grid;
  gap: 2px;
  min-width: 0;
  padding: 6px 7px;
  border-right: 1px solid var(--border);
  text-align: center;
}

.dashboard-detail-evidence__metrics span:last-child {
  border-right: 0;
}

.dashboard-detail-evidence__metrics strong {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.dashboard-detail-todo__meta span {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 7px 8px;
  border-right: 1px solid var(--border);
}

.dashboard-detail-todo__meta span:last-child {
  border-right: 0;
}

.dashboard-detail-todo__meta small {
  color: #667085;
  font-size: 11px;
}

.dashboard-detail-todo__meta strong {
  overflow: hidden;
  color: #172033;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-todo__error {
  display: grid;
  gap: 5px;
  border: 1px solid #fecdd3;
  border-radius: 8px;
  background: #fff1f2;
  padding: 8px 10px;
}

.dashboard-detail-todo__error code {
  width: fit-content;
  border-radius: 6px;
  background: white;
  padding: 2px 6px;
  color: #be123c;
  font-size: 11px;
}

.dashboard-detail-todo__error small {
  color: #9f1239;
  font-size: 12px;
  line-height: 1.5;
}

.dashboard-detail-row-title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.dashboard-detail-row-title strong {
  overflow: hidden;
  color: #172033;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-detail-row-title code {
  flex: 0 0 auto;
  border-radius: 6px;
  background: var(--muted);
  padding: 2px 6px;
  color: #475467;
  font-size: 11px;
}

.dashboard-detail-action {
  display: flex;
  min-width: 112px;
  flex: 0 0 auto;
  align-items: stretch;
  flex-direction: column;
  justify-content: flex-end;
  gap: 8px;
}

.dashboard-detail-count {
  display: grid;
  min-width: 42px;
  height: 34px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--primary) 16%, var(--border));
  border-radius: 8px;
  background: #eff6ff;
  color: #172033;
  color: #172033;
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.dashboard-confirm-card {
  display: grid;
  gap: 6px;
  border: 1px solid #fecdd3;
  border-radius: 8px;
  background: #fff1f2;
  padding: 10px 12px;
}

.dashboard-confirm-card strong {
  color: #172033;
  font-size: 14px;
}

.dashboard-confirm-card span,
.dashboard-confirm-card small {
  width: fit-content;
  border-radius: 6px;
  background: white;
  padding: 2px 6px;
  color: #be123c;
  font-size: 11px;
}

.dashboard-confirm-card p {
  margin: 0;
  color: #9f1239;
  font-size: 12px;
  line-height: 1.5;
}

.dashboard-detail-rank__index {
  display: grid;
  width: 58px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: color-mix(in srgb, var(--muted) 78%, transparent);
  color: #475467;
  font-size: 12px;
  font-weight: 700;
}

.dashboard-detail-rank__index.is-top {
  background: #eff6ff;
  color: #1d4ed8;
}

.dashboard-detail-rank__body {
  min-width: 0;
  flex: 1;
}

.dashboard-detail-rank__body > small {
  display: block;
  margin-top: 6px;
  color: #667085;
  font-size: 12px;
}

.dashboard-detail-rank__bar {
  overflow: hidden;
  height: 7px;
  margin-top: 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted) 70%, transparent);
}

.dashboard-detail-rank__bar span {
  display: block;
  height: 100%;
  min-width: 8px;
  border-radius: inherit;
  background: #2563eb;
}

.dashboard-detail-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(74px, 1fr));
  flex: 0 0 280px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.dashboard-detail-metrics span {
  display: grid;
  gap: 3px;
  padding: 8px 9px;
  border-right: 1px solid var(--border);
  text-align: center;
}

.dashboard-detail-metrics span:last-child {
  border-right: 0;
}

.dashboard-detail-metrics small {
  color: #667085;
  font-size: 11px;
}

.dashboard-detail-metrics strong {
  color: #172033;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.dashboard-detail-rank--supplier .dashboard-detail-metrics {
  flex-basis: 250px;
}

.dashboard-dialog-table [data-slot="table-head"],
.dashboard-dialog-table [data-slot="table-cell"] {
  height: 40px;
  padding: 7px 8px;
  font-size: 12px;
  text-align: center;
  vertical-align: middle;
}

.dashboard-dialog-table [data-slot="table-head"] {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--card);
}

@media (max-width: 1180px) {
  .dashboard-grid,
  .dashboard-grid--three {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 760px) {
  .dashboard-panel__header {
    flex-direction: column;
  }

  .dashboard-detail-hero,
  .dashboard-detail-todo,
  .dashboard-detail-rank,
  .dashboard-detail-stock__head {
    align-items: stretch;
    flex-direction: column;
  }

  .dashboard-detail-action {
    width: 100%;
    min-width: 0;
    flex-direction: row;
    justify-content: space-between;
  }

  .dashboard-detail-todo__meta {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-detail-todo__meta span,
  .dashboard-detail-todo__summary span {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .dashboard-detail-todo__meta span:last-child,
  .dashboard-detail-todo__summary span:last-child {
    border-bottom: 0;
  }

  .dashboard-detail-todo__summary,
  .dashboard-detail-evidence__row,
  .dashboard-detail-evidence__metrics {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-detail-evidence__metrics span {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .dashboard-detail-evidence__metrics span:last-child {
    border-bottom: 0;
  }

  .dashboard-detail-metrics {
    width: 100%;
    flex-basis: auto;
  }

  .dashboard-detail-stock__metrics {
    grid-template-columns: minmax(0, 1fr);
  }

  .dashboard-detail-stock__metrics span {
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .dashboard-detail-stock__metrics span:last-child {
    border-bottom: 0;
  }

  .dashboard-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
