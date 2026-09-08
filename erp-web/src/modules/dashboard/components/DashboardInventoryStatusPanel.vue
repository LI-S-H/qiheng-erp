<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
import { AlertTriangle, ChevronRight, PieChart } from 'lucide-vue-next';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { getApiErrorMessage } from '@/api/http';
import { formatQtyByPrecision } from '@/shared/utils/qty';
import { listWarehouses } from '@/modules/warehouse/warehouses/api';
import { getDashboardInventoryStatus } from '../api';
import type { DashboardInventoryStatus } from '../types';

const TEXT = {
  title: '\u5e93\u5b58\u72b6\u6001',
  description: '\u4ee5\u4ed3\u5e93\u5546\u54c1\u8bb0\u5f55\u4e3a\u5355\u4f4d\u8bc4\u4f30\uff0c\u4e0d\u8de8\u4ed3\u5e93\u6c47\u603b\u5e93\u5b58',
  allWarehouses: '\u5168\u90e8\u4ed3\u5e93',
  searchWarehouse: '\u8f93\u5165\u4ed3\u5e93\u7f16\u7801\u6216\u540d\u79f0',
  riskRecords: '\u98ce\u9669\u4ed3\u50a8\u5546\u54c1',
  riskInventory: '风险库存',
  totalRecords: '共 {count} 个库存记录',
  previewHint: '仅展示风险最高的 {count} 条',
  sortHint: '按安全库存缺口由高到低排序',
  viewAll: '\u67e5\u770b\u5168\u90e8\u98ce\u9669\u5e93\u5b58',
  empty: '\u6682\u65e0\u5e93\u5b58\u8bb0\u5f55',
  denied: '\u60a8\u6682\u65e0\u5e93\u5b58\u67e5\u770b\u6743\u9650',
  product: '\u4ea7\u54c1',
  warehouse: '\u4ed3\u5e93',
  available: '\u53ef\u7528',
  safety: '\u5b89\u5168',
  status: '\u72b6\u6001',
  normal: '\u5e93\u5b58\u6b63\u5e38',
  low: '\u4f4e\u4e8e\u5b89\u5168\u5e93\u5b58',
  unavailable: '\u65e0\u53ef\u7528\u5e93\u5b58',
  out: '\u7f3a\u8d27',
  updated: '\u5df2\u66f4\u65b0\u4e3a',
};

const PREVIEW_LIMIT = 5;
const router = useRouter();
const selectedWarehouseId = ref('all');
const warehouseOptions = ref<Array<{ value: string; label: string }>>([{ value: 'all', label: TEXT.allWarehouses }]);
const status = ref<DashboardInventoryStatus | null>(null);
const busy = ref(false);
const transitioning = ref(false);
let requestSequence = 0;
let transitionTimer: number | undefined;

const selectedWarehouseLabel = computed(() => selectedWarehouseId.value === 'all'
  ? TEXT.allWarehouses
  : warehouseOptions.value.find(item => item.value === selectedWarehouseId.value)?.label || '');
