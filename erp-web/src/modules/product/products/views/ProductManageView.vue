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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import { listProductCategories } from '../../categories/api';
import type { ProductCategoryListItem } from '../../categories/types';
import {
  batchDeleteProducts,
  batchUpdateProductStatus,
  createProduct,
  deleteProduct,
  listProducts,
  updateProduct,
  updateProductStatus,
} from '../api';
import type { ProductFormPayload, ProductListItem, ProductQuery, ProductStatus } from '../types';

const statusFilterOptions = [
  { value: 'all', label: '全部状态' },
  { value: 1, label: '启用' },
  { value: 0, label: '停用' },
];
const unitOptions = ['件', '箱', '盒', '包', '瓶', '卷', '本', '卡', '个', 'kg'];
const unitSelectOptions = unitOptions.map(unit => ({ value: unit, label: unit }));
const uncategorizedValue = '__uncategorized__';

const products = ref<ProductListItem[]>([]);
const categories = ref<ProductCategoryListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const selectedIds = ref<Set<string>>(new Set());
const dialogVisible = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingProductId = ref('');
const editingProductCode = ref('');
const editingOriginalStatus = ref<ProductStatus>(1);
let fetchSequence = 0;

const query = reactive<ProductQuery>({
  productCode: '', productName: '', brandName: '', barcode: '',
  categoryId: 'all', status: 'all', pageNum: 1, pageSize: 10,
});
const form = reactive<ProductFormPayload>({
  productName: '', categoryId: null, brandName: '', unitName: '件', specification: '', barcode: null,
  referencePurchasePrice: 0, referenceSalePrice: 0, safetyStockQty: 0, status: 1, remark: '',
});
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false, title: '', description: '', confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as (() => void | Promise<void>),
});

const queryBusy = computed(() => queryPending.value || loading.value);
const enabledCount = computed(() => products.value.filter(item => item.status === 1).length);
const lowStockConfigCount = computed(() => products.value.filter(item => item.safetyStockQty > 0).length);
const categoryCount = computed(() => new Set(products.value.map(item => item.categoryId).filter(Boolean)).size);
const selectedRows = computed(() => products.value.filter(item => selectedIds.value.has(item.productId)));
const allSelected = computed(() => products.value.length > 0 && products.value.every(item => selectedIds.value.has(item.productId)));
const categoryOptions = computed(() => categories.value.map(item => ({
  value: item.categoryId,
  label: buildCategoryPath(item.categoryId),
  disabled: item.status === 0,
})));
const categoryFilterOptions = computed(() => [
  { value: 'all', label: '全部分类' },
  ...categoryOptions.value.map(item => ({ value: item.value, label: item.label })),
]);
const formCategoryValue = computed({
  get: () => form.categoryId || uncategorizedValue,
  set: (value: string | number) => {
    form.categoryId = value === uncategorizedValue ? null : String(value);
  },
});
const formCategoryOptions = computed(() => [
  { value: uncategorizedValue, label: '未分类' },
  ...categoryOptions.value.map(item => ({
    ...item,
    label: `${item.label}${item.disabled ? '（停用）' : ''}`,
  })),
]);

function buildCategoryPath(categoryId: string) {
  const path: string[] = [];
  const visited = new Set<string>();
  let current = categories.value.find(item => item.categoryId === categoryId);
  while (current && !visited.has(current.categoryId)) {
    visited.add(current.categoryId);
    path.unshift(current.categoryName);
    current = current.parentId === '0' ? undefined : categories.value.find(item => item.categoryId === current?.parentId);
  }
  return path.join(' / ');
}

async function fetchProducts() {
  const sequence = ++fetchSequence;
  loading.value = true;
  try {
    const result = await listProducts({ ...query });
    if (sequence !== fetchSequence) return;
    products.value = result.records;
    total.value = result.total;
    selectedIds.value = new Set();
  } finally {
    if (sequence === fetchSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

async function fetchCategories() {
  categories.value = await listProductCategories();
}

onMounted(() => {
  fetchCategories();
  fetchProducts();
});

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchProducts();
}, 250);
const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchProducts();
}, 180);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  query.productCode = '';
  query.productName = '';
  query.brandName = '';
  query.barcode = '';
  query.categoryId = 'all';
  query.status = 'all';
  query.pageNum = 1;
  fetchProducts();
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

