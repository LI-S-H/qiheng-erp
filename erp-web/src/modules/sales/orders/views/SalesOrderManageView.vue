<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
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
  createSalesOrder,
  listEnabledSalesProductOptions,
  listEnabledSalesWarehouseOptions,
  listSalesOrders,
  searchCustomerOptions,
  updateSalesOrder,
  updateSalesOrderStatus,
} from '../../api';
import type {
  SalesOrderDraftItemPayload,
  SalesOrderFormPayload,
  SalesOrderListItem,
  SalesOrderQuery,
  SalesOrderStatus,
  SalesOrderSummary,
} from '../../types';

interface Option {
  value: string;
  label: string;
  disabled?: boolean;
}

interface DraftItem extends SalesOrderDraftItemPayload {
  rowId: string;
  remark: string;
  unitName: string;
}

interface SalesOrderFormModel extends Omit<SalesOrderFormPayload, 'expectedDeliveryDate'> {
  expectedDeliveryDate: string;
}

const emptySummary = (): SalesOrderSummary => ({
  draftCount: 0,
  submittedCount: 0,
  approvedCount: 0,
  outboundPendingCount: 0,
});

const statusOptions: Array<{ value: SalesOrderStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'PARTIAL_OUTBOUND', label: '部分出库' },
  { value: 'OUTBOUND_DONE', label: '出库完成' },
  { value: 'CANCELLED', label: '已取消' },
];

const orders = ref<SalesOrderListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const createDialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingOrder = ref<SalesOrderListItem | null>(null);
const detailDialogOpen = ref(false);
const detailRow = ref<SalesOrderListItem | null>(null);
const detailActionMode = ref<'view' | 'submit' | 'approve'>('view');
const customerOptions = ref<Option[]>([{ value: 'all', label: '全部客户' }]);
const warehouseOptions = ref<Option[]>([{ value: 'all', label: '全部仓库' }]);
const productOptions = ref<Array<Option & { referenceSalePrice: number; quantityPrecision: number; unitName: string }>>([]);
let requestSequence = 0;
let lineSequence = 1;

