<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import type { RemoteSearchOption } from '@/components/common/RemoteSearchSelect.vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { listScoreChangeLogs, searchSupplierOptions, searchSupplierProductLogOptions } from '../api';
import type { ScoreChangeLog, ScoreChangeLogQuery, ScoreChangeSource, ScoreMetricType, ScoreTriggerType } from '../types';

const props = defineProps<{
  open: boolean;
  supplierId?: string | null;
  supplierName?: string;
  supplierProductId?: string | null;
  productName?: string;
}>();
const emit = defineEmits<{ 'update:open': [value: boolean] }>();
const dialogOpen = computed({ get: () => props.open, set: value => emit('update:open', value) });

const query = reactive({
  supplierId: null as string | null,
  supplierProductId: null as string | null,
  metricType: 'all',
  triggerType: 'all',
  batchNo: '',
  startTime: '',
  endTime: '',
  pageNum: 1,
  pageSize: 10,
});
const rows = ref<ScoreChangeLog[]>([]);
const total = ref(0);
const loading = ref(false);
const pending = ref(false);
const errorText = ref('');
const selectedSupplierLabel = ref('');
const selectedProductLabel = ref('');
const busy = computed(() => loading.value || pending.value);
let requestSequence = 0;

const metricOptions = [
  { value: 'all', label: '全部指标' },
  { value: 'SERVICE', label: '服务分' },
  { value: 'DELIVERY', label: '交付分' },
  { value: 'QUALITY', label: '质量分' },
  { value: 'PRICE', label: '价格分' },
];
const triggerOptions = [
  { value: 'all', label: '全部触发方式' },
  { value: 'PRICE_TRIGGER', label: '报价或参考价调整' },
  { value: 'SERVICE_TRIGGER', label: '服务分调整' },
  { value: 'INBOUND_TRIGGER', label: '完全入库' },
  { value: 'QUOTE_EXPIRED_TRIGGER', label: '报价过期' },
  { value: 'DAILY_TRIGGER', label: '每日核对' },
  { value: 'MERGED', label: '合并重算' },
];
const metricLabels: Record<ScoreMetricType, string> = {
  SERVICE: '服务分', DELIVERY: '交付分', QUALITY: '质量分', PRICE: '价格分',
};
const triggerLabels: Record<ScoreTriggerType, string> = {
  PRICE_TRIGGER: '报价或参考价调整', SERVICE_TRIGGER: '服务分调整', INBOUND_TRIGGER: '完全入库',
  QUOTE_EXPIRED_TRIGGER: '报价过期', DAILY_TRIGGER: '每日核对', MERGED: '合并重算',
};

async function fetchSupplierOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const options = await searchSupplierOptions(keyword);
  return options.map(item => ({
    value: item.supplierId,
    label: `${item.supplierName} · ${item.supplierCode}`,
  }));
}

async function fetchProductOptions(keyword: string): Promise<RemoteSearchOption[]> {
  const options = await searchSupplierProductLogOptions(keyword, query.supplierId);
  return options.map(item => ({
    value: item.supplierProductId,
    label: `${item.supplierName} · ${item.productCode} · ${item.productName}`,
  }));
}

function selectSupplier(option: RemoteSearchOption) {
  if (busy.value) return;
  const nextId = option.value === 'all' ? null : String(option.value);
  query.supplierId = nextId;
  selectedSupplierLabel.value = nextId ? option.label : '';
  query.pageNum = 1;
  scheduleLoad();
}

function selectProduct(option: RemoteSearchOption) {
  if (busy.value) return;
  const nextId = option.value === 'all' ? null : String(option.value);
  query.supplierProductId = nextId;
  selectedProductLabel.value = nextId ? option.label : '';
  query.pageNum = 1;
  scheduleLoad();
}

function toQuery(): ScoreChangeLogQuery {
  const toTime = (value: string, end = false) => value
    ? (value.length === 16 ? `${value}:${end ? '59' : '00'}` : value)
    : null;
  return {
    supplierId: query.supplierId,
    supplierProductId: query.supplierProductId,
    metricType: query.metricType === 'all' ? null : query.metricType as ScoreMetricType,
    triggerType: query.triggerType === 'all' ? null : query.triggerType as ScoreTriggerType,
    batchNo: query.batchNo.trim() || null,
    startTime: toTime(query.startTime),
    endTime: toTime(query.endTime, true),
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  };
}

