<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { toast } from 'vue-sonner';
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
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import PromptDialog from '@/components/common/PromptDialog.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import type {
  ReturnHandlingType,
  ReturnOrderDetail,
  ReturnOrderFormPayload,
  ReturnOrderListItem,
  ReturnOrderPageConfig,
  ReturnOrderQuery,
  ReturnOrderStatus,
  ReturnOrderUpdateRequest,
  ReturnReasonCode,
  ReturnSelectOption,
  ReturnableSourceOrder,
  ReturnableSourceOrderItem,
} from '../types';

interface DraftLine extends ReturnableSourceOrderItem {
  rowId: string;
  requestedQty: number;
  remark: string;
}

interface FormModel {
  sourceOrderId: string;
  warehouseId: string;
  expectedExecutionDate: string;
  handlingType: ReturnHandlingType;
  reasonCode: ReturnReasonCode;
  returnReason: string;
  remark: string;
}

type DetailActionMode = 'view' | 'submit' | 'approve' | 'cancel' | 'delete';

const props = defineProps<{ config: ReturnOrderPageConfig }>();
const authStore = useAuthStore();
const businessLabel = computed(() => props.config.title);
const returnTypeLabel = computed(() => props.config.returnType === 'PURCHASE_RETURN' ? '采购退回' : '销售退货');
const processedQuantityLabel = computed(() => props.config.returnType === 'PURCHASE_RETURN' ? '已退货出库' : '已退货入库');
const pendingQuantityLabel = computed(() => props.config.returnType === 'PURCHASE_RETURN' ? '待退货出库' : '待退货入库');
const executionSummaryCopy = computed(() => props.config.returnType === 'PURCHASE_RETURN'
  ? { amountLabel: '累计退货出库', processedLabel: '已退货出库明细', pendingLabel: '待退货出库明细', hint: '完成率按累计退货出库金额 ÷ 审核通过退货金额计算。' }
  : { amountLabel: '累计退货入库', processedLabel: '已退货入库明细', pendingLabel: '待退货入库明细', hint: '完成率按累计退货入库金额 ÷ 审核通过退货金额计算。' });

const statusLabels = computed<Record<ReturnOrderStatus, string>>(() => ({
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: props.config.approvedStatusLabel,
  PARTIAL_EXECUTED: props.config.partialStatusLabel,
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}));
const statusClassNames: Record<ReturnOrderStatus, string> = {
  DRAFT: 'border-slate-200 bg-slate-50 text-slate-600',
  SUBMITTED: 'border-blue-200 bg-blue-50 text-blue-700',
  APPROVED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL_EXECUTED: 'border-amber-200 bg-amber-50 text-amber-700',
  COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  CANCELLED: 'border-rose-200 bg-rose-50 text-rose-700',
};
const statusOptions = computed(() => [
  { value: 'all', label: '全部状态' },
  ...(['DRAFT', 'SUBMITTED', 'APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED', 'CANCELLED'] as const)
    .map(value => ({ value, label: statusLabels.value[value] })),
]);
const handlingOptions = [
  { value: 'REFUND', label: '退款 / 退回货款' },
  { value: 'EXCHANGE', label: '换货' },
  { value: 'OTHER', label: '其他处理方式' },
];
const reasonOptions = [
  { value: 'QUALITY_ISSUE', label: '质量问题' },
  { value: 'DAMAGED', label: '商品损坏' },
  { value: 'WRONG_ITEM', label: '商品错发' },
  { value: 'QUANTITY_ERROR', label: '数量错误' },
  { value: 'SPEC_MISMATCH', label: '规格不符' },
  { value: 'NO_LONGER_NEEDED', label: '不再需要' },
  { value: 'OTHER', label: '其他原因' },
];

const rows = ref<ReturnOrderListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const formSubmitting = ref(false);
const actionSubmitting = ref(false);
const detailLoading = ref(false);
const formDialogOpen = ref(false);
const dialogMode = ref<'create' | 'edit'>('create');
const editingDetail = ref<ReturnOrderDetail | null>(null);
const detailDialogOpen = ref(false);
const detailRow = ref<ReturnOrderDetail | null>(null);
const detailActionMode = ref<DetailActionMode>('view');
const draftLines = ref<DraftLine[]>([]);
const sourceItems = ref<ReturnableSourceOrderItem[]>([]);
const approvalQuantities = reactive<Record<string, number>>({});
const partyOptions = ref<ReturnSelectOption[]>([{ value: 'all', label: props.config.partyAllLabel }]);
const warehouseOptions = ref<ReturnSelectOption[]>([{ value: 'all', label: '全部仓库' }]);
const sourceOrderOptions = ref<ReturnSelectOption[]>([]);
const sourceOrderCache = new Map<string, ReturnableSourceOrder>();
const formErrors = reactive<Record<string, string>>({});
let requestSequence = 0;
let draftLineSequence = 1;

const query = reactive<ReturnOrderQuery>({
  returnNo: '',
  sourceOrderNo: '',
  partyId: 'all',
  warehouseId: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});

const form = reactive<FormModel>({
  sourceOrderId: '',
  warehouseId: '',
  expectedExecutionDate: '',
  handlingType: 'REFUND',
  reasonCode: 'QUALITY_ISSUE',
  returnReason: '',
  remark: '',
});

const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'warning' as 'default' | 'destructive' | 'warning',
  onConfirm: (() => {}) as () => Promise<void>,
});
const promptState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
});

const canQuery = computed(() => authStore.hasPermission(props.config.permissions.query));
const canCreate = computed(() => authStore.hasPermission(props.config.permissions.create));
const canManage = computed(() => authStore.hasPermission(props.config.permissions.manage));
const queryBusy = computed(() => loading.value || queryPending.value);
const selectedSource = computed(() => sourceOrderCache.get(form.sourceOrderId));
const selectedSourceLabel = computed(() => sourceOrderOptions.value.find(option => option.value === form.sourceOrderId)?.label
  || (editingDetail.value?.sourceOrderId === form.sourceOrderId ? `${editingDetail.value.sourceOrderNo} · ${editingDetail.value.partyName}` : ''));
const selectedWarehouseLabel = computed(() => warehouseOptions.value.find(option => option.value === form.warehouseId)?.label
  || (editingDetail.value?.warehouseId === form.warehouseId ? editingDetail.value.warehouseName : ''));
const queryPartyLabel = computed(() => query.partyId === 'all' ? props.config.partyAllLabel : partyOptions.value.find(option => option.value === query.partyId)?.label || '');
const queryWarehouseLabel = computed(() => query.warehouseId === 'all' ? '全部仓库' : warehouseOptions.value.find(option => option.value === query.warehouseId)?.label || '');
const selectedLines = computed(() => draftLines.value.filter(line => Boolean(line.sourceOrderItemId)));
const draftAmount = computed(() => selectedLines.value.reduce((sum, line) => sum + Number(line.requestedQty || 0) * line.unitPrice, 0));
const selectableProductCount = computed(() => new Set(sourceItems.value.map(item => item.productId)).size);
const selectedProductCount = computed(() => new Set(selectedLines.value.map(line => line.productId)).size);
const canAddLine = computed(() => Boolean(form.sourceOrderId)
  && selectableProductCount.value > 0
  && selectedProductCount.value < selectableProductCount.value);
