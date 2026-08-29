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
import DashboardEmptyPanel from '@/components/dashboard/DashboardEmptyPanel.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Separator } from '@/components/ui/separator';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { getDashboardOverview } from '../api';
import type {
  DashboardMetric,
  DashboardOrderStagePermissions,
  DashboardOverview,
  DashboardTodoItem,
  DashboardTrendPermissions,
  DashboardTrendPoint,
} from '../types';

type DetailType = 'todos' | 'products' | 'suppliers' | 'stockAlerts';

const router = useRouter();
const loading = ref(false);
const overview = ref<DashboardOverview | null>(null);
const activeDetail = ref<DetailType | null>(null);
const expandedTodoId = ref<string | null>(null);
const selectedTodoDetailId = ref<string | null>(null);
const trendDayOptions = [7, 15, 30] as const;
const selectedTrendDays = ref<(typeof trendDayOptions)[number]>(7);
const trendTransitioning = ref(false);
const activeTrendTooltip = ref<{ point: DashboardTrendPoint; x: number; y: number; placeBelow: boolean } | null>(null);
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

const canViewAnyOrderStage = computed(() => {
  const perm = orderStagePermissions.value;
  return perm.canViewPurchase || perm.canViewSales;
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

const trendScale = computed(() => {
  const series = visibleTrendSeries.value.map(s => s.key);
  const values = series.length
    ? displayedTrend.value.flatMap(item => series.map(k => item[k as keyof DashboardTrendPoint] as number))
    : [0];
  const rawMin = Math.min(0, ...values);
  const rawMax = Math.max(0, ...values);
  if (rawMin === rawMax) return { min: 0, max: 1, ticks: [1, 0] };

  let step = getNiceTickStep((rawMax - rawMin) / 4);
  let min = Math.floor(rawMin / step) * step;
  let max = Math.ceil(rawMax / step) * step;
  let tickCount = Math.round((max - min) / step) + 1;

  while (tickCount > 6) {
    step = getNiceTickStep(step * 1.01);
    min = Math.floor(rawMin / step) * step;
    max = Math.ceil(rawMax / step) * step;
    tickCount = Math.round((max - min) / step) + 1;
  }

  const ticks: number[] = [];
  for (let tick = max; tick >= min - step * 0.001; tick -= step) {
    ticks.push(Math.abs(tick) < step * 0.001 ? 0 : tick);
  }
  return { min, max, ticks };
});
const trendRange = computed(() => trendScale.value.max - trendScale.value.min);
const trendAxisUnit = computed(() => {
  const maxMagnitude = Math.max(Math.abs(trendScale.value.min), Math.abs(trendScale.value.max));
  return maxMagnitude >= 10000
    ? { divisor: 10000, label: '万元' }
    : { divisor: 1, label: '元' };
});

const trendLines = computed(() => {
  const points = displayedTrend.value;
  const series = visibleTrendSeries.value;
  const xStep = points.length > 1 ? trendChart.plotWidth / (points.length - 1) : trendChart.plotWidth;
  const build = (field: 'salesAmount' | 'purchaseAmount' | 'grossMarginAmount') =>
    points.map((item, index) => `${trendChart.offsetX + index * xStep},${trendY(item[field])}`).join(' ');
  const lines: Record<string, string> = {};
  for (const s of series) {
    const key = s.key === 'salesAmount' ? 'sales' : s.key === 'purchaseAmount' ? 'purchase' : 'grossMargin';
    lines[key] = build(s.key);
  }
  return lines;
});

const trendTicks = computed(() => trendScale.value.ticks);
const trendGridLines = computed(() => trendTicks.value
  .filter(value => value !== 0)
  .map(value => trendY(value)));
const trendZeroAxisY = computed(() => trendY(0));
const trendAxisLabels = computed(() => {
  const points = displayedTrend.value;
  const total = points.length;
  if (total === 0) return [];

  // 折线保留每天的数据点，坐标轴只保留足够间距的代表日期，避免长区间标签相互覆盖。
  const labelCount = total <= 7 ? total : 8;
  const lastLabelIndex = Math.max(labelCount - 1, 1);
  return Array.from({ length: labelCount }, (_, index) => {
    const pointIndex = labelCount === 1 ? 0 : Math.round(((total - 1) * index) / lastLabelIndex);
    const fullDate = points[pointIndex].date;
    return {
      key: `${fullDate}-${index}`,
      date: fullDate.length >= 10 ? fullDate.slice(5, 10) : fullDate,
      fullDate,
      x: trendChart.offsetX + (trendChart.plotWidth * index) / lastLabelIndex,
    };
  });
});

const maxStageCount = computed(() => {
  const perm = orderStagePermissions.value;
  const stages = overview.value?.orderStages || [];
  const values: number[] = [];
  for (const stage of stages) {
    if (perm.canViewPurchase) values.push(stage.purchaseCount);
    if (perm.canViewSales) values.push(stage.salesCount);
  }
  if (values.length === 0) values.push(0);
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

    if (left.completionMode !== right.completionMode) return left.completionMode === 'TRACKED' ? -1 : 1;
    return left.title.localeCompare(right.title, 'zh-CN');
  });
});
const selectedDetailTodo = computed(() => sortedTodos.value.find(todo => todo.todoId === selectedTodoDetailId.value) || null);
const detailTodos = computed(() => (selectedDetailTodo.value ? [selectedDetailTodo.value] : sortedTodos.value));
const visibleTodos = computed(() => sortedTodos.value.slice(0, 8));
const visibleTopProducts = computed(() => overview.value?.topProducts.slice(0, 5) || []);
const visibleSupplierPerformance = computed(() => overview.value?.supplierPerformance.slice(0, 5) || []);
const detailDialogWidth = computed(() => {
  if (activeDetail.value === 'todos') return 'min(740px, calc(100vw - 2rem))';
  if (activeDetail.value === 'stockAlerts') return 'min(780px, calc(100vw - 2rem))';
  return 'min(920px, calc(100vw - 2rem))';
});

