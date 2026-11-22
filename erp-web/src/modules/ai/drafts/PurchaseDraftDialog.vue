<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import RemoteSearchSelect from '@/components/common/RemoteSearchSelect.vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { MAX_SAFE_MONEY } from '@/shared/utils/money';
import { listEnabledWarehouseOptions } from '@/modules/purchase/api';
import { getWarehouseDetail } from '@/modules/warehouse/warehouses/api';
import {
  createPurchaseOrderFromDraft,
  findDraftLine,
  loadPurchaseDraftContext,
  resolvePurchaseDraft,
  searchDraftSupplierProducts,
} from './purchase-draft-api';
import {
  PURCHASE_DRAFT_REJECT_REASONS,
  type PurchaseDraftHeader,
  type PurchaseDraftLine,
  type PurchaseDraftRequest,
} from './purchase-draft-types';

const props = defineProps<{
  open: boolean;
  request: PurchaseDraftRequest | null;
  conversationId: string;
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [];
}>();

/** 确认动作：创建、保存编辑、放弃编辑。 */
type ConfirmAction = 'create' | 'update' | 'discard' | 'close';

const loading = ref(false);
const loadError = ref('');
const mode = ref<'readonly' | 'edit'>('readonly');
const submitting = ref(false);
const supplierLabel = ref('');
const warehouseLabel = ref('');
const originalWarehouseLabel = ref('');
const lines = ref<PurchaseDraftLine[]>([]);
const originalLines = ref<PurchaseDraftLine[]>([]);
const rejectOpen = ref(false);
const rejectReasons = ref<string[]>([]);
const rejectNote = ref('');
const rejectError = ref('');
const form = reactive({ warehouseId: '', expectedArrivalDate: '', remark: '', userNote: '' });
const originalForm = reactive({ warehouseId: '', expectedArrivalDate: '', remark: '' });
const errors = reactive<Record<string, string>>({});
const confirmState = reactive({ open: false, action: 'create' as ConfirmAction, title: '', description: '', confirmText: '', variant: 'default' as 'default' | 'warning' | 'destructive' });

const readonly = computed(() => mode.value === 'readonly');
const totalAmount = computed(() => lines.value.reduce((sum, line) => sum + lineAmount(line), 0));
const busy = computed(() => submitting.value || loading.value || Boolean(loadError.value));
const supplierId = computed(() => props.request?.draft.supplierId || '');
const rejectOptions = PURCHASE_DRAFT_REJECT_REASONS;
/** 选择「其他」时必须写清楚具体要求，否则模型无法据此调整。 */
const rejectOtherSelected = computed(() => rejectReasons.value.includes('其他'));