const selectedWarehouseName = computed(() => selectedWarehouseLabel.value.replace(/^[^\s]+\s+/, ''));
const statusMeta = [
  { status: 'NORMAL', label: TEXT.normal, color: '#10b981', badge: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  { status: 'LOW_STOCK', label: TEXT.low, color: '#f59e0b', badge: 'border-amber-200 bg-amber-50 text-amber-700' },
  { status: 'NO_AVAILABLE', label: TEXT.unavailable, color: '#f97316', badge: 'border-orange-200 bg-orange-50 text-orange-700' },
  { status: 'OUT_OF_STOCK', label: TEXT.out, color: '#e11d48', badge: 'border-rose-200 bg-rose-50 text-rose-700' },
] as const;
const distribution = computed(() => statusMeta.map(meta => ({ ...meta, recordCount: status.value?.distribution.find(item => item.status === meta.status)?.recordCount ?? 0 })));
const donutStyle = computed(() => {
  const total = distribution.value.reduce((sum, item) => sum + item.recordCount, 0);
  if (!total) return { background: '#e2e8f0' };
  let offset = 0;
  const stops = distribution.value.map(item => {
    const next = offset + item.recordCount / total * 100;
    const value = `${item.color} ${offset}% ${next}%`;
    offset = next;
    return value;
  });
  return { background: `conic-gradient(${stops.join(', ')})` };
});
const riskCount = computed(() => distribution.value.filter(item => item.status !== 'NORMAL').reduce((sum, item) => sum + item.recordCount, 0));
const totalRecordCount = computed(() => distribution.value.reduce((sum, item) => sum + item.recordCount, 0));
const previewItems = computed(() => status.value?.riskPreview.items ?? []);
const canShow = computed(() => status.value?.access.state !== 'DENIED');

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}
function mergeWarehouseOptions(options: Array<{ value: string; label: string }>) {
  const cache = new Map(warehouseOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  warehouseOptions.value = [{ value: 'all', label: TEXT.allWarehouses }, ...Array.from(cache.values()).filter(item => item.value !== 'all')];
}
async function fetchWarehouseOptions(keyword: string) {
  const page = await listWarehouses({ pageNum: 1, pageSize: 10, ...warehouseKeywordQuery(keyword) }, { skipPageLoading: true });
  const options = page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
  mergeWarehouseOptions(options);
  return options;
}
async function loadStatus() {
  const current = ++requestSequence;
  busy.value = true;
  try {
    const next = await getDashboardInventoryStatus(selectedWarehouseId.value === 'all' ? undefined : selectedWarehouseId.value);
    if (current !== requestSequence) return;
    status.value = next;
    transitioning.value = true;
    if (transitionTimer) window.clearTimeout(transitionTimer);
    transitionTimer = window.setTimeout(() => { transitioning.value = false; }, 220);
  } catch (error) {
    if (current === requestSequence) toast.warning(getApiErrorMessage(error) || '\u5e93\u5b58\u72b6\u6001\u52a0\u8f7d\u5931\u8d25');
  } finally {
    if (current === requestSequence) busy.value = false;
  }
}
function openAllRisks() {
  const query: Record<string, string> = { riskOnly: 'true' };
  if (selectedWarehouseId.value !== 'all') query.warehouseId = selectedWarehouseId.value;
  void router.push({ path: '/warehouse/stocks', query });
}
function formatCount(value: number) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value); }
function formatRiskQty(value: number, quantityPrecision: number) { return formatQtyByPrecision(value, quantityPrecision); }
function alertMeta(severity: string) { return statusMeta.find(item => item.status === severity) ?? statusMeta[1]; }
watch(selectedWarehouseId, () => { void loadStatus(); });
onMounted(() => { void loadStatus(); });
onBeforeUnmount(() => { requestSequence += 1; if (transitionTimer) window.clearTimeout(transitionTimer); });
</script>

<template>
  <section class="inventory-status-panel" :aria-busy="busy">
    <header class="inventory-status-panel__header">
      <div class="inventory-status-panel__heading">
        <div class="inventory-status-panel__title"><PieChart class="h-4 w-4 text-primary" /><h2>{{ TEXT.title }}</h2></div>
        <p>{{ TEXT.description }}</p>
      </div>
      <div class="inventory-status-panel__filter">
        <span>统计仓库</span>
        <RemoteSearchSelect v-model="selectedWarehouseId" class="w-full" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseOptions" :placeholder="TEXT.allWarehouses" :search-placeholder="TEXT.searchWarehouse" clearable clear-value="all" :clear-label="TEXT.allWarehouses" :disabled="busy" />
      </div>
    </header>
    <div class="inventory-status-panel__body">
      <p class="sr-only" aria-live="polite">{{ busy ? '' : `${TEXT.updated}${selectedWarehouseName}` }}</p>
      <div v-if="canShow" :class="['inventory-status-panel__content', { 'inventory-status-panel__content--changing': transitioning }]">
        <section class="inventory-status-panel__chart" :aria-label="`${selectedWarehouseName}${TEXT.title}`">
          <div class="inventory-status-panel__donut" :style="donutStyle"><div><span>{{ TEXT.riskInventory }}</span><strong>{{ formatCount(riskCount) }}</strong><small>{{ TEXT.totalRecords.replace('{count}', formatCount(totalRecordCount)) }}</small></div></div>
          <ul class="inventory-status-panel__legend">
            <li v-for="item in distribution" :key="item.status"><i :style="{ backgroundColor: item.color }" /><span>{{ item.label }}</span><strong>{{ formatCount(item.recordCount) }}</strong></li>
          </ul>
        </section>
        <section class="inventory-status-panel__preview">
          <div class="inventory-status-panel__preview-head"><div class="inventory-status-panel__preview-title"><span>{{ TEXT.riskRecords }}</span><small>{{ TEXT.previewHint.replace('{count}', String(PREVIEW_LIMIT)) }}</small></div></div>
          <div v-if="previewItems.length" class="inventory-status-panel__table-wrap">
            <table class="inventory-status-panel__table">
              <colgroup v-if="selectedWarehouseId === 'all'"><col class="product" /><col class="warehouse" /><col class="quantity" /><col class="quantity" /><col class="state" /></colgroup>
              <colgroup v-else><col class="product-selected" /><col class="quantity" /><col class="quantity" /><col class="state" /></colgroup>
              <thead><tr><th>{{ TEXT.product }}</th><th v-if="selectedWarehouseId === 'all'">{{ TEXT.warehouse }}</th><th>{{ TEXT.available }}库存</th><th>{{ TEXT.safety }}库存</th><th>{{ TEXT.status }}</th></tr></thead>
              <tbody><tr v-for="alert in previewItems" :key="alert.stockId"><td><div class="inventory-status-panel__product"><code>{{ alert.productCode }}</code><OverflowTooltip :text="alert.productName" fallback="-" :lines="2" class="inventory-status-panel__product-name" data-dashboard-inventory-product-name /></div></td><td v-if="selectedWarehouseId === 'all'"><OverflowTooltip :text="alert.warehouseName" fallback="-" class="block" data-dashboard-inventory-warehouse-name /></td><td class="is-risk">{{ formatRiskQty(alert.availableQty, alert.quantityPrecision) }} {{ alert.unitName }}</td><td>{{ formatRiskQty(alert.safetyStockQty, alert.quantityPrecision) }} {{ alert.unitName }}</td><td><Badge variant="outline" :class="alertMeta(alert.severity).badge">{{ alertMeta(alert.severity).label }}</Badge></td></tr></tbody>
            </table>
          </div>
          <p v-else class="inventory-status-panel__empty">{{ TEXT.empty }}</p>
          <div v-if="previewItems.length" class="inventory-status-panel__preview-footer"><small>{{ TEXT.sortHint }}</small><Button size="sm" variant="outline" class="inventory-status-panel__action" @click="openAllRisks">{{ TEXT.viewAll }}<ChevronRight class="ml-1 h-4 w-4" /></Button></div>
        </section>
      </div>
      <div v-else class="inventory-status-panel__empty"><AlertTriangle class="h-4 w-4" />{{ TEXT.denied }}</div>
    </div>
  </section>
