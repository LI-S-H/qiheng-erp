<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { isPastDate } from '@/shared/utils/date';
import { MAX_SAFE_MONEY } from '@/shared/utils/money';
import { listEnabledSalesWarehouseOptions } from '@/modules/sales/api';
import { getWarehouseDetail } from '@/modules/warehouse/warehouses/api';
import RejectDraftDialog from './RejectDraftDialog.vue';
import {
  createSalesOrderFromDraft,
  findDraftLine,
  loadSalesDraftContext,
  loadSalesOrderDetail,
  resolveSalesDraft,
  searchDraftProducts,
  updateSalesOrderFromDraft,
} from './sales-draft-api';
import {
  SALES_DRAFT_REJECT_REASONS,
  type SalesDraftHeader,
  type SalesDraftLine,
  type SalesDraftRequest,
} from './sales-draft-types';

const props = defineProps<{
  open: boolean;
  request: SalesDraftRequest | null;
  conversationId: string;
  /** 创建新草稿或修改已有销售单。 */
  action?: 'CREATE' | 'UPDATE';
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [context: { conversationId: string; callId: string }];
}>();

/** 确认动作：创建、保存编辑、放弃编辑。 */
type ConfirmAction = 'create' | 'update' | 'discard' | 'close';

const isUpdate = computed(() => props.action === 'UPDATE');
const orderVersion = ref<number | null>(null);
const existingOrderNo = ref('');
const existingOrderStatus = ref('');

const loading = ref(false);
const loadError = ref('');
const mode = ref<'readonly' | 'edit'>('readonly');
const submitting = ref(false);
const customerLabel = ref('');
const warehouseLabel = ref('');
const originalWarehouseLabel = ref('');
const lines = ref<SalesDraftLine[]>([]);
const originalLines = ref<SalesDraftLine[]>([]);
const rejectOpen = ref(false);
const rejectReasons = ref<string[]>([]);
const rejectNote = ref('');
const rejectError = ref('');
const form = reactive({ warehouseId: '', expectedDeliveryDate: '', remark: '', userNote: '' });
const originalForm = reactive({ warehouseId: '', expectedDeliveryDate: '', remark: '' });
const errors = reactive<Record<string, string>>({});

const readonly = computed(() => mode.value === 'readonly');
const totalAmount = computed(() => lines.value.reduce((sum, line) => sum + lineAmount(line), 0));
const busy = computed(() => submitting.value || loading.value || Boolean(loadError.value));
const customerId = ref('');
const rejectOptions = SALES_DRAFT_REJECT_REASONS;
/** 选择「其他」时必须写清楚具体要求，否则模型无法据此调整。 */
const rejectOtherSelected = computed(() => rejectReasons.value.includes('其他'));

/** 每次打开都按最新草稿重新加载，避免沿用上一次确认页的状态。 */
watch(() => [props.open, props.request?.draftRequestId], ([open]) => {
  if (open) {
    resetState();
    void load();
  }
}, { immediate: true });

/** 读取草稿展示信息并切回只读态。 */
async function load() {
  const request = props.request;
  if (!request) return;
  loading.value = true;
  loadError.value = '';
  mode.value = 'readonly';
  try {
    if (isUpdate.value) {
      await loadExistingOrder(request.draft);
    } else {
      await loadNewDraft(request.draft);
    }
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '销售草稿加载失败，请重新发起销售需求';
  } finally {
    loading.value = false;
  }
}

/** 创建：按工具参数补齐客户、仓库和产品名称。 */
async function loadNewDraft(draft: SalesDraftHeader) {
  customerId.value = draft.customerId;
  const context = await loadSalesDraftContext(draft);
  customerLabel.value = context.customerLabel;
  warehouseLabel.value = context.warehouseLabel;
  originalWarehouseLabel.value = context.warehouseLabel;
  lines.value = context.lines.map(line => ({ ...line }));
  originalLines.value = context.lines.map(line => ({ ...line }));
  Object.assign(form, {
    warehouseId: draft.warehouseId,
    expectedDeliveryDate: draft.expectedDeliveryDate || '',
    remark: draft.remark || '',
    userNote: '',
  });
  Object.assign(originalForm, {
    warehouseId: draft.warehouseId,
    expectedDeliveryDate: draft.expectedDeliveryDate || '',
    remark: draft.remark || '',
  });
}