const summaryItems = computed(() => [
  { key: 'draft', label: '本页草稿', value: rows.value.filter(row => row.status === 'DRAFT').length },
  { key: 'submitted', label: '本页待审核', value: rows.value.filter(row => row.status === 'SUBMITTED').length },
  { key: 'approved', label: `本页${props.config.approvedStatusLabel}`, value: rows.value.filter(row => row.status === 'APPROVED').length, tone: 'positive' as const },
  { key: 'executing', label: `本页${props.config.partialStatusLabel}`, value: rows.value.filter(row => row.status === 'PARTIAL_EXECUTED').length, tone: 'warning' as const },
]);

function mergeOptions(target: typeof partyOptions, options: ReturnSelectOption[], allOption?: ReturnSelectOption) {
  const cache = new Map(target.value.map(option => [option.value, option]));
  options.forEach(option => cache.set(option.value, option));
  target.value = [
    ...(allOption ? [allOption] : []),
    ...[...cache.values()].filter(option => !allOption || option.value !== allOption.value),
  ];
}

async function fetchPartyOptions(keyword: string) {
  const options = await props.config.service.searchPartyOptions(keyword);
  mergeOptions(partyOptions, options, { value: 'all', label: props.config.partyAllLabel });
  return options;
}

async function fetchWarehouseOptions(keyword: string) {
  const options = await props.config.service.searchWarehouseOptions(keyword);
  mergeOptions(warehouseOptions, options, { value: 'all', label: '全部仓库' });
  return options;
}

async function fetchSourceOrderOptions(keyword: string) {
  const sourceOrders = await props.config.service.searchSourceOrders(keyword);
  const options = sourceOrders.map(order => {
    sourceOrderCache.set(order.sourceOrderId, order);
    return {
      value: order.sourceOrderId,
      label: `${order.sourceOrderNo} · ${order.partyName} · ${order.fulfilledItemCount} 项可退`,
    };
  });
  mergeOptions(sourceOrderOptions, options);
  return options;
}

async function loadRows() {
  if (!canQuery.value) return;
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const page = await props.config.service.listReturns(query);
    if (sequence !== requestSequence) return;
    rows.value = page.records;
    total.value = page.total;
    page.records.forEach(row => {
      mergeOptions(partyOptions, [{ value: row.partyId, label: `${row.partyCode} ${row.partyName}` }], { value: 'all', label: props.config.partyAllLabel });
      mergeOptions(warehouseOptions, [{ value: row.warehouseId, label: row.warehouseName }], { value: 'all', label: '全部仓库' });
    });
  } catch (error) {
    toast.error(getApiErrorMessage(error) || `${props.config.title}加载失败`);
  } finally {
    if (sequence === requestSequence) {
      loading.value = false;
      queryPending.value = false;
    }
  }
}

const { handleSearch, handleReset, handlePageChange, handlePageSizeChange, refreshList } = usePagedQuery({
  query,
  busy: queryBusy,
  pending: queryPending,
  load: loadRows,
  resetFilters: () => {
    query.returnNo = '';
    query.sourceOrderNo = '';
    query.partyId = 'all';
    query.warehouseId = 'all';
    query.status = 'all';
  },
});

function clearFormErrors() {
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function resetForm() {
  form.sourceOrderId = '';
  form.warehouseId = '';
  form.expectedExecutionDate = '';
  form.handlingType = 'REFUND';
  form.reasonCode = 'QUALITY_ISSUE';
  form.returnReason = '';
  form.remark = '';
  draftLines.value = [];
  sourceItems.value = [];
  editingDetail.value = null;
  clearFormErrors();
}

function newDraftLine(): DraftLine {
  return {
    rowId: `return-line-${draftLineSequence++}`,
    sourceOrderItemId: '',
    productId: '',
    productCode: '',
    productName: '',
    unitName: '',
    quantityPrecision: 0,
    sourceFulfilledQty: 0,
    stockAvailableQty: 0,
    occupiedQty: 0,
    availableReturnQty: 0,
    unitPrice: 0,
    requestedQty: 0,
    remark: '',
  };
}

function createDraftLine(sourceItem: ReturnableSourceOrderItem, saved?: ReturnOrderDetail['items'][number]): DraftLine {
  return {
    ...sourceItem,
    rowId: `return-line-${draftLineSequence++}`,
    // 编辑草稿时，来源接口的可退数量未计入当前草稿，允许保留用户之前填写的申请数量。
    availableReturnQty: saved ? Math.max(sourceItem.availableReturnQty, saved.requestedQty) : sourceItem.availableReturnQty,
    requestedQty: saved?.requestedQty ?? Math.min(1, sourceItem.availableReturnQty),
    remark: saved?.remark || '',
  };
}

async function loadSourceLines(sourceOrderId: string, existing?: ReturnOrderDetail) {
  const items = await props.config.service.listSourceItems(sourceOrderId);
  const existingBySourceId = new Map(existing?.items.map(item => [item.sourceOrderItemId, item]) || []);
  const availableItems = items.map(item => {
    const saved = existingBySourceId.get(item.sourceOrderItemId);
    return {
      ...item,
      availableReturnQty: saved ? Math.max(item.availableReturnQty, saved.requestedQty) : item.availableReturnQty,
    };
  });
  existing?.items.forEach(saved => {
    if (availableItems.some(item => item.sourceOrderItemId === saved.sourceOrderItemId)) return;
    availableItems.push({
      sourceOrderItemId: saved.sourceOrderItemId,
      productId: saved.productId,
      productCode: saved.productCode,
      productName: saved.productName,
      unitName: saved.unitName,
      quantityPrecision: saved.quantityPrecision,
      sourceFulfilledQty: saved.sourceFulfilledQty,
      stockAvailableQty: 0,
      // 来源接口不再返回该草稿中已失效的明细时，保留原申请数量供用户修订；剩余可退数量仍以来源接口为准。
      occupiedQty: 0,
      availableReturnQty: saved.requestedQty,
      unitPrice: saved.unitPrice,
    });
  });
  sourceItems.value = availableItems;
  draftLines.value = existing
    ? existing.items.map(saved => createDraftLine(
      availableItems.find(item => item.sourceOrderItemId === saved.sourceOrderItemId) || {
        sourceOrderItemId: saved.sourceOrderItemId,
        productId: saved.productId,
        productCode: saved.productCode,
        productName: saved.productName,
        unitName: saved.unitName,
        quantityPrecision: saved.quantityPrecision,
        sourceFulfilledQty: saved.sourceFulfilledQty,
        stockAvailableQty: 0,
        occupiedQty: 0,
        availableReturnQty: saved.requestedQty,
        unitPrice: saved.unitPrice,
      },
      saved,
    ))
    : (availableItems.length > 0 ? [newDraftLine()] : []);
}

async function handleSourceChange(value: string | number) {
  const sourceOrderId = String(value || '');
  form.sourceOrderId = sourceOrderId;
  delete formErrors.sourceOrderId;
  draftLines.value = [];
  sourceItems.value = [];
  const source = sourceOrderCache.get(sourceOrderId);
  if (source) {
    mergeOptions(partyOptions, [{ value: source.partyId, label: `${source.partyCode} ${source.partyName}` }], { value: 'all', label: props.config.partyAllLabel });
    mergeOptions(warehouseOptions, [{ value: source.warehouseId, label: source.warehouseName }], { value: 'all', label: '全部仓库' });
    form.warehouseId = source.warehouseId;
    delete formErrors.warehouseId;
  }
  if (!sourceOrderId) return;
  try {
    await loadSourceLines(sourceOrderId);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '可退明细加载失败');
  }
}

