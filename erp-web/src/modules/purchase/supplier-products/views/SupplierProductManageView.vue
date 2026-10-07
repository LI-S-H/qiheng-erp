<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from "vue";
import { useDebounceFn } from "@vueuse/core";
import { toast } from "vue-sonner";
import { getApiErrorMessage } from "@/api/http";
import AnchoredSelect from "@/components/common/AnchoredSelect.vue";
import BusinessDetailHero from "@/components/common/BusinessDetailHero.vue";
import BusinessDetailWorkbenchCard from "@/components/common/BusinessDetailWorkbenchCard.vue";
import ConfirmDialog from "@/components/common/ConfirmDialog.vue";
import DataTablePagination from "@/components/common/DataTablePagination.vue";
import ListFilterActions from "@/components/common/ListFilterActions.vue";
import ListFilterPanel from "@/components/common/ListFilterPanel.vue";
import ListSummaryStrip from "@/components/common/ListSummaryStrip.vue";
import OverflowTooltip from "@/components/common/OverflowTooltip.vue";
import RemoteSearchSelect from "@/components/common/RemoteSearchSelect.vue";
import TableColumnPreferencesCanvas from "@/components/common/TableColumnPreferencesCanvas.vue";
import RowActionsMenu, {
  type RowActionOption,
} from "@/components/common/RowActionsMenu.vue";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogScrollArea,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useListRefresh } from "@/shared/composables/use-list-refresh";
import {
  useTableColumnPreferences,
  type TableColumnPreferenceDefinition,
} from "@/shared/composables/use-table-column-preferences";
import {
  batchDeleteSupplierProducts,
  batchUpdateSupplierProductStatus,
  createSupplierProduct,
  deleteSupplierProduct,
  getSupplierProductDetail,
  listEnabledProductOptions,
  listProductFilterOptions,
  listSupplierProducts,
  searchSupplierFilterOptions,
  searchSupplierOptions,
  updateSupplierProduct,
  updateSupplierProductQuote,
} from "../../api";
import type {
  SupplierProductBatchIdsPayload,
  SupplierProductBatchStatusPayload,
  SupplierProductCreatePayload,
  SupplierProductListItem,
  SupplierProductQuery,
  SupplierProductQuotePayload,
  SupplierProductUpdatePayload,
} from "../../types";
import SupplierScoreChangeLogDialog from "../../components/SupplierScoreChangeLogDialog.vue";

const columns: readonly TableColumnPreferenceDefinition[] = [
  { key: "supplier", label: "供应商", width: 170, pinAllowed: true },
  { key: "product", label: "产品", width: 180, pinAllowed: true },
  { key: "quote", label: "当前报价", width: 125, pinAllowed: true },
  { key: "minOrderQty", label: "最小起订量", width: 110, pinAllowed: true },
  { key: "avgDeliveryDays", label: "平均到货周期", width: 125, pinAllowed: true },
  { key: "qualityScore", label: "质量分", width: 90, pinAllowed: true },
  { key: "priceScore", label: "价格分", width: 90, pinAllowed: true },
  { key: "recommendScore", label: "推荐分", width: 90, pinAllowed: true },
  { key: "scoreBasisAmount", label: "样本金额", width: 120, pinAllowed: true },
  { key: "scoreStatus", label: "评分状态", width: 110, pinAllowed: true },
  { key: "status", label: "状态", width: 80, pinAllowed: true },
  { key: "actions", label: "操作", width: 112, required: true },
];
const {
  isPinnedLeft,
  isVisible,
  reset: resetColumns,
  setPinnedLeft,
  setVisible,
  tableMinWidth,
} = useTableColumnPreferences(
  "erp.purchase.supplier-products.table-columns.v2",
  columns,
);
const configurableColumns = columns.filter((column) => !column.required);
const columnSettingsOpen = ref(false);
const tablePanelRef = ref<HTMLElement | null>(null);
const pinnedColumnOffsets = ref<Record<string, number>>({});
let columnResizeObserver: ResizeObserver | undefined;
const columnSettings = computed(() =>
  configurableColumns.map((column) => ({
    ...column,
    visible: isVisible(column.key),
    pinned: isPinnedLeft(column.key),
  })),
);
const rows = ref<SupplierProductListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const saving = ref(false);
const editVisible = ref(false);
const quoteVisible = ref(false);
const scoreLogVisible = ref(false);
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailRow = ref<SupplierProductListItem | null>(null);
const editing = ref<SupplierProductListItem | null>(null);
const quoteTarget = ref<SupplierProductListItem | null>(null);
const scoreLogTarget = ref<SupplierProductListItem | null>(null);
const selectedIds = ref<Set<string>>(new Set());
const actionSubmitting = ref(false);
const confirmState = reactive({
  open: false,
  title: "",
  description: "",
  confirmText: "确认",
  variant: "warning" as "default" | "warning" | "destructive",
  onConfirm: (() => {}) as () => void | Promise<void>,
});
const query = reactive<SupplierProductQuery>({
  supplierId: "all" as string | "all",
  productId: "all" as string | "all",
  supplierName: "",
  productCode: "",
  productName: "",
  status: "all" as "" | "all" | 0 | 1,
  scoreStatus: "all" as "" | "all" | "NOT_READY" | "READY",
  quoteStatus: "all" as "" | "all" | "NONE" | "VALID" | "EXPIRED",
  quoteValidUntilEnd: "",
  qualityScoreMin: null as number | null,
  qualityScoreMax: null as number | null,
  priceScoreMin: null as number | null,
  priceScoreMax: null as number | null,
  aiScoreMin: null as number | null,
  aiScoreMax: null as number | null,
  scoreBasisAmountMin: null as number | null,
  scoreBasisAmountMax: null as number | null,
  minOrderQtyMin: null as number | null,
  minOrderQtyMax: null as number | null,
  pageNum: 1,
  pageSize: 10,
});
const form = reactive({
  supplierId: "",
  productId: "",
  quotedPurchasePrice: null as number | null,
  quoteValidUntil: "",
  quoteReason: "",
  minOrderQty: 1,
  status: 1 as 0 | 1,
  remark: "",
});
const quoteForm = reactive({
  quotedPurchasePrice: null as number | null,
  quoteValidUntil: "",
  reason: "",
});
const queryBusy = computed(() => loading.value || queryPending.value);
const quoteStatusOptions = [
  { value: "all", label: "全部报价状态" },
  { value: "VALID", label: "有效报价" },
  { value: "EXPIRED", label: "已过期" },
  { value: "NONE", label: "未报价" },
];
const scoreStatusOptions = [
  { value: "all", label: "全部评分状态" },
  { value: "READY", label: "已就绪" },
  { value: "NOT_READY", label: "暂无样本" },
];
const statusOptions = [
  { value: "all", label: "全部状态" },
  { value: 1, label: "启用" },
  { value: 0, label: "停用" },
];
const formStatusOptions = [
  { value: 1, label: "启用" },
  { value: 0, label: "停用" },
];
const allSelected = computed(
  () =>
    rows.value.length > 0 &&
    rows.value.every((row) => selectedIds.value.has(row.supplierProductId)),
);
const readyCount = computed(
  () => rows.value.filter((item) => item.scoreStatus === "READY").length,
);
const activeCount = computed(
  () => rows.value.filter((item) => item.status === 1).length,
);
const quotedCount = computed(
  () => rows.value.filter((item) => item.quotedPurchasePrice != null).length,
);
const noQuoteCount = computed(
  () => rows.value.filter((item) => item.quotedPurchasePrice == null).length,
);
const summaryItems = computed(() => [
  {
    key: "active",
    label: "本页启用关系",
    value: activeCount.value,
    tone: "positive" as const,
  },
  { key: "quote", label: "本页已有报价", value: quotedCount.value },
  {
    key: "ready",
    label: "本页评分已就绪",
    value: readyCount.value,
    tone: "positive" as const,
  },
  {
    key: "no-quote",
    label: "本页待维护报价",
    value: noQuoteCount.value,
    tone: "warning" as const,
  },
]);
const formatScore = (value: number | null) =>
  value == null ? "—" : value.toFixed(2);
