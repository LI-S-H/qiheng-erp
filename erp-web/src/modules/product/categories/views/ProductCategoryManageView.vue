<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
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
import type {
  ProductCategoryFormPayload,
  ProductCategoryListItem,
  ProductCategoryQuery,
  ProductCategoryStatus,
} from '../types';
import {
  batchDeleteProductCategories,
  batchUpdateProductCategoryStatus,
  createProductCategory,
  deleteProductCategory,
  listProductCategories,
  updateProductCategory,
} from '../api';

const ROOT_PARENT_ID = '0';
const ROOT_PARENT_LABEL = '无上级分类';
// DOM 移除要晚于行高动画结束，避免最后一帧和删除折叠行抢同一帧造成轻微回跳。
const TREE_COLLAPSE_DURATION = 340;
const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];

interface VisibleCategoryRow extends ProductCategoryListItem {
  level: number;
  isCollapsing?: boolean;
}

interface CategoryParentOption {
  deptId: string;
  deptName: string;
  parentId: string;
  status: ProductCategoryStatus;
  children?: CategoryParentOption[];
}

const filteredListResponse = ref<ProductCategoryListItem[]>([]);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const expandedCategoryIds = ref<Set<string>>(new Set());
const collapsingCategoryIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit' | 'child'>('create');
const editingCategoryId = ref('');
const query = reactive<ProductCategoryQuery>({ categoryName: '', status: 'all' });
const appliedQuery = reactive<ProductCategoryQuery>({ categoryName: '', status: 'all' });
const form = reactive<ProductCategoryFormPayload>({ parentId: ROOT_PARENT_ID, categoryName: '', status: 1 });
const formErrors = reactive<Record<string, string>>({});
let fetchSequence = 0;
const collapseTimers = new Map<string, number>();

const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const categories = computed(() => buildCategoryTree(filteredListResponse.value));
const flatCategories = computed(() => flattenCategoryTree(categories.value));
const enabledCount = computed(() => flatCategories.value.filter(item => item.status === 1).length);
const childCategoryCount = computed(() => flatCategories.value.filter(item => item.parentId !== ROOT_PARENT_ID).length);
const productTotal = computed(() => flatCategories.value.reduce((total, item) => total + item.productCount, 0));
const summaryItems = computed(() => [
  { key: 'total', label: '分类总数', value: flatCategories.value.length },
  { key: 'enabled', label: '启用分类', value: enabledCount.value, tone: 'positive' as const },
  { key: 'children', label: '下级分类', value: childCategoryCount.value },
  { key: 'products', label: '关联产品', value: productTotal.value },
]);
const visibleCategories = computed(() => flattenVisibleCategoryTree(categories.value));
const selectedRows = computed(() => flatCategories.value.filter(item => selectedIds.value.has(item.categoryId)));
const queryBusy = computed(() => queryPending.value || loading.value);
const allSelected = computed(() =>
  visibleCategories.value.length > 0 && visibleCategories.value.every(item => selectedIds.value.has(item.categoryId)),
);
const parentOptions = computed<CategoryParentOption[]>(() => [
  {
    deptId: ROOT_PARENT_ID,
    deptName: ROOT_PARENT_LABEL,
    parentId: '',
    status: 1,
    children: buildParentOptions(categories.value, getExcludedParentIds(editingCategoryId.value)),
  },
]);
const dialogTitle = computed(() => {
  if (dialogMode.value === 'edit') return '编辑分类';
  if (dialogMode.value === 'child') return '新增下级分类';
  return '新增分类';
});

function toCategoryQueryParams(queryParams: ProductCategoryQuery): ProductCategoryQuery | undefined {
  const categoryName = queryParams.categoryName?.trim() || '';
  const status = isBlankStatus(queryParams.status) ? undefined : queryParams.status;
  if (!categoryName && status === undefined) return undefined;
  return {
    ...(categoryName ? { categoryName } : {}),
    ...(status !== undefined ? { status } : {}),
  };
}

