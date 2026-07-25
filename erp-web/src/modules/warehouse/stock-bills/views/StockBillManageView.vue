<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Columns3, RotateCcw, X } from 'lucide-vue-next';
import { CollapsibleContent, CollapsibleRoot } from 'reka-ui';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import DataTablePagination from '@/components/common/DataTablePagination.vue';
import ListFilterActions from '@/components/common/ListFilterActions.vue';
import ListFilterPanel from '@/components/common/ListFilterPanel.vue';
import ListLoadingOverlay from '@/components/common/ListLoadingOverlay.vue';
import ListSummaryStrip from '@/components/common/ListSummaryStrip.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import WarehouseDetailTableFrame from '@/components/common/WarehouseDetailTableFrame.vue';
import RowActionsMenu from '@/components/common/RowActionsMenu.vue';
import type { RowActionOption } from '@/components/common/RowActionsMenu.vue';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { usePagedQuery } from '@/shared/composables/use-paged-query';
import { useAuthStore } from '@/modules/auth/stores/authStore';
import { listProducts } from '@/modules/product/products/api';
import type { ProductListItem } from '@/modules/product/products/types';
import { searchSupplierOptions } from '@/modules/purchase/api';
import { listPurchaseOrders } from '@/modules/purchase/api';
import { getPurchaseOrderDetail } from '@/modules/purchase/api';
import { searchPurchaseReturnSourceOrders } from '@/modules/purchase/returns/api';
import { listPurchaseReturnSourceItems } from '@/modules/purchase/returns/api';
import { searchCustomerOptions } from '@/modules/sales/api';
import { listSalesOrders } from '@/modules/sales/api';
import { getSalesOrderDetail } from '@/modules/sales/api';
import { searchSalesReturnSourceOrders } from '@/modules/sales/returns/api';
import { listSalesReturnSourceItems } from '@/modules/sales/returns/api';
import { listWarehouses } from '../../warehouses/api';
import type { WarehouseListItem } from '../../warehouses/types';
import { stockBillListColumns, stockBillOptionalColumns, useStockBillTableColumns } from '../composables/use-stock-bill-table-columns';
import type { StockBillOptionalColumnKey } from '../composables/use-stock-bill-table-columns';
import {
  cancelStockBill,
  confirmStockBill,
  createStockBill,
  getStockBillDetail,
  listStockBills,
  submitStockBill,
  updateStockBill,
} from '../api';
import type {
  ManualStockBillType,
  StockBillCreatePayload,
  StockBillDetail,
  StockBillDirection,
  StockBillDraftItemPayload,
  StockBillEntryMode,
  StockBillItem,
  StockBillListItem,
  StockBillQuery,
  StockBillStatus,
  StockBillSummary,
  StockBillType,
  StockBillUpdatePayload,
} from '../types';

interface DraftFormItem extends StockBillDraftItemPayload {
  key: string;
  productCode?: string;
  productName?: string;
  unitName?: string;
  quantityPrecision?: number;
  planQty?: number;
  processedQty?: number | null;
  pendingQty?: number | null;
}

const route = useRoute();
const authStore = useAuthStore();
const emptySummary = (): StockBillSummary => ({ sourceGeneratedCount: 0, pendingCount: 0, confirmedCount: 0, cancelledCount: 0 });

const inboundTypes = new Set<StockBillType>(['PURCHASE_IN', 'SALES_RETURN', 'ADJUST_IN']);
const outboundTypes = new Set<StockBillType>(['SALES_OUT', 'PURCHASE_RETURN', 'ADJUST_OUT']);
const adjustmentTypes = new Set<StockBillType>(['ADJUST_IN', 'ADJUST_OUT']);

const pageDirection = computed<StockBillDirection>(() => String(route.meta.stockDirection) === 'OUTBOUND' ? 'OUTBOUND' : 'INBOUND');
const isInboundPage = computed(() => pageDirection.value === 'INBOUND');
const {
  isVisible: isListColumnVisible,
  reset: resetListColumnsImmediately,
  setVisible: setListColumnVisibleImmediately,
  tableMinWidth,
  visibleColumnCount,
  visibleOptionalCount,
} = useStockBillTableColumns(pageDirection);

type ColumnLayoutState = 'idle' | 'leaving' | 'entering';

const stockBillTableScroll = ref<HTMLElement | null>(null);
const columnLayoutState = ref<ColumnLayoutState>('idle');
const columnLayoutAnnouncement = ref('');
const pendingColumnVisibility = reactive<Partial<Record<StockBillOptionalColumnKey, boolean>>>({});
let pendingColumnReset = false;
let columnLayoutGeneration = 0;
let columnLayoutDisposed = false;
let columnLeaveTimer: number | undefined;
let columnEnterTimer: number | undefined;
let columnStableFrame: number | undefined;
let resolveColumnStableFrame: ((valid: boolean) => void) | undefined;
const preservedColumnScrollLeft = ref(0);

function prefersReducedColumnMotion() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function getStockBillTableViewport() {
  return stockBillTableScroll.value?.querySelector<HTMLElement>('[data-slot="table-container"]') ?? null;
}

function restoreColumnScrollLeft(viewport: HTMLElement) {
  viewport.scrollLeft = Number.MAX_SAFE_INTEGER;
  const maxScrollLeft = viewport.scrollLeft;
  viewport.scrollLeft = Math.min(preservedColumnScrollLeft.value, maxScrollLeft);
}

function isPendingListColumnVisible(key: StockBillOptionalColumnKey) {
  return pendingColumnVisibility[key] ?? isListColumnVisible(key);
}

function announceColumnLayoutUpdated(generation: number) {
  columnLayoutAnnouncement.value = '';
  void nextTick(() => {
    if (columnLayoutDisposed || generation !== columnLayoutGeneration) return;
    columnLayoutAnnouncement.value = '显示字段已更新';
  });
}

function clearColumnStableFrame() {
  if (columnStableFrame !== undefined) window.cancelAnimationFrame(columnStableFrame);
  columnStableFrame = undefined;
  resolveColumnStableFrame?.(false);
  resolveColumnStableFrame = undefined;
}

function waitForColumnStableFrame(generation: number) {
  clearColumnStableFrame();
  return new Promise<boolean>(resolve => {
    resolveColumnStableFrame = resolve;
    columnStableFrame = window.requestAnimationFrame(() => {
      columnStableFrame = undefined;
      resolveColumnStableFrame = undefined;
      resolve(!columnLayoutDisposed && generation === columnLayoutGeneration);
    });
  });
}

function cancelColumnLayoutWork() {
  columnLayoutGeneration += 1;
  if (columnLeaveTimer !== undefined) window.clearTimeout(columnLeaveTimer);
  if (columnEnterTimer !== undefined) window.clearTimeout(columnEnterTimer);
  columnLeaveTimer = undefined;
  columnEnterTimer = undefined;
  clearColumnStableFrame();
}

async function flushColumnLayoutUpdate(generation: number) {
  columnLeaveTimer = undefined;
  if (columnLayoutDisposed || generation !== columnLayoutGeneration) return;
  if (pendingColumnReset) {
    resetListColumnsImmediately();
  } else {
    for (const column of stockBillOptionalColumns) {
      const visible = pendingColumnVisibility[column.key];
      if (typeof visible === 'boolean') setListColumnVisibleImmediately(column.key, visible);
    }
  }
  pendingColumnReset = false;
  for (const column of stockBillOptionalColumns) delete pendingColumnVisibility[column.key];

  await nextTick();
  if (columnLayoutDisposed || generation !== columnLayoutGeneration) return;
  if (!await waitForColumnStableFrame(generation)) return;
  const viewport = getStockBillTableViewport();
  if (viewport) restoreColumnScrollLeft(viewport);
  announceColumnLayoutUpdated(generation);

  if (prefersReducedColumnMotion()) {
    columnLayoutState.value = 'idle';
    return;
  }

  columnLayoutState.value = 'entering';
  columnEnterTimer = window.setTimeout(() => {
    columnEnterTimer = undefined;
    if (columnLayoutDisposed || generation !== columnLayoutGeneration) return;
    columnLayoutState.value = 'idle';
    const stableViewport = getStockBillTableViewport();
    if (stableViewport) restoreColumnScrollLeft(stableViewport);
  }, 140);
}

function scheduleColumnLayoutUpdate() {
  if (columnLayoutState.value !== 'leaving') {
    preservedColumnScrollLeft.value = getStockBillTableViewport()?.scrollLeft ?? 0;
  }
  cancelColumnLayoutWork();
  const generation = columnLayoutGeneration;
  columnLayoutState.value = 'leaving';

  if (prefersReducedColumnMotion()) {
    void flushColumnLayoutUpdate(generation);
    return;
  }

  columnLeaveTimer = window.setTimeout(() => {
    void flushColumnLayoutUpdate(generation);
  }, 90);
}

function setListColumnVisible(key: StockBillOptionalColumnKey, visible: boolean) {
  pendingColumnReset = false;
  pendingColumnVisibility[key] = visible;
  scheduleColumnLayoutUpdate();
}

function resetListColumns() {
  pendingColumnReset = true;
  for (const column of stockBillOptionalColumns) pendingColumnVisibility[column.key] = true;
  scheduleColumnLayoutUpdate();
}

onBeforeUnmount(() => {
  columnLayoutDisposed = true;
  cancelColumnLayoutWork();
});
const defaultBillType = computed<ManualStockBillType>(() => isInboundPage.value ? 'ADJUST_IN' : 'ADJUST_OUT');
const pageText = computed(() => ({
  title: isInboundPage.value ? '入库单' : '出库单',
  description: isInboundPage.value
    ? '采购、销售退货和调整入库先形成待确认入库单，仓库确认本次入库数量后才更新库存'
    : '销售、采购退货和调整出库先形成待确认出库单，仓库确认本次出库数量后才更新库存',
  pendingLabel: '本页待确认',
  confirmedLabel: '本页已确认',
  cancelledLabel: '本页已取消',
  sourceGeneratedLabel: '本页系统生成',
  billNoLabel: isInboundPage.value ? '入库单号' : '出库单号',
  sourceNoPlaceholder: isInboundPage.value ? '如 PO202606001' : '如 SO202606001',
  listQtyLabel: isInboundPage.value ? '入库量' : '出库量',
  currentQtyLabel: isInboundPage.value ? '本次入库数量' : '本次出库数量',
  processedLabel: isInboundPage.value ? '累计已入库' : '累计已出库',
  pendingQtyLabel: isInboundPage.value ? '剩余未入库' : '剩余未出库',
  createButton: isInboundPage.value ? '新增入库单' : '新增出库单',
  formTitle: isInboundPage.value ? '入库单' : '出库单',
  confirmTitle: isInboundPage.value ? '确认入库' : '确认出库',
  emptyText: isInboundPage.value ? '暂无符合条件的入库单' : '暂无符合条件的出库单',
  partyColumnLabel: '来源对象',
}));

const loading = ref(false);
const queryPending = ref(false);
const requestSequence = ref(0);
const records = ref<StockBillListItem[]>([]);
const total = ref(0);
const summary = reactive(emptySummary());
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<StockBillDetail | null>(null);
const detailActionMode = ref<'view' | 'submit' | 'confirm'>('view');
const expandedDetails = reactive<Record<string, StockBillDetail | undefined>>({});
const detailLoadingIds = ref<Set<string>>(new Set());
const detailLoadErrors = reactive<Record<string, string | undefined>>({});
const expandedDetailIds = ref<Set<string>>(new Set());
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
  direction: pageDirection.value,
  billNo: '',
  sourceNo: '',
  warehouseId: 'all',
  billType: 'all',
  entryMode: 'all',
  status: 'all',
  pageNum: 1,
  pageSize: 10,
});
const form = reactive<{ billType: ManualStockBillType; sourceNo: string; sourceId: string; warehouseId: string; sourcePartyId: string; manualReason: string; remark: string; items: DraftFormItem[] }>({
  billType: defaultBillType.value,
  sourceNo: '',
  sourceId: '',
  warehouseId: '',
  sourcePartyId: '',
  manualReason: '',
  remark: '',
  items: [],
});
const sourceNoMode = ref<'search' | 'manual'>('search');