</template>

<style scoped>
.inventory-status-panel { overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: var(--card); box-shadow: 0 1px 2px rgb(15 23 42 / 4%); }
.inventory-status-panel__header { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 88px; padding: 18px 20px; border-bottom: 1px solid var(--border); }
.inventory-status-panel__heading { min-width: 0; }.inventory-status-panel__title { display: flex; align-items: center; gap: 8px; }.inventory-status-panel__title h2 { margin: 0; font-size: 16px; font-weight: 650; line-height: 24px; }.inventory-status-panel__heading p { margin: 4px 0 0; color: var(--muted-foreground); font-size: 12px; line-height: 18px; }
.inventory-status-panel__filter { display: grid; flex: 0 0 252px; gap: 5px; }.inventory-status-panel__filter > span { color: var(--muted-foreground); font-size: 12px; font-weight: 500; }
.inventory-status-panel__body { padding: 18px 20px 20px; }.inventory-status-panel__content { display: grid; grid-template-columns: minmax(340px, 380px) minmax(0, 1fr); gap: 0; transition: opacity 180ms var(--motion-ease-standard), transform 180ms var(--motion-ease-standard); }.inventory-status-panel__content--changing { opacity: .72; transform: translateY(2px); }
.inventory-status-panel__chart { display: grid; grid-template-rows: 230px auto; align-content: center; justify-items: center; gap: 24px; min-height: 372px; padding: 14px 34px 14px 4px; }.inventory-status-panel__donut { display: grid; width: 230px; height: 230px; place-items: center; border-radius: 50%; box-shadow: inset 0 0 0 1px rgb(255 255 255 / 28%); transition: background 220ms var(--motion-ease-standard); }.inventory-status-panel__donut > div { display: grid; width: 156px; height: 156px; align-content: center; justify-items: center; border-radius: 50%; background: var(--card); text-align: center; }.inventory-status-panel__donut strong { order: 2; margin-top: 7px; font-size: 42px; font-variant-numeric: tabular-nums; line-height: 1; }.inventory-status-panel__donut span { order: 1; color: var(--foreground); font-size: 14px; font-weight: 600; line-height: 20px; }.inventory-status-panel__donut small { order: 3; margin-top: 10px; color: var(--muted-foreground); font-size: 12px; line-height: 17px; }
.inventory-status-panel__legend { display: grid; grid-template-columns: repeat(2, max-content); justify-content: center; column-gap: 28px; row-gap: 13px; width: 100%; margin: 0; padding: 0; list-style: none; }.inventory-status-panel__legend li { display: grid; grid-template-columns: 8px max-content max-content; align-items: center; gap: 8px; min-width: 0; color: var(--muted-foreground); font-size: 13px; line-height: 20px; }.inventory-status-panel__legend i { width: 8px; height: 8px; border-radius: 50%; }.inventory-status-panel__legend span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.inventory-status-panel__legend strong { color: var(--foreground); font-weight: 650; font-variant-numeric: tabular-nums; }
.inventory-status-panel__preview { display: flex; min-width: 0; min-height: 372px; flex-direction: column; padding: 13px 0 4px 34px; border-left: 1px solid color-mix(in srgb, var(--border) 90%, transparent); }.inventory-status-panel__preview-head { display: flex; align-items: center; min-height: 35px; margin-bottom: 10px; }.inventory-status-panel__preview-title { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; }.inventory-status-panel__preview-head span { color: var(--foreground); font-size: 16px; font-weight: 650; line-height: 24px; }.inventory-status-panel__preview-title small { color: var(--muted-foreground); font-size: 12px; font-weight: 400; line-height: 17px; }.inventory-status-panel__table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 9px; }.inventory-status-panel__table { width: 100%; min-width: 580px; border-collapse: collapse; table-layout: fixed; }.inventory-status-panel__table .product { width: 31%; }.inventory-status-panel__table .warehouse { width: 18%; }.inventory-status-panel__table .quantity { width: 14%; }.inventory-status-panel__table .state { width: 23%; }.inventory-status-panel__table .product-selected { width: 47%; }.inventory-status-panel__table th, .inventory-status-panel__table td { min-height: 48px; padding: 9px 10px; border-bottom: 1px solid var(--border); text-align: center; vertical-align: middle; font-size: 12px; line-height: 17px; }.inventory-status-panel__table th { height: 38px; background: color-mix(in srgb, var(--muted) 58%, var(--card)); color: var(--muted-foreground); font-weight: 600; }.inventory-status-panel__table tr:last-child td { border-bottom: 0; }.inventory-status-panel__product { display: grid; justify-items: center; gap: 4px; min-width: 0; }.inventory-status-panel__product code { max-width: 100%; overflow: hidden; border-radius: 4px; background: var(--muted); padding: 1px 4px; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }.inventory-status-panel__product-name { display: -webkit-box; overflow: hidden; max-width: 100%; -webkit-box-orient: vertical; -webkit-line-clamp: 2; word-break: break-word; font-weight: 500; }.inventory-status-panel__table td:nth-child(2) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.inventory-status-panel__table .is-risk { color: #be123c; font-weight: 650; font-variant-numeric: tabular-nums; }
.inventory-status-panel__preview-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }.inventory-status-panel__preview-footer small { color: var(--muted-foreground); font-size: 12px; line-height: 17px; }.inventory-status-panel__action { flex: 0 0 auto; }.inventory-status-panel__empty { display: flex; min-height: 180px; align-items: center; justify-content: center; gap: 8px; color: var(--muted-foreground); font-size: 13px; }.inventory-status-panel__preview > .inventory-status-panel__empty { min-height: 214px; }
@media (max-width: 1280px) { .inventory-status-panel__content { grid-template-columns: 1fr; }.inventory-status-panel__chart { grid-template-columns: 230px minmax(0, 1fr); grid-template-rows: none; min-height: 0; padding-right: 4px; }.inventory-status-panel__legend { justify-content: start; width: auto; }.inventory-status-panel__preview { min-height: 0; margin-top: 18px; padding: 22px 0 4px; border-top: 1px solid color-mix(in srgb, var(--border) 90%, transparent); border-left: 0; } }
@media (max-width: 720px) { .inventory-status-panel__header { align-items: stretch; flex-direction: column; }.inventory-status-panel__filter { flex-basis: auto; width: 100%; }.inventory-status-panel__body { padding: 14px; }.inventory-status-panel__chart { grid-template-columns: 1fr; grid-template-rows: 230px auto; padding: 0; }.inventory-status-panel__legend { grid-template-columns: 1fr; justify-content: start; width: 100%; }.inventory-status-panel__preview-footer { align-items: stretch; flex-direction: column; }.inventory-status-panel__action { width: 100%; }.inventory-status-panel__table { min-width: 560px; } }@media (prefers-reduced-motion: reduce) { .inventory-status-panel__content, .inventory-status-panel__donut { transition: none; } }
</style>