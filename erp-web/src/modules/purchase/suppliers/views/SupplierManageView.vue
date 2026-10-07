<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
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
import RowActionsMenu, {
  type RowActionOption,
} from "@/components/common/RowActionsMenu.vue";
import TableColumnPreferencesCanvas from "@/components/common/TableColumnPreferencesCanvas.vue";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
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
  batchDeleteSuppliers,
  batchUpdateSupplierStatus,
  createSupplier,
  deleteSupplier,
  getSupplierDetail,
  listSuppliers,
  updateSupplier,
  updateSupplierServiceScore,
} from "../../api";
import type {
  SupplierBatchFailure,
  SupplierBatchIdsPayload,
  SupplierBatchStatusPayload,
  SupplierCreatePayload,
  SupplierListItem,
  SupplierServiceScorePayload,
  SupplierUpdatePayload,
} from "../../types";
import SupplierScoreChangeLogDialog from "../../components/SupplierScoreChangeLogDialog.vue";

const columns: readonly TableColumnPreferenceDefinition[] = [
  { key: "supplier", label: "供应商", width: 190 },
  { key: "contact", label: "联系人", width: 135 },
  { key: "serviceScore", label: "服务分", width: 90 },
  { key: "deliveryScore", label: "交付分", width: 90 },
  { key: "qualityScore", label: "质量分", width: 90 },
  { key: "priceScore", label: "价格分", width: 90 },
  { key: "overallScore", label: "综合分", width: 90 },
  { key: "scoreBasisAmount", label: "样本金额", width: 120 },
  {
    key: "avgDeliveryDays",
    label: "平均到货周期",
    width: 125,
  },
  { key: "scoreStatus", label: "评分状态", width: 110 },
  { key: "status", label: "状态", width: 80 },
  { key: "actions", label: "操作", width: 112, required: true },
];
const {
  isVisible,
  reset: resetColumns,
  setVisible,
  tableMinWidth,
} = useTableColumnPreferences(
  "erp.purchase.suppliers.table-columns.v2",
  columns,
);
const configurableColumns = columns.filter((column) => !column.required);
const columnSettingsOpen = ref(false);
const columnSettings = computed(() =>
  configurableColumns.map((column) => ({
    ...column,
    visible: isVisible(column.key),
  })),
);

const rows = ref<SupplierListItem[]>([]);
const total = ref(0);
const loading = ref(false);
const queryPending = ref(false);
const saving = ref(false);
const editVisible = ref(false);
const scoreVisible = ref(false);
const scoreLogVisible = ref(false);
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailRow = ref<SupplierListItem | null>(null);
const editing = ref<SupplierListItem | null>(null);
const scoreTarget = ref<SupplierListItem | null>(null);
const scoreLogTarget = ref<SupplierListItem | null>(null);
const selectedIds = ref<Set<string>>(new Set());
const actionSubmitting = ref(false);
/** 部分删除失败明细：空数组表示全部成功。 */
const failureVisible = ref(false);
const failureTitle = ref("");
const failures = ref<SupplierBatchFailure[]>([]);
const confirmState = reactive({
  open: false,
  title: "",
  description: "",
  confirmText: "确认",
  variant: "warning" as "default" | "warning" | "destructive",
  onConfirm: (() => {}) as () => void | Promise<void>,
});
const query = reactive({
  supplierCode: "",
  supplierName: "",
  contactName: "",
  status: "all" as "" | "all" | 0 | 1,
  scoreStatus: "all" as "" | "all" | "NOT_READY" | "READY",
  overallScoreMin: null as number | null,
  overallScoreMax: null as number | null,
  serviceScoreMin: null as number | null,
  serviceScoreMax: null as number | null,
  scoreBasisAmountMin: null as string | null,
  scoreBasisAmountMax: null as string | null,
  avgDeliveryDaysMin: null as number | null,
  avgDeliveryDaysMax: null as number | null,
  pageNum: 1,
  pageSize: 10,
});
const form = reactive({
  supplierName: "",
  contactName: "",
  contactPhone: "",
  address: "",
  paymentTerms: "",
  status: 1 as 0 | 1,
  remark: "",
  serviceScore: null as number | null,
  serviceScoreReason: "",
});
const scoreForm = reactive({ serviceScore: null as number | null, reason: "" });
const queryBusy = computed(() => loading.value || queryPending.value);
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
    rows.value.every((row) => selectedIds.value.has(row.supplierId)),
);
const readyCount = computed(
  () => rows.value.filter((item) => item.scoreStatus === "READY").length,
);
const enabledCount = computed(
  () => rows.value.filter((item) => item.status === 1).length,
);
const notReadyCount = computed(
  () => rows.value.filter((item) => item.scoreStatus === "NOT_READY").length,
);
const scoreSampleCount = computed(
  () => rows.value.filter((item) => item.scoreBasisAmount != null && item.scoreBasisAmount > 0).length,
);
const summaryItems = computed(() => [
  {
    key: "enabled",
    label: "本页启用",
    value: enabledCount.value,
    tone: "positive" as const,
  },
  {
    key: "ready",
    label: "本页评分已就绪",
    value: readyCount.value,
    tone: "positive" as const,
  },
  {
    key: "not-ready",
    label: "本页暂无样本",
    value: notReadyCount.value,
    tone: "warning" as const,
  },
  { key: "sample", label: "本页已有评分样本", value: scoreSampleCount.value },
]);
const formatScore = (value: number | null) =>
  value == null ? "—" : value.toFixed(2);
