<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { useRoute, useRouter } from 'vue-router';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import BusinessExecutionProgress from '@/components/common/BusinessExecutionProgress.vue';
import BusinessDetailHero from '@/components/common/BusinessDetailHero.vue';
import type { BusinessDetailProgressStep } from '@/components/common/BusinessDetailProgress.vue';
import BusinessDetailTimeline from '@/components/common/BusinessDetailTimeline.vue';
import type { BusinessDetailTimelineItem } from '@/components/common/BusinessDetailTimeline.vue';
import BusinessDetailWorkbenchCard from '@/components/common/BusinessDetailWorkbenchCard.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import OrderNumberLink from '@/components/common/OrderNumberLink.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import { MAX_SAFE_MONEY } from '@/shared/utils/money';
import {
  createSalesOrder,
  getEnabledSalesProductTotal,
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

const route = useRoute();
const router = useRouter();

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
const selectableProductTotal = ref<number | null>(null);
const selectedProductCount = computed(() => new Set(draftItems.value.map(item => item.productId).filter(Boolean)).size);
const canAddLine = computed(() => selectableProductTotal.value === null || selectedProductCount.value < selectableProductTotal.value);
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

async function fetchProductSearchOptions(keyword: string, currentRowId?: string) {
  const products = await listEnabledSalesProductOptions(keyword, 10);
  const selectedProductIds = new Set(draftItems.value
    .filter(item => item.rowId !== currentRowId)
    .map(item => item.productId)
    .filter(Boolean));
  const options = products.map(item => ({
    value: item.value,
    label: item.label,
    disabled: selectedProductIds.has(item.value),
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
  selectableProductTotal.value = null;
  editingOrder.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  void refreshSelectableProductTotal();
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
  void refreshSelectableProductTotal();
  createDialogOpen.value = true;
}

function addLine() {
  if (!canAddLine.value) return;
  draftItems.value = [...draftItems.value, newDraftItem()];
}

async function refreshSelectableProductTotal() {
  try {
    selectableProductTotal.value = await getEnabledSalesProductTotal();
  } catch {
    // 总数加载失败时保持原有可添加行为，最终仍由选择器和提交校验兜底。
    selectableProductTotal.value = null;
  }
}

function removeLine(rowId: string) {
  if (draftItems.value.length === 1) return;
  draftItems.value = draftItems.value.filter(item => item.rowId !== rowId);
}

function selectProduct(line: DraftItem, productId: string | number) {
  const selectedProductId = String(productId);
  if (draftItems.value.some(item => item.rowId !== line.rowId && item.productId === selectedProductId)) {
    formErrors[`items.${draftItems.value.findIndex(item => item.rowId === line.rowId)}.productId`] = '同一产品不能重复添加';
    toast.warning('同一产品不能重复添加');
    return;
  }
  line.productId = selectedProductId;
  delete formErrors[`items.${draftItems.value.findIndex(item => item.rowId === line.rowId)}.productId`];
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
    else if (draftItems.value.some(other => other.rowId !== item.rowId && other.productId === item.productId)) formErrors[`items.${index}.productId`] = '同一产品不能重复添加';
    if (!Number.isFinite(Number(item.quantity)) || Number(item.quantity) <= 0) formErrors[`items.${index}.quantity`] = '销售数量必须大于 0';
    if (item.productId && !quantityPrecisionValid(Number(item.quantity), item.productId)) formErrors[`items.${index}.quantity`] = `数量最多保留 ${getProductPrecision(item.productId)} 位小数`;
    if (!Number.isFinite(Number(item.unitPrice)) || Number(item.unitPrice) < 0 || Number(item.unitPrice) > MAX_SAFE_MONEY) formErrors[`items.${index}.unitPrice`] = '销售单价超出安全金额范围';
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
  detailRow.value = null;
  detailDialogOpen.value = true;
  detailLoading.value = true;
  try {
    detailRow.value = await getSalesOrderDetail(row.salesOrderId);
    detailActionMode.value = actionMode === 'view'
      ? (row.status === 'DRAFT' ? 'submit' : row.status === 'SUBMITTED' ? 'approve' : 'view')
      : actionMode;

  } catch (error) {
    detailDialogOpen.value = false;
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

function hasOrderActions(row: SalesOrderListItem) {
  return row.status === 'DRAFT' || row.status === 'SUBMITTED';
}

async function openDetailEdit(row: SalesOrderDetail) {
  detailDialogOpen.value = false;
  await nextTick();
  await openEditDialog(row);
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

function returnCoverageMeta(coverage: string) {
  return {
    NONE: { label: '待审批退货', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    PARTIAL: { label: '部分退货', className: 'border-sky-200 bg-sky-50 text-sky-700' },
    FULL: { label: '已全量退货', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  }[coverage] || { label: '退货处理中', className: 'border-muted bg-muted text-muted-foreground' };
}

function formatMoney(value: number) {
  return `￥${value.toFixed(2)}`;
}

function applyDashboardStatusPreset() {
  const status = route.query.status;
  if (typeof status !== 'string' || !statusOptions.some(option => option.value === status)) return;
  query.status = status as SalesOrderStatus;
  query.pageNum = 1;
}

function resetFilters() {
  if (Object.keys(route.query).length > 0) {
    void router.replace({ path: route.path });
    return;
  }
  handleReset();
}
onMounted(() => {
  applyDashboardStatusPreset();
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
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">销售订单列表</strong><span class="text-xs text-muted-foreground">点击“处理”查看详情并完成后续操作；审核只生成待确认出库单，库存扣减由仓库确认本次数量</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增销售单</Button></div>
      </div>

      <Table class="business-data-table min-w-[1227px] table-fixed" scroll-label="销售订单列表">
          <colgroup><col class="w-[270px]" /><col class="w-[145px]" /><col class="w-[105px]" /><col class="w-[120px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[135px]" /><col class="w-[96px]" /></colgroup>
          <TableHeader><TableRow><TableHead data-sales-no-column>销售单号</TableHead><TableHead>客户</TableHead><TableHead>出库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计发货</TableHead><TableHead>锁定数量</TableHead><TableHead>更新时间</TableHead><TableHead class="text-center" data-sales-actions-column>操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无销售订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.salesOrderId" class="group">
              <TableCell data-sales-no-column><OrderNumberLink :value="row.salesNo" label="销售单号" /></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.customerCode }}</code><div class="mt-1 truncate font-medium" :title="row.customerName">{{ row.customerName }}</div></TableCell>
              <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><div class="flex flex-col items-center gap-1"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge><span class="text-[11px] text-muted-foreground">{{ statusHint(row.status) }}</span></div></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell class="text-center text-sm">{{ row.expectedDeliveryDate || '未设置' }}</TableCell>
              <TableCell class="text-xs text-muted-foreground">{{ lockedInventoryText(row) }}</TableCell>
              <TableCell class="truncate whitespace-nowrap text-xs text-muted-foreground" :title="row.updateTime">{{ row.updateTime }}</TableCell>
              <TableCell class="text-center" data-sales-actions-column><Button variant="ghost" size="sm" :class="hasOrderActions(row) ? 'h-8 px-2.5 font-medium text-teal-700 hover:bg-teal-50 hover:text-teal-800' : 'h-8 px-2.5 font-medium text-indigo-600 hover:bg-indigo-50 hover:text-indigo-700'" :disabled="detailLoading || actionSubmitting" @click="openDetail(row)">{{ detailLoading ? '加载中' : hasOrderActions(row) ? '处理' : '查看' }}</Button></TableCell>
            </TableRow>
          </TableBody>
      </Table>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="createDialogOpen">
      <DialogContent placement="app-content" class="order-form-dialog flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-6xl" data-sales-form-dialog>
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
              <div class="flex min-h-11 items-center justify-between gap-3 border-b border-border px-3"><div><strong class="text-sm">销售明细</strong><span class="ml-2 text-xs text-muted-foreground">选择产品后填写数量和销售价，草稿阶段可继续调整</span></div><div class="flex shrink-0 items-center gap-3"><span class="text-xs text-muted-foreground">已选 {{ selectedProductCount }} 项</span><Button size="sm" variant="outline" type="button" :disabled="!canAddLine" @click="addLine">添加产品</Button></div></div>
              <ScrollArea class="w-full">
                <Table class="order-line-table min-w-[860px] table-fixed" data-sales-form-items>
                  <colgroup><col class="w-[250px]" /><col class="w-[115px]" /><col class="w-[120px]" /><col class="w-[115px]" /><col class="w-[180px]" /><col class="w-[80px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">销售价</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell class="align-top"><RemoteSearchSelect :model-value="line.productId" :selected-label="selectedProductLabel(line.productId)" :fetch-options="keyword => fetchProductSearchOptions(keyword, line.rowId)" placeholder="请选择产品" search-placeholder="输入产品编码或名称" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="mt-1 text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="flex items-center gap-2"><Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line.productId)" class="min-w-0 text-right" /><span v-if="line.unitName" class="shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span></div><p v-if="formErrors[`items.${index}.quantity`]" class="form-error text-center">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="price-input pl-8 text-center" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(Number(line.quantity || 0) * Number(line.unitPrice || 0)) }}</TableCell>
                      <TableCell class="align-top"><Input v-model="line.remark" placeholder="可选" /><p v-if="formErrors[`items.${index}.remark`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.remark`] }}</p></TableCell>
                      <TableCell class="align-top text-center"><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" :disabled="draftItems.length === 1" @click="removeLine(line.rowId)">删除</Button></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex items-center justify-between gap-3 border-t border-border px-4 py-3 text-sm"><span class="text-muted-foreground">已添加 {{ draftItems.length }} 条，提交时后端将重新校验库存与数量精度</span><span class="shrink-0">草稿金额：<strong class="ml-2 text-sm">{{ formatMoney(totalAmount) }}</strong></span></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="createDialogOpen = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : (dialogMode === 'create' ? '保存草稿' : '保存修改') }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailDialogOpen">
      <DialogContent placement="app-content" :inert="confirmState.open ? '' : undefined" data-order-workbench class="sales-order-workbench flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden !bg-[#f6f8fb] !p-4 sm:max-w-5xl">
        <DialogHeader class="sr-only"><DialogTitle>销售单详情</DialogTitle><DialogDescription>核对销售单头、明细数量、库存锁定和出库流转状态。</DialogDescription></DialogHeader>
        <DialogScrollArea content-class="px-5 py-5 pr-6">
          <div v-if="detailLoading && !detailRow" class="flex min-h-64 items-center justify-center gap-2 text-sm text-muted-foreground"><span class="page-loading-spinner" />详情加载中...</div>
          <div v-if="detailRow" class="space-y-5">
            <BusinessDetailHero
              eyebrow="销售订单"
              :title="detailRow.salesNo"
              :subtitle="`${detailRow.customerCode} · ${detailRow.customerName} · ${detailRow.warehouseName}`"
              :status-label="statusMeta(detailRow.status).label"
              :status-class="statusMeta(detailRow.status).className"
              :metric-columns="3"
              variant="canvas"
            >
              <template #metrics>
                <div class="business-detail-hero__metric"><span>订单金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>商品明细</span><strong>{{ detailRow.items.length }} 项</strong></div>
                <div class="business-detail-hero__metric"><span>预计发货</span><strong>{{ detailRow.expectedDeliveryDate || '未设置' }}</strong></div>
                <div class="business-detail-hero__metric"><span>累计出库</span><strong>{{ formatMoney(salesOutboundSummary(detailRow).completedAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>出库仓库</span><strong>{{ detailRow.warehouseName }}</strong></div>
                <div class="business-detail-hero__metric"><span>销售人员</span><strong>{{ detailRow.createdByName || '系统' }}</strong></div>
              </template>
            </BusinessDetailHero>

            <BusinessDetailWorkbenchCard><section class="sales-workbench-section sales-workbench-section--progress"><BusinessExecutionProgress
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
            /></section></BusinessDetailWorkbenchCard>

            <BusinessDetailWorkbenchCard v-if="detailRow.returnOverview && detailRow.returnOverview.hasReturnOrder">
              <section class="sales-workbench-section">
                <div class="mb-4 flex items-start justify-between gap-3"><div><h3 class="text-sm font-semibold">退货概览</h3><p class="mt-1 text-xs text-muted-foreground">退货不改变销售单主状态；金额仅统计已审批通过的退货事实。</p></div><Badge variant="outline" :class="returnCoverageMeta(detailRow.returnOverview.coverage).className">{{ returnCoverageMeta(detailRow.returnOverview.coverage).label }}</Badge></div>
                <dl class="sales-workbench-info__facts"><div class="sales-workbench-info__fact"><dt>关联退货单</dt><dd><strong>{{ detailRow.returnOverview.returnOrderCount }} 张</strong></dd></div><div class="sales-workbench-info__fact"><dt>已审批退货单</dt><dd><strong>{{ detailRow.returnOverview.effectiveReturnOrderCount }} 张</strong></dd></div><div class="sales-workbench-info__fact"><dt>已审批退货额</dt><dd><strong class="text-rose-700">{{ formatMoney(detailRow.returnOverview.approvedReturnAmount) }}</strong></dd></div><div class="sales-workbench-info__fact"><dt>退货覆盖度</dt><dd><strong>{{ returnCoverageMeta(detailRow.returnOverview.coverage).label }}</strong></dd></div></dl>
                <div v-if="detailRow.returnOverview.items?.length" class="mt-4"><div class="mb-2 flex items-end justify-between gap-3"><div><h4 class="text-sm font-semibold">明细退货覆盖</h4><p class="mt-1 text-xs text-muted-foreground">按来源销售明细核对出库与已审批退货数量。</p></div><span class="shrink-0 text-xs text-muted-foreground">共 {{ detailRow.returnOverview.items.length }} 项</span></div><ScrollArea class="w-full purchase-order-line-scroll detail-table-floating" aria-label="销售订单退货明细"><Table class="min-w-[840px] table-fixed"><colgroup><col class="w-[250px]" /><col class="w-[130px]" /><col class="w-[130px]" /><col class="w-[130px]" /><col class="w-[150px]" /></colgroup><TableHeader><TableRow><TableHead>来源商品</TableHead><TableHead class="text-center">订单数量</TableHead><TableHead class="text-center">已出库</TableHead><TableHead class="text-center">已审批退货</TableHead><TableHead class="text-right">已审批退货额</TableHead></TableRow></TableHeader><TableBody><TableRow v-for="item in detailRow.returnOverview.items" :key="item.salesOrderItemId"><TableCell class="truncate" :title="detailRow.items.find(orderItem => orderItem.salesOrderItemId === item.salesOrderItemId)?.productName || item.salesOrderItemId">{{ detailRow.items.find(orderItem => orderItem.salesOrderItemId === item.salesOrderItemId)?.productName || '订单明细' }}</TableCell><TableCell class="text-center tabular-nums">{{ item.orderedQty }}</TableCell><TableCell class="text-center tabular-nums">{{ item.fulfilledQty }}</TableCell><TableCell class="text-center font-medium tabular-nums text-rose-700">{{ item.approvedReturnQty }}</TableCell><TableCell class="text-right font-medium tabular-nums text-rose-700">{{ formatMoney(item.approvedReturnAmount) }}</TableCell></TableRow></TableBody></Table></ScrollArea></div>
              </section>
            </BusinessDetailWorkbenchCard>
            <BusinessDetailWorkbenchCard>
              <section class="sales-workbench-section sales-workbench-info"><h3 class="sales-workbench-info__title">业务信息</h3><dl class="sales-workbench-info__facts"><div class="sales-workbench-info__fact"><dt>客户</dt><dd><strong>{{ detailRow.customerName }}</strong><small>{{ detailRow.customerCode }}</small></dd></div><div class="sales-workbench-info__fact"><dt>订单日期</dt><dd>{{ detailRow.createTime }}</dd></div><div class="sales-workbench-info__fact"><dt>销售单号</dt><dd><code>{{ detailRow.salesNo }}</code></dd></div><div class="sales-workbench-info__fact"><dt>预计发货</dt><dd>{{ detailRow.expectedDeliveryDate || '未设置' }}</dd></div><div class="sales-workbench-info__fact"><dt>出库仓库</dt><dd>{{ detailRow.warehouseName }}</dd></div><div class="sales-workbench-info__fact"><dt>制单人</dt><dd>{{ detailRow.createdByName || '系统' }}</dd></div><div class="sales-workbench-info__fact"><dt>提交人</dt><dd>{{ detailRow.submittedByName || '未提交' }}</dd></div><div class="sales-workbench-info__fact"><dt>审核人</dt><dd>{{ detailRow.approvedByName || '未审核' }}</dd></div><div class="sales-workbench-info__fact sales-workbench-info__fact--note"><dt>备注</dt><dd>{{ detailRow.remark || '未填写' }}</dd></div></dl></section>
              <section class="sales-workbench-section"><div class="mb-2 flex items-end justify-between gap-3"><div><h3 class="text-sm font-semibold text-foreground">商品明细</h3><p class="mt-1 text-xs text-muted-foreground">优先核对销售、锁定、出库与剩余待出库数量。</p></div><span class="shrink-0 text-xs text-muted-foreground">共 {{ detailRow.items.length }} 项</span></div>
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
            </BusinessDetailWorkbenchCard>

            <BusinessDetailWorkbenchCard><section class="sales-workbench-section"><div class="mb-2"><h3 class="text-sm font-semibold">流程记录</h3><p class="mt-1 text-xs text-muted-foreground">聚合销售订单审计字段和仓储流转状态，不额外新增操作日志。</p></div><BusinessDetailTimeline :items="salesTimelineItems(detailRow)" aria-label="销售订单流程记录" /></section></BusinessDetailWorkbenchCard>
          </div>
        </DialogScrollArea>
        <DialogFooter class="items-center justify-between gap-3">
          <div class="mr-auto flex items-center gap-3">
            <Button v-if="detailRow && hasOrderActions(detailRow)" variant="outline" class="mr-auto border-rose-200 bg-white text-rose-700 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-800" :disabled="actionSubmitting" @click="confirmOrderAction(detailRow, 'cancel')">取消销售单</Button>
          </div>
          <Button v-if="detailRow && hasOrderActions(detailRow)" variant="outline" :disabled="actionSubmitting" @click="openDetailEdit(detailRow)">编辑</Button>
          <Button v-if="detailRow && detailActionMode !== 'view'" :disabled="actionSubmitting || !detailRow.expectedDeliveryDate" @click="runDetailAction(detailRow)">{{ actionSubmitting ? '处理中' : detailActionMode === 'submit' ? '提交销售单' : '审核通过' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog placement="app-content" :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>

<style scoped>
.price-input { appearance: textfield; }
.price-input::-webkit-inner-spin-button,
.price-input::-webkit-outer-spin-button { margin: 0; appearance: none; }

.sales-workbench-section { padding: 18px 20px; }
.sales-workbench-section + .sales-workbench-section { border-top: 1px solid #e5eaf0; }
.sales-workbench-section--progress { padding: 0; }
.sales-workbench-info__title { margin: 0 0 14px; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.sales-workbench-info__facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 42px; margin: 0; }
.sales-workbench-info__fact { display: grid; grid-template-columns: 76px minmax(0, 1fr); column-gap: 10px; align-items: start; min-width: 0; color: var(--foreground); font-size: 13px; line-height: 20px; }
.sales-workbench-info__fact dt { color: var(--muted-foreground); font-size: inherit; white-space: nowrap; }
.sales-workbench-info__fact dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.sales-workbench-info__fact dd > strong { font-weight: 600; }
.sales-workbench-info__fact dd > small { margin-left: 7px; color: var(--muted-foreground); font-size: inherit; }
.sales-workbench-info__fact dd > code { font-size: inherit; }
.sales-workbench-info__fact--note { grid-column: 1 / -1; }

@media (max-width: 640px) {
  .sales-workbench-info__facts { grid-template-columns: 1fr; gap: 9px; }
}
</style>