const formatMoney = (value: number | null) =>
  value == null ? "—" : `￥${value.toFixed(2)}`;
const formatDays = (value: number | null) =>
  value == null ? "暂无完整入库样本" : `${value.toFixed(2)} 天`;
function pinnedStyle(key: string) {
  const offset = pinnedColumnOffsets.value[key];
  return isPinnedLeft(key) && offset != null ? { left: `${offset}px` } : undefined;
}
function columnClass(key: string) {
  return isPinnedLeft(key) ? "supplier-product-table__pinned-left" : "";
}
function pinnedGroupEdge(key: string) {
  const visibleColumns = configurableColumns.filter((column) =>
    isVisible(column.key),
  );
  const index = visibleColumns.findIndex((column) => column.key === key);
  if (index < 0 || !isPinnedLeft(key)) return undefined;

  const previousPinned = isPinnedLeft(visibleColumns[index - 1]?.key ?? "");
  const nextPinned = isPinnedLeft(visibleColumns[index + 1]?.key ?? "");
  if (!previousPinned && !nextPinned) return "group-both";
  if (!previousPinned) return "group-start";
  if (!nextPinned) return "group-end";
  return undefined;
}
function updatePinnedColumnOffsets() {
  const table = tablePanelRef.value?.querySelector("table");
  if (!table) return;

  const widths = Array.from(table.querySelectorAll("col"), (column) =>
    column.getBoundingClientRect().width,
  );
  if (widths.length === 0) return;

  const offsets: Record<string, number> = {};
  let offset = widths[0] ?? 0;
  let columnIndex = 1;
  for (const column of columns) {
    if (!isVisible(column.key)) continue;
    offsets[column.key] = offset;
    offset += widths[columnIndex] ?? column.width;
    columnIndex += 1;
  }
  pinnedColumnOffsets.value = offsets;
}
function updateColumnVisibility(key: string, visible: boolean) {
  setVisible(key, visible);
  void nextTick(updatePinnedColumnOffsets);
}
function updateColumnPinning(key: string, pinned: boolean) {
  setPinnedLeft(key, pinned);
  void nextTick(updatePinnedColumnOffsets);
}
function resetColumnSettings() {
  resetColumns();
  void nextTick(updatePinnedColumnOffsets);
}
async function load() {
  loading.value = true;
  try {
    const page = await listSupplierProducts({ ...query });
    rows.value = page.records;
    total.value = page.total;
    selectedIds.value = new Set();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "供货关系加载失败");
  } finally {
    loading.value = false;
    queryPending.value = false;
  }
}
const debouncedLoad = useDebounceFn(load, 250);
// 重置与查询共用同一个防抖请求，保留清晰的语义入口供列表约定和后续维护使用。
const debouncedSearch = debouncedLoad;
function validateQueryRange(
  min: number | null | undefined,
  max: number | null | undefined,
  label: string,
  maximum?: number,
  requireCents = false,
) {
  for (const value of [min, max]) {
    if (value == null) continue;
    if (!Number.isFinite(value) || value < 0) return `${label}必须为非负数`;
    if (maximum != null && value > maximum) return `${label}不能超过${maximum}`;
    if (requireCents && !Number.isInteger(value * 100)) return `${label}最多两位小数`;
  }
  return min != null && max != null && min > max ? `${label}区间不合法` : "";
}
function validateQuery() {
  return (
    validateQueryRange(query.qualityScoreMin, query.qualityScoreMax, "质量分", 100, true)
    || validateQueryRange(query.priceScoreMin, query.priceScoreMax, "价格分", 100, true)
    || validateQueryRange(query.aiScoreMin, query.aiScoreMax, "推荐分", 100, true)
    || validateQueryRange(query.scoreBasisAmountMin, query.scoreBasisAmountMax, "评分样本金额", undefined, true)
    || validateQueryRange(query.minOrderQtyMin, query.minOrderQtyMax, "最小起订量")
  );
}
function handleSearch() {
  if (!queryBusy.value) {
    const error = validateQuery();
    if (error) return toast.warning(error);
    query.pageNum = 1;
    queryPending.value = true;
    debouncedLoad();
  }
}
function handleReset() {
  if (!queryBusy.value) {
    Object.assign(query, {
      supplierId: "all",
      productId: "all",
      supplierName: "",
      productCode: "",
      productName: "",
      status: "all",
      scoreStatus: "all",
      quoteStatus: "all",
      quoteValidUntilEnd: "",
      qualityScoreMin: null,
      qualityScoreMax: null,
      priceScoreMin: null,
      priceScoreMax: null,
      aiScoreMin: null,
      aiScoreMax: null,
      scoreBasisAmountMin: null,
      scoreBasisAmountMax: null,
      minOrderQtyMin: null,
      minOrderQtyMax: null,
      pageNum: 1,
    });
    queryPending.value = true;
    debouncedSearch();
  }
}
function handlePageChange(page: number) {
  if (!queryBusy.value && page !== query.pageNum) {
    query.pageNum = page;
    queryPending.value = true;
    debouncedLoad();
  }
}
function handlePageSizeChange(size: number) {
  if (!queryBusy.value && size !== query.pageSize) {
    query.pageSize = size;
    query.pageNum = 1;
    queryPending.value = true;
    debouncedLoad();
  }
}
const refreshList = useListRefresh(queryBusy, queryPending, load);
function resetForm() {
  Object.assign(form, {
    supplierId: "",
    productId: "",
    quotedPurchasePrice: null,
    quoteValidUntil: "",
    quoteReason: "",
    minOrderQty: 1,
    status: 1,
    remark: "",
  });
}
function openCreate() {
  editing.value = null;
  resetForm();
  editVisible.value = true;
}
function openEdit(row: SupplierProductListItem) {
  editing.value = row;
  Object.assign(form, {
    supplierId: row.supplierId,
    productId: row.productId,
    quotedPurchasePrice: null,
    quoteValidUntil: "",
    quoteReason: "",
    minOrderQty: row.minOrderQty,
    status: row.status,
    remark: row.remark,
  });
  editVisible.value = true;
}
function quoteComplete() {
  return (
    form.quotedPurchasePrice != null ||
    Boolean(form.quoteValidUntil) ||
    Boolean(form.quoteReason.trim())
  );
}
function validate() {
  if (form.minOrderQty <= 0)
    return "请完整填写供货关系条件";
  if (!editing.value && (!form.supplierId || !form.productId))
    return "请选择供应商和产品";
  if (
    quoteComplete() &&
    (!form.quotedPurchasePrice ||
      form.quotedPurchasePrice <= 0 ||
      !form.quoteValidUntil ||
      !form.quoteReason.trim() ||
      !Number.isInteger(form.quotedPurchasePrice * 100))
  )
    return "初始报价、有效期和原因必须同时填写，报价最多两位小数";
  return "";
}
async function save() {
  const error = validate();
  if (error) return toast.warning(error);
  saving.value = true;
  try {
    if (editing.value) {
      const payload: SupplierProductUpdatePayload = {
        version: editing.value.version,
        minOrderQty: form.minOrderQty,
        status: form.status,
        remark: form.remark.trim(),
      };
      await updateSupplierProduct(editing.value.supplierProductId, payload);
    } else {
      const payload: SupplierProductCreatePayload = {
        supplierId: form.supplierId,
        productId: form.productId,
        quotedPurchasePrice: form.quotedPurchasePrice,
        quoteValidUntil:
          form.quotedPurchasePrice == null ? null : form.quoteValidUntil,
        quoteReason:
          form.quotedPurchasePrice == null ? null : form.quoteReason.trim(),
        minOrderQty: form.minOrderQty,
        status: form.status,
        remark: form.remark.trim(),
      };
      await createSupplierProduct(payload);
    }
    toast.success("保存成功");
    editVisible.value = false;
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "保存失败");
  } finally {
    saving.value = false;
  }
}
function openQuote(row: SupplierProductListItem) {
  quoteTarget.value = row;
  Object.assign(quoteForm, {
    quotedPurchasePrice: row.quotedPurchasePrice,
    quoteValidUntil: row.quoteValidUntil || "",
    reason: "",
  });
  quoteVisible.value = true;
}
async function saveQuote() {
  const target = quoteTarget.value;
  if (!target || !quoteForm.reason.trim())
    return toast.warning("请填写本次报价调整原因");
  const price = quoteForm.quotedPurchasePrice;
  const clearing = price == null;
  if (
    !clearing &&
    (!quoteForm.quoteValidUntil || price <= 0 || !Number.isInteger(price * 100))
  )
    return toast.warning("报价必须大于零、最多两位小数并填写有效期");
  saving.value = true;
  try {
    const payload: SupplierProductQuotePayload = {
      version: target.version,
      quotedPurchasePrice: price,
      quoteValidUntil: clearing ? null : quoteForm.quoteValidUntil,
      reason: quoteForm.reason.trim(),
    };
    await updateSupplierProductQuote(target.supplierProductId, payload);
    toast.success("报价已更新");
    quoteVisible.value = false;
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "报价调整失败");
  } finally {
    saving.value = false;
  }
}
function selectedPayload(): SupplierProductBatchIdsPayload {
  const supplierProductIds = [...selectedIds.value];
  return {
    supplierProductIds,
    versionBySupplierProductId: Object.fromEntries(
      rows.value
        .filter((row) => selectedIds.value.has(row.supplierProductId))
        .map((row) => [row.supplierProductId, row.version]),
    ),
  };
}
function toggleSelect(id: string, selected: boolean | "indeterminate") {
  const next = new Set(selectedIds.value);
  selected === true ? next.add(id) : next.delete(id);
  selectedIds.value = next;
}
function toggleSelectAll(selected: boolean | "indeterminate") {
  selectedIds.value =
    selected === true
      ? new Set(rows.value.map((row) => row.supplierProductId))
      : new Set();
}
function openConfirm(
  options: Omit<typeof confirmState, "open" | "onConfirm"> & {
    onConfirm: () => void | Promise<void>;
  },
) {
  Object.assign(confirmState, options, { open: true });
}
async function runBatchStatus(status: 0 | 1) {
  const payload: SupplierProductBatchStatusPayload = {
    ...selectedPayload(),
    status,
  };
  if (!payload.supplierProductIds.length) return;
  actionSubmitting.value = true;
  try {
    await batchUpdateSupplierProductStatus(payload);
    toast.success(`已批量${status === 1 ? "启用" : "停用"}`);
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "批量调整状态失败");
  } finally {
    actionSubmitting.value = false;
    confirmState.open = false;
  }
}
async function runBatchDelete() {
  const payload = selectedPayload();
  if (!payload.supplierProductIds.length) return;
  actionSubmitting.value = true;
  try {
    await batchDeleteSupplierProducts(payload);
    toast.success("已批量删除");
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "批量删除失败");
  } finally {
    actionSubmitting.value = false;
    confirmState.open = false;
  }
}
async function toggleStatus(row: SupplierProductListItem) {
  actionSubmitting.value = true;
  try {
    await batchUpdateSupplierProductStatus({
      supplierProductIds: [row.supplierProductId],
      versionBySupplierProductId: { [row.supplierProductId]: row.version },
      status: row.status === 1 ? 0 : 1,
    });
    toast.success(row.status === 1 ? "已停用" : "已启用");
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "状态调整失败");
  } finally {
    actionSubmitting.value = false;
    confirmState.open = false;
  }
}
function remove(row: SupplierProductListItem) {
  openConfirm({
    title: "删除供货关系",
    description: `确认删除「${row.supplierName} / ${row.productName}」？`,
    confirmText: "删除",
    variant: "destructive",
    onConfirm: async () => {
      actionSubmitting.value = true;
      try {
        await deleteSupplierProduct(row.supplierProductId, row.version);
        toast.success("已删除");
        await load();
      } catch (error) {
        toast.error(getApiErrorMessage(error) || "删除失败");
      } finally {
        actionSubmitting.value = false;
        confirmState.open = false;
      }
    },
  });
}
async function supplierOptions(keyword: string) {
  return (await searchSupplierOptions(keyword)).map((item) => ({
    value: item.supplierId,
    label: `${item.supplierCode} ${item.supplierName}`,
  }));
}
async function filterSupplierOptions(keyword: string) {
  return (await searchSupplierFilterOptions(keyword)).map((item) => ({
    value: item.supplierId,
    label: `${item.supplierCode} ${item.supplierName}`,
  }));
}
async function productOptions(keyword: string) {
  return (await listEnabledProductOptions(keyword)).map((item) => ({
    value: item.value,
    label: item.label,
    product: item.product,
  }));
}
function filterProductOptions(keyword: string) {
  return listProductFilterOptions(keyword);
}
function selectProduct(option: { value: string | number }) {
  form.productId = String(option.value);
}
function rowActions(row: SupplierProductListItem): RowActionOption[] {
  return [
    { key: "edit", label: "编辑" },
    { key: "quote", label: "调整报价" },
    { key: "score-change-logs", label: "评分变更记录" },
    { key: "status", label: row.status === 1 ? "停用" : "启用" },
    { key: "delete", label: "删除", variant: "destructive", separated: true },
  ];
}
async function openDetail(row: SupplierProductListItem) {
  if (detailLoading.value) return;
  detailVisible.value = true;
  detailLoading.value = true;
  detailRow.value = null;
  try {
    detailRow.value = await getSupplierProductDetail(row.supplierProductId);
  } catch (error) {
    detailVisible.value = false;
    toast.warning(getApiErrorMessage(error) || "供货关系详情加载失败");
  } finally {
    detailLoading.value = false;
  }
}
function handleRowAction(row: SupplierProductListItem, action: string) {
  if (action === "detail") {
    void openDetail(row);
  } else if (action === "edit") openEdit(row);
  else if (action === "quote") openQuote(row);
  else if (action === "score-change-logs") {
    scoreLogTarget.value = row;
    scoreLogVisible.value = true;
  }
  else if (action === "status")
    openConfirm({
      title: `${row.status === 1 ? "停用" : "启用"}供货关系`,
      description: `确认${row.status === 1 ? "停用" : "启用"}「${row.supplierName} / ${row.productName}」？`,
      confirmText: row.status === 1 ? "停用" : "启用",
      variant: "warning",
      onConfirm: () => toggleStatus(row),
    });
  else if (action === "delete") remove(row);
}
onMounted(() => {
  void load();
  void nextTick(() => {
    updatePinnedColumnOffsets();
    const table = tablePanelRef.value?.querySelector("table");
    if (!table) return;
    columnResizeObserver = new ResizeObserver(updatePinnedColumnOffsets);
    columnResizeObserver.observe(table);
  });
});
onBeforeUnmount(() => columnResizeObserver?.disconnect());
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">供货产品</h1>
        <p class="page-description">
          维护供应商产品关系、有效报价和产品级评分状态
        </p>
      </div>
    </div>
    <ListSummaryStrip :items="summaryItems" aria-label="供货产品数据汇总" />
    <ListFilterPanel layout="content" actions-position="bottom" aria-label="供货产品筛选">
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">供应商</Label>
        <RemoteSearchSelect
          v-model="query.supplierId"
          :fetch-options="filterSupplierOptions"
          placeholder="全部供应商"
          search-placeholder="输入供应商编码或名称"
          clearable
          clear-value="all"
          clear-label="全部供应商"
        />
      </div>
      <div class="space-y-1" data-filter-size="standard">
        <Label class="text-xs">供应商名称</Label
        ><Input
          v-model="query.supplierName"
          placeholder="请输入供应商名称"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">产品</Label>
        <RemoteSearchSelect
          v-model="query.productId"
          :fetch-options="filterProductOptions"
          placeholder="全部产品"
          search-placeholder="输入产品编码或名称"
          clearable
          clear-value="all"
          clear-label="全部产品"
        />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">产品编码</Label
        ><Input
          v-model="query.productCode"
          placeholder="请输入产品编码"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="space-y-1" data-filter-size="standard">
        <Label class="text-xs">产品名称</Label
        ><Input
          v-model="query.productName"
          placeholder="请输入产品名称"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">报价状态</Label
        ><AnchoredSelect v-model="query.quoteStatus" :options="quoteStatusOptions" />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">评分状态</Label
        ><AnchoredSelect v-model="query.scoreStatus" :options="scoreStatusOptions" />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">状态</Label
        ><AnchoredSelect v-model="query.status" :options="statusOptions" />
      </div>
      <div class="space-y-1" data-filter-size="standard">
        <Label class="text-xs">报价有效截止日</Label>
        <Input v-model="query.quoteValidUntilEnd" type="date" aria-label="报价有效截止日" />
      </div>
      <template #footer>
        <div class="grid gap-x-4 gap-y-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="供货产品高级范围筛选">
          <div class="space-y-1">
            <Label class="text-xs">质量分范围</Label>
            <div class="supplier-product-filter-range">
              <Input :model-value="query.qualityScoreMin ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最低分" aria-label="质量分最低值" @update:model-value="value => query.qualityScoreMin = value === '' ? null : Number(value)" />
              <span class="text-muted-foreground">—</span>
              <Input :model-value="query.qualityScoreMax ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最高分" aria-label="质量分最高值" @update:model-value="value => query.qualityScoreMax = value === '' ? null : Number(value)" />
            </div>
          </div>
          <div class="space-y-1">
            <Label class="text-xs">价格分范围</Label>
            <div class="supplier-product-filter-range">
              <Input :model-value="query.priceScoreMin ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最低分" aria-label="价格分最低值" @update:model-value="value => query.priceScoreMin = value === '' ? null : Number(value)" />
              <span class="text-muted-foreground">—</span>
              <Input :model-value="query.priceScoreMax ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最高分" aria-label="价格分最高值" @update:model-value="value => query.priceScoreMax = value === '' ? null : Number(value)" />
            </div>
          </div>
          <div class="space-y-1">
            <Label class="text-xs">推荐分范围</Label>
            <div class="supplier-product-filter-range">
              <Input :model-value="query.aiScoreMin ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最低分" aria-label="推荐分最低值" @update:model-value="value => query.aiScoreMin = value === '' ? null : Number(value)" />
              <span class="text-muted-foreground">—</span>
              <Input :model-value="query.aiScoreMax ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最高分" aria-label="推荐分最高值" @update:model-value="value => query.aiScoreMax = value === '' ? null : Number(value)" />
            </div>
          </div>
          <div class="space-y-1">
            <Label class="text-xs">评分样本金额范围</Label>
            <div class="supplier-product-filter-range">
              <Input :model-value="query.scoreBasisAmountMin ?? ''" type="number" min="0" step="0.01" placeholder="最低金额" aria-label="评分样本金额最低值" @update:model-value="value => query.scoreBasisAmountMin = value === '' ? null : Number(value)" />
              <span class="text-muted-foreground">—</span>
              <Input :model-value="query.scoreBasisAmountMax ?? ''" type="number" min="0" step="0.01" placeholder="最高金额" aria-label="评分样本金额最高值" @update:model-value="value => query.scoreBasisAmountMax = value === '' ? null : Number(value)" />
            </div>
          </div>
          <div class="space-y-1">
            <Label class="text-xs">最小起订量范围</Label>
            <div class="supplier-product-filter-range">
              <Input :model-value="query.minOrderQtyMin ?? ''" type="number" min="0" step="0.01" placeholder="最小值" aria-label="最小起订量最低值" @update:model-value="value => query.minOrderQtyMin = value === '' ? null : Number(value)" />
              <span class="text-muted-foreground">—</span>
              <Input :model-value="query.minOrderQtyMax ?? ''" type="number" min="0" step="0.01" placeholder="最大值" aria-label="最小起订量最高值" @update:model-value="value => query.minOrderQtyMax = value === '' ? null : Number(value)" />
            </div>
          </div>
        </div>
      </template>
      <template #actions><ListFilterActions :busy="queryBusy" @query="handleSearch" @reset="handleReset" /></template>
    </ListFilterPanel>
    <div ref="tablePanelRef" class="data-panel relative">
      <div class="table-toolbar supplier-product-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">供货关系列表</strong>
          <span class="text-xs text-muted-foreground"
            >维护供应商报价、交期与评分样本；勾选记录后可批量启用、停用或删除</span
          >
        </div>
        <div class="supplier-product-toolbar__right">
        <div class="table-toolbar__actions supplier-product-toolbar__main">
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button size="sm" @click="openCreate"
                  >新增供货关系</Button
                ></span
              ></TooltipTrigger
            ><TooltipContent>创建供应商产品关系</TooltipContent></Tooltip
          >
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button
                  size="sm"
                  variant="outline"
                  :disabled="!selectedIds.size || actionSubmitting"
                  @click="
                    openConfirm({
                      title: '批量启用供货关系',
                      description: `确认启用已选 ${selectedIds.size} 项供货关系？`,
                      confirmText: '批量启用',
                      variant: 'warning',
                      onConfirm: () => runBatchStatus(1),
                    })
                  "
                  >批量启用</Button
                ></span
              ></TooltipTrigger
            ><TooltipContent>{{
              selectedIds.size
                ? `启用已选 ${selectedIds.size} 项`
                : "请先选择供货关系"
            }}</TooltipContent></Tooltip
          >
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button
                  size="sm"
                  variant="outline"
                  :disabled="!selectedIds.size || actionSubmitting"
                  @click="
                    openConfirm({
                      title: '批量停用供货关系',
                      description: `确认停用已选 ${selectedIds.size} 项供货关系？`,
                      confirmText: '批量停用',
                      variant: 'warning',
                      onConfirm: () => runBatchStatus(0),
                    })
                  "
                  >批量停用</Button
                ></span
              ></TooltipTrigger
            ><TooltipContent>{{
              selectedIds.size
                ? `停用已选 ${selectedIds.size} 项`
                : "请先选择供货关系"
            }}</TooltipContent></Tooltip
          >
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button
                  size="sm"
                  variant="destructive"
                  :disabled="!selectedIds.size || actionSubmitting"
                  @click="
                    openConfirm({
                      title: '批量删除供货关系',
                      description: `确认删除已选 ${selectedIds.size} 项供货关系？已被采购单引用的数据不会被删除。`,
                      confirmText: '批量删除',
                      variant: 'destructive',
                      onConfirm: runBatchDelete,
                    })
                  "
                  >删除</Button
                ></span
              ></TooltipTrigger
            ><TooltipContent>{{
              selectedIds.size
                ? `删除已选 ${selectedIds.size} 项`
                : "请先选择供货关系"
            }}</TooltipContent></Tooltip
          >
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button
                  size="sm"
                  variant="outline"
                  :disabled="queryBusy"
                  @click="refreshList"
                  >刷新</Button
                ></span
              ></TooltipTrigger
            ><TooltipContent>重新加载供货产品列表</TooltipContent></Tooltip
          >
        </div>
        <div class="table-toolbar__actions supplier-product-toolbar__settings">
          <TableColumnPreferencesCanvas
            v-model:open="columnSettingsOpen"
            :columns="columnSettings"
            pinning-enabled
            @toggle-visible="updateColumnVisibility"
            @toggle-pinned="updateColumnPinning"
            @reset="resetColumnSettings"
            ><template #trigger
              ><Button size="sm" variant="outline" class="gap-2"
                >列设置</Button
              ></template
            ></TableColumnPreferencesCanvas
          >
        </div>
        </div>
      </div>
      <Table
        class="supplier-product-table table-fixed"
        :style="{ width: `${tableMinWidth}px`, minWidth: `${tableMinWidth}px` }"
        scroll-label="供货产品列表"
        ><colgroup>
          <col class="w-[44px]" />
          <template v-for="column in columns" :key="column.key"
            ><col
              v-if="isVisible(column.key)"
              :style="{ width: `${column.width}px` }"
          /></template>
        </colgroup>
        <TableHeader
          ><TableRow
            ><TableHead
              ><Checkbox
                :model-value="allSelected"
                aria-label="全选供货关系"
                @update:model-value="toggleSelectAll" /></TableHead
            ><TableHead
              v-if="isVisible('supplier')"
              :class="columnClass('supplier')"
              :style="pinnedStyle('supplier')"
              :data-table-sticky-edge="pinnedGroupEdge('supplier')"
              >供应商</TableHead
            ><TableHead
              v-if="isVisible('product')"
              :class="columnClass('product')"
              :style="pinnedStyle('product')"
              :data-table-sticky-edge="pinnedGroupEdge('product')"
              >产品</TableHead
            ><TableHead
              v-if="isVisible('quote')"
              :class="columnClass('quote')"
              :style="pinnedStyle('quote')"
              :data-table-sticky-edge="pinnedGroupEdge('quote')"
              >当前报价</TableHead
            ><TableHead
              v-if="isVisible('minOrderQty')"
              :class="columnClass('minOrderQty')"
              :style="pinnedStyle('minOrderQty')"
              :data-table-sticky-edge="pinnedGroupEdge('minOrderQty')"
              >最小起订量</TableHead
            ><TableHead
              v-if="isVisible('avgDeliveryDays')"
              :class="columnClass('avgDeliveryDays')"
              :style="pinnedStyle('avgDeliveryDays')"
              :data-table-sticky-edge="pinnedGroupEdge('avgDeliveryDays')"
              >平均到货周期</TableHead
            ><TableHead
              v-if="isVisible('qualityScore')"
              :class="columnClass('qualityScore')"
              :style="pinnedStyle('qualityScore')"
              :data-table-sticky-edge="pinnedGroupEdge('qualityScore')"
              >质量分</TableHead
            ><TableHead
              v-if="isVisible('priceScore')"
              :class="columnClass('priceScore')"
              :style="pinnedStyle('priceScore')"
              :data-table-sticky-edge="pinnedGroupEdge('priceScore')"
              >价格分</TableHead
            ><TableHead
              v-if="isVisible('recommendScore')"
              :class="columnClass('recommendScore')"
              :style="pinnedStyle('recommendScore')"
              :data-table-sticky-edge="pinnedGroupEdge('recommendScore')"
              >推荐分</TableHead
            ><TableHead
              v-if="isVisible('scoreBasisAmount')"
              :class="columnClass('scoreBasisAmount')"
              :style="pinnedStyle('scoreBasisAmount')"
              :data-table-sticky-edge="pinnedGroupEdge('scoreBasisAmount')"
              >样本金额</TableHead
            ><TableHead
              v-if="isVisible('scoreStatus')"
              :class="columnClass('scoreStatus')"
              :style="pinnedStyle('scoreStatus')"
              :data-table-sticky-edge="pinnedGroupEdge('scoreStatus')"
              >评分状态</TableHead
            ><TableHead
              v-if="isVisible('status')"
              :class="columnClass('status')"
              :style="pinnedStyle('status')"
              :data-table-sticky-edge="pinnedGroupEdge('status')"
              >状态</TableHead
            ><TableHead
              class="supplier-product-table__actions sticky right-0 z-20 border-l border-border/60 bg-muted text-center"
              data-table-sticky-edge="end"
              >操作</TableHead
            ></TableRow
          ></TableHeader
        ><TableBody
          ><TableRow v-if="loading"
            ><TableCell
              :colspan="
                columns.filter((column) => isVisible(column.key)).length + 1
              "
              class="h-28 text-center text-muted-foreground"
              >加载中…</TableCell
            ></TableRow
          ><TableRow v-else-if="!rows.length"
            ><TableCell
              :colspan="
                columns.filter((column) => isVisible(column.key)).length + 1
              "
              class="h-28 text-center text-muted-foreground"
              >暂无数据</TableCell
            ></TableRow
          ><TableRow
            v-for="row in rows"
            v-else
            :key="row.supplierProductId"
            class="group"
            ><TableCell
              ><Checkbox
                :model-value="selectedIds.has(row.supplierProductId)"
                :aria-label="`选择供货关系：${row.supplierName} ${row.productName}`"
                @update:model-value="
                  toggleSelect(row.supplierProductId, $event)
                " /></TableCell
            ><TableCell
              v-if="isVisible('supplier')"
              :class="columnClass('supplier')"
              :style="pinnedStyle('supplier')"
              :data-table-sticky-edge="pinnedGroupEdge('supplier')"
              ><div class="min-w-0">
                <p class="truncate font-medium" :title="row.supplierName">
                  {{ row.supplierName }}
                </p>
                <code class="text-xs text-muted-foreground">{{
                  row.supplierCode
                }}</code>
              </div></TableCell
            ><TableCell
              v-if="isVisible('product')"
              :class="columnClass('product')"
              :style="pinnedStyle('product')"
              :data-table-sticky-edge="pinnedGroupEdge('product')"
              ><div class="min-w-0">
                <p class="truncate font-medium" :title="row.productName">
                  {{ row.productName }}
                </p>
                <code class="text-xs text-muted-foreground">{{
                  row.productCode
                }}</code>
              </div></TableCell
            ><TableCell
              v-if="isVisible('quote')"
              :class="columnClass('quote')"
              :style="pinnedStyle('quote')"
              :data-table-sticky-edge="pinnedGroupEdge('quote')"
              ><div>
                <p>{{ formatMoney(row.quotedPurchasePrice) }}</p>
                <small class="text-muted-foreground">{{
                  row.quoteValidUntil || "未报价"
                }}</small>
              </div></TableCell
            ><TableCell
              v-if="isVisible('minOrderQty')"
              :class="columnClass('minOrderQty')"
              :style="pinnedStyle('minOrderQty')"
              :data-table-sticky-edge="pinnedGroupEdge('minOrderQty')"
              >{{ row.minOrderQty }} {{ row.unitName }}</TableCell
            ><TableCell
              v-if="isVisible('avgDeliveryDays')"
              :class="columnClass('avgDeliveryDays')"
              :style="pinnedStyle('avgDeliveryDays')"
              :data-table-sticky-edge="pinnedGroupEdge('avgDeliveryDays')"
              >{{ formatDays(row.avgDeliveryDays) }}</TableCell
            ><TableCell
              v-if="isVisible('qualityScore')"
              :class="columnClass('qualityScore')"
              :style="pinnedStyle('qualityScore')"
              :data-table-sticky-edge="pinnedGroupEdge('qualityScore')"
              >{{ formatScore(row.qualityScore) }}</TableCell
            ><TableCell
              v-if="isVisible('priceScore')"
              :class="columnClass('priceScore')"
              :style="pinnedStyle('priceScore')"
              :data-table-sticky-edge="pinnedGroupEdge('priceScore')"
              >{{ formatScore(row.priceScore) }}</TableCell
            ><TableCell
              v-if="isVisible('recommendScore')"
              :class="columnClass('recommendScore')"
              :style="pinnedStyle('recommendScore')"
              :data-table-sticky-edge="pinnedGroupEdge('recommendScore')"
              ><strong>{{ formatScore(row.aiScore) }}</strong></TableCell
            ><TableCell
              v-if="isVisible('scoreBasisAmount')"
              :class="columnClass('scoreBasisAmount')"
              :style="pinnedStyle('scoreBasisAmount')"
              :data-table-sticky-edge="pinnedGroupEdge('scoreBasisAmount')"
              >{{ formatMoney(row.scoreBasisAmount) }}</TableCell
            ><TableCell
              v-if="isVisible('scoreStatus')"
              :class="columnClass('scoreStatus')"
              :style="pinnedStyle('scoreStatus')"
              :data-table-sticky-edge="pinnedGroupEdge('scoreStatus')"
              ><Badge
                variant="outline"
                :class="
                  row.scoreStatus === 'READY'
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'border-slate-200 bg-slate-100 text-slate-500'
                "
                >{{
                  row.scoreStatus === "READY" ? "已就绪" : "暂无样本"
                }}</Badge
              ></TableCell
            ><TableCell
              v-if="isVisible('status')"
              :class="columnClass('status')"
              :style="pinnedStyle('status')"
              :data-table-sticky-edge="pinnedGroupEdge('status')"
              ><Badge
                variant="outline"
                :class="
                  row.status === 1
                    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'border-slate-200 bg-slate-100 text-slate-500'
                "
                >{{ row.status === 1 ? "启用" : "停用" }}</Badge
              ></TableCell
            ><TableCell
              class="supplier-product-table__actions sticky right-0 z-20 whitespace-nowrap border-l border-border/60 bg-background text-center group-hover:bg-muted/50"
              data-table-sticky-edge="end"
              ><div class="flex items-center justify-center gap-1">
                <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button
                  size="sm"
                  variant="ghost"
                  class="h-8 px-2.5 font-medium text-indigo-600 hover:bg-indigo-50 hover:text-indigo-700"
                  @click="handleRowAction(row, 'detail')"
                  >查看</Button
                ></span></TooltipTrigger><TooltipContent>查看供货条件、报价、成交与评分信息</TooltipContent></Tooltip><RowActionsMenu
                  :actions="rowActions(row)"
                  :label="`更多操作：${row.productName}`"
                  trigger-text="更多"
                  @select="handleRowAction(row, $event)"
                /></div></TableCell></TableRow></TableBody></Table
      ><DataTablePagination
        :total="total"
        :page-num="query.pageNum"
        :page-size="query.pageSize"
        :loading="queryBusy"
        @update:page-num="handlePageChange"
        @update:page-size="handlePageSizeChange"
      />
    </div>
  </section>
  <SupplierScoreChangeLogDialog
    v-model:open="scoreLogVisible"
    :supplier-id="scoreLogTarget?.supplierId"
    :supplier-name="scoreLogTarget?.supplierName"
    :supplier-product-id="scoreLogTarget?.supplierProductId"
    :product-name="scoreLogTarget?.productName"
  />
  <Dialog v-model:open="editVisible"
    ><DialogContent
      placement="app-content"
      class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-2xl"
      ><DialogHeader
        ><DialogTitle>{{
          editing ? "编辑供货关系" : "新增供货关系"
        }}</DialogTitle
        ><DialogDescription
          >报价与基础条件分开维护，自动评分不可手工填写。</DialogDescription
        ></DialogHeader
      ><DialogScrollArea class="max-h-[65vh]"
        ><div class="grid gap-3 p-1 md:grid-cols-2">
          <template v-if="!editing"
            ><div>
              <Label>供应商 <span class="text-destructive">*</span></Label
              ><RemoteSearchSelect
                :model-value="form.supplierId"
                placeholder="搜索供应商"
                :fetch-options="supplierOptions"
                @update:model-value="
                  (value) => (form.supplierId = String(value))
                "
              />
            </div>
            <div>
              <Label>产品 <span class="text-destructive">*</span></Label
              ><RemoteSearchSelect
                :model-value="form.productId"
                placeholder="搜索产品"
                :fetch-options="productOptions"
                @select="selectProduct"
              />
            </div>
          </template>
          <div>
            <Label>最小起订量 <span class="text-destructive">*</span></Label
            ><Input
              v-model.number="form.minOrderQty"
              type="number"
              min="0"
              step="0.01"
            />
          </div>
          <template v-if="!editing"
            ><div>
              <Label>初始报价（可选）</Label
              ><Input
                :model-value="form.quotedPurchasePrice ?? ''"
                type="number"
                min="0"
                step="0.01"
                @update:model-value="
                  (value) =>
                    (form.quotedPurchasePrice =
                      value === '' ? null : Number(value))
                "
              />
            </div>
            <div>
              <Label>报价有效截止日</Label
              ><Input v-model="form.quoteValidUntil" type="date" />
            </div>
            <div class="md:col-span-2">
              <Label>初始报价原因</Label
              ><Input v-model="form.quoteReason" /></div
          ></template>
          <div>
            <Label>状态 <span class="text-destructive">*</span></Label
            ><AnchoredSelect
              v-model="form.status"
              :options="formStatusOptions"
            />
          </div>
          <div class="md:col-span-2">
            <Label>备注</Label
            ><Textarea
              v-model="form.remark"
              maxlength="500"
              placeholder="选填，补充供货条件或合作说明"
            />
          </div></div></DialogScrollArea
      ><DialogFooter
        ><Button variant="outline" @click="editVisible = false">取消</Button
        ><Button :disabled="saving" @click="save">保存</Button></DialogFooter
      ></DialogContent
    ></Dialog
  >
  <Dialog v-model:open="quoteVisible"
    ><DialogContent placement="app-content"
      ><DialogHeader
        ><DialogTitle>调整报价</DialogTitle
        ><DialogDescription
          >清空报价时保留本次操作原因，但当前报价原因会清空。价格分重算将在后续评分任务接入。</DialogDescription
        ></DialogHeader
      >
      <div class="space-y-3">
        <div>
          <Label>报价（留空表示清空）</Label
          ><Input
            :model-value="quoteForm.quotedPurchasePrice ?? ''"
            type="number"
            min="0"
            step="0.01"
            @update:model-value="
              (value) =>
                (quoteForm.quotedPurchasePrice =
                  value === '' ? null : Number(value))
            "
          />
        </div>
        <div>
          <Label>有效截止日</Label
          ><Input
            v-model="quoteForm.quoteValidUntil"
            type="date"
            :disabled="quoteForm.quotedPurchasePrice == null"
          />
        </div>
        <div>
          <Label>本次调整原因</Label><Textarea v-model="quoteForm.reason" />
        </div>
      </div>
      <DialogFooter
        ><Button variant="outline" @click="quoteVisible = false">取消</Button
        ><Button :disabled="saving" @click="saveQuote"
          >确认调整</Button
        ></DialogFooter
      ></DialogContent
    ></Dialog
  >
  <Dialog v-model:open="detailVisible">
    <DialogContent placement="app-content" data-supplier-product-detail-workbench class="supplier-product-detail-workbench flex !h-[min(780px,calc(100dvh-var(--app-shell-header-height)-2rem))] !max-h-[calc(100dvh-var(--app-shell-header-height)-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden !bg-[#f6f8fb] !p-4 sm:max-w-5xl">
      <DialogHeader class="sr-only"><DialogTitle>供货关系详情</DialogTitle><DialogDescription>供货条件、报价、成交与产品级评分。</DialogDescription></DialogHeader>
      <DialogScrollArea content-class="px-5 py-5 pr-6">
        <div v-if="detailLoading" class="flex min-h-64 items-center justify-center gap-2 text-sm text-muted-foreground"><span class="page-loading-spinner" />详情加载中…</div>
        <div v-else-if="detailRow" class="space-y-5">
          <BusinessDetailHero eyebrow="供货关系" :title="detailRow.productName" :subtitle="detailRow.productCode" :status-label="detailRow.status === 1 ? '启用' : '停用'" :status-class="detailRow.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-600'" :metric-columns="3" variant="canvas">
            <template #metrics>
              <div class="business-detail-hero__metric"><span>当前报价</span><strong>{{ formatMoney(detailRow.quotedPurchasePrice) }}</strong></div>
              <div class="business-detail-hero__metric"><span>报价有效期</span><strong>{{ detailRow.quoteValidUntil || '未报价' }}</strong></div>
              <div class="business-detail-hero__metric"><span>最小起订量</span><strong>{{ detailRow.minOrderQty }} {{ detailRow.unitName }}</strong></div>
              <div class="business-detail-hero__metric"><span>平均到货周期</span><strong>{{ formatDays(detailRow.avgDeliveryDays) }}</strong></div>
              <div class="business-detail-hero__metric"><span>推荐评分</span><strong>{{ formatScore(detailRow.aiScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>评分样本金额</span><strong>{{ formatMoney(detailRow.scoreBasisAmount) }}</strong></div>
            </template>
          </BusinessDetailHero>

          <BusinessDetailWorkbenchCard><section class="supplier-product-detail-section"><h3>关系资料</h3><dl class="supplier-product-detail-facts"><div><dt>供应商</dt><dd><strong>{{ detailRow.supplierName }}</strong><small>{{ detailRow.supplierCode }}</small></dd></div><div><dt>产品</dt><dd><strong>{{ detailRow.productName }}</strong><small>{{ detailRow.productCode }}</small></dd></div><div><dt>数量精度</dt><dd>{{ detailRow.quantityPrecision }} 位小数</dd></div><div><dt>当前状态</dt><dd>{{ detailRow.status === 1 ? '启用' : '停用' }}</dd></div><div><dt>供货条件</dt><dd>最小起订 {{ detailRow.minOrderQty }} {{ detailRow.unitName }}</dd></div><div><dt>平均到货周期</dt><dd>{{ formatDays(detailRow.avgDeliveryDays) }}</dd></div><div class="supplier-product-detail-facts__wide"><dt>备注</dt><dd>{{ detailRow.remark || '未填写' }}</dd></div></dl></section></BusinessDetailWorkbenchCard>

          <BusinessDetailWorkbenchCard><section class="supplier-product-detail-section"><h3>报价与成交</h3><dl class="supplier-product-detail-facts"><div><dt>当前报价</dt><dd><strong>{{ formatMoney(detailRow.quotedPurchasePrice) }}</strong></dd></div><div><dt>报价有效期</dt><dd>{{ detailRow.quoteValidUntil || '未报价' }}</dd></div><div><dt>报价更新时间</dt><dd>{{ detailRow.quotedPriceUpdatedAt || '未报价' }}</dd></div><div><dt>最近采购价</dt><dd>{{ formatMoney(detailRow.latestPurchasePrice) }}</dd></div><div><dt>最近采购时间</dt><dd>{{ detailRow.lastPurchaseAt || '暂无成交记录' }}</dd></div><div class="supplier-product-detail-facts__wide"><dt>报价调整原因</dt><dd>{{ detailRow.quotedPriceReason || '未填写' }}</dd></div></dl></section></BusinessDetailWorkbenchCard>

          <BusinessDetailWorkbenchCard><section class="supplier-product-detail-section"><h3>产品评分</h3><dl class="supplier-product-detail-facts"><div><dt>评分状态</dt><dd>{{ detailRow.scoreStatus === 'READY' ? '已有评分样本' : '暂无评分样本' }}</dd></div><div><dt>评分样本金额</dt><dd>{{ formatMoney(detailRow.scoreBasisAmount) }}</dd></div><div><dt>质量评分</dt><dd>{{ formatScore(detailRow.qualityScore) }}</dd></div><div><dt>价格评分</dt><dd>{{ formatScore(detailRow.priceScore) }}</dd></div><div><dt>推荐评分</dt><dd><strong>{{ formatScore(detailRow.aiScore) }}</strong></dd></div></dl></section></BusinessDetailWorkbenchCard>

          <BusinessDetailWorkbenchCard><section class="supplier-product-detail-section"><h3>维护信息</h3><dl class="supplier-product-detail-facts"><div><dt>创建时间</dt><dd>{{ detailRow.createTime }}</dd></div><div><dt>最后更新时间</dt><dd>{{ detailRow.updateTime }}</dd></div><div><dt>最后更新人</dt><dd>{{ detailRow.updatedByName || '系统' }}</dd></div></dl></section></BusinessDetailWorkbenchCard>
        </div>
      </DialogScrollArea>
      <DialogFooter><Button variant="outline" @click="detailVisible = false">关闭</Button></DialogFooter>
    </DialogContent>
  </Dialog>
  <ConfirmDialog
    :open="confirmState.open"
    :title="confirmState.title"
    :description="confirmState.description"
    :confirm-text="confirmState.confirmText"
    :variant="confirmState.variant"
    :loading="actionSubmitting"
    @update:open="confirmState.open = $event"
    @confirm="confirmState.onConfirm"
  />
</template>

<style scoped>
.data-panel :deep([data-slot="table-container"]) {
  display: block;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
}
.data-panel :deep([data-slot="table-container"]) {
  width: 100% !important;
  min-width: 0 !important;
}
.data-panel :deep(table.supplier-product-table) {
  width: max-content !important;
  min-width: 100%;
}
.data-panel {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
.data-panel :deep([data-slot="table-container"]) {
  width: 100%;
  max-width: 100%;
}
.supplier-product-toolbar {
  justify-content: space-between;
  gap: 12px;
}
.supplier-product-toolbar__right {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-left: auto;
}
.supplier-product-toolbar__main,
.supplier-product-toolbar__settings {
  width: auto;
}
.supplier-product-filter-range {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 6px;
}
.supplier-product-filter-range :deep([data-slot="input"]) {
  width: 100%;
  min-width: 0;
}
@media (max-width: 960px) {
  .supplier-product-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .supplier-product-toolbar__right {
    width: 100%;
  }
}
.supplier-product-table__pinned-left {
  position: sticky;
  z-index: 20;
  border-right: 1px solid var(--border);
  background: var(--card);
}
:deep(thead .supplier-product-table__pinned-left) {
  z-index: 30;
  background: var(--muted);
}
.supplier-product-table__actions {
  position: sticky;
  right: 0;
  z-index: 25;
  width: 112px;
  min-width: 112px;
  padding: 0.25rem 0.5rem;
  border-left: 1px solid var(--border);
  background: var(--background);
  text-align: center;
  white-space: nowrap;
  background-clip: padding-box;
}
:deep(thead .supplier-product-table__actions) {
  z-index: 40;
  background: var(--muted);
  background-clip: padding-box;
}
.supplier-product-detail-section { padding: 18px 20px; }
.supplier-product-detail-section h3 { margin: 0 0 14px; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.supplier-product-detail-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px 42px; margin: 0; }
.supplier-product-detail-facts > div { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: 10px; min-width: 0; font-size: 13px; line-height: 20px; }
.supplier-product-detail-facts dt { color: var(--muted-foreground); white-space: nowrap; }
.supplier-product-detail-facts dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.supplier-product-detail-facts dd > strong { display: block; font-weight: 600; }
.supplier-product-detail-facts dd > small { display: block; color: var(--muted-foreground); }
.supplier-product-detail-facts__wide { grid-column: 1 / -1; }
@media (max-width: 680px) { .supplier-product-detail-facts { grid-template-columns: 1fr; gap: 10px; } .supplier-product-detail-facts__wide { grid-column: auto; } }
</style>