async function fetchCategories(queryParams: ProductCategoryQuery = appliedQuery) {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const params = toCategoryQueryParams(queryParams);
    const result = await listProductCategories(params);
    if (sequence !== fetchSequence) return;
    clearCategoryCollapseState();
    filteredListResponse.value = result;
    expandedCategoryIds.value = new Set(collectExpandableCategoryIds(buildCategoryTree(result)));
    selectedIds.value = new Set();
  } catch {
  } finally {
    if (sequence === fetchSequence) loading.value = false;
  }
}

onMounted(() => { void fetchCategories(); });
onBeforeUnmount(() => { clearCategoryCollapseState(); });

function buildCategoryTree(items: ProductCategoryListItem[]) {
  const itemMap = new Map<string, ProductCategoryListItem>();
  const roots: ProductCategoryListItem[] = [];
  items.forEach(item => itemMap.set(item.categoryId, { ...item, children: undefined }));
  itemMap.forEach(item => {
    const parent = itemMap.get(item.parentId);
    if (item.parentId === ROOT_PARENT_ID || !parent) {
      roots.push(item);
    } else {
      parent.children = [...(parent.children || []), item];
    }
  });
  return roots;
}

function flattenCategoryTree(tree: ProductCategoryListItem[]) {
  const result: ProductCategoryListItem[] = [];
  const walk = (items: ProductCategoryListItem[]) => {
    items.forEach(item => {
      result.push(item);
      if (item.children?.length) walk(item.children);
    });
  };
  walk(tree);
  return result;
}

function flattenVisibleCategoryTree(tree: ProductCategoryListItem[], level = 0): VisibleCategoryRow[] {
  const result: VisibleCategoryRow[] = [];
  tree.forEach(item => {
    result.push({
      ...item,
      children: undefined,
      level,
      isCollapsing: collapsingCategoryIds.value.has(item.categoryId),
    });
    if (item.children?.length && (expandedCategoryIds.value.has(item.categoryId) || hasCollapsingCategoryDescendant(item))) {
      result.push(...flattenVisibleCategoryTree(item.children, level + 1));
    }
  });
  return result;
}

function hasCollapsingCategoryDescendant(row: ProductCategoryListItem): boolean {
  return Boolean(row.children?.some(child => collapsingCategoryIds.value.has(child.categoryId) || hasCollapsingCategoryDescendant(child)));
}

function collectExpandableCategoryIds(tree: ProductCategoryListItem[]): string[] {
  const result: string[] = [];
  tree.forEach(item => {
    if (item.children?.length) result.push(item.categoryId, ...collectExpandableCategoryIds(item.children));
  });
  return result;
}

function buildParentOptions(tree: ProductCategoryListItem[], excludedIds: Set<string>): CategoryParentOption[] {
  return tree.filter(item => !excludedIds.has(item.categoryId)).map(item => ({
    deptId: item.categoryId,
    deptName: item.categoryName,
    parentId: item.parentId,
    status: item.status,
    children: item.children ? buildParentOptions(item.children, excludedIds) : undefined,
  }));
}

function getExcludedParentIds(categoryId: string) {
  if (!categoryId) return new Set<string>();
  return new Set([categoryId, ...getCategoryDescendants(categoryId).map(item => item.categoryId)]);
}

function findCategory(categoryId: string, tree = categories.value): ProductCategoryListItem | null {
  for (const item of tree) {
    if (item.categoryId === categoryId) return item;
    if (item.children?.length) {
      const matched = findCategory(categoryId, item.children);
      if (matched) return matched;
    }
  }
  return null;
}

function getParentName(parentId: string) {
  if (parentId === ROOT_PARENT_ID) return ROOT_PARENT_LABEL;
  return flatCategories.value.find(item => item.categoryId === parentId)?.categoryName || '-';
}

function getCategoryPath(category: ProductCategoryListItem) {
  const names: string[] = [];
  let current: ProductCategoryListItem | undefined = category;
  let guard = 0;
  while (current && guard < 50) {
    names.unshift(current.categoryName);
    if (current.parentId === ROOT_PARENT_ID) break;
    current = flatCategories.value.find(item => item.categoryId === current?.parentId);
    guard += 1;
  }
  return names.join(' / ');
}

