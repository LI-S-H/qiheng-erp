<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { ChevronRight } from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
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
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import TreeSelect from '@/components/common/TreeSelect.vue';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import TreeTableBody from '@/components/common/TreeTableBody.vue';
import type { DeptStatus, SystemDeptFormPayload, SystemDeptListItem, SystemDeptQuery } from '../types';
import { listSystemDepts, createSystemDept, updateSystemDept, deleteSystemDept, batchUpdateSystemDeptStatus, batchDeleteSystemDepts } from '../api';

const ROOT_PARENT_ID = '0';
const ROOT_PARENT_LABEL = '无上级部门';
// DOM 移除要晚于行高动画结束，避免最后一帧和删除折叠行抢同一帧造成轻微回跳。
const TREE_COLLAPSE_DURATION = 340;
const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

interface DeptParentOption {
  deptId: string;
  deptName: string;
  parentId: string;
  status: DeptStatus;
  children?: DeptParentOption[];
}

interface VisibleDeptRow extends SystemDeptListItem {
  level: number;
  isCollapsing?: boolean;
}

const deptListResponse = ref<SystemDeptListItem[]>([]);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const expandedDeptIds = ref<Set<string>>(new Set());
const collapsingDeptIds = ref<Set<string>>(new Set());
const deptDialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit' | 'child'>('create');
const editingDeptId = ref('');

const query = reactive<SystemDeptQuery>({ deptName: '', status: 'all' });
const appliedQuery = reactive<SystemDeptQuery>({ deptName: '', status: 'all' });
const deptForm = reactive<SystemDeptFormPayload>({ parentId: '0', deptName: '', status: 1 });
const formErrors = reactive<Record<string, string>>({});
let fetchSequence = 0;
const collapseTimers = new Map<string, number>();

