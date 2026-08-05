<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import BusinessExecutionProgress from '@/components/common/BusinessExecutionProgress.vue';
import BusinessDetailFacts from '@/components/common/BusinessDetailFacts.vue';
import BusinessDetailHero from '@/components/common/BusinessDetailHero.vue';
import type { BusinessDetailProgressStep } from '@/components/common/BusinessDetailProgress.vue';
import BusinessDetailSection from '@/components/common/BusinessDetailSection.vue';
import BusinessDetailTimeline from '@/components/common/BusinessDetailTimeline.vue';
import type { BusinessDetailTimelineItem } from '@/components/common/BusinessDetailTimeline.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import RowActionsMenu from '@/components/common/RowActionsMenu.vue';
import type { RowActionOption } from '@/components/common/RowActionsMenu.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import {
  createSalesOrder,
  getSalesOrderDetail,
  listEnabledSalesProductOptions,
  listEnabledSalesWarehouseOptions,
  listSalesOrders,
  searchCustomerOptions,
  updateSalesOrder,
  updateSalesOrderStatus,
} from '../../api';
import type {
  SalesOrderDraftItemPayload,
  SalesOrderDetail,
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

const statusLabels: Record<SalesOrderStatus, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '待出库',
  PARTIAL_OUTBOUND: '部分出库',
  OUTBOUND_DONE: '已出库',
  CANCELLED: '已取消',
};

const statusClassNames: Record<SalesOrderStatus, string> = {
  DRAFT: 'border-slate-200 bg-slate-50 text-slate-600',
  SUBMITTED: 'border-blue-200 bg-blue-50 text-blue-700',
  APPROVED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL_OUTBOUND: 'border-amber-200 bg-amber-50 text-amber-700',
  OUTBOUND_DONE: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  CANCELLED: 'border-rose-200 bg-rose-50 text-rose-700',
};

const statusOptions: Array<{ value: SalesOrderStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  ...(['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND', 'OUTBOUND_DONE', 'CANCELLED'] as const)
    .map(value => ({ value, label: statusLabels[value] })),
];

const orders = ref<SalesOrderListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const summaryItems = computed(() => [
  { key: 'draft', label: '本页草稿', value: summary.draftCount },
  { key: 'submitted', label: '本页待审核', value: summary.submittedCount },
  { key: 'approved', label: '本页待出库', value: summary.approvedCount, tone: 'positive' as const },
  { key: 'outbound-pending', label: '本页出库未完成', value: summary.outboundPendingCount, tone: 'warning' as const },
]);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const detailLoading = ref(false);
const createDialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingOrder = ref<SalesOrderDetail | null>(null);
const detailDialogOpen = ref(false);
const detailRow = ref<SalesOrderDetail | null>(null);
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

