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
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { useListRefresh } from '@/shared/composables/use-list-refresh';
import {
  createPurchaseOrder,
  listEnabledProductOptions,
  listEnabledWarehouseOptions,
  listPurchaseOrders,
  listSupplierOptions,
  listSupplierProducts,
  updatePurchaseOrder,
  updatePurchaseOrderStatus,
} from '../../api';
import type {
  PurchaseOrderDraftItemPayload,
  PurchaseOrderFormPayload,
  PurchaseOrderListItem,
  PurchaseOrderQuery,
  PurchaseOrderStatus,
  PurchaseOrderSummary,
  SupplierProductListItem,
} from '../../types';

interface Option {
  value: string;
  label: string;
  disabled?: boolean;
}

interface DraftItem extends PurchaseOrderDraftItemPayload {
  rowId: string;
  remark: string;
  unitName: string;
}

interface PurchaseOrderFormModel extends Omit<PurchaseOrderFormPayload, 'expectedArrivalDate'> {
  expectedArrivalDate: string;
}

const emptySummary = (): PurchaseOrderSummary => ({
  draftCount: 0,
  submittedCount: 0,
  approvedCount: 0,
  inboundPendingCount: 0,
});

const statusOptions: Array<{ value: PurchaseOrderStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'PARTIAL_INBOUND', label: '部分入库' },
  { value: 'INBOUND_DONE', label: '入库完成' },
  { value: 'CANCELLED', label: '已取消' },
];

const orders = ref<PurchaseOrderListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const createDialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingOrder = ref<PurchaseOrderListItem | null>(null);
const detailDialogOpen = ref(false);
const detailRow = ref<PurchaseOrderListItem | null>(null);
const detailActionMode = ref<'view' | 'submit' | 'approve'>('view');
const supplierOptions = ref<Option[]>([{ value: 'all', label: '全部供应商' }]);
const warehouseOptions = ref<Option[]>([{ value: 'all', label: '全部仓库' }]);
const productOptions = ref<Array<Option & { referencePurchasePrice: number; quantityPrecision: number; unitName: string }>>([]);
const supplierProducts = ref<SupplierProductListItem[]>([]);
let requestSequence = 0;
let lineSequence = 1;

const query = reactive<PurchaseOrderQuery>({
  purchaseNo: '',
  supplierId: 'all',
  warehouseId: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<PurchaseOrderFormModel>({
  supplierId: '',
  warehouseId: '',
  expectedArrivalDate: '',
  remark: '',
  items: [],
});
const draftItems = ref<DraftItem[]>([]);
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
const totalAmount = computed(() => draftItems.value.reduce((sum, item) => sum + Number(item.quantity || 0) * Number(item.unitPrice || 0), 0));
const activeSupplierProducts = computed(() => supplierProducts.value.filter(item => item.status === 1));
const selectedProductIds = computed(() => Array.from(new Set(draftItems.value.map(item => item.productId).filter(Boolean))));
const selectableSupplierOptions = computed(() => {
  return supplierOptions.value
    .filter(item => item.value !== 'all')
    .filter(item => selectedProductIds.value.length === 0 || selectedProductIds.value.every(productId => hasActiveSupply(String(item.value), productId)));
});

async function listAllActiveSupplierProducts() {
  const pageSize = 200;
  const records: SupplierProductListItem[] = [];
  let pageNum = 1;
  let total = 0;
  do {
    const page = await listSupplierProducts({ pageNum, pageSize, status: 1 });
    records.push(...page.records);
    total = page.total;
    pageNum += 1;
    if (page.records.length === 0) break;
  } while (records.length < total);
  return records;
}

async function loadOptions() {
  try {
    const [suppliers, warehouses, products, allSupplierProducts] = await Promise.all([
      listSupplierOptions(),
      listEnabledWarehouseOptions(),
      listEnabledProductOptions(),
      listAllActiveSupplierProducts(),
    ]);
    supplierOptions.value = [
      { value: 'all', label: '全部供应商' },
      ...suppliers.map(item => ({ value: item.supplierId, label: `${item.supplierCode} ${item.supplierName}`, disabled: item.status === 0 })),
    ];
    warehouseOptions.value = [
      { value: 'all', label: '全部仓库' },
      ...warehouses.map(item => ({ value: item.value, label: item.label })),
    ];
    productOptions.value = products.map(item => ({
      value: item.value,
      label: item.label,
      referencePurchasePrice: item.product.referencePurchasePrice,
      quantityPrecision: item.product.quantityPrecision,
      unitName: item.product.unitName,
    }));
    supplierProducts.value = allSupplierProducts;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购选项加载失败');
  }
}

async function fetchOrders() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await listPurchaseOrders({ ...query });
    if (sequence !== requestSequence) return;
    orders.value = page.records;
    total.value = page.total;
    Object.assign(summary, page.summary);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单加载失败');
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const debouncedSearch = useDebounceFn(() => {
  query.pageNum = 1;
  fetchOrders();
}, 250);
const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
  query.pageNum = pageNum;
  query.pageSize = pageSize;
  fetchOrders();
}, 180);
const refreshList = useListRefresh(queryBusy, queryPending, fetchOrders);