/** 修改：以单据详情为底，再用模型给的新值覆盖。 */
async function loadExistingOrder(draft: SalesDraftHeader) {
  const orderId = draft.salesOrderId;
  if (!orderId) throw new Error('缺少要修改的销售单');
  const detail = await loadSalesOrderDetail(orderId);
  orderVersion.value = detail.version;
  existingOrderNo.value = detail.salesNo;
  existingOrderStatus.value = detail.status;
  customerId.value = detail.customerId;
  customerLabel.value = `${detail.customerCode} ${detail.customerName}`.trim();
  warehouseLabel.value = detail.warehouseName;
  originalWarehouseLabel.value = detail.warehouseName;
  const baseLines: SalesDraftLine[] = detail.items.map(item => ({
    productId: item.productId,
    productCode: item.productCode,
    productName: item.productName,
    unitName: item.unitName,
    quantityPrecision: item.quantityPrecision,
    referenceSalePrice: null,
    salesOrderItemId: item.salesOrderItemId,
    quantity: item.quantity,
    unitPrice: item.unitPrice,
    remark: item.remark || '',
  }));
  lines.value = await applyModelItems(baseLines, draft.items);
  originalLines.value = lines.value.map(line => ({ ...line }));
  Object.assign(form, {
    warehouseId: draft.warehouseId || detail.warehouseId,
    expectedDeliveryDate: draft.expectedDeliveryDate || detail.expectedDeliveryDate || '',
    remark: draft.remark ?? detail.remark ?? '',
    userNote: '',
  });
  Object.assign(originalForm, {
    warehouseId: form.warehouseId,
    expectedDeliveryDate: form.expectedDeliveryDate,
    remark: form.remark,
  });
  // 修改场景直接进入编辑态。
  mode.value = 'edit';
}

/** 模型给的明细覆盖单据明细：保留原明细编号与精度，按产品匹配。 */
async function applyModelItems(baseLines: SalesDraftLine[], items: SalesDraftHeader['items']) {
  if (!items.length) {
    return baseLines;
  }
  return Promise.all(items.map(async item => {
    const existing = baseLines.find(line => line.productId === item.productId);
    if (existing) {
      return { ...existing, quantity: item.quantity, unitPrice: item.unitPrice ?? existing.unitPrice, remark: item.remark || '' };
    }
    const found = await findDraftLine(item.productId).catch(() => null);
    if (found) {
      return { ...found, quantity: item.quantity, unitPrice: item.unitPrice ?? found.unitPrice, remark: item.remark || '' };
    }
    return {
      productId: item.productId, productCode: '', productName: `产品 ${item.productId}`, unitName: '',
      quantityPrecision: 0, referenceSalePrice: null, salesOrderItemId: null,
      quantity: item.quantity, unitPrice: item.unitPrice, remark: item.remark || '',
    } satisfies SalesDraftLine;
  }));
}

/** 清空全部临时状态，不保留上一次的编辑内容。 */
function resetState() {
  mode.value = 'readonly';
  loadError.value = '';
  customerLabel.value = '';
  existingOrderNo.value = '';
  existingOrderStatus.value = '';
  warehouseLabel.value = '';
  originalWarehouseLabel.value = '';
  lines.value = [];
  originalLines.value = [];
  rejectOpen.value = false;
  rejectReasons.value = [];
  rejectNote.value = '';
  rejectError.value = '';
  Object.keys(errors).forEach(key => delete errors[key]);
}

/** 进入编辑态；只复制一次原始数据，取消时整体还原。 */
function startEdit() {
  if (busy.value) return;
  mode.value = 'edit';
  Object.keys(errors).forEach(key => delete errors[key]);
}

/** 取消编辑并恢复 AI 生成内容，不记录任何修改。 */
function cancelEdit() {
  mode.value = 'readonly';
  lines.value = originalLines.value.map(line => ({ ...line }));
  warehouseLabel.value = originalWarehouseLabel.value;
  Object.assign(form, originalForm, { userNote: '' });
  Object.keys(errors).forEach(key => delete errors[key]);
}

/** 直接执行用户在详情页选择的操作。 */
function askConfirm(action: ConfirmAction) {
  if (busy.value) return;
  if (action === 'discard') { cancelEdit(); return; }
  if (action === 'close') { emit('update:open', false); return; }
  if (!validate()) return;
  void submitSave();
}