const confirmState = reactive({
  open: false, title: '', description: '', confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const depts = computed(() => buildDeptTree(deptListResponse.value));
const flatDepts = computed(() => flattenDeptTree(depts.value));
const enabledCount = computed(() => flatDepts.value.filter(d => d.status === 1).length);
const childDeptCount = computed(() => flatDepts.value.filter(d => d.parentId !== ROOT_PARENT_ID).length);
const employeeTotal = computed(() => flatDepts.value.reduce((t, d) => t + d.userCount, 0));
const summaryItems = computed(() => [
  { key: 'total', label: '部门总数', value: flatDepts.value.length },
  { key: 'enabled', label: '启用部门', value: enabledCount.value, tone: 'positive' as const },
  { key: 'children', label: '下级部门', value: childDeptCount.value },
  { key: 'employees', label: '员工数量', value: employeeTotal.value },
]);
const visibleDepts = computed(() => flattenVisibleDeptTree(depts.value));
const parentOptions = computed(() => [
  {
    deptId: ROOT_PARENT_ID, deptName: ROOT_PARENT_LABEL, parentId: '', status: 1 as DeptStatus,
    children: buildParentOptionTree(depts.value, editingDeptId.value),
  },
]);

const selectedRows = computed(() => flatDepts.value.filter(d => selectedIds.value.has(d.deptId)));
const queryBusy = computed(() => queryPending.value || loading.value);
const dialogTitle = computed(() => {
  if (dialogMode.value === 'edit') return '编辑部门';
  if (dialogMode.value === 'child') return '新增下级部门';
  return '新增部门';
});

function toDeptQueryParams(params: SystemDeptQuery): SystemDeptQuery | undefined {
  const deptName = params.deptName?.trim() || '';
  const status = isBlankStatus(params.status) ? undefined : params.status;
  if (!deptName && status === undefined) return undefined;
  return {
    ...(deptName ? { deptName } : {}),
    ...(status !== undefined ? { status } : {}),
  };
}

async function fetchDepts(params: SystemDeptQuery = appliedQuery) {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listSystemDepts(toDeptQueryParams(params));
    if (sequence !== fetchSequence) return;
    const tree = buildDeptTree(result);
    clearDeptCollapseState();
    deptListResponse.value = result;
    expandedDeptIds.value = new Set(collectExpandableDeptIds(tree));
  } catch {
  } finally {
    if (sequence === fetchSequence) loading.value = false;
  }
}

onMounted(() => { fetchDepts(); });
onBeforeUnmount(() => { clearDeptCollapseState(); });

function buildDeptTree(items: SystemDeptListItem[]): SystemDeptListItem[] {
  const itemMap = new Map<string, SystemDeptListItem>();
  const roots: SystemDeptListItem[] = [];
  items.forEach(item => itemMap.set(item.deptId, { ...item, children: undefined }));
  itemMap.forEach(item => {
    if (item.parentId === ROOT_PARENT_ID) {
      roots.push(item);
      return;
    }
    const parent = itemMap.get(item.parentId);
    if (parent) {
      parent.children = [...(parent.children || []), item];
    } else {
      roots.push(item);
    }
  });
  return roots;
}

function flattenDeptTree(tree: SystemDeptListItem[]): SystemDeptListItem[] {
  const result: SystemDeptListItem[] = [];
  function walk(items: SystemDeptListItem[]) {
    items.forEach(item => { result.push(item); if (item.children?.length) walk(item.children); });
  }
  walk(tree);
  return result;
}

function flattenVisibleDeptTree(tree: SystemDeptListItem[], level = 0): VisibleDeptRow[] {
  const result: VisibleDeptRow[] = [];
  tree.forEach(item => {
    result.push({
      ...item,
      children: undefined,
      level,
      isCollapsing: collapsingDeptIds.value.has(item.deptId),
    });
    if (item.children?.length && (expandedDeptIds.value.has(item.deptId) || hasCollapsingDeptDescendant(item))) {
      result.push(...flattenVisibleDeptTree(item.children, level + 1));
    }
  });
  return result;
}

function hasCollapsingDeptDescendant(row: SystemDeptListItem): boolean {
  return Boolean(row.children?.some(child => collapsingDeptIds.value.has(child.deptId) || hasCollapsingDeptDescendant(child)));
}

function collectExpandableDeptIds(tree: SystemDeptListItem[]): string[] {
  const result: string[] = [];
  tree.forEach(item => { if (item.children?.length) { result.push(item.deptId, ...collectExpandableDeptIds(item.children)); } });
  return result;
}

function buildParentOptionTree(tree: SystemDeptListItem[], excludeDeptId = ''): DeptParentOption[] {
  const excludedDeptIds = getExcludedParentOptionIds(excludeDeptId);
  return buildParentOptions(tree, excludedDeptIds);
}

function buildParentOptions(tree: SystemDeptListItem[], excludedDeptIds: Set<string>): DeptParentOption[] {
  return tree.filter(item => !excludedDeptIds.has(item.deptId)).map(item => ({
    deptId: item.deptId, deptName: item.deptName, parentId: item.parentId, status: item.status,
    children: item.children ? buildParentOptions(item.children, excludedDeptIds) : undefined,
  }));
}

function getExcludedParentOptionIds(excludeDeptId = '') {
  if (!excludeDeptId) return new Set<string>();
  return new Set([excludeDeptId, ...getDeptDescendants(excludeDeptId).map(item => item.deptId)]);
}

function findDept(deptId: string, tree = depts.value): SystemDeptListItem | null {
  for (const item of tree) {
    if (item.deptId === deptId) return item;
    if (item.children?.length) { const matched = findDept(deptId, item.children); if (matched) return matched; }
  }
  return null;
}

function getParentName(parentId: string) {
  if (parentId === ROOT_PARENT_ID) return ROOT_PARENT_LABEL;
  return flatDepts.value.find(item => item.deptId === parentId)?.deptName || '-';
}

function getDeptPath(dept: SystemDeptListItem) {
  const pathNames: string[] = [];
  let current: SystemDeptListItem | undefined = dept;
  let guard = 0;
  while (current && guard < 50) {
    pathNames.unshift(current.deptName);
    if (current.parentId === ROOT_PARENT_ID) break;
    current = flatDepts.value.find(item => item.deptId === current?.parentId);
    guard += 1;
  }
  return pathNames.join(' / ');
}

function isBlankStatus(status: SystemDeptQuery['status'] | null) {
  return status === '' || status === 'all' || status === null || status === undefined;
}

function isDeptExpanded(row: SystemDeptListItem) {
  return expandedDeptIds.value.has(row.deptId);
}

function clearDeptCollapseState() {
  collapseTimers.forEach(timer => window.clearTimeout(timer));
  collapseTimers.clear();
  collapsingDeptIds.value = new Set();
}

function cancelCollapsingDeptIds(ids: string[]) {
  if (ids.length === 0) return;
  const nextCollapsing = new Set(collapsingDeptIds.value);
  ids.forEach((id) => {
    const timer = collapseTimers.get(id);
    if (timer) window.clearTimeout(timer);
    collapseTimers.delete(id);
    nextCollapsing.delete(id);
  });
  collapsingDeptIds.value = nextCollapsing;
}

function scheduleDeptCollapseRemoval(ids: string[]) {
  ids.forEach((id) => {
    const oldTimer = collapseTimers.get(id);
    if (oldTimer) window.clearTimeout(oldTimer);
    const timer = window.setTimeout(() => {
      const nextCollapsing = new Set(collapsingDeptIds.value);
      nextCollapsing.delete(id);
      collapsingDeptIds.value = nextCollapsing;
      collapseTimers.delete(id);
    }, TREE_COLLAPSE_DURATION);
    collapseTimers.set(id, timer);
  });
}

function toggleDept(row: SystemDeptListItem) {
  if (!hasChildren(row)) return;
  const descendantIds = getDeptDescendants(row.deptId).map(item => item.deptId);
  const nextExpandedIds = new Set(expandedDeptIds.value);
  if (nextExpandedIds.has(row.deptId)) {
    nextExpandedIds.delete(row.deptId);
    const nextCollapsing = new Set(collapsingDeptIds.value);
    descendantIds.forEach(id => nextCollapsing.add(id));
    collapsingDeptIds.value = nextCollapsing;
    scheduleDeptCollapseRemoval(descendantIds);
  } else {
    cancelCollapsingDeptIds(descendantIds);
    nextExpandedIds.add(row.deptId);
  }
  expandedDeptIds.value = nextExpandedIds;
}

function resetDeptForm() {
  editingDeptId.value = '';
  deptForm.parentId = ROOT_PARENT_ID;
  deptForm.deptName = '';
  deptForm.status = 1;
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
}

const debouncedSearch = useDebounceFn(() => {
  appliedQuery.deptName = query.deptName?.trim() || '';
  appliedQuery.status = query.status;
  selectedIds.value = new Set();
  queryPending.value = false;
  void fetchDepts();
}, 250);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}
function handleReset() {
  if (queryBusy.value) return;
  query.deptName = ''; query.status = 'all';
  queryPending.value = true;
  debouncedSearch();
}