function selectedProductLabel(line: DraftLine) {
  return line.productId ? `${line.productCode} ${line.productName}` : '';
}

async function fetchReturnProductOptions(keyword: string, currentRowId?: string): Promise<ReturnSelectOption[]> {
  const normalizedKeyword = keyword.trim().toLocaleLowerCase();
  const selectedProductIds = new Set(draftLines.value
    .filter(line => line.rowId !== currentRowId)
    .map(line => line.productId)
    .filter(Boolean));
  return sourceItems.value
    .filter(item => !normalizedKeyword
      || item.productCode.toLocaleLowerCase().includes(normalizedKeyword)
      || item.productName.toLocaleLowerCase().includes(normalizedKeyword))
    .map(item => ({
      value: item.sourceOrderItemId,
      label: `${item.productCode} ${item.productName}`,
      disabled: selectedProductIds.has(item.productId),
    }));
}

function selectReturnProduct(line: DraftLine, value: string | number) {
  const sourceItemId = String(value);
  const sourceItem = sourceItems.value.find(item => item.sourceOrderItemId === sourceItemId);
  const index = draftLines.value.findIndex(item => item.rowId === line.rowId);
  if (!sourceItem) return;
  if (draftLines.value.some(item => item.rowId !== line.rowId && item.productId === sourceItem.productId)) {
    formErrors[`items.${index}.productId`] = '同一产品不能重复添加';
    toast.warning('同一产品不能重复添加');
    return;
  }
  const rowId = line.rowId;
  Object.assign(line, createDraftLine(sourceItem));
  line.rowId = rowId;
  delete formErrors[`items.${index}.productId`];
  delete formErrors[`items.${index}.requestedQty`];
}

function addLine() {
  if (!canAddLine.value) return;
  draftLines.value = [...draftLines.value, newDraftLine()];
}

function removeLine(rowId: string) {
  if (draftLines.value.length === 1) return;
  draftLines.value = draftLines.value.filter(line => line.rowId !== rowId);
}

function openCreateDialog() {
  if (!canCreate.value) {
    toast.error('没有新建权限');
    return;
  }
  dialogMode.value = 'create';
  resetForm();
  formDialogOpen.value = true;
}

async function openEditDialog(row: ReturnOrderListItem) {
  if (!canManage.value || row.status !== 'DRAFT') return;
  detailLoading.value = true;
  try {
    const detail = await props.config.service.getReturnDetail(row.returnOrderId);
    dialogMode.value = 'edit';
    editingDetail.value = detail;
    sourceOrderCache.set(detail.sourceOrderId, {
      sourceOrderId: detail.sourceOrderId,
      sourceOrderNo: detail.sourceOrderNo,
      partyId: detail.partyId,
      partyCode: detail.partyCode,
      partyName: detail.partyName,
      warehouseId: detail.warehouseId,
      warehouseName: detail.warehouseName,
      fulfilledItemCount: detail.items.length,
      totalAvailableReturnQty: detail.items.reduce((sum, item) => sum + item.requestedQty, 0),
    });
    mergeOptions(sourceOrderOptions, [{ value: detail.sourceOrderId, label: `${detail.sourceOrderNo} · ${detail.partyName}` }]);
    mergeOptions(warehouseOptions, [{ value: detail.warehouseId, label: detail.warehouseName }], { value: 'all', label: '全部仓库' });
    form.sourceOrderId = detail.sourceOrderId;
    form.warehouseId = detail.warehouseId;
    form.expectedExecutionDate = detail.expectedExecutionDate || '';
    form.handlingType = detail.handlingType;
    form.reasonCode = detail.reasonCode;
    form.returnReason = detail.returnReason;
    form.remark = detail.remark;
    clearFormErrors();
    await loadSourceLines(detail.sourceOrderId, detail);
    formDialogOpen.value = true;
  } catch (error) {
    toast.error(getApiErrorMessage(error) || `${businessLabel.value}草稿加载失败`);
  } finally {
    detailLoading.value = false;
  }
}

function validateForm() {
  clearFormErrors();
  if (!form.sourceOrderId) formErrors.sourceOrderId = `请选择${props.config.sourceOrderLabel}`;
  if (!form.warehouseId) formErrors.warehouseId = `请选择${props.config.warehouseLabel}`;
  if (form.reasonCode === 'OTHER' && !form.returnReason.trim()) formErrors.returnReason = '选择其他原因时必须填写具体原因';
  if (form.returnReason.trim().length > 500) formErrors.returnReason = '原因说明不能超过 500 字';
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 字';
  if (selectedLines.value.length === 0) formErrors.items = '请至少添加一条退货明细';
  const selectedProductIds = new Set<string>();
  draftLines.value.forEach((line, index) => {
    if (!line.sourceOrderItemId) {
      formErrors[`items.${index}.productId`] = '请选择产品';
      return;
    }
    if (selectedProductIds.has(line.productId)) {
      formErrors[`items.${index}.productId`] = '同一产品不能重复添加';
    }
    selectedProductIds.add(line.productId);
    const quantity = Number(line.requestedQty);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      formErrors[`items.${index}.requestedQty`] = '申请数量必须大于 0';
    } else if (quantity > line.availableReturnQty) {
      formErrors[`items.${index}.requestedQty`] = `不能超过剩余可退 ${formatQuantity(line.availableReturnQty, line.quantityPrecision)}`;
    } else {
      const factor = 10 ** line.quantityPrecision;
      if (Math.abs(quantity * factor - Math.round(quantity * factor)) > 1e-8) {
        formErrors[`items.${index}.requestedQty`] = `最多 ${line.quantityPrecision} 位小数`;
      }
    }
    if (line.remark.trim().length > 500) formErrors[`items.${index}.remark`] = '明细备注不能超过 500 字';
  });
  return Object.keys(formErrors).length === 0;
}

/** 数量输入框失焦时即时校验并弹 toast 提示 */
function validateRequestedQtyOnBlur(line: DraftLine, index: number) {
  if (!line.sourceOrderItemId) return;
  const quantity = Number(line.requestedQty);
  const key = `items.${index}.requestedQty`;
  if (!Number.isFinite(quantity) || quantity <= 0) {
    formErrors[key] = '申请数量必须大于 0';
    toast.warning(`${line.productName || '该产品'}：申请数量必须大于 0`);
  } else if (quantity > line.availableReturnQty) {
    formErrors[key] = `不能超过剩余可退 ${formatQuantity(line.availableReturnQty, line.quantityPrecision)}`;
    toast.warning(`${line.productName || '该产品'}：不能超过剩余可退 ${formatQuantity(line.availableReturnQty, line.quantityPrecision)}`);
  } else {
    const factor = 10 ** line.quantityPrecision;
    if (Math.abs(quantity * factor - Math.round(quantity * factor)) > 1e-8) {
      formErrors[key] = `最多 ${line.quantityPrecision} 位小数`;
      toast.warning(`${line.productName || '该产品'}：数量最多 ${line.quantityPrecision} 位小数`);
    } else {
      delete formErrors[key];
    }
  }
}

function buildFormPayload(): ReturnOrderFormPayload {
  return {
    sourceOrderId: form.sourceOrderId,
    warehouseId: form.warehouseId,
    expectedExecutionDate: form.expectedExecutionDate || null,
    handlingType: form.handlingType,
    reasonCode: form.reasonCode,
    returnReason: form.returnReason.trim(),
    remark: form.remark.trim(),
    items: draftLines.value.map(line => ({
      sourceOrderItemId: line.sourceOrderItemId,
      requestedQty: Number(line.requestedQty),
      remark: line.remark.trim(),
    })),
  };
}

