<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Textarea } from '@/components/ui/textarea';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import { permissionActionOptions, permissionModuleOptions } from '../catalog';
import {
  batchDeleteSystemPermissions,
  batchUpdateSystemPermissionStatus,
  createSystemPermission,
  deleteSystemPermission,
  listSystemPermissions,
  updateSystemPermission,
  updateSystemPermissionStatus,
} from '../api';
import type {
  PermissionAction,
  PermissionStatus,
  SystemPermissionFormPayload,
  SystemPermissionListItem,
  SystemPermissionQuery,
} from '../types';

const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];
const moduleFilterOptions = [{ value: 'all', label: '全部模块' }, ...permissionModuleOptions];
const actionFilterOptions = [{ value: 'all', label: '全部类型' }, ...permissionActionOptions];
const permissionCodePattern = /^[a-z][a-z0-9]*(?::[a-z][a-z0-9]*){1,3}$/;

const permissions = ref<SystemPermissionListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingPermissionId = ref('');
const editingOriginalStatus = ref<PermissionStatus>(1);
const editingRoleCount = ref(0);
let fetchSequence = 0;

const query = reactive<SystemPermissionQuery>({
  permissionCode: '', permissionName: '', moduleCode: 'all', actionType: 'all', status: 'all', pageNum: 1, pageSize: 10,
});
const form = reactive<SystemPermissionFormPayload>({
  permissionCode: '', permissionName: '', moduleCode: '', actionType: 'query',
  status: 1, sortOrder: 0, description: '',
});
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false, title: '', description: '', confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const enabledCount = computed(() => permissions.value.filter(item => item.status === 1).length);
const moduleCount = computed(() => new Set(permissions.value.map(item => item.moduleCode)).size);
const boundRoleCount = computed(() => permissions.value.reduce((sum, item) => sum + item.roleCount, 0));
const allSelected = computed(() => permissions.value.length > 0 && permissions.value.every(item => selectedIds.value.has(item.permissionId)));
const selectedRows = computed(() => permissions.value.filter(item => selectedIds.value.has(item.permissionId)));
const queryBusy = computed(() => queryPending.value || loading.value);

async function fetchPermissions() {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listSystemPermissions({ ...query });
    if (sequence !== fetchSequence) return;
    permissions.value = result.records;
    total.value = result.total;
    selectedIds.value = new Set();
  } catch {
  } finally {
    if (sequence === fetchSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

onMounted(fetchPermissions);

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchPermissions();
}, 250);

const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchPermissions();
}, 180);

function handleSearch() {
  if (queryBusy.value) return;
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

function handleReset() {
  if (loading.value) return;
  query.permissionCode = '';
  query.permissionName = '';
  query.moduleCode = 'all';
  query.actionType = 'all';
  query.status = 'all';
  query.pageNum = 1;
  fetchPermissions();
}

const refreshList = useListRefresh(queryBusy, queryPending, fetchPermissions);

function formatTableTime(value: string) {
  return value.slice(5, 16);
}

function getActionLabel(action: PermissionAction) {
  return permissionActionOptions.find(item => item.value === action)?.label || action;
}

function getModuleName(moduleCode: string) {
  return permissionModuleOptions.find(item => item.value === moduleCode)?.label || moduleCode;
}

function toggleSelectAll(value: boolean | 'indeterminate') {
  selectedIds.value = value === true ? new Set(permissions.value.map(item => item.permissionId)) : new Set();
}

function toggleSelect(permissionId: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(permissionId) : next.delete(permissionId);
  selectedIds.value = next;
}

function resetForm() {
  form.permissionCode = '';
  form.permissionName = '';
  form.moduleCode = '';
  form.actionType = 'query';
  form.status = 1;
  form.sortOrder = 0;
  form.description = '';
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingPermissionId.value = '';
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: SystemPermissionListItem) {
  dialogMode.value = 'edit';
  editingPermissionId.value = row.permissionId;
  editingOriginalStatus.value = row.status;
  editingRoleCount.value = row.roleCount;
  form.permissionCode = row.permissionCode;
  form.permissionName = row.permissionName;
  form.moduleCode = row.moduleCode;
  form.actionType = row.actionType;
  form.status = row.status;
  form.sortOrder = row.sortOrder;
  form.description = row.description;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  const code = form.permissionCode.trim();
  const name = form.permissionName.trim();
  if (!code) formErrors.permissionCode = '请输入权限码';
  else if (!permissionCodePattern.test(code)) formErrors.permissionCode = '使用小写字母、数字和冒号分段，例如 system:user:query';
  else if (code.length > 100) formErrors.permissionCode = '权限码不能超过 100 个字符';
  if (!name) formErrors.permissionName = '请输入权限名称';
  else if (name.length > 100) formErrors.permissionName = '权限名称不能超过 100 个字符';
  if (!form.moduleCode) formErrors.moduleCode = '请选择所属模块';
  if (!form.actionType) formErrors.actionType = '请选择操作类型';
  if (!Number.isInteger(Number(form.sortOrder)) || Number(form.sortOrder) < 0 || Number(form.sortOrder) > 9999) {
    formErrors.sortOrder = '排序值须为 0-9999 的整数';
  }
  if (form.description.trim().length > 500) formErrors.description = '说明不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const payload: SystemPermissionFormPayload = {
    permissionCode: form.permissionCode.trim(),
    permissionName: form.permissionName.trim(),
    moduleCode: form.moduleCode,
    actionType: form.actionType,
    status: form.status,
    sortOrder: Number(form.sortOrder),
    description: form.description.trim(),
  };
  if (dialogMode.value === 'edit' && editingOriginalStatus.value === 1 && payload.status === 0) {
    showConfirm(
      '确认停用权限码',
      buildPermissionDisableWarning(editingRoleCount.value > 0),
      '确认停用',
      'warning',
      () => persistForm(payload),
    );
    return;
  }
  await persistForm(payload);
}

