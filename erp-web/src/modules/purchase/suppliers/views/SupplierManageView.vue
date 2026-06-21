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
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import {
  batchDeleteSuppliers,
  batchUpdateSupplierStatus,
  createSupplier,
  deleteSupplier,
  listSuppliers,
  updateSupplier,
  updateSupplierStatus,
} from '../../api';
import type { SupplierFormPayload, SupplierListItem, SupplierQuery } from '../../types';

interface SupplierFormModel extends SupplierFormPayload {
  supplierCode: string;
}

const statusOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const suppliers = ref<SupplierListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingSupplierId = ref('');
const editingOriginalStatus = ref<0 | 1>(1);
const detailVisible = ref(false);
const detailRow = ref<SupplierListItem | null>(null);
let requestSequence = 0;

const query = reactive<SupplierQuery>({
  supplierCode: '',
  supplierName: '',
  contactName: '',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<SupplierFormModel>({
  supplierCode: '',
  supplierName: '',
  contactName: '',
  contactPhone: '',
  address: '',
  paymentTerms: '',
  overallScore: 80,
  deliveryScore: 80,
  qualityScore: 80,
  priceScore: 80,
  serviceScore: 80,
  avgDeliveryDays: 5,
  onTimeRate: 90,
  qualifiedRate: 95,
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
const enabledCount = computed(() => suppliers.value.filter(item => item.status === 1).length);
const disabledCount = computed(() => suppliers.value.filter(item => item.status === 0).length);
const topScoreCount = computed(() => suppliers.value.filter(item => item.overallScore >= 90).length);
const avgScore = computed(() => suppliers.value.length ? suppliers.value.reduce((sum, item) => sum + item.overallScore, 0) / suppliers.value.length : 0);
const allSelected = computed(() => suppliers.value.length > 0 && suppliers.value.every(item => selectedIds.value.has(item.supplierId)));

async function fetchSuppliers() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await listSuppliers({ ...query });
    if (sequence !== requestSequence) return;
    suppliers.value = page.records;
    total.value = page.total;
    selectedIds.value = new Set();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '供应商列表加载失败');
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchSuppliers();
}, 250);
const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchSuppliers();
}, 180);
const refreshList = useListRefresh(queryBusy, queryPending, fetchSuppliers);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  Object.assign(query, { supplierCode: '', supplierName: '', contactName: '', status: 'all', pageNum: 1 });
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
  selectedIds.value = value === true ? new Set(suppliers.value.map(item => item.supplierId)) : new Set();
}

function toggleSelect(supplierId: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(supplierId) : next.delete(supplierId);
  selectedIds.value = next;
}