/** 成功后同时关闭两层弹窗，让遮罩和预览同步退出。 */
function finishConfirmation(conversationId: string, request: SalesDraftRequest) {
  emit('settled', { conversationId, callId: request.draftRequestId });
}

/** 弹窗关闭前拦截编辑态，避免误丢失修改。 */
function handleOpenChange(value: boolean) {
  if (value) {
    emit('update:open', true);
    return;
  }
  if (mode.value === 'edit' && !submitting.value) {
    askConfirm('close');
    return;
  }
  emit('update:open', false);
}

/** 保存销售单并把结果交回 AI；系统拒绝时同样交回原因。 */
async function submitSave() {
  const request = props.request;
  if (!request) return;
  const conversationId = props.conversationId;
  const header = currentHeader();
  const initial = initialHeader();
  const orderLines = lines.value.map(line => ({ ...line }));
  const update = isUpdate.value;
  const version = orderVersion.value;
  submitting.value = true;
  try {
    const note = form.userNote.trim();
    // 用户直接确认模型建议时只回“批准”；改了参数或补充说明才回差异和基线。
    const edited = !sameParams(initial, header) || note.length > 0;
    if (update) {
      const orderId = request.draft.salesOrderId;
      if (!orderId || version === null) {
        throw new Error('销售单信息不完整，请关闭后重新发起');
      }
      const updated = await updateSalesOrderFromDraft(orderId, version, header, orderLines);
      await resolveSalesDraft(conversationId, request, edited
        ? {
            status: 'edited', orderNo: updated.salesNo, salesOrderId: orderId, orderStatus: updated.status,
            finalParams: header, originalParams: initial, userNote: note,
          }
        : { status: 'updated', orderNo: updated.salesNo, salesOrderId: orderId, orderStatus: updated.status });
      toast.success(`销售单 ${updated.salesNo} 已保存`);
    } else {
      const created = await createSalesOrderFromDraft(header, orderLines);
      await resolveSalesDraft(conversationId, request, edited
        ? {
            status: 'edited', orderNo: created.salesNo, salesOrderId: created.salesOrderId,
            finalParams: header, originalParams: initial, userNote: note,
          }
        : { status: 'created', orderNo: created.salesNo, salesOrderId: created.salesOrderId });
      toast.success(`销售单 ${created.salesNo} 已创建`);
    }
    finishConfirmation(conversationId, request);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error)
      || (update ? '销售单保存失败，请调整后重试' : '销售单创建失败，请调整后重试'), conversationId, request, header);
  } finally {
    submitting.value = false;
  }
}

/** 把创建失败的原因交回模型，让 AI 依据真实约束调整。 */
async function reportFailure(message: string, conversationId: string, request: SalesDraftRequest, header: SalesDraftHeader) {
  toast.error(message);
  try {
    const orderId = request.draft.salesOrderId || undefined;
    await resolveSalesDraft(conversationId, request, {
      status: 'failed', error: message, finalParams: header,
      ...(orderId ? { salesOrderId: orderId } : {}),
    });
    finishConfirmation(conversationId, request);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把创建结果交回 AI，请重新发起销售需求');
  }
}

/** 打开驳回面板；驳回说明在确认前不提交。 */
function openReject() {
  if (busy.value) return;
  rejectOpen.value = true;
  rejectReasons.value = [];
  rejectNote.value = '';
  rejectError.value = '';
}

/** 驳回面板本身就是一次明确填写，直接提交，不再叠第三层确认。 */
function askConfirmReject() {
  if (!validateReject()) return;
  void submitReject();
}

/** 驳回规则：选「其他」必须写说明，否则原因与说明至少一项。 */
function validateReject() {
  if (rejectOtherSelected.value && !rejectNote.value.trim()) {
    rejectError.value = '选择「其他」时需要填写补充要求';
    return false;
  }
  if (!rejectReasons.value.length && !rejectNote.value.trim()) {
    rejectError.value = '请选择驳回原因或填写补充要求';
    return false;
  }
  return true;
}