const formatMoney = (value: number | null) =>
  value == null ? "--" : `￥${value.toFixed(2)}`;
const formatDays = (value: number | null) =>
  value == null ? "—" : `${value.toFixed(1)} 天`;
async function load() {
  loading.value = true;
  try {
    const page = await listSuppliers({ ...query });
    rows.value = page.records;
    total.value = page.total;
    selectedIds.value = new Set();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "供应商列表加载失败");
  } finally {
    loading.value = false;
    queryPending.value = false;
  }
}
const debouncedLoad = useDebounceFn(load, 250);
// 重置与查询共用同一个防抖请求，保留清晰的语义入口供列表约定和后续维护使用。
const debouncedSearch = debouncedLoad;
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
      supplierCode: "",
      supplierName: "",
      contactName: "",
      status: "all",
      scoreStatus: "all",
      overallScoreMin: null,
      overallScoreMax: null,
      serviceScoreMin: null,
      serviceScoreMax: null,
      scoreBasisAmountMin: null,
      scoreBasisAmountMax: null,
      avgDeliveryDaysMin: null,
      avgDeliveryDaysMax: null,
      pageNum: 1,
    });
    queryPending.value = true;
    debouncedSearch();
  }
}
function handlePageChange(pageNum: number) {
  if (!queryBusy.value && pageNum !== query.pageNum) {
    query.pageNum = pageNum;
    queryPending.value = true;
    debouncedLoad();
  }
}
function handlePageSizeChange(pageSize: number) {
  if (!queryBusy.value && pageSize !== query.pageSize) {
    query.pageSize = pageSize;
    query.pageNum = 1;
    queryPending.value = true;
    debouncedLoad();
  }
}
const refreshList = useListRefresh(queryBusy, queryPending, load);