const refreshList = useListRefresh(queryBusy, queryPending, fetchDepts);

function openCreateDialog() {
  dialogMode.value = 'create'; resetDeptForm(); deptDialogVisible.value = true;
}

function openChildDialog(row: SystemDeptListItem) {
  dialogMode.value = 'child'; resetDeptForm(); deptForm.parentId = row.deptId; deptDialogVisible.value = true;
}

function openEditDialog(row: SystemDeptListItem) {
  dialogMode.value = 'edit'; editingDeptId.value = row.deptId;
  deptForm.parentId = row.parentId; deptForm.deptName = row.deptName; deptForm.status = row.status;
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  deptDialogVisible.value = true;
}

function validateDeptForm(): boolean {
  Object.keys(formErrors).forEach(k => delete formErrors[k]);
  const deptName = deptForm.deptName.trim();
  if (!deptForm.parentId) formErrors.parentId = '请选择上级部门';
  if (!deptName) formErrors.deptName = '请输入部门名称';
  else if (deptName.length > 100) formErrors.deptName = '部门名称不能超过 100 个字符';
  return Object.keys(formErrors).length === 0;
}

async function submitDeptForm() {
  if (formSubmitting.value || !validateDeptForm()) return;
  const payload: SystemDeptFormPayload = { ...deptForm, deptName: deptForm.deptName.trim() };
  const currentDept = dialogMode.value === 'edit' ? findDept(editingDeptId.value) : null;
  if (currentDept?.status === 1 && payload.status === 0) {
    showConfirm(
      '确认停用部门',
      '停用后，该部门及其下级部门会同步停用。员工账号不会自动停用，但不能再将员工新增或调整到停用部门。是否继续？',
      '确认停用',
      'warning',
      () => persistDeptForm(payload),
    );
    return;
  }
  await persistDeptForm(payload);
}

