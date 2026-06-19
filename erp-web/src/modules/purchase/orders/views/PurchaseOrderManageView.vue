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
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
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
}

interface PurchaseOrderFormModel extends Omit<PurchaseOrderFormPayload, 'expectedArrivalDate'> {
  expectedArrivalDate: string;
}

const emptySummary = (): PurchaseOrderSummary => ({
  orderCount: 0,
  draftCount: 0,
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
const detailDialogOpen = ref(false);
const detailRow = ref<PurchaseOrderListItem | null>(null);
const supplierOptions = ref<Option[]>([{ value: 'all', label: '全部供应商' }]);
const warehouseOptions = ref<Option[]>([{ value: 'all', label: '全部仓库' }]);
const productOptions = ref<Array<Option & { referencePurchasePrice: number }>>([]);
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

async function loadOptions() {
  try {
    const [suppliers, warehouses, products, supplierProductPage] = await Promise.all([
      listSupplierOptions(),
      listEnabledWarehouseOptions(),
      listEnabledProductOptions(),
      listSupplierProducts({ pageNum: 1, pageSize: 200, status: 1 }),
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
    }));
    supplierProducts.value = supplierProductPage.records;
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
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  resetForm();
  createDialogOpen.value = true;
}

function newDraftItem(): DraftItem {
  return {
    rowId: `line-${lineSequence++}`,
    supplierProductId: null,
    productId: '',
    quantity: 1,
    unitPrice: 0,
    selectedSupplierScore: 0,
    expectedArrivalDate: '',
    remark: '',
  };
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
  const supplierProduct = supplierProducts.value
    .filter(item => !form.supplierId || item.supplierId === form.supplierId)
    .sort((a, b) => b.aiScore - a.aiScore)
    .find(item => item.productId === line.productId);
  const product = productOptions.value.find(item => item.value === line.productId);
  line.supplierProductId = supplierProduct?.supplierProductId || null;
  line.unitPrice = supplierProduct?.latestPurchasePrice || product?.referencePurchasePrice || 0;
  line.selectedSupplierScore = supplierProduct?.aiScore || 0;
}

function validateForm() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  if (!form.supplierId) formErrors.supplierId = '请选择供应商';
  if (!form.warehouseId) formErrors.warehouseId = '请选择入库仓库';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  draftItems.value.forEach((item, index) => {
    if (!item.productId) formErrors[`items.${index}.productId`] = '请选择产品';
    if (!Number.isFinite(Number(item.quantity)) || Number(item.quantity) <= 0) formErrors[`items.${index}.quantity`] = '采购数量必须大于 0';
    if (!Number.isFinite(Number(item.unitPrice)) || Number(item.unitPrice) < 0) formErrors[`items.${index}.unitPrice`] = '采购单价不能小于 0';
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
      supplierProductId: item.supplierProductId || null,
      productId: item.productId,
      quantity: Number(item.quantity),
      unitPrice: Number(item.unitPrice),
      selectedSupplierScore: Number(item.selectedSupplierScore),
      expectedArrivalDate: item.expectedArrivalDate || form.expectedArrivalDate || null,
      remark: item.remark.trim(),
    })),
  };
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) return;
  formSubmitting.value = true;
  try {
    await createPurchaseOrder(buildPayload());
    toast.success('采购订单草稿已创建');
    createDialogOpen.value = false;
    await fetchOrders();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单保存失败');
  } finally {
    formSubmitting.value = false;
  }
}

