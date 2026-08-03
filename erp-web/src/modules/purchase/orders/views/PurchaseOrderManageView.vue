<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import BusinessDetailHero from '@/components/common/BusinessDetailHero.vue';
import BusinessDetailProgress from '@/components/common/BusinessDetailProgress.vue';
import type { BusinessDetailProgressStep } from '@/components/common/BusinessDetailProgress.vue';
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
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import {
  createPurchaseOrder,
  getPurchaseOrderDetail,
  listEnabledProductOptions,
  listEnabledWarehouseOptions,
  listPurchaseOrders,
  listSupplierProducts,
  searchSupplierOptions,
  updatePurchaseOrder,
  updatePurchaseOrderStatus,
} from '../../api';
import type {
  PurchaseOrderDraftItemPayload,
  PurchaseOrderDetail,
  PurchaseOrderFormPayload,
  PurchaseOrderListItem,
  PurchaseOrderQuery,
  PurchaseOrderStatus,
  PurchaseOrderSummary,
  PurchaseOrderTimelineEvent,
  SupplierProductListItem,
} from '../../types';

interface Option {
  value: string;
  label: string;
  disabled?: boolean;
}

interface DraftItem extends Omit<PurchaseOrderDraftItemPayload, 'supplierProductId'> {
  rowId: string;
  supplierProductId: string | null;
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

const statusLabels: Record<PurchaseOrderStatus, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '待入库',
  PARTIAL_INBOUND: '部分入库',
  INBOUND_DONE: '已入库',
  CANCELLED: '已取消',
};

const statusClassNames: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'border-slate-200 bg-slate-50 text-slate-600',
  SUBMITTED: 'border-blue-200 bg-blue-50 text-blue-700',
  APPROVED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL_INBOUND: 'border-amber-200 bg-amber-50 text-amber-700',
  INBOUND_DONE: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  CANCELLED: 'border-rose-200 bg-rose-50 text-rose-700',
};

const statusOptions: Array<{ value: PurchaseOrderStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  ...(['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL_INBOUND', 'INBOUND_DONE', 'CANCELLED'] as const)
    .map(value => ({ value, label: statusLabels[value] })),
];

const orders = ref<PurchaseOrderListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const summaryItems = computed(() => [
  { key: 'draft', label: '本页草稿', value: summary.draftCount },
  { key: 'submitted', label: '本页待审核', value: summary.submittedCount },
  { key: 'approved', label: '本页待入库', value: summary.approvedCount, tone: 'positive' as const },
  { key: 'inbound-pending', label: '本页入库未完成', value: summary.inboundPendingCount, tone: 'warning' as const },
]);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const detailLoading = ref(false);
const createDialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingOrder = ref<PurchaseOrderDetail | null>(null);
const detailDialogOpen = ref(false);
const detailRow = ref<PurchaseOrderDetail | null>(null);
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
const selectedSupplierLabel = computed(() => supplierOptions.value.find(item => item.value === form.supplierId)?.label || (editingOrder.value?.supplierId === form.supplierId ? `${editingOrder.value.supplierCode} ${editingOrder.value.supplierName}` : ''));
const selectedWarehouseLabel = computed(() => warehouseOptions.value.find(item => item.value === form.warehouseId)?.label || (editingOrder.value?.warehouseId === form.warehouseId ? editingOrder.value.warehouseName : ''));
const querySupplierLabel = computed(() => query.supplierId === 'all' ? '全部供应商' : supplierOptions.value.find(item => item.value === query.supplierId)?.label || '');
const queryWarehouseLabel = computed(() => query.warehouseId === 'all' ? '全部仓库' : warehouseOptions.value.find(item => item.value === query.warehouseId)?.label || '');

function selectedProductLabel(productId: string) {
  return productOptions.value.find(item => item.value === productId)?.label
    || (() => {
      const item = editingOrder.value?.items.find(candidate => candidate.productId === productId && candidate.productCode);
      return item ? `${item.productCode} ${item.productName}` : '';
    })()
    || '';
}

function keywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { productCode: value } : { productName: value };
}