function resetForm() {
  Object.assign(form, {
    supplierCode: '',
    supplierName: '',
    contactName: '',
    contactPhone: '',
    address: '',
    paymentTerms: '',
    overallScore: 80,
    deliveryScore: 80,
    qualityScore: 80,
    priceScore: 80,
    serviceScore: 80,
    avgDeliveryDays: 5,
    onTimeRate: 90,
    qualifiedRate: 95,
    status: 1,
    remark: '',
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingSupplierId.value = '';
  editingOriginalStatus.value = 1;
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: SupplierListItem) {
  dialogMode.value = 'edit';
  editingSupplierId.value = row.supplierId;
  editingOriginalStatus.value = row.status;
  Object.assign(form, row);
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function openDetail(row: SupplierListItem) {
  detailRow.value = row;
  detailVisible.value = true;
}

function validateScore(field: keyof SupplierFormPayload, label: string) {
  const value = Number(form[field]);
  if (!Number.isFinite(value) || value < 0 || value > 100) formErrors[field] = `${label}必须在 0 到 100 之间`;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.supplierName.trim()) formErrors.supplierName = '请输入供应商名称';
  else if (form.supplierName.trim().length > 200) formErrors.supplierName = '供应商名称不能超过 200 个字符';
  if (form.contactName.trim().length > 100) formErrors.contactName = '联系人不能超过 100 个字符';
  if (form.contactPhone.trim().length > 32) formErrors.contactPhone = '联系电话不能超过 32 个字符';
  if (form.address.trim().length > 255) formErrors.address = '地址不能超过 255 个字符';
  if (form.paymentTerms.trim().length > 100) formErrors.paymentTerms = '付款条件不能超过 100 个字符';
  if (!Number.isFinite(Number(form.avgDeliveryDays)) || Number(form.avgDeliveryDays) < 0) formErrors.avgDeliveryDays = '平均交付天数不能小于 0';
  validateScore('overallScore', '综合评分');
  validateScore('deliveryScore', '交付评分');
  validateScore('qualityScore', '质量评分');
  validateScore('priceScore', '价格评分');
  validateScore('serviceScore', '服务评分');
  validateScore('onTimeRate', '准时率');
  validateScore('qualifiedRate', '合格率');
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function toPayload(): SupplierFormPayload {
  return {
    supplierName: form.supplierName.trim(),
    contactName: form.contactName.trim(),
    contactPhone: form.contactPhone.trim(),
    address: form.address.trim(),
    paymentTerms: form.paymentTerms.trim(),
    overallScore: Number(form.overallScore),
    deliveryScore: Number(form.deliveryScore),
    qualityScore: Number(form.qualityScore),
    priceScore: Number(form.priceScore),
    serviceScore: Number(form.serviceScore),
    avgDeliveryDays: Number(form.avgDeliveryDays),
    onTimeRate: Number(form.onTimeRate),
    qualifiedRate: Number(form.qualifiedRate),
    status: form.status,
    remark: form.remark.trim(),
  };
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const payload = toPayload();
  if (dialogMode.value === 'edit' && editingOriginalStatus.value === 1 && payload.status === 0) {
    showConfirm('确认停用供应商', '停用后，该供应商不能再用于新建采购订单，关联供货产品也不应继续作为候选。是否继续？', '确认停用', 'warning', () => persistForm(payload));
    return;
  }
  await persistForm(payload);
}

async function persistForm(payload: SupplierFormPayload) {
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createSupplier(payload);
      toast.success('供应商已创建');
    } else {
      await updateSupplier(editingSupplierId.value, payload);
      toast.success('供应商已更新');
    }
    dialogVisible.value = false;
    await fetchSuppliers();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '供应商保存失败');
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

function confirmStatus(row: SupplierListItem, status: 0 | 1) {
  showConfirm(status === 1 ? '启用供应商' : '停用供应商', status === 1 ? '启用后可重新作为采购候选供应商。' : '停用后不能用于新建采购订单。', status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await updateSupplierStatus(row.supplierId, status);
    toast.success(status === 1 ? '供应商已启用' : '供应商已停用');
    await fetchSuppliers();
  });
}

function confirmDelete(row: SupplierListItem) {
  showConfirm('删除供应商', '已被供货产品或采购订单引用的供应商会被后端拒绝删除。是否继续？', '删除', 'destructive', async () => {
    await deleteSupplier(row.supplierId);
    toast.success('供应商已删除');
    await fetchSuppliers();
  });
}

function confirmBatchStatus(status: 0 | 1) {
  if (selectedIds.value.size === 0) return;
  showConfirm(status === 1 ? '批量启用供应商' : '批量停用供应商', `将处理 ${selectedIds.value.size} 个供应商。停用会影响后续采购候选，但不影响历史订单。`, status === 1 ? '启用' : '停用', status === 1 ? 'default' : 'warning', async () => {
    await batchUpdateSupplierStatus({ supplierIds: [...selectedIds.value], status });
    toast.success('批量状态已更新');
    await fetchSuppliers();
  });
}

function confirmBatchDelete() {
  if (selectedIds.value.size === 0) return;
  showConfirm('批量删除供应商', '已被业务引用的供应商会被后端拒绝删除，存在任一冲突时整批不应删除。', '删除', 'destructive', async () => {
    await batchDeleteSuppliers({ supplierIds: [...selectedIds.value] });
    toast.success('供应商已批量删除');
    await fetchSuppliers();
  });
}

function formatScore(value: number) {
  return value.toFixed(1);
}

onMounted(fetchSuppliers);
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">供应商管理</h1>
        <p class="page-description">维护供应商基础资料、付款条件和采购评分，作为采购订单与 AI 推荐供应商的基础</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页启用</span><strong class="mt-1 text-2xl text-emerald-700">{{ enabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页停用</span><strong class="mt-1 text-2xl">{{ disabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页高评分</span><strong class="mt-1 text-2xl text-blue-700">{{ topScoreCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页均分</span><strong class="mt-1 text-2xl">{{ formatScore(avgScore) }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--purchase">
        <div class="space-y-1"><Label class="text-xs">供应商编码</Label><Input v-model="query.supplierCode" placeholder="如 S001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">供应商名称</Label><Input v-model="query.supplierName" placeholder="请输入名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">联系人</Label><Input v-model="query.contactName" placeholder="请输入联系人" @keyup.enter="handleSearch" /></div>
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
        <div class="table-toolbar__title"><strong class="text-sm">供应商列表</strong><span class="text-xs text-muted-foreground">供应商编码由后端生成，页面不提交编码字段</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(1)">批量启用</Button></span></TooltipTrigger><TooltipContent>先选择供应商</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchStatus(0)">批量停用</Button></span></TooltipTrigger><TooltipContent>停用后不再作为采购候选</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span><Button size="sm" variant="outline" class="text-destructive hover:text-destructive" :disabled="selectedIds.size === 0 || queryBusy" @click="confirmBatchDelete">批量删除</Button></span></TooltipTrigger><TooltipContent>存在业务引用时后端应返回 409</TooltipContent></Tooltip>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button>
          <Button size="sm" @click="openCreateDialog">新增供应商</Button>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1420px] table-fixed">
          <colgroup><col class="w-[48px]" /><col class="w-[180px]" /><col class="w-[210px]" /><col class="w-[170px]" /><col class="w-[130px]" /><col class="w-[100px]" /><col class="w-[120px]" /><col class="w-[120px]" /><col class="w-[160px]" /><col class="w-[220px]" /></colgroup>
          <TableHeader><TableRow><TableHead><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead><TableHead>编码</TableHead><TableHead>供应商</TableHead><TableHead>联系人</TableHead><TableHead class="text-center">综合评分</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-center">准时率</TableHead><TableHead class="text-center">合格率</TableHead><TableHead>付款条件</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && suppliers.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="suppliers.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无供应商</TableCell></TableRow>
            <TableRow v-for="row in suppliers" v-else :key="row.supplierId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.supplierId)" @update:model-value="value => toggleSelect(row.supplierId, value)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.supplierCode }}</code></TableCell>
              <TableCell><div class="truncate font-medium" :title="row.supplierName">{{ row.supplierName }}</div><div class="truncate text-xs text-muted-foreground" :title="row.address">{{ row.address || '未维护地址' }}</div></TableCell>
              <TableCell><div>{{ row.contactName || '未维护' }}</div><div class="text-xs text-muted-foreground">{{ row.contactPhone || '无电话' }}</div></TableCell>
              <TableCell class="text-center font-semibold tabular-nums" :class="row.overallScore >= 90 ? 'text-emerald-700' : 'text-slate-700'">{{ formatScore(row.overallScore) }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-center tabular-nums">{{ formatScore(row.onTimeRate) }}%</TableCell>
              <TableCell class="text-center tabular-nums">{{ formatScore(row.qualifiedRate) }}%</TableCell>
              <TableCell class="truncate text-muted-foreground" :title="row.paymentTerms">{{ row.paymentTerms || '未维护' }}</TableCell>
              <TableCell class="text-right">
                <Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" @click="openDetail(row)">详情</Button>
                <Button variant="ghost" size="sm" @click="openEditDialog(row)">编辑</Button>
                <Button variant="ghost" size="sm" :class="row.status === 1 ? 'text-amber-700 hover:text-amber-800' : 'text-primary hover:text-primary'" @click="confirmStatus(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button>
                <Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmDelete(row)">删除</Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent class="flex h-[min(720px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden bg-background shadow-xl sm:max-w-3xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增供应商' : '编辑供应商' }}</DialogTitle></DialogHeader>
        <DialogScrollArea>
          <div class="grid grid-cols-2 gap-4 p-1 max-sm:grid-cols-1">
            <div class="space-y-1"><Label>供应商编码</Label><Input v-model="form.supplierCode" disabled placeholder="后端自动生成" /></div>
            <div class="space-y-1"><Label>供应商名称 <span class="text-destructive">*</span></Label><Input v-model="form.supplierName" :aria-invalid="Boolean(formErrors.supplierName)" /><p v-if="formErrors.supplierName" class="form-error">{{ formErrors.supplierName }}</p></div>
            <div class="space-y-1"><Label>联系人</Label><Input v-model="form.contactName" /><p v-if="formErrors.contactName" class="form-error">{{ formErrors.contactName }}</p></div>
            <div class="space-y-1"><Label>联系电话</Label><Input v-model="form.contactPhone" /><p v-if="formErrors.contactPhone" class="form-error">{{ formErrors.contactPhone }}</p></div>
            <div class="space-y-1"><Label>付款条件</Label><Input v-model="form.paymentTerms" placeholder="如 月结30天" /><p v-if="formErrors.paymentTerms" class="form-error">{{ formErrors.paymentTerms }}</p></div>
            <div class="space-y-1"><Label>状态</Label><AnchoredSelect v-model="form.status" :options="statusOptions.filter(item => item.value !== 'all')" /></div>
            <div class="space-y-1"><Label>地址</Label><Input v-model="form.address" /><p v-if="formErrors.address" class="form-error">{{ formErrors.address }}</p></div>
            <div class="space-y-1"><Label>平均交付天数</Label><Input v-model.number="form.avgDeliveryDays" type="number" min="0" step="0.1" /><p v-if="formErrors.avgDeliveryDays" class="form-error">{{ formErrors.avgDeliveryDays }}</p></div>
            <div v-for="field in ['overallScore','deliveryScore','qualityScore','priceScore','serviceScore','onTimeRate','qualifiedRate']" :key="field" class="space-y-1">
              <Label>{{ ({ overallScore: '综合评分', deliveryScore: '交付评分', qualityScore: '质量评分', priceScore: '价格评分', serviceScore: '服务评分', onTimeRate: '准时率', qualifiedRate: '合格率' } as Record<string, string>)[field] }}</Label>
              <Input v-model.number="(form as any)[field]" type="number" min="0" max="100" step="0.1" />
              <p v-if="formErrors[field]" class="form-error">{{ formErrors[field] }}</p>
            </div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>备注</Label><Textarea v-model="form.remark" rows="3" /><p v-if="formErrors.remark" class="form-error">{{ formErrors.remark }}</p></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailVisible">
      <DialogContent class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden bg-background shadow-xl sm:max-w-4xl">
        <DialogHeader><DialogTitle>供应商详情</DialogTitle></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-4 p-1">
            <div class="purchase-detail-grid grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="purchase-detail-field"><span>供应商编码</span><code>{{ detailRow.supplierCode }}</code></div>
              <div class="purchase-detail-field"><span>供应商名称</span><strong>{{ detailRow.supplierName }}</strong></div>
              <div class="purchase-detail-field"><span>状态</span><Badge variant="outline" :class="detailRow.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-50 text-slate-600'">{{ detailRow.status === 1 ? '启用' : '停用' }}</Badge></div>
              <div class="purchase-detail-field"><span>联系人</span><strong>{{ detailRow.contactName || '未维护' }}</strong><small>{{ detailRow.contactPhone || '无电话' }}</small></div>
              <div class="purchase-detail-field"><span>付款条件</span><strong>{{ detailRow.paymentTerms || '未维护' }}</strong></div>
              <div class="purchase-detail-field"><span>综合评分</span><strong>{{ formatScore(detailRow.overallScore) }}</strong></div>
            </div>
            <div class="purchase-score-grid">
              <div><span>交付评分</span><strong>{{ formatScore(detailRow.deliveryScore) }}</strong></div>
              <div><span>质量评分</span><strong>{{ formatScore(detailRow.qualityScore) }}</strong></div>
              <div><span>价格评分</span><strong>{{ formatScore(detailRow.priceScore) }}</strong></div>
              <div><span>服务评分</span><strong>{{ formatScore(detailRow.serviceScore) }}</strong></div>
              <div><span>准时率</span><strong>{{ formatScore(detailRow.onTimeRate) }}%</strong></div>
              <div><span>合格率</span><strong>{{ formatScore(detailRow.qualifiedRate) }}%</strong></div>
              <div><span>平均交付</span><strong>{{ detailRow.avgDeliveryDays }} 天</strong></div>
            </div>
            <div class="purchase-detail-grid grid grid-cols-2 gap-4 max-md:grid-cols-1">
              <div class="purchase-detail-field"><span>创建时间</span><strong>{{ detailRow.createTime }}</strong></div>
              <div class="purchase-detail-field"><span>更新时间</span><strong>{{ detailRow.updateTime }}</strong></div>
              <div class="purchase-detail-field purchase-detail-field--wide"><span>地址</span><strong>{{ detailRow.address || '未维护' }}</strong></div>
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