function openDetail(row: PurchaseOrderListItem) {
  detailRow.value = row;
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
    submit: ['提交采购单', '提交后进入待审核状态，不能再作为普通草稿直接编辑。', '提交', 'default'],
    approve: ['审核采购单', '审核后应由后端生成采购入库流水草稿，库存变动仍以仓库模块确认为准。', '审核通过', 'warning'],
    cancel: ['取消采购单', '取消后该采购单保留追溯但不能继续流转。', '确认取消', 'destructive'],
  } as const;
  const [title, description, confirmText, variant] = config[action];
  showConfirm(title, description, confirmText, variant, async () => {
    await updatePurchaseOrderStatus(row.purchaseOrderId, action);
    toast.success('采购订单状态已更新');
    await fetchOrders();
  });
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
        <p class="page-description">创建采购草稿、提交审核，并追踪采购入库进度；库存变化统一由出入库流水确认</p>
      </div>
    </div>

    <div class="summary-strip">
      <div class="summary-item"><span class="text-xs text-muted-foreground">采购订单</span><strong class="mt-1 text-2xl">{{ summary.orderCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">草稿</span><strong class="mt-1 text-2xl">{{ summary.draftCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">已审核</span><strong class="mt-1 text-2xl text-emerald-700">{{ summary.approvedCount }}</strong></div>
      <div class="summary-item"><span class="text-xs text-muted-foreground">待入库</span><strong class="mt-1 text-2xl text-amber-700">{{ summary.inboundPendingCount }}</strong></div>
    </div>

    <div class="filter-panel">
      <div class="filter-grid">
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
        <div class="table-toolbar__title"><strong class="text-sm">采购订单列表</strong><span class="text-xs text-muted-foreground">审核动作只推进状态，实际入库由仓库出入库记录承接</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增采购单</Button></div>
      </div>

      <ScrollArea class="w-full">
        <Table class="min-w-[1320px] table-fixed">
          <colgroup><col class="w-[160px]" /><col class="w-[220px]" /><col class="w-[160px]" /><col class="w-[120px]" /><col class="w-[130px]" /><col class="w-[130px]" /><col class="w-[130px]" /><col class="w-[170px]" /><col class="w-[220px]" /></colgroup>
          <TableHeader><TableRow><TableHead>采购单号</TableHead><TableHead>供应商</TableHead><TableHead>入库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计到货</TableHead><TableHead>创建人</TableHead><TableHead>更新时间</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无采购订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.purchaseOrderId">
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.purchaseNo }}</code></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.supplierCode }}</code><div class="mt-1 truncate font-medium">{{ row.supplierName }}</div></TableCell>
              <TableCell>{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell>{{ row.expectedArrivalDate || '未设置' }}</TableCell>
              <TableCell>{{ row.createdByName || '系统' }}</TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ row.updateTime }}</TableCell>
              <TableCell class="text-right">
                <Button variant="ghost" size="sm" @click="openDetail(row)">详情</Button>
                <Button v-if="row.status === 'DRAFT'" variant="ghost" size="sm" @click="confirmOrderAction(row, 'submit')">提交</Button>
                <Button v-if="row.status === 'SUBMITTED'" variant="ghost" size="sm" @click="confirmOrderAction(row, 'approve')">审核</Button>
                <Button v-if="row.status === 'DRAFT' || row.status === 'SUBMITTED'" variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="confirmOrderAction(row, 'cancel')">取消</Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </ScrollArea>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="createDialogOpen">
      <DialogContent class="max-w-5xl">
        <DialogHeader><DialogTitle>新增采购单草稿</DialogTitle></DialogHeader>
        <DialogScrollArea class="max-h-[calc(100dvh-12rem)]">
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>供应商 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.supplierId" :options="supplierOptions.filter(item => item.value !== 'all')" placeholder="请选择供应商" :invalid="Boolean(formErrors.supplierId)" /><p v-if="formErrors.supplierId" class="form-error">{{ formErrors.supplierId }}</p></div>
              <div class="space-y-1"><Label>入库仓库 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.warehouseId" :options="warehouseOptions.filter(item => item.value !== 'all')" placeholder="请选择仓库" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="form-error">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>预计到货日期</Label><Input v-model="form.expectedArrivalDate" type="date" /></div>
            </div>
            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="2" /><p v-if="formErrors.remark" class="form-error">{{ formErrors.remark }}</p></div>

            <div class="rounded-md border border-border">
              <div class="flex min-h-11 items-center justify-between border-b border-border px-3"><strong class="text-sm">采购明细</strong><Button size="sm" variant="outline" type="button" @click="addLine">添加产品</Button></div>
              <ScrollArea class="w-full">
                <Table class="min-w-[1100px] table-fixed">
                  <colgroup><col class="w-[280px]" /><col class="w-[120px]" /><col class="w-[130px]" /><col class="w-[110px]" /><col class="w-[130px]" /><col class="w-[180px]" /><col class="w-[110px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">采购价</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell><AnchoredSelect :model-value="line.productId" :options="productOptions" placeholder="请选择产品" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="form-error">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell><Input v-model.number="line.quantity" type="number" min="0" step="0.0001" class="text-right" /><p v-if="formErrors[`items.${index}.quantity`]" class="form-error">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="pl-8 text-right" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="form-error">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
                      <TableCell class="text-center tabular-nums">{{ Number(line.selectedSupplierScore || 0).toFixed(1) }}</TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(Number(line.quantity || 0) * Number(line.unitPrice || 0)) }}</TableCell>
                      <TableCell><Input v-model="line.remark" placeholder="可选" /></TableCell>
                      <TableCell class="text-right"><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" :disabled="draftItems.length === 1" @click="removeLine(line.rowId)">删除</Button></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex justify-end border-t border-border px-4 py-3 text-sm">草稿金额：<strong class="ml-2 text-base">{{ formatMoney(totalAmount) }}</strong></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="createDialogOpen = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : '保存草稿' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailDialogOpen">
      <DialogContent class="max-w-4xl">
        <DialogHeader><DialogTitle>采购单详情</DialogTitle></DialogHeader>
        <DialogScrollArea class="max-h-[calc(100dvh-12rem)]">
          <div v-if="detailRow" class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">采购单号</span><div class="mt-1 font-medium">{{ detailRow.purchaseNo }}</div></div>
              <div><span class="text-muted-foreground">供应商</span><div class="mt-1 font-medium">{{ detailRow.supplierName }}</div></div>
              <div><span class="text-muted-foreground">入库仓库</span><div class="mt-1 font-medium">{{ detailRow.warehouseName }}</div></div>
              <div><span class="text-muted-foreground">状态</span><div class="mt-1"><Badge variant="outline" :class="statusMeta(detailRow.status).className">{{ statusMeta(detailRow.status).label }}</Badge></div></div>
              <div><span class="text-muted-foreground">订单金额</span><div class="mt-1 font-medium">{{ formatMoney(detailRow.totalAmount) }}</div></div>
              <div><span class="text-muted-foreground">预计到货</span><div class="mt-1 font-medium">{{ detailRow.expectedArrivalDate || '未设置' }}</div></div>
            </div>
            <Table class="table-fixed">
              <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">已入库</TableHead><TableHead class="text-right">单价</TableHead><TableHead class="text-right">金额</TableHead><TableHead class="text-center">推荐分快照</TableHead></TableRow></TableHeader>
              <TableBody>
                <TableRow v-for="item in detailRow.items" :key="item.purchaseOrderItemId">
                  <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell>
                  <TableCell class="text-right">{{ item.quantity }} {{ item.unitName }}</TableCell>
                  <TableCell class="text-right">{{ item.inboundQty }} {{ item.unitName }}</TableCell>
                  <TableCell class="text-right">{{ formatMoney(item.unitPrice) }}</TableCell>
                  <TableCell class="text-right">{{ formatMoney(item.totalAmount) }}</TableCell>
                  <TableCell class="text-center">{{ item.selectedSupplierScore.toFixed(1) }}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" @click="detailDialogOpen = false">关闭</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>