function handleSearch() {
  if (queryBusy.value) return;
  queryPending.value = true;
  debouncedSearch();
}

function handleReset() {
  if (queryBusy.value) return;
  Object.assign(query, { purchaseNo: '', supplierId: 'all', warehouseId: 'all', status: 'all', pageNum: 1 });
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

function resetForm() {
  Object.assign(form, { supplierId: '', warehouseId: '', expectedArrivalDate: '', remark: '', items: [] });
  draftItems.value = [newDraftItem()];
  editingOrder.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  createDialogOpen.value = true;
}

function openEditDialog(row: PurchaseOrderListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') {
    toast.warning('仅草稿或已提交采购单可以编辑，审核后不能直接修改');
    return;
  }
  dialogMode.value = 'edit';
  resetForm();
  editingOrder.value = row;
  Object.assign(form, {
    supplierId: row.supplierId,
    warehouseId: row.warehouseId,
    expectedArrivalDate: row.expectedArrivalDate || '',
    remark: row.remark,
    items: [],
  });
  draftItems.value = row.items.length > 0
    ? row.items.map(item => ({
      rowId: `line-${lineSequence++}`,
      purchaseOrderItemId: item.purchaseOrderItemId,
      supplierProductId: item.supplierProductId,
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      selectedSupplierScore: item.selectedSupplierScore,
      remark: item.remark,
      unitName: item.unitName,
    }))
    : [newDraftItem()];
  createDialogOpen.value = true;
}

function newDraftItem(): DraftItem {
  return {
    rowId: `line-${lineSequence++}`,
    purchaseOrderItemId: null,
    supplierProductId: null,
    productId: '',
    quantity: 1,
    unitPrice: 0,
    selectedSupplierScore: 0,
    remark: '',
    unitName: '',
  };
}

function addLine() {
  draftItems.value = [...draftItems.value, newDraftItem()];
}

function removeLine(rowId: string) {
  if (draftItems.value.length === 1) return;
  draftItems.value = draftItems.value.filter(item => item.rowId !== rowId);
}

function hasActiveSupply(supplierId: string, productId: string) {
  return activeSupplierProducts.value.some(item => item.supplierId === supplierId && item.productId === productId);
}

function findSupplierProduct(supplierId: string, productId: string) {
  return activeSupplierProducts.value
    .filter(item => item.supplierId === supplierId && item.productId === productId)
    .sort((a, b) => b.aiScore - a.aiScore)[0] || null;
}

function bestSupplierProductForProduct(productId: string, otherProductIds: string[] = []) {
  return activeSupplierProducts.value
    .filter(item => item.productId === productId)
    .filter(item => !otherProductIds.length || otherProductIds.every(otherProductId => hasActiveSupply(item.supplierId, otherProductId)))
    .sort((a, b) => b.aiScore - a.aiScore)[0] || null;
}

function clearLineProduct(line: DraftItem) {
  line.productId = '';
  line.supplierProductId = null;
  line.unitPrice = 0;
  line.selectedSupplierScore = 0;
  line.unitName = '';
}

function applySupplierProduct(line: DraftItem, supplierProduct: SupplierProductListItem) {
  line.productId = supplierProduct.productId;
  line.supplierProductId = supplierProduct.supplierProductId;
  line.unitPrice = supplierProduct.latestPurchasePrice;
  line.selectedSupplierScore = supplierProduct.aiScore;
  line.unitName = supplierProduct.unitName;
}

function productOptionsForLine(line: DraftItem) {
  const otherProductIds = draftItems.value
    .filter(item => item.rowId !== line.rowId)
    .map(item => item.productId)
    .filter(Boolean);
  const allowedProductIds = new Set(
    activeSupplierProducts.value
      .filter(item => !form.supplierId || item.supplierId === form.supplierId)
      .filter(item => otherProductIds.every(productId => hasActiveSupply(item.supplierId, productId)))
      .map(item => item.productId),
  );
  return productOptions.value.filter(item => allowedProductIds.has(String(item.value)));
}