function mergeSupplierOptions(options: Option[]) {
  const cache = new Map(supplierOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  supplierOptions.value = [
    { value: 'all', label: '全部供应商' },
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

function mergeProductOptions(options: Array<Option & { referencePurchasePrice: number; quantityPrecision: number; unitName: string }>) {
  const cache = new Map(productOptions.value.map(item => [item.value, item]));
  options.forEach(item => cache.set(item.value, item));
  productOptions.value = Array.from(cache.values());
}

function mergeSupplierProducts(records: SupplierProductListItem[]) {
  const cache = new Map(supplierProducts.value.map(item => [item.supplierProductId, item]));
  records.forEach(item => cache.set(item.supplierProductId, item));
  supplierProducts.value = Array.from(cache.values());
}

async function fetchSupplierSearchOptions(keyword: string) {
  const suppliers = await searchSupplierOptions(keyword, 10);
  const options = suppliers.map(item => ({ value: item.supplierId, label: `${item.supplierCode} ${item.supplierName}`, disabled: item.status === 0 }));
  mergeSupplierOptions(options);
  return options;
}

async function fetchFormSupplierSearchOptions(keyword: string) {
  // 采购订单表单只展示有启用供货关系的供应商，通过供货产品接口提取 distinct 供应商
  const page = await listSupplierProducts({
    pageNum: 1,
    pageSize: 20,
    status: 1,
    ...(keyword ? { supplierName: keyword } : {}),
  });
  mergeSupplierProducts(page.records);
  const seen = new Set<string>();
  const options: Array<{ value: string; label: string; disabled?: boolean }> = [];
  page.records.forEach(item => {
    if (seen.has(item.supplierId)) return;
    seen.add(item.supplierId);
    options.push({ value: item.supplierId, label: `${item.supplierCode} ${item.supplierName}`, disabled: false });
  });
  mergeSupplierOptions(options);
  return options;
}

async function fetchWarehouseSearchOptions(keyword: string) {
  const warehouses = await listEnabledWarehouseOptions(keyword, 10);
  const options = warehouses.map(item => ({ value: item.value, label: item.label }));
  mergeWarehouseOptions(options);
  return options;
}

async function fetchPurchaseProductSearchOptions(keyword: string) {
  const page = await listSupplierProducts({
    pageNum: 1,
    pageSize: 10,
    status: 1,
    ...(form.supplierId ? { supplierId: form.supplierId } : {}),
    ...keywordQuery(keyword),
  });
  mergeSupplierProducts(page.records);
  const options = page.records.map(item => ({
    value: item.productId,
    label: `${item.productCode} ${item.productName}`,
    referencePurchasePrice: item.latestPurchasePrice ?? 0,
    quantityPrecision: item.quantityPrecision,
    unitName: item.unitName,
  }));
  mergeProductOptions(options);
  return options;
}

function cacheOrderOptions(row: PurchaseOrderDetail) {
  mergeSupplierOptions([{ value: row.supplierId, label: `${row.supplierCode} ${row.supplierName}` }]);
  mergeWarehouseOptions([{ value: row.warehouseId, label: row.warehouseName }]);
  mergeProductOptions(row.items.map(item => ({
    value: item.productId,
    label: `${item.productCode} ${item.productName}`,
    referencePurchasePrice: item.unitPrice,
    quantityPrecision: item.quantityPrecision,
    unitName: item.unitName,
  })));
  mergeSupplierProducts(row.items
    .filter(item => item.supplierProductId)
    .map(item => ({
      supplierProductId: item.supplierProductId || '',
      supplierId: row.supplierId,
      supplierCode: row.supplierCode,
      supplierName: row.supplierName,
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      unitName: item.unitName,
      quantityPrecision: item.quantityPrecision,
      supplierProductCode: '',
      latestPurchasePrice: item.unitPrice,
      minOrderQty: 1,
      leadTimeDays: 0,
      deliveryScore: item.selectedSupplierScore,
      qualityScore: item.selectedSupplierScore,
      priceScore: item.selectedSupplierScore,
      aiScore: item.selectedSupplierScore,
      lastPurchaseAt: null,
      status: 1,
      version: 0,
      remark: '',
      createTime: row.createTime,
      updateTime: row.updateTime,
    })));
}

async function loadOptions() {
  try {
    const [suppliers, warehouses, products] = await Promise.all([
      searchSupplierOptions('', 10),
      listEnabledWarehouseOptions('', 10),
      listEnabledProductOptions('', 10),
    ]);
    mergeSupplierOptions(suppliers.map(item => ({ value: item.supplierId, label: `${item.supplierCode} ${item.supplierName}`, disabled: item.status === 0 })));
    mergeWarehouseOptions(warehouses.map(item => ({ value: item.value, label: item.label })));
    mergeProductOptions(products.map(item => ({
      value: item.value,
      label: item.label,
      referencePurchasePrice: item.product.referencePurchasePrice,
      quantityPrecision: item.product.quantityPrecision,
      unitName: item.product.unitName,
    })));
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
    Object.assign(summary, {
      draftCount: page.records.filter(item => item.status === 'DRAFT').length,
      submittedCount: page.records.filter(item => item.status === 'SUBMITTED').length,
      approvedCount: page.records.filter(item => item.status === 'APPROVED').length,
      inboundPendingCount: page.records.filter(item => item.status === 'APPROVED' || item.status === 'PARTIAL_INBOUND').length,
    });
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单加载失败');
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
    Object.assign(query, { purchaseNo: '', supplierId: 'all', warehouseId: 'all', status: 'all' });
  },
});

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

async function openEditDialog(row: PurchaseOrderListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') {
    toast.warning('仅草稿或已提交采购单可以编辑，审核后不能直接修改');
    return;
  }
  if (detailLoading.value) return;
  detailLoading.value = true;
  let detail: PurchaseOrderDetail;
  try {
    detail = await getPurchaseOrderDetail(row.purchaseOrderId);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单详情加载失败');
    return;
  } finally {
    detailLoading.value = false;
  }
  dialogMode.value = 'edit';
  resetForm();
  editingOrder.value = detail;
  cacheOrderOptions(detail);
  Object.assign(form, {
    supplierId: detail.supplierId,
    warehouseId: detail.warehouseId,
    expectedArrivalDate: detail.expectedArrivalDate || '',
    remark: detail.remark,
    items: [],
  });
  draftItems.value = detail.items.length > 0
    ? detail.items.map(item => ({
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
  line.unitPrice = supplierProduct.latestPurchasePrice
    ?? productOptions.value.find(item => item.value === supplierProduct.productId)?.referencePurchasePrice
    ?? 0;
  line.selectedSupplierScore = supplierProduct.aiScore;
  line.unitName = supplierProduct.unitName;
}

function handleSupplierChange(value: string | number) {
  const newSupplierId = String(value);
  const supplierChanged = form.supplierId && form.supplierId !== newSupplierId;
  form.supplierId = newSupplierId;
  // 切换供应商后旧明细的产品不属于新供应商，清空明细（保留一行空行）
  if (supplierChanged && draftItems.value.some(line => line.productId)) {
    draftItems.value = [newDraftItem()];
  }
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
    ...(dialogMode.value === 'edit' && editingOrder.value ? { version: editingOrder.value.version } : {}),
    supplierId: form.supplierId,
    warehouseId: form.warehouseId,
    expectedArrivalDate: form.expectedArrivalDate || null,
    remark: form.remark.trim(),
    items: draftItems.value.map(item => ({
      ...(item.purchaseOrderItemId ? { purchaseOrderItemId: item.purchaseOrderItemId } : {}),
      supplierProductId: findSupplierProduct(form.supplierId, item.productId)!.supplierProductId,
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

async function openDetail(row: PurchaseOrderListItem, actionMode: 'view' | 'submit' | 'approve' = 'view') {
  if (detailLoading.value) return;
  detailLoading.value = true;
  try {
    detailRow.value = await getPurchaseOrderDetail(row.purchaseOrderId);
    detailActionMode.value = actionMode;
    detailDialogOpen.value = true;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '采购订单详情加载失败');
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

function confirmOrderAction(row: PurchaseOrderListItem, action: 'submit' | 'approve' | 'cancel') {
  const config = {
    submit: ['提交采购单', '提交后进入待审核状态，预计到货日期不能为空；已提交采购单只允许具备审核权限的人继续修改。', '提交', 'default'],
    approve: ['审核采购单', '审核后后端会生成待确认入库单，采购单本身不得再直接修改；库存变动仍以仓库模块确认本次数量为准。', '审核通过', 'warning'],
    cancel: ['取消采购单', '取消后该采购单保留追溯但不能继续流转。', '确认取消', 'destructive'],
  } as const;
  const [title, description, confirmText, variant] = config[action];
  showConfirm(title, description, confirmText, variant, async () => {
    await updatePurchaseOrderStatus(row.purchaseOrderId, action, row.version);
    toast.success('采购订单状态已更新');
    await fetchOrders();
  });
}

function openOrderActionDetail(row: PurchaseOrderListItem, action: 'submit' | 'approve') {
  openDetail(row, action);
}

function getRowActions(row: PurchaseOrderListItem): RowActionOption[] {
  if (row.status !== 'DRAFT' && row.status !== 'SUBMITTED') return [];

  return [
    { key: 'edit', label: '编辑采购单' },
    row.status === 'DRAFT'
      ? { key: 'submit', label: '提交采购单' }
      : { key: 'approve', label: '审核采购单' },
    { key: 'cancel', label: '取消采购单', variant: 'destructive', separated: true },
  ];
}

function handleRowAction(row: PurchaseOrderListItem, actionKey: string) {
  if (detailLoading.value || actionSubmitting.value) return;
  if (actionKey === 'edit') openEditDialog(row);
  if (actionKey === 'submit' && row.status === 'DRAFT') openOrderActionDetail(row, 'submit');
  if (actionKey === 'approve' && row.status === 'SUBMITTED') openOrderActionDetail(row, 'approve');
  if (actionKey === 'cancel' && (row.status === 'DRAFT' || row.status === 'SUBMITTED')) confirmOrderAction(row, 'cancel');
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
  return { label: statusLabels[status], className: statusClassNames[status] };
}

function statusHint(status: PurchaseOrderStatus) {
  const map: Record<PurchaseOrderStatus, string> = {
    DRAFT: '待提交',
    SUBMITTED: '等待审核',
    APPROVED: '等待入库',
    PARTIAL_INBOUND: '部分入库',
    INBOUND_DONE: '流程完成',
    CANCELLED: '流程终止',
  };
  return map[status];
}

function purchaseProgressSteps(row: PurchaseOrderDetail): BusinessDetailProgressStep[] {
  if (row.status === 'CANCELLED') {
    return [
      { label: '草稿', state: 'done', hint: row.createTime },
      { label: '已取消', state: 'cancelled', hint: row.updateTime },
    ];
  }

  const currentIndex: Record<Exclude<PurchaseOrderStatus, 'CANCELLED'>, number> = {
    DRAFT: 0,
    SUBMITTED: 1,
    APPROVED: 2,
    PARTIAL_INBOUND: 3,
    INBOUND_DONE: 4,
  };
  const activeStatus = row.status as Exclude<PurchaseOrderStatus, 'CANCELLED'>;
  const hints = [row.createTime, row.submittedAt || '等待提交', row.approvedAt || '等待审核', row.status === 'PARTIAL_INBOUND' ? '仍有明细待入库' : '等待部分入库', row.status === 'INBOUND_DONE' ? row.updateTime : '等待完成'];
  const labels = ['草稿', '待审核', '待入库', '部分入库', '已入库'];
  return labels.map((label, index) => ({
    label,
    hint: hints[index],
    state: index < currentIndex[activeStatus] ? 'done' : index === currentIndex[activeStatus] ? 'current' : 'pending',
  }));
}

function purchaseInboundSummary(row: PurchaseOrderDetail) {
  const totalItems = row.items.length;
  const inboundItems = row.items.filter((item) => item.inboundQty > 0).length;
  const completedItems = row.items.filter((item) => item.quantity > 0 && item.inboundQty >= item.quantity).length;
  const pendingItems = totalItems - completedItems;

  return {
    totalItems,
    inboundItems,
    completedItems,
    pendingItems,
    completionRate: row.fulfillmentSummary.completionRate,
  };
}

function timelineMeta(event: PurchaseOrderTimelineEvent) {
  const map: Record<PurchaseOrderTimelineEvent, { action: string; type: string; className: string }> = {
    CREATED: { action: '创建采购订单', type: '单据创建', className: 'is-create' },
    SUBMITTED: { action: '提交采购订单审核', type: '审核流转', className: 'is-review' },
    APPROVED: { action: '审核通过采购订单', type: '审核完成', className: 'is-review' },
    INBOUND_CREATED: { action: '生成待确认入库单', type: '入库任务', className: 'is-warehouse' },
    INBOUND_CONFIRMED: { action: '确认入库单', type: '仓库入库', className: 'is-warehouse' },
  };
  return map[event];
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

    <ListSummaryStrip :items="summaryItems" aria-label="采购订单数据汇总" />

    <ListFilterPanel layout="content" aria-label="采购订单筛选">
        <div class="space-y-1" data-filter-size="standard"><Label class="text-xs">采购单号</Label><Input v-model="query.purchaseNo" placeholder="如 PO202606001" @keyup.enter="handleSearch" /></div>
        <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">供应商</Label><RemoteSearchSelect v-model="query.supplierId" :selected-label="querySupplierLabel" :fetch-options="fetchSupplierSearchOptions" placeholder="全部供应商" search-placeholder="输入供应商编码或名称" clearable clear-value="all" clear-label="全部供应商" /></div>
        <div class="space-y-1" data-filter-size="wide"><Label class="text-xs">入库仓库</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="queryWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
        <div class="space-y-1" data-filter-size="compact"><Label class="text-xs">订单状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
      <template #actions>
        <ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" />
      </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy" />
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">采购订单列表</strong><span class="text-xs text-muted-foreground">审核动作只生成待确认入库单，实际入库由仓库确认本次数量</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button><Button size="sm" @click="openCreateDialog">新增采购单</Button></div>
      </div>

      <Table class="business-data-table min-w-[1107px] table-fixed" scroll-label="采购订单列表">
          <colgroup><col class="w-[130px]" /><col class="w-[145px]" /><col class="w-[110px]" /><col class="w-[120px]" /><col class="w-[120px]" /><col class="w-[105px]" /><col class="w-[105px]" /><col class="w-[140px]" /><col class="w-[132px]" /></colgroup>
          <TableHeader><TableRow><TableHead data-purchase-no-column>采购单号</TableHead><TableHead>供应商</TableHead><TableHead>入库仓库</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">订单金额</TableHead><TableHead>预计到货</TableHead><TableHead>创建人</TableHead><TableHead>更新时间</TableHead><TableHead class="text-center" data-purchase-actions-column>操作</TableHead></TableRow></TableHeader>
          <TableBody>
            <TableRow v-if="loading && orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">正在加载...</TableCell></TableRow>
            <TableRow v-else-if="orders.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">暂无采购订单</TableCell></TableRow>
            <TableRow v-for="row in orders" v-else :key="row.purchaseOrderId" class="group">
              <TableCell data-purchase-no-column><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.purchaseNo }}</code></TableCell>
              <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.supplierCode }}</code><div class="mt-1 truncate font-medium">{{ row.supplierName }}</div></TableCell>
              <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
              <TableCell class="text-center"><div class="flex flex-col items-center gap-1"><Badge variant="outline" :class="statusMeta(row.status).className">{{ statusMeta(row.status).label }}</Badge><span class="text-[11px] text-muted-foreground">{{ statusHint(row.status) }}</span></div></TableCell>
              <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
              <TableCell class="text-center text-sm">{{ row.expectedArrivalDate || '未设置' }}</TableCell>
              <TableCell class="whitespace-nowrap" :title="row.createdByName || '系统'">{{ row.createdByName || '系统' }}</TableCell>
              <TableCell class="truncate whitespace-nowrap text-xs text-muted-foreground" :title="row.updateTime">{{ row.updateTime }}</TableCell>
              <TableCell class="text-center" data-purchase-actions-column>
                <div class="inline-flex flex-nowrap items-center justify-center gap-1 whitespace-nowrap">
                <Button variant="ghost" size="sm" class="text-cyan-700 hover:text-cyan-800" :disabled="detailLoading" @click="openDetail(row)">{{ detailLoading ? '加载中' : '详情' }}</Button>
                <RowActionsMenu :actions="getRowActions(row)" :disabled="detailLoading || actionSubmitting" :label="`更多 ${row.purchaseNo} 操作`" @select="handleRowAction(row, $event)" />
                </div>
              </TableCell>
            </TableRow>
          </TableBody>
      </Table>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="createDialogOpen">
      <DialogContent class="order-form-dialog flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-5xl">
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? '新增采购单草稿' : '编辑采购单' }}</DialogTitle><DialogDescription>采购单保存为草稿后可提交审核，审核通过后由仓储生成待确认入库单。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">采购单号</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? editingOrder.purchaseNo : '后端自动生成' }}</div></div>
              <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? statusMeta(editingOrder.status).label : '保存后为草稿' }}</div></div>
              <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">{{ dialogMode === 'edit' && editingOrder ? `${editingOrder.createdByName || '系统'} / ${editingOrder.createTime}` : '当前登录用户' }}</div></div>
            </div>
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>供应商 <span class="text-destructive">*</span></Label><RemoteSearchSelect :model-value="form.supplierId" :selected-label="selectedSupplierLabel" :fetch-options="fetchFormSupplierSearchOptions" placeholder="请选择供应商" search-placeholder="输入供应商编码或名称" :invalid="Boolean(formErrors.supplierId)" @update:model-value="handleSupplierChange" /><p v-if="formErrors.supplierId" class="text-xs text-destructive">{{ formErrors.supplierId }}</p></div>
              <div class="space-y-1"><Label>入库仓库 <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="请选择仓库" search-placeholder="输入仓库编码或名称" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="text-xs text-destructive">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>预计到货</Label><OrderDatePicker v-model="form.expectedArrivalDate" :invalid="Boolean(formErrors.expectedArrivalDate)" /><p v-if="formErrors.expectedArrivalDate" class="text-xs text-destructive">{{ formErrors.expectedArrivalDate }}</p></div>
            </div>
            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="2" /><p v-if="formErrors.remark" class="text-xs text-destructive">{{ formErrors.remark }}</p></div>

            <div class="rounded-md border border-border">
              <div class="flex min-h-11 items-center justify-between border-b border-border px-3"><strong class="text-sm">采购明细</strong><Button size="sm" variant="outline" type="button" :disabled="!form.supplierId" @click="addLine">添加产品</Button></div>
              <ScrollArea class="w-full purchase-order-line-scroll">
                <Table class="order-line-table min-w-[900px] table-fixed">
                  <colgroup><col class="w-[235px]" /><col class="w-[120px]" /><col class="w-[120px]" /><col class="w-[90px]" /><col class="w-[115px]" /><col class="w-[145px]" /><col class="w-[75px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">数量</TableHead><TableHead class="text-right">采购价</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead class="text-right">小计</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in draftItems" :key="line.rowId">
                      <TableCell class="align-top"><RemoteSearchSelect :model-value="line.productId" :selected-label="selectedProductLabel(line.productId)" :fetch-options="fetchPurchaseProductSearchOptions" :disabled="!form.supplierId" placeholder="请选择产品" search-placeholder="输入产品编码或名称" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="purchase-line-quantity-cell"><div class="purchase-line-quantity-control" :class="{ 'purchase-line-quantity-control--single': !line.unitName }"><Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line.productId)" class="purchase-line-quantity-input" /><span v-if="line.unitName" class="purchase-line-quantity-unit">{{ line.unitName }}</span></div><p v-if="formErrors[`items.${index}.quantity`]" class="form-error text-center">{{ formErrors[`items.${index}.quantity`] }}</p></TableCell>
                      <TableCell class="align-top"><div class="relative"><span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span><Input v-model.number="line.unitPrice" type="number" min="0" step="0.01" class="pl-8 text-center" /></div><p v-if="formErrors[`items.${index}.unitPrice`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.unitPrice`] }}</p></TableCell>
                      <TableCell class="text-center tabular-nums">{{ Number(line.selectedSupplierScore || 0).toFixed(1) }}</TableCell>
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
      <DialogContent placement="app-content" class="flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden !p-4 sm:max-w-6xl">
        <DialogHeader class="sr-only"><DialogTitle>采购单详情</DialogTitle><DialogDescription>先查看单据状态和入库进度，再核对明细与审批信息。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div v-if="detailRow" class="space-y-5 p-1">
            <BusinessDetailHero eyebrow="采购订单" :title="detailRow.purchaseNo" :subtitle="`${detailRow.supplierCode} · ${detailRow.supplierName} · ${detailRow.warehouseName}`" :status-label="statusMeta(detailRow.status).label" :status-class="statusMeta(detailRow.status).className">
              <template #metrics>
                <div class="business-detail-hero__metric"><span>订单金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>明细项目</span><strong>{{ detailRow.items.length }} 项</strong></div>
                <div class="business-detail-hero__metric"><span>预计到货</span><strong>{{ detailRow.expectedArrivalDate || '未设置' }}</strong></div>
                <div class="business-detail-hero__metric"><span>当前任务</span><strong>{{ statusHint(detailRow.status) }}</strong></div>
              </template>
            </BusinessDetailHero>

            <section class="rounded-lg border border-border bg-card px-4 py-4">
              <div class="mb-4 flex items-center justify-between gap-3"><div><h3 class="text-sm font-semibold">业务进度</h3><p class="mt-1 text-xs text-muted-foreground">状态由系统业务流转生成，不能在详情中直接修改。</p></div></div>
              <BusinessDetailProgress :steps="purchaseProgressSteps(detailRow)" />
              <div class="purchase-execution-summary">
                <div class="purchase-execution-summary__heading">
                  <div><span>累计入库</span><strong>{{ formatMoney(detailRow.fulfillmentSummary.inboundAmount) }}</strong></div>
                  <b class="purchase-execution-summary__rate" aria-label="按金额核算的完成率">{{ purchaseInboundSummary(detailRow).completionRate }}%</b>
                </div>
                <Tooltip :delay-duration="0" :skip-delay-duration="0">
                  <TooltipTrigger as-child>
                    <button type="button" class="purchase-execution-summary__track" aria-label="查看按金额核算的完成率"><i :style="{ width: `${purchaseInboundSummary(detailRow).completionRate}%` }" /></button>
                  </TooltipTrigger>
                  <TooltipContent class="max-w-[280px] !animate-none">完成率按累计入库金额 ÷ 订单总金额计算。</TooltipContent>
                </Tooltip>
                <dl>
                  <div><dt>已入库明细</dt><dd>{{ purchaseInboundSummary(detailRow).inboundItems }} 项</dd></div>
                  <div><dt>待入库明细</dt><dd :class="{ 'is-pending': purchaseInboundSummary(detailRow).pendingItems > 0 }">{{ purchaseInboundSummary(detailRow).pendingItems }} 项</dd></div>
                  <div><dt>已完成明细</dt><dd>{{ purchaseInboundSummary(detailRow).completedItems }} 项</dd></div>
                </dl>
              </div>
            </section>

            <section>
              <div class="mb-2 flex items-center justify-between gap-3"><div><h3 class="text-sm font-semibold">业务信息</h3><p class="mt-1 text-xs text-muted-foreground">供应商、仓库及到货安排。</p></div></div>
              <dl class="purchase-detail-facts">
                <div><dt>采购单号</dt><dd><code>{{ detailRow.purchaseNo }}</code></dd></div>
                <div><dt>供应商</dt><dd><strong>{{ detailRow.supplierName }}</strong><small>{{ detailRow.supplierCode }}</small></dd></div>
                <div><dt>入库仓库</dt><dd><strong>{{ detailRow.warehouseName }}</strong></dd></div>
                <div><dt>预计到货</dt><dd><strong>{{ detailRow.expectedArrivalDate || '未设置' }}</strong></dd></div>
              </dl>
            </section>

            <section>
              <div class="mb-2 flex items-center justify-between gap-3"><div><h3 class="text-sm font-semibold">商品明细</h3><p class="mt-1 text-xs text-muted-foreground">优先核对订购、已入库与剩余待入库数量。</p></div><span class="text-xs text-muted-foreground">共 {{ detailRow.items.length }} 项</span></div>
              <ScrollArea class="w-full purchase-order-line-scroll detail-table-floating" aria-label="采购订单商品明细">
              <Table class="min-w-[1020px] table-fixed">
                <colgroup><col class="w-[240px]" /><col class="w-[100px]" /><col class="w-[110px]" /><col class="w-[120px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[160px]" /></colgroup>
                <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">订购数量</TableHead><TableHead class="text-center">已入库</TableHead><TableHead class="text-center">待入库</TableHead><TableHead class="text-center">单价</TableHead><TableHead class="text-center">金额</TableHead><TableHead class="text-center">推荐分</TableHead><TableHead>明细备注</TableHead></TableRow></TableHeader>
                <TableBody>
                  <TableRow v-for="item in detailRow.items" :key="item.purchaseOrderItemId">
                    <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.quantity }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ item.inboundQty }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center font-medium tabular-nums" :class="item.quantity > item.inboundQty ? 'text-amber-700' : 'text-emerald-700'">{{ Math.max(0, item.quantity - item.inboundQty) }} {{ item.unitName }}</TableCell>
                    <TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell>
                    <TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell>
                    <TableCell class="text-center">{{ item.selectedSupplierScore.toFixed(1) }}</TableCell>
                    <TableCell><OverflowTooltip :text="item.remark" fallback="未维护" class="block text-muted-foreground" /></TableCell>
                  </TableRow>
                </TableBody>
              </Table>
              </ScrollArea>
            </section>

            <section>
              <div class="mb-2"><h3 class="text-sm font-semibold">流程记录</h3><p class="mt-1 text-xs text-muted-foreground">聚合采购单审计字段与关联入库单，不额外新增操作日志。</p></div>
              <ol class="purchase-detail-timeline" aria-label="采购订单流程记录">
                <li v-for="item in detailRow.timeline" :key="`${item.event}-${item.occurredAt}-${item.inboundBillId || ''}`">
                  <i aria-hidden="true" />
                  <div class="purchase-detail-timeline__body">
                    <div class="purchase-detail-timeline__title"><strong>{{ timelineMeta(item.event).action }}</strong><span class="purchase-detail-timeline__type" :class="timelineMeta(item.event).className">{{ timelineMeta(item.event).type }}</span></div>
                    <div class="purchase-detail-timeline__meta">
                      <span><small>操作人</small>{{ item.operatorName }}</span>
                      <span v-if="item.inboundBillNo" class="purchase-detail-timeline__reference"><small>关联入库单</small><code>{{ item.inboundBillNo }}</code></span>
                      <span class="purchase-detail-timeline__time"><small>操作时间</small>{{ item.occurredAt }}</span>
                    </div>
                  </div>
                </li>
              </ol>
              <p v-if="detailRow.remark" class="purchase-detail-remark"><span>备注</span>{{ detailRow.remark }}</p>
            </section>
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