const detailTitle = computed(() => {
  if (activeDetail.value === 'todos' && selectedDetailTodo.value) return `${selectedDetailTodo.value.title}详情`;

  const titles: Record<DetailType, string> = {
    todos: '业务待办详情',
    products: '销售商品排行详情',
    suppliers: '供应商履约详情',
    stockAlerts: '库存预警详情',
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
  if (Math.abs(value) >= 10000) return `￥${(value / 10000).toFixed(1)}万`;
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function formatCompactCurrency(value: number) {
  return Math.abs(value) >= 10000 ? `${(value / 10000).toFixed(1)}万` : formatNumber(value);
}

function formatDetailedCurrency(value: number) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(value);
}

function formatTrendAxisTick(value: number) {
  return formatNumber(value / trendAxisUnit.value.divisor);
}

function getNiceTickStep(rawStep: number) {
  if (!Number.isFinite(rawStep) || rawStep <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(rawStep));
  const normalized = rawStep / magnitude;
  const nice = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return nice * magnitude;
}

function valueTone(value: number) {
  if (value > 0) return 'is-positive';
  if (value < 0) return 'is-negative';
  return 'is-neutral';
}

function showTrendTooltip(event: MouseEvent | FocusEvent, point: DashboardTrendPoint) {
  const target = event.currentTarget as SVGCircleElement;
  const chart = target.closest('.dashboard-trend-chart');
  if (!chart) return;

  const chartRect = chart.getBoundingClientRect();
  const pointRect = target.getBoundingClientRect();
  const pointX = pointRect.left + pointRect.width / 2 - chartRect.left;
  const pointY = pointRect.top + pointRect.height / 2 - chartRect.top;
  activeTrendTooltip.value = {
    point,
    x: Math.min(92, Math.max(8, (pointX / chartRect.width) * 100)),
    y: Math.min(92, Math.max(8, (pointY / chartRect.height) * 100)),
    placeBelow: pointY < chartRect.height * 0.28,
  };
}

function hideTrendTooltip() {
  activeTrendTooltip.value = null;
}

function trendX(index: number) {
  const points = displayedTrend.value;
  return trendChart.offsetX + index * (trendChart.plotWidth / Math.max(points.length - 1, 1));
}

function trendY(value: number) {
  return trendChart.plotHeight - trendChart.topPadding
    - ((value - trendScale.value.min) / trendRange.value) * trendChart.valueHeight;
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

function openDetail(type: DetailType, todoId?: string) {
  activeDetail.value = type;
  selectedTodoDetailId.value = type === 'todos' ? todoId || null : null;
  expandedTodoId.value = type === 'todos' && todoId ? todoId : null;
}

function setDetailDialogOpen(open: boolean) {
  if (open) return;
  activeDetail.value = null;
  selectedTodoDetailId.value = null;
  expandedTodoId.value = null;
}

function toggleTodoEvidence(todo: DashboardTodoItem) {
  expandedTodoId.value = expandedTodoId.value === todo.todoId ? null : todo.todoId;
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
      <div class="dashboard-heading-actions">
        <span data-dashboard-refresh-status aria-live="polite">
          {{ loading ? '正在同步经营数据...' : (overview ? `更新于 ${overview.refreshedAt}` : '等待加载经营数据') }}
        </span>
        <Button size="sm" variant="outline" :disabled="loading" @click="loadOverview">
          <RefreshCw class="mr-2 h-4 w-4" :class="{ 'animate-spin': loading }" aria-hidden="true" />
          {{ loading ? '刷新中' : '刷新' }}
        </Button>
      </div>
    </div>

    <div class="relative">

      <div v-if="overview" class="space-y-4">
        <div class="summary-strip dashboard-metrics">
          <div
            v-for="metric in overview.metrics"
            :key="metric.label"
            :class="['summary-item', 'dashboard-metric', `dashboard-metric--${metric.status}`]"
          >
            <div class="dashboard-metric__head">
              <span>{{ metric.label }}</span>
              <i aria-hidden="true" />
            </div>
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
                <p class="mt-1 text-xs text-muted-foreground">
                  <template v-if="!canViewAnyTrend">需要销售或采购权限后查看</template>
                  <template v-else>近 {{ selectedTrendDays }} 日按当前权限展示销售、采购和毛利变化</template>
                </p>
              </div>
              <div v-if="canViewAnyTrend" class="dashboard-range-switch" aria-label="经营趋势天数选择">
                <Button
                  v-for="option in trendDayOptions"
                  :key="option"
                  size="sm"
                  :variant="selectedTrendDays === option ? 'default' : 'outline'"
                  :aria-pressed="selectedTrendDays === option"
                  @click="selectTrendDays(option)"
                >
                  {{ option }}天
                </Button>
              </div>
            </CardHeader>
            <CardContent>
              <div v-if="!canViewAnyTrend" data-dashboard-trend-empty>
                <DashboardEmptyPanel
                  title="暂无经营趋势数据"
                  description="需要销售或采购模块的查询权限才能查看趋势曲线。"
                  :required-permissions="['sales:query', 'purchase:query']"
                />
              </div>
              <template v-else>
              <div
                data-dashboard-trend-summary
                class="dashboard-trend-summary"
                :class="{ 'is-transitioning': trendTransitioning }"
                aria-live="polite"
              >
                <div
                  v-for="item in trendSummary"
                  :key="item.key"
                  :class="['dashboard-trend-summary__item', `dashboard-trend-summary__item--${item.tone}`]"
                >
                  <span class="dashboard-trend-summary__marker" aria-hidden="true" />
                  <small>{{ item.label }}</small>
                  <strong :class="item.className">{{ item.value }}</strong>
                  <span class="dashboard-trend-summary__hint">{{ item.hint }}</span>
                </div>
              </div>
              <div class="dashboard-trend-chart-wrap">
              <svg
                class="dashboard-trend-chart"
                :class="{ 'is-transitioning': trendTransitioning }"
                viewBox="0 0 700 260"
                role="img"
                :aria-label="`近 ${selectedTrendDays} 日经营趋势`"
              >
                <g class="dashboard-grid-lines">
                  <line v-for="(lineY, index) in trendGridLines" :key="index" x1="34" :y1="lineY" x2="622" :y2="lineY" />
                  <line data-dashboard-trend-zero-axis class="dashboard-trend-zero-axis" x1="34" :y1="trendZeroAxisY" x2="622" :y2="trendZeroAxisY" />
                </g>
                <g class="dashboard-trend-axis">
                  <text class="dashboard-trend-axis__unit" x="638" y="15">金额（{{ trendAxisUnit.label }}）</text>
                  <text v-for="tick in trendTicks" :key="tick" x="638" :y="trendY(tick) + 4">{{ formatTrendAxisTick(tick) }}</text>
                </g>
                <g :key="selectedTrendDays" class="dashboard-trend-layer">
                  <polyline v-for="series in visibleTrendSeries" :key="series.key" :points="trendLines[series.key === 'salesAmount' ? 'sales' : series.key === 'purchaseAmount' ? 'purchase' : 'grossMargin']" class="dashboard-trend" :class="series.className" />
                  <g class="dashboard-trend-points">
                    <g v-for="series in visibleTrendSeries" :key="series.key">
                      <circle
                        v-for="(point, index) in displayedTrend"
                        :key="`${series.key}-${point.date}`"
                        :cx="trendX(index)"
                        :cy="trendY(point[series.key])"
                        r="3.2"
                        :class="[series.className, { 'is-negative': series.key === 'grossMarginAmount' && point[series.key] < 0 }]"
                        tabindex="0"
                        :aria-label="`${point.date} ${series.label}：${formatDetailedCurrency(point[series.key])}`"
                        @mouseenter="showTrendTooltip($event, point)"
                        @mouseleave="hideTrendTooltip"
                        @focus="showTrendTooltip($event, point)"
                        @blur="hideTrendTooltip"
                      />
                    </g>
                  </g>
                  <g class="dashboard-trend-labels">
                    <text v-for="label in trendAxisLabels" :key="label.key" :x="label.x" y="238" text-anchor="middle">
                      <title>{{ label.fullDate }}</title>
                      {{ label.date }}
                    </text>
                  </g>
                </g>
              </svg>
              <div
                v-if="activeTrendTooltip"
                data-dashboard-trend-tooltip
                :class="['dashboard-trend-tooltip', { 'is-below': activeTrendTooltip.placeBelow }]"
                :style="{ left: `${activeTrendTooltip.x}%`, top: `${activeTrendTooltip.y}%` }"
                role="tooltip"
              >
                <strong>{{ activeTrendTooltip.point.date }}</strong>
                <span v-for="tooltipSeries in visibleTrendSeries" :key="tooltipSeries.key">
                  <i :class="tooltipSeries.colorClass" />
                  {{ tooltipSeries.label }}
                  <b :class="valueTone(activeTrendTooltip.point[tooltipSeries.key])">{{ formatDetailedCurrency(activeTrendTooltip.point[tooltipSeries.key]) }}</b>
                </span>
              </div>
            </div>
              <div class="dashboard-legend">
                <span v-for="series in visibleTrendSeries" :key="series.key"><i :class="series.colorClass" />{{ series.label }}</span>
              </div>
              <div
                class="dashboard-trend-values-scroll"
                aria-label="经营趋势数值明细"
                role="region"
                tabindex="0"
              >
                <div
                  class="dashboard-trend-values"
                  :class="{ 'is-transitioning': trendTransitioning }"
                  :style="{
                    '--trend-day-count': displayedTrend.length,
                    '--trend-table-min-width': `${96 + displayedTrend.length * 88}px`,
                  }"
                >
                  <div class="dashboard-trend-values__head">指标</div>
                  <div v-for="point in displayedTrend" :key="`head-${point.date}`" class="dashboard-trend-values__head">{{ point.date }}</div>
                  <template v-for="series in visibleTrendSeries" :key="series.key">
                    <div class="dashboard-trend-values__label"><i :class="series.colorClass" />{{ series.label }}</div>
                    <div v-for="point in displayedTrend" :key="`${series.key}-${point.date}`" :class="['dashboard-trend-values__value', valueTone(point[series.key])]">
                      {{ formatCompactCurrency(point[series.key]) }}
                    </div>
                  </template>
                </div>
              </div>
              </template>
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
                :aria-label="`${todo.title}，${todo.count}项，${priorityText(todo.priority)}，查看详情`"
                @click="openDetail('todos', todo.todoId)"
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
                <span class="dashboard-todo__end" aria-hidden="true">
                  <span class="dashboard-todo__count">{{ todo.count }}</span>
                  <ChevronRight class="size-4 text-muted-foreground" />
                </span>
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
                <span v-if="orderStagePermissions.canViewPurchase"><i class="bg-amber-500" />采购单</span>
                <span v-if="orderStagePermissions.canViewSales"><i class="bg-blue-600" />销售单</span>
              </div>
              <div v-if="!canViewAnyOrderStage" data-dashboard-order-empty>
                <DashboardEmptyPanel
                  title="暂无订单流转数据"
                  description="需要采购或销售模块的查询权限才能查看订单阶段分布。"
                  :required-permissions="['purchase:query', 'sales:query']"
                />
              </div>
              <template v-else>
              <div v-for="stage in overview.orderStages" :key="stage.stage" class="dashboard-stage">
                <div class="flex items-center justify-between text-xs">
                  <span class="font-medium text-slate-700">{{ stage.stage }}</span>
                  <span class="dashboard-stage__counts">
                    <span v-if="orderStagePermissions.canViewPurchase"><i class="bg-amber-500" />{{ stage.purchaseCount }}</span>
                    <span v-if="orderStagePermissions.canViewSales"><i class="bg-blue-600" />{{ stage.salesCount }}</span>
                  </span>
                </div>
                <div class="dashboard-stage__bars">
                  <span v-if="orderStagePermissions.canViewPurchase" class="bg-amber-500" :style="{ width: `${(stage.purchaseCount / maxStageCount) * 100}%` }" />
                  <span v-if="orderStagePermissions.canViewSales" class="bg-blue-600" :style="{ width: `${(stage.salesCount / maxStageCount) * 100}%` }" />
                </div>
              </div>
              </template>
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

        <Dialog :open="activeDetail !== null" @update:open="setDetailDialogOpen">
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
              <div v-for="todo in detailTodos" :key="todo.todoId" class="dashboard-detail-todo" :class="{ 'dashboard-detail-todo--system': isTrackedTodo(todo) }">
                <span class="dashboard-todo__icon">
                  <component :is="todoIcon(todo)" class="h-4 w-4" />
                </span>
                <div class="dashboard-detail-todo__body">
                  <div class="dashboard-detail-row-title">
                    <strong>{{ todo.title }}</strong>
                    <Badge variant="outline" :class="priorityClass(todo.priority)">{{ priorityText(todo.priority) }}</Badge>
                    <Badge variant="outline" class="border-slate-200 bg-slate-50 text-slate-600">{{ businessLabel(todo) }}</Badge>
                    <Badge v-if="isTrackedTodo(todo)" variant="outline" class="border-slate-200 bg-slate-50 text-slate-700">
                      数据库记录
                    </Badge>
                  </div>
                  <p>{{ todo.description }}</p>
                  <div v-if="isTrackedTodo(todo)" class="dashboard-detail-todo__meta">
                    <span><small>状态</small><strong>{{ statusText(todo.status) }}</strong></span>
                    <span><small>{{ todoSourceLabel(todo) }}</small><strong>{{ todoSourceText(todo) }}</strong></span>
                    <span><small>{{ todoOccurredAtLabel(todo) }}</small><strong>{{ todo.occurredAt || '-' }}</strong></span>
                  </div>
                  <div v-else class="dashboard-detail-todo__summary">
                    <span><small>待处理数量</small><strong>{{ todo.count }}</strong></span>
                    <span><small>完成方式</small><strong>处理对应业务后自动完成</strong></span>
                  </div>
                  <div
                    v-if="todo.evidence.length > 0"
                    class="dashboard-detail-evidence-collapse"
                    :class="{ 'is-open': expandedTodoId === todo.todoId }"
                    :aria-hidden="expandedTodoId !== todo.todoId"
                  >
                    <div class="dashboard-detail-evidence-collapse__inner">
                      <div class="dashboard-detail-evidence">
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
                    variant="outline"
                    @click="goToTodoRoute(todo)"
                  >
                    前往完成
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

      </div>
      <div v-else class="dashboard-skeleton space-y-4" :aria-busy="loading" data-dashboard-skeleton>
        <div class="summary-strip dashboard-metrics">
          <div v-for="index in 4" :key="index" class="summary-item dashboard-metric">
            <span class="dashboard-skeleton__line dashboard-skeleton__line--label" />
            <strong class="dashboard-skeleton__line dashboard-skeleton__line--value" />
            <span class="dashboard-skeleton__line dashboard-skeleton__line--meta" />
          </div>
        </div>

        <div class="dashboard-grid">
          <Card class="dashboard-panel dashboard-panel--trend">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base"><BarChart3 class="h-4 w-4 text-primary" />经营趋势</CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">正在准备经营数据</p>
              </div>
            </CardHeader>
            <CardContent class="dashboard-skeleton__trend">
              <span class="dashboard-skeleton__line dashboard-skeleton__line--wide" />
              <span class="dashboard-skeleton__chart" />
              <span class="dashboard-skeleton__line dashboard-skeleton__line--wide" />
            </CardContent>
          </Card>

          <Card class="dashboard-panel dashboard-panel--todos">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="flex items-center gap-2 text-base"><Clock3 class="h-4 w-4 text-primary" />业务待办</CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">正在整理待处理事项</p>
              </div>
            </CardHeader>
            <CardContent class="dashboard-skeleton__todos">
              <span v-for="index in 5" :key="index" class="dashboard-skeleton__todo" />
            </CardContent>
          </Card>
        </div>

        <div class="dashboard-grid dashboard-grid--three">
          <Card v-for="title in ['订单流转', '销售商品排行', '供应商履约']" :key="title" class="dashboard-panel dashboard-skeleton__compact-panel">
            <CardHeader class="dashboard-panel__header">
              <div>
                <CardTitle class="text-base">{{ title }}</CardTitle>
                <p class="mt-1 text-xs text-muted-foreground">正在加载</p>
              </div>
            </CardHeader>
            <CardContent class="dashboard-skeleton__rows">
              <span v-for="index in 4" :key="index" class="dashboard-skeleton__line dashboard-skeleton__line--wide" />
            </CardContent>
          </Card>
        </div>

        <Card class="dashboard-panel">
          <CardHeader class="dashboard-panel__header">
            <div>
              <CardTitle class="flex items-center gap-2 text-base"><AlertTriangle class="h-4 w-4 text-amber-600" />库存预警</CardTitle>
              <p class="mt-1 text-xs text-muted-foreground">正在汇总库存风险</p>
            </div>
          </CardHeader>
          <CardContent class="dashboard-skeleton__table">
            <span v-for="index in 5" :key="index" class="dashboard-skeleton__line dashboard-skeleton__line--wide" />
          </CardContent>
        </Card>
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

.dashboard-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  overflow: visible;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.dashboard-metric {
  position: relative;
  display: grid;
  min-height: 104px;
  gap: 8px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--muted) 36%, white), white 62%);
  padding: 14px 15px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.dashboard-metric::before {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 3px;
  background: #94a3b8;
  content: '';
}

.dashboard-metric--good::before { background: #059669; }
.dashboard-metric--watch::before { background: #d97706; }
.dashboard-metric--risk::before { background: #e11d48; }
.dashboard-metric--neutral::before { background: #64748b; }

.dashboard-metric__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dashboard-metric__head > span {
  overflow: hidden;
  color: #667085;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-metric__head > i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #94a3b8;
}

.dashboard-metric--good .dashboard-metric__head > i { background: #059669; }
.dashboard-metric--watch .dashboard-metric__head > i { background: #d97706; }
.dashboard-metric--risk .dashboard-metric__head > i { background: #e11d48; }
.dashboard-metric--neutral .dashboard-metric__head > i { background: #64748b; }

.dashboard-metric > strong {
  overflow: hidden;
  color: #172033;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-metric > div:last-child {
  align-self: end;
  margin-top: 0 !important;
}

.dashboard-heading-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dashboard-heading-actions > span {
  color: var(--muted-foreground);
  font-size: 12px;
  white-space: nowrap;
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

.dashboard-skeleton {
  pointer-events: none;
}

.dashboard-skeleton__line,
.dashboard-skeleton__chart,
.dashboard-skeleton__todo {
  display: block;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef2f7 18%, #f8fafc 38%, #eef2f7 62%);
  background-size: 200% 100%;
  animation: dashboard-skeleton-shimmer 1.5s ease-in-out infinite;
}

.dashboard-skeleton__line--label { width: 38%; height: 12px; }
.dashboard-skeleton__line--value { width: 58%; height: 23px; margin-top: 10px; }
.dashboard-skeleton__line--meta { width: 66%; height: 12px; margin-top: 14px; }
.dashboard-skeleton__line--wide { width: 100%; height: 13px; }

.dashboard-skeleton__trend,
.dashboard-skeleton__todos,
.dashboard-skeleton__rows,
.dashboard-skeleton__table {
  display: grid;
  gap: 12px;
}

.dashboard-skeleton__trend { grid-template-rows: auto 1fr auto; min-height: 316px; }
.dashboard-skeleton__chart { min-height: 220px; }
.dashboard-skeleton__todos { align-content: start; }
.dashboard-skeleton__todo { height: 60px; }
.dashboard-skeleton__compact-panel { min-height: 246px; }
.dashboard-skeleton__rows { gap: 18px; }
.dashboard-skeleton__table { grid-template-columns: repeat(5, minmax(0, 1fr)); min-height: 88px; align-items: center; }

@keyframes dashboard-skeleton-shimmer {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
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

.dashboard-trend-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 12px;
}

.dashboard-trend-summary__item {
  position: relative;
  display: grid;
  min-width: 0;
  min-height: 82px;
  gap: 4px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--muted) 42%, white), white 62%);
  padding: 12px 13px 10px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
}

.dashboard-trend-summary__item::after {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  height: 2px;
  background: #94a3b8;
  content: '';
}

.dashboard-trend-summary__item--sales::after { background: #2563eb; }
.dashboard-trend-summary__item--purchase::after { background: #f59e0b; }
.dashboard-trend-summary__item--margin::after { background: #059669; }
.dashboard-trend-summary__item--margin:has(.is-negative)::after { background: #e11d48; }
.dashboard-trend-summary__item--rate::after { background: #7c3aed; }

.dashboard-trend-summary__marker {
  position: absolute;
  top: 11px;
  right: 12px;
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted-foreground) 35%, transparent);
}

.dashboard-trend-summary__item--sales .dashboard-trend-summary__marker { background: #2563eb; }
.dashboard-trend-summary__item--purchase .dashboard-trend-summary__marker { background: #f59e0b; }
.dashboard-trend-summary__item--margin .dashboard-trend-summary__marker { background: #059669; }
.dashboard-trend-summary__item--margin:has(.is-negative) .dashboard-trend-summary__marker { background: #e11d48; }
.dashboard-trend-summary__item--rate .dashboard-trend-summary__marker { background: #7c3aed; }

.dashboard-trend-summary small {
  overflow: hidden;
  padding-right: 14px;
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.01em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-trend-summary strong {
  overflow: hidden;
  color: #172033;
  font-size: 18px;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-trend-summary__hint {
  overflow: hidden;
  color: #667085;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-trend-summary.is-transitioning {
  animation: dashboard-trend-soft-enter var(--motion-duration-slow) var(--motion-ease-standard);
}

.dashboard-trend-chart-wrap {
  position: relative;
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
  animation: dashboard-trend-soft-enter var(--motion-duration-slow) var(--motion-ease-standard);
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

.dashboard-trend-zero-axis {
  stroke: #98a2b3;
  stroke-width: 1.5;
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

.dashboard-trend-axis .dashboard-trend-axis__unit {
  fill: #667085;
  font-size: 9px;
  font-weight: 700;
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

circle.dashboard-trend--margin.is-negative {
  fill: #e11d48;
}

.dashboard-trend-points circle {
  cursor: pointer;
  transition: r 140ms ease, stroke-width 140ms ease;
}

.dashboard-trend-points circle:hover,
.dashboard-trend-points circle:focus-visible {
  r: 4.8px;
  stroke-width: 3px;
  outline: none;
}

.dashboard-trend-tooltip {
  position: absolute;
  z-index: 4;
  display: grid;
  min-width: 168px;
  gap: 6px;
  border: 1px solid #dbe3ee;
  border-radius: 10px;
  background: #fff;
  padding: 9px 10px;
  box-shadow: 0 12px 28px rgb(15 23 42 / 14%);
  color: #667085;
  pointer-events: none;
  transform: translate(-50%, calc(-100% - 10px));
}

.dashboard-trend-tooltip.is-below {
  transform: translate(-50%, 10px);
}

.dashboard-trend-tooltip strong {
  color: #172033;
  font-size: 12px;
}

.dashboard-trend-tooltip span {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}

.dashboard-trend-tooltip i {
  width: 7px;
  height: 7px;
  border-radius: 999px;
}

.dashboard-trend-tooltip b {
  color: #172033;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.dashboard-trend-tooltip b.is-positive { color: #15803d; }
.dashboard-trend-tooltip b.is-negative { color: #be123c; }
.dashboard-trend-tooltip b.is-neutral { color: #667085; }

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

.dashboard-trend-values-scroll {
  max-width: 100%;
  overflow-x: auto;
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.dashboard-trend-values-scroll:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--ring) 72%, transparent);
  outline-offset: 2px;
}

.dashboard-trend-values {
  display: grid;
  grid-template-columns: 96px repeat(var(--trend-day-count, 7), minmax(88px, 1fr));
  min-width: max(100%, var(--trend-table-min-width));
  gap: 0;
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

.dashboard-trend-values__head:first-child,
.dashboard-trend-values__label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  position: sticky;
  left: 0;
  z-index: 1;
  background: color-mix(in srgb, var(--muted) 92%, white);
  box-shadow: 1px 0 0 var(--border);
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

.dashboard-trend-values__value.is-positive,
.dashboard-trend-summary .is-positive {
  color: #15803d;
  font-weight: 650;
}

.dashboard-trend-values__value.is-negative,
.dashboard-trend-summary .is-negative {
  color: #be123c;
  font-weight: 650;
}

.dashboard-trend-values__value.is-neutral,
.dashboard-trend-summary .is-neutral {
  color: #667085;
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
  min-height: 60px;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 2px);
  background: color-mix(in srgb, var(--muted) 28%, transparent);
  padding: 9px 12px;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease,
    transform 160ms ease;
}

.dashboard-todos-list {
  justify-content: space-between;
  gap: 10px;
  padding-top: 0;
  padding-bottom: 12px;
}

.dashboard-todos-list > * + * { margin-top: 0; }

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

.dashboard-todo__end {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
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
  min-width: 0;
  gap: 8px;
  overflow: hidden;
}

.dashboard-detail-evidence-collapse {
  display: grid;
  grid-template-rows: 0fr;
  min-width: 0;
  margin-top: -8px;
  overflow: hidden;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-4px);
  visibility: hidden;
  transition:
    grid-template-rows 180ms cubic-bezier(0.4, 0, 0.2, 1),
    margin-top 180ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 140ms ease,
    transform 180ms cubic-bezier(0.4, 0, 0.2, 1),
    visibility 0s linear 180ms;
  will-change: grid-template-rows, margin-top, opacity, transform;
}

.dashboard-detail-evidence-collapse.is-open {
  grid-template-rows: 1fr;
  margin-top: 0;
  opacity: 1;
  pointer-events: auto;
  transform: translateY(0);
  visibility: visible;
  transition:
    grid-template-rows 180ms cubic-bezier(0.4, 0, 0.2, 1),
    margin-top 180ms cubic-bezier(0.4, 0, 0.2, 1),
    opacity 140ms ease,
    transform 180ms cubic-bezier(0.4, 0, 0.2, 1),
    visibility 0s linear 0s;
}

.dashboard-detail-evidence-collapse__inner {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.dashboard-detail-evidence__row {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.25fr);
  min-width: 0;
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
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
}

.dashboard-detail-todo--system .dashboard-detail-evidence__row small {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
}

.dashboard-detail-evidence__metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  min-width: 0;
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
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  .dashboard-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dashboard-metric {
    min-height: 98px;
  }

  .dashboard-heading-actions {
    width: 100%;
    align-items: flex-end;
    flex-direction: column-reverse;
  }

  .dashboard-trend-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

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

@media (prefers-reduced-motion: reduce) {
  .dashboard-skeleton__line,
  .dashboard-skeleton__chart,
  .dashboard-skeleton__todo {
    animation: none;
  }
}
</style>