const refreshList = useListRefresh(queryBusy, queryPending, fetchProducts);

function toggleSelectAll(value: boolean | 'indeterminate') {
  selectedIds.value = value === true ? new Set(products.value.map(item => item.productId)) : new Set();
}

function toggleSelect(productId: string, value: boolean | 'indeterminate') {
  const next = new Set(selectedIds.value);
  value === true ? next.add(productId) : next.delete(productId);
  selectedIds.value = next;
}

function resetForm() {
  Object.assign(form, {
    productName: '', categoryId: null, brandName: '', unitName: '件', specification: '', barcode: null,
    referencePurchasePrice: 0, referenceSalePrice: 0, safetyStockQty: 0, status: 1, remark: '',
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  editingProductId.value = '';
  editingProductCode.value = '';
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: ProductListItem) {
  dialogMode.value = 'edit';
  editingProductId.value = row.productId;
  editingProductCode.value = row.productCode;
  editingOriginalStatus.value = row.status;
  Object.assign(form, {
    productName: row.productName,
    categoryId: row.categoryId,
    brandName: row.brandName,
    unitName: row.unitName,
    specification: row.specification,
    barcode: row.barcode,
    referencePurchasePrice: row.referencePurchasePrice,
    referenceSalePrice: row.referenceSalePrice,
    safetyStockQty: row.safetyStockQty,
    status: row.status,
    remark: row.remark,
  });
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  dialogVisible.value = true;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.productName.trim()) formErrors.productName = '请输入产品名称';
  else if (form.productName.trim().length > 200) formErrors.productName = '产品名称不能超过 200 个字符';
  if (!form.unitName.trim()) formErrors.unitName = '请输入单位名称';
  else if (form.unitName.trim().length > 32) formErrors.unitName = '单位名称不能超过 32 个字符';
  if (form.brandName.trim().length > 100) formErrors.brandName = '品牌名称不能超过 100 个字符';
  if (form.specification.trim().length > 255) formErrors.specification = '规格型号不能超过 255 个字符';
  if ((form.barcode || '').trim().length > 64) formErrors.barcode = '条码不能超过 64 个字符';
  for (const [key, label] of [['referencePurchasePrice', '参考采购价'], ['referenceSalePrice', '参考销售价'], ['safetyStockQty', '安全库存']] as const) {
    if (!Number.isFinite(Number(form[key])) || Number(form[key]) < 0) formErrors[key] = `${label}不能小于 0`;
  }
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  const selectedCategory = categories.value.find(item => item.categoryId === form.categoryId);
  if (form.status === 1 && selectedCategory?.status === 0) formErrors.categoryId = '停用分类下不能保存启用产品';
  return Object.keys(formErrors).length === 0;
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  const payload: ProductFormPayload = {
    ...form,
    productName: form.productName.trim(),
    brandName: form.brandName.trim(),
    unitName: form.unitName.trim(),
    specification: form.specification.trim(),
    barcode: form.barcode?.trim() || null,
    referencePurchasePrice: Number(form.referencePurchasePrice),
    referenceSalePrice: Number(form.referenceSalePrice),
    safetyStockQty: Number(form.safetyStockQty),
    remark: form.remark.trim(),
  };
  if (dialogMode.value === 'edit' && editingOriginalStatus.value === 1 && payload.status === 0) {
    showConfirm('确认停用产品', productDisableWarning, '确认停用', 'warning', () => persistForm(payload));
    return;
  }
  await persistForm(payload);
}

async function persistForm(payload: ProductFormPayload) {
  if (formSubmitting.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      await createProduct(payload);
      toast.success('产品已创建');
    } else {
      await updateProduct(editingProductId.value, payload);
      toast.success('产品已更新');
    }
    dialogVisible.value = false;
    fetchProducts();
  } catch (error) {
    const message = getApiErrorMessage(error);
    if (message) toast.warning(message);
  } finally {
    formSubmitting.value = false;
  }
}