async function submitForm() {
  if (formSubmitting.value || !validateForm()) {
    toast.warning('请先修正明细中的数量错误');
    return;
  }
  if (dialogMode.value === 'create' && !canCreate.value) return;
  if (dialogMode.value === 'edit' && !canManage.value) return;
  formSubmitting.value = true;
  try {
    if (dialogMode.value === 'edit' && editingDetail.value) {
      const updatePayload: ReturnOrderUpdateRequest = {
        ...buildFormPayload(),
        version: editingDetail.value.version,
      };
      await props.config.service.updateReturn(editingDetail.value.returnOrderId, updatePayload);
      toast.success(`${businessLabel.value}草稿已更新`);
    } else {
      await props.config.service.createReturn(buildFormPayload());
      toast.success(`${businessLabel.value}草稿已创建`);
    }
    formDialogOpen.value = false;
    await loadRows();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || `${businessLabel.value}草稿保存失败`);
  } finally {
    formSubmitting.value = false;
  }
}

async function openDetail(row: ReturnOrderListItem, mode: DetailActionMode = 'view') {
  if (mode !== 'view' && !canManage.value) {
    toast.error(`没有${businessLabel.value}管理权限`);
    return;
  }
  detailLoading.value = true;
  try {
    const detail = await props.config.service.getReturnDetail(row.returnOrderId);
    detailRow.value = detail;
    detailActionMode.value = mode === 'view' && canManage.value
      ? (detail.status === 'DRAFT' ? 'submit' : detail.status === 'SUBMITTED' ? 'approve' : 'view')
      : mode;
    Object.keys(approvalQuantities).forEach(key => delete approvalQuantities[key]);
    detail.items.forEach(item => { approvalQuantities[item.returnOrderItemId] = item.requestedQty; });
    detailDialogOpen.value = true;
  } catch (error) {
    toast.error(getApiErrorMessage(error) || `${businessLabel.value}详情加载失败`);
  } finally {
    detailLoading.value = false;
  }
}

function hasReturnActions(row: ReturnOrderListItem) {
  return canManage.value && (row.status === 'DRAFT' || row.status === 'SUBMITTED' || row.status === 'APPROVED');
}

async function openDetailEdit(row: ReturnOrderDetail) {
  detailDialogOpen.value = false;
  await nextTick();
  await openEditDialog(row);
}

function runDetailRiskAction(action: 'cancel' | 'delete') {
  detailActionMode.value = action;
  runDetailAction();
}

function openConfirm(title: string, description: string, confirmText: string, onConfirm: () => Promise<void>, variant: 'default' | 'destructive' | 'warning' = 'warning') {
  confirmState.title = title;
  confirmState.description = description;
  confirmState.confirmText = confirmText;
  confirmState.onConfirm = onConfirm;
  confirmState.variant = variant;
  confirmState.open = true;
}

async function executeAction(action: () => Promise<void>, successMessage: string) {
  if (actionSubmitting.value || !canManage.value) return;
  actionSubmitting.value = true;
  try {
    await action();
    toast.success(successMessage);
    confirmState.open = false;
    promptState.open = false;
    detailDialogOpen.value = false;
    await loadRows();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || `${businessLabel.value}操作失败`);
  } finally {
    actionSubmitting.value = false;
  }
}

function validateApproval(detail: ReturnOrderDetail) {
  let positiveCount = 0;
  for (const item of detail.items) {
    const value = Number(approvalQuantities[item.returnOrderItemId]);
    if (!Number.isFinite(value) || value < 0 || value > item.requestedQty) {
      toast.error(`${item.productName} 的审核数量必须在 0～申请数量之间`);
      return false;
    }
    const factor = 10 ** item.quantityPrecision;
    if (Math.abs(value * factor - Math.round(value * factor)) > 1e-8) {
      toast.error(`${item.productName} 的审核数量最多 ${item.quantityPrecision} 位小数`);
      return false;
    }
    if (value > 0) positiveCount += 1;
  }
  if (positiveCount === 0) {
    toast.error('至少一条明细的审核数量必须大于 0；全部不通过请使用取消');
    return false;
  }
  return true;
}

function runDetailAction() {
  const detail = detailRow.value;
  if (!detail || !canManage.value) return;
  if (detailActionMode.value === 'submit') {
    if (!detail.expectedExecutionDate) {
      toast.error(`提交前必须填写${props.config.executionDateLabel}`);
      return;
    }
    openConfirm(`提交${businessLabel.value}`, `确认提交 ${detail.returnNo}？提交后将占用申请数量并进入待审核。`, '确认提交', () => executeAction(
      () => props.config.service.submitReturn(detail.returnOrderId, detail.version),
      `${businessLabel.value}已提交`,
    ));
  } else if (detailActionMode.value === 'approve') {
    if (!validateApproval(detail)) return;
    openConfirm(`审核通过${businessLabel.value}`, `${props.config.approvalResultDescription} 请确认审核数量准确。`, '审核通过', () => executeAction(
      () => props.config.service.approveReturn(detail.returnOrderId, {
        version: detail.version,
        items: detail.items.map(item => ({ returnOrderItemId: item.returnOrderItemId, approvedQty: Number(approvalQuantities[item.returnOrderItemId]) })),
      }),
      `${businessLabel.value}审核通过`,
    ));
  } else if (detailActionMode.value === 'delete') {
    openConfirm(`删除${businessLabel.value}草稿`, `确认删除 ${detail.returnNo}？草稿主表将逻辑删除，明细将物理删除。`, '确认删除', () => executeAction(
      () => props.config.service.deleteReturn(detail.returnOrderId, detail.version),
      `${businessLabel.value}草稿已删除`,
    ), 'destructive');
  } else if (detailActionMode.value === 'cancel') {
    promptState.title = `取消${businessLabel.value}`;
    promptState.description = '取消后单据进入终态且不可恢复，请填写取消原因。';
    promptState.confirmText = '确认取消';
    promptState.open = true;
  }
}

function runConfirmAction() {
  // AlertDialogAction 会先关闭弹窗再触发 confirm，不能用 open 状态拦截业务回调。
  if (actionSubmitting.value) return;
  void confirmState.onConfirm();
}

function runReasonAction(reason: string) {
  const detail = detailRow.value;
  if (!detail || !reason.trim() || !canManage.value) return;
  const payload = { version: detail.version, reason: reason.trim() };
  void executeAction(
    () => props.config.service.cancelReturn(detail.returnOrderId, payload),
    `${businessLabel.value}已取消`,
  );
}

function statusHint(status: ReturnOrderStatus) {
  const hints: Record<ReturnOrderStatus, string> = {
    DRAFT: '待提交',
    SUBMITTED: '等待审核',
    APPROVED: props.config.approvedHint,
    PARTIAL_EXECUTED: props.config.partialHint,
    COMPLETED: '流程完成',
    CANCELLED: '流程终止',
  };
  return hints[status];
}