function getCategoryPathIds(categoryId: string, rows = flatCategories.value) {
  const ids: string[] = [];
  let current = rows.find(item => item.categoryId === categoryId);
  let guard = 0;
  while (current && guard < 50) {
    ids.unshift(current.categoryId);
    if (current.parentId === ROOT_PARENT_ID) break;
    current = rows.find(item => item.categoryId === current?.parentId);
    guard += 1;
  }
  return ids;
}

function getCategoryDescendants(categoryId: string, tree = categories.value) {
  const category = findCategory(categoryId, tree);
  return category?.children?.length ? flattenCategoryTree(category.children) : [];
}

function hasChildren(row: ProductCategoryListItem) {
  return Boolean(findCategory(row.categoryId)?.children?.length || row.children?.length);
}

function isBlankStatus(status: ProductCategoryQuery['status'] | null) {
  return status === '' || status === 'all' || status === null || status === undefined;
}

function isCategoryExpanded(row: ProductCategoryListItem) {
  return expandedCategoryIds.value.has(row.categoryId);
}

function clearCategoryCollapseState() {
  collapseTimers.forEach(timer => window.clearTimeout(timer));
  collapseTimers.clear();
  collapsingCategoryIds.value = new Set();
}

function cancelCollapsingCategoryIds(ids: string[]) {
  if (ids.length === 0) return;
  const nextCollapsing = new Set(collapsingCategoryIds.value);
  ids.forEach((id) => {
    const timer = collapseTimers.get(id);
    if (timer) window.clearTimeout(timer);
    collapseTimers.delete(id);
    nextCollapsing.delete(id);
  });
  collapsingCategoryIds.value = nextCollapsing;
}

function scheduleCategoryCollapseRemoval(ids: string[]) {
  ids.forEach((id) => {
    const oldTimer = collapseTimers.get(id);
    if (oldTimer) window.clearTimeout(oldTimer);
    const timer = window.setTimeout(() => {
      const nextCollapsing = new Set(collapsingCategoryIds.value);
      nextCollapsing.delete(id);
      collapsingCategoryIds.value = nextCollapsing;
      collapseTimers.delete(id);
    }, TREE_COLLAPSE_DURATION);
    collapseTimers.set(id, timer);
  });
}

function toggleCategory(row: ProductCategoryListItem) {
  if (!hasChildren(row)) return;
  const descendantIds = getCategoryDescendants(row.categoryId).map(item => item.categoryId);
  const next = new Set(expandedCategoryIds.value);
  if (next.has(row.categoryId)) {
    next.delete(row.categoryId);
    const nextCollapsing = new Set(collapsingCategoryIds.value);
    descendantIds.forEach(id => nextCollapsing.add(id));
    collapsingCategoryIds.value = nextCollapsing;
    scheduleCategoryCollapseRemoval(descendantIds);
  } else {
    cancelCollapsingCategoryIds(descendantIds);
    next.add(row.categoryId);
  }
  expandedCategoryIds.value = next;
}

const debouncedSearch = useDebounceFn(() => {
  appliedQuery.categoryName = query.categoryName?.trim() || '';
  appliedQuery.status = query.status;
  selectedIds.value = new Set();
  queryPending.value = false;
  void fetchCategories();
}, 250);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  query.categoryName = '';
  query.status = 'all';
  queryPending.value = true;
  debouncedSearch();
}

const refreshList = useListRefresh(queryBusy, queryPending, fetchCategories);

function resetForm() {
  editingCategoryId.value = '';
  form.parentId = ROOT_PARENT_ID;
  form.categoryName = '';
  form.status = 1;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  dialogVisible.value = true;
}

function openChildDialog(row: ProductCategoryListItem) {
  dialogMode.value = 'child';
  resetForm();
  form.parentId = row.categoryId;
  form.status = row.status;
  dialogVisible.value = true;
}