/** 提交驳回结果并把原因交回模型。 */
async function submitReject() {
  const request = props.request;
  if (!request) return;
  const conversationId = props.conversationId;
  submitting.value = true;
  try {
    await resolveSalesDraft(conversationId, request, {
      status: 'rejected',
      ...(isUpdate.value && existingOrderNo.value ? { orderNo: existingOrderNo.value } : {}),
      ...(isUpdate.value && request.draft.salesOrderId ? { salesOrderId: request.draft.salesOrderId } : {}),
      reasons: rejectReasons.value,
      suggestion: rejectNote.value.trim(),
    });
    if (props.conversationId === conversationId && props.request?.draftRequestId === request.draftRequestId) rejectOpen.value = false;
    toast.success('已驳回销售草稿，AI 将按你的意见调整');
    emit('settled', { conversationId, callId: request.draftRequestId });
  } catch (error) {
    rejectError.value = getApiErrorMessage(error) || '驳回失败，请稍后重试';
    toast.error(rejectError.value);
  } finally {
    submitting.value = false;
  }
}

/** 按现有销售单表单规则校验编辑后的内容。 */
function validate() {
  Object.keys(errors).forEach(key => delete errors[key]);
  if (!form.warehouseId) errors.warehouseId = '请选择出库仓库';
  if (isPastDate(form.expectedDeliveryDate)) errors.expectedDeliveryDate = '预计发货日期不能早于今天';
  if (form.remark.trim().length > 500) errors.remark = '备注不能超过 500 个字符';
  if (!lines.value.length) errors.items = '请至少保留一行明细';
  lines.value.forEach((line, index) => {
    if (!line.productId) errors[`items.${index}.productId`] = '请选择产品';
    else if (lines.value.some((other, otherIndex) => otherIndex !== index && other.productId === line.productId)) errors[`items.${index}.productId`] = '同一产品不能重复添加';
    if (!Number.isFinite(Number(line.quantity)) || Number(line.quantity) <= 0) errors[`items.${index}.quantity`] = '销售数量必须大于 0';
    else if (!quantityPrecisionValid(line)) errors[`items.${index}.quantity`] = `数量最多保留 ${line.quantityPrecision} 位小数`;
    // 销售单必须有明确成交价，前端与工具参数保持一致，不允许留空。
    if (line.unitPrice === null || !Number.isFinite(Number(line.unitPrice)) || Number(line.unitPrice) < 0 || Number(line.unitPrice) > MAX_SAFE_MONEY) errors[`items.${index}.unitPrice`] = '请填写销售单价（0 到安全金额范围内）';
    if (line.remark.trim().length > 500) errors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
  });
  const keys = Object.keys(errors);
  if (keys.length) toast.error(errors[keys[0]]);
  return keys.length === 0;
}

/** 判断数量小数位是否符合产品精度。 */
function quantityPrecisionValid(line: SalesDraftLine) {
  const decimal = String(line.quantity).split('.')[1] || '';
  return decimal.length <= line.quantityPrecision;
}

/** 数量步长跟随产品精度，避免离散单位产生多余小数。 */
function quantityStep(line: SalesDraftLine) {
  return line.quantityPrecision <= 0 ? '1' : `0.${'0'.repeat(Math.max(line.quantityPrecision - 1, 0))}1`;
}

/** 编辑时切换产品：按编号回查名称、单位、精度与参考售价。 */
async function selectLineProduct(line: SalesDraftLine, value: string | number) {
  const productId = String(value);
  line.productId = productId;
  delete errors[`items.${lines.value.indexOf(line)}.productId`];
  if (!productId) return;
  const resolved = await findDraftLine(productId).catch(() => null);
  if (!resolved) {
    toast.error('该产品信息读取失败，请稍后重试');
    return;
  }
  line.productCode = resolved.productCode;
  line.productName = resolved.productName;
  line.unitName = resolved.unitName;
  line.quantityPrecision = resolved.quantityPrecision;
  line.referenceSalePrice = resolved.referenceSalePrice;
  // 换产品时按参考售价回填，用户仍可手动改价。
  line.unitPrice = resolved.referenceSalePrice;
  line.salesOrderItemId = null;
}

/** 删除一行明细；至少保留一行由提交校验兜底。 */
function removeLine(index: number) {
  lines.value.splice(index, 1);
  Object.keys(errors).forEach(key => delete errors[key]);
}

/** 新增一行空白明细，由用户选择产品。 */
function addLine() {
  lines.value.push({
    productId: '', productCode: '', productName: '', unitName: '', quantityPrecision: 0,
    referenceSalePrice: null, salesOrderItemId: null, quantity: 1, unitPrice: null, remark: '',
  });
}