const queryBusy = computed(() => loading.value || queryPending.value);
const formBillType = computed<StockBillType>(() => dialogMode.value === 'edit' && editingDetail.value ? editingDetail.value.billType : form.billType);
const qualityFieldsVisible = computed(() => formBillType.value === 'PURCHASE_IN' || formBillType.value === 'SALES_RETURN');
const isAdjustmentForm = computed(() => adjustmentTypes.has(formBillType.value));
const isManualForm = computed(() => dialogMode.value === 'create' || editingDetail.value?.entryMode !== 'SOURCE_GENERATED');
const editingIsDraft = computed(() => dialogMode.value === 'edit' && editingDetail.value?.status === 'DRAFT');
const warehouseEditable = computed(() => dialogMode.value === 'create' || editingIsDraft.value);
const sourceNoEditable = computed(() => (dialogMode.value === 'create' || editingIsDraft.value) && isManualForm.value && !isAdjustmentForm.value);
const manualReasonEditable = computed(() => (dialogMode.value === 'create' || editingIsDraft.value) && isManualForm.value);
const structureEditable = computed(() => dialogMode.value === 'create' || (editingIsDraft.value && editingDetail.value?.entryMode !== 'SOURCE_GENERATED'));
const summaryCards = computed(() => [
  { key: 'pending', label: pageText.value.pendingLabel, value: summary.pendingCount, tone: 'warning' as const },
  { key: 'confirmed', label: pageText.value.confirmedLabel, value: summary.confirmedCount, tone: 'positive' as const },
  { key: 'cancelled', label: pageText.value.cancelledLabel, value: summary.cancelledCount },
  { key: 'source-generated', label: pageText.value.sourceGeneratedLabel, value: summary.sourceGeneratedCount },
]);
const selectedQueryWarehouseLabel = computed(() => query.warehouseId === 'all' ? '全部仓库' : warehouseOptions.value.find(item => item.value === query.warehouseId)?.label || '');
const selectedFormWarehouseLabel = computed(() => formWarehouseOptions.value.find(item => item.value === form.warehouseId)?.label || (editingDetail.value?.warehouseId === form.warehouseId ? editingDetail.value.warehouseName : ''));
const sourcePartyFormDisplay = computed(() => {
  if (adjustmentTypes.has(formBillType.value)) return selectedFormWarehouseLabel.value || '请选择调整仓库';
  return editingDetail.value ? sourcePartyDisplay(editingDetail.value) : '手工补录';
});
const sourcePartyEditable = computed(() => dialogMode.value === 'create');
const sourcePartyOptions = ref<Array<{ value: string; label: string }>>([]);
const selectedSourcePartyLabel = computed(() => sourcePartyOptions.value.find(item => item.value === form.sourcePartyId)?.label || '');
const sourcePartyPlaceholder = computed(() => {
  if (formBillType.value === 'PURCHASE_IN' || formBillType.value === 'PURCHASE_RETURN') return '请选择供应商';
  if (formBillType.value === 'SALES_RETURN' || formBillType.value === 'SALES_OUT') return '请选择客户';
  return '请选择来源仓库';
});
const sourcePartySearchPlaceholder = computed(() => {
  if (formBillType.value === 'PURCHASE_IN' || formBillType.value === 'PURCHASE_RETURN') return '输入供应商编码或名称';
  if (formBillType.value === 'SALES_RETURN' || formBillType.value === 'SALES_OUT') return '输入客户编码或名称';
  return '输入仓库编码或名称';
});
const sourceNoSearchable = computed(() => dialogMode.value === 'create' && !isAdjustmentForm.value);
const sourceOrderOptions = ref<Array<{ value: string; label: string }>>([]);
const selectedSourceOrderLabel = computed(() => sourceOrderOptions.value.find(item => item.value === form.sourceNo)?.label || form.sourceNo || '');

async function fetchSourceOrderSearchOptions(keyword: string) {
  if (formBillType.value === 'PURCHASE_IN') {
    const page = await listPurchaseOrders({ purchaseNo: keyword.trim() || undefined, status: 'APPROVED', pageNum: 1, pageSize: 10 });
    const options = page.records.map(item => ({
      value: item.purchaseNo,
      label: `${item.purchaseNo}（${item.supplierName}）`,
      _meta: { sourceId: item.purchaseOrderId, sourcePartyId: item.supplierId, sourcePartyName: `${item.supplierCode} ${item.supplierName}` },
    }));
    sourceOrderOptions.value = options;
    return options;
  }
  if (formBillType.value === 'SALES_RETURN') {
    const rows = await searchSalesReturnSourceOrders(keyword);
    const options = rows.map(item => ({
      value: item.sourceOrderNo,
      label: `${item.sourceOrderNo}（${item.partyName}）`,
      _meta: { sourceId: item.sourceOrderId, sourcePartyId: item.partyId, sourcePartyName: `${item.partyCode} ${item.partyName}` },
    }));
    sourceOrderOptions.value = options;
    return options;
  }
  if (formBillType.value === 'SALES_OUT') {
    const page = await listSalesOrders({ salesNo: keyword.trim() || undefined, status: 'APPROVED', pageNum: 1, pageSize: 10 });
    const options = page.records.map(item => ({
      value: item.salesNo,
      label: `${item.salesNo}（${item.customerName}）`,
      _meta: { sourceId: item.salesOrderId, sourcePartyId: item.customerId, sourcePartyName: `${item.customerCode} ${item.customerName}` },
    }));
    sourceOrderOptions.value = options;
    return options;
  }
  if (formBillType.value === 'PURCHASE_RETURN') {
    const rows = await searchPurchaseReturnSourceOrders(keyword);
    const options = rows.map(item => ({
      value: item.sourceOrderNo,
      label: `${item.sourceOrderNo}（${item.partyName}）`,
      _meta: { sourceId: item.sourceOrderId, sourcePartyId: item.partyId, sourcePartyName: `${item.partyCode} ${item.partyName}` },
    }));
    sourceOrderOptions.value = options;
    return options;
  }
  return [];
}

function handleSourceOrderSelect(value: string | number) {
  const selected = sourceOrderOptions.value.find(item => item.value === String(value));
  form.sourceNo = String(value);
  if (selected && (selected as any)._meta) {
    const meta = (selected as any)._meta;
    form.sourceId = meta.sourceId || '';
    form.sourcePartyId = meta.sourcePartyId || '';
    sourcePartyOptions.value = [{ value: meta.sourcePartyId, label: meta.sourcePartyName }];
    if (meta.sourceId) loadSourceOrderItems(meta.sourceId);
  }
  clearFormError('sourceNo');
  clearFormError('sourcePartyId');
}

async function loadSourceOrderItems(sourceId: string) {
  try {
    let items: DraftFormItem[] = [];
    if (formBillType.value === 'PURCHASE_IN') {
      const detail = await getPurchaseOrderDetail(sourceId);
      mergeProducts(detail.items.map((i: any) => ({ productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision })) as any);
      items = detail.items.map((i: any) => {
        const plan = i.quantity ?? 0;
        const processed = i.inboundQty ?? 0;
        const pending = Math.max(plan - processed, 0);
        return { key: `${Date.now()}-${Math.random()}`, sourceItemId: i.purchaseOrderItemId, productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision, planQty: plan, processedQty: processed, pendingQty: pending, currentQty: pending, qualifiedQty: pending, defectiveQty: 0, remark: '' };
      });
    } else if (formBillType.value === 'SALES_RETURN') {
      const rows = await listSalesReturnSourceItems(sourceId);
      mergeProducts(rows.map(i => ({ productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision } as any)));
      items = rows.map(i => {
        const plan = i.sourceFulfilledQty ?? 0;
        const processed = i.occupiedQty ?? 0;
        const pending = Math.max(i.availableReturnQty ?? 0, 0);
        return { key: `${Date.now()}-${Math.random()}`, sourceItemId: i.sourceOrderItemId, productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision, planQty: plan, processedQty: processed, pendingQty: pending, currentQty: pending, qualifiedQty: pending, defectiveQty: 0, remark: '' };
      });
    } else if (formBillType.value === 'SALES_OUT') {
      const detail = await getSalesOrderDetail(sourceId);
      mergeProducts(detail.items.map(i => ({ productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision } as any)));
      items = detail.items.map(i => {
        const plan = i.quantity ?? 0;
        const processed = i.outboundQty ?? 0;
        const pending = Math.max(plan - processed, 0);
        return { key: `${Date.now()}-${Math.random()}`, sourceItemId: i.salesOrderItemId, productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision, planQty: plan, processedQty: processed, pendingQty: pending, currentQty: pending, qualifiedQty: 0, defectiveQty: 0, remark: '' };
      });
    } else if (formBillType.value === 'PURCHASE_RETURN') {
      const rows = await listPurchaseReturnSourceItems(sourceId);
      mergeProducts(rows.map(i => ({ productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision } as any)));
      items = rows.map(i => {
        const plan = i.sourceFulfilledQty ?? 0;
        const processed = i.occupiedQty ?? 0;
        const pending = Math.max(i.availableReturnQty ?? 0, 0);
        return { key: `${Date.now()}-${Math.random()}`, sourceItemId: i.sourceOrderItemId, productId: i.productId, productCode: i.productCode, productName: i.productName, unitName: i.unitName, quantityPrecision: i.quantityPrecision, planQty: plan, processedQty: processed, pendingQty: pending, currentQty: pending, qualifiedQty: 0, defectiveQty: 0, remark: '' };
      });
    }
    if (items.length > 0) {
      form.items = items;
    } else {
      toast.info('该来源单据暂无可处理的产品明细');
    }
  } catch {
    toast.warning('加载来源单据明细失败');
  }
}

function toggleSourceNoMode() {
  form.sourceNo = '';
  form.sourceId = '';
  form.items = [newDraftItem()];
  if (sourceNoMode.value === 'search') {
    sourceNoMode.value = 'manual';
    form.sourcePartyId = '';
    sourcePartyOptions.value = [];
  } else {
    sourceNoMode.value = 'search';
  }
  clearFormError('sourceNo');
}

async function fetchSourcePartySearchOptions(keyword: string) {
  if (formBillType.value === 'PURCHASE_IN' || formBillType.value === 'PURCHASE_RETURN') {
    const suppliers = await searchSupplierOptions(keyword, 10);
    const options = suppliers.map(item => ({
      value: item.supplierId,
      label: `${item.supplierCode} ${item.supplierName}`,
    }));
    sourcePartyOptions.value = options;
    return options;
  }
  if (formBillType.value === 'SALES_RETURN' || formBillType.value === 'SALES_OUT') {
    const customers = await searchCustomerOptions(keyword, 10);
    const options = customers.map(item => ({
      value: item.customerId,
      label: `${item.customerCode} ${item.customerName}`,
    }));
    sourcePartyOptions.value = options;
    return options;
  }
  // 调整入库/出库 → 选择来源仓库
  const page = await listWarehouses({ status: 1, pageNum: 1, pageSize: 10, ...warehouseKeywordQuery(keyword) });
  const options = page.records.map(item => ({
    value: item.warehouseId,
    label: `${item.warehouseCode} ${item.warehouseName}`,
  }));
  sourcePartyOptions.value = options;
  return options;
}
const warehouseFieldLabel = computed(() => isInboundPage.value ? '入库仓库' : '出库仓库');

watch(() => form.billType, () => {
  form.sourceNo = '';
  form.sourceId = '';
  form.sourcePartyId = '';
  form.items = [newDraftItem()];
  sourceNoMode.value = 'search';
  sourcePartyOptions.value = [];
  sourceOrderOptions.value = [];
});