function openEditDialog(row: ProductCategoryListItem) {
  dialogMode.value = 'edit';
  editingCategoryId.value = row.categoryId;
  form.parentId = row.parentId;
  form.categoryName = row.categoryName;
  form.status = row.status;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  const categoryName = form.categoryName.trim();
  const parent = form.parentId === ROOT_PARENT_ID ? null : flatCategories.value.find(item => item.categoryId === form.parentId);
  if (!form.parentId) formErrors.parentId = '请选择上级分类';
  if (!categoryName) formErrors.categoryName = '请输入分类名称';
  else if (categoryName.length > 100) formErrors.categoryName = '分类名称不能超过 100 个字符';
  if (parent?.status === 0 && form.status === 1) formErrors.parentId = '上级分类停用时，当前分类不能设为启用';
  const duplicate = flatCategories.value.some(item =>
    item.categoryId !== editingCategoryId.value
    && item.parentId === form.parentId
    && item.categoryName.trim().toLocaleLowerCase() === categoryName.toLocaleLowerCase(),
  );
  if (duplicate) formErrors.categoryName = '同级分类名称已存在';
  return Object.keys(formErrors).length === 0;
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const payload: ProductCategoryFormPayload = { ...form, categoryName: form.categoryName.trim() };
  const currentCategory = dialogMode.value === 'edit' ? findCategory(editingCategoryId.value) : null;
  if (currentCategory?.status === 1 && payload.status === 0) {
    showConfirm(
      '确认停用分类',
      '停用后，该分类及其下级分类会同步停用，关联产品也将停用并无法用于新的采购或销售业务。是否继续？',
      '确认停用',
      'warning',
      () => persistForm(payload),
    );
    return;
  }
  await persistForm(payload);
}

async function persistForm(payload: ProductCategoryFormPayload) {
  if (formSubmitting.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'edit') {
      await updateProductCategory(editingCategoryId.value, payload);
      toast.success('分类已更新');
    } else {
      await createProductCategory(payload);
      toast.success(dialogMode.value === 'child' ? '下级分类已新增' : '分类已新增');
    }
    dialogVisible.value = false;
    void fetchCategories();
  } catch (error) {
    const message = getApiErrorMessage(error);
    if (message === '同级分类名称已存在') formErrors.categoryName = message;
  } finally {
    formSubmitting.value = false;
  }
}

function toggleSelectAll() {
  const next = new Set(selectedIds.value);
  if (allSelected.value) {
    visibleCategories.value.forEach(item => next.delete(item.categoryId));
  } else {
    visibleCategories.value.forEach(item => next.add(item.categoryId));
  }
  selectedIds.value = next;
}