<style scoped>
.order-form-dialog :deep(input),
.order-form-dialog :deep(textarea),
.order-form-dialog :deep([role="combobox"]),
.order-form-dialog :deep([data-anchored-select-trigger]) { font-size: .875rem; }
.order-line-table :deep(th), .order-line-table :deep(td) { font-size: .875rem; }
.order-line-table :deep(input::placeholder) { font-size: .875rem; }

.purchase-detail-facts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--muted) 38%, var(--card));
}

.purchase-detail-facts > div { min-width: 0; padding: 12px 14px; }
.purchase-detail-facts > div + div { border-left: 1px solid var(--border); }
.purchase-detail-facts dt { color: var(--muted-foreground); font-size: 12px; line-height: 1.3; }
.purchase-detail-facts dd { min-width: 0; margin: 5px 0 0; color: var(--foreground); font-size: 14px; line-height: 1.35; }
.purchase-detail-facts dd code, .purchase-detail-facts dd strong, .purchase-detail-facts dd small { overflow: hidden; display: block; text-overflow: ellipsis; white-space: nowrap; }
.purchase-detail-facts dd strong { font-weight: 600; }
.purchase-detail-facts dd small { margin-top: 2px; color: var(--muted-foreground); font-size: 12px; }

.purchase-execution-summary { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border); }
.purchase-execution-summary__heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; }
.purchase-execution-summary__heading span, .purchase-execution-summary dl dt { display: block; color: var(--muted-foreground); font-size: 12px; }
.purchase-execution-summary__heading strong { display: block; margin-top: 3px; font-size: 14px; font-weight: 650; }
.purchase-execution-summary__rate { color: var(--primary); font-size: 18px; font-weight: 700; line-height: 1; }
.purchase-execution-summary__track { display: block; width: 100%; height: 6px; margin-top: 10px; padding: 0; overflow: hidden; border: 0; border-radius: 999px; background: var(--muted); cursor: help; }
.purchase-execution-summary__track i { display: block; height: 100%; border-radius: inherit; background: var(--primary); pointer-events: none; transition: width .2s ease; }
.purchase-execution-summary__track:focus-visible { outline: 2px solid color-mix(in srgb, var(--primary) 35%, transparent); outline-offset: 3px; }
.purchase-execution-summary dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 14px 0 0; }
.purchase-execution-summary dl > div { min-width: 0; padding: 0 14px; }
.purchase-execution-summary dl > div:first-child { padding-left: 0; }
.purchase-execution-summary dl > div + div { border-left: 1px solid var(--border); }
.purchase-execution-summary dl dd { margin: 4px 0 0; color: var(--foreground); font-size: 14px; font-weight: 650; }
.purchase-execution-summary dl dd.is-pending { color: #b45309; }

.purchase-detail-timeline { position: relative; margin: 0; padding: 3px 0; list-style: none; overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); }
.purchase-detail-timeline li { position: relative; display: grid; grid-template-columns: 16px minmax(0, 1fr); gap: 10px; padding: 11px 14px; }
.purchase-detail-timeline li + li { border-top: 1px solid var(--border); }
.purchase-detail-timeline li::before { position: absolute; top: 0; bottom: 0; left: 20px; width: 1px; transform: translateX(-50%); background: var(--border); content: ''; }
.purchase-detail-timeline li:first-child::before { top: 20px; }
.purchase-detail-timeline li:last-child::before { bottom: 20px; }
.purchase-detail-timeline li > i { position: relative; z-index: 1; display: block; width: 12px; height: 12px; margin-top: 3px; border: 3px solid color-mix(in srgb, var(--primary) 18%, var(--card)); border-radius: 50%; background: var(--primary); }
.purchase-detail-timeline__body { min-width: 0; }
.purchase-detail-timeline__title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.purchase-detail-timeline__title strong { color: var(--foreground); font-size: 13px; font-weight: 650; line-height: 18px; }
.purchase-detail-timeline__type { display: inline-flex; align-items: center; min-height: 18px; padding: 1px 6px; border: 1px solid var(--border); border-radius: 999px; background: var(--muted); color: var(--muted-foreground); font-size: 11px; font-weight: 600; line-height: 14px; }
.purchase-detail-timeline__type.is-review { border-color: #bfdbfe; background: #eff6ff; color: #1d4ed8; }
.purchase-detail-timeline__type.is-warehouse { border-color: #bbf7d0; background: #f0fdf4; color: #15803d; }
.purchase-detail-timeline__meta { display: flex; flex-wrap: wrap; align-items: center; gap: 7px 22px; margin-top: 6px; color: var(--muted-foreground); font-size: 12px; line-height: 16px; }
.purchase-detail-timeline__meta > span { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.purchase-detail-timeline__meta small { color: color-mix(in srgb, var(--muted-foreground) 76%, transparent); font-size: 11px; white-space: nowrap; }
.purchase-detail-timeline__reference code { padding: 1px 5px; border-radius: 4px; background: var(--muted); color: var(--muted-foreground); font-size: 11px; }
.purchase-detail-timeline__time { margin-left: auto; font-size: 11px; white-space: nowrap; }
.purchase-detail-remark { margin: 10px 0 0; padding: 10px 12px; border-radius: calc(var(--radius) - 2px); background: color-mix(in srgb, var(--muted) 44%, transparent); color: var(--muted-foreground); font-size: 13px; line-height: 1.55; }
.purchase-detail-remark span { margin-right: 8px; color: var(--foreground); font-weight: 600; }

@media (max-width: 960px) {
  .purchase-detail-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .purchase-detail-facts > div:nth-child(3) { border-left: 0; border-top: 1px solid var(--border); }
  .purchase-detail-facts > div:nth-child(4) { border-top: 1px solid var(--border); }
}

@media (max-width: 640px) {
  .purchase-detail-facts { grid-template-columns: 1fr; }
  .purchase-detail-facts > div + div { border-top: 1px solid var(--border); border-left: 0; }
  .purchase-detail-facts > div:nth-child(3) { border-left: 0; }
  .purchase-execution-summary dl { grid-template-columns: 1fr; gap: 10px; }
  .purchase-execution-summary dl > div, .purchase-execution-summary dl > div:first-child { padding: 0; }
  .purchase-execution-summary dl > div + div { padding-top: 10px; border-top: 1px solid var(--border); border-left: 0; }
  .purchase-detail-timeline__time { margin-left: 0; }
}
</style>
