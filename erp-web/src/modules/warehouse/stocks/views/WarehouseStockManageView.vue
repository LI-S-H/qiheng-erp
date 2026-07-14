<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import { listWarehouses } from '../../warehouses/api';
import { listWarehouseStocks } from '../api';
import type {
  InventoryHealth,
  ReservationState,
  WarehouseStockListItem,
  WarehouseStockQuery,
  WarehouseStockSummary,
} from '../types';

const emptySummary = (): WarehouseStockSummary => ({
  warehouseCount: 0,
  productCount: 0,
  lowStockCount: 0,
  noAvailableCount: 0,
  lockedCount: 0,
});

const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const stocks = ref<WarehouseStockListItem[]>([]);
const summary = reactive(emptySummary());
const warehouseOptions = ref<Array<{ value: string; label: string }>>([{ value: 'all', label: '全部仓库' }]);
const query = reactive<WarehouseStockQuery>({
  warehouseId: 'all',
  productCode: '',
  productName: '',
  inventoryHealth: 'all',
  reservationState: 'all',
  pageNum: 1,
  pageSize: 10,
});

const queryBusy = computed(() => loading.value || queryPending.value);
const hasNextPage = computed(() => stocks.value.length >= query.pageSize);
const selectedWarehouseLabel = computed(() => query.warehouseId === 'all' ? '全部仓库' : warehouseOptions.value.find(item => item.value === query.warehouseId)?.label || '');
const summaryItems = computed(() => [
  { key: 'warehouse', label: '本页仓库', value: summary.warehouseCount },
  { key: 'product', label: '本页产品', value: summary.productCount },
  { key: 'low-stock', label: '本页低库存', value: summary.lowStockCount, tone: 'warning' as const },
  { key: 'locked', label: '本页已锁定', value: summary.lockedCount },
]);
const inventoryHealthOptions: Array<{ value: InventoryHealth | 'all'; label: string }> = [
  { value: 'all', label: '全部健康状态' },
  { value: 'NORMAL', label: '正常库存' },
  { value: 'LOW_STOCK', label: '低库存' },
  { value: 'NO_AVAILABLE', label: '无可用库存' },
  { value: 'OUT_OF_STOCK', label: '零库存' },
];
const reservationStateOptions: Array<{ value: ReservationState | 'all'; label: string }> = [
  { value: 'all', label: '全部占用情况' },
  { value: 'UNLOCKED', label: '未锁定' },
  { value: 'PARTIALLY_LOCKED', label: '部分锁定' },
  { value: 'FULLY_LOCKED', label: '全部锁定' },
];

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}