/** 切换仓库后按编号回填名称，避免只显示编号。 */
async function selectWarehouse(value: string | number) {
  const warehouseId = String(value);
  form.warehouseId = warehouseId;
  delete errors.warehouseId;
  const warehouse = await getWarehouseDetail(warehouseId, { skipPageLoading: true }).catch(() => null);
  if (warehouse) {
    warehouseLabel.value = warehouse.warehouseName;
  }
}

/** 仓库下拉只列出启用仓库，与销售单表单一致。 */
async function fetchWarehouseOptions(keyword: string) {
  const warehouses = await listEnabledSalesWarehouseOptions(keyword, 10);
  return warehouses.map(item => ({ value: item.value, label: item.label }));
}

/** 客户由 AI 指定且不可更换，下拉不提供候选。 */
async function emptyOptions() {
  return [];
}

/** 产品下拉只列出启用状态的产品。 */
async function fetchProductOptions(keyword: string) {
  return searchDraftProducts(keyword);
}

/** 当前表单对应的业务参数，扣掉前端派生字段后交回模型。 */
function currentHeader(): SalesDraftHeader {
  return {
    customerId: customerId.value,
    warehouseId: form.warehouseId,
    expectedDeliveryDate: form.expectedDeliveryDate || null,
    remark: form.remark.trim() || null,
    items: lines.value.map(line => ({
      productId: line.productId,
      quantity: Number(line.quantity),
      unitPrice: line.unitPrice === null ? null : Number(line.unitPrice),
      remark: line.remark.trim() || null,
    })),
  };
}

/** 卡片打开时的业务参数；用户是否改过参数以它为基线。 */
function initialHeader(): SalesDraftHeader {
  return {
    customerId: customerId.value,
    warehouseId: originalForm.warehouseId,
    expectedDeliveryDate: originalForm.expectedDeliveryDate || null,
    remark: originalForm.remark.trim() || null,
    items: originalLines.value.map(line => ({
      productId: line.productId,
      quantity: Number(line.quantity),
      unitPrice: line.unitPrice === null ? null : Number(line.unitPrice),
      remark: line.remark.trim() || null,
    })),
  };
}

/** 参数是否完全一致；字段顺序固定，直接按序列化结果判断。 */
function sameParams(left: SalesDraftHeader, right: SalesDraftHeader) {
  return JSON.stringify(left) === JSON.stringify(right);
}

/** 明细行小计。 */
function lineAmount(line: SalesDraftLine) {
  return Number(line.quantity || 0) * Number(line.unitPrice || 0);
}

/** 单据状态编码转中文，仅修改态需要展示。 */
function statusLabel(status: string) {
  switch (status) {
    case 'DRAFT': return '草稿';
    case 'SUBMITTED': return '待审核';
    case 'APPROVED': return '待出库';
    case 'PARTIAL_OUTBOUND': return '部分出库';
    case 'OUTBOUND_DONE': return '已出库';
    case 'CANCELLED': return '已取消';
    default: return status;
  }
}

/** 金额展示保留两位小数。 */
function formatMoney(value: number) {
  return `￥${value.toFixed(2)}`;
}
</script>