const allBillTypeOptions: Array<{ value: StockBillType; label: string }> = [
  { value: 'PURCHASE_IN', label: '采购入库' },
  { value: 'SALES_OUT', label: '销售出库' },
  { value: 'PURCHASE_RETURN', label: '采购退货出库' },
  { value: 'SALES_RETURN', label: '销售退货入库' },
  { value: 'ADJUST_IN', label: '库存调整入库' },
  { value: 'ADJUST_OUT', label: '库存调整出库' },
];
const pageBillTypeOptions = computed<Array<{ value: StockBillType | 'all'; label: string }>>(() => [
  { value: 'all', label: '全部类型' },
  ...allBillTypeOptions.filter(option => isInboundPage.value ? inboundTypes.has(option.value) : outboundTypes.has(option.value)),
]);
const manualBillTypeOptions = computed<Array<{ value: ManualStockBillType; label: string }>>(() =>
  allBillTypeOptions
    .filter(option => isInboundPage.value ? inboundTypes.has(option.value) : outboundTypes.has(option.value))
    .map(option => ({ value: option.value, label: option.value.startsWith('ADJUST') ? option.label : `补录${option.label}` })),
);
const statusOptions: Array<{ value: StockBillStatus | 'all'; label: string }> = [
  { value: 'all', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_CONFIRM', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CANCELLED', label: '已取消' },
];
const entryModeOptions: Array<{ value: StockBillEntryMode | 'all'; label: string }> = [
  { value: 'all', label: '全部录入方式' },
  { value: 'SOURCE_GENERATED', label: '系统生成' },
  { value: 'MANUAL_SUPPLEMENT', label: '人工补录' },
  { value: 'MANUAL_ADJUSTMENT', label: '人工调整' },
];
const billTypeMap: Record<StockBillType, { label: string; className: string }> = {
  PURCHASE_IN: { label: '采购入库', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
  SALES_OUT: { label: '销售出库', className: 'border-sky-200 bg-sky-50 text-sky-700' },
  PURCHASE_RETURN: { label: '采购退货出库', className: 'border-violet-200 bg-violet-50 text-violet-700' },
  SALES_RETURN: { label: '销售退货入库', className: 'border-teal-200 bg-teal-50 text-teal-700' },
  ADJUST_IN: { label: '调整入库', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  ADJUST_OUT: { label: '调整出库', className: 'border-orange-200 bg-orange-50 text-orange-700' },
};
const statusMap: Record<StockBillStatus, { label: string; className: string }> = {
  DRAFT: { label: '草稿', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  PENDING_CONFIRM: { label: '待确认', className: 'border-sky-200 bg-sky-50 text-sky-700' },
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
  SOURCE_GENERATED: '系统生成',
  MANUAL_SUPPLEMENT: '人工补录',
  MANUAL_ADJUSTMENT: '人工调整',
} as const;

function billDirection(billType: StockBillType): StockBillDirection {
  return inboundTypes.has(billType) ? 'INBOUND' : 'OUTBOUND';
}

function sourcePartyLabel(billType: StockBillType) {
  if (billType === 'ADJUST_IN' || billType === 'ADJUST_OUT') return '来源仓库';
  return '来源对象';
}

function sourcePartyDisplay(row: Pick<StockBillListItem, 'billType' | 'sourcePartyName' | 'warehouseName'> | null | undefined) {
  if (!row) return '-';
  return row.sourcePartyName || '-';
}

function planQtyLabel(billType: StockBillType) {
  if (billType === 'PURCHASE_IN') return '采购数量';
  if (billType === 'SALES_OUT') return '销售数量';
  return '计划数量';
}

function formatQty(value: number | null | undefined) {
  if (value === null || value === undefined) return '-';
  return Number.isInteger(value) ? value.toFixed(0) : value.toFixed(2);
}

function formatChangeQty(value: number) {
  if (value === 0) return '0';
  return `${value > 0 ? '+' : ''}${formatQty(value)}`;
}

function billTotalQuantityText(row: StockBillListItem) {
  if (row.totalCurrentQty !== null && row.quantityUnitName) return `${formatQty(row.totalCurrentQty)} ${row.quantityUnitName}`.trim();
  if (row.itemCount > 0) return `${row.itemCount} 条商品`;
  return '-';
}

function itemQuantityText(item: StockBillItem) {
  const quantity = formatQty(item.currentQty);
  return quantity === '-' ? '-' : `${quantity} ${item.unitName}`.trim();
}

function remainingQtyText(item: StockBillItem) {
  const quantity = formatQty(item.pendingQty);
  return quantity === '-' ? '-' : `${quantity} ${item.unitName}`.trim();
}

function isQualityBillType(billType: StockBillType) {
  return billType === 'PURCHASE_IN' || billType === 'SALES_RETURN';
}

function qualityQtyText(item: StockBillItem, billType: StockBillType, field: 'qualifiedQty' | 'defectiveQty') {
  if (!isQualityBillType(billType)) return '-';
  return formatQty(item[field]);
}

function expandedItems(row: StockBillListItem) {
  return expandedDetails[row.workBillId]?.items ?? [];
}

function isRowDetailLoading(row: StockBillListItem) {
  return detailLoadingIds.value.has(row.workBillId);
}

function isRowDetailCollapsed(row: StockBillListItem) {
  return !expandedDetailIds.value.has(row.workBillId);
}

async function toggleRowDetail(row: StockBillListItem) {
  const next = new Set(expandedDetailIds.value);
  if (next.has(row.workBillId)) {
    next.delete(row.workBillId);
    expandedDetailIds.value = next;
    return;
  }
  next.add(row.workBillId);
  expandedDetailIds.value = next;
  if (expandedDetails[row.workBillId]) return;
  detailLoadErrors[row.workBillId] = undefined;
  setRowDetailLoading(row.workBillId, true);
  try {
    expandedDetails[row.workBillId] = await getStockBillDetail(billDirection(row.billType), row.workBillId);
  } catch (error) {
    detailLoadErrors[row.workBillId] = getApiErrorMessage(error) || '商品明细加载失败';
  } finally {
    setRowDetailLoading(row.workBillId, false);
  }
}

function setRowDetailLoading(stockBillId: string, loadingDetail: boolean) {
  const next = new Set(detailLoadingIds.value);
  if (loadingDetail) next.add(stockBillId);
  else next.delete(stockBillId);
  detailLoadingIds.value = next;
}

function newDraftItem(): DraftFormItem {
  return { key: `${Date.now()}-${Math.random()}`, productId: '', currentQty: 1, qualifiedQty: qualityFieldsVisible.value ? 1 : 0, defectiveQty: 0, remark: '' };
}

function productKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { productCode: value } : { productName: value };
}

function warehouseKeywordQuery(keyword: string) {
  const value = keyword.trim();
  if (!value) return {};
  return /^[A-Za-z0-9_-]+$/.test(value) ? { warehouseCode: value } : { warehouseName: value };
}

function mergeWarehouses(records: WarehouseListItem[]) {
  const cache = new Map(warehouses.value.map(item => [item.warehouseId, item]));
  records.forEach(item => cache.set(item.warehouseId, item));
  warehouses.value = Array.from(cache.values());
  warehouseOptions.value = [
    { value: 'all', label: '全部仓库' },
    ...warehouses.value.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` })),
  ];
  formWarehouseOptions.value = warehouses.value
    .filter(item => item.status === 1)
    .map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
}

function mergeProducts(records: ProductListItem[]) {
  const cache = new Map(products.value.map(item => [item.productId, item]));
  records.forEach(item => cache.set(item.productId, item));
  products.value = Array.from(cache.values());
  productOptions.value = products.value.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}（${item.unitName}）` }));
}

async function fetchWarehouseSearchOptions(keyword: string) {
  const page = await listWarehouses({
    pageNum: 1,
    pageSize: 10,
    ...warehouseKeywordQuery(keyword),
  });
  mergeWarehouses(page.records);
  return page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
}

async function fetchFormWarehouseSearchOptions(keyword: string) {
  const page = await listWarehouses({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...warehouseKeywordQuery(keyword),
  });
  mergeWarehouses(page.records);
  return page.records.map(item => ({ value: item.warehouseId, label: `${item.warehouseCode} ${item.warehouseName}` }));
}

async function fetchProductSearchOptions(keyword: string) {
  const page = await listProducts({
    status: 1,
    pageNum: 1,
    pageSize: 10,
    ...productKeywordQuery(keyword),
  });
  mergeProducts(page.records);
  return page.records.map(item => ({ value: item.productId, label: `${item.productCode} ${item.productName}（${item.unitName}）` }));
}

async function loadFormOptions() {
  try {
    const [warehousePage, productPage] = await Promise.all([
      listWarehouses({ pageNum: 1, pageSize: 10 }),
      listProducts({ status: 1, pageNum: 1, pageSize: 10 }),
    ]);
    mergeWarehouses(warehousePage.records);
    mergeProducts(productPage.records);
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || `${pageText.value.title}表单选项加载失败`);
  }
}

