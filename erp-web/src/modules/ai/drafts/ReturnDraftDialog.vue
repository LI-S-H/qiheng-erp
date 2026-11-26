<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import AnchoredSelect from '@/components/common/AnchoredSelect.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogScrollArea,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { isPastDate } from '@/shared/utils/date';
import type { ReturnHandlingType, ReturnOrderStatus, ReturnReasonCode, ReturnType } from '@/modules/returns/types';
import RejectDraftDialog from './RejectDraftDialog.vue';
import {
  createReturnFromDraft,
  loadReturnDraftContext,
  resolveReturnDraft,
  updateReturnFromDraft,
  type ReturnDraftContext,
} from './return-draft-api';
import {
  RETURN_DRAFT_REJECT_REASONS,
  type ReturnDraftHeader,
  type ReturnDraftRequest,
} from './return-draft-types';

const props = defineProps<{
  open: boolean;
  request: ReturnDraftRequest | null;
  conversationId: string;
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [context: { conversationId: string; callId: string }];
}>();

interface ReturnLine {
  sourceOrderItemId: string;
  productCode: string;
  productName: string;
  unitName: string;
  quantityPrecision: number;
  sourceFulfilledQty: number;
  occupiedQty: number;
  availableReturnQty: number;
  unitPrice: number;
  requestedQty: number;
  remark: string;
}

const loading = ref(false);
const loadError = ref('');
const submitting = ref(false);
const context = ref<ReturnDraftContext | null>(null);
const lines = ref<ReturnLine[]>([]);
const originalHeader = ref<ReturnDraftHeader | null>(null);
const rejectOpen = ref(false);
const rejectReasons = ref<string[]>([]);
const rejectNote = ref('');
const rejectError = ref('');
const form = reactive({
  expectedExecutionDate: '',
  handlingType: 'REFUND' as ReturnHandlingType,
  reasonCode: 'QUALITY_ISSUE' as ReturnReasonCode,
  returnReason: '',
  remark: '',
});
const errors = reactive<Record<string, string>>({});

const isUpdate = computed(() => props.request?.action === 'UPDATE');
const draft = computed(() => props.request?.draft || null);
const returnType = computed<ReturnType>(() => draft.value?.returnType || 'SALES_RETURN');
const orderTitle = computed(() => returnType.value === 'SALES_RETURN' ? '销售退货单' : '采购退货单');
const returnTypeLabel = computed(() => returnType.value === 'SALES_RETURN' ? '销售退货' : '采购退回');
const partyLabel = computed(() => returnType.value === 'SALES_RETURN' ? '客户' : '供应商');
const busy = computed(() => loading.value || submitting.value || Boolean(loadError.value));
const draftAmount = computed(() => lines.value.reduce(
  (sum, line) => sum + Number(line.requestedQty || 0) * line.unitPrice,
  0,
));

const statusLabels: Record<ReturnOrderStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '待审核', APPROVED: '已审核',
  PARTIAL_EXECUTED: '部分执行', COMPLETED: '已完成', CANCELLED: '已取消',
};
const handlingOptions: Array<{ value: ReturnHandlingType; label: string }> = [
  { value: 'REFUND', label: '退款 / 退回货款' },
  { value: 'EXCHANGE', label: '换货' },
  { value: 'OTHER', label: '其他处理方式' },
];
const reasonOptions: Array<{ value: ReturnReasonCode; label: string }> = [
  { value: 'QUALITY_ISSUE', label: '质量问题' },
  { value: 'DAMAGED', label: '商品损坏' },
  { value: 'WRONG_ITEM', label: '商品错发' },
  { value: 'QUANTITY_ERROR', label: '数量错误' },
  { value: 'SPEC_MISMATCH', label: '规格不符' },
  { value: 'NO_LONGER_NEEDED', label: '不再需要' },
  { value: 'OTHER', label: '其他原因' },
];

/** 每次打开都按最新草稿重新加载，避免沿用上一次确认页的状态。 */
watch(() => [props.open, props.request?.draftRequestId], ([open]) => {
  if (open) {
    void load();
  } else {
    resetState();
  }
}, { immediate: true });

