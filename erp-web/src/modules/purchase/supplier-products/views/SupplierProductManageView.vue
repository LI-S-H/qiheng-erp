<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import {
  batchDeleteSupplierProducts,
  batchUpdateSupplierProductStatus,
  createSupplierProduct,
  deleteSupplierProduct,
  listEnabledProductOptions,
  listSupplierOptions,
  listSupplierProducts,
  updateSupplierProduct,
} from '../../api';
import type { SupplierProductFormPayload, SupplierProductListItem, SupplierProductQuery } from '../../types';

interface ProductOption {
  value: string;
  label: string;
  product: { productId: string; productCode: string; productName: string; unitName: string; referencePurchasePrice: number };
}

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const records = ref<SupplierProductListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingId = ref('');
const detailVisible = ref(false);
const detailRow = ref<SupplierProductListItem | null>(null);
const selectedIds = ref<Set<string>>(new Set());
const supplierOptions = ref<Array<{ value: string; label: string; disabled?: boolean }>>([{ value: 'all', label: '全部供应商' }]);
const productOptions = ref<ProductOption[]>([]);
let requestSequence = 0;

const query = reactive<SupplierProductQuery>({
  supplierId: 'all',
  productCode: '',
  productName: '',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<SupplierProductFormPayload>({
  supplierId: '',
  productId: '',
  supplierProductCode: '',
  latestPurchasePrice: 0,
  minOrderQty: 1,
  leadTimeDays: 3,
  deliveryScore: 80,
  qualityScore: 80,
  priceScore: 80,
  aiScore: 80,
  status: 1,
  remark: '',
});
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'warning' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as () => void | Promise<void>,
});

const queryBusy = computed(() => loading.value || queryPending.value);
const enabledCount = computed(() => records.value.filter(item => item.status === 1).length);
const disabledCount = computed(() => records.value.filter(item => item.status === 0).length);
const highRecommendCount = computed(() => records.value.filter(item => item.aiScore >= 90).length);
const avgLeadDays = computed(() => records.value.length ? records.value.reduce((sum, item) => sum + item.leadTimeDays, 0) / records.value.length : 0);
const allSelected = computed(() => records.value.length > 0 && records.value.every(item => selectedIds.value.has(item.supplierProductId)));

async function loadOptions() {
  try {
    const [suppliers, products] = await Promise.all([listSupplierOptions(), listEnabledProductOptions()]);
    supplierOptions.value = [
      { value: 'all', label: '全部供应商' },
      ...suppliers.map(item => ({ value: item.supplierId, label: `${item.supplierCode} ${item.supplierName}`, disabled: item.status === 0 })),
    ];
    productOptions.value = products as ProductOption[];
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '供应商或产品选项加载失败');
  }
}