async function persistDeptForm(payload: SystemDeptFormPayload) {
  if (formSubmitting.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'edit') {
      await updateSystemDept(editingDeptId.value, payload);
      toast.success('部门已更新');
    } else {
      await createSystemDept(payload);
      toast.success(dialogMode.value === 'child' ? '下级部门已新增' : '部门已新增');
    }
    deptDialogVisible.value = false;
    fetchDepts();
  } catch {} finally {
    formSubmitting.value = false;
  }
}

function buildAncestorsByParentId(parentId: string) {
  if (parentId === ROOT_PARENT_ID) return ROOT_PARENT_ID;
  const parentPath = getDeptPathIds(parentId);
  return [ROOT_PARENT_ID, ...parentPath].join(',');
}

function getDeptPathIds(deptId: string) {
  const pathIds: string[] = [];
  let current = flatDepts.value.find(item => item.deptId === deptId);
  let guard = 0;
  while (current && guard < 50) {
    pathIds.unshift(current.deptId);
    if (current.parentId === ROOT_PARENT_ID) break;
    current = flatDepts.value.find(item => item.deptId === current?.parentId);
    guard += 1;
  }
  return pathIds;
}

function rebuildDeptTree(items: SystemDeptListItem[]): SystemDeptListItem[] {
  const itemMap = new Map<string, SystemDeptListItem>();
  const roots: SystemDeptListItem[] = [];
  items.forEach(item => { itemMap.set(item.deptId, { ...item, children: undefined }); });
  itemMap.forEach(item => {
    if (item.parentId === ROOT_PARENT_ID) { roots.push(item); return; }
    const parent = itemMap.get(item.parentId);
    if (parent) { parent.children = [...(parent.children || []), item]; } else { roots.push(item); }
  });
  return roots;
}

async function setDeptStatus(row: SystemDeptListItem, status: DeptStatus) {
  try {
    await batchUpdateSystemDeptStatus({ deptIds: [row.deptId], status });
    fetchDepts();
  } catch {}
}

// Selection logic
const allSelected = computed(() =>
  visibleDepts.value.length > 0 && visibleDepts.value.every(d => selectedIds.value.has(d.deptId)),
);

function toggleSelectAll() {
  if (allSelected.value) {
    visibleDepts.value.forEach(d => selectedIds.value.delete(d.deptId));
  } else {
    visibleDepts.value.forEach(d => selectedIds.value.add(d.deptId));
  }
  selectedIds.value = new Set(selectedIds.value);
}