async function fetchRecords() {
  const currentSequence = ++requestSequence.value;
  loading.value = true;
  query.direction = pageDirection.value;
  try {
    const page = await listStockBills(query);
    if (currentSequence !== requestSequence.value) return;
    records.value = page.records;
    total.value = page.total;
    Object.assign(summary, page.summary);
    expandedDetailIds.value = new Set();
  } catch (error) {
    if (currentSequence === requestSequence.value) toast.warning(getApiErrorMessage(error) || `${pageText.value.title}查询失败`);
  } finally {
    if (currentSequence === requestSequence.value) {
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
  load: fetchRecords,
  resetFilters: () => {
    Object.assign(query, {
      direction: pageDirection.value,
      billNo: '',
      sourceNo: '',
      warehouseId: 'all',
      billType: 'all',
      entryMode: 'all',
      status: 'all',
    });
  },
});

async function openDetail(row: StockBillListItem, actionMode: 'view' | 'submit' | 'confirm' = 'view') {
  detailActionMode.value = actionMode;
  detailVisible.value = true;
  detailLoading.value = true;
  detail.value = null;
  try {
    detail.value = await getStockBillDetail(billDirection(row.billType), row.workBillId);
  } catch (error) {
    detailVisible.value = false;
    toast.warning(getApiErrorMessage(error) || `${pageText.value.title}详情加载失败`);
  } finally {
    detailLoading.value = false;
  }
}

function resetForm() {
  Object.assign(form, { billType: defaultBillType.value, sourceNo: '', sourceId: '', warehouseId: formWarehouseOptions.value[0]?.value || '', sourcePartyId: '', manualReason: '', remark: '', items: [newDraftItem()] });
  sourceNoMode.value = 'search';
  sourceOrderOptions.value = [];
  editingDetail.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
}

function openCreateDialog() {
  dialogMode.value = 'create';
  resetForm();
  formVisible.value = true;
}

async function openEditDialog(row: StockBillListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'PENDING_CONFIRM') return;
  dialogMode.value = 'edit';
  formVisible.value = true;
  formLoading.value = true;
  editingDetail.value = null;
  Object.keys(formErrors).forEach(key => delete formErrors[key]);
  try {
    const current = await getStockBillDetail(billDirection(row.billType), row.workBillId);
    if (current.status !== 'DRAFT' && current.status !== 'PENDING_CONFIRM') throw new Error('只有草稿或待确认状态可以编辑');
    editingDetail.value = current;
    mergeProducts(current.items.map(item => ({
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      unitName: item.unitName,
      quantityPrecision: item.quantityPrecision,
    } as ProductListItem)));
    form.billType = current.billType;
    form.sourceNo = current.sourceNo;
    form.warehouseId = current.warehouseId;
    form.manualReason = current.manualReason;
    form.remark = current.remark;
    form.items = current.items.map(item => ({
      key: item.workBillItemId,
      workBillItemId: item.workBillItemId,
      productId: item.productId,
      productCode: item.productCode,
      productName: item.productName,
      unitName: item.unitName,
      quantityPrecision: item.quantityPrecision,
      planQty: item.planQty ?? undefined,
      processedQty: item.processedQty,
      pendingQty: item.pendingQty,
      currentQty: item.currentQty,
      qualifiedQty: item.qualifiedQty,
      defectiveQty: item.defectiveQty,
      remark: item.remark,
    }));
  } catch (error) {
    formVisible.value = false;
    toast.warning(getApiErrorMessage(error) || `${pageText.value.title}加载失败`);
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
  if (item.productCode || item.productName) return `${item.productCode || ''} ${item.productName || ''}（${item.unitName || ''}）`.trim();
  const snapshot = editingDetail.value?.items.find(detailItem => detailItem.workBillItemId === item.workBillItemId);
  if (snapshot) return `${snapshot.productCode} ${snapshot.productName}（${snapshot.unitName}）`;
  const product = products.value.find(option => option.productId === item.productId);
  return product ? `${product.productCode} ${product.productName}（${product.unitName}）` : item.productId;
}

function productDisplay(item: DraftFormItem) {
  const snapshot = editingDetail.value?.items.find(detailItem => detailItem.workBillItemId === item.workBillItemId);
  const product = products.value.find(option => option.productId === item.productId);
  return {
    code: item.productCode || snapshot?.productCode || product?.productCode || item.productId || '-',
    name: item.productName || snapshot?.productName || product?.productName || '-',
    unitName: item.unitName || snapshot?.unitName || product?.unitName || '-',
  };
}

function detailItemFor(item: DraftFormItem) {
  return editingDetail.value?.items.find(detailItem => detailItem.workBillItemId === item.workBillItemId) || null;
}

function handleProductChange(item: DraftFormItem, index: number) {
  clearFormError(`items.${index}.productId`);
  const product = products.value.find(option => option.productId === item.productId);
  item.productCode = product?.productCode;
  item.productName = product?.productName;
  item.unitName = product?.unitName;
  item.quantityPrecision = product?.quantityPrecision;
  if (qualityFieldsVisible.value) handleQuantityChange(item, index);
}

function handleQuantityChange(item: DraftFormItem, index: number) {
  clearFormError(`items.${index}.quantity`);
  if (!qualityFieldsVisible.value) return;
  const quantity = Number(item.currentQty) || 0;
  const defectiveQty = Math.min(Math.max(Number(item.defectiveQty) || 0, 0), quantity);
  item.defectiveQty = defectiveQty;
  item.qualifiedQty = Math.max(0, quantity - defectiveQty);
}

function remainingAfterText(item: DraftFormItem) {
  const snapshot = detailItemFor(item);
  if (snapshot?.planQty === null || snapshot?.planQty === undefined || snapshot.processedQty === null || snapshot.processedQty === undefined) return '-';
  return formatQty(Math.max(0, snapshot.planQty - snapshot.processedQty - (Number(item.currentQty) || 0)));
}

function itemPendingQty(item: DraftFormItem): number | null {
  const plan = item.planQty;
  const processed = item.processedQty;
  if (plan == null || processed == null) return null;
  return Math.max(0, plan - processed - (Number(item.currentQty) || 0));
}

function itemQuantityPrecision(item: DraftFormItem) {
  const snapshot = detailItemFor(item);
  return snapshot?.quantityPrecision ?? item.quantityPrecision ?? products.value.find(product => product.productId === item.productId)?.quantityPrecision ?? 0;
}

function itemQuantityStep(item: DraftFormItem) {
  return 10 ** -itemQuantityPrecision(item);
}

function itemUnitName(item: DraftFormItem) {
  const snapshot = detailItemFor(item);
  return snapshot?.unitName ?? item.unitName ?? products.value.find(product => product.productId === item.productId)?.unitName ?? '';
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
  if (warehouseEditable.value && !form.warehouseId) formErrors.warehouseId = '请选择仓库';
  if (sourcePartyEditable.value && !form.sourcePartyId) formErrors.sourcePartyId = isAdjustmentForm.value ? '请选择来源仓库' : (formBillType.value === 'PURCHASE_IN' || formBillType.value === 'PURCHASE_RETURN' ? '请选择供应商' : '请选择客户');
  if (sourceNoEditable.value && !form.sourceNo.trim()) formErrors.sourceNo = sourceNoSearchable.value && sourceNoMode.value === 'search' ? '请选择来源单据' : '请输入原业务单号，便于追溯补录来源';
  if (isManualForm.value && !form.manualReason.trim()) formErrors.manualReason = isAdjustmentForm.value ? '请填写调整原因' : '请填写补录原因';
  else if (form.manualReason.trim().length > 500) formErrors.manualReason = '原因不能超过 500 个字符';
  if (!form.items.length) formErrors.items = '至少添加一条产品明细';
  const selectedProducts = new Set<string>();
  form.items.forEach((item, index) => {
    if (!item.productId) formErrors[`items.${index}.productId`] = '请选择产品';
    else if (selectedProducts.has(item.productId)) formErrors[`items.${index}.productId`] = '同一产品不能重复添加';
    selectedProducts.add(item.productId);
    if (!Number.isFinite(item.currentQty) || item.currentQty <= 0) formErrors[`items.${index}.quantity`] = '数量必须大于 0';
    const precision = itemQuantityPrecision(item);
    if (!formErrors[`items.${index}.quantity`] && !matchesQuantityPrecision(item.currentQty, precision)) {
      formErrors[`items.${index}.quantity`] = precision === 0 ? '该产品按整单位管理，数量必须是整数' : `该产品数量最多保留 ${precision} 位小数`;
    }
    if (qualityFieldsVisible.value) {
      if (!Number.isFinite(item.qualifiedQty) || item.qualifiedQty < 0 || !Number.isFinite(item.defectiveQty) || item.defectiveQty < 0) {
        formErrors[`items.${index}.quality`] = '质量数量不能小于 0';
      } else if (Math.abs(item.qualifiedQty + item.defectiveQty - item.currentQty) > 0.0001) {
        formErrors[`items.${index}.quality`] = '合格与不合格数量之和必须等于本次数量';
      } else if (!matchesQuantityPrecision(item.qualifiedQty, precision) || !matchesQuantityPrecision(item.defectiveQty, precision)) {
        formErrors[`items.${index}.quality`] = precision === 0 ? '质量数量必须是整数' : `质量数量最多保留 ${precision} 位小数`;
      }
    }
    if ((item.remark ?? '').trim().length > 500) formErrors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
  });
  if (form.remark.trim().length > 500) formErrors.remark = '备注不能超过 500 个字符';
  return Object.keys(formErrors).length === 0;
}

function buildItemPayloads(): StockBillDraftItemPayload[] {
  return form.items.map(item => ({
    ...(item.workBillItemId ? { workBillItemId: item.workBillItemId } : {}),
    ...(item.sourceItemId ? { sourceItemId: item.sourceItemId } : {}),
    productId: item.productId,
    ...(item.planQty != null ? { planQty: Number(item.planQty) } : {}),
    currentQty: Number(item.currentQty),
    qualifiedQty: qualityFieldsVisible.value ? Number(item.qualifiedQty) : 0,
    defectiveQty: qualityFieldsVisible.value ? Number(item.defectiveQty) : 0,
    remark: item.remark?.trim() ?? '',
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
        ...(form.sourceId ? { sourceId: form.sourceId } : {}),
        warehouseId: form.warehouseId,
        ...(sourcePartyEditable.value && form.sourcePartyId ? {
          sourcePartyId: form.sourcePartyId,
          sourcePartyName: selectedSourcePartyLabel.value || '',
        } : {}),
        manualReason: form.manualReason.trim(),
        items: buildItemPayloads(),
        remark: form.remark.trim(),
      };
      await createStockBill(pageDirection.value, payload);
      toast.success(`${pageText.value.formTitle}草稿已创建`);
    } else if (editingDetail.value) {
      const payload: StockBillUpdatePayload = {
        version: editingDetail.value.version,
        items: buildItemPayloads(),
        ...(warehouseEditable.value ? { warehouseId: form.warehouseId } : {}),
        ...(sourceNoEditable.value ? { sourceNo: form.sourceNo.trim() } : {}),
        ...(manualReasonEditable.value ? { manualReason: form.manualReason.trim() } : {}),
        ...(form.remark.trim() ? { remark: form.remark.trim() } : {}),
      };
      await updateStockBill(billDirection(editingDetail.value.billType), editingDetail.value.workBillId, payload);
      toast.success(`${pageText.value.formTitle}已保存`);
    }
    formVisible.value = false;
    await fetchRecords();
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || `${pageText.value.formTitle}保存失败`);
  } finally {
    formSubmitting.value = false;
  }
}

function openConfirmDialog(options: Omit<typeof confirmState, 'open' | 'onConfirm'> & { onConfirm: () => Promise<void> }) {
  confirmState.title = options.title;
  confirmState.description = options.description;
  confirmState.confirmText = options.confirmText;
  confirmState.variant = options.variant;
  confirmState.onConfirm = options.onConfirm;
  confirmState.open = true;
}

function openConfirmDetail(row: StockBillListItem) {
  if (row.status !== 'PENDING_CONFIRM') return;
  openDetail(row, 'confirm');
}

function openSubmitDetail(row: StockBillListItem) {
  if (row.status !== 'DRAFT') return;
  openDetail(row, 'submit');
}

function getSubmitValidationError(row: StockBillDetail) {
  if (!row.warehouseId || !row.warehouseName) return '提交前必须选择仓库';
  if (row.entryMode === 'MANUAL_SUPPLEMENT' && !row.sourceNo.trim()) return '手工补录提交前必须填写原业务单号';
  if (row.entryMode !== 'SOURCE_GENERATED' && !row.manualReason.trim()) return row.entryMode === 'MANUAL_ADJUSTMENT' ? '提交前必须填写调整原因' : '提交前必须填写补录原因';
  if (!row.items.length) return '提交前至少需要一条产品明细';
  for (const item of row.items) {
    if (!item.productId || !item.productCode || !item.productName || !item.unitName) return '提交前产品明细必须完整';
    if (!Number.isFinite(item.currentQty) || item.currentQty <= 0) return `产品 ${item.productCode} 的本次数量必须大于 0`;
    if (isQualityBillType(row.billType)) {
      if (!Number.isFinite(item.qualifiedQty) || !Number.isFinite(item.defectiveQty) || item.qualifiedQty < 0 || item.defectiveQty < 0) return `产品 ${item.productCode} 的合格数量和不合格数量不能小于 0`;
      if (Math.abs(item.qualifiedQty + item.defectiveQty - item.currentQty) > 0.0001) return `产品 ${item.productCode} 的合格数量与不合格数量之和必须等于本次数量`;
    }
    if (row.entryMode === 'SOURCE_GENERATED' && (item.planQty === null || item.processedQty === null || item.pendingQty === null)) return `系统生成单据的产品 ${item.productCode} 缺少计划、累计或剩余数量`;
  }
  return '';
}

function handleConfirm(row: StockBillListItem | StockBillDetail) {
  if (row.status !== 'PENDING_CONFIRM') return;
  if ('items' in row) {
    const validationError = getConfirmValidationError(row);
    if (validationError) {
      toast.warning(validationError);
      return;
    }
  }
  const currentLabel = billDirection(row.billType) === 'INBOUND' ? '本次入库数量' : '本次出库数量';
  openConfirmDialog({
    title: pageText.value.confirmTitle,
    description: `${row.billNo} 确认后会按${currentLabel}更新库存余额，并同步来源单据的已处理数量；确认后不能再编辑或直接取消。`,
    confirmText: '确认执行',
    variant: 'warning',
    onConfirm: async () => {
      await confirmStockBill(billDirection(row.billType), row.workBillId, row.version);
      toast.success(`${row.billNo} 已确认`);
      detailVisible.value = false;
      await fetchRecords();
    },
  });
}

