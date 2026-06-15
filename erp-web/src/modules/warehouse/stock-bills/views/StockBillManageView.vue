<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { Eye, Pencil, Plus, Trash2 } from 'lucide-vue-next';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import { listProducts } from '@/modules/product/products/api';
import type { ProductListItem } from '@/modules/product/products/types';
import { listWarehouses } from '../../warehouses/api';
import type { WarehouseListItem } from '../../warehouses/types';
import {
  cancelStockBill,
  confirmStockBill,
  createStockBill,
  getStockBillDetail,
  listStockBills,
  updateStockBill,
} from '../api';
import type {
  ManualStockBillType,
  StockBillCreatePayload,
  StockBillDetail,
  StockBillDraftItemPayload,
  StockBillListItem,
  StockBillQuery,
  StockBillStatus,
  StockBillSummary,
  StockBillType,
  StockBillUpdatePayload,
} from '../types';

interface DraftFormItem extends StockBillDraftItemPayload {
  key: string;
}

const emptySummary = (): StockBillSummary => ({ stockBillCount: 0, inboundCount: 0, outboundCount: 0, confirmedCount: 0 });
const authStore = useAuthStore();
const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const records = ref<StockBillListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<StockBillDetail | null>(null);
const formVisible = ref(false);
const formLoading = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingDetail = ref<StockBillDetail | null>(null);
const warehouses = ref<WarehouseListItem[]>([]);
const products = ref<ProductListItem[]>([]);
const warehouseOptions = ref<Array<{ value: string; label: string }>>([{ value: 'all', label: '全部仓库' }]);
const formWarehouseOptions = ref<Array<{ value: string; label: string }>>([]);
const productOptions = ref<Array<{ value: string; label: string }>>([]);
const formErrors = reactive<Record<string, string>>({});
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'destructive' | 'warning',
  onConfirm: async () => {},
});
const query = reactive<StockBillQuery>({
  billNo: '',
  sourceNo: '',
  warehouseId: 'all',
  billType: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});
const form = reactive<{ billType: ManualStockBillType; sourceNo: string; warehouseId: string; manualReason: string; remark: string; items: DraftFormItem[] }>({
  billType: 'ADJUST_IN',
  sourceNo: '',
  warehouseId: '',
  manualReason: '',
  remark: '',
  items: [],
});

