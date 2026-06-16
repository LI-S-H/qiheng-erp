<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { getApiErrorMessage } from '@/api/http';
import { toast } from 'vue-sonner';
import { Eye } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Textarea } from '@/components/ui/textarea';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogScrollArea,
  DialogTitle,
} from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { Alert, AlertDescription } from '@/components/ui/alert';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import type { RoleStatus, SystemRoleFormPayload, SystemRoleListItem, SystemRoleQuery } from '../types';
import { listSystemRoles, createSystemRole, updateSystemRole, updateSystemRolePermissions, deleteSystemRole, batchUpdateSystemRoleStatus, batchDeleteSystemRoles } from '../api';
import { listPermissionOptions } from '../../permissions/api';
import type { PermissionOptionGroup } from '../../permissions/types';

type PermissionGroup = PermissionOptionGroup;

const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

const permissionGroups = ref<PermissionGroup[]>([]);
const allPermissionCodes = computed(() => permissionGroups.value.flatMap(group => group.codes));

const roles = ref<SystemRoleListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const permissionSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const roleDialogVisible = ref(false);
const permissionPreviewVisible = ref(false);
const permissionDialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingRoleId = ref('');
const permissionPreviewRole = ref<SystemRoleListItem | null>(null);
const permissionEditingRole = ref<SystemRoleListItem | null>(null);

const query = reactive<SystemRoleQuery>({
  roleCode: '', roleName: '', status: 'all', pageNum: 1, pageSize: 10,
});

const roleForm = reactive<SystemRoleFormPayload>({
  roleCode: '', roleName: '', permissionCodes: [], status: 1, remark: '',
});

const permissionForm = reactive({ permissionCodes: [] as string[] });

const formErrors = reactive<Record<string, string>>({});
const permFormErrors = reactive<Record<string, string>>({});
let fetchSequence = 0;