function getConfirmValidationError(row: StockBillDetail) {
  const currentLabel = billDirection(row.billType) === 'INBOUND' ? '本次入库数量' : '本次出库数量';
  for (const item of row.items) {
    if (!Number.isFinite(item.currentQty) || item.currentQty <= 0) return `产品 ${item.productCode} 请先填写${currentLabel}`;
    if (row.entryMode === 'SOURCE_GENERATED' && item.planQty !== null && item.processedQty !== null) {
      const remainingBefore = Math.max(0, item.planQty - item.processedQty);
      if (item.currentQty > remainingBefore) return `产品 ${item.productCode} 的${currentLabel}不能超过剩余数量 ${formatQty(remainingBefore)}`;
    }
    if (isQualityBillType(row.billType)) {
      if (!Number.isFinite(item.qualifiedQty) || !Number.isFinite(item.defectiveQty) || item.qualifiedQty < 0 || item.defectiveQty < 0) return `产品 ${item.productCode} 的合格数量和不合格数量不能小于 0`;
      if (Math.abs(item.qualifiedQty + item.defectiveQty - item.currentQty) > 0.0001) return `产品 ${item.productCode} 的合格数量与不合格数量之和必须等于${currentLabel}`;
    }
  }
  return '';
}

function handleSubmit(row: StockBillListItem | StockBillDetail) {
  if (row.status !== 'DRAFT') return;
  if ('items' in row) {
    const validationError = getSubmitValidationError(row);
    if (validationError) {
      toast.warning(validationError);
      return;
    }
  }
  openConfirmDialog({
    title: `提交${pageText.value.formTitle}`,
    description: `${row.billNo} 提交后进入待确认状态，仓库确认前仍可由有权限人员调整本次数量和备注；提交本身不改变库存。`,
    confirmText: '确认提交',
    variant: 'warning',
    onConfirm: async () => {
      await submitStockBill(billDirection(row.billType), row.workBillId, row.version);
      toast.success(`${row.billNo} 已提交待确认`);
      detailVisible.value = false;
      await fetchRecords();
    },
  });
}

function handleCancel(row: StockBillListItem) {
  openConfirmDialog({
    title: `取消${pageText.value.formTitle}`,
    description: `${row.billNo} 取消后不改变库存余额，后续如需处理要重新创建单据。`,
    confirmText: '确认取消',
    variant: 'destructive',
    onConfirm: async () => {
      await cancelStockBill(billDirection(row.billType), row.workBillId, row.version);
      toast.success(`${row.billNo} 已取消`);
      await fetchRecords();
    },
  });
}

function getRowActions(row: StockBillListItem): RowActionOption[] {
  if (row.status !== 'DRAFT' && row.status !== 'PENDING_CONFIRM') return [];

  return [
    { key: 'edit', label: `编辑${pageText.value.formTitle}` },
    row.status === 'DRAFT'
      ? { key: 'submit', label: '提交确认' }
      : { key: 'confirm', label: isInboundPage.value ? '确认入库' : '确认出库' },
    { key: 'cancel', label: `取消${pageText.value.formTitle}`, variant: 'destructive', separated: true },
  ];
}

function handleRowAction(row: StockBillListItem, actionKey: string) {
  if (actionSubmitting.value) return;
  if (actionKey === 'edit') openEditDialog(row);
  if (actionKey === 'submit') openSubmitDetail(row);
  if (actionKey === 'confirm') openConfirmDetail(row);
  if (actionKey === 'cancel') handleCancel(row);
}

async function runConfirmAction() {
  if (actionSubmitting.value) return;
  actionSubmitting.value = true;
  try {
    await confirmState.onConfirm();
    confirmState.open = false;
  } catch (error) {
    toast.warning(getApiErrorMessage(error) || '操作失败');
  } finally {
    actionSubmitting.value = false;
  }
}

watch(pageDirection, () => {
  cancelColumnLayoutWork();
  pendingColumnReset = false;
  for (const column of stockBillOptionalColumns) delete pendingColumnVisibility[column.key];
  columnLayoutState.value = 'idle';
  columnLayoutAnnouncement.value = '';
  handleReset();
  form.billType = defaultBillType.value;
});