function mergeWarehouseOptions(options: Array<{ value: string; label: string }>) {
  const cache = new Map(warehouseOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  warehouseOptions.value = [
    { value: 'all', label: '全部仓库' },
    ...Array.from(cache.values()).filter(item => item.value !== 'all'),
  ];
}

async function fetchWarehouseSearchOptions(keyword: string) {
  const page = await listWarehouses({
    pageNum: 1,
    pageSize: 10,
    ...warehouseKeywordQuery(keyword),
  });
  const options = page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
  mergeWarehouseOptions(options);
  return options;
}

async function loadWarehouseOptions() {
  try {
    const page = await listWarehouses({ pageNum: 1, pageSize: 10 });
    mergeWarehouseOptions(page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` })));
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '仓库选项加载失败');
  }
}

async function fetchStocks() {
  const currentSequence = ++requestSequence.value;
  loading.value = true;
  try {
    const page = await listWarehouseStocks(query);
    if (currentSequence !== requestSequence.value) return;
    stocks.value = page.records;
    Object.assign(summary, page.summary);
  } catch (error) {
    if (currentSequence === requestSequence.value) toast.warning(getApiErrorMessage(error) || '库存查询失败');
  } finally {
    if (currentSequence === requestSequence.value) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const {
  handleSearch,
  handleReset,
  handlePageChange,
  handlePageSizeChange,
  refreshList,
} = usePagedQuery({
  query,
  busy: queryBusy,
  pending: queryPending,
  load: fetchStocks,
  resetFilters: () => {
    Object.assign(query, { warehouseId: 'all', productCode: '', productName: '', inventoryHealth: 'all', reservationState: 'all' });
  },
});

function formatQty(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value);
}

function stockHealth(row: WarehouseStockListItem) {
  if (row.stockQty === 0) return { label: '零库存', className: 'border-rose-200 bg-rose-50 text-rose-700' };
  if (row.availableQty === 0) return { label: '无可用库存', className: 'border-rose-200 bg-rose-50 text-rose-700' };
  if (row.availableQty <= row.safetyStockQty) return { label: '低库存', className: 'border-amber-200 bg-amber-50 text-amber-700' };
  return { label: '库存正常', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' };
}

function reservationState(row: WarehouseStockListItem) {
  if (row.lockedQty === 0) return { label: '未锁定', className: 'border-slate-200 bg-slate-50 text-slate-600' };
  if (row.lockedQty === row.stockQty) return { label: '全部锁定', className: 'border-violet-200 bg-violet-50 text-violet-700' };
  return { label: '部分锁定', className: 'border-blue-200 bg-blue-50 text-blue-700' };
}

type StockRiskLevel = 'normal' | 'warning' | 'critical';

function stockRiskLevel(row: WarehouseStockListItem): StockRiskLevel {
  if (row.stockQty === 0 || row.availableQty === 0) return 'critical';
  if (row.availableQty <= row.safetyStockQty) return 'warning';
  return 'normal';
}

function stockRowClass(row: WarehouseStockListItem) {
  const riskLevel = stockRiskLevel(row);
  return ['inventory-risk-row', `inventory-risk-row--${riskLevel}`];
}

onMounted(() => {
  loadWarehouseOptions();
  fetchStocks();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">库存管理</h1>
        <p class="page-description">按仓库和产品查看当前库存、销售锁定库存与可用库存</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" aria-label="库存数据汇总" />

    <ListFilterPanel layout="content" aria-label="库存筛选">
        <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">仓库</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">产品编码</Label><Input v-model="query.productCode" placeholder="如 P000001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">产品名称</Label><Input v-model="query.productName" placeholder="请输入产品名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">库存健康</Label><AnchoredSelect v-model="query.inventoryHealth" :options="inventoryHealthOptions" placeholder="全部健康状态" /></div>
        <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">占用情况</Label><AnchoredSelect v-model="query.reservationState" :options="reservationStateOptions" placeholder="全部占用情况" /></div>
      <template #actions>
        <ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" />
      </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">库存余额</strong><span class="text-xs text-muted-foreground">库存变更请通过出入库或库存调整业务完成</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载当前库存余额</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1250px] table-fixed">
          <colgroup><col class="w-[170px]" /><col class="w-[220px]" /><col class="w-[70px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[115px]" /><col class="w-[105px]" /><col class="w-[150px]" /></colgroup>
          <TableHeader><TableRow><TableHead>仓库</TableHead><TableHead>产品</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-right">当前库存</TableHead><TableHead class="text-right">锁定库存</TableHead><TableHead class="text-right">可用库存</TableHead><TableHead class="text-right">安全库存</TableHead><TableHead class="text-center">库存健康</TableHead><TableHead class="text-center">占用情况</TableHead><TableHead>更新时间</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && stocks.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="stocks.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无符合条件的库存记录</TableCell></TableRow>
            <TableRow v-for="row in stocks" v-else :key="row.stockId" :data-stock-id="row.stockId" :data-stock-risk="stockRiskLevel(row)" :class="stockRowClass(row)">
              <TableCell><div class="flex flex-col items-center gap-1 text-center"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.warehouseCode }}</code><span class="max-w-full truncate font-medium" :title="row.warehouseName">{{ row.warehouseName }}</span></div></TableCell>
              <TableCell><div class="flex flex-col items-center gap-1 text-center"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.productCode }}</code><span class="max-w-full truncate font-medium" :title="row.productName">{{ row.productName }}</span></div></TableCell>
              <TableCell class="text-center">{{ row.unitName }}</TableCell>
              <TableCell class="text-right font-medium tabular-nums">{{ formatQty(row.stockQty) }}</TableCell>
              <TableCell class="text-right tabular-nums" :class="row.lockedQty > 0 ? 'text-blue-700' : 'text-muted-foreground'">{{ formatQty(row.lockedQty) }}</TableCell>
              <TableCell class="text-right font-semibold tabular-nums" :class="row.availableQty === 0 ? 'text-rose-700' : 'text-emerald-700'">{{ formatQty(row.availableQty) }}</TableCell>
              <TableCell class="text-right tabular-nums text-muted-foreground">{{ formatQty(row.safetyStockQty) }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="stockHealth(row).className">{{ stockHealth(row).label }}</Badge></TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="reservationState(row).className">{{ reservationState(row).label }}</Badge></TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updateTime }}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination simple :current-count="stocks.length" :has-next="hasNextPage" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>
  </section>
</template>

<style scoped>
.inventory-risk-row :deep([data-slot='table-cell']) {
  transition: background-color var(--motion-duration-fast) ease;
}

.inventory-risk-row--normal :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #22c55e 5%, var(--card));
}

.inventory-risk-row--normal:hover :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #22c55e 8%, var(--card));
}

.inventory-risk-row--warning :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #f59e0b 7%, var(--card));
}

.inventory-risk-row--warning:hover :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #f59e0b 10%, var(--card));
}

.inventory-risk-row--critical :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #f43f5e 7%, var(--card));
}

.inventory-risk-row--critical:hover :deep([data-slot='table-cell']) {
  background-color: color-mix(in srgb, #f43f5e 10%, var(--card));
}

.inventory-risk-row--normal :deep([data-slot='table-cell']:first-child) {
  box-shadow: inset 2px 0 #86efac;
}

.inventory-risk-row--warning :deep([data-slot='table-cell']:first-child) {
  box-shadow: inset 2px 0 #fbbf24;
}

.inventory-risk-row--critical :deep([data-slot='table-cell']:first-child) {
  box-shadow: inset 2px 0 #fb7185;
}

</style>