const queryBusy = computed(() => loading.value || queryPending.value);
const formBillType = computed<StockBillType>(() => dialogMode.value === 'edit' && editingDetail.value ? editingDetail.value.billType : form.billType);
const structureEditable = computed(() => dialogMode.value === 'create' || editingDetail.value?.entryMode !== 'SOURCE_GENERATED');
const qualityFieldsVisible = computed(() => formBillType.value === 'PURCHASE_IN' || formBillType.value === 'SALES_RETURN');
const isAdjustmentForm = computed(() => formBillType.value === 'ADJUST_IN' || formBillType.value === 'ADJUST_OUT');
const isManualForm = computed(() => dialogMode.value === 'create' || editingDetail.value?.entryMode !== 'SOURCE_GENERATED');
const billTypeOptions: Array<{ value: StockBillType | 'all'; label: string }> = [
  { value: 'all', label: '全部类型' },
  { value: 'PURCHASE_IN', label: '采购入库' },
  { value: 'SALES_OUT', label: '销售出库' },
  { value: 'PURCHASE_RETURN', label: '采购退货出库' },
  { value: 'SALES_RETURN', label: '销售退货入库' },
  { value: 'ADJUST_IN', label: '库存调整入库' },
  { value: 'ADJUST_OUT', label: '库存调整出库' },
];
const manualBillTypeOptions: Array<{ value: ManualStockBillType; label: string }> = [
  { value: 'PURCHASE_IN', label: '补录采购入库' },
  { value: 'SALES_OUT', label: '补录销售出库' },
  { value: 'PURCHASE_RETURN', label: '补录采购退货出库' },
  { value: 'SALES_RETURN', label: '补录销售退货入库' },
  { value: 'ADJUST_IN', label: '库存调整入库' },
  { value: 'ADJUST_OUT', label: '库存调整出库' },
];
const statusOptions: Array<{ value: StockBillStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CANCELLED', label: '已取消' },
];
const billTypeMap: Record<StockBillType, { label: string; direction: 'in' | 'out'; className: string }> = {
  PURCHASE_IN: { label: '采购入库', direction: 'in', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  SALES_OUT: { label: '销售出库', direction: 'out', className: 'border-blue-200 bg-blue-50 text-blue-700' },
  PURCHASE_RETURN: { label: '采购退货出库', direction: 'out', className: 'border-blue-200 bg-blue-50 text-blue-700' },
  SALES_RETURN: { label: '销售退货入库', direction: 'in', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  ADJUST_IN: { label: '调整入库', direction: 'in', className: 'border-teal-200 bg-teal-50 text-teal-700' },
  ADJUST_OUT: { label: '调整出库', direction: 'out', className: 'border-cyan-200 bg-cyan-50 text-cyan-700' },
};
const statusMap: Record<StockBillStatus, { label: string; className: string }> = {
  DRAFT: { label: '草稿', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  CONFIRMED: { label: '已确认', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  CANCELLED: { label: '已取消', className: 'border-slate-200 bg-slate-50 text-slate-600' },
};
const sourceTypeMap = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  PURCHASE_RETURN_ORDER: '采购退货单',
  SALES_RETURN_ORDER: '销售退货单',
  STOCK_ADJUST: '库存调整单',
} as const;
const entryModeMap = {
  SOURCE_GENERATED: { label: '来源生成', className: 'border-slate-200 bg-slate-50 text-slate-600' },
  MANUAL_SUPPLEMENT: { label: '手工补录', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  MANUAL_ADJUSTMENT: { label: '手工调整', className: 'border-cyan-200 bg-cyan-50 text-cyan-700' },
} as const;

function newDraftItem(): DraftFormItem {
  return { key: `${Date.now()}-${Math.random()}`, productId: '', quantity: 1, qualifiedQty: 0, defectiveQty: 0, remark: '' };
}

async function loadFormOptions() {
  try {
    const [warehousePage, productPage] = await Promise.all([
      listWarehouses({ pageNum: 1, pageSize: 100 }),
      listProducts({ status: 1, pageNum: 1, pageSize: 100 }),
    ]);
    warehouses.value = warehousePage.records;
    products.value = productPage.records;
    warehouseOptions.value = [
      { value: 'all', label: '全部仓库' },
      ...warehousePage.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` })),
    ];
    formWarehouseOptions.value = warehousePage.records
      .filter(item => item.status === 1)
      .map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
    productOptions.value = productPage.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}（${item.unitName}）` }));
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '出入库表单选项加载失败');
  }
}

async function fetchRecords() {
  const currentSequence = ++requestSequence.value;
  loading.value = true;
  try {
    const page = await listStockBills(query);
    if (currentSequence !== requestSequence.value) return;
    records.value = page.records;
    total.value = page.total;
    Object.assign(summary, page.summary);
  } catch (error) {
    if (currentSequence === requestSequence.value) toast.warning(getApiErrorMessage(error) || '出入库记录查询失败');
  } finally {
    if (currentSequence === requestSequence.value) {
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
  Object.assign(query, { billNo: '', sourceNo: '', warehouseId: 'all', billType: 'all', status: 'all', pageNum: 1 });
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

async function openDetail(row: StockBillListItem) {
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getStockBillDetail(row.stockBillId);
  } catch (error) {
    detailVisible.value = false;
    toast.warning(getApiErrorMessage(error) || '出入库详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

function resetForm() {
  Object.assign(form, { billType: 'ADJUST_IN', sourceNo: '', warehouseId: formWarehouseOptions.value[0]?.value || '', manualReason: '', remark: '', items: [newDraftItem()] });
  editingDetail.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

async function openEditDialog(row: StockBillListItem) {
  if (row.status !== 'DRAFT') return;
  dialogMode.value = 'edit';
  formVisible.value = true;
  formLoading.value = true;
  editingDetail.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  try {
    const current = await getStockBillDetail(row.stockBillId);
    if (current.status !== 'DRAFT') throw new Error('只有草稿状态的出入库流水可以编辑');
    editingDetail.value = current;
    form.billType = current.billType;
    form.sourceNo = current.sourceNo;
    form.warehouseId = current.warehouseId;
    form.manualReason = current.manualReason;
    form.remark = current.remark;
    form.items = current.items.map(item => ({
      key: item.stockBillItemId,
      stockBillItemId: item.stockBillItemId,
      productId: item.productId,
      quantity: item.quantity,
      qualifiedQty: item.qualifiedQty,
      defectiveQty: item.defectiveQty,
      remark: item.remark,
    }));
  } catch (error) {
    formVisible.value = false;
    toast.warning(getApiErrorMessage(error) || '草稿加载失败');
  } finally {
    formLoading.value = false;
  }
}

function addFormItem() {
  form.items.push(newDraftItem());
}

function removeFormItem(index: number) {
  if (form.items.length <= 1) return;
  form.items.splice(index, 1);
}

function productLabel(item: DraftFormItem) {
  const snapshot = editingDetail.value?.items.find(detailItem => detailItem.stockBillItemId === item.stockBillItemId);
  if (snapshot) return `${snapshot.productCode} ${snapshot.productName}（${snapshot.unitName}）`;
  const product = products.value.find(option => option.productId === item.productId);
  return product ? `${product.productCode} ${product.productName}（${product.unitName}）` : item.productId;
}

function itemQuantityPrecision(item: DraftFormItem) {
  const snapshot = editingDetail.value?.items.find(detailItem => detailItem.stockBillItemId === item.stockBillItemId);
  return snapshot?.quantityPrecision ?? products.value.find(product => product.productId === item.productId)?.quantityPrecision ?? 0;
}

function itemQuantityStep(item: DraftFormItem) {
  return 10 ** -itemQuantityPrecision(item);
}

function itemUnitName(item: DraftFormItem) {
  const snapshot = editingDetail.value?.items.find(detailItem => detailItem.stockBillItemId === item.stockBillItemId);
  return snapshot?.unitName ?? products.value.find(product => product.productId === item.productId)?.unitName ?? '';
}

function quantityHint(item: DraftFormItem) {
  const precision = itemQuantityPrecision(item);
  const unit = itemUnitName(item);
  return `${unit ? `单位 ${unit}，` : ''}${precision === 0 ? '仅允许整数' : `最多 ${precision} 位小数`}`;
}

function matchesQuantityPrecision(value: number, precision: number) {
  return Math.abs(value * 10 ** precision - Math.round(value * 10 ** precision)) < 1e-8;
}

function clearFormError(key: string) {
  delete formErrors[key];
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (dialogMode.value === 'create' && !form.warehouseId) formErrors.warehouseId = '请选择仓库';
  if (isManualForm.value && !isAdjustmentForm.value && !form.sourceNo.trim()) formErrors.sourceNo = '请输入原业务单号，便于追溯补录来源';
  if (isManualForm.value && !form.manualReason.trim()) formErrors.manualReason = isAdjustmentForm.value ? '请填写调整原因' : '请填写补录原因';
  else if (form.manualReason.trim().length > 500) formErrors.manualReason = '原因不能超过 500 个字符';
  if (!form.items.length) formErrors.items = '至少添加一条产品明细';
  const selectedProducts = new Set<string>();
  form.items.forEach((item, index) => {
    if (!item.productId) formErrors[`items.${index}.productId`] = '请选择产品';
    else if (selectedProducts.has(item.productId)) formErrors[`items.${index}.productId`] = '同一产品不能重复添加';
    selectedProducts.add(item.productId);
    if (!Number.isFinite(item.quantity) || item.quantity <= 0) formErrors[`items.${index}.quantity`] = '数量必须大于 0';
    const precision = itemQuantityPrecision(item);
    if (!formErrors[`items.${index}.quantity`] && !matchesQuantityPrecision(item.quantity, precision)) {
      formErrors[`items.${index}.quantity`] = precision === 0 ? '该产品按整单位管理，数量必须是整数' : `该产品数量最多保留 ${precision} 位小数`;
    }
    if (qualityFieldsVisible.value) {
      if (!Number.isFinite(item.qualifiedQty) || item.qualifiedQty < 0 || !Number.isFinite(item.defectiveQty) || item.defectiveQty < 0) {
        formErrors[`items.${index}.quality`] = '质量数量不能小于 0';
      } else if (Math.abs(item.qualifiedQty + item.defectiveQty - item.quantity) > 0.0001) {
        formErrors[`items.${index}.quality`] = '合格与不合格数量之和必须等于本次数量';
      } else if (!matchesQuantityPrecision(item.qualifiedQty, precision) || !matchesQuantityPrecision(item.defectiveQty, precision)) {
        formErrors[`items.${index}.quality`] = precision === 0 ? '该产品的质量数量必须是整数' : `质量数量最多保留 ${precision} 位小数`;
      }
    }
    if (item.remark.trim().length > 500) formErrors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
  });
  if (form.remark.trim().length > 500) formErrors.remark = '凭证备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function buildItemPayloads(): StockBillDraftItemPayload[] {
  return form.items.map(item => ({
    ...(item.stockBillItemId ? { stockBillItemId: item.stockBillItemId } : {}),
    productId: item.productId,
    quantity: Number(item.quantity),
    qualifiedQty: qualityFieldsVisible.value ? Number(item.qualifiedQty) : 0,
    defectiveQty: qualityFieldsVisible.value ? Number(item.defectiveQty) : 0,
    remark: item.remark.trim(),
  }));
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'create') {
      const payload: StockBillCreatePayload = {
        billType: form.billType,
        sourceNo: form.sourceNo.trim(),
        warehouseId: form.warehouseId,
        manualReason: form.manualReason.trim(),
        items: buildItemPayloads(),
        remark: form.remark.trim(),
      };
      await createStockBill(payload);
      toast.success(isAdjustmentForm.value ? '库存调整草稿已创建' : '补录出入库草稿已创建');
    } else if (editingDetail.value) {
      const payload: StockBillUpdatePayload = { sourceNo: form.sourceNo.trim(), manualReason: form.manualReason.trim(), items: buildItemPayloads(), remark: form.remark.trim() };
      await updateStockBill(editingDetail.value.stockBillId, payload);
      toast.success('出入库草稿已更新');
    }
    formVisible.value = false;
    await fetchRecords();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '出入库草稿保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
}

function handleConfirm(row: StockBillListItem) {
  showConfirm(
    '确认出入库',
    `确认「${row.billNo}」后将立即更新库存余额，且凭证不能再编辑或直接取消。请确认仓库和产品数量无误。`,
    '确认执行',
    'warning',
    async () => {
      await confirmStockBill(row.stockBillId);
      toast.success('出入库已确认，库存余额已更新');
      await fetchRecords();
    },
  );
}

function handleCancel(row: StockBillListItem) {
  showConfirm(
    '取消出入库草稿',
    `确认取消「${row.billNo}」吗？取消后不改变库存，凭证将保留用于追溯且不能再次编辑。`,
    '确认取消',
    'destructive',
    async () => {
      await cancelStockBill(row.stockBillId);
      toast.success('出入库草稿已取消');
      await fetchRecords();
    },
  );
}

async function runConfirmAction() {
  if (actionSubmitting.value) return;
  actionSubmitting.value = true;
  try {
    await confirmState.onConfirm();
    confirmState.open = false;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '出入库状态变更失败');
  } finally {
    actionSubmitting.value = false;
  }
}

function formatQty(value: number) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value);
}

function formatChangeQty(value: number) {
  if (value === 0) return '0';
  return `${value > 0 ? '+' : ''}${formatQty(value)}`;
}

onMounted(() => {
  loadFormOptions();
  fetchRecords();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">出入库记录</h1>
        <p class="page-description">处理出入库草稿并追踪采购、销售、退货和库存调整形成的库存变动凭证</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">流水记录</span><strong class="mt-1 text-2xl">{{ summary.stockBillCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">入库记录</span><strong class="mt-1 text-2xl text-emerald-700">{{ summary.inboundCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">出库记录</span><strong class="mt-1 text-2xl text-blue-700">{{ summary.outboundCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">已确认</span><strong class="mt-1 text-2xl">{{ summary.confirmedCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--stock-bills">
        <div class="space-y-1"><Label class="text-xs">流水号</Label><Input v-model="query.billNo" placeholder="如 SB202606140001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">来源单号</Label><Input v-model="query.sourceNo" placeholder="如 PO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">仓库</Label><AnchoredSelect v-model="query.warehouseId" :options="warehouseOptions" placeholder="全部仓库" /></div>
        <div class="space-y-1"><Label class="text-xs">出入库类型</Label><AnchoredSelect v-model="query.billType" :options="billTypeOptions" placeholder="全部类型" /></div>
        <div class="space-y-1"><Label class="text-xs">状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" placeholder="全部状态" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">库存变动凭证</strong><span class="text-xs text-muted-foreground">业务单据正常生成凭证；遗漏登记时可手工补录，并保留负责人、原业务单号和补录原因</span></div>
        <div class="table-toolbar__actions">
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" :disabled="formSubmitting || actionSubmitting" @click="openCreateDialog"><Plus class="size-4" />新增出入库</Button></span></TooltipTrigger><TooltipContent>创建库存调整或补录采购、销售、退货凭证</TooltipContent></Tooltip>
          <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span></TooltipTrigger><TooltipContent>重新加载当前出入库记录</TooltipContent></Tooltip>
        </div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1650px] table-fixed">
          <colgroup><col class="w-[165px]" /><col class="w-[125px]" /><col class="w-[190px]" /><col class="w-[150px]" /><col class="w-[70px]" /><col class="w-[90px]" /><col class="w-[130px]" /><col class="w-[165px]" /><col class="w-[165px]" /><col class="w-[260px]" /></colgroup>
          <TableHeader><TableRow><TableHead>流水号</TableHead><TableHead class="text-center">出入库类型</TableHead><TableHead>来源单据</TableHead><TableHead>仓库</TableHead><TableHead class="text-center">明细数</TableHead><TableHead class="text-center">状态</TableHead><TableHead>负责人</TableHead><TableHead>创建信息</TableHead><TableHead>确认信息</TableHead><TableHead class="text-center">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && records.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="records.length === 0"><TableCell colspan="10" class="h-28 text-center text-muted-foreground">暂无符合条件的出入库记录</TableCell></TableRow>
            <TableRow v-for="row in records" v-else :key="row.stockBillId" :data-stock-bill-id="row.stockBillId">
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.billNo }}</code></TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="billTypeMap[row.billType].className">{{ billTypeMap[row.billType].label }}</Badge></TableCell>
              <TableCell><div class="flex flex-col items-start gap-1"><div class="flex items-center gap-1.5"><span class="text-xs text-muted-foreground">{{ sourceTypeMap[row.sourceType] }}</span><Badge variant="outline" :class="entryModeMap[row.entryMode].className">{{ entryModeMap[row.entryMode].label }}</Badge></div><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.sourceNo || '无来源单号' }}</code></div></TableCell>
              <TableCell class="font-medium">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center tabular-nums">{{ row.itemCount }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="statusMap[row.status].className">{{ statusMap[row.status].label }}</Badge></TableCell>
              <TableCell>{{ row.responsibleByName }}</TableCell>
              <TableCell><div class="flex flex-col gap-1"><span>{{ row.createdByName || '系统' }}</span><span class="text-xs text-muted-foreground">{{ row.createdAt }}</span></div></TableCell>
              <TableCell><div v-if="row.status === 'CONFIRMED'" class="flex flex-col gap-1"><span>{{ row.confirmedByName }}</span><span class="text-xs text-muted-foreground">{{ row.confirmedAt }}</span></div><span v-else class="text-sm text-muted-foreground">未确认</span></TableCell>
              <TableCell class="text-center">
                <div class="flex justify-center gap-1">
                  <Button size="sm" variant="ghost" class="text-primary" :disabled="actionSubmitting" @click="openDetail(row)"><Eye class="size-4" />详情</Button>
                  <template v-if="row.status === 'DRAFT'">
                    <Button size="sm" variant="ghost" :disabled="actionSubmitting" @click="openEditDialog(row)"><Pencil class="size-4" />编辑</Button>
                    <Button size="sm" variant="ghost" class="text-emerald-700" :disabled="actionSubmitting" @click="handleConfirm(row)">确认</Button>
                    <Button size="sm" variant="ghost" class="text-destructive" :disabled="actionSubmitting" @click="handleCancel(row)">取消</Button>
                  </template>
                </div>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="formVisible">
      <DialogContent :inert="confirmState.open ? '' : undefined" class="flex h-[min(820px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden sm:max-w-[1080px]">
        <DialogHeader>
          <DialogTitle>{{ dialogMode === 'create' ? '新增出入库凭证' : '编辑出入库草稿' }}</DialogTitle>
          <DialogDescription>{{ dialogMode === 'create' ? '库存调整由系统生成调整单号；采购、销售和退货补录必须填写原业务单号、原因，并由当前登录人承担补录责任。' : '只有草稿可以编辑；来源业务单据生成的草稿不能更换仓库、产品或出入库类型。' }}</DialogDescription>
        </DialogHeader>
        <div v-if="formLoading" class="flex min-h-64 flex-1 items-center justify-center gap-2 text-muted-foreground"><span class="page-loading-spinner" />草稿加载中...</div>
        <DialogScrollArea v-else>
          <div class="space-y-5 py-2">
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>流水号</Label><Input :model-value="dialogMode === 'create' ? '保存后由系统生成' : editingDetail?.billNo" readonly class="bg-muted/55 text-muted-foreground" /></div>
              <div class="space-y-1">
                <Label>出入库类型 <span class="text-destructive">*</span></Label>
                <AnchoredSelect v-if="dialogMode === 'create'" v-model="form.billType" :options="manualBillTypeOptions" placeholder="请选择类型" />
                <Input v-else :model-value="billTypeMap[formBillType].label" readonly class="bg-muted/55 text-muted-foreground" />
              </div>
              <div class="space-y-1">
                <Label>仓库 <span class="text-destructive">*</span></Label>
                <AnchoredSelect v-if="dialogMode === 'create'" v-model="form.warehouseId" :options="formWarehouseOptions" placeholder="请选择启用仓库" />
                <Input v-else :model-value="editingDetail?.warehouseName" readonly class="bg-muted/55 text-muted-foreground" />
                <p v-if="formErrors.warehouseId" class="text-xs text-destructive">{{ formErrors.warehouseId }}</p>
              </div>
            </div>

            <div v-if="isManualForm" class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1">
                <Label>{{ isAdjustmentForm ? '调整单号' : '原业务单号' }} <span v-if="!isAdjustmentForm" class="text-destructive">*</span></Label>
                <Input v-if="!isAdjustmentForm" v-model="form.sourceNo" maxlength="64" placeholder="填写线下单据、送货单或退货单号" :aria-invalid="Boolean(formErrors.sourceNo)" @update:model-value="clearFormError('sourceNo')" />
                <Input v-else :model-value="dialogMode === 'create' ? '保存后由系统生成' : form.sourceNo" readonly class="bg-muted/55 text-muted-foreground" />
                <p v-if="formErrors.sourceNo" class="text-xs text-destructive">{{ formErrors.sourceNo }}</p>
              </div>
              <div class="space-y-1"><Label>负责人</Label><Input :model-value="editingDetail?.responsibleByName || authStore.displayName" readonly class="bg-muted/55 text-muted-foreground" /><p class="text-xs text-muted-foreground">由后端按当前登录用户写入，不允许代填</p></div>
              <div class="space-y-1"><Label>{{ isAdjustmentForm ? '调整原因' : '补录原因' }} <span class="text-destructive">*</span></Label><Input v-model="form.manualReason" maxlength="500" :placeholder="isAdjustmentForm ? '说明盘点差异或调整依据' : '说明未登记原业务单据的原因'" :aria-invalid="Boolean(formErrors.manualReason)" @update:model-value="clearFormError('manualReason')" /><p v-if="formErrors.manualReason" class="text-xs text-destructive">{{ formErrors.manualReason }}</p></div>
            </div>

            <div>
              <div class="mb-2 flex items-center justify-between gap-3"><div><h3 class="text-sm font-semibold">产品明细 <span class="text-destructive">*</span></h3><p class="mt-1 text-xs text-muted-foreground">确认后才会更新库存；出库数量还会校验当前库存与锁定库存。</p></div><Button v-if="structureEditable" size="sm" variant="outline" @click="addFormItem"><Plus class="size-4" />添加产品</Button></div>
              <p v-if="formErrors.items" class="mb-2 text-xs text-destructive">{{ formErrors.items }}</p>
              <div class="space-y-3">
                <div v-for="(item, index) in form.items" :key="item.key" class="draft-item-grid rounded-lg border bg-muted/20 p-3" :class="{ 'draft-item-grid--quality': qualityFieldsVisible }">
                  <div class="space-y-1 draft-product">
                    <Label>产品 <span class="text-destructive">*</span></Label>
                    <AnchoredSelect v-if="structureEditable" v-model="item.productId" :options="productOptions" placeholder="请选择启用产品" @update:model-value="clearFormError(`items.${index}.productId`)" />
                    <Input v-else :model-value="productLabel(item)" readonly class="bg-background text-muted-foreground" />
                    <p v-if="formErrors[`items.${index}.productId`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p>
                  </div>
                  <div class="space-y-1"><Label>本次数量 <span class="text-destructive">*</span></Label><Input v-model.number="item.quantity" type="number" :min="itemQuantityStep(item)" :step="itemQuantityStep(item)" :aria-invalid="Boolean(formErrors[`items.${index}.quantity`])" @update:model-value="clearFormError(`items.${index}.quantity`)" /><p class="text-xs" :class="formErrors[`items.${index}.quantity`] ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors[`items.${index}.quantity`] || quantityHint(item) }}</p></div>
                  <template v-if="qualityFieldsVisible">
                    <div class="space-y-1"><Label>合格数量</Label><Input v-model.number="item.qualifiedQty" type="number" min="0" :step="itemQuantityStep(item)" /></div>
                    <div class="space-y-1"><Label>不合格数量</Label><Input v-model.number="item.defectiveQty" type="number" min="0" :step="itemQuantityStep(item)" /><p v-if="formErrors[`items.${index}.quality`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.quality`] }}</p></div>
                  </template>
                  <div class="space-y-1 draft-remark"><Label>明细备注</Label><Input v-model="item.remark" maxlength="500" placeholder="可填写盘点差异原因" /><p v-if="formErrors[`items.${index}.remark`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.remark`] }}</p></div>
                  <Button v-if="structureEditable" size="icon" variant="ghost" class="mt-6 text-destructive" :disabled="form.items.length <= 1" aria-label="删除产品明细" @click="removeFormItem(index)"><Trash2 class="size-4" /></Button>
                </div>
              </div>
            </div>

            <div class="space-y-1"><Label>凭证备注</Label><Textarea v-model="form.remark" maxlength="500" rows="3" placeholder="填写调整原因、盘点依据或其他说明" :aria-invalid="Boolean(formErrors.remark)" /><div class="flex justify-between text-xs"><span :class="formErrors.remark ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors.remark || '选填，最多 500 个字符' }}</span><span class="text-muted-foreground">{{ form.remark.length }}/500</span></div></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="formVisible = false">关闭</Button><Button :disabled="formSubmitting || formLoading" @click="submitForm">{{ formSubmitting ? '保存中...' : '保存草稿' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailVisible">
      <DialogContent class="flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden sm:max-w-[1120px]">
        <DialogHeader><DialogTitle>出入库凭证详情</DialogTitle><DialogDescription>查看业务来源、确认信息以及每个产品的库存变动记录。</DialogDescription></DialogHeader>
        <div v-if="detailLoading" class="flex min-h-64 flex-1 items-center justify-center gap-2 text-muted-foreground"><span class="page-loading-spinner" />详情加载中...</div>
        <DialogScrollArea v-else-if="detail">
          <div class="space-y-5 py-1">
            <div class="grid grid-cols-4 gap-3 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="detail-field"><span>流水号</span><code>{{ detail.billNo }}</code></div>
              <div class="detail-field"><span>类型</span><Badge variant="outline" :class="billTypeMap[detail.billType].className">{{ billTypeMap[detail.billType].label }}</Badge></div>
              <div class="detail-field"><span>状态</span><Badge variant="outline" :class="statusMap[detail.status].className">{{ statusMap[detail.status].label }}</Badge></div>
              <div class="detail-field"><span>仓库</span><strong>{{ detail.warehouseName }}</strong></div>
              <div class="detail-field"><span>录入方式</span><Badge variant="outline" :class="entryModeMap[detail.entryMode].className">{{ entryModeMap[detail.entryMode].label }}</Badge></div>
              <div class="detail-field"><span>来源类型</span><strong>{{ sourceTypeMap[detail.sourceType] }}</strong></div>
              <div class="detail-field"><span>来源单号</span><code>{{ detail.sourceNo || '-' }}</code></div>
              <div class="detail-field"><span>负责人</span><strong>{{ detail.responsibleByName }}</strong></div>
              <div class="detail-field"><span>创建人 / 时间</span><strong>{{ detail.createdByName || '系统' }}</strong><small>{{ detail.createdAt }}</small></div>
              <div class="detail-field"><span>确认人 / 时间</span><strong>{{ detail.confirmedByName || '未确认' }}</strong><small>{{ detail.confirmedAt || '-' }}</small></div>
            </div>
            <div>
              <div class="mb-2 flex items-center justify-between"><h3 class="text-sm font-semibold">产品明细</h3><span class="text-xs text-muted-foreground">共 {{ detail.items.length }} 条</span></div>
              <div class="overflow-hidden rounded-lg border">
                <ScrollArea class="w-full">
                  <Table class="min-w-[1040px] table-fixed">
                    <colgroup><col class="w-[200px]" /><col class="w-[70px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[140px]" /></colgroup>
                    <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-right">本次数量</TableHead><TableHead class="text-right">合格数量</TableHead><TableHead class="text-right">不合格数量</TableHead><TableHead class="text-right">变动前</TableHead><TableHead class="text-right">变动数量</TableHead><TableHead class="text-right">变动后</TableHead><TableHead>备注</TableHead></TableRow></TableHeader>
                    <TableBody>
                      <TableRow v-for="item in detail.items" :key="item.stockBillItemId" :data-stock-bill-item-id="item.stockBillItemId">
                        <TableCell><div class="flex flex-col gap-1"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><span class="font-medium">{{ item.productName }}</span></div></TableCell>
                        <TableCell class="text-center">{{ item.unitName }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.quantity) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.qualifiedQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums" :class="item.defectiveQty > 0 ? 'font-medium text-rose-700' : 'text-muted-foreground'">{{ formatQty(item.defectiveQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.beforeQty) }}</TableCell>
                        <TableCell class="text-right font-semibold tabular-nums" :class="item.changeQty > 0 ? 'text-emerald-700' : item.changeQty < 0 ? 'text-blue-700' : 'text-muted-foreground'">{{ formatChangeQty(item.changeQty) }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.afterQty) }}</TableCell>
                        <TableCell class="text-xs text-muted-foreground">{{ item.remark || '-' }}</TableCell>
                      </TableRow>
                    </TableBody>
                  </Table>
                </ScrollArea>
              </div>
            </div>
            <div v-if="detail.entryMode !== 'SOURCE_GENERATED'" class="rounded-lg border border-amber-200 bg-amber-50/60 p-3"><span class="text-xs text-amber-700">{{ detail.entryMode === 'MANUAL_SUPPLEMENT' ? '补录原因' : '调整原因' }}</span><p class="mt-1 text-sm">{{ detail.manualReason }}</p></div>
            <div class="rounded-lg border bg-muted/25 p-3"><span class="text-xs text-muted-foreground">凭证备注</span><p class="mt-1 text-sm">{{ detail.remark || '无' }}</p></div>
          </div>
        </DialogScrollArea>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>

<style scoped>
.filter-grid--stock-bills {
  grid-template-columns: repeat(5, minmax(0, 1fr)) auto;
}

.detail-field {
  display: flex;
  min-height: 78px;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  background: color-mix(in srgb, var(--muted) 34%, transparent);
  padding: 12px;
}

.detail-field > span {
  font-size: 12px;
  color: var(--muted-foreground);
}

.detail-field > small {
  color: var(--muted-foreground);
}

.draft-item-grid {
  display: grid;
  grid-template-columns: minmax(250px, 2fr) minmax(120px, 0.8fr) minmax(220px, 1.5fr) auto;
  gap: 12px;
  align-items: start;
}

.draft-item-grid--quality {
  grid-template-columns: minmax(220px, 1.7fr) repeat(3, minmax(110px, 0.75fr)) minmax(180px, 1.2fr) auto;
}

@media (max-width: 1279px) {
  .filter-grid--stock-bills {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-grid--stock-bills .filter-actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .draft-item-grid,
  .draft-item-grid--quality {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .draft-remark {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .filter-grid--stock-bills,
  .draft-item-grid,
  .draft-item-grid--quality {
    grid-template-columns: minmax(0, 1fr);
  }

  .draft-remark {
    grid-column: auto;
  }
}
</style>