/** 读取来源单/退货单信息和可退明细。 */
async function load() {
  const request = props.request;
  if (!request) return;
  loading.value = true;
  loadError.value = '';
  try {
    const loaded = await loadReturnDraftContext(request.draft);
    context.value = loaded;
    originalHeader.value = JSON.parse(JSON.stringify(request.draft)) as ReturnDraftHeader;
    Object.assign(form, {
      expectedExecutionDate: request.draft.expectedExecutionDate || '',
      handlingType: request.draft.handlingType || 'REFUND',
      reasonCode: request.draft.reasonCode,
      returnReason: request.draft.returnReason,
      remark: request.draft.remark || '',
    });
    lines.value = request.draft.items.map(item => {
      const source = loaded.items.find(candidate => candidate.sourceOrderItemId === item.sourceOrderItemId);
      if (!source) {
        throw new Error(`来源明细 ${item.sourceOrderItemId} 已不可退，请重新查询`);
      }
      return {
        sourceOrderItemId: source.sourceOrderItemId,
        productCode: source.productCode,
        productName: source.productName,
        unitName: source.unitName,
        quantityPrecision: source.quantityPrecision,
        sourceFulfilledQty: source.sourceFulfilledQty,
        occupiedQty: source.occupiedQty,
        availableReturnQty: source.availableReturnQty,
        unitPrice: source.unitPrice,
        requestedQty: item.requestedQty,
        remark: item.remark || '',
      };
    });
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '退货草稿加载失败，请重新发起';
  } finally {
    loading.value = false;
  }
}

/** 关闭后清空全部临时状态。 */
function resetState() {
  loadError.value = '';
  context.value = null;
  originalHeader.value = null;
  lines.value = [];
  rejectOpen.value = false;
  rejectReasons.value = [];
  rejectNote.value = '';
  rejectError.value = '';
  Object.keys(errors).forEach(key => delete errors[key]);
}

/** 校验表头和明细；错误显示在字段旁，不提交注定失败的数据。 */
function validate(): boolean {
  Object.keys(errors).forEach(key => delete errors[key]);
  if (form.expectedExecutionDate && isPastDate(form.expectedExecutionDate)) {
    errors.expectedExecutionDate = '预计执行日期不得早于今天';
  }
  if (form.returnReason.trim().length === 0) {
    errors.returnReason = '请填写退货原因说明';
  } else if (form.returnReason.trim().length > 500) {
    errors.returnReason = '退货原因最多 500 个字符';
  }
  if (form.remark.trim().length > 500) {
    errors.remark = '备注最多 500 个字符';
  }
  if (lines.value.length === 0) {
    errors.items = '至少保留一行退货明细';
  }
  lines.value.forEach((line, index) => {
    if (!Number.isFinite(line.requestedQty) || line.requestedQty <= 0) {
      errors[`items.${index}.requestedQty`] = '数量必须大于 0';
      return;
    }
    const factor = 10 ** line.quantityPrecision;
    if (Math.abs(line.requestedQty * factor - Math.round(line.requestedQty * factor)) > 1e-8) {
      errors[`items.${index}.requestedQty`] = `数量最多保留 ${line.quantityPrecision} 位小数`;
    }
    if (line.requestedQty > line.availableReturnQty) {
      errors[`items.${index}.requestedQty`] = `不能超过剩余可退 ${line.availableReturnQty}`;
    }
  });
  return Object.keys(errors).length === 0;
}

function currentHeader(): ReturnDraftHeader {
  return {
    returnOrderId: draft.value?.returnOrderId || null,
    returnType: returnType.value,
    sourceOrderId: draft.value?.sourceOrderId || '',
    warehouseId: draft.value?.warehouseId || '',
    expectedExecutionDate: form.expectedExecutionDate || null,
    handlingType: form.handlingType,
    reasonCode: form.reasonCode,
    returnReason: form.returnReason.trim(),
    remark: form.remark.trim() || null,
    items: lines.value.map(line => ({
      sourceOrderItemId: line.sourceOrderItemId,
      requestedQty: line.requestedQty,
      remark: line.remark.trim() || null,
    })),
  };
}

function isEdited() {
  const original = originalHeader.value;
  if (!original) return false;
  return JSON.stringify(original) !== JSON.stringify(currentHeader());
}

