<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import {
  batchDeleteWarehouses,
  batchUpdateWarehouseStatus,
  createWarehouse,
  deleteWarehouse,
  listWarehouses,
  updateWarehouse,
  updateWarehouseStatus,
} from '../api';
import type {
  WarehouseFormPayload,
  WarehouseListItem,
  WarehouseQuery,
  WarehouseStatus,
  WarehouseUpdatePayload,
} from '../types';

interface WarehouseFormModel extends WarehouseFormPayload { warehouseCode: string; version?: number }

const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const warehouses = ref<WarehouseListItem[]>([]);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingWarehouseId = ref('');
const editingOriginalStatus = ref<WarehouseStatus>(1);
let fetchSequence = 0;

const query = reactive<WarehouseQuery>({
  warehouseCode: '',
  warehouseName: '',
  contactName: '',
  contactPhone: '',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});
const form = reactive<WarehouseFormModel>({
  warehouseCode: '',
  warehouseName: '',
  contactName: '',
  contactPhone: '',
  address: '',
  status: 1,
  remark: '',
});
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const queryBusy = computed(() => queryPending.value || loading.value);
const enabledCount = computed(() => warehouses.value.filter(item => item.status === 1).length);
const disabledCount = computed(() => warehouses.value.filter(item => item.status === 0).length);
const contactReadyCount = computed(() => warehouses.value.filter(item => item.contactName && item.contactPhone).length);
const contactMissingCount = computed(() => warehouses.value.filter(item => !item.contactName || !item.contactPhone).length);
const hasNextPage = computed(() => warehouses.value.length >= query.pageSize);
const allSelected = computed(() => warehouses.value.length > 0 && warehouses.value.every(item => selectedIds.value.has(item.warehouseId)));

async function fetchWarehouses() {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listWarehouses({ ...query });
    if (sequence !== fetchSequence) return;
    warehouses.value = result.records;
    selectedIds.value = new Set();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '仓库列表加载失败');
  } finally {
    if (sequence === fetchSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

onMounted(fetchWarehouses);

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
  load: fetchWarehouses,
  resetFilters: () => {
    Object.assign(query, { warehouseCode: '', warehouseName: '', contactName: '', contactPhone: '', status: 'all' });
  },
});

function toggleSelectAll(value: boolean | 'indeterminate') {
  selectedIds.value = value === true ? new Set(warehouses.value.map(item => item.warehouseId)) : new Set();
}

function toggleSelect(warehouseId: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(warehouseId) : next.delete(warehouseId);
  selectedIds.value = next;
}

function selectedVersionMap() {
  return Object.fromEntries(
    warehouses.value
      .filter(item => selectedIds.value.has(item.warehouseId))
      .map(item => [item.warehouseId, item.version]),
  );
}

