<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
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
  stockRecordCount: 0,
  warehouseCount: 0,
  productCount: 0,
  lowStockCount: 0,
});

const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const stocks = ref<WarehouseStockListItem[]>([]);
const total = ref(0);
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

async function loadWarehouseOptions() {
  try {
    const page = await listWarehouses({ pageNum: 1, pageSize: 100 });
    warehouseOptions.value = [
      { value: 'all', label: '全部仓库' },
      ...page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` })),
    ];
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
    total.value = page.total;
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

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchStocks();
}, 250);
const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchStocks();
}, 180);
const refreshList = useListRefresh(queryBusy, queryPending, fetchStocks);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  Object.assign(query, {
    warehouseId: 'all', productCode: '', productName: '', inventoryHealth: 'all', reservationState: 'all', pageNum: 1,
  });
  queryPending.value = true;
  debouncedSearch();
}

function handlePageChange(pageNum: number) {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedPageChange(pageNum, query.pageSize);
}

function handlePageSizeChange(pageSize: number) {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedPageChange(1, pageSize);
}

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

function stockRowClass(row: WarehouseStockListItem) {
  if (row.stockQty === 0) return 'bg-rose-50/60';
  if (row.availableQty <= row.safetyStockQty) return 'bg-amber-50/35';
  return '';
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

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">库存记录</span><strong class="mt-1 text-2xl">{{ summary.stockRecordCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">涉及仓库</span><strong class="mt-1 text-2xl">{{ summary.warehouseCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">涉及产品</span><strong class="mt-1 text-2xl">{{ summary.productCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">低库存记录</span><strong class="mt-1 text-2xl text-amber-700">{{ summary.lowStockCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--stocks">
        <div class="space-y-1"><Label class="text-xs">仓库</Label><AnchoredSelect v-model="query.warehouseId" :options="warehouseOptions" placeholder="全部仓库" /></div>
        <div class="space-y-1"><Label class="text-xs">产品编码</Label><Input v-model="query.productCode" placeholder="如 P0001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">产品名称</Label><Input v-model="query.productName" placeholder="请输入产品名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">库存健康</Label><AnchoredSelect v-model="query.inventoryHealth" :options="inventoryHealthOptions" placeholder="全部健康状态" /></div>
        <div class="space-y-1"><Label class="text-xs">占用情况</Label><AnchoredSelect v-model="query.reservationState" :options="reservationStateOptions" placeholder="全部占用情况" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">库存余额</strong><span class="text-xs text-muted-foreground">库存变更请通过出入库或库存调整业务完成</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载当前库存余额</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1280px] table-fixed">
          <colgroup><col class="w-[170px]" /><col class="w-[220px]" /><col class="w-[70px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[115px]" /><col class="w-[105px]" /><col class="w-[150px]" /></colgroup>
          <TableHeader><TableRow><TableHead>仓库</TableHead><TableHead>产品</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-right">当前库存</TableHead><TableHead class="text-right">锁定库存</TableHead><TableHead class="text-right">可用库存</TableHead><TableHead class="text-right">安全库存</TableHead><TableHead class="text-center">库存健康</TableHead><TableHead class="text-center">占用情况</TableHead><TableHead>更新时间</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && stocks.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="stocks.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无符合条件的库存记录</TableCell></TableRow>
            <TableRow v-for="row in stocks" v-else :key="row.stockId" :data-stock-id="row.stockId" :class="stockRowClass(row)">
              <TableCell><div class="flex flex-col gap-1"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.warehouseCode }}</code><span class="truncate font-medium" :title="row.warehouseName">{{ row.warehouseName }}</span></div></TableCell>
              <TableCell><div class="flex flex-col gap-1"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.productCode }}</code><span class="truncate font-medium" :title="row.productName">{{ row.productName }}</span></div></TableCell>
              <TableCell class="text-center">{{ row.unitName }}</TableCell>
              <TableCell class="text-right font-medium tabular-nums">{{ formatQty(row.stockQty) }}</TableCell>
              <TableCell class="text-right tabular-nums" :class="row.lockedQty > 0 ? 'text-blue-700' : 'text-muted-foreground'">{{ formatQty(row.lockedQty) }}</TableCell>
              <TableCell class="text-right font-semibold tabular-nums" :class="row.availableQty === 0 ? 'text-rose-700' : 'text-emerald-700'">{{ formatQty(row.availableQty) }}</TableCell>
              <TableCell class="text-right tabular-nums text-muted-foreground">{{ formatQty(row.safetyStockQty) }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="stockHealth(row).className">{{ stockHealth(row).label }}</Badge></TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="reservationState(row).className">{{ reservationState(row).label }}</Badge></TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updatedAt }}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>
  </section>
</template>

<style scoped>
.filter-grid--stocks {
  grid-template-columns: repeat(5, minmax(0, 1fr)) auto;
}

@media (max-width: 1279px) {
  .filter-grid--stocks {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-grid--stocks .filter-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}

@media (max-width: 640px) {
  .filter-grid--stocks {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