/** 提交确认：先写业务表，再把处置结果交回模型。 */
async function confirm() {
  const request = props.request;
  if (!request || busy.value || !validate()) return;
  submitting.value = true;
  try {
    const header = currentHeader();
    const edited = isEdited();
    if (isUpdate.value) {
      const returnOrderId = context.value?.returnOrderId || request.draft.returnOrderId;
      if (!returnOrderId || context.value?.version === null || context.value?.version === undefined) {
        throw new Error('退货单信息已变化，请关闭后重新发起');
      }
      const updated = await updateReturnFromDraft(returnOrderId, context.value.version, header, header.items);
      await resolveReturnDraft(props.conversationId, request, edited
        ? {
            status: 'edited', orderNo: updated.returnNo, returnOrderId: updated.returnOrderId,
            returnType: updated.returnType, orderStatus: updated.status,
            finalParams: header, originalParams: originalHeader.value!, userNote: '',
          }
        : {
            status: 'updated', orderNo: updated.returnNo, returnOrderId: updated.returnOrderId,
            returnType: updated.returnType, orderStatus: updated.status,
          });
      toast.success(`${orderTitle.value} ${updated.returnNo} 已保存`);
    } else {
      const created = await createReturnFromDraft(header, header.items);
      await resolveReturnDraft(props.conversationId, request, edited
        ? {
            status: 'edited', orderNo: created.returnNo, returnOrderId: created.returnOrderId,
            returnType: created.returnType, orderStatus: created.status,
            finalParams: header, originalParams: originalHeader.value!, userNote: '',
          }
        : {
            status: 'created', orderNo: created.returnNo, returnOrderId: created.returnOrderId,
            returnType: created.returnType, orderStatus: created.status,
          });
      toast.success(`${orderTitle.value} ${created.returnNo} 已创建`);
    }
    emit('settled', { conversationId: props.conversationId, callId: request.draftRequestId });
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '退货单保存失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

/** 把失败原因交回模型，让 AI 说明业务规则。 */
async function reportFailure(message: string) {
  const request = props.request;
  if (!request) return;
  toast.error(message);
  try {
    await resolveReturnDraft(props.conversationId, request, {
      status: 'failed',
      returnType: returnType.value,
      error: message,
      finalParams: currentHeader(),
      ...(request.draft.returnOrderId ? {
        returnOrderId: context.value?.returnOrderId || request.draft.returnOrderId,
        orderNo: context.value?.returnNo || '',
      } : {}),
    });
    emit('settled', { conversationId: props.conversationId, callId: request.draftRequestId });
    emit('update:open', false);
  } catch (cause) {
    toast.error(getApiErrorMessage(cause) || '未能把结果交回 AI，请重新发起');
  }
}

function openReject() {
  if (busy.value) return;
  rejectReasons.value = [];
  rejectNote.value = '';
  rejectError.value = '';
  rejectOpen.value = true;
}

async function reject() {
  const request = props.request;
  if (!request || submitting.value) return;
  if (!rejectReasons.value.length && !rejectNote.value.trim()) {
    rejectError.value = '请选择驳回原因或填写补充要求';
    return;
  }
  submitting.value = true;
  try {
    await resolveReturnDraft(props.conversationId, request, {
      status: 'rejected',
      returnType: returnType.value,
      reasons: rejectReasons.value,
      suggestion: rejectNote.value.trim(),
      ...(request.draft.returnOrderId ? {
        returnOrderId: context.value?.returnOrderId || request.draft.returnOrderId,
        orderNo: context.value?.returnNo || '',
      } : {}),
    });
    rejectOpen.value = false;
    toast.success('已驳回退货草稿，AI 会根据说明调整');
    emit('settled', { conversationId: props.conversationId, callId: request.draftRequestId });
    emit('update:open', false);
  } catch (error) {
    rejectError.value = getApiErrorMessage(error) || '驳回失败，请稍后重试';
    toast.error(rejectError.value);
  } finally {
    submitting.value = false;
  }
}

function statusLabel(status: string) {
  return statusLabels[status as ReturnOrderStatus] || status;
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
</script>

<template>
  <Dialog :open="open" @update:open="$emit('update:open', $event)">
    <!-- 复用退货单业务页的新增/编辑弹窗结构与尺寸，避免 AI 确认页另起一套视觉。 -->
    <DialogContent
      placement="app-content"
      :inert="rejectOpen ? '' : undefined"
      class="flex h-[min(790px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-[74rem]"
      data-ai-return-draft-dialog
    >
      <DialogHeader>
        <DialogTitle>{{ isUpdate ? `编辑${orderTitle}` : `新建${orderTitle}` }}</DialogTitle>
        <DialogDescription>
          {{ isUpdate ? '确认后保存修改；来源单、退货仓库和金额由后端维护。' : 'AI 已按你的要求填好草稿，确认后保存；快照、金额、状态和审计字段由后端维护。' }}
        </DialogDescription>
      </DialogHeader>

      <DialogScrollArea>
        <div class="space-y-4 p-1">
          <div class="grid grid-cols-3 gap-3 rounded-md border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
            <div>
              <span class="text-muted-foreground">退回单号</span>
              <div class="mt-1 font-medium">{{ context?.returnNo || '保存后由系统生成' }}</div>
            </div>
            <div>
              <span class="text-muted-foreground">退回类型</span>
              <div class="mt-1 font-medium">{{ returnTypeLabel }}（固定）</div>
            </div>
            <div>
              <span class="text-muted-foreground">单据状态</span>
              <div class="mt-1 font-medium">
                {{ isUpdate ? statusLabel(context?.sourceStatus || '') : '保存后为草稿' }}
              </div>
            </div>
          </div>

          <div v-if="loading" class="flex min-h-64 items-center justify-center gap-2 text-sm text-muted-foreground">
            <span class="page-loading-spinner" />正在读取退货单...
          </div>
          <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive" data-ai-return-draft-error>
            {{ loadError }}
          </div>
          <template v-else>
            <div class="grid grid-cols-3 gap-4 max-lg:grid-cols-2 max-sm:grid-cols-1">
              <div class="space-y-1">
                <Label>{{ returnType === 'SALES_RETURN' ? '销售单' : '采购单' }} <span class="text-destructive">*</span></Label>
                <Input :model-value="context?.sourceOrderNo || ''" disabled />
                <p class="text-xs text-muted-foreground">来源单固定，不能更换</p>
              </div>
              <div class="space-y-1">
                <Label>{{ partyLabel }}</Label>
                <Input :model-value="context?.partyLabel || ''" disabled />
              </div>
              <div class="space-y-1">
                <Label>退货仓库 <span class="text-destructive">*</span></Label>
                <Input :model-value="context?.warehouseLabel || ''" disabled />
                <p class="text-xs text-muted-foreground">来源单仓库，不能修改</p>
              </div>
              <div class="space-y-1">
                <Label>预计执行日期</Label>
                <OrderDatePicker v-model="form.expectedExecutionDate" :invalid="Boolean(errors.expectedExecutionDate)" />
                <p v-if="errors.expectedExecutionDate" class="text-xs text-destructive">{{ errors.expectedExecutionDate }}</p>
              </div>
              <div class="space-y-1">
                <Label>处理方式 <span class="text-destructive">*</span></Label>
                <AnchoredSelect v-model="form.handlingType" :options="handlingOptions" />
              </div>
              <div class="space-y-1">
                <Label>退货原因 <span class="text-destructive">*</span></Label>
                <AnchoredSelect v-model="form.reasonCode" :options="reasonOptions" />
              </div>
            </div>

            <div class="grid grid-cols-2 gap-4 max-md:grid-cols-1">
              <div class="space-y-1">
                <Label>原因说明 <span v-if="form.reasonCode === 'OTHER'" class="text-destructive">*</span></Label>
                <Textarea v-model="form.returnReason" rows="3" placeholder="说明退货事实与补充原因" :aria-invalid="Boolean(errors.returnReason)" />
                <p v-if="errors.returnReason" class="text-xs text-destructive">{{ errors.returnReason }}</p>
              </div>
              <div class="space-y-1">
                <Label>备注</Label>
                <Textarea v-model="form.remark" rows="3" placeholder="可选，最多 500 字" :aria-invalid="Boolean(errors.remark)" />
                <p v-if="errors.remark" class="text-xs text-destructive">{{ errors.remark }}</p>
              </div>
            </div>

            <div class="rounded-md border">
              <div class="flex min-h-11 items-center justify-between gap-3 border-b px-3">
                <div>
                  <strong class="text-sm">退货明细</strong>
                  <span class="ml-2 text-xs text-muted-foreground">来源单和可退数量已由 AI 核对，只需确认本次退货数量</span>
                </div>
                <span class="shrink-0 text-xs text-muted-foreground">共 {{ lines.length }} 项</span>
              </div>
              <ScrollArea class="w-full">
                <Table class="order-line-table min-w-[1080px] table-fixed" data-return-form-items>
                  <colgroup>
                    <col class="w-[220px]" /><col class="w-[95px]" /><col class="w-[95px]" />
                    <col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[100px]" />
                    <col class="w-[105px]" /><col class="w-[130px]" /><col class="w-[60px]" />
                  </colgroup>
                  <TableHeader>
                    <TableRow>
                      <TableHead>产品</TableHead>
                      <TableHead class="text-right">已履约</TableHead>
                      <TableHead class="text-right">其他退货已占</TableHead>
                      <TableHead class="text-right">剩余可退</TableHead>
                      <TableHead class="text-right">申请数量</TableHead>
                      <TableHead class="text-right">原单价</TableHead>
                      <TableHead class="text-right">预计金额</TableHead>
                      <TableHead>明细备注</TableHead>
                      <TableHead class="text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    <TableRow v-for="(line, index) in lines" :key="line.sourceOrderItemId" :data-source-item-id="line.sourceOrderItemId">
                      <TableCell class="align-top">
                        <code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ line.productCode }}</code>
                        <div class="mt-1">{{ line.productName }}</div>
                      </TableCell>
                      <TableCell class="text-right tabular-nums">{{ formatQuantity(line.sourceFulfilledQty, line.quantityPrecision) }} {{ line.unitName }}</TableCell>
                      <TableCell class="text-right tabular-nums">{{ formatQuantity(line.occupiedQty, line.quantityPrecision) }} {{ line.unitName }}</TableCell>
                      <TableCell class="text-right tabular-nums">{{ formatQuantity(line.availableReturnQty, line.quantityPrecision) }} {{ line.unitName }}</TableCell>
                      <TableCell class="align-top">
                        <div class="flex items-center gap-2">
                          <Input
                            v-model.number="line.requestedQty"
                            type="number"
                            min="0"
                            :max="line.availableReturnQty"
                            :step="quantityStep(line.quantityPrecision)"
                            :class="['min-w-0 text-right', { 'border-destructive focus-visible:border-destructive': errors[`items.${index}.requestedQty`] }]"
                          />
                          <span v-if="line.unitName" class="shrink-0 text-xs text-muted-foreground">{{ line.unitName }}</span>
                        </div>
                        <p v-if="errors[`items.${index}.requestedQty`]" class="mt-1 text-xs text-destructive">{{ errors[`items.${index}.requestedQty`] }}</p>
                      </TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(line.unitPrice) }}</TableCell>
                      <TableCell class="text-right font-medium tabular-nums">{{ formatMoney(Number(line.requestedQty || 0) * line.unitPrice) }}</TableCell>
                      <TableCell class="align-top"><Input v-model="line.remark" maxlength="500" placeholder="可选" /></TableCell>
                      <TableCell class="text-center text-xs text-muted-foreground">固定</TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
              <div class="flex items-center justify-between border-t px-4 py-3 text-sm">
                <span v-if="errors.items" class="text-xs text-destructive">{{ errors.items }}</span>
                <span v-else class="text-muted-foreground">草稿不占用数量，提交后才占用</span>
                <span>预计退回金额：<strong class="ml-2 text-sm">{{ formatMoney(draftAmount) }}</strong></span>
              </div>
            </div>
          </template>
        </div>
      </DialogScrollArea>

      <DialogFooter class="items-center justify-between gap-3">
        <Button
          variant="outline"
          class="border-rose-200 bg-white text-rose-700 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-800"
          :disabled="busy"
          @click="openReject"
        >
          驳回
        </Button>
        <div class="flex items-center gap-2">
          <Button variant="outline" :disabled="submitting" @click="$emit('update:open', false)">取消</Button>
          <Button :disabled="busy" data-testid="return-draft-confirm" @click="confirm">
            {{ submitting ? '保存中' : isUpdate ? '保存修改' : '保存草稿' }}
          </Button>
        </div>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <RejectDraftDialog
    :open="rejectOpen"
    title="驳回退货草稿"
    description="选择原因或写清补充要求，AI 会据此调整，不会写入退货单。"
    :options="RETURN_DRAFT_REJECT_REASONS"
    :reasons="rejectReasons"
    :note="rejectNote"
    :error="rejectError"
    :busy="submitting"
    confirm-text="确认驳回"
    confirm-test-id="return-draft-reject-confirm"
    data-ai-return-draft-reject
    @update:open="rejectOpen = $event"
    @update:reasons="rejectReasons = $event; rejectError = ''"
    @update:note="rejectNote = $event; rejectError = ''"
    @confirm="reject"
  />
</template>