function cacheOrderOptions(row: SalesOrderDetail) {
  mergeCustomerOptions([{ value: row.customerId, label: `${row.customerCode} ${row.customerName}` }]);
  mergeWarehouseOptions([{ value: row.warehouseId, label: row.warehouseName }]);
  mergeProductOptions(row.items.map(item => ({
    value: item.productId,
    label: `${item.productCode} ${item.productName}`,
    referenceSalePrice: item.unitPrice,
    quantityPrecision: item.quantityPrecision,
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
    Object.assign(summary, {
      draftCount: page.records.filter(item => item.status === 'DRAFT').length,
      submittedCount: page.records.filter(item => item.status === 'SUBMITTED').length,
      approvedCount: page.records.filter(item => item.status === 'APPROVED').length,
      outboundPendingCount: page.records.filter(item => item.status === 'APPROVED' || item.status === 'PARTIAL_OUTBOUND').length,
    });
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售订单加载失败');
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const {
  handleSearch,
  handleReset,
  handlePageChange,
  handlePageSizeChange,
  refreshList,
} = usePagedQuery({
  query,
  busy: queryBusy,
  pending: queryPending,
  load: fetchOrders,
  resetFilters: () => {
    Object.assign(query, { salesNo: '', customerId: 'all', warehouseId: 'all', status: 'all' });
  },
});

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

async function openEditDialog(row: SalesOrderListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') {
    toast.warning('仅草稿或已提交销售单可以编辑，审核后不能直接修改');
    return;
  }
  if (detailLoading.value) return;
  detailLoading.value = true;
  let detail: SalesOrderDetail;
  try {
    detail = await getSalesOrderDetail(row.salesOrderId);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售订单详情加载失败');
    return;
  } finally {
    detailLoading.value = false;
  }
  dialogMode.value = 'edit';
  resetForm();
  editingOrder.value = detail;
  cacheOrderOptions(detail);
  Object.assign(form, {
    customerId: detail.customerId,
    warehouseId: detail.warehouseId,
    expectedDeliveryDate: detail.expectedDeliveryDate || '',
    remark: detail.remark,
    items: [],
  });
  draftItems.value = detail.items.length > 0
    ? detail.items.map(item => ({
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
  return productOptions.value.find(item => item.value === productId)?.quantityPrecision ?? 0;
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

async function openDetail(row: SalesOrderListItem, actionMode: 'view' | 'submit' | 'approve' = 'view') {
  if (detailLoading.value) return;
  detailLoading.value = true;
  try {
    detailRow.value = await getSalesOrderDetail(row.salesOrderId);
    detailActionMode.value = actionMode;
    detailDialogOpen.value = true;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '销售订单详情加载失败');
  } finally {
    detailLoading.value = false;
  }
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

function getRowActions(row: SalesOrderListItem): RowActionOption[] {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') return [];

  return [
    { key: 'edit', label: '编辑销售单' },
    row.status === 'DRAFT'
      ? { key: 'submit', label: '提交销售单' }
      : { key: 'approve', label: '审核销售单' },
    { key: 'cancel', label: '取消销售单', variant: 'destructive', separated: true },
  ];
}

function handleRowAction(row: SalesOrderListItem, actionKey: string) {
  if (detailLoading.value || actionSubmitting.value) return;
  if (actionKey === 'edit') openEditDialog(row);
  if (actionKey === 'submit' && row.status === 'DRAFT') openOrderActionDetail(row, 'submit');
  if (actionKey === 'approve' && row.status === 'SUBMITTED') openOrderActionDetail(row, 'approve');
  if (actionKey === 'cancel' && (row.status === 'DRAFT' || row.status === 'SUBMITTED')) confirmOrderAction(row, 'cancel');
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
  return { label: statusLabels[status], className: statusClassNames[status] };
}

function statusHint(status: SalesOrderStatus) {
  const map: Record<SalesOrderStatus, string> = {
    DRAFT: '待提交',
    SUBMITTED: '等待审核',
    APPROVED: '等待出库',
    PARTIAL_OUTBOUND: '出库处理中',
    OUTBOUND_DONE: '流程完成',
    CANCELLED: '流程终止',
  };
  return map[status];
}

function salesProgressSteps(row: SalesOrderDetail): BusinessDetailProgressStep[] {
  if (row.status === 'CANCELLED') {
    return [
      { label: '草稿', state: 'done', hint: row.createTime },
      { label: '已取消', state: 'cancelled', hint: '订单流程已终止' },
    ];
  }

  const activeStatus = row.status as Exclude<SalesOrderStatus, 'CANCELLED'>;
  const currentIndex: Record<Exclude<SalesOrderStatus, 'CANCELLED'>, number> = {
    DRAFT: 0,
    SUBMITTED: 1,
    APPROVED: 2,
    PARTIAL_OUTBOUND: 3,
    OUTBOUND_DONE: 4,
  };
  const labels = ['草稿', '待审核', '待出库', '出库处理中', '已出库'];
  const hints = [
    row.createTime,
    row.submittedAt || '等待提交',
    row.approvedAt || '等待审核',
    row.status === 'PARTIAL_OUTBOUND' ? '仍有明细待出库' : '等待出库',
    row.status === 'OUTBOUND_DONE' ? row.updateTime : '等待完成',
  ];

  return labels.map((label, index) => ({
    label,
    hint: hints[index],
    state: index < currentIndex[activeStatus] ? 'done' : index === currentIndex[activeStatus] ? 'current' : 'pending',
  }));
}

function salesOutboundSummary(row: SalesOrderDetail) {
  const totalItems = row.items.length;
  const outboundItems = row.items.filter(item => Number(item.outboundQty || 0) > 0).length;
  const completedItems = row.items.filter(item => Number(item.quantity || 0) > 0 && Number(item.outboundQty || 0) >= Number(item.quantity || 0)).length;
  const pendingItems = totalItems - completedItems;
  const completedAmount = row.items.reduce((sum, item) => {
    const plannedQty = Math.max(0, Number(item.quantity || 0));
    const outboundQty = Math.max(0, Number(item.outboundQty || 0));
    return sum + Math.min(plannedQty, outboundQty) * Math.max(0, Number(item.unitPrice || 0));
  }, 0);
  const targetAmount = Math.max(0, Number(row.totalAmount || 0));

  return {
    outboundItems,
    completedItems,
    pendingItems,
    completedAmount,
    completionRate: targetAmount > 0 ? completedAmount / targetAmount * 100 : 0,
  };
}

function salesTimelineItems(row: SalesOrderDetail): BusinessDetailTimelineItem[] {
  const items: BusinessDetailTimelineItem[] = [
    { id: 'created', action: '创建销售订单', type: '单据创建', operatorName: row.createdByName || '系统', occurredAt: row.createTime },
  ];
    if (row.submittedAt) items.push({ id: 'submitted', action: '提交销售订单审核', type: '审核流转', tone: 'review', operatorName: row.submittedByName || '系统', occurredAt: row.submittedAt });
  if (row.approvedAt) items.push({ id: 'approved', action: '审核通过销售订单', type: '审核完成', tone: 'review', operatorName: row.approvedByName || '系统', occurredAt: row.approvedAt });
  if (row.status === 'PARTIAL_OUTBOUND' || row.status === 'OUTBOUND_DONE') items.push({ id: 'outbound', action: row.status === 'OUTBOUND_DONE' ? '完成销售出库' : '生成并处理出库任务', type: '仓库出库', tone: 'warehouse', operatorName: '系统', occurredAt: row.updateTime });
  return items;
}

function formatQty(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 3 });
}

function lockedInventoryText(row: SalesOrderListItem | SalesOrderDetail) {
  if (!('items' in row)) {
    if (row.status === 'CANCELLED') return '已释放';
    if (row.status === 'OUTBOUND_DONE') return '已出库';
    return row.lockedAt ? '已锁定' : '未锁定';
  }
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

    <ListSummaryStrip :items="summaryItems" aria-label="销售订单数据汇总" />

    <ListFilterPanel layout="content" aria-label="销售订单筛选">
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">销售单号</Label><Input v-model="query.salesNo" placeholder="如 SO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">客户</Label><RemoteSearchSelect v-model="query.customerId" :selected-label="queryCustomerLabel" :fetch-options="fetchCustomerSearchOptions" placeholder="全部客户" search-placeholder="输入客户编码或名称" clearable clear-value="all" clear-label="全部客户" /></div>
        <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">出库仓库</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="queryWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
        <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">订单状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
      <template #actions>
        <ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" />
      </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">销售订单列表</strong><span class="text-xs text-muted-foreground">审核动作只生成待确认出库单，库存扣减由仓库确认本次数量</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增销售单</Button></div>
      </div>

      <Table class="business-data-table min-w-[1087px] table-fixed" scroll-label="销售订单列表">
          <colgroup><col class="w-[130px]" /><col class="w-[145px]" /><col class="w-[105px]" /><col class="w-[120px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[135px]" /><col class="w-[132px]" /></colgroup>
          <TableHeader><TableRow><TableHead data-sales-no-column>销售单号</TableHead><TableHead>客户</TableHead><TableHead>出库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计发货</TableHead><TableHead>锁定数量</TableHead><TableHead>更新时间</TableHead><TableHead class="text-center" data-sales-actions-column>操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无销售订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.salesOrderId" class="group">
              <TableCell data-sales-no-column><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.salesNo }}</code></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.customerCode }}</code><div class="mt-1 truncate font-medium" :title="row.customerName">{{ row.customerName }}</div></TableCell>
              <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><div class="flex flex-col items-center gap-1"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge><span class="text-[11px] text-muted-foreground">{{ statusHint(row.status) }}</span></div></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell class="text-center text-sm">{{ row.expectedDeliveryDate || '未设置' }}</TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ lockedInventoryText(row) }}</TableCell>
              <TableCell class="truncate whitespace-nowrap text-xs text-muted-foreground" :title="row.updateTime">{{ row.updateTime }}</TableCell>
              <TableCell class="text-center" data-sales-actions-column>
                <div class="inline-flex flex-nowrap items-center justify-center gap-1 whitespace-nowrap">
                <Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" :disabled="detailLoading" @click="openDetail(row)">{{ detailLoading ? '加载中' : '详情' }}</Button>
                <RowActionsMenu :actions="getRowActions(row)" :disabled="detailLoading || actionSubmitting" :label="`更多 ${row.salesNo} 操作`" @select="handleRowAction(row, $event)" />
                </div>
              </TableCell>
            </TableRow>
          </TableBody>
      </Table>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="createDialogOpen">
      <DialogContent class="order-form-dialog flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-5xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增销售单草稿' : '编辑销售单' }}</DialogTitle><DialogDescription>销售单保存为草稿后可提交审核，提交时校验并锁定可用库存，审核后生成待确认出库单。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">销售单号</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? editingOrder.salesNo : '后端自动生成' }}</div></div>
              <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? statusMeta(editingOrder.status).label : '保存后为草稿' }}</div></div>
              <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? `${editingOrder.createdByName || '系统'} / ${editingOrder.createTime}` : '当前登录用户' }}</div></div>
            </div>
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>客户 <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.customerId" :selected-label="selectedCustomerLabel" :fetch-options="fetchCustomerSearchOptions" placeholder="请选择客户" search-placeholder="输入客户编码或名称" :invalid="Boolean(formErrors.customerId)" /><p v-if="formErrors.customerId" class="text-xs text-destructive">{{ formErrors.customerId }}</p></div>
              <div class="space-y-1"><Label>出库仓库 <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="请选择仓库" search-placeholder="输入仓库编码或名称" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="text-xs text-destructive">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>预计发货</Label><OrderDatePicker v-model="form.expectedDeliveryDate" :invalid="Boolean(formErrors.expectedDeliveryDate)" /><p v-if="formErrors.expectedDeliveryDate" class="text-xs text-destructive">{{ formErrors.expectedDeliveryDate }}</p><p v-else class="text-sm text-muted-foreground">请选择预计发货日期</p></div>
            </div>
            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="2" /><p v-if="formErrors.remark" class="text-xs text-destructive">{{ formErrors.remark }}</p></div>

            <div class="rounded-md border border-border">
              <div class="flex min-h-11 items-center justify-between border-b border-border px-3"><strong class="text-sm">销售明细</strong><Button size="sm" variant="outline" type="button" @click="addLine">添加产品</Button></div>
              <ScrollArea class="w-full purchase-order-line-scroll">
                <Table class="order-line-table min-w-[830px] table-fixed">
                  <colgroup><col class="w-[230px]" /><col class="w-[115px]" /><col class="w-[120px]" /><col class="w-[115px]" /><col class="w-[170px]" /><col class="w-[80px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">销售价</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell class="align-top"><RemoteSearchSelect :model-value="line.productId" :selected-label="selectedProductLabel(line.productId)" :fetch-options="fetchProductSearchOptions" placeholder="请选择产品" search-placeholder="输入产品编码或名称" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="purchase-line-quantity-cell"><div class="purchase-line-quantity-control" :class="{ 'purchase-line-quantity-control--single': !line.unitName }"><Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line.productId)" class="purchase-line-quantity-input" /><span v-if="line.unitName" class="purchase-line-quantity-unit">{{ line.unitName }}</span></div><p v-if="formErrors[`items.${index}.quantity`]" class="form-error text-center">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="pl-8 text-center" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(Number(line.quantity || 0) * Number(line.unitPrice || 0)) }}</TableCell>
                      <TableCell class="align-top"><Input v-model="line.remark" placeholder="可选" /><p v-if="formErrors[`items.${index}.remark`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.remark`] }}</p></TableCell>
                      <TableCell class="align-top text-center"><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" :disabled="draftItems.length === 1" @click="removeLine(line.rowId)">删除</Button></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex justify-end border-t border-border px-4 py-3 text-sm">草稿金额：<strong class="ml-2 text-sm">{{ formatMoney(totalAmount) }}</strong></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="createDialogOpen = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : (dialogMode === 'create' ? '保存草稿' : '保存修改') }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailDialogOpen">
      <DialogContent placement="app-content" class="flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-6xl">
        <DialogHeader><DialogTitle>销售单详情</DialogTitle><DialogDescription>核对销售单头、明细数量、库存锁定和出库流转状态。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-6 p-1">
            <BusinessDetailHero
              eyebrow="销售订单"
              :title="detailRow.salesNo"
              :subtitle="`${detailRow.customerCode} · ${detailRow.customerName} · ${detailRow.warehouseName}`"
              :status-label="statusMeta(detailRow.status).label"
              :status-class="statusMeta(detailRow.status).className"
            >
              <template #metrics>
                <div class="business-detail-hero__metric"><span>订单金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>商品明细</span><strong>{{ detailRow.items.length }} 项</strong></div>
                <div class="business-detail-hero__metric"><span>预计发货</span><strong>{{ detailRow.expectedDeliveryDate || '未设置' }}</strong></div>
                <div class="business-detail-hero__metric"><span>库存锁定</span><strong>{{ lockedInventoryText(detailRow) }}</strong></div>
              </template>
            </BusinessDetailHero>

            <BusinessExecutionProgress
              :steps="salesProgressSteps(detailRow)"
              description="状态由销售、审核与仓储出库流程生成，不能在详情中直接修改。"
              amount-label="累计出库"
              :completed-amount="salesOutboundSummary(detailRow).completedAmount"
              :completion-rate="salesOutboundSummary(detailRow).completionRate"
              completion-rate-hint="完成率按累计出库金额 ÷ 订单总金额计算。"
              :metrics="[
                { label: '已出库明细', value: salesOutboundSummary(detailRow).outboundItems },
                { label: '待出库明细', value: salesOutboundSummary(detailRow).pendingItems, pending: salesOutboundSummary(detailRow).pendingItems > 0 },
                { label: '已完成明细', value: salesOutboundSummary(detailRow).completedItems },
              ]"
            />

            <BusinessDetailSection title="业务信息" description="客户、出库仓库与发货安排。"><BusinessDetailFacts><div class="business-detail-fact"><dt>销售单号</dt><dd><code>{{ detailRow.salesNo }}</code></dd></div><div class="business-detail-fact"><dt>客户</dt><dd><strong>{{ detailRow.customerName }}</strong><small>{{ detailRow.customerCode }}</small></dd></div><div class="business-detail-fact"><dt>出库仓库</dt><dd><strong>{{ detailRow.warehouseName }}</strong></dd></div><div class="business-detail-fact"><dt>预计发货</dt><dd><strong>{{ detailRow.expectedDeliveryDate || '未设置' }}</strong></dd></div></BusinessDetailFacts></BusinessDetailSection>

            <section class="space-y-3"><div class="flex items-end justify-between gap-3"><div><h3 class="text-sm font-semibold text-foreground">商品明细</h3><p class="mt-1 text-xs text-muted-foreground">优先核对销售、锁定、出库与剩余待出库数量。</p></div><span class="shrink-0 text-xs text-muted-foreground">共 {{ detailRow.items.length }} 项</span></div>
              <ScrollArea class="w-full purchase-order-line-scroll detail-table-floating" aria-label="销售订单商品明细">
                <Table class="min-w-[1020px] table-fixed">
                  <colgroup><col class="w-[240px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[120px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[160px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">销售数量</TableHead><TableHead class="text-center">已锁定</TableHead><TableHead class="text-center">已出库</TableHead><TableHead class="text-center">待出库</TableHead><TableHead class="text-center">单价</TableHead><TableHead class="text-center">金额</TableHead><TableHead>明细备注</TableHead></TableRow></TableHeader>
                  <TableBody><TableRow v-for="item in detailRow.items" :key="item.salesOrderItemId">
                    <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.quantity }} {{ item.unitName }}</TableCell><TableCell class="text-center tabular-nums">{{ item.lockedQty }} {{ item.unitName }}</TableCell><TableCell class="text-center tabular-nums">{{ item.outboundQty }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center font-medium tabular-nums" :class="Number(item.quantity) > Number(item.outboundQty) ? 'text-amber-700' : 'text-emerald-700'">{{ Math.max(0, Number(item.quantity) - Number(item.outboundQty)) }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell><TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell><TableCell><OverflowTooltip :text="item.remark" fallback="未维护" class="block text-muted-foreground" /></TableCell>
                  </TableRow></TableBody>
                </Table>
              </ScrollArea>
            </section>

            <BusinessDetailSection title="流程记录" description="聚合销售订单审计字段和仓储流转状态，不额外新增操作日志。"><BusinessDetailTimeline :items="salesTimelineItems(detailRow)" aria-label="销售订单流程记录" /><p v-if="detailRow.remark" class="mt-3 rounded-md bg-muted px-3 py-2 text-xs leading-5 text-muted-foreground"><span class="mr-2 font-semibold text-foreground">备注</span>{{ detailRow.remark }}</p></BusinessDetailSection>
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

<style scoped>
.order-form-dialog :deep(input),
.order-form-dialog :deep(textarea),
.order-form-dialog :deep([role="combobox"]),
.order-form-dialog :deep([data-anchored-select-trigger]) { font-size: .875rem; }
.order-line-table :deep(th), .order-line-table :deep(td) { font-size: .875rem; }
.order-line-table :deep(input::placeholder) { font-size: .875rem; }
</style>