onMounted(async () => {
  await loadFormOptions();
  await fetchRecords();
});
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">{{ pageText.title }}</h1>
        <p class="page-description">{{ pageText.description }}</p>
      </div>
    </div>

    <ListSummaryStrip :items="summaryCards" :aria-label="`${pageText.title}数据汇总`" />

    <ListFilterPanel layout="content" :aria-label="`${pageText.title}筛选`">
        <div class="space-y-1" data-filter-size="standard">
          <Label>{{ pageText.billNoLabel }}</Label>
          <Input v-model="query.billNo" :placeholder="`如 ${isInboundPage ? 'IB202606140001' : 'OB202606140002'}`" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1" data-filter-size="standard">
          <Label>来源单号</Label>
          <Input v-model="query.sourceNo" :placeholder="pageText.sourceNoPlaceholder" @keyup.enter="handleSearch" />
        </div>
        <div class="space-y-1" data-filter-size="wide">
          <Label>仓库</Label>
          <RemoteSearchSelect v-model="query.warehouseId" :selected-label="selectedQueryWarehouseLabel" :fetch-options="fetchWarehouseSearchOptions" placeholder="全部仓库" search-placeholder="输入仓库编码或名称" clearable clear-value="all" clear-label="全部仓库" />
        </div>
        <div class="space-y-1" data-filter-size="compact">
          <Label>类型</Label>
          <AnchoredSelect v-model="query.billType" :options="pageBillTypeOptions" />
        </div>
        <div class="space-y-1" data-filter-size="compact">
          <Label>录入方式</Label>
          <AnchoredSelect v-model="query.entryMode" :options="entryModeOptions" />
        </div>
        <div class="space-y-1" data-filter-size="compact">
          <Label>状态</Label>
          <AnchoredSelect v-model="query.status" :options="statusOptions" />
        </div>
      <template #actions>
          <ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" />
      </template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <ListLoadingOverlay :visible="queryBusy && records.length > 0" label="正在刷新单据..." />
      <div class="table-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">{{ pageText.title }}列表</strong>
          <span class="text-xs text-muted-foreground">列表只显示本单作业数量；来源订单进度在详情和原单中查看</span>
        </div>
        <div class="table-toolbar__actions">
          <DropdownMenu>
            <DropdownMenuTrigger as-child>
              <Button
                size="sm"
                variant="outline"
                class="gap-2 border-slate-200 bg-white shadow-sm hover:border-primary/35 hover:bg-primary/[0.03]"
                :aria-label="`选择显示字段，当前 ${visibleOptionalCount}/${stockBillOptionalColumns.length}`"
                data-stock-bill-column-trigger
              >
                <Columns3 class="size-4 text-muted-foreground" />
                显示字段
                <span class="rounded-full bg-muted px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground">{{ visibleOptionalCount }}/{{ stockBillOptionalColumns.length }}</span>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent class="w-64 rounded-[10px] border-border/80 p-1.5 shadow-lg" align="end" :side-offset="6" data-stock-bill-column-menu>
              <DropdownMenuLabel class="px-2.5 py-2">
                <strong class="block text-sm font-semibold text-foreground">显示字段</strong>
                <small class="mt-0.5 block font-normal leading-4 text-muted-foreground">按需精简列表，偏好仅保存在当前浏览器</small>
              </DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuCheckboxItem
                v-for="column in stockBillOptionalColumns"
                :key="column.key"
                indicator-style="checkbox"
                class="min-h-9 text-sm"
                :model-value="isPendingListColumnVisible(column.key)"
                :data-stock-bill-column-key="column.key"
                @select.prevent
                @update:model-value="setListColumnVisible(column.key, Boolean($event))"
              >
                {{ column.key === 'party' ? pageText.partyColumnLabel : column.label }}
              </DropdownMenuCheckboxItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem class="min-h-9 gap-2 px-2 text-sm text-muted-foreground" data-stock-bill-column-reset @select="resetListColumns">
                <RotateCcw class="size-3.5" />
                恢复默认字段
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <Tooltip>
            <TooltipTrigger as-child>
              <span class="inline-flex"><Button size="sm" variant="outline" :disabled="queryBusy" @click="refreshList">刷新</Button></span>
            </TooltipTrigger>
            <TooltipContent>重新加载{{ pageText.title }}</TooltipContent>
          </Tooltip>
          <Button size="sm" @click="openCreateDialog">{{ pageText.createButton }}</Button>
        </div>
      </div>
      <p class="sr-only" aria-live="polite">{{ columnLayoutAnnouncement }}</p>
      <div
        ref="stockBillTableScroll"
        class="stock-bill-table-scroll w-full"
        :data-column-layout-state="columnLayoutState"
        :data-column-layout-scroll-left="preservedColumnScrollLeft"
      >
        <Table class="stock-bill-list-table table-fixed" :style="{ '--stock-bill-table-min-width': `${tableMinWidth}px` }" :scroll-label="`${pageText.title}列表`" data-stock-bill-list-table>
          <colgroup>
            <template v-for="column in stockBillListColumns" :key="column.key">
              <col v-if="isListColumnVisible(column.key)" :style="{ width: `${column.width}px` }" />
            </template>
          </colgroup>
          <TableHeader>
            <TableRow>
              <TableHead class="stock-bill-key-column sticky left-0 z-20 border-r border-border/60 bg-muted" data-table-sticky-edge="start">{{ pageText.billNoLabel }}</TableHead>
              <TableHead>类型</TableHead>
              <TableHead v-if="isListColumnVisible('entryMode')">录入方式</TableHead>
              <TableHead v-if="isListColumnVisible('sourceType')">来源类型</TableHead>
              <TableHead v-if="isListColumnVisible('sourceNo')">来源单号</TableHead>
              <TableHead v-if="isListColumnVisible('party')">{{ pageText.partyColumnLabel }}</TableHead>
              <TableHead v-if="isListColumnVisible('warehouse')">仓库</TableHead>
              <TableHead class="text-right">{{ pageText.listQtyLabel }}</TableHead>
              <TableHead class="text-center">状态</TableHead>
              <TableHead v-if="isListColumnVisible('responsible')">负责人</TableHead>
              <TableHead v-if="isListColumnVisible('createTime')">创建时间</TableHead>
              <TableHead class="stock-bill-actions-column sticky right-0 z-20 border-l border-border/60 bg-muted text-center" data-table-sticky-edge="end">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-if="loading && records.length === 0">
              <TableCell :colspan="visibleColumnCount" class="h-28 text-center text-muted-foreground">正在加载...</TableCell>
            </TableRow>
            <TableRow v-else-if="records.length === 0">
              <TableCell :colspan="visibleColumnCount" class="h-28 text-center text-muted-foreground">{{ pageText.emptyText }}</TableCell>
            </TableRow>
            <template v-else>
              <template v-for="row in records" :key="row.workBillId">
                <TableRow class="group bg-muted/25" :data-stock-bill-id="row.workBillId">
                  <TableCell class="stock-bill-key-column sticky left-0 z-20 border-r border-border/60 bg-background group-hover:bg-muted/50" data-table-sticky-edge="start">
                    <div class="flex items-center gap-2">
                      <Button size="sm" variant="ghost" class="h-7 shrink-0 px-2 text-xs text-primary hover:text-primary" :aria-expanded="!isRowDetailCollapsed(row)" :aria-controls="`stock-bill-detail-${row.workBillId}`" @click="toggleRowDetail(row)">{{ isRowDetailCollapsed(row) ? '展开明细' : '收起明细' }}</Button>
                      <code class="rounded bg-muted px-1.5 py-0.5 text-xs font-medium">{{ row.billNo }}</code>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" :class="billTypeMap[row.billType].className">{{ billTypeMap[row.billType].label }}</Badge>
                  </TableCell>
                  <TableCell v-if="isListColumnVisible('entryMode')" class="text-muted-foreground">{{ entryModeMap[row.entryMode] }}</TableCell>
                  <TableCell v-if="isListColumnVisible('sourceType')" class="text-muted-foreground">{{ sourceTypeMap[row.sourceType] }}</TableCell>
                  <TableCell v-if="isListColumnVisible('sourceNo')">
                    <code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ row.sourceNo || '-' }}</code>
                  </TableCell>
                  <TableCell v-if="isListColumnVisible('party')" class="truncate" :title="sourcePartyDisplay(row)">
                    <span class="text-sm">{{ sourcePartyDisplay(row) }}</span>
                  </TableCell>
                  <TableCell v-if="isListColumnVisible('warehouse')" class="truncate" :title="row.warehouseName">{{ row.warehouseName }}</TableCell>
                  <TableCell class="text-right font-medium tabular-nums">{{ billTotalQuantityText(row) }}</TableCell>
                  <TableCell class="text-center">
                    <Badge variant="outline" :class="statusMap[row.status].className">{{ statusMap[row.status].label }}</Badge>
                  </TableCell>
                  <TableCell v-if="isListColumnVisible('responsible')" class="truncate" :title="row.responsibleByName">{{ row.responsibleByName }}</TableCell>
                  <TableCell v-if="isListColumnVisible('createTime')" class="whitespace-nowrap text-muted-foreground">{{ row.createTime }}</TableCell>
                  <TableCell class="stock-bill-actions-column sticky right-0 z-20 whitespace-nowrap border-l border-border/60 bg-background text-center group-hover:bg-muted/50" data-table-sticky-edge="end">
                    <div class="inline-flex flex-nowrap items-center justify-center gap-1">
                      <Button size="sm" variant="ghost" class="text-cyan-700 hover:text-cyan-800" :disabled="actionSubmitting" @click="openDetail(row)">详情</Button>
                      <RowActionsMenu :actions="getRowActions(row)" :disabled="actionSubmitting" :label="`更多 ${row.billNo} 操作`" @select="handleRowAction(row, $event)" />
                    </div>
                  </TableCell>
                </TableRow>
                <TableRow class="stock-bill-detail-host-row bg-background" :data-stock-bill-detail-host-id="row.workBillId">
                  <TableCell :colspan="visibleColumnCount" class="h-0 px-4 py-0">
                    <CollapsibleRoot :open="!isRowDetailCollapsed(row)" :unmount-on-hide="false">
                      <CollapsibleContent :id="`stock-bill-detail-${row.workBillId}`" class="stock-bill-detail-drawer" :data-stock-bill-detail-id="row.workBillId">
                        <div class="stock-bill-detail-drawer__inner">
                          <div v-if="isRowDetailLoading(row)" class="stock-bill-detail-message text-muted-foreground" :data-stock-bill-detail-loading-id="row.workBillId"><span class="page-loading-spinner mr-2 !size-3.5" />商品明细加载中...</div>
                          <div v-else-if="detailLoadErrors[row.workBillId]" class="stock-bill-detail-message text-destructive" :data-stock-bill-detail-error-id="row.workBillId">{{ detailLoadErrors[row.workBillId] }}</div>
                          <div v-else-if="expandedItems(row).length === 0" class="stock-bill-detail-message text-muted-foreground" :data-stock-bill-detail-empty-id="row.workBillId">暂无商品明细</div>
                          <WarehouseDetailTableFrame v-else class="stock-bill-detail-card">
                              <Table class="min-w-[880px] table-fixed">
                                <colgroup>
                                  <col class="w-[104px]" />
                                  <col class="w-[160px]" />
                                  <col class="w-[56px]" />
                                  <col class="w-[104px]" />
                                  <col class="w-[96px]" />
                                  <col class="w-[104px]" />
                                  <col class="w-[116px]" />
                                  <col class="w-[140px]" />
                                </colgroup>
                                <TableHeader>
                                  <TableRow>
                                    <TableHead class="text-center">产品编码</TableHead>
                                    <TableHead class="text-center">产品名称</TableHead>
                                    <TableHead class="text-center">单位</TableHead>
                                    <TableHead class="text-center">{{ pageText.listQtyLabel }}</TableHead>
                                    <TableHead class="text-center">合格数量</TableHead>
                                    <TableHead class="text-center">不合格数量</TableHead>
                                    <TableHead class="text-center">{{ pageText.pendingQtyLabel }}</TableHead>
                                    <TableHead class="text-center">备注</TableHead>
                                  </TableRow>
                                </TableHeader>
                                <TableBody>
                                  <TableRow v-for="item in expandedItems(row)" :key="item.workBillItemId" :data-stock-bill-expanded-item-id="item.workBillItemId">
                                    <TableCell class="text-center"><code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code></TableCell>
                                    <TableCell class="truncate text-center font-medium" :title="item.productName">{{ item.productName }}</TableCell>
                                    <TableCell class="text-center text-muted-foreground">{{ item.unitName }}</TableCell>
                                    <TableCell class="text-center font-medium tabular-nums">{{ itemQuantityText(item) }}</TableCell>
                                    <TableCell class="text-center tabular-nums">{{ qualityQtyText(item, row.billType, 'qualifiedQty') }}</TableCell>
                                    <TableCell class="text-center tabular-nums" :class="item.defectiveQty > 0 && isQualityBillType(row.billType) ? 'font-medium text-rose-700' : 'text-muted-foreground'">{{ qualityQtyText(item, row.billType, 'defectiveQty') }}</TableCell>
                                    <TableCell class="text-center tabular-nums">{{ remainingQtyText(item) }}</TableCell>
                                    <TableCell class="text-center"><OverflowTooltip :text="item.remark" fallback="-" class="block text-muted-foreground" /></TableCell>
                                  </TableRow>
                                </TableBody>
                              </Table>
                          </WarehouseDetailTableFrame>
                        </div>
                      </CollapsibleContent>
                    </CollapsibleRoot>
                  </TableCell>
                </TableRow>
              </template>
            </template>
          </TableBody>
        </Table>
      </div>
      <DataTablePagination :total="total" :page-num="query.pageNum" :page-size="query.pageSize" :loading="queryBusy" @update:page-num="handlePageChange" @update:page-size="handlePageSizeChange" />
    </div>

    <Dialog v-model:open="formVisible">
      <DialogContent placement="app-content" :inert="confirmState.open ? '' : undefined" class="flex !h-[min(820px,calc(100dvh-var(--app-shell-header-height)-2rem))] !max-h-[calc(100dvh-var(--app-shell-header-height)-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden !bg-white shadow-2xl sm:max-w-[1120px]">
        <DialogHeader>
          <DialogTitle>{{ dialogMode === 'create' ? pageText.createButton : `编辑${pageText.formTitle}` }}</DialogTitle>
          <DialogDescription>{{ dialogMode === 'create' ? '手工补录或库存调整先保存为草稿；草稿提交后进入待确认，确认入库/出库时才更新库存。' : '草稿可继续保存或提交确认；待确认状态只允许调整本次数量、合格数量、不合格数量和备注。' }}</DialogDescription>
        </DialogHeader>
        <div v-if="formLoading" class="flex min-h-64 flex-1 items-center justify-center gap-2 text-muted-foreground"><span class="page-loading-spinner" />单据加载中...</div>
        <DialogScrollArea v-else>
          <div class="space-y-5 py-2">
            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1"><Label>{{ pageText.billNoLabel }}</Label><Input :model-value="dialogMode === 'create' ? '保存后由系统生成' : editingDetail?.billNo" readonly class="bg-muted/55 text-muted-foreground" /></div>
              <div class="space-y-1">
                <Label>类型 <span class="text-destructive">*</span></Label>
                <AnchoredSelect v-if="dialogMode === 'create'" v-model="form.billType" :options="manualBillTypeOptions" />
                <Input v-else :model-value="billTypeMap[formBillType].label" readonly class="bg-muted/55 text-muted-foreground" />
              </div>
              <div class="space-y-1">
                <Label>{{ warehouseFieldLabel }} <span class="text-destructive">*</span></Label>
                <RemoteSearchSelect v-if="warehouseEditable" v-model="form.warehouseId" :selected-label="selectedFormWarehouseLabel" :fetch-options="fetchFormWarehouseSearchOptions" placeholder="请选择仓库" search-placeholder="输入仓库编码或名称" :invalid="Boolean(formErrors.warehouseId)" />
                <Input v-else :model-value="editingDetail?.warehouseName" readonly class="bg-muted/55 text-muted-foreground" />
                <p v-if="formErrors.warehouseId" class="text-xs text-destructive">{{ formErrors.warehouseId }}</p>
              </div>
            </div>

            <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
              <div class="space-y-1">
                <Label>{{ isAdjustmentForm ? '调整单号' : '来源单号' }} <span v-if="isManualForm && !isAdjustmentForm" class="text-destructive">*</span></Label>
                <div v-if="sourceNoEditable && sourceNoSearchable" class="flex items-center gap-1.5">
                  <div class="min-w-0 flex-1">
                    <RemoteSearchSelect
                      v-if="sourceNoMode === 'search'"
                      :model-value="form.sourceNo"
                      :selected-label="selectedSourceOrderLabel"
                      :fetch-options="fetchSourceOrderSearchOptions"
                      placeholder="搜索已有单据"
                      search-placeholder="输入单号搜索"
                      :invalid="Boolean(formErrors.sourceNo)"
                      @update:model-value="handleSourceOrderSelect"
                    />
                    <Input v-else v-model="form.sourceNo" maxlength="64" placeholder="填写线下单据号" :aria-invalid="Boolean(formErrors.sourceNo)" @update:model-value="clearFormError('sourceNo')" />
                  </div>
                  <Button type="button" variant="outline" size="sm" class="shrink-0 text-xs" @click="toggleSourceNoMode">
                    {{ sourceNoMode === 'search' ? '手动输入' : '搜索选择' }}
                  </Button>
                </div>
                <Input v-else-if="sourceNoEditable" v-model="form.sourceNo" maxlength="64" placeholder="填写线下单据、送货单或退货单号" :aria-invalid="Boolean(formErrors.sourceNo)" @update:model-value="clearFormError('sourceNo')" />
                <Input v-else :model-value="dialogMode === 'create' ? '保存后由系统生成' : form.sourceNo" readonly class="bg-muted/55 text-muted-foreground" />
                <p v-if="formErrors.sourceNo" class="text-xs text-destructive">{{ formErrors.sourceNo }}</p>
              </div>
              <div class="space-y-1">
                <Label>{{ sourcePartyLabel(formBillType) }} <span v-if="sourcePartyEditable" class="text-destructive">*</span></Label>
                <RemoteSearchSelect
                  v-if="sourcePartyEditable"
                  :model-value="form.sourcePartyId"
                  :selected-label="selectedSourcePartyLabel"
                  :fetch-options="fetchSourcePartySearchOptions"
                  :placeholder="sourcePartyPlaceholder"
                  :search-placeholder="sourcePartySearchPlaceholder"
                  :invalid="Boolean(formErrors.sourcePartyId)"
                  @update:model-value="(val: string | number) => { form.sourcePartyId = String(val); clearFormError('sourcePartyId'); }"
                />
                <Input v-if="!sourcePartyEditable" :model-value="sourcePartyFormDisplay" readonly class="bg-muted/55 text-muted-foreground" />
                <p v-if="formErrors.sourcePartyId" class="text-xs text-destructive">{{ formErrors.sourcePartyId }}</p>
              </div>
              <div class="space-y-1"><Label>负责人</Label><Input :model-value="editingDetail?.responsibleByName || authStore.displayName" readonly class="bg-muted/55 text-muted-foreground" /><p class="text-xs text-muted-foreground">由后端按当前登录用户写入，不允许代填</p></div>
            </div>

            <div v-if="isManualForm" class="space-y-1">
              <Label>{{ isAdjustmentForm ? '调整原因' : '补录原因' }} <span class="text-destructive">*</span></Label>
              <Input v-if="manualReasonEditable" v-model="form.manualReason" maxlength="500" :placeholder="isAdjustmentForm ? '说明盘点差异或调整依据' : '说明未登记原业务单据的原因'" :aria-invalid="Boolean(formErrors.manualReason)" @update:model-value="clearFormError('manualReason')" />
              <Input v-else :model-value="form.manualReason || '-'" readonly class="bg-muted/55 text-muted-foreground" />
              <p v-if="formErrors.manualReason" class="text-xs text-destructive">{{ formErrors.manualReason }}</p>
            </div>

            <div>
              <div class="mb-2 flex items-center justify-between gap-3">
                <div><h3 class="text-sm font-semibold">产品明细 <span class="text-destructive">*</span></h3><p class="mt-1 text-xs text-muted-foreground">确认后才会更新库存；计划、累计和剩余数量由来源单据自动带入，手工新增草稿不填写这些字段。</p></div>
                <Button v-if="structureEditable" size="sm" variant="outline" @click="addFormItem">添加产品</Button>
              </div>
              <p v-if="formErrors.items" class="mb-2 text-xs text-destructive">{{ formErrors.items }}</p>
              <div class="stock-bill-form-table-scroll rounded-md border">
                <Table class="min-w-[980px] table-fixed">
                  <colgroup>
                    <col class="w-[220px]" />
                    <col class="w-[80px]" />
                    <col class="w-[80px]" />
                    <col class="w-[160px]" />
                    <col class="w-[140px]" />
                    <col class="w-[100px]" />
                    <col class="w-[100px]" />
                    <col class="w-[150px]" />
                  </colgroup>
                  <TableHeader>
                    <TableRow>
                      <TableHead>产品</TableHead>
                      <TableHead class="text-right">{{ planQtyLabel(formBillType) }}</TableHead>
                      <TableHead class="text-right">{{ pageText.processedLabel }}</TableHead>
                      <TableHead>{{ pageText.currentQtyLabel }}</TableHead>
                      <TableHead class="text-right">确认后{{ pageText.pendingQtyLabel }}</TableHead>
                      <TableHead>合格数量</TableHead>
                      <TableHead>不合格数量</TableHead>
                      <TableHead>明细备注</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    <TableRow v-for="(item, index) in form.items" :key="item.key">
                      <TableCell class="align-top">
                        <div class="flex items-start gap-1">
                          <div class="min-w-0 flex-1">
                            <RemoteSearchSelect v-if="structureEditable" v-model="item.productId" :selected-label="productLabel(item)" :fetch-options="fetchProductSearchOptions" placeholder="请选择产品" search-placeholder="输入产品编码或名称" @update:model-value="handleProductChange(item, index)" />
                            <div v-else class="stock-bill-product-snapshot" :title="productLabel(item)">
                              <code>{{ productDisplay(item).code }}</code>
                              <span>{{ productDisplay(item).name }}</span>
                              <small>{{ productDisplay(item).unitName }}</small>
                            </div>
                          </div>
                          <Button v-if="structureEditable" size="icon-xs" variant="ghost" class="mt-1 shrink-0 text-muted-foreground hover:text-destructive" :disabled="form.items.length <= 1" @click="removeFormItem(index)">
                            <X class="size-3.5" />
                          </Button>
                        </div>
                        <p v-if="formErrors[`items.${index}.productId`]" class="mt-1 text-xs text-destructive">{{ formErrors[`items.${index}.productId`] }}</p>
                      </TableCell>
                      <TableCell class="align-top text-right text-muted-foreground tabular-nums">{{ detailItemFor(item) ? `${formatQty(detailItemFor(item)?.planQty)} ${itemUnitName(item)}` : (item.planQty != null ? `${formatQty(item.planQty)} ${itemUnitName(item)}` : '-') }}</TableCell>
                      <TableCell class="align-top text-right text-muted-foreground tabular-nums">{{ detailItemFor(item) ? `${formatQty(detailItemFor(item)?.processedQty)} ${itemUnitName(item)}` : (item.processedQty != null ? `${formatQty(item.processedQty)} ${itemUnitName(item)}` : '-') }}</TableCell>
                      <TableCell class="align-top">
                        <div class="stock-bill-form-quantity-control"><Input v-model.number="item.currentQty" type="number" :min="itemQuantityStep(item)" :step="itemQuantityStep(item)" :aria-invalid="Boolean(formErrors[`items.${index}.quantity`])" @update:model-value="handleQuantityChange(item, index)" /><span :class="formErrors[`items.${index}.quantity`] ? 'text-destructive' : 'text-muted-foreground'" :title="formErrors[`items.${index}.quantity`] || quantityHint(item)">{{ formErrors[`items.${index}.quantity`] || quantityHint(item) }}</span></div>
                      </TableCell>
                      <TableCell class="align-top text-right text-muted-foreground tabular-nums">{{ detailItemFor(item) ? `${remainingAfterText(item)} ${itemUnitName(item)}` : (itemPendingQty(item) != null ? `${formatQty(itemPendingQty(item))} ${itemUnitName(item)}` : '-') }}</TableCell>
                      <TableCell class="align-top">
                        <Input v-if="qualityFieldsVisible" v-model.number="item.qualifiedQty" type="number" min="0" :step="itemQuantityStep(item)" />
                        <span v-else class="text-muted-foreground">-</span>
                      </TableCell>
                      <TableCell class="align-top">
                        <Input v-if="qualityFieldsVisible" v-model.number="item.defectiveQty" type="number" min="0" :step="itemQuantityStep(item)" />
                        <span v-else class="text-muted-foreground">-</span>
                        <p v-if="formErrors[`items.${index}.quality`]" class="mt-1 text-xs text-destructive">{{ formErrors[`items.${index}.quality`] }}</p>
                      </TableCell>
                      <TableCell class="align-top">
                        <Input v-model="item.remark" maxlength="500" placeholder="可填写差异原因" />
                        <p v-if="formErrors[`items.${index}.remark`]" class="mt-1 text-xs text-destructive">{{ formErrors[`items.${index}.remark`] }}</p>
                      </TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </div>
            </div>

            <div class="space-y-1"><Label>备注</Label><Textarea v-model="form.remark" maxlength="500" rows="3" placeholder="填写调整依据、验收说明或其他备注" :aria-invalid="Boolean(formErrors.remark)" /><div class="flex justify-between text-xs"><span :class="formErrors.remark ? 'text-destructive' : 'text-muted-foreground'">{{ formErrors.remark || '选填，最多 500 个字符' }}</span><span class="text-muted-foreground">{{ form.remark.length }}/500</span></div></div>
          </div>
        </DialogScrollArea>
        <DialogFooter><Button variant="outline" :disabled="formSubmitting" @click="formVisible = false">关闭</Button><Button :disabled="formSubmitting || formLoading" @click="submitForm">{{ formSubmitting ? '保存中...' : dialogMode === 'create' ? '保存草稿' : '保存修改' }}</Button></DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="detailVisible">
      <DialogContent placement="app-content" class="flex !h-[min(780px,calc(100dvh-var(--app-shell-header-height)-2rem))] !max-h-[calc(100dvh-var(--app-shell-header-height)-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden !bg-white shadow-2xl sm:max-w-[1180px]">
        <DialogHeader><DialogTitle>{{ pageText.formTitle }}详情</DialogTitle><DialogDescription>查看业务来源、往来对象、确认信息、本次数量以及本次处理后来源订单的剩余数量。</DialogDescription></DialogHeader>
        <div v-if="detailLoading" class="flex min-h-64 flex-1 items-center justify-center gap-2 text-muted-foreground"><span class="page-loading-spinner" />详情加载中...</div>
        <DialogScrollArea v-else-if="detail">
          <div class="space-y-5 py-1">
            <div class="detail-field-grid grid grid-cols-3 gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="detail-field"><span>{{ pageText.billNoLabel }}</span><code>{{ detail.billNo }}</code></div>
              <div class="detail-field"><span>类型</span><Badge variant="outline" :class="billTypeMap[detail.billType].className">{{ billTypeMap[detail.billType].label }}</Badge></div>
              <div class="detail-field"><span>状态</span><Badge variant="outline" :class="statusMap[detail.status].className">{{ statusMap[detail.status].label }}</Badge></div>
              <div class="detail-field"><span>仓库</span><strong>{{ detail.warehouseName }}</strong></div>
              <div class="detail-field"><span>录入方式</span><strong>{{ entryModeMap[detail.entryMode] }}</strong></div>
              <div class="detail-field"><span>来源类型</span><strong>{{ sourceTypeMap[detail.sourceType] }}</strong></div>
              <div class="detail-field"><span>来源单号</span><code>{{ detail.sourceNo || '-' }}</code></div>
              <div class="detail-field"><span>{{ sourcePartyLabel(detail.billType) }}</span><strong>{{ sourcePartyDisplay(detail) }}</strong></div>
              <div class="detail-field"><span>负责人</span><strong>{{ detail.responsibleByName }}</strong></div>
              <div class="detail-field"><span>创建人 / 时间</span><strong>{{ detail.createdByName || '系统' }}</strong><small>{{ detail.createTime }}</small></div>
              <div class="detail-field"><span>确认人 / 时间</span><strong>{{ detail.confirmedByName || '未确认' }}</strong><small>{{ detail.confirmedAt || '-' }}</small></div>
              <div class="detail-field"><span>本次数量</span><strong>{{ detail.quantitySummary }}</strong><small>来源订单整体进度可在采购单或销售单查看</small></div>
            </div>
            <div>
              <div class="mb-2 flex items-center justify-between"><h3 class="text-sm font-semibold">产品明细</h3><span class="text-xs text-muted-foreground">共 {{ detail.items.length }} 条</span></div>
              <div class="detail-table-floating">
                <div class="stock-bill-dialog-table-scroll w-full">
                  <Table class="min-w-[1210px] table-fixed">
                    <colgroup><col class="w-[190px]" /><col class="w-[65px]" /><col class="w-[95px]" /><col class="w-[105px]" /><col class="w-[120px]" /><col class="w-[155px]" /><col class="w-[95px]" /><col class="w-[105px]" /><col class="w-[90px]" /><col class="w-[90px]" /><col class="w-[100px]" /></colgroup>
                    <TableHeader><TableRow><TableHead>产品</TableHead><TableHead class="text-center">单位</TableHead><TableHead class="text-right">{{ planQtyLabel(detail.billType) }}</TableHead><TableHead class="text-right">{{ pageText.processedLabel }}</TableHead><TableHead class="text-right">{{ pageText.currentQtyLabel }}</TableHead><TableHead class="text-right">确认后{{ pageText.pendingQtyLabel }}</TableHead><TableHead class="text-right">合格数量</TableHead><TableHead class="text-right">不合格数量</TableHead><TableHead class="text-right">变动前</TableHead><TableHead class="text-right">变动后</TableHead><TableHead>备注</TableHead></TableRow></TableHeader>
                    <TableBody>
                      <TableRow v-for="item in detail.items" :key="item.workBillItemId" :data-stock-bill-item-id="item.workBillItemId">
                        <TableCell><div class="flex flex-col gap-1"><code class="w-fit rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code><span class="font-medium">{{ item.productName }}</span></div></TableCell>
                        <TableCell class="text-center">{{ item.unitName }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.planQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.processedQty) }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.currentQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.pendingQty) }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ qualityQtyText(item, detail.billType, 'qualifiedQty') }}</TableCell>
                        <TableCell class="text-right tabular-nums" :class="item.defectiveQty > 0 && isQualityBillType(detail.billType) ? 'font-medium text-rose-700' : 'text-muted-foreground'">{{ qualityQtyText(item, detail.billType, 'defectiveQty') }}</TableCell>
                        <TableCell class="text-right tabular-nums">{{ formatQty(item.beforeQty) }}</TableCell>
                        <TableCell class="text-right font-medium tabular-nums">{{ formatQty(item.afterQty) }}</TableCell>
                        <TableCell><OverflowTooltip :text="item.remark" fallback="-" class="block text-xs text-muted-foreground" /></TableCell>
                      </TableRow>
                    </TableBody>
                  </Table>
                </div>
              </div>
            </div>
            <div v-if="detail.entryMode !== 'SOURCE_GENERATED'" class="rounded-lg border border-amber-200 bg-amber-50/60 p-3"><span class="text-xs text-amber-700">{{ detail.entryMode === 'MANUAL_SUPPLEMENT' ? '补录原因' : '调整原因' }}</span><p class="mt-1 text-sm">{{ detail.manualReason }}</p></div>
            <div class="rounded-lg border bg-muted/25 p-3"><span class="text-xs text-muted-foreground">备注</span><p class="mt-1 text-sm">{{ detail.remark || '无' }}</p></div>
          </div>
        </DialogScrollArea>
        <DialogFooter v-if="detail && (detail.status === 'DRAFT' || detail.status === 'PENDING_CONFIRM')" class="items-center justify-between gap-3">
          <span v-if="detailActionMode !== 'view'" class="mr-auto text-xs text-muted-foreground">请先核对完整单头和产品明细，再执行{{ detail.status === 'DRAFT' ? '提交' : '确认' }}。</span>
          <Button variant="outline" :disabled="actionSubmitting" @click="detailVisible = false">关闭</Button>
          <Button v-if="detail.status === 'DRAFT'" :disabled="actionSubmitting" @click="handleSubmit(detail)">{{ actionSubmitting ? '处理中...' : '提交确认' }}</Button>
          <Button v-else :disabled="actionSubmitting" @click="handleConfirm(detail)">{{ actionSubmitting ? '处理中...' : isInboundPage ? '确认入库' : '确认出库' }}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <ConfirmDialog :open="confirmState.open" :title="confirmState.title" :description="confirmState.description" :confirm-text="confirmState.confirmText" :variant="confirmState.variant" :loading="actionSubmitting" @update:open="confirmState.open = $event" @confirm="runConfirmAction" />
  </section>