const confirmState = reactive({
  open: false, title: '', description: '', confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const enabledCount = computed(() => roles.value.filter(r => r.status === 1).length);
const permissionTotal = computed(() => new Set(roles.value.flatMap(r => r.permissionCodes)).size);
const boundUserTotal = computed(() => roles.value.reduce((t, r) => t + r.userCount, 0));

const allSelected = computed(() => roles.value.length > 0 && roles.value.every(r => selectedIds.value.has(r.roleId)));
const selectedRows = computed(() => roles.value.filter(r => selectedIds.value.has(r.roleId)));
const queryBusy = computed(() => queryPending.value || loading.value);

async function fetchRoles() {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listSystemRoles({ ...query });
    if (sequence !== fetchSequence) return;
    roles.value = result.records;
    total.value = result.total;
  } catch {
    // http.ts 统一处理接口错误提示。
  } finally {
    if (sequence === fetchSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

async function fetchPermissionOptions() {
  try {
    permissionGroups.value = await listPermissionOptions();
  } catch {
    permissionGroups.value = [];
  }
}

onMounted(() => { fetchRoles(); fetchPermissionOptions(); });

function formatTableTime(value: string) { return value.slice(5, 16); }

function getPermissionLabel(code: string) {
  if (code === '*') return '全部权限';
  return allPermissionCodes.value.find(item => item.code === code)?.label || code;
}

function hasAllPermissions(row: SystemRoleListItem | null) {
  return Boolean(row?.permissionCodes.includes('*'));
}

function getPermissionSummary(row: SystemRoleListItem) {
  if (hasAllPermissions(row)) return '全部权限';
  return `${row.permissionCodes.length} 项权限码`;
}

function getPermissionGroupsForRole(row: SystemRoleListItem | null) {
  if (!row) return [];
  if (hasAllPermissions(row)) return permissionGroups.value;
  const selectedCodes = new Set(row.permissionCodes);
  return permissionGroups.value
    .map(g => ({ group: g.group, codes: g.codes.filter(item => selectedCodes.has(item.code)) }))
    .filter(g => g.codes.length > 0);
}

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchRoles();
}, 250);

const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchRoles();
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
  if (queryBusy.value) return;
  query.roleCode = ''; query.roleName = ''; query.status = 'all'; query.pageNum = 1;
  queryPending.value = true;
  debouncedSearch();
}

const refreshList = useListRefresh(queryBusy, queryPending, fetchRoles);

function toggleSelectAll() {
  if (allSelected.value) {
    roles.value.forEach(r => selectedIds.value.delete(r.roleId));
  } else {
    roles.value.forEach(r => selectedIds.value.add(r.roleId));
  }
  selectedIds.value = new Set(selectedIds.value);
}

function toggleSelectRow(roleId: string) {
  const next = new Set(selectedIds.value);
  if (next.has(roleId)) next.delete(roleId); else next.add(roleId);
  selectedIds.value = next;
}

function ensureSelectedRows(actionName: string) {
  if (selectedIds.value.size > 0) return true;
  toast.warning(`请先选择需要${actionName}的角色`);
  return false;
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => Promise<void> | void) {
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

async function handleBatchStatus(status: RoleStatus) {
  const action = status === 1 ? '启用' : '停用';
  if (!ensureSelectedRows(action)) return;
  showConfirm(
    `批量${action}`,
    `确认${action}已选择的 ${selectedIds.value.size} 个角色吗`,
    action,
    status === 1 ? 'default' : 'warning',
    async () => {
      try {
        await batchUpdateSystemRoleStatus({ roleIds: [...selectedIds.value], status });
        selectedIds.value = new Set();
        toast.success(`已批量${action}`);
        fetchRoles();
      } catch {}
    },
  );
}

function handleBatchDelete() {
  if (!ensureSelectedRows('删除')) return;
  const inUseRoles = selectedRows.value.filter(r => r.userCount > 0);
  if (inUseRoles.length > 0) {
    toast.warning('存在已绑定用户的角色，请先解绑后再删除');
    return;
  }
  showConfirm(
    '批量删除角色',
    `确认删除已选择的 ${selectedIds.value.size} 个角色吗`,
    '删除',
    'destructive',
    async () => {
      try {
        await batchDeleteSystemRoles({ roleIds: [...selectedIds.value] });
        selectedIds.value = new Set();
        toast.success('已批量删除角色');
        fetchRoles();
      } catch {}
    },
  );
}

function resetRoleForm() {
  editingRoleId.value = '';
  roleForm.roleCode = ''; roleForm.roleName = '';
  roleForm.permissionCodes = []; roleForm.status = 1; roleForm.remark = '';
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
}

function validateRoleForm(): boolean {
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  const roleCode = roleForm.roleCode.trim();
  const roleName = roleForm.roleName.trim();
  const remark = roleForm.remark.trim();
  if (!roleCode) formErrors.roleCode = '请输入角色编码';
  else if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(roleCode)) formErrors.roleCode = '角色编码须以大写字母开头，仅可使用大写字母、数字和下划线，长度 3-64 位';
  if (!roleName) formErrors.roleName = '请输入角色名称';
  else if (roleName.length > 100) formErrors.roleName = '角色名称不能超过 100 个字符';
  if (!roleForm.permissionCodes.length) formErrors.permissionCodes = '请选择权限码';
  if (remark.length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetRoleForm();
  roleDialogVisible.value = true;
}

function openEditDialog(row: SystemRoleListItem) {
  dialogMode.value = 'edit';
  editingRoleId.value = row.roleId;
  roleForm.roleCode = row.roleCode; roleForm.roleName = row.roleName;
  roleForm.permissionCodes = [...row.permissionCodes];
  roleForm.status = row.status; roleForm.remark = row.remark;
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  roleDialogVisible.value = true;
}

async function submitRoleForm() {
  if (formSubmitting.value || !validateRoleForm()) return;
  formSubmitting.value = true;
  const payload: SystemRoleFormPayload = {
    ...roleForm,
    roleCode: roleForm.roleCode.trim(),
    roleName: roleForm.roleName.trim(),
    permissionCodes: [...new Set(roleForm.permissionCodes)],
    remark: roleForm.remark.trim(),
  };
  try {
    if (dialogMode.value === 'create') {
      await createSystemRole(payload);
      toast.success('角色已创建');
    } else {
      await updateSystemRole(editingRoleId.value, payload);
      toast.success('角色已更新');
    }
    roleDialogVisible.value = false;
    fetchRoles();
  } catch (error) {
    const message = getApiErrorMessage(error);
    if (message === '角色编码已存在') {
      formErrors.roleCode = message;
    }
  } finally {
    formSubmitting.value = false;
  }
}

function openPermissionDialog(row: SystemRoleListItem) {
  permissionEditingRole.value = row;
  permissionForm.permissionCodes = [...row.permissionCodes];
  Object.keys(permFormErrors).forEach(k => delete permFormErrors[k]);
  permissionDialogVisible.value = true;
}

function openPermissionPreview(row: SystemRoleListItem) {
  permissionPreviewRole.value = row;
  permissionPreviewVisible.value = true;
}

async function submitPermissionForm() {
  Object.keys(permFormErrors).forEach(k => delete permFormErrors[k]);
  if (!permissionForm.permissionCodes.length) { permFormErrors.permissionCodes = '请选择权限码'; return; }
  if (permissionSubmitting.value) return;
  const current = permissionEditingRole.value;
  if (!current) return;
  permissionSubmitting.value = true;
  try {
    await updateSystemRolePermissions(current.roleId, { permissionCodes: [...new Set(permissionForm.permissionCodes)] });
    permissionDialogVisible.value = false;
    toast.success('权限码已更新');
    fetchRoles();
  } catch {} finally {
    permissionSubmitting.value = false;
  }
}

// Permission checkbox helpers for create/edit dialog
function isRolePermChecked(code: string): boolean {
  if (code === '*') return roleForm.permissionCodes.includes('*');
  return roleForm.permissionCodes.includes(code);
}

function toggleRolePerm(code: string, checked: boolean) {
  if (code === '*') {
    if (checked) {
      roleForm.permissionCodes = ['*'];
    } else {
      roleForm.permissionCodes = [];
    }
  } else {
    // Remove wildcard if selecting individual codes
    const idx = roleForm.permissionCodes.indexOf('*');
    if (idx !== -1) roleForm.permissionCodes.splice(idx, 1);
    if (checked) {
      if (!roleForm.permissionCodes.includes(code)) roleForm.permissionCodes.push(code);
    } else {
      const ci = roleForm.permissionCodes.indexOf(code);
      if (ci !== -1) roleForm.permissionCodes.splice(ci, 1);
    }
  }
}

// Permission checkbox helpers for permission config dialog
function isPermFormChecked(code: string): boolean {
  if (code === '*') return permissionForm.permissionCodes.includes('*');
  return permissionForm.permissionCodes.includes(code);
}

function togglePermForm(code: string, checked: boolean) {
  if (code === '*') {
    if (checked) {
      permissionForm.permissionCodes = ['*'];
    } else {
      permissionForm.permissionCodes = [];
    }
  } else {
    const idx = permissionForm.permissionCodes.indexOf('*');
    if (idx !== -1) permissionForm.permissionCodes.splice(idx, 1);
    if (checked) {
      if (!permissionForm.permissionCodes.includes(code)) permissionForm.permissionCodes.push(code);
    } else {
      const ci = permissionForm.permissionCodes.indexOf(code);
      if (ci !== -1) permissionForm.permissionCodes.splice(ci, 1);
    }
  }
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">角色管理</h1>
        <p class="page-description">维护角色编码、权限码集合、启用状态和使用情况</p>
      </div>
    </div>

    <!-- Metrics -->
    <div class="summary-strip">
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">角色总数</span>
        <strong class="text-2xl mt-1">{{ total }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">启用角色</span>
        <strong class="text-2xl mt-1">{{ enabledCount }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">权限码覆盖</span>
        <strong class="text-2xl mt-1">{{ permissionTotal }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">绑定用户数</span>
        <strong class="text-2xl mt-1">{{ boundUserTotal }}</strong>
      </div>
    </div>

    <!-- Filter -->
    <div class="filter-panel">
      <div class="filter-grid filter-grid--roles">
        <div class="space-y-1">
          <Label class="text-xs">角色编码</Label>
          <Input v-model="query.roleCode" placeholder="如 SUPER_ADMIN" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">角色名称</Label>
          <Input v-model="query.roleName" placeholder="如 超级管理员" @keyup.enter="handleSearch" />
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

    <!-- Table -->
    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <!-- Toolbar -->
      <div class="table-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">角色列表</strong>
          <span class="text-xs" :class="selectedIds.size > 0 ? 'text-primary' : 'text-muted-foreground'">
            已选 {{ selectedIds.size }} 项
          </span>
        </div>
        <div class="table-toolbar__actions">
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增角色</Button></span></TooltipTrigger>
            <TooltipContent>创建新的系统角色</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchStatus(1)">批量启用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择角色' : '启用已选角色' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchStatus(0)">批量停用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择角色' : '停用已选角色' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchDelete">删除</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择角色' : '删除已选角色' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger>
            <TooltipContent>重新加载角色列表</TooltipContent>
          </Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1216px] table-fixed">
          <colgroup>
            <col class="w-[44px]" />
            <col class="w-[180px]" />
            <col class="w-[220px]" />
            <col class="w-[120px]" />
            <col class="w-[96px]" />
            <col class="w-[220px]" />
            <col class="w-[160px]" />
            <col class="w-[176px]" />
          </colgroup>
          <TableHeader>
            <TableRow>
              <TableHead class="w-[44px]">
                <Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" />
              </TableHead>
              <TableHead>角色</TableHead>
              <TableHead class="text-center">权限码</TableHead>
              <TableHead class="text-center w-[120px]">绑定用户数</TableHead>
              <TableHead class="text-center w-[96px]">状态</TableHead>
              <TableHead>备注</TableHead>
              <TableHead>时间</TableHead>
              <TableHead class="text-center w-[176px]">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-if="loading">
              <TableCell colspan="8" class="text-center text-muted-foreground py-8">加载中...</TableCell>
            </TableRow>
            <TableRow v-else-if="roles.length === 0">
              <TableCell colspan="8" class="text-center text-muted-foreground py-8">暂无数据</TableCell>
            </TableRow>
            <TableRow v-for="row in roles" :key="row.roleId" :class="{ 'bg-muted/50': selectedIds.has(row.roleId) }">
              <TableCell>
                <Checkbox :model-value="selectedIds.has(row.roleId)" @update:model-value="toggleSelectRow(row.roleId)" />
              </TableCell>
              <TableCell>
                <div class="flex flex-col">
                  <strong class="text-sm">{{ row.roleName }}</strong>
                  <span class="text-xs text-muted-foreground">{{ row.roleCode }}</span>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="flex items-center justify-center gap-2">
                  <Badge variant="outline" class="min-w-[102px] justify-center border-blue-200 bg-blue-50 font-semibold text-blue-700">
                    {{ getPermissionSummary(row) }}
                  </Badge>
                  <Button variant="link" size="sm" class="h-7 p-0" :disabled="actionSubmitting" @click="openPermissionPreview(row)">
                    <Eye class="mr-1 h-3.5 w-3.5" />查看明细
                  </Button>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <Badge
                  variant="outline"
                  class="min-w-[38px] justify-center font-semibold"
                  :class="row.userCount === 0 ? 'text-muted-foreground' : 'border-blue-200 bg-blue-50 text-blue-700'"
                >
                  {{ row.userCount }}
                </Badge>
              </TableCell>
              <TableCell class="text-center">
                <Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </Badge>
              </TableCell>
              <TableCell>
                <span class="text-xs text-muted-foreground">{{ row.remark || '未填写' }}</span>
              </TableCell>
              <TableCell>
                <div class="flex flex-col text-xs">
                  <span>创建 {{ formatTableTime(row.createTime) }}</span>
                  <span class="text-muted-foreground">更新 {{ formatTableTime(row.updateTime) }}</span>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="flex items-center justify-center gap-1">
                  <Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button>
                  <Button size="sm" variant="ghost" class="text-primary" :disabled="actionSubmitting" @click="openPermissionDialog(row)">权限配置</Button>
                </div>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination
        :total="total"
        :page-num="query.pageNum"
        :page-size="query.pageSize"
        :loading="queryBusy"
        @update:page-num="handlePageChange"
        @update:page-size="handlePageSizeChange"
      />
    </div>

    <!-- Create/Edit Role Dialog -->
    <Dialog v-model:open="roleDialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[760px] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[620px]">
        <DialogHeader>
          <DialogTitle>{{ dialogMode === 'create' ? '新增角色' : '编辑角色' }}</DialogTitle>
          <DialogDescription>填写角色信息和权限码</DialogDescription>
        </DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 py-2">
            <div class="space-y-1">
              <Label>角色编码 <span class="text-destructive">*</span></Label>
              <Input v-model="roleForm.roleCode" :disabled="dialogMode === 'edit'" maxlength="64" />
              <p v-if="formErrors.roleCode" class="text-xs text-destructive">{{ formErrors.roleCode }}</p>
            </div>
            <div class="space-y-1">
              <Label>角色名称 <span class="text-destructive">*</span></Label>
              <Input v-model="roleForm.roleName" maxlength="100" />
              <p v-if="formErrors.roleName" class="text-xs text-destructive">{{ formErrors.roleName }}</p>
            </div>
            <div class="space-y-1">
              <Label>启用状态 <span class="text-destructive">*</span></Label>
              <RadioGroup :model-value="String(roleForm.status)" @update:model-value="roleForm.status = Number($event) as 0 | 1" class="flex gap-4">
                <div class="flex items-center gap-2">
                  <RadioGroupItem value="1" id="role-status-1" />
                  <Label for="role-status-1" class="cursor-pointer">启用</Label>
                </div>
                <div class="flex items-center gap-2">
                  <RadioGroupItem value="0" id="role-status-0" />
                  <Label for="role-status-0" class="cursor-pointer">停用</Label>
                </div>
              </RadioGroup>
            </div>
            <div class="space-y-1">
              <Label>权限码 <span class="text-destructive">*</span></Label>
              <div class="w-full p-3 bg-muted/50 border border-border rounded-lg space-y-3">
                <!-- Wildcard -->
                <div class="flex items-center gap-2">
                  <Checkbox id="role-perm-all" :model-value="isRolePermChecked('*')" @update:model-value="toggleRolePerm('*', $event === true)" />
                  <Label for="role-perm-all" class="cursor-pointer font-semibold">全部权限</Label>
                </div>
                <!-- Groups -->
                <div v-for="group in permissionGroups" :key="group.group" class="pt-3 border-t border-border">
                  <div class="text-xs font-bold text-foreground mb-2">{{ group.group }}</div>
                  <div class="grid grid-cols-3 gap-1">
                    <div v-for="item in group.codes" :key="item.code" class="flex items-center gap-2">
                      <Checkbox :id="'role-perm-' + item.code" :model-value="isRolePermChecked(item.code)" @update:model-value="toggleRolePerm(item.code, $event === true)" />
                      <Label :for="'role-perm-' + item.code" class="cursor-pointer text-xs">{{ item.label }}</Label>
                    </div>
                  </div>
                </div>
              </div>
              <p v-if="formErrors.permissionCodes" class="text-xs text-destructive">{{ formErrors.permissionCodes }}</p>
            </div>
            <div class="space-y-1">
              <Label>备注</Label>
              <Textarea v-model="roleForm.remark" :rows="3" maxlength="500" placeholder="请输入备注" />
              <p v-if="formErrors.remark" class="text-xs text-destructive">{{ formErrors.remark }}</p>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter>
          <Button variant="outline" :disabled="formSubmitting" @click="roleDialogVisible = false">取消</Button>
          <Button :disabled="formSubmitting" @click="submitRoleForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- Permission Preview Dialog -->
    <Dialog v-model:open="permissionPreviewVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[680px] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[640px]">
        <DialogHeader>
          <DialogTitle>权限码明细</DialogTitle>
          <DialogDescription>查看角色的权限码详情</DialogDescription>
        </DialogHeader>
        <div v-if="permissionPreviewRole" class="flex min-h-0 flex-1 flex-col gap-4">
          <div class="flex items-center justify-between pb-3 border-b border-border">
            <div>
              <strong class="text-sm">{{ permissionPreviewRole.roleName }}</strong>
              <span class="block text-xs text-muted-foreground mt-0.5">{{ permissionPreviewRole.roleCode }}</span>
            </div>
            <Badge variant="outline" class="min-w-[102px] justify-center border-blue-200 bg-blue-50 font-semibold text-blue-700">
              {{ getPermissionSummary(permissionPreviewRole) }}
            </Badge>
          </div>

          <Alert v-if="hasAllPermissions(permissionPreviewRole)">
            <AlertDescription>
              该角色使用全部权限通配符，后端会按系统全部权限码处理
            </AlertDescription>
          </Alert>

          <DialogScrollArea>
            <div class="space-y-3">
              <div v-for="group in getPermissionGroupsForRole(permissionPreviewRole)" :key="group.group" class="p-3 bg-muted/30 border border-border rounded-lg">
                <div class="text-xs font-bold mb-2">{{ group.group }}</div>
                <div class="grid grid-cols-2 gap-2">
                  <div v-for="item in group.codes" :key="item.code" class="flex flex-col p-2 bg-primary/5 border border-primary/20 rounded-md">
                    <span class="text-xs font-semibold">{{ item.label }}</span>
                    <small class="text-[11px] text-muted-foreground font-mono mt-0.5">{{ item.code }}</small>
                  </div>
                </div>
              </div>
            </div>
          </DialogScrollArea>
        </div>
        <DialogFooter>
          <Button variant="outline" @click="permissionPreviewVisible = false">关闭</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- Permission Config Dialog -->
    <Dialog v-model:open="permissionDialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[720px] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>权限配置</DialogTitle>
          <DialogDescription>为角色配置权限码</DialogDescription>
        </DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 py-2">
            <div class="space-y-1">
              <Label>当前角色</Label>
              <Input :model-value="permissionEditingRole?.roleName" disabled />
            </div>
            <div class="space-y-1">
              <Label>权限码 <span class="text-destructive">*</span></Label>
              <div class="w-full p-3 bg-muted/50 border border-border rounded-lg space-y-3">
                <div class="flex items-center gap-2">
                  <Checkbox id="perm-form-all" :model-value="isPermFormChecked('*')" @update:model-value="togglePermForm('*', $event === true)" />
                  <Label for="perm-form-all" class="cursor-pointer font-semibold">全部权限</Label>
                </div>
                <div v-for="group in permissionGroups" :key="group.group" class="pt-3 border-t border-border">
                  <div class="text-xs font-bold text-foreground mb-2">{{ group.group }}</div>
                  <div class="grid grid-cols-3 gap-1">
                    <div v-for="item in group.codes" :key="item.code" class="flex items-center gap-2">
                      <Checkbox :id="'perm-form-' + item.code" :model-value="isPermFormChecked(item.code)" @update:model-value="togglePermForm(item.code, $event === true)" />
                      <Label :for="'perm-form-' + item.code" class="cursor-pointer text-xs">{{ item.label }}</Label>
                    </div>
                  </div>
                </div>
              </div>
              <p v-if="permFormErrors.permissionCodes" class="text-xs text-destructive">{{ permFormErrors.permissionCodes }}</p>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter>
          <Button variant="outline" :disabled="permissionSubmitting" @click="permissionDialogVisible = false">取消</Button>
          <Button :disabled="permissionSubmitting" @click="submitPermissionForm">{{ permissionSubmitting ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- Confirm Dialog -->
    <ConfirmDialog
      :open="confirmState.open"
      :title="confirmState.title"
      :description="confirmState.description"
      :confirm-text="confirmState.confirmText"
      :variant="confirmState.variant"
      :loading="actionSubmitting"
      @update:open="confirmState.open = $event"
      @confirm="runConfirmAction"
    />
  </section>
</template>