async function persistForm(payload: SystemPermissionFormPayload) {
  if (formSubmitting.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createSystemPermission(payload);
      toast.success('权限码已创建');
    } else {
      await updateSystemPermission(editingPermissionId.value, {
        permissionName: payload.permissionName,
        moduleCode: payload.moduleCode,
        actionType: payload.actionType,
        status: payload.status,
        sortOrder: payload.sortOrder,
        description: payload.description,
      });
      toast.success('权限码已更新');
    }
    dialogVisible.value = false;
    fetchPermissions();
  } catch (error) {
    if (getApiErrorMessage(error) === '权限码已存在') formErrors.permissionCode = '权限码已存在';
  } finally {
    formSubmitting.value = false;
  }
}

function showConfirm(
  title: string,
  description: string,
  confirmText: string,
  variant: 'default' | 'destructive' | 'warning',
  onConfirm: () => void | Promise<void>,
) {
  confirmState.title = title;
  confirmState.description = description;
  confirmState.confirmText = confirmText;
  confirmState.variant = variant;
  confirmState.onConfirm = onConfirm;
  confirmState.open = true;
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

function handleStatusChange(row: SystemPermissionListItem, status: PermissionStatus) {
  const action = status === 1 ? '启用' : '停用';
  const description = status === 0
    ? buildPermissionDisableWarning(row.roleCount > 0)
    : `确认启用「${row.permissionName}」吗？`;
  showConfirm(`${action}权限码`, description, action, status === 1 ? 'default' : 'warning', async () => {
    try {
      await updateSystemPermissionStatus(row.permissionId, status);
      toast.success(`权限码已${action}`);
      fetchPermissions();
    } catch {}
  });
}

function handleDelete(row: SystemPermissionListItem) {
  if (row.roleCount > 0) {
    toast.warning(`该权限码已被 ${row.roleCount} 个角色引用，请先解除绑定`);
    return;
  }
  showConfirm('删除权限码', `确认删除「${row.permissionName}」吗？删除后无法恢复。`, '删除', 'destructive', async () => {
    try {
      await deleteSystemPermission(row.permissionId);
      toast.success('权限码已删除');
      fetchPermissions();
    } catch {}
  });
}

function handleBatchStatus(status: PermissionStatus) {
  if (actionSubmitting.value || selectedIds.value.size === 0) return;
  const action = status === 1 ? '启用' : '停用';
  const description = status === 0
    ? buildPermissionDisableWarning(selectedRows.value.some(item => item.roleCount > 0))
    : `确认启用已选的 ${selectedIds.value.size} 个权限码吗？`;
  showConfirm(`批量${action}`, description, action, status === 1 ? 'default' : 'warning', async () => {
    try {
      await batchUpdateSystemPermissionStatus({ permissionIds: [...selectedIds.value], status });
      toast.success(`已批量${action}`);
      fetchPermissions();
    } catch {}
  });
}

function buildPermissionDisableWarning(hasRoleBindings: boolean) {
  if (hasRoleBindings) {
    return '该权限码已与角色关联。停用后，相关角色将不再授予此权限，绑定这些角色的用户也将无法执行对应操作。系统会刷新受影响用户的权限会话。是否继续？';
  }
  return '停用后，该权限码将不再进入用户的有效权限集合，也不能继续分配给角色。是否继续？';
}

function handleBatchDelete() {
  if (actionSubmitting.value || selectedIds.value.size === 0) return;
  const bound = selectedRows.value.filter(item => item.roleCount > 0);
  if (bound.length > 0) {
    toast.warning(`已选数据中有 ${bound.length} 个权限码被角色引用，无法删除`);
    return;
  }
  showConfirm('批量删除', `确认删除已选的 ${selectedIds.value.size} 个权限码吗？`, '删除', 'destructive', async () => {
    try {
      await batchDeleteSystemPermissions({ permissionIds: [...selectedIds.value] });
      toast.success('权限码已批量删除');
      fetchPermissions();
    } catch {}
  });
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">权限码配置</h1>
        <p class="page-description">维护系统能力标识、所属模块与角色引用状态</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">权限码总数</span><strong class="mt-1 text-2xl">{{ total }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">当前页启用</span><strong class="mt-1 text-2xl">{{ enabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">当前页模块</span><strong class="mt-1 text-2xl">{{ moduleCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">当前页角色引用</span><strong class="mt-1 text-2xl">{{ boundRoleCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--permissions">
        <div class="space-y-1">
          <Label class="text-xs">权限码</Label>
          <Input v-model="query.permissionCode" placeholder="如 product:query" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">权限名称</Label>
          <Input v-model="query.permissionName" placeholder="请输入权限名称" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">所属模块</Label>
          <AnchoredSelect v-model="query.moduleCode" :options="moduleFilterOptions" placeholder="全部模块" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">操作类型</Label>
          <AnchoredSelect v-model="query.actionType" :options="actionFilterOptions" placeholder="全部类型" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">状态</Label>
          <AnchoredSelect v-model="query.status" :options="statusFilterOptions" placeholder="全部状态" />
        </div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">权限码列表</strong>
          <span class="text-xs" :class="selectedIds.size ? 'text-primary' : 'text-muted-foreground'">已选 {{ selectedIds.size }} 项</span>
        </div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增权限码</Button></span></TooltipTrigger><TooltipContent>创建新的系统权限标识</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(1)">批量启用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '启用已选权限码' : '请先选择权限码' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(0)">批量停用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '停用已选权限码' : '请先选择权限码' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchDelete">删除</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '删除未被角色引用的权限码' : '请先选择权限码' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载权限码列表</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1154px] table-fixed">
          <colgroup>
            <col class="w-[44px]" />
            <col class="w-[190px]" />
            <col class="w-[220px]" />
            <col class="w-[160px]" />
            <col class="w-[90px]" />
            <col class="w-[80px]" />
            <col class="w-[70px]" />
            <col class="w-[110px]" />
            <col class="w-[190px]" />
          </colgroup>
          <TableHeader><TableRow>
            <TableHead class="w-[44px]"><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead>
            <TableHead>权限码</TableHead><TableHead>权限名称</TableHead><TableHead class="text-center">模块 / 类型</TableHead>
            <TableHead class="text-center">角色引用</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-center">排序</TableHead>
            <TableHead>更新时间</TableHead><TableHead class="w-[190px] text-center">操作</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="permissions.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无数据</TableCell></TableRow>
            <TableRow v-for="row in permissions" v-else :key="row.permissionId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.permissionId)" @update:model-value="toggleSelect(row.permissionId, $event)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-1 text-xs font-medium text-foreground">{{ row.permissionCode }}</code></TableCell>
              <TableCell><div class="flex max-w-[220px] flex-col"><span>{{ row.permissionName }}</span><span class="truncate text-xs text-muted-foreground">{{ row.description || '暂无说明' }}</span></div></TableCell>
              <TableCell class="text-center"><div class="flex items-center justify-center gap-1"><Badge variant="outline">{{ getModuleName(row.moduleCode) }}</Badge><Badge variant="secondary">{{ getActionLabel(row.actionType) }}</Badge></div></TableCell>
              <TableCell class="text-center"><span :class="row.roleCount ? 'font-medium text-primary' : 'text-muted-foreground'">{{ row.roleCount }}</span></TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-center">{{ row.sortOrder }}</TableCell>
              <TableCell class="text-xs">{{ formatTableTime(row.updatedAt) }}</TableCell>
              <TableCell class="text-center"><div class="flex items-center justify-center gap-1">
                <Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button>
                <Button size="sm" variant="ghost" :class="row.status === 1 ? 'text-amber-700' : 'text-primary'" :disabled="actionSubmitting" @click="handleStatusChange(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button>
                <Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="handleDelete(row)">删除</Button>
              </div></TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize"
        :loading="queryBusy"
        @update:page-num="handlePageChange"
        @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="sm:max-w-[620px]">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增权限码' : '编辑权限码' }}</DialogTitle><DialogDescription>权限码用于后端鉴权和角色授权，保存后请同步业务接口使用。</DialogDescription></DialogHeader>
        <div class="grid grid-cols-2 gap-4 py-2 max-sm:grid-cols-1">
          <div class="col-span-2 space-y-1 max-sm:col-span-1">
            <Label>权限码 <span class="text-destructive">*</span></Label>
            <Input v-model="form.permissionCode" maxlength="100" placeholder="例如 system:user:query" :disabled="dialogMode === 'edit'" :aria-invalid="Boolean(formErrors.permissionCode)" />
            <p class="text-xs" :class="formErrors.permissionCode ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors.permissionCode || (dialogMode === 'edit' ? '权限码是鉴权标识，创建后不可修改。' : '仅支持小写字母、数字和冒号分段。') }}</p>
          </div>
          <div class="space-y-1"><Label>权限名称 <span class="text-destructive">*</span></Label><Input v-model="form.permissionName" maxlength="100" placeholder="请输入权限名称" :aria-invalid="Boolean(formErrors.permissionName)" /><p v-if="formErrors.permissionName" class="text-xs text-destructive">{{ formErrors.permissionName }}</p></div>
          <div class="space-y-1"><Label>所属模块 <span class="text-destructive">*</span></Label><Select v-model="form.moduleCode"><SelectTrigger :aria-invalid="Boolean(formErrors.moduleCode)"><SelectValue placeholder="请选择所属模块" /></SelectTrigger><SelectContent position="popper"><SelectItem v-for="item in permissionModuleOptions" :key="item.value" :value="item.value">{{ item.label }}</SelectItem></SelectContent></Select><p v-if="formErrors.moduleCode" class="text-xs text-destructive">{{ formErrors.moduleCode }}</p></div>
          <div class="space-y-1"><Label>操作类型 <span class="text-destructive">*</span></Label><Select v-model="form.actionType"><SelectTrigger :aria-invalid="Boolean(formErrors.actionType)"><SelectValue placeholder="请选择操作类型" /></SelectTrigger><SelectContent position="popper"><SelectItem v-for="item in permissionActionOptions" :key="item.value" :value="item.value">{{ item.label }}</SelectItem></SelectContent></Select><p v-if="formErrors.actionType" class="text-xs text-destructive">{{ formErrors.actionType }}</p></div>
          <div class="space-y-1"><Label>排序值</Label><Input v-model.number="form.sortOrder" type="number" min="0" max="9999" step="1" :aria-invalid="Boolean(formErrors.sortOrder)" /><p v-if="formErrors.sortOrder" class="text-xs text-destructive">{{ formErrors.sortOrder }}</p></div>
          <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>启用状态 <span class="text-destructive">*</span></Label><RadioGroup :model-value="String(form.status)" class="flex gap-5" @update:model-value="form.status = Number($event) as PermissionStatus"><div class="flex items-center gap-2"><RadioGroupItem id="permission-status-1" value="1" /><Label for="permission-status-1" class="cursor-pointer font-normal">启用</Label></div><div class="flex items-center gap-2"><RadioGroupItem id="permission-status-0" value="0" /><Label for="permission-status-0" class="cursor-pointer font-normal">停用</Label></div></RadioGroup></div>
          <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>权限说明</Label><Textarea v-model="form.description" maxlength="500" rows="3" placeholder="说明该权限允许执行的业务操作" :aria-invalid="Boolean(formErrors.description)" /><div class="flex justify-between text-xs"><span v-if="formErrors.description" class="text-destructive">{{ formErrors.description }}</span><span v-else class="text-muted-foreground">选填，便于授权人员理解权限边界</span><span class="text-muted-foreground">{{ form.description.length }}/500</span></div></div>
        </div>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description"
      :confirm-text="confirmState.confirmText" :variant="confirmState.variant" :loading="actionSubmitting"
      @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