/** 每次打开都按最新草稿重新加载，避免沿用上一次确认页的状态。 */
watch(() => [props.open, props.request?.draftRequestId], ([open]) => {
  if (open) {
    void load();
  } else {
    resetState();
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
    const context = await loadPurchaseDraftContext(request.draft);
    supplierLabel.value = context.supplierLabel;
    warehouseLabel.value = context.warehouseLabel;
    originalWarehouseLabel.value = context.warehouseLabel;
    lines.value = context.lines.map(line => ({ ...line }));
    originalLines.value = context.lines.map(line => ({ ...line }));
    Object.assign(form, {
      warehouseId: request.draft.warehouseId,
      expectedArrivalDate: request.draft.expectedArrivalDate || '',
      remark: request.draft.remark || '',
      userNote: '',
    });
    Object.assign(originalForm, {
      warehouseId: request.draft.warehouseId,
      expectedArrivalDate: request.draft.expectedArrivalDate || '',
      remark: request.draft.remark || '',
    });
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '采购草稿加载失败，请重新发起采购需求';
  } finally {
    loading.value = false;
  }
}

/** 清空全部临时状态，不保留上一次的编辑内容。 */
function resetState() {
  mode.value = 'readonly';
  loadError.value = '';
  supplierLabel.value = '';
  warehouseLabel.value = '';
  originalWarehouseLabel.value = '';
  lines.value = [];
  originalLines.value = [];
  rejectOpen.value = false;
  rejectReasons.value = [];
  rejectNote.value = '';
  rejectError.value = '';
  confirmState.open = false;
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

/** 底部按钮与关闭按钮统一走二次确认，避免误操作。 */
function askConfirm(action: ConfirmAction) {
  if (busy.value) return;
  if (action === 'create') {
    if (!validate()) return;
    Object.assign(confirmState, {
      open: true, action, title: '确认创建采购单？',
      description: `将按当前内容创建采购单草稿，供应商为${supplierLabel.value}，共 ${lines.value.length} 行明细，合计 ${formatMoney(totalAmount.value)}。`,
      confirmText: '确认创建', variant: 'default',
    });
    return;
  }
  if (action === 'update') {
    if (!validate()) return;
    Object.assign(confirmState, {
      open: true, action, title: '保存修改并创建？',
      description: `修改内容将随采购单一起提交，并把变化和修改说明交回 AI 继续分析。合计 ${formatMoney(totalAmount.value)}。`,
      confirmText: '保存并创建', variant: 'default',
    });
    return;
  }
  if (action === 'discard') {
    Object.assign(confirmState, {
      open: true, action, title: '取消本次编辑？',
      description: '本次编辑全部取消，恢复为 AI 生成的内容，不会记录。',
      confirmText: '取消编辑', variant: 'warning',
    });
    return;
  }
  Object.assign(confirmState, {
    open: true, action, title: '放弃编辑并关闭？',
    description: '本次编辑全部取消，不会记录；草稿仍保留，可重新打开查看。',
    confirmText: '放弃并关闭', variant: 'warning',
  });
}

/** 执行二次确认选定的动作。 */
function runConfirm() {
  const action = confirmState.action;
  confirmState.open = false;
  if (action === 'discard') {
    cancelEdit();
  } else if (action === 'close') {
    emit('update:open', false);
  } else if (action === 'create') {
    void submitCreate('created');
  } else if (action === 'update') {
    void submitCreate('edited');
  }
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

/** 创建采购单并把结果交回 AI；系统拒绝时同样交回原因。 */
async function submitCreate(status: 'created' | 'edited') {
  const request = props.request;
  if (!request) return;
  submitting.value = true;
  try {
    const created = await createPurchaseOrderFromDraft(currentHeader(), lines.value);
    await resolvePurchaseDraft(props.conversationId, request, status === 'created'
      ? { status: 'created', orderNo: created.purchaseNo, purchaseOrderId: created.purchaseOrderId }
      : {
          status: 'edited', orderNo: created.purchaseNo, purchaseOrderId: created.purchaseOrderId,
          finalParams: currentHeader(), userNote: form.userNote.trim(),
        });
    toast.success(`采购单 ${created.purchaseNo} 已创建`);
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '采购单创建失败，请调整后重试');
  } finally {
    submitting.value = false;
  }
}

/** 把创建失败的原因交回模型，让 AI 依据真实约束调整。 */
async function reportFailure(message: string) {
  const request = props.request;
  if (!request) return;
  toast.error(message);
  try {
    await resolvePurchaseDraft(props.conversationId, request, {
      status: 'failed',
      error: message,
      finalParams: currentHeader(),
    });
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把创建结果交回 AI，请重新发起采购需求');
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

/** 切换驳回原因；取消「其他」时清掉必填提示。 */
function toggleReason(reason: string) {
  rejectReasons.value = rejectReasons.value.includes(reason)
    ? rejectReasons.value.filter(item => item !== reason)
    : [...rejectReasons.value, reason];
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
  submitting.value = true;
  try {
    await resolvePurchaseDraft(props.conversationId, request, {
      status: 'rejected',
      reasons: rejectReasons.value,
      suggestion: rejectNote.value.trim(),
    });
    rejectOpen.value = false;
    toast.success('已驳回采购草稿，AI 将按你的意见调整');
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '驳回失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

/** 按现有采购单表单规则校验编辑后的内容。 */
function validate() {
  Object.keys(errors).forEach(key => delete errors[key]);
  if (!form.warehouseId) errors.warehouseId = '请选择入库仓库';
  if (form.remark.trim().length > 500) errors.remark = '备注不能超过 500 个字符';
  if (!lines.value.length) errors.items = '请至少保留一行明细';
  lines.value.forEach((line, index) => {
    if (!line.productId) errors[`items.${index}.productId`] = '请选择产品';
    else if (lines.value.some((other, otherIndex) => otherIndex !== index && other.productId === line.productId)) errors[`items.${index}.productId`] = '同一产品不能重复添加';
    if (!line.supplierProductId) errors[`items.${index}.productId`] = '当前供应商未维护该产品的启用供货关系';
    if (!Number.isFinite(Number(line.quantity)) || Number(line.quantity) <= 0) errors[`items.${index}.quantity`] = '采购数量必须大于 0';
    else if (!quantityPrecisionValid(line)) errors[`items.${index}.quantity`] = `数量最多保留 ${line.quantityPrecision} 位小数`;
    if (!Number.isFinite(Number(line.unitPrice)) || Number(line.unitPrice) < 0 || Number(line.unitPrice) > MAX_SAFE_MONEY) errors[`items.${index}.unitPrice`] = '采购单价超出安全金额范围';
    if (line.remark.trim().length > 500) errors[`items.${index}.remark`] = '明细备注不能超过 500 个字符';
    if (line.minOrderQty > 0 && Number(line.quantity) < line.minOrderQty) errors[`items.${index}.quantity`] = `该产品最小起订量为 ${line.minOrderQty}`;
  });
  const keys = Object.keys(errors);
  if (keys.length) toast.error(errors[keys[0]]);
  return keys.length === 0;
}

/** 判断数量小数位是否符合产品精度。 */
function quantityPrecisionValid(line: PurchaseDraftLine) {
  const decimal = String(line.quantity).split('.')[1] || '';
  return decimal.length <= line.quantityPrecision;
}

/** 数量步长跟随产品精度，避免离散单位产生多余小数。 */
function quantityStep(line: PurchaseDraftLine) {
  return line.quantityPrecision <= 0 ? '1' : `0.${'0'.repeat(Math.max(line.quantityPrecision - 1, 0))}1`;
}

/** 编辑时切换产品：重新读取供货关系并回填名称、单位、精度与参考价。 */
async function selectLineProduct(line: PurchaseDraftLine, value: string | number) {
  const productId = String(value);
  line.productId = productId;
  delete errors[`items.${lines.value.indexOf(line)}.productId`];
  const supplier = supplierId.value;
  if (!supplier || !productId) return;
  const resolved = await findDraftLine(supplier, productId).catch(() => null);
  if (!resolved) {
    line.supplierProductId = null;
    toast.error('当前供应商未维护该产品的启用供货关系');
    return;
  }
  line.supplierProductId = resolved.supplierProductId;
  line.productCode = resolved.productCode;
  line.productName = resolved.productName;
  line.unitName = resolved.unitName;
  line.quantityPrecision = resolved.quantityPrecision;
  line.latestPurchasePrice = resolved.latestPurchasePrice;
  line.minOrderQty = resolved.minOrderQty;
  line.unitPrice = resolved.unitPrice;
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
    supplierProductId: null, latestPurchasePrice: null, minOrderQty: 0, quantity: 1, unitPrice: null, remark: '',
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

/** 仓库下拉只列出启用仓库，与采购单表单一致。 */
async function fetchWarehouseOptions(keyword: string) {
  const warehouses = await listEnabledWarehouseOptions(keyword, 10);
  return warehouses.map(item => ({ value: item.value, label: item.label }));
}

/** 供应商由 AI 指定且不可更换，下拉不提供候选。 */
async function emptyOptions() {
  return [];
}

/** 产品下拉限定当前供应商的启用供货产品。 */
async function fetchProductOptions(keyword: string) {
  const supplier = supplierId.value;
  if (!supplier) return [];
  return searchDraftSupplierProducts(supplier, keyword);
}

/** 当前表单对应的业务参数，扣掉前端派生字段后交回模型。 */
function currentHeader(): PurchaseDraftHeader {
  return {
    supplierId: supplierId.value,
    warehouseId: form.warehouseId,
    expectedArrivalDate: form.expectedArrivalDate || null,
    remark: form.remark.trim() || null,
    items: lines.value.map(line => ({
      productId: line.productId,
      quantity: Number(line.quantity),
      unitPrice: line.unitPrice === null ? null : Number(line.unitPrice),
      remark: line.remark.trim() || null,
    })),
  };
}

/** 明细行小计。 */
function lineAmount(line: PurchaseDraftLine) {
  return Number(line.quantity || 0) * Number(line.unitPrice || 0);
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
      :inert="confirmState.open || rejectOpen ? '' : undefined"
      class="order-form-dialog flex h-[min(780px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-6xl"
      data-purchase-draft-dialog
    >
      <DialogHeader>
        <DialogTitle>AI 采购草稿</DialogTitle>
        <DialogDescription>确认采购单内容后创建草稿；可编辑后补充说明，或驳回告知调整方向。</DialogDescription>
      </DialogHeader>

      <DialogScrollArea>
        <div v-if="loading" class="flex h-40 items-center justify-center text-sm text-muted-foreground">正在读取草稿…</div>
        <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive">{{ loadError }}</div>
        <div v-else class="space-y-4 p-1">
          <div class="grid grid-cols-3 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
            <div><span class="text-muted-foreground">采购单号</span><div class="mt-1 font-medium">创建后自动生成</div></div>
            <div><span class="text-muted-foreground">订单状态</span><div class="mt-1 font-medium">保存后为草稿</div></div>
            <div><span class="text-muted-foreground">创建来源</span><div class="mt-1 font-medium">AI 生成，尚未写入系统</div></div>
          </div>

          <div class="grid grid-cols-3 gap-4 max-md:grid-cols-1">
            <div class="space-y-1">
              <Label>供应商 <span v-if="!readonly" class="text-destructive">*</span></Label>
              <RemoteSearchSelect :model-value="supplierId" :selected-label="supplierLabel" :fetch-options="emptyOptions" disabled placeholder="AI 指定的供应商" />
            </div>
            <div class="space-y-1">
              <Label>入库仓库 <span class="text-destructive">*</span></Label>
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
              <Label>预计到货</Label>
              <OrderDatePicker v-model="form.expectedArrivalDate" :disabled="readonly" />
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
                <strong class="text-sm">采购明细</strong>
                <span class="ml-2 text-xs text-muted-foreground">已选 {{ lines.length }} 项</span>
              </div>
              <Button v-if="!readonly" size="sm" variant="outline" type="button" @click="addLine">添加产品</Button>
            </div>
            <ScrollArea class="w-full">
              <Table class="order-line-table min-w-[860px] table-fixed" data-purchase-draft-items>
                <colgroup>
                  <col class="w-[260px]" /><col class="w-[120px]" /><col class="w-[130px]" />
                  <col class="w-[120px]" /><col class="w-[180px]" /><col v-if="!readonly" class="w-[80px]" />
                </colgroup>
                <TableHeader>
                  <TableRow>
                    <TableHead>产品</TableHead>
                    <TableHead class="text-right">数量</TableHead>
                    <TableHead class="text-right">采购价</TableHead>
                    <TableHead class="text-right">小计</TableHead>
                    <TableHead>明细备注</TableHead>
                    <TableHead v-if="!readonly" class="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-if="lines.length === 0">
                    <TableCell :colspan="readonly ? 5 : 6" class="h-24 text-center text-sm text-muted-foreground">还没有采购明细</TableCell>
                  </TableRow>
                  <TableRow v-for="(line, index) in lines" v-else :key="`${line.productId}-${index}`">
                    <TableCell class="align-top">
                      <RemoteSearchSelect
                        :model-value="line.productId"
                        :selected-label="line.productCode ? `${line.productCode} ${line.productName}` : ''"
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
              <span class="text-muted-foreground">创建时系统将重新校验供货关系、数量精度与价格规则</span>
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
          <Button variant="outline" :disabled="busy" @click="openReject">驳回并说明</Button>
          <Button variant="outline" :disabled="busy" @click="startEdit">编辑并补充说明</Button>
          <Button :disabled="busy" @click="askConfirm('create')">确认创建</Button>
        </template>
        <template v-else>
          <Button variant="outline" :disabled="busy" @click="askConfirm('discard')">取消编辑</Button>
          <Button :disabled="busy" @click="askConfirm('update')">{{ submitting ? '创建中…' : '保存并创建' }}</Button>
        </template>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <Dialog :open="rejectOpen" @update:open="rejectOpen = $event">
    <DialogContent placement="app-content" overlay-class="bg-slate-950/45" class="sm:max-w-lg" data-purchase-draft-reject>
      <DialogHeader>
        <DialogTitle>驳回采购草稿</DialogTitle>
        <DialogDescription>说明不合适的地方，AI 会按你的意见重新规划。</DialogDescription>
      </DialogHeader>
      <div class="space-y-3">
        <div class="flex flex-wrap gap-2">
          <button
            v-for="reason in rejectOptions"
            :key="reason"
            type="button"
            class="rounded-md border px-3 py-1.5 text-sm transition-colors"
            :class="rejectReasons.includes(reason) ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted/40'"
            :aria-pressed="rejectReasons.includes(reason)"
            @click="toggleReason(reason)"
          >
            {{ reason }}
          </button>
        </div>
        <div class="space-y-1">
          <Label>补充要求 <span v-if="rejectOtherSelected" class="text-destructive">*</span></Label>
          <Textarea v-model="rejectNote" rows="3" maxlength="500" :placeholder="rejectOtherSelected ? '选择「其他」时必须说明具体要求' : '例如：希望分两批到货，首批 30 天内'" @input="rejectError = ''" />
        </div>
        <p v-if="rejectError" class="text-xs text-destructive">{{ rejectError }}</p>
      </div>
      <DialogFooter>
        <Button variant="outline" :disabled="submitting" @click="rejectOpen = false">取消</Button>
        <Button :disabled="submitting" @click="askConfirmReject">提交驳回</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <ConfirmDialog
    placement="app-content"
    :open="confirmState.open"
    :title="confirmState.title"
    :description="confirmState.description"
    :confirm-text="confirmState.confirmText"
    cancel-text="取消"
    :variant="confirmState.variant"
    :loading="submitting"
    @update:open="confirmState.open = $event"
    @confirm="runConfirm"
  />
</template>