<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { CollapsibleContent, CollapsibleRoot } from 'reka-ui';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import OrderNumberLink from '@/components/common/OrderNumberLink.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import WarehouseDetailTableFrame from '@/components/common/WarehouseDetailTableFrame.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import { formatQtyByPrecision } from '@/shared/utils/qty';
import { listWarehouses } from '../../warehouses/api';
import { getStockLedgerDetail, listStockLedgers } from '../api';
import { getStockLedgerSourceType } from '../types';
import type {
  StockLedgerBillType,
  StockLedgerDetail,
  StockLedgerEntryMode,
  StockLedgerItem,
  StockLedgerListItem,
  StockLedgerQuery,
  StockLedgerSourceType,
} from '../types';

const inboundTypes = new Set<StockLedgerBillType>(['PURCHASE_IN', 'SALES_RETURN', 'ADJUST_IN']);
const qualityBillTypes = new Set<StockLedgerBillType>(['PURCHASE_IN', 'SALES_RETURN']);

const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const records = ref<StockLedgerListItem[]>([]);
const total = ref(0);
const expandedDetails = reactive<Record<string, StockLedgerDetail | undefined>>({});
const expandedDetailIds = ref<Set<string>>(new Set());
const detailLoadingIds = ref<Set<string>>(new Set());
const detailLoadErrors = reactive<Record<string, string | undefined>>({});
const warehouseOptions = ref<Array<{ value: string; label: string }>>([{ value: 'all', label: '全部仓库' }]);
const query = reactive<StockLedgerQuery>({
  billNo: '',
  sourceNo: '',
  sourceType: 'all',
  warehouseId: 'all',
  billType: 'all',
  entryMode: 'all',
  pageNum: 1,
  pageSize: 10,
});

const queryBusy = computed(() => loading.value || queryPending.value);
const selectedWarehouseLabel = computed(() => query.warehouseId === 'all'
  ? '全部仓库'
  : warehouseOptions.value.find(item => item.value === query.warehouseId)?.label || '');
const summaryItems = computed(() => [
  { key: 'inbound', label: '本页入库流水', value: records.value.filter(item => inboundTypes.has(item.billType)).length, tone: 'positive' as const },
  { key: 'outbound', label: '本页出库流水', value: records.value.filter(item => !inboundTypes.has(item.billType)).length, tone: 'warning' as const },
  { key: 'adjustment', label: '本页库存调整', value: records.value.filter(item => item.billType.startsWith('ADJUST_')).length },
  { key: 'items', label: '本页变动明细', value: records.value.reduce((sum, item) => sum + item.itemCount, 0) },
]);

const billTypeOptions: Array<{ value: StockLedgerBillType | 'all'; label: string }> = [
  { value: 'all', label: '全部类型' },
  { value: 'PURCHASE_IN', label: '采购入库' },
  { value: 'SALES_OUT', label: '销售出库' },
  { value: 'PURCHASE_RETURN', label: '采购退货出库' },
  { value: 'SALES_RETURN', label: '销售退货入库' },
  { value: 'ADJUST_IN', label: '调整入库' },
  { value: 'ADJUST_OUT', label: '调整出库' },
];
const entryModeOptions: Array<{ value: StockLedgerEntryMode | 'all'; label: string }> = [
  { value: 'all', label: '全部录入方式' },
  { value: 'SOURCE_GENERATED', label: '系统生成' },
  { value: 'MANUAL_SUPPLEMENT', label: '人工补录' },
  { value: 'MANUAL_ADJUSTMENT', label: '人工调整' },
];
const billTypeMap: Record<StockLedgerBillType, { label: string; className: string }> = {
  PURCHASE_IN: { label: '采购入库', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  SALES_OUT: { label: '销售出库', className: 'border-sky-200 bg-sky-50 text-sky-700' },
  PURCHASE_RETURN: { label: '采购退货出库', className: 'border-violet-200 bg-violet-50 text-violet-700' },
  SALES_RETURN: { label: '销售退货入库', className: 'border-teal-200 bg-teal-50 text-teal-700' },
  ADJUST_IN: { label: '调整入库', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  ADJUST_OUT: { label: '调整出库', className: 'border-orange-200 bg-orange-50 text-orange-700' },
};
const sourceTypeMap = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  PURCHASE_RETURN_ORDER: '采购退货单',
  SALES_RETURN_ORDER: '销售退货单',
  STOCK_ADJUST: '库存调整单',
} as const;
const sourceTypeOptions: Array<{ value: StockLedgerSourceType | 'all'; label: string }> = [
  { value: 'all', label: '全部来源类型' },
  { value: 'PURCHASE_ORDER', label: sourceTypeMap.PURCHASE_ORDER },
  { value: 'SALES_ORDER', label: sourceTypeMap.SALES_ORDER },
  { value: 'PURCHASE_RETURN_ORDER', label: sourceTypeMap.PURCHASE_RETURN_ORDER },
  { value: 'SALES_RETURN_ORDER', label: sourceTypeMap.SALES_RETURN_ORDER },
  { value: 'STOCK_ADJUST', label: sourceTypeMap.STOCK_ADJUST },
];

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}