function returnProgressSteps(row: ReturnOrderDetail): BusinessDetailProgressStep[] {
  if (row.status === 'CANCELLED') {
    return [
      { label: '草稿', state: 'done', hint: row.createTime },
      { label: '已取消', state: 'cancelled', hint: row.statusReason || '流程已终止' },
    ];
  }

  const activeStatus = row.status as Exclude<ReturnOrderStatus, 'CANCELLED'>;
  const currentIndex: Record<Exclude<ReturnOrderStatus, 'CANCELLED'>, number> = {
    DRAFT: 0,
    SUBMITTED: 1,
    APPROVED: 2,
    PARTIAL_EXECUTED: 3,
    COMPLETED: 4,
  };
  const labels = ['草稿', '待审核', props.config.approvedStatusLabel, props.config.partialStatusLabel, '已完成'];
  const hints = [
    row.createTime,
    row.submittedAt || '等待提交',
    row.approvedAt || '等待审核',
    row.status === 'PARTIAL_EXECUTED' ? props.config.partialHint : props.config.approvedHint,
    row.status === 'COMPLETED' ? row.approvedAt || '已完成' : '等待完成',
  ];

  return labels.map((label, index) => ({
    label,
    hint: hints[index],
    state: index < currentIndex[activeStatus] ? 'done' : index === currentIndex[activeStatus] ? 'current' : 'pending',
  }));
}

function returnExecutionSummary(row: ReturnOrderDetail) {
  const totalItems = row.items.length;
  const processedItems = row.items.filter(item => Number(item.processedQty || 0) > 0).length;
  const completedItems = row.items.filter(item => Number(item.approvedQty || 0) > 0 && Number(item.processedQty || 0) >= Number(item.approvedQty || 0)).length;
  const pendingItems = totalItems - completedItems;
  const targetAmount = row.items.reduce((sum, item) => sum + Math.max(0, Number(item.approvedQty || 0)) * Math.max(0, Number(item.unitPrice || 0)), 0);
  const completedAmount = row.items.reduce((sum, item) => {
    const approvedQty = Math.max(0, Number(item.approvedQty || 0));
    const processedQty = Math.max(0, Number(item.processedQty || 0));
    return sum + Math.min(approvedQty, processedQty) * Math.max(0, Number(item.unitPrice || 0));
  }, 0);

  return {
    processedItems,
    completedItems,
    pendingItems,
    completedAmount,
    completionRate: targetAmount > 0 ? completedAmount / targetAmount * 100 : 0,
  };
}

function returnTimelineItems(row: ReturnOrderDetail): BusinessDetailTimelineItem[] {
  const items: BusinessDetailTimelineItem[] = [
    { id: 'created', action: `创建${props.config.detailTitle.replace('详情', '')}`, type: '单据创建', operatorName: row.createdByName || '系统', occurredAt: row.createTime },
  ];
  if (row.submittedAt) items.push({ id: 'submitted', action: '提交退回单审核', type: '审核流转', tone: 'review', operatorName: row.createdByName || '系统', occurredAt: row.submittedAt });
  if (row.approvedAt) items.push({ id: 'approved', action: '审核通过退回单', type: '审核完成', tone: 'review', operatorName: row.approvedByName || '系统', occurredAt: row.approvedAt });
  if (row.status === 'PARTIAL_EXECUTED' || row.status === 'COMPLETED') items.push({ id: 'executed', action: row.status === 'COMPLETED' ? '完成退回执行' : '处理退回执行任务', type: '仓储执行', tone: 'warehouse', operatorName: '系统', occurredAt: row.updateTime });
  return items;
}

function detailActionDescription() {
  if (detailActionMode.value === 'submit') return '请先核对退回单头和全部明细，再提交进入待审核。';
  if (detailActionMode.value === 'approve') return props.config.approvalResultDescription;
  if (detailActionMode.value === 'cancel') return '请核对单据尚未产生不可撤销的仓储事实。';
  if (detailActionMode.value === 'delete') return '请核对草稿内容；删除后明细不能恢复。';
  return '查看来源订单、退回原因、数量、金额和仓库执行进度。';
}

function detailActionButtonLabel() {
  const labels: Record<Exclude<DetailActionMode, 'view'>, string> = {
    submit: `提交${businessLabel.value}`,
    approve: `审核${businessLabel.value}`,
    cancel: `取消${businessLabel.value}`,
    delete: '删除草稿',
  };
  return detailActionMode.value === 'view' ? '' : labels[detailActionMode.value];
}

function formatMoney(value: number) {
  return `￥${Number(value || 0).toFixed(2)}`;
}

function formatQuantity(value: number, precision = 2) {
  return Number(value || 0).toFixed(precision).replace(/\.0+$|(?<=\.[0-9])0+$/g, '');
}

function quantityStep(precision: number) {
  return precision === 0 ? 1 : precision === 1 ? 0.1 : 0.01;
}

function handlingLabel(value: ReturnHandlingType) {
  return handlingOptions.find(option => option.value === value)?.label || value;
}

function reasonLabel(value: ReturnReasonCode) {
  return reasonOptions.find(option => option.value === value)?.label || value;
}

onMounted(() => {
  if (!canQuery.value) {
    toast.error(`缺少 ${props.config.permissions.query} 权限`);
    return;
  }
  void loadRows();
});
</script>