function toggleSelectRow(deptId: string) {
  const next = new Set(selectedIds.value);
  const row = visibleDepts.value.find(d => d.deptId === deptId);
  if (!row) return;

  if (next.has(deptId)) {
    // Uncheck: also uncheck descendants
    next.delete(deptId);
    const descendants = getDeptDescendants(deptId);
    descendants.forEach(d => next.delete(d.deptId));
  } else {
    // Check: also check descendants & expand if has children
    next.add(deptId);
    const descendants = getDeptDescendants(deptId);
    descendants.forEach(d => next.add(d.deptId));
    if (hasChildren(row)) {
      cancelCollapsingDeptIds(descendants.map(item => item.deptId));
      const nextExpandedIds = new Set(expandedDeptIds.value);
      [row, ...descendants].filter(item => hasChildren(item)).forEach(item => nextExpandedIds.add(item.deptId));
      expandedDeptIds.value = nextExpandedIds;
    }
  }
  selectedIds.value = next;
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  confirmState.title = title; confirmState.description = description;
  confirmState.confirmText = confirmText; confirmState.variant = variant;
  confirmState.onConfirm = onConfirm; confirmState.open = true;
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

function confirmBatchStatus(status: DeptStatus) {
  if (selectedIds.value.size === 0) { toast.warning('请先选择部门'); return; }
  const actionName = status === 1 ? '启用' : '停用';
  const rows = selectedRows.value;

  if (status === 1) {
    const selectedIdSet = new Set(rows.map(r => r.deptId));
    const blocked = rows.find(row => hasDisabledAncestor(row.deptId, selectedIdSet));
    if (blocked) {
      toast.warning(`请先启用「${getParentName(blocked.parentId)}」，再启用「${blocked.deptName}」`);
      return;
    }
  }

  const description = status === 0
    ? '停用后，所选部门及其下级部门会同步停用。员工账号不会自动停用，但不能再将员工新增或调整到停用部门。是否继续？'
    : `确认启用已选的 ${rows.length} 个部门吗？`;
  showConfirm(
    `批量${actionName}`,
    description,
    actionName, 'warning',
    async () => {
      try {
        await batchUpdateSystemDeptStatus({ deptIds: rows.map(r => r.deptId), status });
        selectedIds.value = new Set();
        toast.success(`已批量${actionName}`);
        fetchDepts();
      } catch {}
    },
  );
}

function hasDisabledAncestor(deptId: string, selectedIdSet: Set<string>) {
  return getDeptPathIds(deptId).filter(id => id !== deptId).some(ancestorId => {
    if (selectedIdSet.has(ancestorId)) return false;
    const ancestor = flatDepts.value.find(item => item.deptId === ancestorId);
    return ancestor?.status === 0;
  });
}

function hasChildren(row: SystemDeptListItem) {
  return Boolean(findDept(row.deptId)?.children?.length || row.children?.length);
}

function getDeptDescendants(deptId: string, tree = depts.value): SystemDeptListItem[] {
  const target = findDept(deptId, tree);
  if (!target?.children?.length) return [];
  return flattenDeptTree(target.children);
}

function showBackendActionError(error: unknown) {
  if (error && typeof error === 'object' && 'response' in error) return;
  const message = getApiErrorMessage(error);
  if (message) toast.warning(message);
}

function confirmDelete(row: SystemDeptListItem) {
  const risks = [
    hasChildren(row) ? '存在下级部门' : '',
    row.userCount > 0 ? `已有 ${row.userCount} 名员工归属` : '',
  ].filter(Boolean);
  const description = risks.length > 0
    ? `该部门当前显示${risks.join('、')}，最终以后端校验为准。确认提交删除请求吗？`
    : `确认删除部门「${row.deptName}」吗？`;
  showConfirm(
    '删除部门', description, '删除', 'destructive',
    async () => {
      try {
        await deleteSystemDept(row.deptId);
        selectedIds.value = new Set();
        toast.success('部门已删除');
        fetchDepts();
      } catch (error) {
        showBackendActionError(error);
      }
    },
  );
}

function confirmBatchDelete() {
  if (selectedIds.value.size === 0) { toast.warning('请先选择部门'); return; }
  const rows = selectedRows.value;
  const blockedCount = rows.filter(row => hasChildren(row) || row.userCount > 0).length;
  const description = blockedCount > 0
    ? `已选部门中有 ${blockedCount} 个当前显示存在下级部门或员工归属，最终以后端校验为准。确认提交批量删除请求吗？`
    : `确认删除已选的 ${rows.length} 个部门吗？`;
  showConfirm(
    '批量删除', description, '删除', 'destructive',
    async () => {
      try {
        await batchDeleteSystemDepts({ deptIds: rows.map(r => r.deptId) });
        selectedIds.value = new Set();
        toast.success('已批量删除');
        fetchDepts();
      } catch (error) {
        showBackendActionError(error);
      }
    },
  );
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">部门管理</h1>
        <p class="page-description">维护部门层级、启用状态和员工归属统计</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" aria-label="部门数据汇总" />

    <ListFilterPanel layout="content" aria-label="部门筛选">
        <div class="space-y-1" data-filter-size="standard">
          <Label class="text-xs">部门名称</Label>
          <Input v-model="query.deptName" placeholder="如 采购部" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1" data-filter-size="compact">
          <Label class="text-xs">状态</Label>
          <AnchoredSelect v-model="query.status" :options="statusFilterOptions" placeholder="全部状态" />
        </div>
        <template #actions>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
        </template>
    </ListFilterPanel>

    <!-- Table -->
    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <!-- Toolbar -->
      <div class="table-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">部门列表</strong>
          <span class="text-xs" :class="selectedIds.size > 0 ? 'text-primary' : 'text-muted-foreground'">
            已选 {{ selectedIds.size }} 项
          </span>
        </div>
        <div class="table-toolbar__actions">
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增部门</Button></span></TooltipTrigger>
            <TooltipContent>创建新的组织部门</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchStatus(1)">批量启用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择部门' : '启用已选部门' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchStatus(0)">批量停用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择部门' : '停用已选部门' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchDelete">删除</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择部门' : '删除已选部门' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger>
            <TooltipContent>重新加载部门列表</TooltipContent>
          </Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1076px] table-fixed">
          <colgroup>
            <col class="w-[46px]" />
            <col class="w-[220px]" />
            <col class="w-[130px]" />
            <col class="w-[260px]" />
            <col class="w-[104px]" />
            <col class="w-[96px]" />
            <col class="w-[220px]" />
          </colgroup>
          <TableHeader>
            <TableRow>
              <TableHead class="w-[46px]">
                <Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" />
              </TableHead>
              <TableHead>部门</TableHead>
              <TableHead class="text-center w-[110px]">上级部门</TableHead>
              <TableHead>层级路径</TableHead>
              <TableHead class="text-center w-[104px]">员工数量</TableHead>
              <TableHead class="text-center w-[96px]">状态</TableHead>
              <TableHead class="text-center w-[220px]">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody v-if="loading">
            <TableRow>
              <TableCell colspan="7" class="text-center text-muted-foreground py-8">加载中...</TableCell>
            </TableRow>
          </TableBody>
          <TableBody v-else-if="visibleDepts.length === 0">
            <TableRow>
              <TableCell colspan="7" class="text-center text-muted-foreground py-8">暂无数据</TableCell>
            </TableRow>
          </TableBody>
          <TreeTableBody v-else>
            <TableRow
              v-for="row in visibleDepts"
              :key="row.deptId"
              :data-tree-row-key="row.deptId"
              :data-tree-row-collapsing="row.isCollapsing ? 'true' : undefined"
              :class="{ 'bg-muted/50': selectedIds.has(row.deptId) }"
            >
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <Checkbox :model-value="selectedIds.has(row.deptId)" @update:model-value="toggleSelectRow(row.deptId)" />
                </div>
              </TableCell>
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <div class="flex items-center gap-1" :style="{ paddingLeft: `${row.level * 22}px` }">
                  <button
                    v-if="hasChildren(row)"
                    type="button"
                    class="inline-flex h-6 w-5 items-center justify-center text-muted-foreground transition-colors hover:text-primary"
                    :aria-label="isDeptExpanded(row) ? '收起当前部门' : '展开当前部门'"
                    @click.stop="toggleDept(row)"
                  >
                    <ChevronRight
                      class="h-3.5 w-3.5 transition-transform duration-200 ease-out"
                      :class="{ 'rotate-90': isDeptExpanded(row) }"
                    />
                  </button>
                  <span v-else class="w-5 h-6 flex-shrink-0" />
                    <strong class="text-sm">{{ row.deptName }}</strong>
                  </div>
                </div>
              </TableCell>
              <TableCell class="text-center text-xs text-muted-foreground">
                <div class="tree-table-cell-reveal">{{ getParentName(row.parentId) }}</div>
              </TableCell>
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <span class="text-xs text-muted-foreground truncate">{{ getDeptPath(row) }}</span>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="tree-table-cell-reveal">
                  <Badge
                    variant="outline"
                    class="min-w-[38px] justify-center font-semibold"
                    :class="row.userCount === 0 ? 'text-muted-foreground' : 'border-blue-200 bg-blue-50 text-blue-700'"
                  >
                    {{ row.userCount }}
                  </Badge>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="tree-table-cell-reveal">
                  <Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">
                  {{ row.status === 1 ? '启用' : '停用' }}
                  </Badge>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="tree-table-cell-reveal">
                  <div class="flex items-center justify-center gap-1">
                  <Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button>
                  <Button size="sm" variant="ghost" class="text-primary" :disabled="actionSubmitting" @click="openChildDialog(row)">新增下级</Button>
                  <Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="confirmDelete(row)">删除</Button>
                  </div>
                </div>
              </TableCell>
            </TableRow>
          </TreeTableBody>
        </Table>
      </ScrollArea>

      <!-- Tree summary bar -->
      <div class="flex items-center justify-between min-h-[52px] px-4 border-t border-border">
        <span class="text-xs text-muted-foreground">命中 {{ deptListResponse.length }} 条</span>
        <span class="text-xs text-muted-foreground">层级视图</span>
      </div>
    </div>

    <!-- Create/Edit Dept Dialog -->
    <Dialog v-model:open="deptDialogVisible" @update:open="(val: boolean) => { if (!val) resetDeptForm() }">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>{{ dialogTitle }}</DialogTitle>
          <DialogDescription>填写部门信息</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-2">
          <div class="space-y-1">
            <Label>上级部门 <span class="text-destructive">*</span></Label>
            <TreeSelect v-model="deptForm.parentId" :options="parentOptions" placeholder="请选择上级部门" />
            <p v-if="formErrors.parentId" class="text-xs text-destructive">{{ formErrors.parentId }}</p>
          </div>
          <div class="space-y-1">
            <Label>部门名称 <span class="text-destructive">*</span></Label>
            <Input v-model="deptForm.deptName" maxlength="100" placeholder="请输入部门名称" />
            <p v-if="formErrors.deptName" class="text-xs text-destructive">{{ formErrors.deptName }}</p>
          </div>
          <div class="space-y-1">
            <Label>状态 <span class="text-destructive">*</span></Label>
            <RadioGroup :model-value="String(deptForm.status)" @update:model-value="deptForm.status = Number($event) as 0 | 1" class="flex gap-4">
              <div class="flex items-center gap-2">
                <RadioGroupItem value="1" id="dept-status-1" />
                <Label for="dept-status-1" class="cursor-pointer">启用</Label>
              </div>
              <div class="flex items-center gap-2">
                <RadioGroupItem value="0" id="dept-status-0" />
                <Label for="dept-status-0" class="cursor-pointer">停用</Label>
              </div>
            </RadioGroup>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" :disabled="formSubmitting" @click="deptDialogVisible = false">取消</Button>
          <Button :disabled="formSubmitting" @click="submitDeptForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button>
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
