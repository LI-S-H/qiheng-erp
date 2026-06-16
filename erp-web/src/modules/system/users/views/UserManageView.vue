<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { getApiErrorMessage } from '@/api/http';
import { toast } from 'vue-sonner';
import { User } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Switch } from '@/components/ui/switch';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Checkbox } from '@/components/ui/checkbox';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
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
  DialogTitle,
} from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import PromptDialog from '@/components/common/PromptDialog.vue';
import MultiSelect from '@/components/common/MultiSelect.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import type {
  DeptOption,
  RoleOption,
  SystemUserFormPayload,
  SystemUserFormModel,
  SystemUserListItem,
  SystemUserQuery,
  UserStatus,
} from '../types';
import {
  listSystemUsers,
  createSystemUser,
  updateSystemUser,
  updateSystemUserStatus,
  resetSystemUserPassword,
  deleteSystemUser,
  batchUpdateSystemUserStatus,
  batchResetSystemUserPassword,
  batchDeleteSystemUsers,
  listRoleOptions,
  listDeptOptions,
  bindSystemUserRoles,
} from '../api';

const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const roleSubmitting = ref(false);
const actionSubmitting = ref(false);
const users = ref<SystemUserListItem[]>([]);
const total = ref(0);
const roleOptions = ref<RoleOption[]>([]);
const deptOptions = ref<DeptOption[]>([]);
const roleMultiOptions = computed(() => roleOptions.value.map(r => ({ value: r.roleId, label: r.roleName })));
const deptFilterOptions = computed(() => [
  { value: 'all', label: '全部部门' },
  ...deptOptions.value.map(dept => ({ value: dept.deptId, label: dept.deptName })),
]);
const roleFilterOptions = computed(() => [
  { value: 'all', label: '全部角色' },
  ...roleOptions.value.map(role => ({ value: role.roleId, label: role.roleName })),
]);
const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];
const selectedIds = ref<Set<string>>(new Set());
const userDialogVisible = ref(false);
const roleDialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingUserId = ref('');
const roleEditingUser = ref<SystemUserListItem | null>(null);

const query = reactive<SystemUserQuery>({
  username: '', realName: '', deptId: 'all', roleId: 'all', status: 'all', pageNum: 1, pageSize: 10,
});

const userForm = reactive<SystemUserFormModel>({
  username: '', realName: '', password: '', deptId: null, isAdmin: false, status: 1, roleIds: [],
});

const roleForm = reactive({ roleIds: [] as string[] });

const formErrors = reactive<Record<string, string>>({});
const roleFormErrors = reactive<Record<string, string>>({});
let fetchSequence = 0;

// Confirm dialog state
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

// Prompt dialog state
const promptState = reactive({
  open: false,
  title: '',
  description: '',
  inputType: 'text',
  inputPattern: undefined as RegExp | undefined,
  inputErrorMessage: '',
  inputPlaceholder: '',
  confirmText: '',
  onConfirm: ((_value: string) => {}) as ((value: string) => void | Promise<void>),
});

const enabledCount = computed(() => users.value.filter(u => u.status === 1).length);
const adminCount = computed(() => users.value.filter(u => u.isAdmin).length);
const roleBoundCount = computed(() => users.value.filter(u => u.roleIds.length > 0).length);

const allSelected = computed(() => users.value.length > 0 && users.value.every(u => selectedIds.value.has(u.userId)));
const selectedRows = computed(() => users.value.filter(u => selectedIds.value.has(u.userId)));
const queryBusy = computed(() => queryPending.value || loading.value);

async function fetchUsers() {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listSystemUsers({ ...query });
    if (sequence !== fetchSequence) return;
    users.value = result.records;
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

async function fetchOptions() {
  try {
    const [roles, depts] = await Promise.all([listRoleOptions(), listDeptOptions()]);
    roleOptions.value = roles;
    deptOptions.value = depts;
  } catch {
    // silent
  }
}

onMounted(() => {
  fetchOptions();
  fetchUsers();
});

function formatTableTime(value: string | null) {
  if (!value) return '未登录';
  return value.slice(5, 16);
}

const refreshList = useListRefresh(queryBusy, queryPending, fetchUsers);

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchUsers();
}, 250);

const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchUsers();
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
  query.username = ''; query.realName = ''; query.deptId = 'all'; query.roleId = 'all'; query.status = 'all'; query.pageNum = 1;
  queryPending.value = true;
  debouncedSearch();
}

function toggleSelectAll() {
  if (allSelected.value) {
    users.value.forEach(u => selectedIds.value.delete(u.userId));
  } else {
    users.value.forEach(u => selectedIds.value.add(u.userId));
  }
  selectedIds.value = new Set(selectedIds.value);
}