function handleSupplierChange(value: string | number) {
  form.supplierId = String(value);
  draftItems.value.forEach(line => {
    if (!line.productId) return;
    const supplierProduct = findSupplierProduct(form.supplierId, line.productId);
    if (supplierProduct) {
      applySupplierProduct(line, supplierProduct);
    } else {
      clearLineProduct(line);
    }
  });
}

function selectProduct(line: DraftItem, productId: string | number) {
  line.productId = String(productId);
  const otherProductIds = draftItems.value
    .filter(item => item.rowId !== line.rowId)
    .map(item => item.productId)
    .filter(Boolean);
  const supplierProduct = form.supplierId
    ? findSupplierProduct(form.supplierId, line.productId)
    : bestSupplierProductForProduct(line.productId, otherProductIds);
  const product = productOptions.value.find(item => item.value === line.productId);
  line.supplierProductId = supplierProduct?.supplierProductId || null;
  line.unitPrice = supplierProduct?.latestPurchasePrice || product?.referencePurchasePrice || 0;
  line.selectedSupplierScore = supplierProduct?.aiScore || 0;
  line.unitName = supplierProduct?.unitName || product?.unitName || '';
}

function getProductPrecision(productId: string) {
  return productOptions.value.find(item => item.value === productId)?.quantityPrecision ?? 2;
}

function quantityStep(productId: string) {
  const precision = getProductPrecision(productId);
  if (precision <= 0) return '1';
  return `0.${'0'.repeat(Math.max(precision - 1, 0))}1`;
}

function quantityPrecisionValid(value: number, productId: string) {
  const precision = getProductPrecision(productId);
  const decimal = String(value).split('.')[1] || '';
  return decimal.length <= precision;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.supplierId) formErrors.supplierId = '请选择供应商';
  if (!form.warehouseId) formErrors.warehouseId = '请选择入库仓库';
  if (dialogMode.value === 'edit' && editingOrder.value?.status === 'SUBMITTED' && !form.expectedArrivalDate) formErrors.expectedArrivalDate = '已提交采购单必须维护预计到货日期';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  draftItems.value.forEach((item, index) => {
    if (!item.productId) formErrors[`items.${index}.productId`] = '请选择产品';
    if (item.productId && form.supplierId && !findSupplierProduct(form.supplierId, item.productId)) formErrors[`items.${index}.productId`] = '当前供应商未维护该产品的启用供货关系';
    if (!Number.isFinite(Number(item.quantity)) || Number(item.quantity) <= 0) formErrors[`items.${index}.quantity`] = '采购数量必须大于 0';
    if (item.productId && !quantityPrecisionValid(Number(item.quantity), item.productId)) formErrors[`items.${index}.quantity`] = `数量最多保留 ${getProductPrecision(item.productId)} 位小数`;
    if (!Number.isFinite(Number(item.unitPrice)) || Number(item.unitPrice) < 0) formErrors[`items.${index}.unitPrice`] = '采购单价不能小于 0';
    if (item.remark.trim().length > 500) formErrors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
  });
  return Object.keys(formErrors).length === 0;
}

function buildPayload(): PurchaseOrderFormPayload {
  return {
    supplierId: form.supplierId,
    warehouseId: form.warehouseId,
    expectedArrivalDate: form.expectedArrivalDate || null,
    remark: form.remark.trim(),
    items: draftItems.value.map(item => ({
      purchaseOrderItemId: item.purchaseOrderItemId || null,
      supplierProductId: findSupplierProduct(form.supplierId, item.productId)?.supplierProductId || null,
      productId: item.productId,
      quantity: Number(item.quantity),
      unitPrice: Number(item.unitPrice),
      selectedSupplierScore: Number(item.selectedSupplierScore),
      remark: item.remark.trim(),
    })),
  };
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'edit' && editingOrder.value) {
      await updatePurchaseOrder(editingOrder.value.purchaseOrderId, buildPayload());
      toast.success('采购订单草稿已更新');
    } else {
      await createPurchaseOrder(buildPayload());
      toast.success('采购订单草稿已创建');
    }
    createDialogOpen.value = false;
    await fetchOrders();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

function openDetail(row: PurchaseOrderListItem, actionMode: 'view' | 'submit' | 'approve' = 'view') {
  detailRow.value = row;
  detailActionMode.value = actionMode;
  detailDialogOpen.value = true;
}

