<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { Eye } from 'lucide-vue-next';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import { listWarehouses } from '../../warehouses/api';
import { getStockBillDetail, listStockBills } from '../api';
import type {
  StockBillDetail,
  StockBillListItem,
  StockBillQuery,
  StockBillStatus,
  StockBillSummary,
  StockBillType,
} from '../types';

const emptySummary = (): StockBillSummary => ({ stockBillCount: 0, inboundCount: 0, outboundCount: 0, confirmedCount: 0 });
const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const records = ref<StockBillListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<StockBillDetail | null>(null);
const warehouseOptions = ref<Array<{ value: string; label: string }>>([{ value: 'all', label: '全部仓库' }]);
const query = reactive<StockBillQuery>({
  billNo: '',
  sourceNo: '',
  warehouseId: 'all',
  billType: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const queryBusy = computed(() => loading.value || queryPending.value);
const billTypeOptions: Array<{ value: StockBillType | 'all'; label: string }> = [
  { value: 'all', label: '全部类型' },
  { value: 'PURCHASE_IN', label: '采购入库' },
  { value: 'SALES_OUT', label: '销售出库' },
  { value: 'PURCHASE_RETURN', label: '采购退货出库' },
  { value: 'SALES_RETURN', label: '销售退货入库' },
  { value: 'ADJUST_IN', label: '库存调整入库' },
  { value: 'ADJUST_OUT', label: '库存调整出库' },
];
const statusOptions: Array<{ value: StockBillStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CANCELLED', label: '已取消' },
];

const billTypeMap: Record<StockBillType, { label: string; direction: 'in' | 'out'; className: string }> = {
  PURCHASE_IN: { label: '采购入库', direction: 'in', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  SALES_OUT: { label: '销售出库', direction: 'out', className: 'border-blue-200 bg-blue-50 text-blue-700' },
  PURCHASE_RETURN: { label: '采购退货出库', direction: 'out', className: 'border-blue-200 bg-blue-50 text-blue-700' },
  SALES_RETURN: { label: '销售退货入库', direction: 'in', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  ADJUST_IN: { label: '调整入库', direction: 'in', className: 'border-teal-200 bg-teal-50 text-teal-700' },
  ADJUST_OUT: { label: '调整出库', direction: 'out', className: 'border-cyan-200 bg-cyan-50 text-cyan-700' },
};
const statusMap: Record<StockBillStatus, { label: string; className: string }> = {
  DRAFT: { label: '草稿', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  CONFIRMED: { label: '已确认', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  CANCELLED: { label: '已取消', className: 'border-slate-200 bg-slate-50 text-slate-600' },
};
const sourceTypeMap = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  PURCHASE_RETURN_ORDER: '采购退货单',
  SALES_RETURN_ORDER: '销售退货单',
  STOCK_ADJUST: '库存调整单',
} as const;

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

async function fetchRecords() {
  const currentSequence = ++requestSequence.value;
  loading.value = true;
  try {
    const page = await listStockBills(query);
    if (currentSequence !== requestSequence.value) return;
    records.value = page.records;
    total.value = page.total;
    Object.assign(summary, page.summary);
  } catch (error) {
    if (currentSequence === requestSequence.value) toast.warning(getApiErrorMessage(error) || '出入库记录查询失败');
  } finally {
    if (currentSequence === requestSequence.value) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchRecords();
}, 250);
const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchRecords();
}, 180);
const refreshList = useListRefresh(queryBusy, queryPending, fetchRecords);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  Object.assign(query, { billNo: '', sourceNo: '', warehouseId: 'all', billType: 'all', status: 'all', pageNum: 1 });
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

async function openDetail(row: StockBillListItem) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getStockBillDetail(row.stockBillId);
  } catch (error) {
    detailVisible.value = false;
    toast.warning(getApiErrorMessage(error) || '出入库详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

function formatQty(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value);
}

function formatChangeQty(value: number) {
  if (value === 0) return '0';
  return `${value > 0 ? '+' : ''}${formatQty(value)}`;
}

onMounted(() => {
  loadWarehouseOptions();
  fetchRecords();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">出入库记录</h1>
        <p class="page-description">追踪采购、销售、退货和库存调整形成的库存变动凭证</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">流水记录</span><strong class="mt-1 text-2xl">{{ summary.stockBillCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">入库记录</span><strong class="mt-1 text-2xl text-emerald-700">{{ summary.inboundCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">出库记录</span><strong class="mt-1 text-2xl text-blue-700">{{ summary.outboundCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">已确认</span><strong class="mt-1 text-2xl">{{ summary.confirmedCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--stock-bills">
        <div class="space-y-1"><Label class="text-xs">流水号</Label><Input v-model="query.billNo" placeholder="如 SB202606140001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">来源单号</Label><Input v-model="query.sourceNo" placeholder="如 PO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">仓库</Label><AnchoredSelect v-model="query.warehouseId" :options="warehouseOptions" placeholder="全部仓库" /></div>
        <div class="space-y-1"><Label class="text-xs">出入库类型</Label><AnchoredSelect v-model="query.billType" :options="billTypeOptions" placeholder="全部类型" /></div>
        <div class="space-y-1"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" placeholder="全部状态" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">库存变动凭证</strong><span class="text-xs text-muted-foreground">流水由对应业务流程生成，当前页面仅提供查询和追溯</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载当前出入库记录</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1340px] table-fixed">
          <colgroup><col class="w-[165px]" /><col class="w-[125px]" /><col class="w-[175px]" /><col class="w-[165px]" /><col class="w-[80px]" /><col class="w-[90px]" /><col class="w-[180px]" /><col class="w-[180px]" /><col class="w-[90px]" /></colgroup>
          <TableHeader><TableRow><TableHead>流水号</TableHead><TableHead class="text-center">出入库类型</TableHead><TableHead>来源单据</TableHead><TableHead>仓库</TableHead><TableHead class="text-center">明细数</TableHead><TableHead class="text-center">状态</TableHead><TableHead>确认信息</TableHead><TableHead>创建信息</TableHead><TableHead class="text-center">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && records.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="records.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无符合条件的出入库记录</TableCell></TableRow>
            <TableRow v-for="row in records" v-else :key="row.stockBillId" :data-stock-bill-id="row.stockBillId">
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.billNo }}</code></TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="billTypeMap[row.billType].className">{{ billTypeMap[row.billType].label }}</Badge></TableCell>
              <TableCell><div class="flex flex-col gap-1"><span class="text-xs text-muted-foreground">{{ sourceTypeMap[row.sourceType] }}</span><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.sourceNo || '无来源单号' }}</code></div></TableCell>
              <TableCell class="font-medium">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center tabular-nums">{{ row.itemCount }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="statusMap[row.status].className">{{ statusMap[row.status].label }}</Badge></TableCell>
              <TableCell><div v-if="row.status === 'CONFIRMED'" class="flex flex-col gap-1"><span>{{ row.confirmedByName }}</span><span class="text-xs text-muted-foreground">{{ row.confirmedAt }}</span></div><span v-else class="text-sm text-muted-foreground">未确认</span></TableCell>
              <TableCell><div class="flex flex-col gap-1"><span>{{ row.createdByName || '系统' }}</span><span class="text-xs text-muted-foreground">{{ row.createdAt }}</span></div></TableCell>
              <TableCell class="text-center"><Button size="sm" variant="ghost" class="text-primary" @click="openDetail(row)"><Eye class="size-4" />详情</Button></TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="detailVisible">
      <DialogContent class="flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden sm:max-w-[1120px]">
        <DialogHeader>
          <DialogTitle>出入库凭证详情</DialogTitle>
          <DialogDescription>查看业务来源、确认信息以及每个产品的库存变动记录。</DialogDescription>
        </DialogHeader>
        <div v-if="detailLoading" class="flex min-h-64 flex-1 items-center justify-center gap-2 text-muted-foreground"><span class="page-loading-spinner" />详情加载中...</div>
        <ScrollArea v-else-if="detail" class="dialog-scroll-area min-h-0 flex-1 pr-3">
          <div class="space-y-5 py-1">
            <div class="grid grid-cols-4 gap-3 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="detail-field"><span>流水号</span><code>{{ detail.billNo }}</code></div>
              <div class="detail-field"><span>类型</span><Badge variant="outline" :class="billTypeMap[detail.billType].className">{{ billTypeMap[detail.billType].label }}</Badge></div>
              <div class="detail-field"><span>状态</span><Badge variant="outline" :class="statusMap[detail.status].className">{{ statusMap[detail.status].label }}</Badge></div>
              <div class="detail-field"><span>仓库</span><strong>{{ detail.warehouseName }}</strong></div>
              <div class="detail-field"><span>来源类型</span><strong>{{ sourceTypeMap[detail.sourceType] }}</strong></div>
              <div class="detail-field"><span>来源单号</span><code>{{ detail.sourceNo || '-' }}</code></div>
              <div class="detail-field"><span>创建人 / 时间</span><strong>{{ detail.createdByName || '系统' }}</strong><small>{{ detail.createdAt }}</small></div>
              <div class="detail-field"><span>确认人 / 时间</span><strong>{{ detail.confirmedByName || '未确认' }}</strong><small>{{ detail.confirmedAt || '-' }}</small></div>
            </div>

            <div>
              <div class="mb-2 flex items-center justify-between"><h3 class="text-sm font-semibold">产品明细</h3><span class="text-xs text-muted-foreground">共 {{ detail.items.length }} 条</span></div>
              <div class="overflow-hidden rounded-lg border">
                <ScrollArea class="w-full">
                  <Table class="min-w-[1040px] table-fixed">
                    <colgroup><col class="w-[200px]" /><col class="w-[70px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[140px]" /></colgroup>
                    <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-right">本次数量</TableHead><TableHead class="text-right">合格数量</TableHead><TableHead class="text-right">不合格数量</TableHead><TableHead class="text-right">变动前</TableHead><TableHead class="text-right">变动数量</TableHead><TableHead class="text-right">变动后</TableHead><TableHead>备注</TableHead></TableRow></TableHeader>
                    <TableBody>
                      <TableRow v-for="item in detail.items" :key="item.stockBillItemId" :data-stock-bill-item-id="item.stockBillItemId">
                        <TableCell><div class="flex flex-col gap-1"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><span class="font-medium">{{ item.productName }}</span></div></TableCell>
                        <TableCell class="text-center">{{ item.unitName }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.quantity) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.qualifiedQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums" :class="item.defectiveQty > 0 ? 'font-medium text-rose-700' : 'text-muted-foreground'">{{ formatQty(item.defectiveQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.beforeQty) }}</TableCell>
                        <TableCell class="text-right font-semibold tabular-nums" :class="item.changeQty > 0 ? 'text-emerald-700' : item.changeQty < 0 ? 'text-blue-700' : 'text-muted-foreground'">{{ formatChangeQty(item.changeQty) }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.afterQty) }}</TableCell>
                        <TableCell class="text-xs text-muted-foreground">{{ item.remark || '-' }}</TableCell>
                      </TableRow>
                    </TableBody>
                  </Table>
                </ScrollArea>
              </div>
            </div>

            <div class="rounded-lg border bg-muted/25 p-3"><span class="text-xs text-muted-foreground">凭证备注</span><p class="mt-1 text-sm">{{ detail.remark || '无' }}</p></div>
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  </section>
</template>

<style scoped>
.filter-grid--stock-bills {
  grid-template-columns: repeat(5, minmax(0, 1fr)) auto;
}

.detail-field {
  display: flex;
  min-height: 78px;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  background: color-mix(in srgb, var(--muted) 34%, transparent);
  padding: 12px;
}

.detail-field > span {
  font-size: 12px;
  color: var(--muted-foreground);
}

.detail-field > small {
  color: var(--muted-foreground);
}

@media (max-width: 1279px) {
  .filter-grid--stock-bills {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-grid--stock-bills .filter-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}

@media (max-width: 640px) {
  .filter-grid--stock-bills {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