function toggleSelectRow(userId: string) {
  const next = new Set(selectedIds.value);
  if (next.has(userId)) next.delete(userId); else next.add(userId);
  selectedIds.value = next;
}

function ensureSelectedRows(actionName: string) {
  if (selectedIds.value.size > 0) return true;
  toast.warning(`请先选择需要${actionName}的用户`);
  return false;
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  confirmState.title = title;
  confirmState.description = description;
  confirmState.confirmText = confirmText;
  confirmState.variant = variant;
  confirmState.onConfirm = onConfirm;
  confirmState.open = true;
}

function showPrompt(title: string, description: string, inputType: string, inputPattern: RegExp | undefined, inputErrorMessage: string, inputPlaceholder: string, confirmText: string, onConfirm: (v: string) => void | Promise<void>) {
  promptState.title = title;
  promptState.description = description;
  promptState.inputType = inputType;
  promptState.inputPattern = inputPattern;
  promptState.inputErrorMessage = inputErrorMessage;
  promptState.inputPlaceholder = inputPlaceholder;
  promptState.confirmText = confirmText;
  promptState.onConfirm = onConfirm;
  promptState.open = true;
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

async function runPromptAction(value: string) {
  if (actionSubmitting.value) return;
  actionSubmitting.value = true;
  try {
    await promptState.onConfirm(value);
    promptState.open = false;
  } finally {
    actionSubmitting.value = false;
  }
}

function handleBatchStatus(status: UserStatus) {
  const action = status === 1 ? '启用' : '停用';
  if (!ensureSelectedRows(action)) return;
  showConfirm(
    `批量${action}`,
    `确认${action}已选择的 ${selectedIds.value.size} 个账号吗`,
    action,
    status === 1 ? 'default' : 'warning',
    async () => {
      try {
        await batchUpdateSystemUserStatus({ userIds: [...selectedIds.value], status });
        selectedIds.value = new Set();
        toast.success(`已批量${action}`);
        fetchUsers();
      } catch {}
    },
  );
}

function handleBatchResetPassword() {
  if (!ensureSelectedRows('重置密码')) return;
  showPrompt(
    '批量重置密码',
    `为已选择的 ${selectedIds.value.size} 个账号设置新密码`,
    'password',
    /^(?=.*\S).{6,32}$/,
    '密码长度为 6-32 个字符且不能全为空格',
    '请输入新密码',
    '确认重置',
    async (value) => {
      if (value) {
        try {
          await batchResetSystemUserPassword({ userIds: [...selectedIds.value], password: value });
          toast.success('已批量重置密码');
        } catch {}
      }
    },
  );
}

function handleBatchDelete() {
  if (!ensureSelectedRows('删除')) return;
  showConfirm(
    '批量删除用户',
    `确认删除已选择的 ${selectedIds.value.size} 个账号吗`,
    '删除',
    'destructive',
    async () => {
      try {
        await batchDeleteSystemUsers({ userIds: [...selectedIds.value] });
        selectedIds.value = new Set();
        toast.success('已批量删除用户');
        fetchUsers();
      } catch {}
    },
  );
}

function resetUserForm() {
  editingUserId.value = '';
  userForm.username = ''; userForm.realName = ''; userForm.password = '';
  userForm.deptId = null; userForm.isAdmin = false; userForm.status = 1; userForm.roleIds = [];
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
}

function validateUserForm(): boolean {
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  const username = userForm.username.trim();
  const realName = userForm.realName.trim();
  const password = userForm.password ?? '';
  if (!username) formErrors.username = '请输入登录账号';
  else if (!/^[A-Za-z][A-Za-z0-9_]{2,63}$/.test(username)) formErrors.username = '账号须以字母开头，仅可使用字母、数字和下划线，长度 3-64 位';
  if (!realName) formErrors.realName = '请输入用户姓名';
  else if (realName.length > 100) formErrors.realName = '姓名不能超过 100 个字符';
  if (dialogMode.value === 'create') {
    if (!password.trim()) formErrors.password = '请输入初始密码';
    else if (password.length < 6 || password.length > 32) formErrors.password = '密码长度为 6-32 个字符';
  } else {
    if (password && !password.trim()) formErrors.password = '密码不能全为空格';
    else if (password && (password.length < 6 || password.length > 32)) formErrors.password = '密码长度为 6-32 个字符';
  }
  if (!userForm.deptId) formErrors.deptId = '请选择所属部门';
  if (!userForm.roleIds.length) formErrors.roleIds = '请选择用户角色';
  return Object.keys(formErrors).length === 0;
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetUserForm();
  userDialogVisible.value = true;
}

function openEditDialog(row: SystemUserListItem) {
  dialogMode.value = 'edit';
  editingUserId.value = row.userId;
  userForm.username = row.username;
  userForm.realName = row.realName;
  userForm.password = '';
  userForm.deptId = row.deptId;
  userForm.isAdmin = row.isAdmin;
  userForm.status = row.status;
  userForm.roleIds = [...row.roleIds];
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  userDialogVisible.value = true;
}

async function submitUserForm() {
  if (formSubmitting.value || !validateUserForm()) return;
  formSubmitting.value = true;
  const password = userForm.password ?? '';
  const payload: SystemUserFormPayload = {
    username: userForm.username.trim(),
    realName: userForm.realName.trim(),
    deptId: userForm.deptId!,
    isAdmin: userForm.isAdmin,
    status: userForm.status,
    roleIds: [...new Set(userForm.roleIds)],
    ...((dialogMode.value === 'create' || password) ? { password } : {}),
  };
  try {
    if (dialogMode.value === 'create') {
      await createSystemUser(payload);
      toast.success('用户已创建');
    } else {
      await updateSystemUser(editingUserId.value, payload);
      toast.success('用户已更新');
    }
    userDialogVisible.value = false;
    fetchUsers();
  } catch (error) {
    const message = getApiErrorMessage(error);
    if (message === '登录账号已存在') {
      formErrors.username = message;
    }
  } finally {
    formSubmitting.value = false;
  }
}

function openRoleDialog(row: SystemUserListItem) {
  roleEditingUser.value = row;
  roleForm.roleIds = [...row.roleIds];
  Object.keys(roleFormErrors).forEach(k => delete roleFormErrors[k]);
  roleDialogVisible.value = true;
}

async function submitRoleForm() {
  Object.keys(roleFormErrors).forEach(k => delete roleFormErrors[k]);
  if (!roleForm.roleIds.length) { roleFormErrors.roleIds = '请选择用户角色'; return; }
  if (roleSubmitting.value) return;
  const current = roleEditingUser.value;
  if (!current) return;
  roleSubmitting.value = true;
  try {
    await bindSystemUserRoles(current.userId, { roleIds: [...new Set(roleForm.roleIds)] });
    roleDialogVisible.value = false;
    toast.success('角色绑定已更新');
    fetchUsers();
  } catch {
    // http.ts handles toast
  } finally {
    roleSubmitting.value = false;
  }
}

async function handleStatusChange(row: SystemUserListItem, status: UserStatus) {
  const action = status === 1 ? '启用' : '停用';
  showConfirm(`${action}账号`, `确认${action}账号「${row.username}」吗`, action, status === 1 ? 'default' : 'warning', async () => {
    try {
      await updateSystemUserStatus(row.userId, status);
      toast.success(`账号已${action}`);
      fetchUsers();
    } catch {}
  });
}

function handleResetPassword(row: SystemUserListItem) {
  showPrompt(
    '重置密码', `为「${row.realName}」设置新密码`, 'password',
    /^(?=.*\S).{6,32}$/, '密码长度为 6-32 个字符且不能全为空格', '请输入新密码', '确认重置',
    async (value) => {
      if (value) {
        try {
          await resetSystemUserPassword(row.userId, { password: value });
          toast.success('密码已重置');
        } catch {}
      }
    },
  );
}

function handleDelete(row: SystemUserListItem) {
  showConfirm('删除用户', `确认删除账号「${row.username}」吗`, '删除', 'destructive', async () => {
    try {
      await deleteSystemUser(row.userId);
      toast.success('用户已删除');
      fetchUsers();
    } catch {}
  });
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-description">维护登录账号、部门归属、角色绑定与启用状态</p>
      </div>
    </div>

    <!-- Metrics -->
    <div class="summary-strip">
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">用户总数</span>
        <strong class="text-2xl mt-1">{{ total }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">启用账号</span>
        <strong class="text-2xl mt-1">{{ enabledCount }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">超级管理员</span>
        <strong class="text-2xl mt-1">{{ adminCount }}</strong>
      </div>
      <div class="summary-item">
        <span class="text-xs text-muted-foreground">已绑定角色</span>
        <strong class="text-2xl mt-1">{{ roleBoundCount }}</strong>
      </div>
    </div>

    <!-- Filter -->
    <div class="filter-panel">
      <div class="filter-grid filter-grid--users">
        <div class="space-y-1">
          <Label class="text-xs">登录账号</Label>
          <Input v-model="query.username" placeholder="请输入登录账号" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">用户姓名</Label>
          <Input v-model="query.realName" placeholder="请输入用户姓名" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">部门</Label>
          <AnchoredSelect v-model="query.deptId" :options="deptFilterOptions" placeholder="全部部门" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">角色</Label>
          <AnchoredSelect v-model="query.roleId" :options="roleFilterOptions" placeholder="全部角色" />
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
          <strong class="text-sm">账号列表</strong>
          <span class="text-xs" :class="selectedIds.size > 0 ? 'text-primary' : 'text-muted-foreground'">
            已选 {{ selectedIds.size }} 项
          </span>
        </div>
        <div class="table-toolbar__actions">
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增用户</Button></span>
            </TooltipTrigger>
            <TooltipContent>创建新的登录账号</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex">
                <Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchStatus(1)">批量启用</Button>
              </span>
            </TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择账号' : '启用已选账号' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex">
                <Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchStatus(0)">批量停用</Button>
              </span>
            </TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择账号' : '停用已选账号' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex">
                <Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchResetPassword">重置密码</Button>
              </span>
            </TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择账号' : '重置已选账号密码' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex">
                <Button size="sm" variant="destructive" :disabled="selectedIds.size === 0 || actionSubmitting" @click="handleBatchDelete">删除</Button>
              </span>
            </TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择账号' : '删除已选账号' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span>
            </TooltipTrigger>
            <TooltipContent>重新加载账号列表</TooltipContent>
          </Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1050px] table-fixed">
          <colgroup>
            <col class="w-[44px]" />
            <col class="w-[190px]" />
            <col class="w-[110px]" />
            <col class="w-[210px]" />
            <col class="w-[90px]" />
            <col class="w-[80px]" />
            <col class="w-[150px]" />
            <col class="w-[176px]" />
          </colgroup>
          <TableHeader>
            <TableRow>
              <TableHead class="w-[44px]">
                <Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" />
              </TableHead>
              <TableHead>账号</TableHead>
              <TableHead class="text-center">部门</TableHead>
              <TableHead class="text-center">角色</TableHead>
              <TableHead class="text-center w-[108px]">管理员</TableHead>
              <TableHead class="text-center w-[96px]">状态</TableHead>
              <TableHead>时间</TableHead>
              <TableHead class="text-center w-[176px]">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-if="loading">
              <TableCell colspan="8" class="text-center text-muted-foreground py-8">加载中...</TableCell>
            </TableRow>
            <TableRow v-else-if="users.length === 0">
              <TableCell colspan="8" class="text-center text-muted-foreground py-8">暂无数据</TableCell>
            </TableRow>
            <TableRow v-for="row in users" :key="row.userId" :class="{ 'bg-muted/50': selectedIds.has(row.userId) }">
              <TableCell>
                <Checkbox :model-value="selectedIds.has(row.userId)" @update:model-value="toggleSelectRow(row.userId)" />
              </TableCell>
              <TableCell>
                <div class="flex items-center gap-2">
                  <Avatar class="h-8 w-8 bg-primary">
                    <AvatarFallback class="text-primary-foreground text-xs bg-primary"><User class="h-4 w-4" /></AvatarFallback>
                  </Avatar>
                  <div>
                    <div class="text-sm font-medium">{{ row.username }}</div>
                    <div class="text-xs text-muted-foreground">{{ row.realName }}</div>
                  </div>
                </div>
              </TableCell>
              <TableCell class="text-center">{{ row.deptName }}</TableCell>
              <TableCell class="text-center">
                <div class="flex flex-wrap gap-1 justify-center">
                  <Badge v-for="rn in row.roleNames" :key="rn" variant="outline" class="border-blue-200 bg-blue-50 text-blue-700">{{ rn }}</Badge>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <Badge variant="outline" :class="row.isAdmin ? 'border-blue-200 bg-blue-50 text-blue-700' : 'text-muted-foreground'">{{ row.isAdmin ? '是' : '否' }}</Badge>
              </TableCell>
              <TableCell class="text-center">
                <Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </Badge>
              </TableCell>
              <TableCell>
                <div class="flex flex-col text-xs">
                  <span>登录 {{ formatTableTime(row.lastLoginAt) }}</span>
                  <span class="text-muted-foreground">更新 {{ formatTableTime(row.updateTime) }}</span>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="flex items-center justify-center gap-1">
                  <Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button>
                  <Button size="sm" variant="ghost" class="text-primary" :disabled="actionSubmitting" @click="openRoleDialog(row)">角色</Button>
                  <Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="handleDelete(row)">删除</Button>
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

    <!-- Create/Edit User Dialog -->
    <Dialog v-model:open="userDialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{{ dialogMode === 'create' ? '新增用户' : '编辑用户' }}</DialogTitle>
          <DialogDescription>填写用户基本信息</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-2">
          <div class="space-y-1">
            <Label>登录账号 <span class="text-destructive">*</span></Label>
            <Input v-model="userForm.username" :disabled="dialogMode === 'edit'" maxlength="64" />
            <p v-if="formErrors.username" class="text-xs text-destructive">{{ formErrors.username }}</p>
          </div>
          <div class="space-y-1">
            <Label>用户姓名 <span class="text-destructive">*</span></Label>
            <Input v-model="userForm.realName" maxlength="100" />
            <p v-if="formErrors.realName" class="text-xs text-destructive">{{ formErrors.realName }}</p>
          </div>
          <div class="space-y-1">
            <Label>{{ dialogMode === 'create' ? '初始密码' : '新密码' }} <span v-if="dialogMode === 'create'" class="text-destructive">*</span></Label>
            <Input v-model="userForm.password" type="password" :placeholder="dialogMode === 'create' ? '请输入初始密码' : '不修改请留空'" />
            <p v-if="formErrors.password" class="text-xs text-destructive">{{ formErrors.password }}</p>
          </div>
          <div class="space-y-1">
            <Label>所属部门 <span class="text-destructive">*</span></Label>
            <Select v-model="userForm.deptId">
              <SelectTrigger><SelectValue placeholder="请选择部门" /></SelectTrigger>
              <SelectContent>
                <SelectItem v-for="d in deptOptions" :key="d.deptId" :value="d.deptId">{{ d.deptName }}</SelectItem>
              </SelectContent>
            </Select>
            <p v-if="formErrors.deptId" class="text-xs text-destructive">{{ formErrors.deptId }}</p>
          </div>
          <div class="space-y-1">
            <Label>绑定角色 <span class="text-destructive">*</span></Label>
            <MultiSelect v-model="userForm.roleIds" :options="roleMultiOptions" placeholder="请选择角色" />
            <p v-if="formErrors.roleIds" class="text-xs text-destructive">{{ formErrors.roleIds }}</p>
          </div>
          <div class="flex items-center justify-between">
            <Label>超级管理员</Label>
            <Switch :model-value="userForm.isAdmin" @update:model-value="userForm.isAdmin = $event === true" />
          </div>
          <div class="space-y-1">
            <Label>启用状态 <span class="text-destructive">*</span></Label>
            <RadioGroup :model-value="String(userForm.status)" @update:model-value="userForm.status = Number($event) as 0 | 1" class="flex gap-4">
              <div class="flex items-center gap-2">
                <RadioGroupItem value="1" id="user-status-1" />
                <Label for="user-status-1" class="cursor-pointer">启用</Label>
              </div>
              <div class="flex items-center gap-2">
                <RadioGroupItem value="0" id="user-status-0" />
                <Label for="user-status-0" class="cursor-pointer">停用</Label>
              </div>
            </RadioGroup>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" :disabled="formSubmitting" @click="userDialogVisible = false">取消</Button>
          <Button :disabled="formSubmitting" @click="submitUserForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <!-- Role Binding Dialog -->
    <Dialog v-model:open="roleDialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="sm:max-w-[460px]">
        <DialogHeader>
          <DialogTitle>角色绑定</DialogTitle>
          <DialogDescription>为用户「{{ roleEditingUser?.realName }}」配置角色</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-2">
          <div class="space-y-1">
            <Label>当前用户</Label>
            <Input :model-value="roleEditingUser?.realName" disabled />
          </div>
          <div class="space-y-1">
            <Label>绑定角色 <span class="text-destructive">*</span></Label>
            <MultiSelect v-model="roleForm.roleIds" :options="roleMultiOptions" placeholder="请选择角色" />
            <p v-if="roleFormErrors.roleIds" class="text-xs text-destructive">{{ roleFormErrors.roleIds }}</p>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" :disabled="roleSubmitting" @click="roleDialogVisible = false">取消</Button>
          <Button :disabled="roleSubmitting" @click="submitRoleForm">{{ roleSubmitting ? '保存中...' : '保存' }}</Button>
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

    <!-- Prompt Dialog -->
    <PromptDialog
      :open="promptState.open"
      :title="promptState.title"
      :description="promptState.description"
      :input-type="promptState.inputType"
      :input-pattern="promptState.inputPattern"
      :input-error-message="promptState.inputErrorMessage"
      :input-placeholder="promptState.inputPlaceholder"
      :confirm-text="promptState.confirmText"
      :loading="actionSubmitting"
      @update:open="promptState.open = $event"
      @confirm="runPromptAction"
    />
  </section>
</template>