function showConfirm(title: string, description: string, confirmText: string, variant: 'default' | 'destructive' | 'warning', onConfirm: () => void | Promise<void>) {
  Object.assign(confirmState, { open: true, title, description, confirmText, variant, onConfirm });
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

function confirmOrderAction(row: PurchaseOrderListItem, action: 'submit' | 'approve' | 'cancel') {
  const config = {
    submit: ['提交采购单', '提交后进入待审核状态，预计到货日期不能为空；已提交采购单只允许具备审核权限的人继续修改。', '提交', 'default'],
    approve: ['审核采购单', '审核后后端会生成待确认入库单，采购单本身不得再直接修改；库存变动仍以仓库模块确认本次数量为准。', '审核通过', 'warning'],
    cancel: ['取消采购单', '取消后该采购单保留追溯但不能继续流转。', '确认取消', 'destructive'],
  } as const;
  const [title, description, confirmText, variant] = config[action];
  showConfirm(title, description, confirmText, variant, async () => {
    await updatePurchaseOrderStatus(row.purchaseOrderId, action);
    toast.success('采购订单状态已更新');
    await fetchOrders();
  });
}

function openOrderActionDetail(row: PurchaseOrderListItem, action: 'submit' | 'approve') {
  openDetail(row, action);
}

function detailActionHint(row: PurchaseOrderListItem) {
  if (detailActionMode.value === 'view') return '';
  if (!row.expectedArrivalDate) return '预计到货日期为空，提交或审核前请先编辑维护。';
  return detailActionMode.value === 'submit'
    ? '请先核对采购单头和全部采购明细，再提交进入待审核。'
    : '请先核对采购单头和全部采购明细，审核通过后将生成待确认入库单。';
}

function runDetailAction(row: PurchaseOrderListItem) {
  if (detailActionMode.value === 'view') return;
  if (!row.expectedArrivalDate) {
    toast.warning('提交或审核前必须先维护预计到货日期');
    return;
  }
  confirmOrderAction(row, detailActionMode.value as 'submit' | 'approve');
}

function statusMeta(status: PurchaseOrderStatus) {
  const map: Record<PurchaseOrderStatus, { label: string; className: string }> = {
    DRAFT: { label: '草稿', className: 'border-slate-200 bg-slate-50 text-slate-600' },
    SUBMITTED: { label: '已提交', className: 'border-blue-200 bg-blue-50 text-blue-700' },
    APPROVED: { label: '已审核', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
    PARTIAL_INBOUND: { label: '部分入库', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    INBOUND_DONE: { label: '入库完成', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
    CANCELLED: { label: '已取消', className: 'border-rose-200 bg-rose-50 text-rose-700' },
  };
  return map[status];
}

function formatMoney(value: number) {
  return `￥${value.toFixed(2)}`;
}

onMounted(() => {
  loadOptions();
  fetchOrders();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">采购订单</h1>
        <p class="page-description">创建采购草稿、提交审核，并追踪采购入库进度；库存变化统一由仓库入库单确认</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页草稿</span><strong class="mt-1 text-2xl">{{ summary.draftCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页已提交</span><strong class="mt-1 text-2xl text-blue-700">{{ summary.submittedCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页已审核</span><strong class="mt-1 text-2xl text-emerald-700">{{ summary.approvedCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页待入库</span><strong class="mt-1 text-2xl text-amber-700">{{ summary.inboundPendingCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--purchase">
        <div class="space-y-1"><Label class="text-xs">采购单号</Label><Input v-model="query.purchaseNo" placeholder="如 PO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">供应商</Label><AnchoredSelect v-model="query.supplierId" :options="supplierOptions" /></div>
        <div class="space-y-1"><Label class="text-xs">入库仓库</Label><AnchoredSelect v-model="query.warehouseId" :options="warehouseOptions" /></div>
        <div class="space-y-1"><Label class="text-xs">订单状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
        <div class="filter-actions">
          <Button size="sm" :disabled="queryBusy" @click="handleSearch"><span v-if="queryBusy" class="page-loading-spinner !size-3.5" />{{ queryBusy ? '查询中' : '查询' }}</Button>
          <Button size="sm" variant="outline" :disabled="queryBusy" @click="handleReset">重置</Button>
        </div>
      </div>
    </div>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">采购订单列表</strong><span class="text-xs text-muted-foreground">审核动作只生成待确认入库单，实际入库由仓库确认本次数量</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增采购单</Button></div>
      </div>

      <ScrollArea class="w-full">
        <Table class="business-data-table min-w-[1155px] table-fixed">
          <colgroup><col class="w-[130px]" /><col class="w-[145px]" /><col class="w-[110px]" /><col class="w-[90px]" /><col class="w-[120px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[140px]" /><col class="w-[210px]" /></colgroup>
          <TableHeader><TableRow><TableHead>采购单号</TableHead><TableHead>供应商</TableHead><TableHead>入库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计到货</TableHead><TableHead>创建人</TableHead><TableHead>更新时间</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无采购订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.purchaseOrderId">
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.purchaseNo }}</code></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.supplierCode }}</code><div class="mt-1 truncate font-medium">{{ row.supplierName }}</div></TableCell>
              <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell class="text-center text-sm">{{ row.expectedArrivalDate || '未设置' }}</TableCell>
              <TableCell class="whitespace-nowrap" :title="row.createdByName || '系统'">{{ row.createdByName || '系统' }}</TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updateTime }}</TableCell>
              <TableCell class="text-right">
                <Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" @click="openDetail(row)">详情</Button>
                <Button v-if="row.status === 'DRAFT' || row.status === 'SUBMITTED'" variant="ghost" size="sm" @click="openEditDialog(row)">编辑</Button>
                <Button v-if="row.status === 'DRAFT'" variant="ghost" size="sm" class="text-primary hover:text-primary" @click="openOrderActionDetail(row, 'submit')">提交</Button>
                <Button v-if="row.status === 'SUBMITTED'" variant="ghost" size="sm" class="text-emerald-700 hover:text-emerald-800" @click="openOrderActionDetail(row, 'approve')">审核</Button>
                <Button v-if="row.status === 'DRAFT' || row.status === 'SUBMITTED'" variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmOrderAction(row, 'cancel')">取消</Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="createDialogOpen">
      <DialogContent class="flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-5xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增采购单草稿' : '编辑采购单' }}</DialogTitle><DialogDescription>采购单保存为草稿后可提交审核，审核通过后由仓储生成待确认入库单。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">采购单号</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? editingOrder.purchaseNo : '后端自动生成' }}</div></div>
              <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? statusMeta(editingOrder.status).label : '保存后为草稿' }}</div></div>
              <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? `${editingOrder.createdByName || '系统'} / ${editingOrder.createTime}` : '当前登录用户' }}</div></div>
            </div>
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>供应商 <span class="text-destructive">*</span></Label><AnchoredSelect :model-value="form.supplierId" :options="selectableSupplierOptions" placeholder="请选择供应商" :invalid="Boolean(formErrors.supplierId)" @update:model-value="handleSupplierChange" /><p v-if="formErrors.supplierId" class="form-error">{{ formErrors.supplierId }}</p></div>
              <div class="space-y-1"><Label>入库仓库 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.warehouseId" :options="warehouseOptions.filter(item => item.value !== 'all')" placeholder="请选择仓库" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="form-error">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>预计到货</Label><Input v-model="form.expectedArrivalDate" type="date" /><p v-if="formErrors.expectedArrivalDate" class="form-error">{{ formErrors.expectedArrivalDate }}</p><p v-else class="text-xs text-muted-foreground">示例：2026-06-30</p></div>
            </div>
            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="2" /><p v-if="formErrors.remark" class="form-error">{{ formErrors.remark }}</p></div>

            <div class="rounded-md border border-border">
              <div class="flex min-h-11 items-center justify-between border-b border-border px-3"><strong class="text-sm">采购明细</strong><Button size="sm" variant="outline" type="button" @click="addLine">添加产品</Button></div>
              <ScrollArea class="w-full purchase-order-line-scroll">
                <Table class="min-w-[1080px] table-fixed">
                  <colgroup><col class="w-[280px]" /><col class="w-[150px]" /><col class="w-[145px]" /><col class="w-[110px]" /><col class="w-[135px]" /><col class="w-[170px]" /><col class="w-[90px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">采购价</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell class="align-top"><AnchoredSelect :model-value="line.productId" :options="productOptionsForLine(line)" placeholder="请选择产品" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="form-error">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="flex items-center justify-center gap-2"><Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line.productId)" class="text-center" /><span class="w-10 shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span></div><p v-if="formErrors[`items.${index}.quantity`]" class="form-error">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="pl-8 text-center" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="form-error">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
                      <TableCell class="text-center tabular-nums">{{ Number(line.selectedSupplierScore || 0).toFixed(1) }}</TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(Number(line.quantity || 0) * Number(line.unitPrice || 0)) }}</TableCell>
                      <TableCell class="align-top"><Input v-model="line.remark" placeholder="可选" /><p v-if="formErrors[`items.${index}.remark`]" class="form-error">{{ formErrors[`items.${index}.remark`] }}</p></TableCell>
                      <TableCell class="align-top text-center"><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" :disabled="draftItems.length === 1" @click="removeLine(line.rowId)">删除</Button></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex justify-end border-t border-border px-4 py-3 text-sm">草稿金额：<strong class="ml-2 text-base">{{ formatMoney(totalAmount) }}</strong></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="createDialogOpen = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : (dialogMode === 'create' ? '保存草稿' : '保存修改') }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailDialogOpen">
      <DialogContent class="flex h-[min(760px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-5xl">
        <DialogHeader><DialogTitle>采购单详情</DialogTitle><DialogDescription>核对采购单头、明细数量、金额和入库流转状态。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-4 p-1">
            <div class="purchase-detail-grid grid grid-cols-3 gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="purchase-detail-field"><span>采购单号</span><code>{{ detailRow.purchaseNo }}</code></div>
              <div class="purchase-detail-field"><span>供应商</span><strong>{{ detailRow.supplierName }}</strong><small>{{ detailRow.supplierCode }}</small></div>
              <div class="purchase-detail-field"><span>入库仓库</span><strong>{{ detailRow.warehouseName }}</strong></div>
              <div class="purchase-detail-field"><span>状态</span><Badge variant="outline" :class="statusMeta(detailRow.status).className">{{ statusMeta(detailRow.status).label }}</Badge></div>
              <div class="purchase-detail-field"><span>订单金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
              <div class="purchase-detail-field"><span>预计到货</span><strong>{{ detailRow.expectedArrivalDate || '未设置' }}</strong></div>
              <div class="purchase-detail-field"><span>创建人 / 时间</span><strong>{{ detailRow.createdByName || '系统' }}</strong><small>{{ detailRow.createTime }}</small></div>
              <div class="purchase-detail-field"><span>提交时间</span><strong>{{ detailRow.submittedAt || '未提交' }}</strong></div>
              <div class="purchase-detail-field"><span>审核信息</span><strong>{{ detailRow.approvedByName || '未审核' }}</strong><small>{{ detailRow.approvedAt || '-' }}</small></div>
              <div class="purchase-detail-field purchase-detail-field--wide"><span>备注</span><strong>{{ detailRow.remark || '未维护' }}</strong></div>
            </div>
            <ScrollArea class="w-full purchase-order-line-scroll">
            <Table class="min-w-[920px] table-fixed">
              <colgroup><col class="w-[240px]" /><col class="w-[100px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[160px]" /></colgroup>
              <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">数量</TableHead><TableHead class="text-center">已入库</TableHead><TableHead class="text-center">单价</TableHead><TableHead class="text-center">金额</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead>明细备注</TableHead></TableRow></TableHeader>
              <TableBody>
                <TableRow v-for="item in detailRow.items" :key="item.purchaseOrderItemId">
                  <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell>
                  <TableCell class="text-center tabular-nums">{{ item.quantity }} {{ item.unitName }}</TableCell>
                  <TableCell class="text-center tabular-nums">{{ item.inboundQty }} {{ item.unitName }}</TableCell>
                  <TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell>
                  <TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell>
                  <TableCell class="text-center">{{ item.selectedSupplierScore.toFixed(1) }}</TableCell>
                  <TableCell class="truncate" :title="item.remark">{{ item.remark || '未维护' }}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
            </ScrollArea>
          </div>
        </DialogScrollArea>
        <DialogFooter class="items-center justify-between gap-3">
          <span v-if="detailRow && detailActionMode !== 'view'" class="mr-auto text-xs" :class="detailRow.expectedArrivalDate ? 'text-muted-foreground' : 'text-destructive'">{{ detailActionHint(detailRow) }}</span>
          <Button variant="outline" :disabled="actionSubmitting" @click="detailDialogOpen = false">关闭</Button>
          <Button v-if="detailRow && detailActionMode !== 'view'" :disabled="actionSubmitting || !detailRow.expectedArrivalDate" @click="runDetailAction(detailRow)">{{ actionSubmitting ? '处理中' : detailActionMode === 'submit' ? '提交采购单' : '审核通过' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