</template>

<style scoped>
.stock-bill-table-scroll :deep(.stock-bill-list-table) {
  min-width: var(--stock-bill-table-min-width);
  transform-origin: top center;
  transition:
    opacity 140ms ease,
    transform 140ms ease;
}

.stock-bill-table-scroll[data-column-layout-state='leaving'] :deep(.stock-bill-list-table) {
  opacity: 0.46;
  transform: translateY(2px);
}

.stock-bill-table-scroll[data-column-layout-state='entering'] :deep(.stock-bill-list-table) {
  opacity: 1;
  transform: none;
}

.stock-bill-table-scroll {
  border-bottom: 1px solid var(--border);
}

@media (prefers-reduced-motion: reduce) {
  .stock-bill-table-scroll :deep(.stock-bill-list-table) {
    transition: none;
  }

  .stock-bill-table-scroll[data-column-layout-state] :deep(.stock-bill-list-table) {
    opacity: 1;
    transform: none;
  }
}

.stock-bill-table-scroll > :deep([data-slot="table-container"]) {
  min-height: min(52vh, 430px);
  max-height: min(74vh, 760px);
  overflow: auto;
  padding-bottom: 10px;
}

.stock-bill-table-scroll :deep([data-slot="table-head"].stock-bill-key-column),
.stock-bill-table-scroll :deep([data-slot="table-head"].stock-bill-actions-column) {
  /* 必须低于 Dialog（50），否则底层固定表头会覆盖新增/编辑弹窗。 */
  z-index: 40;
  background-color: var(--muted);
}