async function fetchRecords() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await listSupplierProducts({ ...query });
    if (sequence !== requestSequence) return;
    records.value = page.records;
    total.value = page.total;
    selectedIds.value = new Set();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '供货产品列表加载失败');
  } finally {
    if (sequence === requestSequence) {
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
  Object.assign(query, { supplierId: 'all', productCode: '', productName: '', status: 'all', pageNum: 1 });
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

function toggleSelectAll(value: boolean | 'indeterminate') {
  selectedIds.value = value === true ? new Set(records.value.map(item => item.supplierProductId)) : new Set();
}

function toggleSelect(id: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(id) : next.delete(id);
  selectedIds.value = next;
}

function resetForm() {
  Object.assign(form, {
    supplierId: '',
    productId: '',
    supplierProductCode: '',
    latestPurchasePrice: 0,
    minOrderQty: 1,
    leadTimeDays: 3,
    deliveryScore: 80,
    qualityScore: 80,
    priceScore: 80,
    aiScore: 80,
    status: 1,
    remark: '',
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingId.value = '';
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: SupplierProductListItem) {
  dialogMode.value = 'edit';
  editingId.value = row.supplierProductId;
  Object.assign(form, {
    supplierId: row.supplierId,
    productId: row.productId,
    supplierProductCode: row.supplierProductCode,
    latestPurchasePrice: row.latestPurchasePrice,
    minOrderQty: row.minOrderQty,
    leadTimeDays: row.leadTimeDays,
    deliveryScore: row.deliveryScore,
    qualityScore: row.qualityScore,
    priceScore: row.priceScore,
    aiScore: row.aiScore,
    status: row.status,
    remark: row.remark,
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function openDetail(row: SupplierProductListItem) {
  detailRow.value = row;
  detailVisible.value = true;
}

function validateScore(field: keyof SupplierProductFormPayload, label: string) {
  const value = Number(form[field]);
  if (!Number.isFinite(value) || value < 0 || value > 100) formErrors[field] = `${label}必须在 0 到 100 之间`;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.supplierId) formErrors.supplierId = '请选择供应商';
  if (!form.productId) formErrors.productId = '请选择产品';
  if (form.supplierProductCode.trim().length > 100) formErrors.supplierProductCode = '供应商侧编码不能超过 100 个字符';
  if (!Number.isFinite(Number(form.latestPurchasePrice)) || Number(form.latestPurchasePrice) < 0) formErrors.latestPurchasePrice = '采购价不能小于 0';
  if (!Number.isFinite(Number(form.minOrderQty)) || Number(form.minOrderQty) <= 0) formErrors.minOrderQty = '起订量必须大于 0';
  if (!Number.isInteger(Number(form.leadTimeDays)) || Number(form.leadTimeDays) < 0) formErrors.leadTimeDays = '交期必须是非负整数';
  validateScore('deliveryScore', '交付评分');
  validateScore('qualityScore', '质量评分');
  validateScore('priceScore', '价格评分');
  validateScore('aiScore', '推荐分');
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function payload(): SupplierProductFormPayload {
  return {
    supplierId: form.supplierId,
    productId: form.productId,
    supplierProductCode: form.supplierProductCode.trim(),
    latestPurchasePrice: Number(form.latestPurchasePrice),
    minOrderQty: Number(form.minOrderQty),
    leadTimeDays: Number(form.leadTimeDays),
    deliveryScore: Number(form.deliveryScore),
    qualityScore: Number(form.qualityScore),
    priceScore: Number(form.priceScore),
    aiScore: Number(form.aiScore),
    status: form.status,
    remark: form.remark.trim(),
  };
}

function syncProductPrice(productId: string | number) {
  form.productId = String(productId);
  const selected = productOptions.value.find(item => item.value === String(productId));
  if (selected && dialogMode.value === 'create') {
    form.latestPurchasePrice = selected.product.referencePurchasePrice;
  }
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createSupplierProduct(payload());
      toast.success('供货产品已创建');
    } else {
      await updateSupplierProduct(editingId.value, payload());
      toast.success('供货产品已更新');
    }
    dialogVisible.value = false;
    await fetchRecords();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '供货产品保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

async function runConfirmAction() {
  if (actionSubmitting.value) return;
  actionSubmitting.value = true;
  try {
    await confirmState.onConfirm();
    confirmState.open = false;
  } finally {
    actionSubmitting.value = false;
  }
}

function confirmStatus(row: SupplierProductListItem, status: 0 | 1) {
  showConfirm(status === 1 ? '启用供货产品' : '停用供货产品', status === 1 ? '启用后可作为采购订单候选产品。' : '停用后不能作为新采购订单候选。', status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await batchUpdateSupplierProductStatus({ supplierProductIds: [row.supplierProductId], status });
    toast.success('供货产品状态已更新');
    await fetchRecords();
  });
}

function confirmDelete(row: SupplierProductListItem) {
  showConfirm('删除供货产品', '已被采购订单引用的供货产品会被后端拒绝删除。是否继续？', '删除', 'destructive', async () => {
    await deleteSupplierProduct(row.supplierProductId);
    toast.success('供货产品已删除');
    await fetchRecords();
  });
}

function confirmBatchStatus(status: 0 | 1) {
  if (selectedIds.value.size === 0) return;
  showConfirm(status === 1 ? '批量启用供货产品' : '批量停用供货产品', `将处理 ${selectedIds.value.size} 条供货关系。`, status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await batchUpdateSupplierProductStatus({ supplierProductIds: [...selectedIds.value], status });
    toast.success('批量状态已更新');
    await fetchRecords();
  });
}

function confirmBatchDelete() {
  if (selectedIds.value.size === 0) return;
  showConfirm('批量删除供货产品', '存在采购订单引用时，后端应整批返回 409。', '删除', 'destructive', async () => {
    await batchDeleteSupplierProducts({ supplierProductIds: [...selectedIds.value] });
    toast.success('供货产品已批量删除');
    await fetchRecords();
  });
}

function formatMoney(value: number) {
  return `￥${value.toFixed(2)}`;
}

function formatScore(value: number) {
  return value.toFixed(1);
}

onMounted(() => {
  loadOptions();
  fetchRecords();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">供货产品</h1>
        <p class="page-description">维护供应商可供货产品、最近采购价、起订量、预计交期和推荐分</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页启用</span><strong class="mt-1 text-2xl text-emerald-700">{{ enabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页停用</span><strong class="mt-1 text-2xl">{{ disabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页高推荐分</span><strong class="mt-1 text-2xl text-blue-700">{{ highRecommendCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">平均交期</span><strong class="mt-1 text-2xl">{{ avgLeadDays.toFixed(1) }} 天</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--purchase">
        <div class="space-y-1"><Label class="text-xs">供应商</Label><AnchoredSelect v-model="query.supplierId" :options="supplierOptions" /></div>
        <div class="space-y-1"><Label class="text-xs">产品编码</Label><Input v-model="query.productCode" placeholder="如 P0001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">产品名称</Label><Input v-model="query.productName" placeholder="请输入产品名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">供货产品列表</strong><span class="text-xs text-muted-foreground">供应商 + 产品唯一，推荐分用于采购建议候选排序</span></div>
        <div class="table-toolbar__actions">
          <Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(1)">批量启用</Button>
          <Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(0)">批量停用</Button>
          <Button size="sm" variant="outline" class="text-destructive hover:text-destructive" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchDelete">批量删除</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button>
          <Button size="sm" @click="openCreateDialog">新增供货产品</Button>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="business-data-table min-w-[1124px] table-fixed">
          <colgroup><col class="w-[44px]" /><col class="w-[135px]" /><col class="w-[180px]" /><col class="w-[105px]" /><col class="w-[120px]" /><col class="w-[82px]" /><col class="w-[76px]" /><col class="w-[82px]" /><col class="w-[75px]" /><col class="w-[225px]" /></colgroup>
          <TableHeader><TableRow><TableHead><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead><TableHead>供应商</TableHead><TableHead>产品</TableHead><TableHead>供应商侧编码</TableHead><TableHead class="text-right">最近采购价</TableHead><TableHead class="text-right">起订量</TableHead><TableHead class="text-center">交期</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && records.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="records.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无供货产品</TableCell></TableRow>
            <TableRow v-for="row in records" v-else :key="row.supplierProductId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.supplierProductId)" @update:model-value="value => toggleSelect(row.supplierProductId, value)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.supplierCode }}</code><div class="mt-1 truncate font-medium">{{ row.supplierName }}</div></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.productCode }}</code><div class="mt-1 truncate font-medium">{{ row.productName }}</div><div class="text-xs text-muted-foreground">单位：{{ row.unitName }}</div></TableCell>
              <TableCell class="truncate text-muted-foreground">{{ row.supplierProductCode || '未维护' }}</TableCell>
              <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(row.latestPurchasePrice) }}</TableCell>
              <TableCell class="text-right tabular-nums">{{ row.minOrderQty }}</TableCell>
              <TableCell class="text-center">{{ row.leadTimeDays }} 天</TableCell>
              <TableCell class="text-center font-semibold tabular-nums" :class="row.aiScore >= 90 ? 'text-emerald-700' : 'text-slate-700'">{{ formatScore(row.aiScore) }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-right"><Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" @click="openDetail(row)">详情</Button><Button variant="ghost" size="sm" @click="openEditDialog(row)">编辑</Button><Button variant="ghost" size="sm" :class="row.status === 1 ? 'text-amber-700 hover:text-amber-800' : 'text-primary hover:text-primary'" @click="confirmStatus(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmDelete(row)">删除</Button></TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent class="flex h-[min(720px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-3xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增供货产品' : '编辑供货产品' }}</DialogTitle><DialogDescription>供货产品用于采购候选和价格建议，请维护供应商、产品、采购价、起订量和交期。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="grid grid-cols-2 gap-4 py-2 max-sm:grid-cols-1">
            <div class="space-y-1"><Label>供应商 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.supplierId" :options="supplierOptions.filter(item => item.value !== 'all')" placeholder="请选择供应商" :invalid="Boolean(formErrors.supplierId)" /><p v-if="formErrors.supplierId" class="form-error">{{ formErrors.supplierId }}</p></div>
            <div class="space-y-1"><Label>产品 <span class="text-destructive">*</span></Label><AnchoredSelect :model-value="form.productId" :options="productOptions" placeholder="请选择产品" :invalid="Boolean(formErrors.productId)" @update:model-value="syncProductPrice" /><p v-if="formErrors.productId" class="form-error">{{ formErrors.productId }}</p></div>
            <div class="space-y-1"><Label>供应商侧编码</Label><Input v-model="form.supplierProductCode" /><p v-if="formErrors.supplierProductCode" class="form-error">{{ formErrors.supplierProductCode }}</p></div>
            <div class="space-y-1"><Label>状态</Label><AnchoredSelect v-model="form.status" :options="statusOptions.filter(item => item.value !== 'all')" /></div>
            <div class="space-y-1"><Label>最近采购价</Label><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="form.latestPurchasePrice" type="number" min="0" step="0.01" class="pl-8" /></div><p v-if="formErrors.latestPurchasePrice" class="form-error">{{ formErrors.latestPurchasePrice }}</p></div>
            <div class="space-y-1"><Label>最小起订量</Label><Input v-model.number="form.minOrderQty" type="number" min="0" step="0.0001" /><p v-if="formErrors.minOrderQty" class="form-error">{{ formErrors.minOrderQty }}</p></div>
            <div class="space-y-1"><Label>预计交期天数</Label><Input v-model.number="form.leadTimeDays" type="number" min="0" step="1" /><p v-if="formErrors.leadTimeDays" class="form-error">{{ formErrors.leadTimeDays }}</p></div>
            <div class="space-y-1"><Label>推荐分</Label><Input v-model.number="form.aiScore" type="number" min="0" max="100" step="0.1" /><p v-if="formErrors.aiScore" class="form-error">{{ formErrors.aiScore }}</p></div>
            <div class="space-y-1"><Label>交付评分</Label><Input v-model.number="form.deliveryScore" type="number" min="0" max="100" step="0.1" /><p v-if="formErrors.deliveryScore" class="form-error">{{ formErrors.deliveryScore }}</p></div>
            <div class="space-y-1"><Label>质量评分</Label><Input v-model.number="form.qualityScore" type="number" min="0" max="100" step="0.1" /><p v-if="formErrors.qualityScore" class="form-error">{{ formErrors.qualityScore }}</p></div>
            <div class="space-y-1"><Label>价格评分</Label><Input v-model.number="form.priceScore" type="number" min="0" max="100" step="0.1" /><p v-if="formErrors.priceScore" class="form-error">{{ formErrors.priceScore }}</p></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>备注</Label><Textarea v-model="form.remark" rows="3" /><p v-if="formErrors.remark" class="form-error">{{ formErrors.remark }}</p></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailVisible">
      <DialogContent class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-4xl">
        <DialogHeader><DialogTitle>供货产品详情</DialogTitle><DialogDescription>核对供应商供货关系、采购价格、交期和评分。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-4 p-1">
            <div class="purchase-detail-grid grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="purchase-detail-field"><span>供应商</span><code>{{ detailRow.supplierCode }}</code><strong>{{ detailRow.supplierName }}</strong></div>
              <div class="purchase-detail-field"><span>产品</span><code>{{ detailRow.productCode }}</code><strong>{{ detailRow.productName }}</strong><small>单位：{{ detailRow.unitName }}</small></div>
              <div class="purchase-detail-field"><span>状态</span><Badge variant="outline" :class="detailRow.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'">{{ detailRow.status === 1 ? '启用' : '停用' }}</Badge></div>
              <div class="purchase-detail-field"><span>供应商侧编码</span><strong>{{ detailRow.supplierProductCode || '未维护' }}</strong></div>
              <div class="purchase-detail-field"><span>最近采购价</span><strong>{{ formatMoney(detailRow.latestPurchasePrice) }}</strong></div>
              <div class="purchase-detail-field"><span>起订量</span><strong>{{ detailRow.minOrderQty }} {{ detailRow.unitName }}</strong></div>
            </div>
            <div class="purchase-score-grid">
              <div><span>预计交期</span><strong>{{ detailRow.leadTimeDays }} 天</strong></div>
              <div><span>推荐分</span><strong>{{ formatScore(detailRow.aiScore) }}</strong></div>
              <div><span>交付评分</span><strong>{{ formatScore(detailRow.deliveryScore) }}</strong></div>
              <div><span>质量评分</span><strong>{{ formatScore(detailRow.qualityScore) }}</strong></div>
              <div><span>价格评分</span><strong>{{ formatScore(detailRow.priceScore) }}</strong></div>
            </div>
            <div class="purchase-detail-grid grid grid-cols-2 gap-4 max-md:grid-cols-1">
              <div class="purchase-detail-field"><span>最近采购时间</span><strong>{{ detailRow.lastPurchaseAt || '暂无' }}</strong></div>
              <div class="purchase-detail-field"><span>更新时间</span><strong>{{ detailRow.updateTime }}</strong></div>
              <div class="purchase-detail-field purchase-detail-field--wide"><span>备注</span><strong>{{ detailRow.remark || '未维护' }}</strong></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" @click="detailVisible = false">关闭</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