function resetForm() {
  Object.assign(form, {
    supplierName: "",
    contactName: "",
    contactPhone: "",
    address: "",
    paymentTerms: "",
    status: 1,
    remark: "",
    serviceScore: null,
    serviceScoreReason: "",
  });
}
function openCreate() {
  editing.value = null;
  resetForm();
  editVisible.value = true;
}
function openEdit(row: SupplierListItem) {
  editing.value = row;
  Object.assign(form, {
    supplierName: row.supplierName,
    contactName: row.contactName,
    contactPhone: row.contactPhone,
    address: row.address,
    paymentTerms: row.paymentTerms,
    status: row.status,
    remark: row.remark,
    serviceScore: null,
    serviceScoreReason: "",
  });
  editVisible.value = true;
}
function validateBase() {
  if (!form.supplierName.trim()) return "请输入供应商名称";
  if (
    form.serviceScore != null &&
    (!form.serviceScoreReason.trim() ||
      form.serviceScore < 0 ||
      form.serviceScore > 100 ||
      !Number.isInteger(form.serviceScore * 100))
  )
    return "初始服务分必须在 0-100 之间、最多两位小数，并填写原因";
  if (form.serviceScore == null && form.serviceScoreReason.trim())
    return "未填写初始服务分时不能填写原因";
  return "";
}
function validateQueryRange(
  min: number | null,
  max: number | null,
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
function validateMoneyQueryRange(
  min: string | null,
  max: string | null,
  label: string,
) {
  const values = [min, max].map((value) => value?.trim() || null);
  if (values.some((value) => value != null && !/^\d+(?:\.\d{1,2})?$/.test(value))) {
    return `${label}必须是最多两位小数的非负金额`;
  }
  const [minValue, maxValue] = values.map((value) =>
    value == null ? null : Number(value),
  );
  return minValue != null && maxValue != null && minValue > maxValue
    ? `${label}区间不合法`
    : "";
}
function validateQuery() {
  return (
    validateQueryRange(query.overallScoreMin, query.overallScoreMax, "综合分", 100, true)
    || validateQueryRange(query.serviceScoreMin, query.serviceScoreMax, "服务分", 100, true)
    || validateMoneyQueryRange(query.scoreBasisAmountMin, query.scoreBasisAmountMax, "评分样本金额")
    || validateQueryRange(query.avgDeliveryDaysMin, query.avgDeliveryDaysMax, "平均到货周期")
  );
}
async function save() {
  const error = validateBase();
  if (error) return toast.warning(error);
  saving.value = true;
  try {
    if (editing.value) {
      const payload: SupplierUpdatePayload = {
        supplierName: form.supplierName.trim(),
        contactName: form.contactName.trim(),
        contactPhone: form.contactPhone.trim(),
        address: form.address.trim(),
        paymentTerms: form.paymentTerms.trim(),
        status: form.status,
        remark: form.remark.trim(),
        version: editing.value.version,
      };
      await updateSupplier(editing.value.supplierId, payload);
    } else {
      const payload: SupplierCreatePayload = {
        supplierName: form.supplierName.trim(),
        contactName: form.contactName.trim(),
        contactPhone: form.contactPhone.trim(),
        address: form.address.trim(),
        paymentTerms: form.paymentTerms.trim(),
        status: form.status,
        remark: form.remark.trim(),
        serviceScore: form.serviceScore,
        serviceScoreReason:
          form.serviceScore == null ? null : form.serviceScoreReason.trim(),
      };
      await createSupplier(payload);
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
function openScore(row: SupplierListItem) {
  scoreTarget.value = row;
  scoreForm.serviceScore = row.serviceScore;
  scoreForm.reason = "";
  scoreVisible.value = true;
}
async function saveScore() {
  const target = scoreTarget.value;
  if (!target || !scoreForm.reason.trim())
    return toast.warning("请填写本次调整原因");
  if (
    scoreForm.serviceScore != null &&
    (scoreForm.serviceScore < 0 ||
      scoreForm.serviceScore > 100 ||
      !Number.isInteger(scoreForm.serviceScore * 100))
  )
    return toast.warning("服务分必须在 0-100 之间且最多两位小数");
  saving.value = true;
  try {
    const payload: SupplierServiceScorePayload = {
      version: target.version,
      serviceScore: scoreForm.serviceScore,
      reason: scoreForm.reason.trim(),
    };
    await updateSupplierServiceScore(target.supplierId, payload);
    toast.success("服务分已调整");
    scoreVisible.value = false;
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "服务分调整失败");
  } finally {
    saving.value = false;
  }
}
function selectedPayload(): SupplierBatchIdsPayload {
  return {
    supplierIds: [...selectedIds.value],
    versionBySupplierId: Object.fromEntries(
      rows.value
        .filter((row) => selectedIds.value.has(row.supplierId))
        .map((row) => [row.supplierId, row.version]),
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
      ? new Set(rows.value.map((row) => row.supplierId))
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
  const payload: SupplierBatchStatusPayload = { ...selectedPayload(), status };
  if (!payload.supplierIds.length) return;
  actionSubmitting.value = true;
  try {
    await batchUpdateSupplierStatus(payload);
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
  if (!payload.supplierIds.length) return;
  actionSubmitting.value = true;
  try {
    const failedItems = await batchDeleteSuppliers(payload);
    if (failedItems.length) {
      showDeleteFailures("批量删除未完成", failedItems);
      await load();
      return;
    }
    toast.success("已批量删除");
    await load();
  } catch (error) {
    toast.error(getApiErrorMessage(error) || "批量删除失败");
  } finally {
    actionSubmitting.value = false;
    confirmState.open = false;
  }
}
async function toggleStatus(row: SupplierListItem) {
  actionSubmitting.value = true;
  try {
    await batchUpdateSupplierStatus({
      supplierIds: [row.supplierId],
      versionBySupplierId: { [row.supplierId]: row.version },
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
function remove(row: SupplierListItem) {
  openConfirm({
    title: "删除供应商",
    description: `确认删除供应商「${row.supplierName}」？`,
    confirmText: "删除",
    variant: "destructive",
    onConfirm: async () => {
      actionSubmitting.value = true;
      try {
        const failedItems = await deleteSupplier(row.supplierId, row.version);
        if (failedItems.length) {
          showDeleteFailures("删除未完成", failedItems);
          await load();
          return;
        }
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

function showDeleteFailures(title: string, failedItems: SupplierBatchFailure[]) {
  failureTitle.value = title;
  failures.value = failedItems;
  failureVisible.value = true;
}
function rowActions(row: SupplierListItem): RowActionOption[] {
  return [
    { key: "edit", label: "编辑" },
    { key: "service-score", label: "调整服务分" },
    { key: "score-change-logs", label: "评分变更记录" },
    { key: "status", label: row.status === 1 ? "停用" : "启用" },
    { key: "delete", label: "删除", variant: "destructive", separated: true },
  ];
}
async function openDetail(row: SupplierListItem) {
  if (detailLoading.value) return;
  detailVisible.value = true;
  detailLoading.value = true;
  detailRow.value = null;
  try {
    detailRow.value = await getSupplierDetail(row.supplierId);
  } catch (error) {
    detailVisible.value = false;
    toast.warning(getApiErrorMessage(error) || "供应商详情加载失败");
  } finally {
    detailLoading.value = false;
  }
}
function handleRowAction(row: SupplierListItem, action: string) {
  if (action === "detail") {
    void openDetail(row);
  } else if (action === "edit") openEdit(row);
  else if (action === "service-score") openScore(row);
  else if (action === "score-change-logs") {
    scoreLogTarget.value = row;
    scoreLogVisible.value = true;
  }
  else if (action === "status")
    openConfirm({
      title: `${row.status === 1 ? "停用" : "启用"}供应商`,
      description: `确认${row.status === 1 ? "停用" : "启用"}供应商「${row.supplierName}」？`,
      confirmText: row.status === 1 ? "停用" : "启用",
      variant: "warning",
      onConfirm: () => toggleStatus(row),
    });
  else if (action === "delete") remove(row);
}
onMounted(load);
</script>

<template>
  <section class="page-shell space-y-4">
    <div class="page-heading">
      <div>
        <h1 class="page-title">供应商管理</h1>
        <p class="page-description">维护供应商基础资料、服务分及评分样本状态</p>
      </div>
    </div>
    <ListSummaryStrip :items="summaryItems" aria-label="供应商数据汇总" />

    <ListFilterPanel layout="content" actions-position="bottom" aria-label="供应商筛选">
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">供应商编码</Label
        ><Input
          v-model="query.supplierCode"
          placeholder="请输入供应商编码"
          @keyup.enter="handleSearch"
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
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">联系人</Label
        ><Input
          v-model="query.contactName"
          placeholder="请输入联系人"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">评分状态</Label
        ><AnchoredSelect v-model="query.scoreStatus" :options="scoreStatusOptions" />
      </div>
      <div class="space-y-1" data-filter-size="compact">
        <Label class="text-xs">状态</Label
        ><AnchoredSelect v-model="query.status" :options="statusOptions" />
      </div>
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">综合分范围</Label>
        <div class="supplier-score-range">
          <Input
            :model-value="query.overallScoreMin ?? ''"
            type="number"
            min="0"
            max="100"
            step="0.01"
            placeholder="最低分"
            @update:model-value="
              (value) =>
                (query.overallScoreMin = value === '' ? null : Number(value))
            "
          /><span class="text-muted-foreground">—</span
          ><Input
            :model-value="query.overallScoreMax ?? ''"
            type="number"
            min="0"
            max="100"
            step="0.01"
            placeholder="最高分"
            @update:model-value="
              (value) =>
                (query.overallScoreMax = value === '' ? null : Number(value))
            "
          />
        </div>
      </div>
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">服务分范围</Label>
        <div class="supplier-score-range">
          <Input :model-value="query.serviceScoreMin ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最低分" aria-label="服务分最低值" @update:model-value="value => query.serviceScoreMin = value === '' ? null : Number(value)" />
          <span class="text-muted-foreground">—</span>
          <Input :model-value="query.serviceScoreMax ?? ''" type="number" min="0" max="100" step="0.01" placeholder="最高分" aria-label="服务分最高值" @update:model-value="value => query.serviceScoreMax = value === '' ? null : Number(value)" />
        </div>
      </div>
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">评分样本金额范围</Label>
        <div class="supplier-score-range">
          <Input :model-value="query.scoreBasisAmountMin ?? ''" type="text" inputmode="decimal" placeholder="最低金额" aria-label="评分样本金额最低值" @update:model-value="value => query.scoreBasisAmountMin = value === '' ? null : String(value)" />
          <span class="text-muted-foreground">—</span>
          <Input :model-value="query.scoreBasisAmountMax ?? ''" type="text" inputmode="decimal" placeholder="最高金额" aria-label="评分样本金额最高值" @update:model-value="value => query.scoreBasisAmountMax = value === '' ? null : String(value)" />
        </div>
      </div>
      <div class="space-y-1" data-filter-size="wide">
        <Label class="text-xs">平均到货周期范围</Label>
        <div class="supplier-score-range">
          <Input :model-value="query.avgDeliveryDaysMin ?? ''" type="number" min="0" step="0.01" placeholder="最短天数" aria-label="平均到货周期最小值" @update:model-value="value => query.avgDeliveryDaysMin = value === '' ? null : Number(value)" />
          <span class="text-muted-foreground">—</span>
          <Input :model-value="query.avgDeliveryDaysMax ?? ''" type="number" min="0" step="0.01" placeholder="最长天数" aria-label="平均到货周期最大值" @update:model-value="value => query.avgDeliveryDaysMax = value === '' ? null : Number(value)" />
        </div>
      </div>
      <template #actions
        ><ListFilterActions
          :busy="queryBusy"
          @query="handleSearch"
          @reset="handleReset"
      /></template>
    </ListFilterPanel>

    <div class="data-panel relative">
      <div class="table-toolbar supplier-toolbar">
        <div class="table-toolbar__title">
          <strong class="text-sm">供应商列表</strong>
          <span class="text-xs text-muted-foreground"
            >维护供应商资料、评分状态与服务分；勾选记录后可批量启用、停用或删除</span
          >
        </div>
        <div class="supplier-toolbar__right">
        <div class="table-toolbar__actions supplier-toolbar__main">
          <Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button size="sm" @click="openCreate">新增供应商</Button></span
              ></TooltipTrigger
            ><TooltipContent>创建供应商主数据</TooltipContent></Tooltip
          ><Tooltip
            ><TooltipTrigger as-child
              ><span class="inline-flex"
                ><Button
                  size="sm"
                  variant="outline"
                  :disabled="!selectedIds.size || actionSubmitting"
                  @click="
                    openConfirm({
                      title: '批量启用供应商',
                      description: `确认启用已选 ${selectedIds.size} 个供应商？`,
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
                ? `启用已选 ${selectedIds.size} 个供应商`
                : "请先选择供应商"
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
                      title: '批量停用供应商',
                      description: `确认停用已选 ${selectedIds.size} 个供应商？`,
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
                ? `停用已选 ${selectedIds.size} 个供应商`
                : "请先选择供应商"
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
                      title: '批量删除供应商',
                      description: `确认删除已选 ${selectedIds.size} 个供应商？已被供货关系或采购单引用的数据不会被删除。`,
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
                ? `删除已选 ${selectedIds.size} 个供应商`
                : "请先选择供应商"
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
            ><TooltipContent>重新加载供应商列表</TooltipContent></Tooltip
          >
        </div>
        <div class="table-toolbar__actions supplier-toolbar__settings">
          <TableColumnPreferencesCanvas
            v-model:open="columnSettingsOpen"
            :columns="columnSettings"
            @toggle-visible="setVisible"
            @reset="resetColumns"
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
        class="supplier-table table-fixed"
        :style="{ width: `${tableMinWidth}px`, minWidth: `${tableMinWidth}px` }"
        scroll-label="供应商列表"
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
                aria-label="全选供应商"
                @update:model-value="toggleSelectAll" /></TableHead
            ><TableHead
              v-if="isVisible('supplier')"
              >供应商</TableHead
            ><TableHead
              v-if="isVisible('contact')"
              >联系人</TableHead
            ><TableHead
              v-if="isVisible('serviceScore')"
              >服务分</TableHead
            ><TableHead
              v-if="isVisible('deliveryScore')"
              >交付分</TableHead
            ><TableHead
              v-if="isVisible('qualityScore')"
              >质量分</TableHead
            ><TableHead
              v-if="isVisible('priceScore')"
              >价格分</TableHead
            ><TableHead
              v-if="isVisible('overallScore')"
              >综合分</TableHead
            ><TableHead
              v-if="isVisible('scoreBasisAmount')"
              >样本金额</TableHead
            ><TableHead
              v-if="isVisible('avgDeliveryDays')"
              >平均到货周期</TableHead
            ><TableHead
              v-if="isVisible('scoreStatus')"
              >评分状态</TableHead
            ><TableHead
              v-if="isVisible('status')"
              >状态</TableHead
            ><TableHead
              class="supplier-table__actions sticky right-0 z-20 border-l border-border/60 bg-muted text-center"
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
            :key="row.supplierId"
            class="group"
            ><TableCell
              ><Checkbox
                :model-value="selectedIds.has(row.supplierId)"
                :aria-label="`选择供应商：${row.supplierName}`"
                @update:model-value="
                  toggleSelect(row.supplierId, $event)
                " /></TableCell
            ><TableCell
              v-if="isVisible('supplier')"
              ><div class="min-w-0">
                <p class="truncate font-medium" :title="row.supplierName">
                  {{ row.supplierName }}
                </p>
                <code class="text-xs text-muted-foreground">{{
                  row.supplierCode
                }}</code>
              </div></TableCell
            ><TableCell
              v-if="isVisible('contact')"
              ><div class="min-w-0">
                <p class="truncate">{{ row.contactName }}</p>
                <OverflowTooltip
                  :text="row.contactPhone"
                  class="block text-xs text-muted-foreground"
                /></div></TableCell
            ><TableCell
              v-if="isVisible('serviceScore')"
              >{{ formatScore(row.serviceScore) }}</TableCell
            ><TableCell
              v-if="isVisible('deliveryScore')"
              >{{ formatScore(row.deliveryScore) }}</TableCell
            ><TableCell
              v-if="isVisible('qualityScore')"
              >{{ formatScore(row.qualityScore) }}</TableCell
            ><TableCell
              v-if="isVisible('priceScore')"
              >{{ formatScore(row.priceScore) }}</TableCell
            ><TableCell
              v-if="isVisible('overallScore')"
              ><strong>{{ formatScore(row.overallScore) }}</strong></TableCell
            ><TableCell
              v-if="isVisible('scoreBasisAmount')"
              >{{ formatMoney(row.scoreBasisAmount) }}</TableCell
            ><TableCell
              v-if="isVisible('avgDeliveryDays')"
              >{{ formatDays(row.avgDeliveryDays) }}</TableCell
            ><TableCell
              v-if="isVisible('scoreStatus')"
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
              class="supplier-table__actions sticky right-0 z-20 whitespace-nowrap border-l border-border/60 bg-background text-center group-hover:bg-muted/50"
              data-table-sticky-edge="end"
              ><div class="flex items-center justify-center gap-1">
                <Tooltip><TooltipTrigger as-child><span class="inline-flex"><Button
                  size="sm"
                  variant="ghost"
                  class="h-8 px-2.5 font-medium text-indigo-600 hover:bg-indigo-50 hover:text-indigo-700"
                  @click="handleRowAction(row, 'detail')"
                  >查看</Button
                ></span></TooltipTrigger><TooltipContent>查看供应商完整资料、评分与维护信息</TooltipContent></Tooltip><RowActionsMenu
                  :actions="rowActions(row)"
                  :label="`更多操作：${row.supplierName}`"
                  trigger-text="更多"
                  @select="handleRowAction(row, $event)"
                /></div></TableCell></TableRow></TableBody
      ></Table>
      <DataTablePagination
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
  />
  <Dialog v-model:open="editVisible"
    ><DialogContent
      placement="app-content"
      class="flex h-[min(680px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-2xl"
      ><DialogHeader
        ><DialogTitle>{{ editing ? "编辑供应商" : "新增供应商" }}</DialogTitle
        ><DialogDescription
          >基础资料与自动评分分开维护。</DialogDescription
        ></DialogHeader
      ><DialogScrollArea class="max-h-[65vh]"
        ><div class="grid gap-3 p-1 md:grid-cols-2">
          <div>
            <Label>供应商名称 <span class="text-destructive">*</span></Label
            ><Input
              v-model="form.supplierName"
              maxlength="200"
              placeholder="请输入供应商名称"
            />
          </div>
          <div>
            <Label>联系人</Label
            ><Input
              v-model="form.contactName"
              maxlength="100"
              placeholder="选填"
            />
          </div>
          <div>
            <Label>联系电话</Label
            ><Input
              v-model="form.contactPhone"
              maxlength="32"
              placeholder="选填"
            />
          </div>
          <div>
            <Label>付款条件</Label
            ><Input
              v-model="form.paymentTerms"
              maxlength="100"
              placeholder="选填，如：月结 30 天"
            />
          </div>
          <div class="md:col-span-2">
            <Label>地址</Label
            ><Input
              v-model="form.address"
              maxlength="255"
              placeholder="选填"
            />
          </div>
          <template v-if="!editing"
            ><div>
              <Label>初始服务分（可选）</Label
              ><Input
                :model-value="form.serviceScore ?? ''"
                type="number"
                min="0"
                max="100"
                step="0.01"
                @update:model-value="
                  (value) =>
                    (form.serviceScore = value === '' ? null : Number(value))
                "
              />
            </div>
            <div>
              <Label>初始服务分原因</Label
              ><Input
                v-model="form.serviceScoreReason"
                placeholder="填写初始服务分时必填"
              /></div
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
              placeholder="选填，补充供应商合作说明"
            />
          </div></div></DialogScrollArea
      ><DialogFooter
        ><Button variant="outline" @click="editVisible = false">取消</Button
        ><Button :disabled="saving" @click="save">保存</Button></DialogFooter
      ></DialogContent
    ></Dialog
  >
  <Dialog v-model:open="scoreVisible"
    ><DialogContent placement="app-content"
      ><DialogHeader
        ><DialogTitle>调整服务分</DialogTitle
        ><DialogDescription
          >服务分变化会记录评分变化日志；自动重算将在后续评分任务接入。</DialogDescription
        ></DialogHeader
      >
      <div class="space-y-3">
        <div>
          <Label>服务分（留空表示清空）</Label
          ><Input
            :model-value="scoreForm.serviceScore ?? ''"
            type="number"
            min="0"
            max="100"
            step="0.01"
            @update:model-value="
              (value) =>
                (scoreForm.serviceScore = value === '' ? null : Number(value))
            "
          />
        </div>
        <div>
          <Label>本次调整原因</Label><Textarea v-model="scoreForm.reason" />
        </div>
      </div>
      <DialogFooter
        ><Button variant="outline" @click="scoreVisible = false">取消</Button
        ><Button :disabled="saving" @click="saveScore"
          >确认调整</Button
        ></DialogFooter
      ></DialogContent
    ></Dialog
  >
  <Dialog v-model:open="detailVisible">
    <DialogContent placement="app-content" data-supplier-detail-workbench class="supplier-detail-workbench flex !h-[min(780px,calc(100dvh-var(--app-shell-header-height)-2rem))] !max-h-[calc(100dvh-var(--app-shell-header-height)-2rem)] max-w-[calc(100%-2rem)] flex-col overflow-hidden !bg-[#f6f8fb] !p-4 sm:max-w-5xl">
      <DialogHeader class="sr-only"><DialogTitle>供应商详情</DialogTitle><DialogDescription>供应商基础信息、评分状态与维护记录。</DialogDescription></DialogHeader>
      <DialogScrollArea content-class="px-5 py-5 pr-6">
        <div v-if="detailLoading" class="flex min-h-64 items-center justify-center gap-2 text-sm text-muted-foreground"><span class="page-loading-spinner" />详情加载中…</div>
        <div v-else-if="detailRow" class="space-y-5">
          <BusinessDetailHero eyebrow="供应商档案" :title="detailRow.supplierName" :subtitle="detailRow.supplierCode" :status-label="detailRow.status === 1 ? '启用' : '停用'" :status-class="detailRow.status === 1 ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-600'" :metric-columns="3" variant="canvas">
            <template #metrics>
              <div class="business-detail-hero__metric"><span>综合评分</span><strong>{{ formatScore(detailRow.overallScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>服务评分</span><strong>{{ formatScore(detailRow.serviceScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>交付评分</span><strong>{{ formatScore(detailRow.deliveryScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>质量评分</span><strong>{{ formatScore(detailRow.qualityScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>价格评分</span><strong>{{ formatScore(detailRow.priceScore) }}</strong></div>
              <div class="business-detail-hero__metric"><span>评分样本金额</span><strong>{{ formatMoney(detailRow.scoreBasisAmount) }}</strong></div>
            </template>
          </BusinessDetailHero>

          <BusinessDetailWorkbenchCard><section class="supplier-detail-section"><h3>基础资料</h3><dl class="supplier-detail-facts"><div><dt>供应商编码</dt><dd><code>{{ detailRow.supplierCode }}</code></dd></div><div><dt>联系人</dt><dd>{{ detailRow.contactName || '未维护' }}</dd></div><div><dt>联系电话</dt><dd>{{ detailRow.contactPhone || '未维护' }}</dd></div><div><dt>付款条件</dt><dd>{{ detailRow.paymentTerms || '未维护' }}</dd></div><div><dt>当前状态</dt><dd>{{ detailRow.status === 1 ? '启用' : '停用' }}</dd></div><div class="supplier-detail-facts__wide"><dt>联系地址</dt><dd>{{ detailRow.address || '未维护' }}</dd></div><div class="supplier-detail-facts__wide"><dt>备注</dt><dd>{{ detailRow.remark || '未填写' }}</dd></div></dl></section></BusinessDetailWorkbenchCard>

          <BusinessDetailWorkbenchCard><section class="supplier-detail-section"><h3>评分与样本</h3><dl class="supplier-detail-facts"><div><dt>评分状态</dt><dd>{{ detailRow.scoreStatus === 'READY' ? '已有评分样本' : '暂无评分样本' }}</dd></div><div><dt>评分样本金额</dt><dd>{{ formatMoney(detailRow.scoreBasisAmount) }}</dd></div><div><dt>平均到货周期</dt><dd>{{ formatDays(detailRow.avgDeliveryDays) }}</dd></div><div><dt>服务分调整原因</dt><dd>{{ detailRow.serviceScoreReason || '未填写' }}</dd></div><div><dt>交付 / 质量 / 价格</dt><dd>{{ formatScore(detailRow.deliveryScore) }} / {{ formatScore(detailRow.qualityScore) }} / {{ formatScore(detailRow.priceScore) }}</dd></div><div><dt>综合评分</dt><dd><strong>{{ formatScore(detailRow.overallScore) }}</strong></dd></div></dl></section></BusinessDetailWorkbenchCard>

          <BusinessDetailWorkbenchCard><section class="supplier-detail-section"><h3>维护信息</h3><dl class="supplier-detail-facts"><div><dt>创建时间</dt><dd>{{ detailRow.createTime }}</dd></div><div><dt>最后更新时间</dt><dd>{{ detailRow.updateTime }}</dd></div><div><dt>最后更新人</dt><dd>{{ detailRow.updatedByName || '系统' }}</dd></div></dl></section></BusinessDetailWorkbenchCard>
        </div>
      </DialogScrollArea>
      <DialogFooter><Button variant="outline" @click="detailVisible = false">关闭</Button></DialogFooter>
    </DialogContent>
  </Dialog>
  <Dialog v-model:open="failureVisible"
    ><DialogContent
      placement="app-content"
      class="flex max-h-[min(520px,calc(100dvh-2rem))] flex-col overflow-hidden sm:max-w-md"
      ><DialogHeader
        ><DialogTitle>{{ failureTitle }}</DialogTitle
        ><DialogDescription
          >共 {{ failures.length }} 条未删除,可在列表中查看完整原因。</DialogDescription
        ></DialogHeader
      ><ul class="flex-1 space-y-2 overflow-y-auto p-1 text-sm">
          <li
            v-for="item in failures"
            :key="item.supplierId"
            class="rounded-md border border-border/60 bg-muted/30 p-3"
          >
            <div class="flex items-center justify-between gap-2">
              <code class="text-xs text-muted-foreground">{{ item.supplierId }}</code>
              <Badge variant="destructive">失败</Badge>
            </div>
            <p class="mt-1 text-foreground">{{ item.reason }}</p>
          </li>
        </ul
      ><DialogFooter
        ><Button variant="outline" @click="failureVisible = false">关闭</Button></DialogFooter
      ></DialogContent
    >
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
.data-panel {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}
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
.data-panel :deep(table.supplier-table) {
  width: max-content !important;
  min-width: 100%;
}
.supplier-toolbar {
  justify-content: space-between;
  gap: 12px;
}
.supplier-toolbar__right {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-left: auto;
}
.supplier-toolbar__main,
.supplier-toolbar__settings {
  width: auto;
}
@media (max-width: 960px) {
  .supplier-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .supplier-toolbar__right {
    width: 100%;
  }
}
.supplier-score-range {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 6px;
}
.supplier-score-range :deep([data-slot="input"]) {
  width: 100%;
  min-width: 0;
}
.supplier-table__actions {
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
:deep(thead .supplier-table__actions) {
  z-index: 40;
  background: var(--muted);
  background-clip: padding-box;
}
.supplier-detail-section { padding: 18px 20px; }
.supplier-detail-section h3 { margin: 0 0 14px; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.supplier-detail-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px 42px; margin: 0; }
.supplier-detail-facts > div { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 10px; min-width: 0; font-size: 13px; line-height: 20px; }
.supplier-detail-facts dt { color: var(--muted-foreground); white-space: nowrap; }
.supplier-detail-facts dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.supplier-detail-facts__wide { grid-column: 1 / -1; }
@media (max-width: 680px) { .supplier-detail-facts { grid-template-columns: 1fr; gap: 10px; } .supplier-detail-facts__wide { grid-column: auto; } }
</style>