.stock-bill-table-scroll :deep([data-slot="table-cell"].stock-bill-key-column),
.stock-bill-table-scroll :deep([data-slot="table-cell"].stock-bill-actions-column) {
  z-index: 25;
  background-color: var(--background);
}

.stock-bill-detail-row-scroll :deep([data-slot="table-container"]) {
  max-height: min(32vh, 300px);
  overflow: auto;
  padding-bottom: 10px;
}

.stock-bill-detail-card {
  width: min(100%, 900px);
  border-left: 3px solid var(--primary);
  background: color-mix(in srgb, var(--muted) 36%, white);
}

.stock-bill-detail-drawer {
  overflow: hidden;
  overflow: clip;
  inline-size: 100%;
  min-inline-size: 0;
  max-inline-size: 100%;
  contain: inline-size paint;
  will-change: height, opacity;
}

.stock-bill-detail-host-row :deep([data-slot='table-cell']) {
  height: 0;
}

.stock-bill-detail-drawer[data-state="open"] {
  animation: stock-bill-collapsible-down var(--motion-duration-base) var(--motion-ease-standard);
}

.stock-bill-detail-drawer[data-state="closed"] {
  animation: stock-bill-collapsible-up var(--motion-duration-fast) var(--motion-ease-exit);
}

.stock-bill-detail-drawer__inner {
  min-height: 0;
  padding-block: 12px;
}

.stock-bill-detail-message {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: center;
}

@keyframes stock-bill-collapsible-down {
  from {
    height: 0;
    opacity: 0;
  }

  to {
    height: var(--reka-collapsible-content-height);
    opacity: 1;
  }
}

@keyframes stock-bill-collapsible-up {
  from {
    height: var(--reka-collapsible-content-height);
    opacity: 1;
  }

  to {
    height: 0;
    opacity: 0;
  }
}

.stock-bill-detail-row-scroll :deep([data-slot="table"]) {
  font-size: 12px;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-head"]),
.stock-bill-detail-row-scroll :deep([data-slot="table-cell"]) {
  font-size: 12px;
  height: 38px;
  padding: 6px 10px;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-head"]) {
  font-weight: 500;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-head"]),
.stock-bill-detail-row-scroll :deep([data-slot="table-cell"]),
.stock-bill-dialog-table-scroll :deep([data-slot="table-head"]),
.stock-bill-dialog-table-scroll :deep([data-slot="table-cell"]) {
  height: 40px;
  padding: 6px 8px;
  font-size: 12px;
  text-align: center !important;
  vertical-align: middle !important;
}

.stock-bill-form-table-scroll :deep([data-slot="table-head"]),
.stock-bill-form-table-scroll :deep([data-slot="table-cell"]) {
  height: 40px;
  padding: 6px 8px;
  font-size: 14px;
  text-align: center !important;
  vertical-align: middle !important;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-head"]),
.stock-bill-detail-row-scroll :deep([data-slot="table-cell"]),
.stock-bill-detail-row-scroll :deep([data-slot="table-cell"] .text-center),
.stock-bill-detail-row-scroll :deep([data-slot="table-cell"] .text-right) {
  text-align: left !important;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-cell"] > *),
.stock-bill-dialog-table-scroll :deep([data-slot="table-cell"] > *) {
  margin-right: auto;
  margin-left: auto;
}

.stock-bill-detail-row-scroll :deep([data-slot="table-cell"] > *) {
  margin-right: 0;
  margin-left: 0;
}

.stock-bill-form-table-scroll :deep(input) {
  height: 32px;
  text-align: center;
}

.stock-bill-form-quantity-control {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stock-bill-form-quantity-control :deep(input) {
  min-width: 0;
  flex: 1;
}

.stock-bill-form-quantity-control span {
  max-width: 72px;
  overflow: hidden;
  font-size: 14px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stock-bill-form-table-scroll :deep([role="combobox"]) {
  min-height: 32px;
  font-size: 14px;
}

.stock-bill-form-table-scroll :deep([data-slot="table-cell"] .text-left),
.stock-bill-form-table-scroll :deep([data-slot="table-cell"] .text-right) {
  text-align: center !important;
}

.stock-bill-product-snapshot {
  display: flex;
  min-height: 60px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 2px);
  background: var(--background);
  padding: 8px 10px;
  text-align: center;
}

.stock-bill-product-snapshot code {
  width: fit-content;
  border-radius: calc(var(--radius) - 4px);
  background: var(--muted);
  padding: 2px 6px;
  font-size: 12px;
}

.stock-bill-product-snapshot span {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 500;
}

.stock-bill-product-snapshot small {
  font-size: 12px;
  color: var(--muted-foreground);
}

.stock-bill-dialog-table-scroll {
  border-bottom: 1px solid var(--border);
}

.stock-bill-dialog-table-scroll :deep([data-slot="table-container"]) {
  max-height: min(42vh, 420px);
  overflow: auto;
  padding-bottom: 10px;
}

.stock-bill-form-table-scroll :deep([data-slot="table-container"]) {
  max-height: min(44vh, 460px);
  overflow: auto;
  padding-bottom: 10px;
}

:global(.stock-bill-table-scroll thead),
:global(.stock-bill-table-scroll thead tr),
:global(.stock-bill-detail-row-scroll thead),
:global(.stock-bill-detail-row-scroll thead tr),
:global(.stock-bill-dialog-table-scroll thead),
:global(.stock-bill-dialog-table-scroll thead tr),
:global(.stock-bill-form-table-scroll thead),
:global(.stock-bill-form-table-scroll thead tr) {
  background-color: var(--muted);
}

:global(.stock-bill-table-scroll [data-slot="table-head"]),
:global(.stock-bill-detail-row-scroll [data-slot="table-head"]),
:global(.stock-bill-dialog-table-scroll [data-slot="table-head"]),
:global(.stock-bill-form-table-scroll [data-slot="table-head"]) {
  position: sticky;
  top: 0;
  z-index: 30;
  background-color: var(--muted);
  background-image: none;
  background-clip: padding-box;
  box-shadow: inset 0 -1px var(--border);
}

:global(.stock-bill-table-scroll [data-slot="table-head"]) {
  /* 普通表头必须低于左右固定表头，否则横向滚动时会遮住单号标题。 */
  z-index: 30;
}

:global(.stock-bill-detail-row-scroll [data-slot="table-head"]),
:global(.stock-bill-dialog-table-scroll [data-slot="table-head"]),
:global(.stock-bill-form-table-scroll [data-slot="table-head"]) {
  z-index: 20;
}

:global(.stock-bill-detail-row-scroll thead),
:global(.stock-bill-detail-row-scroll thead tr),
:global(.stock-bill-detail-row-scroll [data-slot="table-head"]) {
  border-bottom-color: color-mix(in srgb, var(--primary) 24%, var(--border));
  background-color: color-mix(in srgb, var(--primary) 9%, white);
}

:global(.stock-bill-detail-row-scroll [data-slot="table-head"]) {
  color: color-mix(in srgb, var(--primary) 78%, var(--foreground));
  font-weight: 600 !important;
}

.stock-bill-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar),
.stock-bill-detail-row-scroll :deep([data-slot="table-container"]::-webkit-scrollbar),
.stock-bill-dialog-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar),
.stock-bill-form-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar) {
  width: 10px;
  height: 10px;
}

.stock-bill-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-track),
.stock-bill-detail-row-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-track),
.stock-bill-dialog-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-track),
.stock-bill-form-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-track) {
  border-radius: 999px;
  background: var(--muted);
}

.stock-bill-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-thumb),
.stock-bill-detail-row-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-thumb),
.stock-bill-dialog-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-thumb),
.stock-bill-form-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-thumb) {
  border: 2px solid var(--muted);
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted-foreground) 45%, transparent);
}

.detail-field {
  display: flex;
  min-height: 92px;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  background: color-mix(in srgb, var(--muted) 34%, transparent);
  padding: 14px;
}

.detail-field > span {
  font-size: 12px;
  color: var(--muted-foreground);
}

.detail-field > strong,
.detail-field > code {
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.45;
}

.detail-field > small {
  line-height: 1.4;
  color: var(--muted-foreground);
}

.draft-item-grid {
  display: grid;
  grid-template-columns: minmax(240px, 1.7fr) minmax(130px, 0.8fr) minmax(220px, 1.4fr) auto;
  gap: 12px;
  align-items: start;
}

.draft-item-grid--quality {
  grid-template-columns: minmax(220px, 1.6fr) repeat(3, minmax(110px, 0.75fr)) minmax(180px, 1.1fr) auto;
}

.draft-item-grid--source {
  grid-template-columns: minmax(220px, 1.5fr) repeat(4, minmax(110px, 0.7fr)) minmax(180px, 1.1fr);
}

.draft-item-grid--source.draft-item-grid--quality {
  grid-template-columns: minmax(210px, 1.4fr) repeat(7, minmax(96px, 0.62fr)) minmax(160px, 1fr);
}

@media (max-width: 1279px) {
  .draft-item-grid,
  .draft-item-grid--quality,
  .draft-item-grid--source,
  .draft-item-grid--source.draft-item-grid--quality {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .draft-remark {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .draft-item-grid,
  .draft-item-grid--quality,
  .draft-item-grid--source,
  .draft-item-grid--source.draft-item-grid--quality {
    grid-template-columns: minmax(0, 1fr);
  }

  .draft-remark {
    grid-column: auto;
  }
}
</style>
