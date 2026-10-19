<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { toast } from 'vue-sonner';
import { ChevronRight } from 'lucide-vue-next';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import DashboardEmptyPanel from '@/components/dashboard/DashboardEmptyPanel.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
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
const loadError = ref<string | null>(null);
let requestSequence = 0;

const selectedWarehouseLabel = computed(() => selectedWarehouseId.value === 'all'
  ? TEXT.allWarehouses
  : warehouseOptions.value.find(item => item.value === selectedWarehouseId.value)?.label || '');
const selectedWarehouseName = computed(() => selectedWarehouseLabel.value.replace(/^[^\s]+\s+/, ''));
const statusMeta = [
  { status: 'NORMAL', label: TEXT.normal, color: '#7cbcaf', badge: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  { status: 'LOW_STOCK', label: TEXT.low, color: '#e0b96d', badge: 'border-amber-200 bg-amber-50 text-amber-700' },
  { status: 'NO_AVAILABLE', label: TEXT.unavailable, color: '#e29191', badge: 'border-orange-200 bg-orange-50 text-orange-700' },
  { status: 'OUT_OF_STOCK', label: TEXT.out, color: '#bd7e95', badge: 'border-rose-200 bg-rose-50 text-rose-700' },
] as const;
const distribution = computed(() => statusMeta.map(meta => ({ ...meta, recordCount: status.value?.distribution.find(item => item.status === meta.status)?.recordCount ?? 0 })));
const riskCount = computed(() => distribution.value.filter(item => item.status !== 'NORMAL').reduce((sum, item) => sum + item.recordCount, 0));
const totalRecordCount = computed(() => distribution.value.reduce((sum, item) => sum + item.recordCount, 0));
const previewItems = computed(() => status.value?.riskPreview.items ?? []);
const accessState = computed(() => status.value?.access.state ?? null);
const isAllowed = computed(() => accessState.value === 'ALLOWED');
const panelState = computed<'EMPTY' | 'DENIED'>(() => accessState.value === 'DENIED' ? 'DENIED' : 'EMPTY');

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
  loadError.value = null;
  try {
    const next = await getDashboardInventoryStatus(selectedWarehouseId.value === 'all' ? undefined : selectedWarehouseId.value);
    if (current !== requestSequence) return;
    status.value = next;
  } catch (error) {
    if (current === requestSequence) { loadError.value = getApiErrorMessage(error) || '库存状态加载失败'; toast.warning(loadError.value); }
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
onBeforeUnmount(() => { requestSequence += 1; });
</script>

<template>
  <section class="inventory-status-panel dashboard-panel" :aria-busy="busy">
    <header class="dashboard-panel-heading inventory-status-panel__header"><div><h2>{{ TEXT.title }}</h2><p>按产品与仓库记录统计</p></div><div class="inventory-status-panel__filter" role="group" aria-label="统计仓库"><RemoteSearchSelect v-model="selectedWarehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseOptions" :placeholder="TEXT.allWarehouses" :search-placeholder="TEXT.searchWarehouse" clearable clear-value="all" :clear-label="TEXT.allWarehouses" :disabled="busy" /></div></header>
    <div class="inventory-status-panel__body">
      <p class="sr-only" aria-live="polite">{{ busy ? '' : `${TEXT.updated}${selectedWarehouseName}` }}</p>
      <p v-if="loadError" class="inventory-status-panel__error" role="status">{{ status ? '刷新失败，保留上次数据。' : loadError }}<Button size="sm" variant="ghost" :disabled="busy" @click="loadStatus">重试</Button></p>
      <template v-if="isAllowed"><section class="inventory-status-panel__distribution" :aria-label="`${selectedWarehouseName}库存四态分布`"><div class="inventory-status-panel__band" aria-hidden="true"><span v-for="item in distribution" :key="item.status" :style="{ flex: item.recordCount, background: item.color }" /></div><ul class="inventory-status-panel__legend"><li v-for="item in distribution" :key="item.status"><span><i :style="{ background: item.color }" />{{ item.status === 'NORMAL' ? '正常' : item.status === 'LOW_STOCK' ? '低库存' : item.status === 'NO_AVAILABLE' ? '无可用' : '零库存' }}</span><strong>{{ formatCount(item.recordCount) }}</strong></li></ul><span class="sr-only">风险库存 {{ formatCount(riskCount) }}，共 {{ formatCount(totalRecordCount) }} 个库存记录</span></section>
        <div class="inventory-status-panel__risk-heading"><h3>风险库存</h3><span>可用 / 安全库存</span></div><div class="inventory-status-panel__risks"><article v-for="alert in previewItems" :key="alert.stockId" class="inventory-status-panel__risk-row"><div><OverflowTooltip :text="alert.productName" fallback="—" :lines="2" data-dashboard-inventory-product-name class="inventory-status-panel__product-name" /><small><span>{{ alert.productCode }}</span> · <OverflowTooltip :text="alert.warehouseName" fallback="—" data-dashboard-inventory-warehouse-name /></small><span class="sr-only">{{ alertMeta(alert.severity).label }}</span></div><div class="inventory-status-panel__quantity"><strong>{{ formatRiskQty(alert.availableQty, alert.quantityPrecision) }}</strong><span> / {{ formatRiskQty(alert.safetyStockQty, alert.quantityPrecision) }}</span><small>{{ alert.unitName }}</small></div></article><p v-if="!previewItems.length" class="inventory-status-panel__empty">当前没有风险库存</p></div>
      </template>
      <div v-else-if="accessState" class="inventory-status-panel__state" data-dashboard-inventory-state><DashboardEmptyPanel :state="panelState" :title="accessState === 'DENIED' ? TEXT.denied : TEXT.empty" :description="accessState === 'DENIED' ? '请联系管理员开通仓储查看权限。' : '当前统计范围内没有库存记录。'" cover layout="stacked" /></div><div v-else-if="!loadError" class="inventory-status-panel__loading-space" aria-hidden="true" />
      <ListLoadingOverlay :visible="busy" :initial-delay="0" label="正在读取库存状态" />
    </div>
    <footer class="dashboard-panel-foot"><span>{{ TEXT.previewHint.replace('{count}', String(PREVIEW_LIMIT)) }}</span><Button v-if="isAllowed" size="sm" variant="ghost" class="inventory-status-panel__action" :disabled="busy" @click="openAllRisks">{{ TEXT.viewAll }}<ChevronRight /></Button></footer>
  </section>
</template>

<style scoped>
.inventory-status-panel{display:flex;flex-direction:column;min-width:0;background:#fff}.inventory-status-panel__header{align-items:flex-start}.inventory-status-panel__header>div:first-child{flex:1}.inventory-status-panel__filter{flex:0 1 180px;width:180px;min-width:118px}.inventory-status-panel__filter :deep(button){height:33px;font-size:12px}.inventory-status-panel__filter :deep([role=combobox]>svg){display:none}.inventory-status-panel__filter :deep([role=combobox]):after{content:'';width:6px;height:6px;border-right:1.5px solid #92a0b2;border-bottom:1.5px solid #92a0b2;transform:rotate(45deg) translateY(-2px);margin-left:10px;flex:none;transition:transform 160ms}.inventory-status-panel__filter :deep([role=combobox][aria-expanded=true]):after{transform:rotate(225deg) translate(-1px,-1px)}.inventory-status-panel__body{position:relative;flex:1;min-width:0}.inventory-status-panel__distribution{padding:5px 22px 20px}.inventory-status-panel__band{display:flex;gap:3px;height:9px;border-radius:4px;overflow:hidden;margin:7px 0 17px;background:#f0f3f7}.inventory-status-panel__band span{min-width:0}.inventory-status-panel__legend{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin:0;padding:0;list-style:none}.inventory-status-panel__legend span{display:flex;align-items:center;gap:4px;color:#78859a;font-size:11px;white-space:nowrap}.inventory-status-panel__legend i{width:5px;height:5px;border-radius:50%;flex:none}.inventory-status-panel__legend strong{display:block;margin:8px 0 0 9px;font-size:19px;font-weight:500;color:#40516b;font-variant-numeric:tabular-nums}.inventory-status-panel__risk-heading{display:flex;align-items:center;justify-content:space-between;margin:0 22px 5px;padding-top:16px;border-top:1px solid #e8edf3;gap:10px}.inventory-status-panel__risk-heading h3{font-size:13px;font-weight:600}.inventory-status-panel__risk-heading>span{color:#98a3b3;font-size:11px}.inventory-status-panel__risks{padding:0 22px;min-height:167px}.inventory-status-panel__risk-row{display:flex;gap:12px;align-items:center;min-height:64px;border-bottom:1px solid #f0f3f7;padding:9px 0}.inventory-status-panel__risk-row:last-child{border:0}.inventory-status-panel__risk-row>div:first-child{flex:1;min-width:0}.inventory-status-panel__product-name{font-weight:500;font-size:14px}.inventory-status-panel__risk-row small{display:flex;align-items:center;gap:4px;margin-top:4px;color:#93a0b0;font-size:11px;min-width:0}.inventory-status-panel__risk-row small>span:first-child{white-space:nowrap}.inventory-status-panel__quantity{text-align:right;color:#c0813d;font-size:13px;white-space:nowrap;font-variant-numeric:tabular-nums}.inventory-status-panel__quantity strong{font-weight:500}.inventory-status-panel__quantity>span{color:#8d9aaf}.inventory-status-panel__quantity small{display:inline;color:#9aa6b5;margin-left:3px;font-size:11px}.inventory-status-panel__action{font-size:12px;padding:0;height:27px;color:#5b7cc0;white-space:nowrap}.inventory-status-panel__action :deep(svg){width:13px;height:13px}.inventory-status-panel__empty{padding:40px 12px;color:#94a0b2;text-align:center;font-size:13px}.inventory-status-panel__state,.inventory-status-panel__loading-space{display:flex;min-height:330px;padding:14px 22px 20px}.inventory-status-panel__state :deep(.dashboard-empty-panel){width:100%;border:none;background:transparent}.inventory-status-panel__error{padding:5px 22px;font-size:12px;color:#aa7539}
@media(max-width:1200px){.inventory-status-panel__filter{flex-basis:145px;width:145px}.inventory-status-panel__distribution{padding-inline:17px}.inventory-status-panel__risks{padding-inline:17px}.inventory-status-panel__risk-heading{margin-inline:17px}.inventory-status-panel__product-name{font-size:13px}.inventory-status-panel__risk-row{gap:8px}.inventory-status-panel__quantity{font-size:12px}.inventory-status-panel__legend{gap:5px}.inventory-status-panel__legend span{font-size:10px}}
@media(max-width:1020px){.inventory-status-panel__filter{flex-basis:210px;width:210px}.inventory-status-panel__legend span{font-size:12px}.inventory-status-panel__product-name{font-size:14px}}
@media(max-width:650px){.inventory-status-panel__filter{flex-basis:148px;width:148px;min-width:110px}.inventory-status-panel__header{gap:9px}.inventory-status-panel__legend span{font-size:11px}.inventory-status-panel__quantity{font-size:13px}}
</style>