<template>
  <section class="page-shell space-y-4" :data-return-type="config.returnType">
    <div class="page-heading">
      <div>
        <h1 class="page-title">{{ config.title }}</h1>
        <p class="page-description">{{ config.description }}</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryItems" :aria-label="`${config.title}数据汇总`" />

    <ListFilterPanel layout="content" :aria-label="`${config.title}筛选`">
      <div class="space-y-1" data-filter-size="standard"><Label>退回单号</Label><Input v-model="query.returnNo" :placeholder="config.returnNoPlaceholder" @keyup.enter="handleSearch" /></div>
      <div class="space-y-1" data-filter-size="standard"><Label>{{ config.sourceOrderLabel }}号</Label><Input v-model="query.sourceOrderNo" :placeholder="config.sourceOrderPlaceholder" @keyup.enter="handleSearch" /></div>
      <div class="space-y-1" data-filter-size="wide"><Label>{{ config.partyLabel }}</Label><RemoteSearchSelect v-model="query.partyId" :selected-label="queryPartyLabel" :fetch-options="fetchPartyOptions" :placeholder="config.partyAllLabel" :search-placeholder="config.partySearchPlaceholder" clearable clear-value="all" :clear-label="config.partyAllLabel" /></div>
      <div class="space-y-1" data-filter-size="wide"><Label>{{ config.warehouseLabel }}</Label><RemoteSearchSelect v-model="query.warehouseId" :selected-label="queryWarehouseLabel" :fetch-options="fetchWarehouseOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" /></div>
      <div class="space-y-1" data-filter-size="compact"><Label>退回状态</Label><AnchoredSelect v-model="query.status" :options="statusOptions" /></div>
      <template #actions><ListFilterActions :busy="queryBusy" :disabled="!canQuery" @query="handleSearch" @reset="handleReset" /></template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <div class="table-toolbar">
        <div class="table-toolbar__title"><strong class="text-sm">{{ config.listTitle }}</strong><span class="text-xs text-muted-foreground">点击“处理”查看详情并完成后续操作；退回审核只生成来源工作单，实际库存变化由仓库确认</span></div>
        <div class="table-toolbar__actions"><Button size="sm" variant="outline" :disabled="queryBusy || !canQuery" @click="refreshList">刷新</Button><Button v-if="canCreate" size="sm" @click="openCreateDialog">{{ config.createButtonLabel }}</Button></div>
      </div>

      <Table class="business-data-table min-w-[1140px] table-fixed" :scroll-label="config.listTitle" data-return-order-table>
        <colgroup><col class="w-[125px]" /><col class="w-[125px]" /><col class="w-[160px]" /><col class="w-[115px]" /><col class="w-[135px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[140px]" /><col class="w-[96px]" /></colgroup>
        <TableHeader><TableRow><TableHead data-return-no-column>退回单号</TableHead><TableHead>{{ config.sourceOrderLabel }}号</TableHead><TableHead>{{ config.partyLabel }}</TableHead><TableHead>{{ config.warehouseLabel }}</TableHead><TableHead class="text-center">状态</TableHead><TableHead class="text-right">退回金额</TableHead><TableHead class="text-center">预计执行</TableHead><TableHead>更新时间</TableHead><TableHead class="text-center" data-return-actions-column>操作</TableHead></TableRow></TableHeader>
        <TableBody>
          <TableRow v-if="rows.length === 0"><TableCell colspan="9" class="h-28 text-center text-muted-foreground">{{ config.emptyText }}</TableCell></TableRow>
          <TableRow v-for="row in rows" v-else :key="row.returnOrderId">
            <TableCell data-return-no-column><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.returnNo }}</code></TableCell>
            <TableCell><code class="text-xs">{{ row.sourceOrderNo }}</code></TableCell>
            <TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.partyCode }}</code><div class="mt-1 truncate font-medium" :title="row.partyName">{{ row.partyName }}</div></TableCell>
            <TableCell class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
            <TableCell class="text-center"><div class="flex flex-col items-center gap-1"><Badge variant="outline" :class="statusClassNames[row.status]">{{ statusLabels[row.status] }}</Badge><span class="text-[11px] text-muted-foreground">{{ statusHint(row.status) }}</span></div></TableCell>
            <TableCell class="text-right font-semibold tabular-nums">{{ formatMoney(row.totalAmount) }}</TableCell>
            <TableCell class="text-center">{{ row.expectedExecutionDate || '未设置' }}</TableCell>
            <TableCell class="truncate whitespace-nowrap text-xs text-muted-foreground" :title="row.updateTime">{{ row.updateTime }}</TableCell>
            <TableCell class="text-center" data-return-actions-column><Button variant="ghost" size="sm" :class="hasReturnActions(row) ? 'h-8 px-2.5 font-medium text-teal-700 hover:bg-teal-50 hover:text-teal-800' : 'h-8 px-2.5 font-medium text-indigo-600 hover:bg-indigo-50 hover:text-indigo-700'" :disabled="detailLoading || actionSubmitting" @click="openDetail(row)">{{ detailLoading ? '加载中' : hasReturnActions(row) ? '处理' : '查看' }}</Button></TableCell>
          </TableRow>
        </TableBody>
      </Table>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="formDialogOpen">
      <DialogContent placement="app-content" class="flex h-[min(790px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[74rem]" data-return-form-dialog>
        <DialogHeader><DialogTitle>{{ dialogMode === 'create' ? config.createTitle : config.editTitle }}</DialogTitle><DialogDescription>只选择已有实际履约数量的原订单明细；快照、金额、状态和审计字段由后端维护。</DialogDescription></DialogHeader>
        <DialogScrollArea>
          <div class="space-y-4 p-1">
            <div class="grid grid-cols-3 gap-3 rounded-md border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
              <div><span class="text-muted-foreground">退回单号</span><div class="mt-1 font-medium">{{ editingDetail?.returnNo || '保存后由系统生成' }}</div></div>
              <div><span class="text-muted-foreground">退回类型</span><div class="mt-1 font-medium">{{ returnTypeLabel }}（固定）</div></div>
              <div><span class="text-muted-foreground">单据状态</span><div class="mt-1 font-medium">{{ editingDetail ? statusLabels[editingDetail.status] : '保存后为草稿' }}</div></div>
            </div>

            <div class="grid grid-cols-3 gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="space-y-1"><Label>{{ config.sourceOrderLabel }} <span class="text-destructive">*</span></Label><RemoteSearchSelect :model-value="form.sourceOrderId" :selected-label="selectedSourceLabel" :fetch-options="fetchSourceOrderOptions" :placeholder="config.sourceOrderPlaceholder" :search-placeholder="config.sourceOrderSearchPlaceholder" :invalid="Boolean(formErrors.sourceOrderId)" @update:model-value="handleSourceChange" /><p v-if="formErrors.sourceOrderId" class="text-xs text-destructive">{{ formErrors.sourceOrderId }}</p></div>
              <div class="space-y-1"><Label>{{ config.partyLabel }}</Label><Input :model-value="selectedSource ? `${selectedSource.partyCode} ${selectedSource.partyName}` : editingDetail ? `${editingDetail.partyCode} ${editingDetail.partyName}` : ''" disabled placeholder="选择原订单后自动带出" /></div>
              <div class="space-y-1"><Label>{{ config.warehouseLabel }} <span class="text-destructive">*</span></Label><RemoteSearchSelect v-model="form.warehouseId" :selected-label="selectedWarehouseLabel" :fetch-options="fetchWarehouseOptions" placeholder="请选择仓库" search-placeholder="输入仓库编码或名称" :invalid="Boolean(formErrors.warehouseId)" /><p v-if="formErrors.warehouseId" class="text-xs text-destructive">{{ formErrors.warehouseId }}</p></div>
              <div class="space-y-1"><Label>{{ config.executionDateLabel }}</Label><OrderDatePicker v-model="form.expectedExecutionDate" /></div>
              <div class="space-y-1"><Label>处理方式 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.handlingType" :options="handlingOptions" /></div>
              <div class="space-y-1"><Label>退货原因 <span class="text-destructive">*</span></Label><AnchoredSelect v-model="form.reasonCode" :options="reasonOptions" /></div>
            </div>

            <div class="grid grid-cols-2 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>原因说明 <span v-if="form.reasonCode === 'OTHER'" class="text-destructive">*</span></Label><Textarea v-model="form.returnReason" rows="3" placeholder="说明退货事实与补充原因" :aria-invalid="Boolean(formErrors.returnReason)" /><p v-if="formErrors.returnReason" class="text-xs text-destructive">{{ formErrors.returnReason }}</p></div>
              <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" rows="3" placeholder="可选，最多 500 字" :aria-invalid="Boolean(formErrors.remark)" /><p v-if="formErrors.remark" class="text-xs text-destructive">{{ formErrors.remark }}</p></div>
            </div>

            <div class="rounded-md border">
              <div class="flex min-h-11 items-center justify-between gap-3 border-b px-3"><div><strong class="text-sm">退货明细</strong><span class="ml-2 text-xs text-muted-foreground">选择原单已履约产品后填写退货数量，提交和审核时由服务端再次校验</span></div><div class="flex shrink-0 items-center gap-3"><span class="text-xs text-muted-foreground">已添加 {{ selectedProductCount }} / {{ selectableProductCount }} 项</span><Button size="sm" variant="outline" type="button" :disabled="!canAddLine" @click="addLine">添加产品</Button></div></div>
              <ScrollArea class="w-full">
                <Table class="order-line-table min-w-[1080px] table-fixed" data-return-form-items>
                  <colgroup><col class="w-[220px]" /><col class="w-[95px]" /><col class="w-[95px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[100px]" /><col class="w-[105px]" /><col class="w-[130px]" /><col class="w-[60px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-right">{{ config.fulfilledQuantityLabel }}</TableHead><TableHead class="text-right">其他退货已占</TableHead><TableHead class="text-right">剩余可退</TableHead><TableHead class="text-right">申请数量</TableHead><TableHead class="text-right">原单价</TableHead><TableHead class="text-right">预计金额</TableHead><TableHead>明细备注</TableHead><TableHead class="text-right">操作</TableHead></TableRow></TableHeader>
                  <TableBody>
                    <TableRow v-if="!form.sourceOrderId"><TableCell colspan="9" class="h-24 text-center text-muted-foreground">请先选择{{ config.sourceOrderLabel }}</TableCell></TableRow>
                    <TableRow v-if="draftLines.length === 0"><TableCell colspan="9" class="h-24 text-center text-muted-foreground">该订单暂无剩余可退明细</TableCell></TableRow>
                    <TableRow v-for="(line, index) in draftLines" v-else :key="line.rowId" :data-source-item-id="line.sourceOrderItemId">
                      <TableCell class="align-top"><RemoteSearchSelect :model-value="line.sourceOrderItemId" :selected-label="selectedProductLabel(line)" :fetch-options="keyword => fetchReturnProductOptions(keyword, line.rowId)" placeholder="请选择产品" search-placeholder="输入产品编码或名称" :invalid="Boolean(formErrors[`items.${index}.productId`])" @update:model-value="value => selectReturnProduct(line, value)" /><p v-if="formErrors[`items.${index}.productId`]" class="mt-1 text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p></TableCell>
                      <TableCell class="text-right tabular-nums">{{ line.sourceOrderItemId ? `${formatQuantity(line.sourceFulfilledQty, line.quantityPrecision)} ${line.unitName}` : '-' }}</TableCell>
                      <TableCell class="text-right tabular-nums">{{ line.sourceOrderItemId ? `${formatQuantity(line.occupiedQty, line.quantityPrecision)} ${line.unitName}` : '-' }}</TableCell>
                      <TableCell class="text-right tabular-nums">{{ line.sourceOrderItemId ? `${formatQuantity(line.availableReturnQty, line.quantityPrecision)} ${line.unitName}` : '-' }}</TableCell>
                      <TableCell class="align-top"><div class="flex items-center gap-2"><Input v-model.number="line.requestedQty" type="number" min="0" :max="line.availableReturnQty" :step="quantityStep(line.quantityPrecision)" :disabled="!line.sourceOrderItemId" :class="['min-w-0 text-right', { 'border-destructive focus-visible:border-destructive': formErrors[`items.${index}.requestedQty`] }]" @blur="validateRequestedQtyOnBlur(line, index)" /><span v-if="line.unitName" class="shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span></div></TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ line.sourceOrderItemId ? formatMoney(line.unitPrice) : '-' }}</TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ line.sourceOrderItemId ? formatMoney(Number(line.requestedQty || 0) * line.unitPrice) : '-' }}</TableCell>
                      <TableCell class="align-top"><Input v-model="line.remark" :disabled="!line.sourceOrderItemId" placeholder="可选" /><p v-if="formErrors[`items.${index}.remark`]" class="text-xs text-destructive">{{ formErrors[`items.${index}.remark`] }}</p></TableCell>
                      <TableCell class="align-top text-center"><Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" :disabled="draftLines.length === 1" @click="removeLine(line.rowId)">删除</Button></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex items-center justify-between border-t px-4 py-3 text-sm"><span v-if="formErrors.items" class="text-xs text-destructive">{{ formErrors.items }}</span><span v-else class="text-muted-foreground">草稿不占用数量，提交后才占用</span><span>预计退回金额：<strong class="ml-2 text-sm">{{ formatMoney(draftAmount) }}</strong></span></div>
            </div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="formDialogOpen = false">取消</Button><Button :disabled="formSubmitting" @click="submitForm">{{ formSubmitting ? '保存中' : dialogMode === 'create' ? '保存草稿' : '保存修改' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailDialogOpen">
      <DialogContent placement="app-content" :inert="(confirmState.open || promptState.open) ? '' : undefined" data-order-workbench class="return-order-workbench flex h-[min(770px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden !bg-[#f6f8fb] !p-4 sm:max-w-5xl" data-return-detail-dialog>
        <DialogHeader class="sr-only"><DialogTitle>{{ config.detailTitle }}</DialogTitle><DialogDescription>{{ detailActionDescription() }}</DialogDescription></DialogHeader>
        <DialogScrollArea content-class="px-5 py-5 pr-6">
          <div v-if="detailRow" class="space-y-5">
            <BusinessDetailHero
              :eyebrow="returnTypeLabel"
              :title="detailRow.returnNo"
              :subtitle="`${detailRow.partyCode} · ${detailRow.partyName} · ${detailRow.warehouseName}`"
              :status-label="statusLabels[detailRow.status]"
              :status-class="statusClassNames[detailRow.status]"
              :metric-columns="3"
              variant="canvas"
            >
              <template #metrics>
                <div class="business-detail-hero__metric"><span>退回金额</span><strong>{{ formatMoney(detailRow.totalAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>商品明细</span><strong>{{ detailRow.items.length }} 项</strong></div>
                <div class="business-detail-hero__metric"><span>{{ config.executionDateLabel }}</span><strong>{{ detailRow.expectedExecutionDate || '未设置' }}</strong></div>
                <div class="business-detail-hero__metric"><span>{{ executionSummaryCopy.amountLabel }}</span><strong>{{ formatMoney(returnExecutionSummary(detailRow).completedAmount) }}</strong></div>
                <div class="business-detail-hero__metric"><span>{{ config.warehouseLabel }}</span><strong>{{ detailRow.warehouseName }}</strong></div>
                <div class="business-detail-hero__metric"><span>{{ config.partyLabel }}</span><strong>{{ detailRow.partyName }}</strong></div>
              </template>
            </BusinessDetailHero>

            <BusinessDetailWorkbenchCard><section class="return-workbench-section return-workbench-section--progress"><BusinessExecutionProgress
              :steps="returnProgressSteps(detailRow)"
              description="状态由退回、审核和仓储执行流程生成，不能在详情中直接修改。"
              :amount-label="executionSummaryCopy.amountLabel"
              :completed-amount="returnExecutionSummary(detailRow).completedAmount"
              :completion-rate="returnExecutionSummary(detailRow).completionRate"
              :completion-rate-hint="executionSummaryCopy.hint"
              :metrics="[
                { label: executionSummaryCopy.processedLabel, value: returnExecutionSummary(detailRow).processedItems },
                { label: executionSummaryCopy.pendingLabel, value: returnExecutionSummary(detailRow).pendingItems, pending: returnExecutionSummary(detailRow).pendingItems > 0 },
                { label: '已完成明细', value: returnExecutionSummary(detailRow).completedItems },
              ]"
            /></section></BusinessDetailWorkbenchCard>

            <BusinessDetailWorkbenchCard>
              <section class="return-workbench-section return-workbench-info"><h3 class="return-workbench-info__title">业务信息</h3><dl class="return-workbench-info__facts"><div class="return-workbench-info__fact"><dt>{{ config.partyLabel }}</dt><dd><strong>{{ detailRow.partyName }}</strong><small>{{ detailRow.partyCode }}</small></dd></div><div class="return-workbench-info__fact"><dt>{{ config.executionDateLabel }}</dt><dd>{{ detailRow.expectedExecutionDate || '未设置' }}</dd></div><div class="return-workbench-info__fact"><dt>退回单号</dt><dd><code>{{ detailRow.returnNo }}</code></dd></div><div class="return-workbench-info__fact"><dt>{{ config.sourceOrderLabel }}</dt><dd><code>{{ detailRow.sourceOrderNo }}</code></dd></div><div class="return-workbench-info__fact"><dt>{{ config.warehouseLabel }}</dt><dd>{{ detailRow.warehouseName }}</dd></div><div class="return-workbench-info__fact"><dt>处理方式</dt><dd>{{ handlingLabel(detailRow.handlingType) }}</dd></div><div class="return-workbench-info__fact"><dt>退回原因</dt><dd>{{ reasonLabel(detailRow.reasonCode) }}</dd></div></dl></section>
              <section class="return-workbench-section">
              <div class="mb-2 flex items-center justify-between gap-3"><div><h3 class="text-sm font-semibold">商品明细</h3><p class="mt-1 text-xs text-muted-foreground">核对原单履约、申请与审核数量，以及仓储实际处理进度。</p></div><span class="text-xs text-muted-foreground">共 {{ detailRow.items.length }} 项</span></div>
              <ScrollArea class="purchase-order-line-scroll detail-table-floating w-full" aria-label="退回单商品明细">
                <Table class="return-detail-items min-w-[1180px] table-fixed" data-return-detail-items>
                  <colgroup><col class="w-[240px]" /><col class="w-[105px]" /><col class="w-[115px]" /><col class="w-[115px]" /><col class="w-[120px]" /><col class="w-[120px]" /><col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[160px]" /></colgroup>
                  <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">{{ config.fulfilledQuantityLabel }}</TableHead><TableHead class="text-center">申请退回</TableHead><TableHead class="text-center">审核退回</TableHead><TableHead class="text-center">{{ processedQuantityLabel }}</TableHead><TableHead class="text-center">{{ pendingQuantityLabel }}</TableHead><TableHead class="text-center">单价</TableHead><TableHead class="text-center">金额</TableHead><TableHead>明细备注</TableHead></TableRow></TableHeader>
                  <TableBody><TableRow v-for="item in detailRow.items" :key="item.returnOrderItemId"><TableCell><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><div class="mt-1">{{ item.productName }}</div></TableCell><TableCell class="text-center tabular-nums">{{ formatQuantity(item.sourceFulfilledQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell><TableCell class="text-center tabular-nums">{{ formatQuantity(item.requestedQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell><TableCell class="text-center"><div v-if="detailActionMode === 'approve'" class="flex items-center gap-2"><Input v-model.number="approvalQuantities[item.returnOrderItemId]" type="number" min="0" :max="item.requestedQty" :step="quantityStep(item.quantityPrecision)" class="text-right" /><span class="text-xs text-muted-foreground">{{ item.unitName }}</span></div><span v-else class="tabular-nums">{{ formatQuantity(item.approvedQty, item.quantityPrecision) }} {{ item.unitName }}</span></TableCell><TableCell class="text-center tabular-nums">{{ formatQuantity(item.processedQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell><TableCell class="text-center font-medium tabular-nums" :class="item.approvedQty > item.processedQty ? 'text-amber-700' : 'text-emerald-700'">{{ formatQuantity(Math.max(0, item.approvedQty - item.processedQty), item.quantityPrecision) }} {{ item.unitName }}</TableCell><TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell><TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell><TableCell><OverflowTooltip :text="item.remark" fallback="未维护" class="block text-muted-foreground" /></TableCell></TableRow></TableBody>
                </Table>
              </ScrollArea>
              </section>
            </BusinessDetailWorkbenchCard>

            <BusinessDetailWorkbenchCard><section class="return-workbench-section"><div class="mb-2"><h3 class="text-sm font-semibold">流程记录</h3><p class="mt-1 text-xs text-muted-foreground">聚合退回单审计字段和仓储执行状态，不额外新增操作日志。</p></div><BusinessDetailTimeline :items="returnTimelineItems(detailRow)" :aria-label="`${config.detailTitle}流程记录`" /><div v-if="detailRow.statusReason || detailRow.returnReason || detailRow.remark" class="return-workbench-notes"><p v-if="detailRow.statusReason"><span>状态原因</span>{{ detailRow.statusReason }}</p><p v-if="detailRow.returnReason"><span>原因说明</span>{{ detailRow.returnReason }}</p><p v-if="detailRow.remark"><span>备注</span>{{ detailRow.remark }}</p></div></section></BusinessDetailWorkbenchCard>
          </div>
        </DialogScrollArea>
        <DialogFooter class="items-center justify-between gap-3"><div class="mr-auto flex items-center gap-3"><Button v-if="detailRow && hasReturnActions(detailRow)" variant="outline" class="border-rose-200 bg-white text-rose-700 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-800" :disabled="actionSubmitting" @click="runDetailRiskAction('cancel')">取消{{ businessLabel }}</Button><Button v-if="detailRow && detailRow.status === 'DRAFT' && canManage" variant="outline" class="border-rose-200 bg-white text-rose-700 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-800" :disabled="actionSubmitting" @click="runDetailRiskAction('delete')">删除草稿</Button></div><Button v-if="detailRow && detailRow.status === 'DRAFT' && canManage" variant="outline" :disabled="actionSubmitting" @click="openDetailEdit(detailRow)">编辑</Button><Button v-if="detailRow && (detailActionMode === 'submit' || detailActionMode === 'approve')" :disabled="actionSubmitting" @click="runDetailAction">{{ actionSubmitting ? '处理中' : detailActionButtonLabel() }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog placement="app-content" :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" cancel-text="取消" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
    <PromptDialog :open="promptState.open" :title="promptState.title" :description="promptState.description" :input-placeholder="'请输入取消原因'" :input-pattern="/^\s*\S[\s\S]{0,499}$/" input-error-message="原因必填且不能超过 500 字" :confirm-text="promptState.confirmText" :loading="actionSubmitting" @update:open="promptState.open = $event" @confirm="runReasonAction" />
  </section>
</template>

<style scoped>
.return-workbench-section { padding: 18px 20px; }
.return-workbench-section + .return-workbench-section { border-top: 1px solid #e5eaf0; }
.return-workbench-section--progress { padding: 0; }
.return-workbench-info__title { margin: 0 0 14px; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.return-workbench-info__facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 42px; margin: 0; }
.return-workbench-info__fact { display: grid; grid-template-columns: 88px minmax(0, 1fr); column-gap: 10px; align-items: start; min-width: 0; color: var(--foreground); font-size: 13px; line-height: 20px; }
.return-workbench-info__fact dt { color: var(--muted-foreground); font-size: inherit; white-space: nowrap; }
.return-workbench-info__fact dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.return-workbench-info__fact dd > strong { font-weight: 600; }
.return-workbench-info__fact dd > small { margin-left: 7px; color: var(--muted-foreground); font-size: inherit; }
.return-workbench-info__fact dd > code { font-size: inherit; }
.return-workbench-notes { display: grid; gap: 8px; margin-top: 14px; color: var(--muted-foreground); font-size: 12px; line-height: 20px; }
.return-workbench-notes p { margin: 0; padding: 8px 10px; border-radius: 8px; background: var(--muted); }
.return-workbench-notes span { margin-right: 8px; color: var(--foreground); font-weight: 600; }

@media (max-width: 640px) {
  .return-workbench-info__facts { grid-template-columns: 1fr; gap: 9px; }
}
</style>