function mergeWarehouseOptions(options: Array<{ value: string; label: string }>) {
  const cache = new Map(warehouseOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  warehouseOptions.value = [{ value: 'all', label: '全部仓库' }, ...Array.from(cache.values()).filter(item => item.value !== 'all')];
}

async function fetchWarehouseSearchOptions(keyword: string) {
  const page = await listWarehouses({ pageNum: 1, pageSize: 10, ...warehouseKeywordQuery(keyword) }, { skipPageLoading: true });
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

async function fetchLedgers() {
  const currentSequence = ++requestSequence.value;
  loading.value = true;
  try {
    const page = await listStockLedgers(query);
    if (currentSequence !== requestSequence.value) return;
    records.value = page.records;
    total.value = page.total;
    expandedDetailIds.value = new Set();
    detailLoadingIds.value = new Set();
    Object.keys(expandedDetails).forEach(key => delete expandedDetails[key]);
    Object.keys(detailLoadErrors).forEach(key => delete detailLoadErrors[key]);
  } catch (error) {
    if (currentSequence === requestSequence.value) toast.warning(getApiErrorMessage(error) || '库存流水查询失败');
  } finally {
    if (currentSequence === requestSequence.value) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

function isRowDetailCollapsed(row: StockLedgerListItem) {
  return !expandedDetailIds.value.has(row.stockLedgerId);
}

function setRowDetailLoading(stockLedgerId: string, loading: boolean) {
  const next = new Set(detailLoadingIds.value);
  if (loading) next.add(stockLedgerId);
  else next.delete(stockLedgerId);
  detailLoadingIds.value = next;
}

function isRowDetailLoading(row: StockLedgerListItem) {
  return detailLoadingIds.value.has(row.stockLedgerId);
}

function expandedItems(row: StockLedgerListItem) {
  return expandedDetails[row.stockLedgerId]?.items || [];
}

function formatQty(value: number, item: StockLedgerItem) {
  return formatQtyByPrecision(value, item.quantityPrecision);
}

function qualityQty(item: StockLedgerItem, billType: StockLedgerBillType, field: 'qualifiedQty' | 'defectiveQty') {
  return qualityBillTypes.has(billType) ? formatQty(item[field], item) : '-';
}

async function toggleRowDetail(row: StockLedgerListItem) {
  const next = new Set(expandedDetailIds.value);
  if (next.has(row.stockLedgerId)) {
    next.delete(row.stockLedgerId);
    expandedDetailIds.value = next;
    return;
  }
  next.add(row.stockLedgerId);
  expandedDetailIds.value = next;
  if (expandedDetails[row.stockLedgerId] || isRowDetailLoading(row)) return;
  await loadRowDetail(row);
}

async function loadRowDetail(row: StockLedgerListItem) {
  detailLoadErrors[row.stockLedgerId] = undefined;
  setRowDetailLoading(row.stockLedgerId, true);
  try {
    expandedDetails[row.stockLedgerId] = await getStockLedgerDetail(row.stockLedgerId);
  } catch (error) {
    detailLoadErrors[row.stockLedgerId] = getApiErrorMessage(error) || '变动明细加载失败，请重试';
  } finally {
    setRowDetailLoading(row.stockLedgerId, false);
  }
}

async function retryRowDetail(row: StockLedgerListItem) {
  if (isRowDetailLoading(row)) return;
  delete expandedDetails[row.stockLedgerId];
  await loadRowDetail(row);
}

const { handleSearch, handleReset, handlePageChange, handlePageSizeChange, refreshList } = usePagedQuery({
  query,
  busy: queryBusy,
  pending: queryPending,
  load: fetchLedgers,
  resetFilters: () => {
    Object.assign(query, { billNo: '', sourceNo: '', sourceType: 'all', warehouseId: 'all', billType: 'all', entryMode: 'all' });
  },
});

onMounted(() => {
  loadWarehouseOptions();
  fetchLedgers();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">库存流水</h1>
        <p class="page-description">查看已确认并实际改变库存的凭证；流水不可编辑，异常请通过反向业务单据纠正</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" aria-label="库存流水数据汇总" />

    <ListFilterPanel layout="content" aria-label="库存流水筛选">
      <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">流水号</Label><Input v-model="query.billNo" placeholder="如 SL202607180001" @keyup.enter="handleSearch" /></div>
      <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">来源业务单号</Label><Input v-model="query.sourceNo" placeholder="如 PO202607180001" @keyup.enter="handleSearch" /></div>
      <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">来源业务类型</Label><AnchoredSelect v-model="query.sourceType" :options="sourceTypeOptions" placeholder="全部来源类型" /></div>
      <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">仓库</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
      <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">出入库类型</Label><AnchoredSelect v-model="query.billType" :options="billTypeOptions" placeholder="全部类型" /></div>
      <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">录入方式</Label><AnchoredSelect v-model="query.entryMode" :options="entryModeOptions" placeholder="全部录入方式" /></div>
      <template #actions><ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" /></template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">已确认库存事实</strong><span class="text-xs text-muted-foreground">仅用于追溯，不能新建、编辑、提交、确认或取消</span></div>
        <div class="table-toolbar__actions"><Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载库存流水</TooltipContent></Tooltip></div>
      </div>

      <ScrollArea class="w-full">
        <div class="stock-ledger-table-scroll">
        <Table class="min-w-[1455px] table-fixed">
          <colgroup><col class="w-[270px]" /><col class="w-[130px]" /><col class="w-[120px]" /><col class="w-[130px]" /><col class="w-[175px]" /><col class="w-[170px]" /><col class="w-[120px]" /><col class="w-[170px]" /><col class="w-[170px]" /></colgroup>
          <TableHeader><TableRow><TableHead class="stock-ledger-key-column sticky left-0 z-20 border-r border-border/60 bg-muted" data-table-sticky-edge="start">流水号</TableHead><TableHead class="text-center">出入库类型</TableHead><TableHead>录入方式</TableHead><TableHead>来源业务类型</TableHead><TableHead>来源业务单号</TableHead><TableHead>仓库</TableHead><TableHead>确认人</TableHead><TableHead>确认时间</TableHead><TableHead>创建时间</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="records.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无符合条件的库存流水</TableCell></TableRow>
            <template v-else v-for="row in records" :key="row.stockLedgerId">
              <TableRow class="group" :data-stock-ledger-id="row.stockLedgerId">
                <TableCell class="stock-ledger-key-column sticky left-0 z-20 border-r border-border/60 bg-background group-hover:bg-muted/50" data-table-sticky-edge="start"><div class="flex items-center gap-2"><Button size="sm" variant="ghost" class="h-7 shrink-0 px-2 text-xs text-primary hover:text-primary" :aria-expanded="!isRowDetailCollapsed(row)" :aria-controls="`stock-ledger-detail-${row.stockLedgerId}`" :aria-label="`${isRowDetailCollapsed(row) ? '展开' : '收起'} ${row.billNo} 的 ${row.itemCount} 条变动明细`" @click="toggleRowDetail(row)">{{ isRowDetailCollapsed(row) ? '展开明细' : '收起明细' }}</Button><OrderNumberLink :value="row.billNo" label="库存流水号" /></div></TableCell>
                <TableCell class="text-center"><Badge variant="outline" :class="billTypeMap[row.billType].className">{{ billTypeMap[row.billType].label }}</Badge></TableCell>
                <TableCell class="text-muted-foreground" data-stock-ledger-entry-mode>{{ entryModeOptions.find(item => item.value === row.entryMode)?.label || '-' }}</TableCell>
                <TableCell class="text-muted-foreground" data-stock-ledger-source-type>{{ sourceTypeMap[getStockLedgerSourceType(row.billType)] }}</TableCell>
                <TableCell><span class="block truncate" :title="row.sourceNo">{{ row.sourceNo || '-' }}</span></TableCell>
                <TableCell><span class="block truncate" :title="row.warehouseName">{{ row.warehouseName }}</span></TableCell>
                <TableCell>{{ row.confirmedByName || '-' }}</TableCell>
                <TableCell class="text-xs text-muted-foreground">{{ row.confirmedAt }}</TableCell>
                <TableCell class="text-xs text-muted-foreground">{{ row.createTime }}</TableCell>
              </TableRow>
              <TableRow class="stock-ledger-detail-host-row bg-background" :data-stock-ledger-detail-host-id="row.stockLedgerId">
                <TableCell colspan="9" class="h-0 px-4 py-0">
                  <CollapsibleRoot :open="!isRowDetailCollapsed(row)" :unmount-on-hide="false">
                    <CollapsibleContent :id="`stock-ledger-detail-${row.stockLedgerId}`" class="stock-ledger-detail-drawer" :data-stock-ledger-detail-id="row.stockLedgerId">
                      <div class="stock-ledger-detail-drawer__inner">
                        <div v-if="isRowDetailLoading(row)" class="stock-ledger-detail-message text-muted-foreground" :data-stock-ledger-detail-loading-id="row.stockLedgerId"><span class="page-loading-spinner mr-2 !size-3.5" />变动明细加载中...</div>
                        <div v-else-if="detailLoadErrors[row.stockLedgerId]" class="stock-ledger-detail-message flex-col gap-2 text-destructive" :data-stock-ledger-detail-error-id="row.stockLedgerId"><span>{{ detailLoadErrors[row.stockLedgerId] }}</span><Button size="sm" variant="outline" @click="retryRowDetail(row)">重试</Button></div>
                        <div v-else-if="expandedItems(row).length === 0" class="stock-ledger-detail-message text-muted-foreground" :data-stock-ledger-detail-empty-id="row.stockLedgerId">暂无变动明细</div>
                        <WarehouseDetailTableFrame v-else class="stock-ledger-detail-card" max-width="1024px">
                          <Table class="!w-[1020px] min-w-[1020px] table-fixed"><colgroup><col class="w-[104px]" /><col class="w-[180px]" /><col class="w-[56px]" /><col class="w-[104px]" /><col class="w-[104px]" /><col class="w-[104px]" /><col class="w-[116px]" /><col class="w-[104px]" /><col class="w-[148px]" /></colgroup><TableHeader><TableRow><TableHead class="text-center">产品编码</TableHead><TableHead class="text-center">产品名称</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-center">变动前</TableHead><TableHead class="text-center">合格数量</TableHead><TableHead class="text-center">不合格数量</TableHead><TableHead class="text-center">变动数量</TableHead><TableHead class="text-center">变动后</TableHead><TableHead class="text-center">备注</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="item in expandedItems(row)" :key="item.stockLedgerItemId" :data-stock-ledger-expanded-item-id="item.stockLedgerItemId"><TableCell class="text-center"><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code></TableCell><TableCell class="truncate text-center font-medium" :title="item.productName">{{ item.productName }}</TableCell><TableCell class="text-center text-muted-foreground">{{ item.unitName }}</TableCell><TableCell class="text-center tabular-nums">{{ formatQty(item.beforeQty, item) }}</TableCell><TableCell class="text-center tabular-nums" data-stock-ledger-quality="qualified">{{ qualityQty(item, row.billType, 'qualifiedQty') }}</TableCell><TableCell class="text-center tabular-nums" data-stock-ledger-quality="defective">{{ qualityQty(item, row.billType, 'defectiveQty') }}</TableCell><TableCell class="text-center font-medium tabular-nums" :class="item.changeQty > 0 ? 'text-emerald-700' : 'text-rose-700'">{{ item.changeQty > 0 ? '+' : '' }}{{ formatQty(item.changeQty, item) }}</TableCell><TableCell class="text-center font-medium tabular-nums">{{ formatQty(item.afterQty, item) }}</TableCell><TableCell class="text-center"><OverflowTooltip :text="item.remark" fallback="-" class="block text-muted-foreground" /></TableCell></TableRow></TableBody></Table>
                        </WarehouseDetailTableFrame>
                      </div>
                    </CollapsibleContent>
                  </CollapsibleRoot>
                </TableCell>
              </TableRow>
            </template>
          </TableBody>
        </Table>
        </div>
      </ScrollArea>

      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>
  </section>
</template>

<style scoped>
.stock-ledger-detail-host-row :deep([data-slot='table-cell']) {
  height: 0;
  background-color: var(--background);
}

.stock-ledger-table-scroll {
  container-type: inline-size;
}

.stock-ledger-detail-drawer {
  overflow: hidden;
  overflow: clip;
  inline-size: 100%;
  min-inline-size: 0;
  max-inline-size: 100%;
  contain: inline-size paint;
  will-change: height, opacity;
}

.stock-ledger-detail-drawer[data-state="open"] {
  animation: stock-ledger-collapsible-down var(--motion-duration-base) var(--motion-ease-standard);
}

.stock-ledger-detail-drawer[data-state="closed"] {
  animation: stock-ledger-collapsible-up var(--motion-duration-fast) var(--motion-ease-exit);
}

.stock-ledger-detail-drawer__inner {
  min-height: 0;
  padding-block: 12px;
}

.stock-ledger-detail-message {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: center;
}

.stock-ledger-detail-card {
  position: sticky;
  /* 与主表单元格内容对齐，保留左侧留白，避免卡片边框被滚动容器裁切。 */
  left: 16px;
  z-index: 10;
  /* 表格内容宽度为 1020px，额外 4px 仅供左右边框，避免右侧出现空白条。 */
  width: min(1024px, calc(100cqi - 32px));
}

@keyframes stock-ledger-collapsible-down {
  from { height: 0; opacity: 0; }
  to { height: var(--reka-collapsible-content-height); opacity: 1; }
}

@keyframes stock-ledger-collapsible-up {
  from { height: var(--reka-collapsible-content-height); opacity: 1; }
  to { height: 0; opacity: 0; }
}
</style>