<template>
  <Dialog :open="props.open" @update:open="handleOpenChange">
    <DialogContent
      placement="app-content"
      overlay-class="sales-approval-overlay"
      :inert="rejectOpen ? '' : undefined"
      class="order-form-dialog flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-6xl"
      data-sales-draft-dialog
    >
      <DialogHeader>
        <DialogTitle>{{ isUpdate ? 'AI 销售单修改' : 'AI 销售草稿' }}</DialogTitle>
        <DialogDescription>
          {{ isUpdate ? '确认销售单修改内容后保存；可编辑后补充说明，或驳回告知调整方向。' : '确认销售单内容后创建草稿；可编辑后补充说明，或驳回告知调整方向。' }}
          当前为只读预览，点击「编辑并补充说明」后才能修改客户、仓库、日期和明细。
        </DialogDescription>
      </DialogHeader>

      <DialogScrollArea>
        <div v-if="loading" class="flex h-40 items-center justify-center text-sm text-muted-foreground">正在读取草稿…</div>
        <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive">{{ loadError }}</div>
        <div v-else class="space-y-4 p-1">
          <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
            <div><span class="text-muted-foreground">销售单号</span><div class="mt-1 font-medium">{{ isUpdate ? (existingOrderNo || '读取中…') : '创建后自动生成' }}</div></div>
            <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">{{ isUpdate ? (existingOrderStatus ? statusLabel(existingOrderStatus) : '读取中…') : '保存后为草稿' }}</div></div>
            <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">{{ isUpdate ? 'AI 修改建议，尚未保存' : 'AI 生成，尚未写入系统' }}</div></div>
          </div>

          <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
            <div class="space-y-1">
              <Label>客户 <span v-if="!readonly" class="text-destructive">*</span></Label>
              <RemoteSearchSelect :model-value="customerId" :selected-label="customerLabel" :fetch-options="emptyOptions" disabled placeholder="AI 指定的客户" />
            </div>
            <div class="space-y-1">
              <Label>出库仓库 <span class="text-destructive">*</span></Label>
              <RemoteSearchSelect
                :model-value="form.warehouseId"
                :selected-label="warehouseLabel"
                :fetch-options="fetchWarehouseOptions"
                :disabled="readonly"
                placeholder="请选择仓库"
                search-placeholder="输入仓库编码或名称"
                :invalid="Boolean(errors.warehouseId)"
                @update:model-value="selectWarehouse"
              />
              <p v-if="errors.warehouseId" class="text-xs text-destructive">{{ errors.warehouseId }}</p>
            </div>
            <div class="space-y-1">
              <Label>预计发货</Label>
              <OrderDatePicker v-model="form.expectedDeliveryDate" :disabled="readonly" :invalid="Boolean(errors.expectedDeliveryDate)" />
              <p v-if="errors.expectedDeliveryDate" class="text-xs text-destructive">{{ errors.expectedDeliveryDate }}</p>
            </div>
          </div>

          <div class="space-y-1">
            <Label>备注</Label>
            <Textarea v-model="form.remark" rows="2" maxlength="500" :disabled="readonly" />
            <p v-if="errors.remark" class="text-xs text-destructive">{{ errors.remark }}</p>
          </div>

          <div class="rounded-md border border-border">
            <div class="flex min-h-11 items-center justify-between gap-3 border-b border-border px-3">
              <div>
                <strong class="text-sm">销售明细</strong>
                <span class="ml-2 text-xs text-muted-foreground">已选 {{ lines.length }} 项</span>
              </div>
              <Button v-if="!readonly" size="sm" variant="outline" type="button" @click="addLine">添加产品</Button>
            </div>
            <ScrollArea class="w-full">
              <Table class="order-line-table min-w-[860px] table-fixed" data-sales-draft-items>
                <colgroup>
                  <col class="w-[260px]" /><col class="w-[120px]" /><col class="w-[130px]" />
                  <col class="w-[120px]" /><col class="w-[180px]" /><col v-if="!readonly" class="w-[80px]" />
                </colgroup>
                <TableHeader>
                  <TableRow>
                    <TableHead>产品</TableHead>
                    <TableHead class="text-right">数量</TableHead>
                    <TableHead class="text-right">销售单价</TableHead>
                    <TableHead class="text-right">小计</TableHead>
                    <TableHead>明细备注</TableHead>
                    <TableHead v-if="!readonly" class="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-if="lines.length === 0">
                    <TableCell :colspan="readonly ? 5 : 6" class="h-24 text-center text-sm text-muted-foreground">还没有销售明细</TableCell>
                  </TableRow>
                  <TableRow v-for="(line, index) in lines" v-else :key="`${line.productId}-${index}`">
                    <TableCell class="align-top">
                      <RemoteSearchSelect
                        :model-value="line.productId"
                        :selected-label="line.productCode ? `${line.productCode} ${line.productName}` : (line.productName || line.productId)"
                        :fetch-options="fetchProductOptions"
                        :disabled="readonly"
                        placeholder="请选择产品"
                        search-placeholder="输入产品编码或名称"
                        :invalid="Boolean(errors[`items.${index}.productId`])"
                        @update:model-value="(value: string | number) => selectLineProduct(line, value)"
                      />
                      <p v-if="errors[`items.${index}.productId`]" class="mt-1 text-xs text-destructive">{{ errors[`items.${index}.productId`] }}</p>
                    </TableCell>
                    <TableCell class="align-top">
                      <div class="flex items-center gap-2">
                        <Input v-model.number="line.quantity" type="number" min="0" :step="quantityStep(line)" :disabled="readonly" class="min-w-0 text-right" />
                        <span v-if="line.unitName" class="shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span>
                      </div>
                      <p v-if="errors[`items.${index}.quantity`]" class="form-error text-center">{{ errors[`items.${index}.quantity`] }}</p>
                    </TableCell>
                    <TableCell class="align-top">
                      <div class="relative">
                        <span class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">￥</span>
                        <Input
                          :model-value="line.unitPrice ?? ''"
                          type="number"
                          min="0"
                          step="0.01"
                          :disabled="readonly"
                          class="price-input pl-8 text-center"
                          @update:model-value="(value: string | number) => (line.unitPrice = value === '' ? null : Number(value))"
                        />
                      </div>
                      <p v-if="errors[`items.${index}.unitPrice`]" class="text-xs text-destructive">{{ errors[`items.${index}.unitPrice`] }}</p>
                    </TableCell>
                    <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(lineAmount(line)) }}</TableCell>
                    <TableCell class="align-top">
                      <Input v-model="line.remark" maxlength="500" :disabled="readonly" placeholder="可选" />
                      <p v-if="errors[`items.${index}.remark`]" class="text-xs text-destructive">{{ errors[`items.${index}.remark`] }}</p>
                    </TableCell>
                    <TableCell v-if="!readonly" class="align-top text-center">
                      <Button variant="ghost" size="sm" class="text-destructive hover:text-destructive" @click="removeLine(index)">删除</Button>
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </ScrollArea>
            <div class="flex items-center justify-between gap-3 border-t border-border px-4 py-3 text-sm">
              <span class="text-muted-foreground">创建时系统将重新校验客户、数量精度与价格规则；提交审核时校验并锁定可用库存</span>
              <span class="shrink-0">草稿金额：<strong class="ml-2 text-sm">{{ formatMoney(totalAmount) }}</strong></span>
            </div>
          </div>

          <div v-if="!readonly" class="space-y-1">
            <Label>修改说明</Label>
            <Textarea v-model="form.userNote" rows="2" maxlength="500" placeholder="可选：说明为什么调整，AI 会结合变化分析影响" />
            <p class="text-xs text-muted-foreground">填写后 AI 能理解调整意图，而不只是看到数字变化。</p>
          </div>
        </div>
      </DialogScrollArea>

      <DialogFooter>
        <template v-if="readonly">
          <Button variant="outline" class="border-rose-200 bg-rose-50 text-rose-700 hover:border-rose-300 hover:bg-rose-100 hover:text-rose-800" :disabled="busy" @click="openReject">驳回并说明</Button>
          <Button variant="outline" :disabled="busy" @click="startEdit">编辑并补充说明</Button>
          <Button :disabled="busy" @click="askConfirm('create')">{{ isUpdate ? '确认保存' : '确认创建' }}</Button>
        </template>
        <template v-else>
          <Button variant="outline" class="border-rose-200 bg-rose-50 text-rose-700 hover:border-rose-300 hover:bg-rose-100 hover:text-rose-800" :disabled="busy" @click="openReject">驳回并说明</Button>
          <Button variant="outline" :disabled="busy" @click="askConfirm('discard')">取消编辑</Button>
          <Button :disabled="busy" @click="askConfirm('update')">{{ submitting ? '保存中…' : (isUpdate ? '保存修改' : '保存并创建') }}</Button>
        </template>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <RejectDraftDialog
    :open="rejectOpen"
    title="驳回销售草稿"
    description="说明不合适的地方，AI 会按你的意见重新规划。"
    :options="rejectOptions"
    :reasons="rejectReasons"
    :note="rejectNote"
    :error="rejectError"
    :busy="submitting"
    :note-required="rejectOtherSelected"
    :note-placeholder="rejectOtherSelected ? '选择「其他」时必须说明具体要求' : '例如：希望先发一半，剩余下周发'"
    confirm-text="提交驳回"
    confirm-test-id="sales-draft-reject-confirm"
    data-sales-draft-reject
    @update:open="rejectOpen = $event"
    @update:reasons="rejectReasons = $event; rejectError = ''"
    @update:note="rejectNote = $event; rejectError = ''"
    @confirm="askConfirmReject"
  />

</template>

<style>
[data-sales-draft-dialog][data-state='closed'],
.sales-approval-overlay[data-state='closed'] { animation: none !important; }
</style>