const productDisableWarning = '停用后，该产品不能用于新建采购单或销售单，历史业务数据不受影响。是否继续？';

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { title, description, confirmText, variant, onConfirm, open: true });
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

function handleStatusChange(row: ProductListItem, status: ProductStatus) {
  const action = status === 1 ? '启用' : '停用';
  showConfirm(`${action}产品`, status === 0 ? productDisableWarning : `确认启用「${row.productName}」吗？`, action, status === 0 ? 'warning' : 'default', async () => {
    try {
      await updateProductStatus(row.productId, status);
      toast.success(`产品已${action}`);
      fetchProducts();
    } catch (error) {
      toast.warning(getApiErrorMessage(error));
    }
  });
}

function handleDelete(row: ProductListItem) {
  showConfirm('删除产品', `确认删除「${row.productName}」吗？已被库存或业务单据引用的产品将无法删除。`, '删除', 'destructive', async () => {
    try {
      await deleteProduct(row.productId);
      toast.success('产品已删除');
      fetchProducts();
    } catch (error) {
      toast.warning(getApiErrorMessage(error));
    }
  });
}

function handleBatchStatus(status: ProductStatus) {
  if (!selectedIds.value.size) return;
  const action = status === 1 ? '启用' : '停用';
  showConfirm(`批量${action}`, status === 0 ? productDisableWarning : `确认启用已选的 ${selectedIds.value.size} 个产品吗？`, action, status === 0 ? 'warning' : 'default', async () => {
    try {
      await batchUpdateProductStatus({ productIds: [...selectedIds.value], status });
      toast.success(`已批量${action}`);
      fetchProducts();
    } catch (error) {
      toast.warning(getApiErrorMessage(error));
    }
  });
}

function handleBatchDelete() {
  if (!selectedIds.value.size) return;
  showConfirm('批量删除', `确认删除已选的 ${selectedIds.value.size} 个产品吗？存在业务引用时整批操作将被拒绝。`, '删除', 'destructive', async () => {
    try {
      await batchDeleteProducts({ productIds: [...selectedIds.value] });
      toast.success('产品已批量删除');
      fetchProducts();
    } catch (error) {
      toast.warning(getApiErrorMessage(error));
    }
  });
}

function formatMoney(value: number) {
  return `¥${value.toFixed(2)}`;
}