async function load(sequence: number) {
  if (sequence !== requestSequence || !props.open) return;
  pending.value = false;
  loading.value = true;
  errorText.value = '';
  try {
    const page = await listScoreChangeLogs(toQuery());
    if (sequence !== requestSequence || !props.open) return;
    rows.value = page.records;
    total.value = page.total;
  } catch (error) {
    if (sequence !== requestSequence || !props.open) return;
    rows.value = [];
    total.value = 0;
    errorText.value = getApiErrorMessage(error) || '评分记录加载失败';
    toast.warning(errorText.value);
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}
const debouncedLoad = useDebounceFn(load, 220);

function scheduleLoad(immediate = false) {
  const sequence = ++requestSequence;
  pending.value = !immediate;
  loading.value = immediate;
  if (immediate) void load(sequence);
  else void debouncedLoad(sequence);
}

function validateQuery() {
  if (query.startTime && query.endTime && query.startTime > query.endTime) {
    toast.warning('开始时间不能晚于结束时间');
    return false;
  }
  return true;
}

function search() {
  if (busy.value || !validateQuery()) return;
  query.pageNum = 1;
  scheduleLoad();
}

function reset() {
  if (busy.value) return;
  query.supplierId = null;
  query.supplierProductId = null;
  selectedSupplierLabel.value = '';
  selectedProductLabel.value = '';
  query.metricType = 'all';
  query.triggerType = 'all';
  query.batchNo = '';
  query.startTime = '';
  query.endTime = '';
  query.pageNum = 1;
  scheduleLoad();
}

function clearScope(field: 'supplierId' | 'supplierProductId') {
  if (busy.value) return;
  query[field] = null;
  if (field === 'supplierId') selectedSupplierLabel.value = '';
  else selectedProductLabel.value = '';
  query.pageNum = 1;
  scheduleLoad();
}

function pageChange(pageNum: number) {
  if (busy.value || pageNum === query.pageNum) return;
  query.pageNum = pageNum;
  scheduleLoad();
}

function pageSizeChange(pageSize: number) {
  if (busy.value || pageSize === query.pageSize) return;
  query.pageSize = pageSize;
  query.pageNum = 1;
  scheduleLoad();
}

function formatScore(value: number | null) {
  return value == null ? '—' : value.toFixed(2);
}

function formatChange(before: number | null, after: number | null) {
  return `${formatScore(before)} → ${formatScore(after)}`;
}

const sourceLabels: Record<ScoreChangeSource['businessType'], string> = {
  PURCHASE_ORDER: '采购单', SUPPLIER_PRODUCT: '供货关系', PRODUCT: '产品', SUPPLIER: '供应商',
};

function formatSources(sources: ScoreChangeSource[]) {
  return sources.map(source => `${sourceLabels[source.businessType]}：${source.businessNo || `ID ${source.businessId}`}`).join('\n');
}

function isDerivedCorrection(row: ScoreChangeLog) {
  // 没有基础指标变化时，不把推荐分或综合分校正展示成基础指标调整。
  return row.metricScoreBefore === row.metricScoreAfter
    && (row.productRecommendScoreBefore !== row.productRecommendScoreAfter
      || row.supplierOverallScoreBefore !== row.supplierOverallScoreAfter);
}

watch(() => props.open, open => {
  if (!open) {
    requestSequence++;
    pending.value = false;
    loading.value = false;
    return;
  }
  query.supplierId = props.supplierId || null;
  query.supplierProductId = props.supplierProductId || null;
  selectedSupplierLabel.value = props.supplierName || '';
  selectedProductLabel.value = props.productName || '';
  query.metricType = 'all';
  query.triggerType = 'all';
  query.batchNo = '';
  query.startTime = '';
  query.endTime = '';
  query.pageNum = 1;
  rows.value = [];
  total.value = 0;
  scheduleLoad(true);
});
</script>

<template>
  <Dialog v-model:open="dialogOpen">
    <DialogContent data-score-change-log-dialog class="flex !h-[min(820px,calc(100dvh-2rem))] !max-h-[calc(100dvh-2rem)] !w-[min(1152px,calc(100vw-2rem))] !max-w-[calc(100vw-2rem)] flex-col overflow-hidden">
      <DialogHeader>
        <DialogTitle>评分变更记录</DialogTitle>
        <DialogDescription>查询实际发生的指标分变化。未生成的评分与快照显示为“—”。</DialogDescription>
      </DialogHeader>
      <DialogScrollArea content-class="space-y-4 pb-3">
        <div v-if="query.supplierId || query.supplierProductId" class="flex flex-wrap gap-2 text-xs">
          <div v-if="query.supplierId" class="inline-flex items-center gap-2 rounded-md border bg-muted/50 px-3 py-1.5">
            <span>供应商：{{ selectedSupplierLabel || query.supplierId }}</span>
            <Button type="button" variant="ghost" size="sm" class="h-6 px-1.5" :disabled="busy" aria-label="清除供应商筛选" @click="clearScope('supplierId')">清除</Button>
          </div>
          <div v-if="query.supplierProductId" class="inline-flex items-center gap-2 rounded-md border bg-muted/50 px-3 py-1.5">
            <span>供货产品：{{ selectedProductLabel || query.supplierProductId }}</span>
            <Button type="button" variant="ghost" size="sm" class="h-6 px-1.5" :disabled="busy" aria-label="清除供货产品筛选" @click="clearScope('supplierProductId')">清除</Button>
          </div>
        </div>
        <ListFilterPanel aria-label="评分变更记录筛选" actions-position="bottom" grid-class="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <div role="group" aria-labelledby="score-log-supplier-label" class="space-y-1.5"><Label id="score-log-supplier-label">供应商</Label><RemoteSearchSelect :model-value="query.supplierId ?? 'all'" :selected-label="selectedSupplierLabel" placeholder="全部供应商" search-placeholder="输入供应商编码或名称" clearable clear-label="全部供应商" :disabled="busy" :fetch-options="fetchSupplierOptions" @select="selectSupplier" /></div>
          <div role="group" aria-labelledby="score-log-product-label" class="space-y-1.5"><Label id="score-log-product-label">供货产品</Label><RemoteSearchSelect :model-value="query.supplierProductId ?? 'all'" :selected-label="selectedProductLabel" placeholder="全部供货产品" search-placeholder="输入产品编码或名称" clearable clear-label="全部供货产品" :disabled="busy" :fetch-options="fetchProductOptions" @select="selectProduct" /></div>
          <div role="group" aria-labelledby="score-log-metric-label" class="space-y-1.5"><Label id="score-log-metric-label">指标类型</Label><AnchoredSelect :model-value="query.metricType" :options="metricOptions" @update:model-value="value => query.metricType = String(value)" /></div>
          <div role="group" aria-labelledby="score-log-trigger-label" class="space-y-1.5"><Label id="score-log-trigger-label">触发方式</Label><AnchoredSelect :model-value="query.triggerType" :options="triggerOptions" @update:model-value="value => query.triggerType = String(value)" /></div>
          <div class="space-y-1.5"><Label for="score-log-batch">批次号</Label><Input id="score-log-batch" v-model="query.batchNo" maxlength="64" placeholder="精确查询批次号" @keyup.enter="search" /></div>
          <div class="space-y-1.5"><Label for="score-log-start">开始时间</Label><Input id="score-log-start" v-model="query.startTime" type="datetime-local" class="min-w-0" /></div>
          <div class="space-y-1.5"><Label for="score-log-end">结束时间</Label><Input id="score-log-end" v-model="query.endTime" type="datetime-local" class="min-w-0" /></div>
          <template #actions><ListFilterActions :busy="busy" @query="search" @reset="reset" /></template>
        </ListFilterPanel>
        <div class="relative overflow-hidden rounded-lg border bg-card" :aria-busy="busy">
          <Table class="min-w-[1120px] table-fixed" scroll-label="评分变更记录表格">
            <colgroup><col style="width: 150px"><col style="width: 174px"><col style="width: 126px"><col style="width: 140px"><col style="width: 140px"><col style="width: 185px"><col style="width: 205px"></colgroup>
            <TableHeader><TableRow><TableHead>变更时间</TableHead><TableHead>关联范围</TableHead><TableHead>指标分</TableHead><TableHead>产品推荐分</TableHead><TableHead>供应商综合分</TableHead><TableHead>触发与批次</TableHead><TableHead>原因与操作人</TableHead></TableRow></TableHeader>
            <TableBody>
              <TableRow v-if="errorText"><TableCell colspan="7" class="py-10 text-center text-destructive">{{ errorText }}</TableCell></TableRow>
              <TableRow v-else-if="!busy && rows.length === 0"><TableCell colspan="7" class="py-10 text-center text-muted-foreground">暂无符合条件的评分变更记录</TableCell></TableRow>
              <TableRow v-for="row in rows" :key="row.scoreChangeLogId">
                <TableCell class="text-center tabular-nums">{{ row.createTime.replace('T', ' ') }}</TableCell>
                <TableCell class="text-left"><div class="truncate" :title="row.supplierId">{{ query.supplierId === row.supplierId && selectedSupplierLabel ? `供应商：${selectedSupplierLabel}` : `供应商 ID：${row.supplierId}` }}</div><div v-if="row.supplierProductId" class="truncate text-xs text-muted-foreground" :title="row.supplierProductId">{{ query.supplierProductId === row.supplierProductId && selectedProductLabel ? `供货产品：${selectedProductLabel}` : `供货关系 ID：${row.supplierProductId}` }}</div></TableCell>
                <TableCell class="text-center"><div>{{ isDerivedCorrection(row) ? '衍生分校正' : metricLabels[row.metricType] }}</div><div class="whitespace-nowrap text-xs tabular-nums text-muted-foreground">{{ isDerivedCorrection(row) ? '基础指标未变化' : formatChange(row.metricScoreBefore, row.metricScoreAfter) }}</div></TableCell>
                <TableCell class="text-center whitespace-nowrap tabular-nums">{{ formatChange(row.productRecommendScoreBefore, row.productRecommendScoreAfter) }}</TableCell>
                <TableCell class="text-center whitespace-nowrap tabular-nums">{{ formatChange(row.supplierOverallScoreBefore, row.supplierOverallScoreAfter) }}</TableCell>
                <TableCell class="text-left"><div class="truncate" :title="triggerLabels[row.triggerType]">{{ triggerLabels[row.triggerType] }}</div><div class="truncate text-xs tabular-nums text-muted-foreground" :title="row.batchNo">批次：{{ row.batchNo || '—' }}</div><div class="truncate text-xs text-muted-foreground" :title="row.ruleVersion">规则：{{ row.ruleVersion || '—' }}</div></TableCell>
                <TableCell class="text-left">
                  <div class="truncate" :title="row.reason">{{ row.reason || '—' }}</div>
                  <details v-if="row.relatedSources.length" class="text-xs text-muted-foreground" data-score-log-sources>
                    <summary class="cursor-pointer truncate" :title="formatSources(row.relatedSources)">业务来源（{{ row.relatedSources.length }}）</summary>
                    <div class="max-h-32 space-y-1 overflow-y-auto py-1">
                      <div v-for="source in row.relatedSources" :key="`${source.businessType}:${source.businessId}`" class="break-all" :title="`${sourceLabels[source.businessType]} ID：${source.businessId}`">{{ sourceLabels[source.businessType] }}：{{ source.businessNo || `ID ${source.businessId}` }}</div>
                    </div>
                  </details>
                  <div v-else class="text-xs text-muted-foreground">无业务来源</div>
                  <div class="truncate text-xs text-muted-foreground" :title="row.operatorId ? `${row.operatorName} · 用户 ID：${row.operatorId}` : row.operatorName">{{ row.operatorType === 'SYSTEM' ? '系统：' : '操作人：' }}{{ row.operatorName }}</div>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
          <ListLoadingOverlay :visible="busy" label="评分记录查询中" :initial-delay="0" />
        </div>
      </DialogScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="busy" @update:page-num="pageChange" @update:page-size="pageSizeChange" />
      <DialogFooter><Button type="button" variant="outline" @click="dialogOpen = false">关闭</Button></DialogFooter>
    </DialogContent>
  </Dialog>
</template>