function resetForm() {
  Object.assign(form, {
    warehouseCode: '', warehouseName: '', contactName: '', contactPhone: '', address: '', status: 1, remark: '',
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingWarehouseId.value = '';
  editingOriginalStatus.value = 1;
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: WarehouseListItem) {
  dialogMode.value = 'edit';
  editingWarehouseId.value = row.warehouseId;
  editingOriginalStatus.value = row.status;
  Object.assign(form, {
    warehouseCode: row.warehouseCode,
    warehouseName: row.warehouseName,
    contactName: row.contactName,
    contactPhone: row.contactPhone,
    address: row.address,
    status: row.status,
    version: row.version,
    remark: row.remark,
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.warehouseName.trim()) formErrors.warehouseName = '请输入仓库名称';
  else if (form.warehouseName.trim().length > 100) formErrors.warehouseName = '仓库名称不能超过 100 个字符';
  if (form.contactName.trim().length > 100) formErrors.contactName = '联系人不能超过 100 个字符';
  if (form.contactPhone.trim().length > 32) formErrors.contactPhone = '联系电话不能超过 32 个字符';
  else if (form.contactPhone.trim() && !/^[0-9+()\-\s]+$/.test(form.contactPhone.trim())) formErrors.contactPhone = '联系电话格式不正确';
  if (form.address.trim().length > 255) formErrors.address = '仓库地址不能超过 255 个字符';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

const warehouseDisableWarning = '停用后，该仓库不能用于新建采购、销售、退货或库存调整业务，历史单据和现有库存不受影响。是否继续？';

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const basePayload: WarehouseFormPayload = {
    warehouseName: form.warehouseName.trim(),
    contactName: form.contactName.trim(),
    contactPhone: form.contactPhone.trim(),
    address: form.address.trim(),
    status: form.status,
    remark: form.remark.trim(),
  };
  const updatePayload: WarehouseUpdatePayload = { ...basePayload, version: Number(form.version) };
  if (dialogMode.value === 'edit' && editingOriginalStatus.value === 1 && updatePayload.status === 0) {
    showConfirm('确认停用仓库', warehouseDisableWarning, '确认停用', 'warning', () => persistForm(basePayload, updatePayload));
    return;
  }
  await persistForm(basePayload, updatePayload);
}

async function persistForm(createPayload: WarehouseFormPayload, updatePayload: WarehouseUpdatePayload) {
  if (formSubmitting.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createWarehouse(createPayload);
      toast.success('仓库已创建');
    } else {
      await updateWarehouse(editingWarehouseId.value, updatePayload);
      toast.success('仓库已更新');
    }
    dialogVisible.value = false;
    await fetchWarehouses();
  } catch (error) {
    const message = getApiErrorMessage(error) || '仓库保存失败';
    toast.warning(message);
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

function handleStatusChange(row: WarehouseListItem, status: WarehouseStatus) {
  const action = status === 1 ? '启用' : '停用';
  showConfirm(`${action}仓库`, status === 0 ? warehouseDisableWarning : `确认启用「${row.warehouseName}」吗？`, action, status === 0 ? 'warning' : 'default', async () => {
    try {
      await updateWarehouseStatus(row.warehouseId, status, row.version);
      toast.success(`仓库已${action}`);
      await fetchWarehouses();
    } catch (error) {
      toast.warning(getApiErrorMessage(error) || `仓库${action}失败`);
    }
  });
}

function handleDelete(row: WarehouseListItem) {
  showConfirm('删除仓库', `确认删除「${row.warehouseName}」吗？存在库存余额、入库单、出库单或库存流水的仓库无法删除。`, '删除', 'destructive', async () => {
    try {
      await deleteWarehouse(row.warehouseId, row.version);
      toast.success('仓库已删除');
      await fetchWarehouses();
    } catch (error) {
      toast.warning(getApiErrorMessage(error) || '仓库删除失败');
    }
  });
}

function handleBatchStatus(status: WarehouseStatus) {
  if (!selectedIds.value.size) return;
  const action = status === 1 ? '启用' : '停用';
  showConfirm(`批量${action}`, status === 0 ? warehouseDisableWarning : `确认启用已选的 ${selectedIds.value.size} 个仓库吗？`, action, status === 0 ? 'warning' : 'default', async () => {
    try {
      await batchUpdateWarehouseStatus({ warehouseIds: [...selectedIds.value], versionByWarehouseId: selectedVersionMap(), status });
      toast.success(`已批量${action}`);
      await fetchWarehouses();
    } catch (error) {
      toast.warning(getApiErrorMessage(error) || `批量${action}失败`);
    }
  });
}

function handleBatchDelete() {
  if (!selectedIds.value.size) return;
  showConfirm('批量删除', `确认删除已选的 ${selectedIds.value.size} 个仓库吗？存在库存余额、入库单、出库单或库存流水时整批操作将被拒绝。`, '删除', 'destructive', async () => {
    try {
      await batchDeleteWarehouses({ warehouseIds: [...selectedIds.value], versionByWarehouseId: selectedVersionMap() });
      toast.success('仓库已批量删除');
      await fetchWarehouses();
    } catch (error) {
      toast.warning(getApiErrorMessage(error) || '批量删除失败');
    }
  });
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">仓库管理</h1>
        <p class="page-description">维护采购、销售、退货和库存调整共同使用的仓库主数据</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页启用</span><strong class="mt-1 text-2xl">{{ enabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页停用</span><strong class="mt-1 text-2xl">{{ disabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">联系方式完整</span><strong class="mt-1 text-2xl">{{ contactReadyCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">联系方式待补</span><strong class="mt-1 text-2xl text-amber-700">{{ contactMissingCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--warehouses">
        <div class="space-y-1"><Label class="text-xs">仓库编码</Label><Input v-model="query.warehouseCode" placeholder="如 WH001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">仓库名称</Label><Input v-model="query.warehouseName" placeholder="请输入仓库名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">联系人</Label><Input v-model="query.contactName" placeholder="请输入联系人" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">联系电话</Label><Input v-model="query.contactPhone" placeholder="请输入联系电话" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusFilterOptions" placeholder="全部状态" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">仓库列表</strong><span class="text-xs" :class="selectedIds.size ? 'text-primary' : 'text-muted-foreground'">已选 {{ selectedIds.size }} 项</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增仓库</Button></span></TooltipTrigger><TooltipContent>创建仓库主数据</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(1)">批量启用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '启用已选仓库' : '请先选择仓库' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(0)">批量停用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '停用已选仓库' : '请先选择仓库' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchDelete">删除</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '删除无库存和流水引用的仓库' : '请先选择仓库' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载仓库列表</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1114px] table-fixed">
          <colgroup><col class="w-[44px]" /><col class="w-[100px]" /><col class="w-[150px]" /><col class="w-[100px]" /><col class="w-[130px]" /><col class="w-[220px]" /><col class="w-[70px]" /><col class="w-[130px]" /><col class="w-[170px]" /></colgroup>
          <TableHeader><TableRow>
            <TableHead><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead><TableHead>仓库编码</TableHead><TableHead>仓库名称</TableHead><TableHead>联系人</TableHead><TableHead>联系电话</TableHead><TableHead>仓库地址</TableHead><TableHead>状态</TableHead><TableHead>更新时间</TableHead><TableHead class="text-center">操作</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && warehouses.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="warehouses.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无数据</TableCell></TableRow>
            <TableRow v-for="row in warehouses" v-else :key="row.warehouseId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.warehouseId)" @update:model-value="toggleSelect(row.warehouseId, $event)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-1 text-xs font-medium">{{ row.warehouseCode }}</code></TableCell>
              <TableCell><div class="flex flex-col"><span class="font-medium">{{ row.warehouseName }}</span><span class="truncate text-xs text-muted-foreground">{{ row.remark || '暂无备注' }}</span></div></TableCell>
              <TableCell>{{ row.contactName || '未维护' }}</TableCell>
              <TableCell>{{ row.contactPhone || '未维护' }}</TableCell>
              <TableCell><span class="block truncate" :title="row.address">{{ row.address || '未维护' }}</span></TableCell>
              <TableCell><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updateTime }}</TableCell>
              <TableCell class="text-center"><div class="flex justify-center gap-1"><Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button><Button size="sm" variant="ghost" :class="row.status === 1 ? 'text-amber-700' : 'text-primary'" :disabled="actionSubmitting" @click="handleStatusChange(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button><Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="handleDelete(row)">删除</Button></div></TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination simple :current-count="warehouses.length" :has-next="hasNextPage" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[720px]">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增仓库' : '编辑仓库' }}</DialogTitle><DialogDescription>仓库会被采购、销售和库存业务共同引用，请准确维护基础信息。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="grid grid-cols-2 gap-4 py-2 max-sm:grid-cols-1">
            <div class="space-y-1"><Label>仓库编码</Label><Input data-warehouse-code :model-value="dialogMode === 'create' ? '保存后由系统生成' : form.warehouseCode" readonly class="bg-muted/55 text-muted-foreground" /><p class="text-xs text-muted-foreground">系统生成，创建后不可修改</p></div>
            <div class="space-y-1"><Label>仓库名称 <span class="text-destructive">*</span></Label><Input v-model="form.warehouseName" maxlength="100" placeholder="请输入仓库名称" :aria-invalid="Boolean(formErrors.warehouseName)" /><p v-if="formErrors.warehouseName" class="text-xs text-destructive">{{ formErrors.warehouseName }}</p></div>
            <div class="space-y-1"><Label>联系人</Label><Input v-model="form.contactName" maxlength="100" placeholder="请输入联系人" :aria-invalid="Boolean(formErrors.contactName)" /><p v-if="formErrors.contactName" class="text-xs text-destructive">{{ formErrors.contactName }}</p></div>
            <div class="space-y-1"><Label>联系电话</Label><Input v-model="form.contactPhone" maxlength="32" placeholder="请输入联系电话" :aria-invalid="Boolean(formErrors.contactPhone)" /><p v-if="formErrors.contactPhone" class="text-xs text-destructive">{{ formErrors.contactPhone }}</p></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>仓库地址</Label><Input v-model="form.address" maxlength="255" placeholder="请输入仓库地址" :aria-invalid="Boolean(formErrors.address)" /><p v-if="formErrors.address" class="text-xs text-destructive">{{ formErrors.address }}</p></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>启用状态 <span class="text-destructive">*</span></Label><RadioGroup :model-value="String(form.status)" class="flex gap-5" @update:model-value="form.status = Number($event) as WarehouseStatus"><div class="flex items-center gap-2"><RadioGroupItem id="warehouse-status-1" value="1" /><Label for="warehouse-status-1" class="cursor-pointer font-normal">启用</Label></div><div class="flex items-center gap-2"><RadioGroupItem id="warehouse-status-0" value="0" /><Label for="warehouse-status-0" class="cursor-pointer font-normal">停用</Label></div></RadioGroup></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>备注</Label><Textarea v-model="form.remark" maxlength="500" rows="3" placeholder="补充仓库用途、收发货时间或管理说明" :aria-invalid="Boolean(formErrors.remark)" /><div class="flex justify-between text-xs"><span :class="formErrors.remark ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors.remark || '选填，最多 500 个字符' }}</span><span class="text-muted-foreground">{{ form.remark.length }}/500</span></div></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" :variant="confirmState.variant" :loading="actionSubmitting || formSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>

<style scoped>
.filter-grid--warehouses {
  grid-template-columns: repeat(5, minmax(0, 1fr)) auto;
}

@media (max-width: 1279px) {
  .filter-grid--warehouses {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-grid--warehouses .filter-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}

@media (max-width: 640px) {
  .filter-grid--warehouses {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