const query = reactive<SalesOrderQuery>({
  salesNo: '',
  customerId: 'all',
  warehouseId: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<SalesOrderFormModel>({
  customerId: '',
  warehouseId: '',
  expectedDeliveryDate: '',
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
const selectedCustomerLabel = computed(() => customerOptions.value.find(item => item.value === form.customerId)?.label || (editingOrder.value?.customerId === form.customerId ? `${editingOrder.value.customerCode} ${editingOrder.value.customerName}` : ''));
const selectedWarehouseLabel = computed(() => warehouseOptions.value.find(item => item.value === form.warehouseId)?.label || (editingOrder.value?.warehouseId === form.warehouseId ? editingOrder.value.warehouseName : ''));
const queryCustomerLabel = computed(() => query.customerId === 'all' ? '全部客户' : customerOptions.value.find(item => item.value === query.customerId)?.label || '');
const queryWarehouseLabel = computed(() => query.warehouseId === 'all' ? '全部仓库' : warehouseOptions.value.find(item => item.value === query.warehouseId)?.label || '');

function selectedProductLabel(productId: string) {
  return productOptions.value.find(item => item.value === productId)?.label
    || (() => {
      const item = editingOrder.value?.items.find(candidate => candidate.productId === productId && candidate.productCode);
      return item ? `${item.productCode} ${item.productName}` : '';
    })()
    || '';
}

function mergeCustomerOptions(options: Option[]) {
  const cache = new Map(customerOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  customerOptions.value = [
    { value: 'all', label: '全部客户' },
    ...Array.from(cache.values()).filter(item => item.value !== 'all'),
  ];
}

function mergeWarehouseOptions(options: Option[]) {
  const cache = new Map(warehouseOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  warehouseOptions.value = [
    { value: 'all', label: '全部仓库' },
    ...Array.from(cache.values()).filter(item => item.value !== 'all'),
  ];
}

function mergeProductOptions(options: Array<Option & { referenceSalePrice: number; quantityPrecision: number; unitName: string }>) {
  const cache = new Map(productOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  productOptions.value = Array.from(cache.values());
}

async function fetchCustomerSearchOptions(keyword: string) {
  const customers = await searchCustomerOptions(keyword, 10);
  const options = customers.map(item => ({ value: item.customerId, label: `${item.customerCode} ${item.customerName}`, disabled: item.status === 0 }));
  mergeCustomerOptions(options);
  return options;
}

async function fetchWarehouseSearchOptions(keyword: string) {
  const warehouses = await listEnabledSalesWarehouseOptions(keyword, 10);
  const options = warehouses.map(item => ({ value: item.value, label: item.label }));
  mergeWarehouseOptions(options);
  return options;
}

async function fetchProductSearchOptions(keyword: string) {
  const products = await listEnabledSalesProductOptions(keyword, 10);
  const options = products.map(item => ({
    value: item.value,
    label: item.label,
    referenceSalePrice: item.product.referenceSalePrice,
    quantityPrecision: item.product.quantityPrecision,
    unitName: item.product.unitName,
  }));
  mergeProductOptions(options);
  return options;
}

function cacheOrderOptions(row: SalesOrderListItem) {
  mergeCustomerOptions([{ value: row.customerId, label: `${row.customerCode} ${row.customerName}` }]);
  mergeWarehouseOptions([{ value: row.warehouseId, label: row.warehouseName }]);
  mergeProductOptions(row.items.map(item => ({
    value: item.productId,
    label: `${item.productCode} ${item.productName}`,
    referenceSalePrice: item.unitPrice,
    quantityPrecision: 2,
    unitName: item.unitName,
  })));
}

async function loadOptions() {
  try {
    const [customers, warehouses, products] = await Promise.all([
      searchCustomerOptions('', 10),
      listEnabledSalesWarehouseOptions('', 10),
      listEnabledSalesProductOptions('', 10),
    ]);
    mergeCustomerOptions(customers.map(item => ({ value: item.customerId, label: `${item.customerCode} ${item.customerName}`, disabled: item.status === 0 })));
    mergeWarehouseOptions(warehouses.map(item => ({ value: item.value, label: item.label })));
    mergeProductOptions(products.map(item => ({
      value: item.value,
      label: item.label,
      referenceSalePrice: item.product.referenceSalePrice,
      quantityPrecision: item.product.quantityPrecision,
      unitName: item.product.unitName,
    })));
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售选项加载失败');
  }
}

async function fetchOrders() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await listSalesOrders({ ...query });
    if (sequence !== requestSequence) return;
    orders.value = page.records;
    total.value = page.total;
    Object.assign(summary, page.summary);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售订单加载失败');
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
  Object.assign(query, { salesNo: '', customerId: 'all', warehouseId: 'all', status: 'all', pageNum: 1 });
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

function newDraftItem(): DraftItem {
  return {
    rowId: `line-${lineSequence++}`,
    salesOrderItemId: null,
    productId: '',
    quantity: 1,
    unitPrice: 0,
    remark: '',
    unitName: '',
  };
}

function resetForm() {
  Object.assign(form, { customerId: '', warehouseId: '', expectedDeliveryDate: '', remark: '', items: [] });
  draftItems.value = [newDraftItem()];
  editingOrder.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  createDialogOpen.value = true;
}

function openEditDialog(row: SalesOrderListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') {
    toast.warning('仅草稿或已提交销售单可以编辑，审核后不能直接修改');
    return;
  }
  dialogMode.value = 'edit';
  resetForm();
  editingOrder.value = row;
  cacheOrderOptions(row);
  Object.assign(form, {
    customerId: row.customerId,
    warehouseId: row.warehouseId,
    expectedDeliveryDate: row.expectedDeliveryDate || '',
    remark: row.remark,
    items: [],
  });
  draftItems.value = row.items.length > 0
    ? row.items.map(item => ({
      rowId: `line-${lineSequence++}`,
      salesOrderItemId: item.salesOrderItemId,
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      remark: item.remark,
      unitName: item.unitName,
    }))
    : [newDraftItem()];
  createDialogOpen.value = true;
}

function addLine() {
  draftItems.value = [...draftItems.value, newDraftItem()];
}

function removeLine(rowId: string) {
  if (draftItems.value.length === 1) return;
  draftItems.value = draftItems.value.filter(item => item.rowId !== rowId);
}

function selectProduct(line: DraftItem, productId: string | number) {
  line.productId = String(productId);
  const product = productOptions.value.find(item => item.value === line.productId);
  line.unitPrice = product?.referenceSalePrice || 0;
  line.unitName = product?.unitName || '';
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
  if (!form.customerId) formErrors.customerId = '请选择客户';
  if (!form.warehouseId) formErrors.warehouseId = '请选择出库仓库';
  if (dialogMode.value === 'edit' && editingOrder.value?.status === 'SUBMITTED' && !form.expectedDeliveryDate) formErrors.expectedDeliveryDate = '已提交销售单必须维护预计发货日期';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  draftItems.value.forEach((item, index) => {
    if (!item.productId) formErrors[`items.${index}.productId`] = '请选择产品';
    if (!Number.isFinite(Number(item.quantity)) || Number(item.quantity) <= 0) formErrors[`items.${index}.quantity`] = '销售数量必须大于 0';
    if (item.productId && !quantityPrecisionValid(Number(item.quantity), item.productId)) formErrors[`items.${index}.quantity`] = `数量最多保留 ${getProductPrecision(item.productId)} 位小数`;
    if (!Number.isFinite(Number(item.unitPrice)) || Number(item.unitPrice) < 0) formErrors[`items.${index}.unitPrice`] = '销售单价不能小于 0';
    if (item.remark.trim().length > 500) formErrors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
  });
  return Object.keys(formErrors).length === 0;
}

function buildPayload(): SalesOrderFormPayload {
  return {
    ...(dialogMode.value === 'edit' && editingOrder.value ? { version: editingOrder.value.version } : {}),
    customerId: form.customerId,
    warehouseId: form.warehouseId,
    expectedDeliveryDate: form.expectedDeliveryDate || null,
    remark: form.remark.trim(),
    items: draftItems.value.map(item => ({
      salesOrderItemId: item.salesOrderItemId || null,
      productId: item.productId,
      quantity: Number(item.quantity),
      unitPrice: Number(item.unitPrice),
      remark: item.remark.trim(),
    })),
  };
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'edit' && editingOrder.value) {
      await updateSalesOrder(editingOrder.value.salesOrderId, buildPayload());
      toast.success('销售订单草稿已更新');
    } else {
      await createSalesOrder(buildPayload());
      toast.success('销售订单草稿已创建');
    }
    createDialogOpen.value = false;
    await fetchOrders();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售订单保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

function openDetail(row: SalesOrderListItem, actionMode: 'view' | 'submit' | 'approve' = 'view') {
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

function confirmOrderAction(row: SalesOrderListItem, action: 'submit' | 'approve' | 'cancel') {
  const config = {
    submit: ['提交销售单', '提交后进入待审核状态，后端会校验并锁定可用库存；预计发货日期不能为空。', '提交', 'default'],
    approve: ['审核销售单', '审核后生成待确认销售出库单，实际扣减库存仍由仓库出库确认完成。', '审核通过', 'warning'],
    cancel: ['取消销售单', '取消后该销售单保留追溯但不能继续流转；如已锁定库存，后端需要释放锁定数量。', '确认取消', 'destructive'],
  } as const;
  const [title, description, confirmText, variant] = config[action];
  showConfirm(title, description, confirmText, variant, async () => {
    await updateSalesOrderStatus(row.salesOrderId, action, row.version);
    toast.success('销售订单状态已更新');
    detailDialogOpen.value = false;
    await fetchOrders();
  });
}

function openOrderActionDetail(row: SalesOrderListItem, action: 'submit' | 'approve') {
  openDetail(row, action);
}

function detailActionHint(row: SalesOrderListItem) {
  if (detailActionMode.value === 'view') return '';
  if (!row.expectedDeliveryDate) return '预计发货日期为空，提交或审核前请先编辑维护。';
  return detailActionMode.value === 'submit'
    ? '请先核对销售单头和全部销售明细，再提交进入待审核并锁定库存。'
    : '请先核对销售单头和全部销售明细，审核通过后将生成待确认销售出库单。';
}

function runDetailAction(row: SalesOrderListItem) {
  if (detailActionMode.value === 'view') return;
  if (!row.expectedDeliveryDate) {
    toast.warning('提交或审核前必须先维护预计发货日期');
    return;
  }
  confirmOrderAction(row, detailActionMode.value as 'submit' | 'approve');
}

function statusMeta(status: SalesOrderStatus) {
  const map: Record<SalesOrderStatus, { label: string; className: string }> = {
    DRAFT: { label: '草稿', className: 'border-slate-200 bg-slate-50 text-slate-600' },
    SUBMITTED: { label: '已提交', className: 'border-blue-200 bg-blue-50 text-blue-700' },
    APPROVED: { label: '已审核', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
    PARTIAL_OUTBOUND: { label: '部分出库', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    OUTBOUND_DONE: { label: '出库完成', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
    CANCELLED: { label: '已取消', className: 'border-rose-200 bg-rose-50 text-rose-700' },
  };
  return map[status];
}

function formatQty(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 });
}

function lockedInventoryText(row: SalesOrderListItem) {
  const lockedItems = row.items.filter(item => Number(item.lockedQty || 0) > 0);
  const lockedQty = lockedItems.reduce((sum, item) => sum + Number(item.lockedQty || 0), 0);
  if (lockedQty > 0) {
    const units = [...new Set(lockedItems.map(item => item.unitName).filter(Boolean))];
    return units.length === 1 ? `${formatQty(lockedQty)} ${units[0]}` : `已锁定 ${lockedItems.length} 项`;
  }
  if (row.status === 'CANCELLED') return '已释放';
  if (row.status === 'OUTBOUND_DONE') return '已出库';
  return '未锁定';
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
        <h1 class="page-title">销售订单</h1>
        <p class="page-description">创建销售草稿、提交审核、锁定库存，并追踪销售出库进度；实际扣减库存由仓库出库单确认</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页草稿</span><strong class="mt-1 text-2xl">{{ summary.draftCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页已提交</span><strong class="mt-1 text-2xl text-blue-700">{{ summary.submittedCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页已审核</span><strong class="mt-1 text-2xl text-emerald-700">{{ summary.approvedCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">本页待出库</span><strong class="mt-1 text-2xl text-amber-700">{{ summary.outboundPendingCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid filter-grid--sales">
        <div class="space-y-1"><Label class="text-xs">销售单号</Label><Input v-model="query.salesNo" placeholder="如 SO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1"><Label class="text-xs">客户</Label><RemoteSearchSelect v-model="query.customerId" :selected-label="queryCustomerLabel" :fetch-options="fetchCustomerSearchOptions" placeholder="全部客户" search-placeholder="输入客户编码或名称" clearable clear-value="all" clear-label="全部客户" /></div>
        <div class="space-y-1"><Label class="text-xs">出库仓库</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="queryWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
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
        <div class="table-toolbar__title"><strong class="text-sm">销售订单列表</strong><span class="text-xs text-muted-foreground">审核动作只生成待确认出库单，库存扣减由仓库确认本次数量</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增销售单</Button></div>
      </div>

      <ScrollArea class="w-full">
        <Table class="business-data-table min-w-[1160px] table-fixed">
          <colgroup><col class="w-[130px]" /><col class="w-[145px]" /><col class="w-[105px]" /><col class="w-[85px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[135px]" /><col class="w-[240px]" /></colgroup>
          <TableHeader><TableRow><TableHead>销售单号</TableHead><TableHead>客户</TableHead><TableHead>出库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计发货</TableHead><TableHead>锁定数量</TableHead><TableHead>更新时间</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无销售订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.salesOrderId">
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.salesNo }}</code></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.customerCode }}</code><div class="mt-1 truncate font-medium">{{ row.customerName }}</div></TableCell>
              <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell class="text-center text-sm">{{ row.expectedDeliveryDate || '未设置' }}</TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ lockedInventoryText(row) }}</TableCell>
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
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增销售单草稿' : '编辑销售单' }}</DialogTitle><DialogDescription>销售单保存为草稿后可提交审核，提交时校验并锁定可用库存，审核后生成待确认出库单。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">销售单号</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? editingOrder.salesNo : '后端自动生成' }}</div></div>
              <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? statusMeta(editingOrder.status).label : '保存后为草稿' }}</div></div>
              <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? `${editingOrder.createdByName || '系统'} / ${editingOrder.createTime}` : '当前登录用户' }}</div></div>
            </div>
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>客户 <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.customerId" :selected-label="selectedCustomerLabel" :fetch-options="fetchCustomerSearchOptions" placeholder="请选择客户" search-placeholder="输入客户编码或名称" :invalid="Boolean(formErrors.customerId)" /><p v-if="formErrors.customerId" class="form-error">{{ formErrors.customerId }}</p></div>
              <div class="space-y-1"><Label>出库仓库 <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="请选择仓库" search-placeholder="输入仓库编码或名称" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="form-error">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>预计发货</Label><Input v-model="form.expectedDeliveryDate" type="date" /><p v-if="formErrors.expectedDeliveryDate" class="form-error">{{ formErrors.expectedDeliveryDate }}</p><p v-else class="text-xs text-muted-foreground">示例：2026-06-30</p></div>
            </div>
            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="2" /><p v-if="formErrors.remark" class="form-error">{{ formErrors.remark }}</p></div>

            <div class="rounded-md border border-border">
              <div class="flex min-h-11 items-center justify-between border-b border-border px-3"><strong class="text-sm">销售明细</strong><Button size="sm" variant="outline" type="button" @click="addLine">添加产品</Button></div>
              <ScrollArea class="w-full purchase-order-line-scroll">
                <Table class="min-w-[830px] table-fixed">
                  <colgroup><col class="w-[230px]" /><col class="w-[115px]" /><col class="w-[120px]" /><col class="w-[115px]" /><col class="w-[170px]" /><col class="w-[80px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">销售价</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell class="align-top"><RemoteSearchSelect :model-value="line.productId" :selected-label="selectedProductLabel(line.productId)" :fetch-options="fetchProductSearchOptions" placeholder="请选择产品" search-placeholder="输入产品编码或名称" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="form-error">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="flex items-center justify-center gap-2"><Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line.productId)" class="text-center" /><span class="w-10 shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span></div><p v-if="formErrors[`items.${index}.quantity`]" class="form-error">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="pl-8 text-center" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="form-error">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
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
        <DialogHeader><DialogTitle>销售单详情</DialogTitle><DialogDescription>核对销售单头、明细数量、库存锁定和出库流转状态。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-4 p-1">
            <div class="purchase-detail-grid grid grid-cols-3 gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="purchase-detail-field"><span>销售单号</span><code>{{ detailRow.salesNo }}</code></div>
              <div class="purchase-detail-field"><span>客户</span><strong>{{ detailRow.customerName }}</strong><small>{{ detailRow.customerCode }}</small></div>
              <div class="purchase-detail-field"><span>出库仓库</span><strong>{{ detailRow.warehouseName }}</strong></div>
              <div class="purchase-detail-field"><span>状态</span><Badge variant="outline" :class="statusMeta(detailRow.status).className">{{ statusMeta(detailRow.status).label }}</Badge></div>
              <div class="purchase-detail-field"><span>订单金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
              <div class="purchase-detail-field"><span>预计发货</span><strong>{{ detailRow.expectedDeliveryDate || '未设置' }}</strong></div>
              <div class="purchase-detail-field"><span>库存锁定数量</span><strong>{{ lockedInventoryText(detailRow) }}</strong><small v-if="detailRow.lockedAt">锁定时间：{{ detailRow.lockedAt }}</small></div>
              <div class="purchase-detail-field"><span>提交时间</span><strong>{{ detailRow.submittedAt || '未提交' }}</strong></div>
              <div class="purchase-detail-field"><span>审核信息</span><strong>{{ detailRow.approvedByName || '未审核' }}</strong><small>{{ detailRow.approvedAt || '-' }}</small></div>
              <div class="purchase-detail-field purchase-detail-field--wide"><span>备注</span><strong>{{ detailRow.remark || '未维护' }}</strong></div>
            </div>
            <ScrollArea class="w-full purchase-order-line-scroll">
              <Table class="min-w-[940px] table-fixed">
                <colgroup><col class="w-[240px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[160px]" /></colgroup>
                <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">销售数量</TableHead><TableHead class="text-center">已锁定</TableHead><TableHead class="text-center">已出库</TableHead><TableHead class="text-center">单价</TableHead><TableHead class="text-center">金额</TableHead><TableHead>明细备注</TableHead></TableRow></TableHeader>
                <TableBody>
                  <TableRow v-for="item in detailRow.items" :key="item.salesOrderItemId">
                    <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.quantity }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.lockedQty }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.outboundQty }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell>
                    <TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell>
                    <TableCell class="truncate" :title="item.remark">{{ item.remark || '未维护' }}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </ScrollArea>
          </div>
        </DialogScrollArea>
        <DialogFooter class="items-center justify-between gap-3">
          <span v-if="detailRow && detailActionMode !== 'view'" class="mr-auto text-xs" :class="detailRow.expectedDeliveryDate ? 'text-muted-foreground' : 'text-destructive'">{{ detailActionHint(detailRow) }}</span>
          <Button variant="outline" :disabled="actionSubmitting" @click="detailDialogOpen = false">关闭</Button>
          <Button v-if="detailRow && detailActionMode !== 'view'" :disabled="actionSubmitting || !detailRow.expectedDeliveryDate" @click="runDetailAction(detailRow)">{{ actionSubmitting ? '处理中' : detailActionMode === 'submit' ? '提交销售单' : '审核通过' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