function toggleSelectRow(categoryId: string) {
  const next = new Set(selectedIds.value);
  if (next.has(categoryId)) {
    next.delete(categoryId);
    getCategoryDescendants(categoryId).forEach(item => next.delete(item.categoryId));
  } else {
    next.add(categoryId);
    const descendants = getCategoryDescendants(categoryId);
    descendants.forEach(item => next.add(item.categoryId));
    if (descendants.length) {
      cancelCollapsingCategoryIds(descendants.map(item => item.categoryId));
      const expanded = new Set(expandedCategoryIds.value);
      [findCategory(categoryId), ...descendants].filter(Boolean).forEach(item => {
        if (item && hasChildren(item)) expanded.add(item.categoryId);
      });
      expandedCategoryIds.value = expanded;
    }
  }
  selectedIds.value = next;
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

function hasDisabledAncestor(categoryId: string, selectedIdSet: Set<string>) {
  return getCategoryPathIds(categoryId).filter(id => id !== categoryId).some(ancestorId => {
    if (selectedIdSet.has(ancestorId)) return false;
    return flatCategories.value.find(item => item.categoryId === ancestorId)?.status === 0;
  });
}

function confirmBatchStatus(status: ProductCategoryStatus) {
  if (selectedIds.value.size === 0) {
    toast.warning('请先选择分类');
    return;
  }
  const rows = selectedRows.value;
  const actionName = status === 1 ? '启用' : '停用';
  if (status === 1) {
    const selectedIdSet = new Set(rows.map(item => item.categoryId));
    const blocked = rows.find(item => hasDisabledAncestor(item.categoryId, selectedIdSet));
    if (blocked) {
      toast.warning(`请先启用「${getParentName(blocked.parentId)}」，再启用「${blocked.categoryName}」`);
      return;
    }
  }
  const description = status === 0
    ? '停用后，所选分类及其下级分类会同步停用，关联产品也将停用并无法用于新的采购或销售业务。是否继续？'
    : `确认启用已选的 ${rows.length} 个分类吗？`;
  showConfirm(
    `批量${actionName}`,
    description,
    actionName,
    'warning',
    async () => {
      try {
        await batchUpdateProductCategoryStatus({ categoryIds: rows.map(item => item.categoryId), status });
        toast.success(`已批量${actionName}`);
        void fetchCategories();
      } catch {
      }
    },
  );
}

function showBackendActionError(error: unknown) {
  if (error && typeof error === 'object' && 'response' in error) return;
  const message = getApiErrorMessage(error);
  if (message) toast.warning(message);
}

function confirmDelete(row: ProductCategoryListItem) {
  const risks = [
    hasChildren(row) ? '存在下级分类' : '',
    row.productCount > 0 ? `已关联 ${row.productCount} 个产品` : '',
  ].filter(Boolean);
  const description = risks.length > 0
    ? `该分类当前显示${risks.join('、')}，最终以后端校验为准。确认提交删除请求吗？`
    : `确认删除分类「${row.categoryName}」吗？`;
  showConfirm('删除分类', description, '删除', 'destructive', async () => {
    try {
      await deleteProductCategory(row.categoryId);
      toast.success('分类已删除');
      void fetchCategories();
    } catch (error) {
      showBackendActionError(error);
    }
  });
}

function confirmBatchDelete() {
  if (selectedIds.value.size === 0) {
    toast.warning('请先选择分类');
    return;
  }
  const rows = selectedRows.value;
  const blockedCount = rows.filter(item => hasChildren(item) || item.productCount > 0).length;
  const description = blockedCount > 0
    ? `已选分类中有 ${blockedCount} 个当前显示存在下级分类或关联产品，最终以后端校验为准。确认提交批量删除请求吗？`
    : `确认删除已选的 ${rows.length} 个分类吗？`;
  showConfirm('批量删除', description, '删除', 'destructive', async () => {
    try {
      await batchDeleteProductCategories({ categoryIds: rows.map(item => item.categoryId) });
      toast.success('已批量删除');
      void fetchCategories();
    } catch (error) {
      showBackendActionError(error);
    }
  });
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">产品分类</h1>
        <p class="page-description">维护产品分类层级、启用状态和产品归属统计</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" aria-label="产品分类数据汇总" />

    <ListFilterPanel grid-class="filter-grid--depts" aria-label="产品分类筛选">
        <div class="space-y-1">
          <Label class="text-xs">分类名称</Label>
          <Input v-model="query.categoryName" placeholder="如 食品饮料" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1">
          <Label class="text-xs">状态</Label>
          <AnchoredSelect v-model="query.status" :options="statusFilterOptions" placeholder="全部状态" />
        </div>
        <template #actions>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
        </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">分类列表</strong>
          <span class="text-xs" :class="selectedIds.size ? 'text-primary' : 'text-muted-foreground'">已选 {{ selectedIds.size }} 项</span>
        </div>
        <div class="table-toolbar__actions">
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增分类</Button></span></TooltipTrigger>
            <TooltipContent>创建新的产品分类</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchStatus(1)">批量启用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择分类' : '启用已选分类' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchStatus(0)">批量停用</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择分类' : '停用已选分类' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="selectedIds.size === 0 || actionSubmitting" @click="confirmBatchDelete">删除</Button></span></TooltipTrigger>
            <TooltipContent>{{ selectedIds.size === 0 ? '请先选择分类' : '删除已选分类' }}</TooltipContent>
          </Tooltip>
          <Tooltip>
            <TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger>
            <TooltipContent>重新加载分类列表</TooltipContent>
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
              <TableHead class="w-[46px]"><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead>
              <TableHead>分类</TableHead>
              <TableHead class="text-center">上级分类</TableHead>
              <TableHead>层级路径</TableHead>
              <TableHead class="text-center">产品数量</TableHead>
              <TableHead class="text-center">状态</TableHead>
              <TableHead class="text-center">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody v-if="loading">
            <TableRow>
              <TableCell colspan="7" class="text-center text-muted-foreground py-8">加载中...</TableCell>
            </TableRow>
          </TableBody>
          <TableBody v-else-if="visibleCategories.length === 0">
            <TableRow>
              <TableCell colspan="7" class="text-center text-muted-foreground py-8">暂无数据</TableCell>
            </TableRow>
          </TableBody>
          <TreeTableBody v-else>
            <TableRow
              v-for="row in visibleCategories"
              :key="row.categoryId"
              :data-tree-row-key="row.categoryId"
              :data-tree-row-collapsing="row.isCollapsing ? 'true' : undefined"
              :class="{ 'bg-muted/50': selectedIds.has(row.categoryId) }"
            >
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <Checkbox :model-value="selectedIds.has(row.categoryId)" @update:model-value="toggleSelectRow(row.categoryId)" />
                </div>
              </TableCell>
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <div class="flex items-center gap-1" :style="{ paddingLeft: `${row.level * 22}px` }">
                  <button
                    v-if="hasChildren(row)"
                    type="button"
                    class="inline-flex h-6 w-5 items-center justify-center text-muted-foreground transition-colors hover:text-primary"
                    :aria-label="isCategoryExpanded(row) ? '收起当前分类' : '展开当前分类'"
                    @click.stop="toggleCategory(row)"
                  >
                    <ChevronRight
                      class="h-3.5 w-3.5 transition-transform duration-200 ease-out"
                      :class="{ 'rotate-90': isCategoryExpanded(row) }"
                    />
                  </button>
                  <span v-else class="w-5 h-6 shrink-0" />
                    <strong class="text-sm">{{ row.categoryName }}</strong>
                  </div>
                </div>
              </TableCell>
              <TableCell class="text-center text-xs text-muted-foreground">
                <div class="tree-table-cell-reveal">{{ getParentName(row.parentId) }}</div>
              </TableCell>
              <TableCell>
                <div class="tree-table-cell-reveal">
                  <span class="text-xs text-muted-foreground truncate">{{ getCategoryPath(row) }}</span>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="tree-table-cell-reveal">
                  <Badge variant="outline" class="min-w-[38px] justify-center font-semibold" :class="row.productCount === 0 ? 'text-muted-foreground' : 'border-blue-200 bg-blue-50 text-blue-700'">{{ row.productCount }}</Badge>
                </div>
              </TableCell>
              <TableCell class="text-center">
                <div class="tree-table-cell-reveal">
                  <Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">{{ row.status === 1 ? '启用' : '停用' }}</Badge>
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

      <div class="flex min-h-[52px] items-center justify-between border-t border-border px-4">
        <span class="text-xs text-muted-foreground">命中 {{ filteredListResponse.length }} 条</span>
        <span class="text-xs text-muted-foreground">层级视图</span>
      </div>
    </div>

    <Dialog v-model:open="dialogVisible" @update:open="(value: boolean) => { if (!value) resetForm() }">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>{{ dialogTitle }}</DialogTitle>
          <DialogDescription>填写产品分类信息</DialogDescription>
        </DialogHeader>
        <div class="space-y-4 py-2">
          <div class="space-y-1">
            <Label>上级分类 <span class="text-destructive">*</span></Label>
            <TreeSelect v-model="form.parentId" :options="parentOptions" placeholder="请选择上级分类" />
            <p v-if="formErrors.parentId" class="text-xs text-destructive">{{ formErrors.parentId }}</p>
          </div>
          <div class="space-y-1">
            <Label>分类名称 <span class="text-destructive">*</span></Label>
            <Input v-model="form.categoryName" maxlength="100" placeholder="请输入分类名称" />
            <p v-if="formErrors.categoryName" class="text-xs text-destructive">{{ formErrors.categoryName }}</p>
          </div>
          <div class="space-y-1">
            <Label>状态 <span class="text-destructive">*</span></Label>
            <RadioGroup :model-value="String(form.status)" class="flex gap-4" @update:model-value="form.status = Number($event) as ProductCategoryStatus">
              <div class="flex items-center gap-2">
                <RadioGroupItem id="category-status-1" value="1" />
                <Label for="category-status-1" class="cursor-pointer">启用</Label>
              </div>
              <div class="flex items-center gap-2">
                <RadioGroupItem id="category-status-0" value="0" />
                <Label for="category-status-0" class="cursor-pointer">停用</Label>
              </div>
            </RadioGroup>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button>
          <Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

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