function formatQty(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
}
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">产品档案</h1>
        <p class="page-description">维护采购、销售与库存共同使用的产品主数据</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">产品总数</span><strong class="mt-1 text-2xl">{{ total }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">当前页启用</span><strong class="mt-1 text-2xl">{{ enabledCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">当前页分类</span><strong class="mt-1 text-2xl">{{ categoryCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">已设安全库存</span><strong class="mt-1 text-2xl">{{ lowStockConfigCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--products">
        <div class="space-y-1"><Label class="text-xs">产品编码</Label><Input v-model="query.productCode" placeholder="如 P0001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">产品名称</Label><Input v-model="query.productName" placeholder="请输入产品名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">产品分类</Label><AnchoredSelect v-model="query.categoryId" :options="categoryFilterOptions" placeholder="全部分类" /></div>
        <div class="space-y-1"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusFilterOptions" placeholder="全部状态" /></div>
        <div class="space-y-1"><Label class="text-xs">品牌名称</Label><Input v-model="query.brandName" placeholder="请输入品牌名称" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">产品条码</Label><Input v-model="query.barcode" placeholder="请输入完整条码" @keyup.enter="handleSearch" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">产品列表</strong><span class="text-xs" :class="selectedIds.size ? 'text-primary' : 'text-muted-foreground'">已选 {{ selectedIds.size }} 项</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog">新增产品</Button></span></TooltipTrigger><TooltipContent>创建产品主数据</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(1)">批量启用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '启用已选产品' : '请先选择产品' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchStatus(0)">批量停用</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '停用已选产品' : '请先选择产品' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="destructive" :disabled="!selectedIds.size || actionSubmitting" @click="handleBatchDelete">删除</Button></span></TooltipTrigger><TooltipContent>{{ selectedIds.size ? '删除未被业务引用的产品' : '请先选择产品' }}</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载产品列表</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1320px] table-fixed">
          <colgroup><col class="w-[44px]" /><col class="w-[130px]" /><col class="w-[220px]" /><col class="w-[170px]" /><col class="w-[150px]" /><col class="w-[90px]" /><col class="w-[170px]" /><col class="w-[120px]" /><col class="w-[100px]" /><col class="w-[190px]" /></colgroup>
          <TableHeader><TableRow>
            <TableHead><Checkbox :model-value="allSelected" @update:model-value="toggleSelectAll" /></TableHead><TableHead>产品编码</TableHead><TableHead>产品名称</TableHead><TableHead>分类</TableHead><TableHead>品牌 / 规格</TableHead><TableHead>单位</TableHead><TableHead>参考价格</TableHead><TableHead>安全库存</TableHead><TableHead>状态</TableHead><TableHead class="text-center">操作</TableHead>
          </TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="products.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无数据</TableCell></TableRow>
            <TableRow v-for="row in products" v-else :key="row.productId">
              <TableCell><Checkbox :model-value="selectedIds.has(row.productId)" @update:model-value="toggleSelect(row.productId, $event)" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-1 text-xs font-medium">{{ row.productCode }}</code></TableCell>
              <TableCell><div class="flex flex-col"><span class="font-medium">{{ row.productName }}</span><span class="truncate text-xs text-muted-foreground">{{ row.barcode || '暂无条码' }}</span></div></TableCell>
              <TableCell><Badge variant="outline">{{ row.categoryName || '未分类' }}</Badge></TableCell>
              <TableCell><div class="flex flex-col"><span>{{ row.brandName || '无品牌' }}</span><span class="truncate text-xs text-muted-foreground">{{ row.specification || '无规格' }}</span></div></TableCell>
              <TableCell>{{ row.unitName }}</TableCell>
              <TableCell><div class="flex flex-col text-xs"><span>采 {{ formatMoney(row.referencePurchasePrice) }}</span><span class="text-muted-foreground">销 {{ formatMoney(row.referenceSalePrice) }}</span></div></TableCell>
              <TableCell>{{ formatQty(row.safetyStockQty) }}</TableCell>
              <TableCell><Badge variant="outline" :class="row.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-500'">{{ row.status === 1 ? '启用' : '停用' }}</Badge></TableCell>
              <TableCell class="text-center"><div class="flex justify-center gap-1"><Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)">编辑</Button><Button size="sm" variant="ghost" :class="row.status === 1 ? 'text-amber-700' : 'text-primary'" :disabled="actionSubmitting" @click="handleStatusChange(row, row.status === 1 ? 0 : 1)">{{ row.status === 1 ? '停用' : '启用' }}</Button><Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="handleDelete(row)">删除</Button></div></TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>

      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="dialogVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[min(720px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[760px]">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增产品' : '编辑产品' }}</DialogTitle><DialogDescription>产品档案将被采购、销售和库存业务共同引用，请准确维护主数据。</DialogDescription></DialogHeader>
        <ScrollArea class="dialog-scroll-area min-h-0 flex-1 pr-3">
          <div class="grid grid-cols-2 gap-4 py-2 max-sm:grid-cols-1">
            <div class="space-y-1"><Label>产品编码</Label><Input data-product-code-display :model-value="dialogMode === 'create' ? '保存后由系统生成' : editingProductCode" readonly class="bg-muted/55 text-muted-foreground" /><p class="text-xs text-muted-foreground">系统生成，创建后不可修改</p></div>
            <div class="space-y-1"><Label>产品名称 <span class="text-destructive">*</span></Label><Input v-model="form.productName" maxlength="200" placeholder="请输入产品名称" :aria-invalid="Boolean(formErrors.productName)" /><p v-if="formErrors.productName" class="text-xs text-destructive">{{ formErrors.productName }}</p></div>
            <div class="space-y-1"><Label>产品分类</Label><AnchoredSelect v-model="formCategoryValue" :options="formCategoryOptions" placeholder="请选择分类" :invalid="Boolean(formErrors.categoryId)" /><p v-if="formErrors.categoryId" class="text-xs text-destructive">{{ formErrors.categoryId }}</p></div>
            <div class="space-y-1"><Label>品牌名称</Label><Input v-model="form.brandName" maxlength="100" placeholder="请输入品牌名称" :aria-invalid="Boolean(formErrors.brandName)" /><p v-if="formErrors.brandName" class="text-xs text-destructive">{{ formErrors.brandName }}</p></div>
            <div class="space-y-1"><Label>单位名称 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.unitName" :options="unitSelectOptions" :invalid="Boolean(formErrors.unitName)" /><p v-if="formErrors.unitName" class="text-xs text-destructive">{{ formErrors.unitName }}</p></div>
            <div class="space-y-1"><Label>规格型号</Label><Input v-model="form.specification" maxlength="255" placeholder="请输入规格型号" :aria-invalid="Boolean(formErrors.specification)" /><p v-if="formErrors.specification" class="text-xs text-destructive">{{ formErrors.specification }}</p></div>
            <div class="space-y-1"><Label>产品条码</Label><Input :model-value="form.barcode || ''" maxlength="64" placeholder="请输入条码" :aria-invalid="Boolean(formErrors.barcode)" @update:model-value="form.barcode = String($event) || null" /><p v-if="formErrors.barcode" class="text-xs text-destructive">{{ formErrors.barcode }}</p></div>
            <div class="space-y-1"><Label>安全库存</Label><Input v-model.number="form.safetyStockQty" type="number" min="0" step="0.0001" :aria-invalid="Boolean(formErrors.safetyStockQty)" /><p v-if="formErrors.safetyStockQty" class="text-xs text-destructive">{{ formErrors.safetyStockQty }}</p></div>
            <div class="space-y-1"><Label>参考采购价</Label><div class="relative"><span data-currency-prefix class="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-muted-foreground">￥</span><Input v-model.number="form.referencePurchasePrice" class="pl-8" type="number" min="0" step="0.01" :aria-invalid="Boolean(formErrors.referencePurchasePrice)" /></div><p v-if="formErrors.referencePurchasePrice" class="text-xs text-destructive">{{ formErrors.referencePurchasePrice }}</p></div>
            <div class="space-y-1"><Label>参考销售价</Label><div class="relative"><span data-currency-prefix class="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-muted-foreground">￥</span><Input v-model.number="form.referenceSalePrice" class="pl-8" type="number" min="0" step="0.01" :aria-invalid="Boolean(formErrors.referenceSalePrice)" /></div><p v-if="formErrors.referenceSalePrice" class="text-xs text-destructive">{{ formErrors.referenceSalePrice }}</p></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>启用状态 <span class="text-destructive">*</span></Label><RadioGroup :model-value="String(form.status)" class="flex gap-5" @update:model-value="form.status = Number($event) as ProductStatus"><div class="flex items-center gap-2"><RadioGroupItem id="product-status-1" value="1" /><Label for="product-status-1" class="cursor-pointer font-normal">启用</Label></div><div class="flex items-center gap-2"><RadioGroupItem id="product-status-0" value="0" /><Label for="product-status-0" class="cursor-pointer font-normal">停用</Label></div></RadioGroup></div>
            <div class="col-span-2 space-y-1 max-sm:col-span-1"><Label>备注</Label><Textarea v-model="form.remark" maxlength="500" rows="3" placeholder="补充产品采购、销售或仓储注意事项" :aria-invalid="Boolean(formErrors.remark)" /><div class="flex justify-between text-xs"><span :class="formErrors.remark ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors.remark || '选填，最多 500 个字符' }}</span><span class="text-muted-foreground">{{ form.remark.length }}/500</span></div></div>
          </div>
        </ScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="dialogVisible = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中...' : '保存' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" :variant="confirmState.variant" :loading="actionSubmitting || formSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
