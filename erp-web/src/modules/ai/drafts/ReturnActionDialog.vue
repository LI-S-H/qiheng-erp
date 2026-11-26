<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import BusinessDetailHero from '@/components/common/BusinessDetailHero.vue';
import BusinessDetailWorkbenchCard from '@/components/common/BusinessDetailWorkbenchCard.vue';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import OrderNumberLink from '@/components/common/OrderNumberLink.vue';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';
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
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { isPastDate } from '@/shared/utils/date';
import type {
  ReturnHandlingType,
  ReturnOrderDetail,
  ReturnOrderStatus,
  ReturnReasonCode,
} from '@/modules/returns/types';
import {
  cancelReturn,
  getReturnDetailById,
  resolveReturnAction,
  submitReturn,
  updateReturnFromDraft,
} from './return-draft-api';
import type { ReturnActionRequest, ReturnDraftHeader } from './return-draft-types';

const props = defineProps<{
  open: boolean;
  request: ReturnActionRequest | null;
  conversationId: string;
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [context: { conversationId: string; callId: string }];
}>();

const loading = ref(false);
const loadError = ref('');
const submitting = ref(false);
const detail = ref<ReturnOrderDetail | null>(null);
const executionDate = ref('');
const reason = ref('');
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'warning' | 'destructive',
});

const isSubmit = computed(() => props.request?.action === 'SUBMIT');
const orderNo = computed(() => detail.value?.returnNo || props.request?.orderNo || '');
const returnTypeLabel = computed(() => detail.value?.returnType === 'PURCHASE_RETURN' ? '采购退回' : '销售退货');
const partyLabel = computed(() => detail.value?.returnType === 'PURCHASE_RETURN' ? '供应商' : '客户');
const processedQuantityLabel = computed(() => detail.value?.returnType === 'PURCHASE_RETURN' ? '已退货出库' : '已退货入库');
const pendingQuantityLabel = computed(() => detail.value?.returnType === 'PURCHASE_RETURN' ? '待退货出库' : '待退货入库');
const busy = computed(() => loading.value || submitting.value || Boolean(loadError.value));

const statusLabels: Record<ReturnOrderStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '待审核', APPROVED: '已审核',
  PARTIAL_EXECUTED: '部分执行', COMPLETED: '已完成', CANCELLED: '已取消',
};
const statusClassNames: Record<ReturnOrderStatus, string> = {
  DRAFT: 'border-slate-200 bg-slate-50 text-slate-600',
  SUBMITTED: 'border-blue-200 bg-blue-50 text-blue-700',
  APPROVED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  PARTIAL_EXECUTED: 'border-amber-200 bg-amber-50 text-amber-700',
  COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  CANCELLED: 'border-rose-200 bg-rose-50 text-rose-700',
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

/** 每次打开按当前动作重新读取退货单。 */
watch(() => [props.open, props.request?.actionRequestId], ([open]) => {
  if (open) {
    void load();
  } else {
    reset();
  }
}, { immediate: true });

async function load() {
  const request = props.request;
  if (!request) return;
  loading.value = true;
  loadError.value = '';
  try {
    const current = await getReturnDetailById(request.returnOrderId, request.orderNo);
    detail.value = current;
    executionDate.value = current.expectedExecutionDate || '';
    reason.value = request.reason || '';
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '退货单读取失败，请重新发起';
  } finally {
    loading.value = false;
  }
}

function reset() {
  detail.value = null;
  executionDate.value = '';
  reason.value = '';
  loadError.value = '';
  confirmState.open = false;
}

function askSubmit() {
  if (busy.value) return;
  if (!executionDate.value) {
    toast.error('提交审核前需要填写预计执行日期');
    return;
  }
  if (isPastDate(executionDate.value)) {
    toast.error('预计执行日期不能早于今天');
    return;
  }
  Object.assign(confirmState, {
    open: true,
    title: '确认提交审核？',
    description: `${orderNo.value || '退货单'} 将提交审核，审核通过后才生成退货入库/出库工作单。`,
    confirmText: '提交审核',
    variant: 'default',
  });
}

function askCancel() {
  if (busy.value) return;
  Object.assign(confirmState, {
    open: true,
    title: '确认取消退货单？',
    description: `${orderNo.value || '退货单'} 取消后不可恢复；已生成但未确认的退货工作单会一并作废。`,
    confirmText: '确认取消',
    variant: 'destructive',
  });
}

function runConfirm() {
  confirmState.open = false;
  if (isSubmit.value) {
    void submitOrder();
  } else {
    void cancelOrder();
  }
}

async function submitOrder() {
  const request = props.request;
  const current = detail.value;
  if (!request || !current) return;
  submitting.value = true;
  try {
    let latest = current;
    if (executionDate.value !== (current.expectedExecutionDate || '')) {
      const header = draftHeaderOf(current, executionDate.value);
      await updateReturnFromDraft(current.returnOrderId, current.version, header, header.items);
      latest = await getReturnDetailById(current.returnOrderId);
      detail.value = latest;
    }
    await submitReturn(latest.returnType, latest.returnOrderId, latest.version);
    await resolveReturnAction(props.conversationId, request, {
      status: 'submitted',
      returnOrderId: latest.returnOrderId,
      returnType: latest.returnType,
      orderNo: latest.returnNo,
      orderStatus: 'SUBMITTED',
      executionDate: executionDate.value || undefined,
    });
    toast.success(`退货单 ${latest.returnNo} 已提交审核`);
    emit('settled', { conversationId: props.conversationId, callId: request.actionRequestId });
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '提交审核失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

async function cancelOrder() {
  const request = props.request;
  const current = detail.value;
  if (!request || !current) return;
  submitting.value = true;
  try {
    await cancelReturn(current.returnType, current.returnOrderId, current.version, reason.value.trim());
    await resolveReturnAction(props.conversationId, request, {
      status: 'cancelled',
      returnOrderId: current.returnOrderId,
      returnType: current.returnType,
      orderNo: current.returnNo,
      orderStatus: 'CANCELLED',
      reason: reason.value.trim() || undefined,
    });
    toast.success(`退货单 ${current.returnNo} 已取消`);
    emit('settled', { conversationId: props.conversationId, callId: request.actionRequestId });
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '取消失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

async function reject() {
  const request = props.request;
  if (!request || busy.value) return;
  submitting.value = true;
  try {
    await resolveReturnAction(props.conversationId, request, {
      status: 'rejected',
      returnOrderId: request.returnOrderId,
      returnType: detail.value?.returnType || request.returnType || 'SALES_RETURN',
      orderNo: orderNo.value,
      reason: reason.value.trim() || undefined,
    });
    emit('settled', { conversationId: props.conversationId, callId: request.actionRequestId });
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  } finally {
    submitting.value = false;
  }
}

async function reportFailure(message: string) {
  const request = props.request;
  const current = detail.value;
  if (!request) return;
  toast.error(message);
  try {
    await resolveReturnAction(props.conversationId, request, {
      status: 'failed',
      returnOrderId: request.returnOrderId,
      returnType: current?.returnType || request.returnType || 'SALES_RETURN',
      orderNo: orderNo.value,
      error: message,
      orderParams: current ? currentReturnParams(current) : undefined,
    });
    emit('settled', { conversationId: props.conversationId, callId: request.actionRequestId });
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  }
}

function draftHeaderOf(current: ReturnOrderDetail, date: string): ReturnDraftHeader {
  return {
    returnOrderId: current.returnOrderId,
    returnType: current.returnType,
    sourceOrderId: current.sourceOrderId,
    warehouseId: current.warehouseId,
    expectedExecutionDate: date || null,
    handlingType: current.handlingType,
    reasonCode: current.reasonCode,
    returnReason: current.returnReason,
    remark: current.remark || null,
    items: current.items.map(item => ({
      sourceOrderItemId: item.sourceOrderItemId,
      requestedQty: item.requestedQty,
      remark: item.remark || null,
    })),
  };
}

function currentReturnParams(current: ReturnOrderDetail): Record<string, unknown> {
  return {
    sourceOrderId: current.sourceOrderId,
    warehouseId: current.warehouseId,
    expectedExecutionDate: current.expectedExecutionDate,
    handlingType: current.handlingType,
    reasonCode: current.reasonCode,
    returnReason: current.returnReason,
    remark: current.remark,
    items: current.items.map(item => ({
      sourceOrderItemId: item.sourceOrderItemId,
      requestedQty: item.requestedQty,
      remark: item.remark,
    })),
  };
}

function statusLabel(status: string) {
  return statusLabels[status as ReturnOrderStatus] || status;
}

function statusClass(status: string) {
  return statusClassNames[status as ReturnOrderStatus] || '';
}

function handlingLabel(value: ReturnHandlingType) {
  return handlingOptions.find(option => option.value === value)?.label || value;
}

function reasonLabel(value: ReturnReasonCode) {
  return reasonOptions.find(option => option.value === value)?.label || value;
}

function formatMoney(value: number) {
  return `￥${Number(value || 0).toFixed(2)}`;
}

function formatQuantity(value: number, precision = 2) {
  return Number(value || 0).toFixed(precision).replace(/\.0+$|(?<=\.[0-9])0+$/g, '');
}
</script>

<template>
  <Dialog :open="open" @update:open="$emit('update:open', $event)">
    <!-- 复用退货单业务页的详情工作台结构，提交/取消都在同一张单据视图里确认。 -->
    <DialogContent
      placement="app-content"
      :inert="confirmState.open ? '' : undefined"
      data-order-workbench
      data-return-detail-dialog
      class="return-order-workbench flex h-[min(770px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-5xl"
      data-ai-return-action-dialog
    >
      <DialogHeader class="sr-only">
        <DialogTitle>{{ isSubmit ? '退货单待提交审核' : '退货单待取消' }}</DialogTitle>
        <DialogDescription>{{ isSubmit ? '核对单据后提交审核。' : '核对单据后取消退货单。' }}</DialogDescription>
      </DialogHeader>

      <DialogScrollArea content-class="px-5 py-5 pr-6">
        <div v-if="loading" class="flex min-h-64 items-center justify-center gap-2 text-sm text-muted-foreground">
          <span class="page-loading-spinner" />正在读取退货单...
        </div>
        <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive" data-ai-return-action-error>
          {{ loadError }}
        </div>
        <div v-else-if="detail" class="space-y-5">
          <BusinessDetailHero
            :eyebrow="returnTypeLabel"
            :title="detail.returnNo"
            :subtitle="`${detail.partyCode} · ${detail.partyName} · ${detail.warehouseName}`"
            :status-label="statusLabel(detail.status)"
            :status-class="statusClass(detail.status)"
            :metric-columns="3"
            variant="canvas"
          >
            <template #metrics>
              <div class="business-detail-hero__metric"><span>退回金额</span><strong>{{ formatMoney(detail.totalAmount) }}</strong></div>
              <div class="business-detail-hero__metric"><span>商品明细</span><strong>{{ detail.items.length }} 项</strong></div>
              <div class="business-detail-hero__metric"><span>预计执行日期</span><strong>{{ detail.expectedExecutionDate || '未设置' }}</strong></div>
              <div class="business-detail-hero__metric"><span>退货仓库</span><strong>{{ detail.warehouseName }}</strong></div>
              <div class="business-detail-hero__metric"><span>{{ partyLabel }}</span><strong>{{ detail.partyName }}</strong></div>
              <div class="business-detail-hero__metric"><span>处理方式</span><strong>{{ handlingLabel(detail.handlingType) }}</strong></div>
            </template>
          </BusinessDetailHero>

          <BusinessDetailWorkbenchCard>
            <section class="return-workbench-section return-workbench-info">
              <h3 class="return-workbench-info__title">业务信息</h3>
              <dl class="return-workbench-info__facts">
                <div class="return-workbench-info__fact">
                  <dt>{{ partyLabel }}</dt>
                  <dd><strong>{{ detail.partyName }}</strong><small>{{ detail.partyCode }}</small></dd>
                </div>
                <div class="return-workbench-info__fact"><dt>预计执行日期</dt><dd>{{ detail.expectedExecutionDate || '未设置' }}</dd></div>
                <div class="return-workbench-info__fact"><dt>退回单号</dt><dd><code>{{ detail.returnNo }}</code></dd></div>
                <div class="return-workbench-info__fact"><dt>来源单</dt><dd><OrderNumberLink :value="detail.sourceOrderNo" label="来源单号" /></dd></div>
                <div class="return-workbench-info__fact"><dt>退货仓库</dt><dd>{{ detail.warehouseName }}</dd></div>
                <div class="return-workbench-info__fact"><dt>处理方式</dt><dd>{{ handlingLabel(detail.handlingType) }}</dd></div>
                <div class="return-workbench-info__fact"><dt>退回原因</dt><dd>{{ reasonLabel(detail.reasonCode) }}</dd></div>
              </dl>
            </section>

            <section class="return-workbench-section">
              <div class="mb-2 flex items-center justify-between gap-3">
                <div>
                  <h3 class="text-sm font-semibold">商品明细</h3>
                  <p class="mt-1 text-xs text-muted-foreground">核对原单履约、申请与审核数量，以及仓储实际处理进度。</p>
                </div>
                <span class="text-xs text-muted-foreground">共 {{ detail.items.length }} 项</span>
              </div>
              <ScrollArea class="purchase-order-line-scroll detail-table-floating w-full" aria-label="退回单商品明细">
                <Table class="return-detail-items min-w-[1180px] table-fixed" data-return-detail-items>
                  <colgroup>
                    <col class="w-[240px]" /><col class="w-[105px]" /><col class="w-[115px]" />
                    <col class="w-[115px]" /><col class="w-[120px]" /><col class="w-[120px]" />
                    <col class="w-[110px]" /><col class="w-[110px]" /><col class="w-[160px]" />
                  </colgroup>
                  <TableHeader>
                    <TableRow>
                      <TableHead>产品</TableHead>
                      <TableHead class="text-center">已履约</TableHead>
                      <TableHead class="text-center">申请退回</TableHead>
                      <TableHead class="text-center">审核退回</TableHead>
                      <TableHead class="text-center">{{ processedQuantityLabel }}</TableHead>
                      <TableHead class="text-center">{{ pendingQuantityLabel }}</TableHead>
                      <TableHead class="text-center">单价</TableHead>
                      <TableHead class="text-center">金额</TableHead>
                      <TableHead>明细备注</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    <TableRow v-for="item in detail.items" :key="item.returnOrderItemId">
                      <TableCell>
                        <code class="rounded bg-muted px-1.5 py-0.5 text-xs">{{ item.productCode }}</code>
                        <div class="mt-1">{{ item.productName }}</div>
                      </TableCell>
                      <TableCell class="text-center tabular-nums">{{ formatQuantity(item.sourceFulfilledQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell>
                      <TableCell class="text-center tabular-nums">{{ formatQuantity(item.requestedQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell>
                      <TableCell class="text-center tabular-nums">{{ formatQuantity(item.approvedQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell>
                      <TableCell class="text-center tabular-nums">{{ formatQuantity(item.processedQty, item.quantityPrecision) }} {{ item.unitName }}</TableCell>
                      <TableCell class="text-center font-medium tabular-nums" :class="item.approvedQty > item.processedQty ? 'text-amber-700' : 'text-emerald-700'">
                        {{ formatQuantity(Math.max(0, item.approvedQty - item.processedQty), item.quantityPrecision) }} {{ item.unitName }}
                      </TableCell>
                      <TableCell class="text-center tabular-nums">{{ formatMoney(item.unitPrice) }}</TableCell>
                      <TableCell class="text-center font-medium tabular-nums">{{ formatMoney(item.totalAmount) }}</TableCell>
                      <TableCell><OverflowTooltip :text="item.remark" fallback="未维护" class="block text-muted-foreground" /></TableCell>
                    </TableRow>
                  </TableBody>
                </Table>
              </ScrollArea>
            </section>

            <section class="return-workbench-section">
              <div v-if="isSubmit" class="space-y-1">
                <Label>预计执行日期 <span class="text-destructive">*</span></Label>
                <OrderDatePicker v-model="executionDate" :invalid="Boolean(executionDate && isPastDate(executionDate))" />
                <p class="text-xs text-muted-foreground">提交审核必须填写预计执行日期；可以是给对方的执行承诺。</p>
                <p v-if="executionDate && isPastDate(executionDate)" class="text-xs text-destructive">预计执行日期不能早于今天</p>
              </div>
              <div v-else class="space-y-1">
                <Label>取消原因</Label>
                <Textarea v-model="reason" rows="2" maxlength="500" placeholder="从用户的话里提取，可留空" />
              </div>
            </section>

            <section v-if="detail.statusReason || detail.returnReason || detail.remark" class="return-workbench-section">
              <div class="return-workbench-notes">
                <p v-if="detail.statusReason"><span>状态原因</span>{{ detail.statusReason }}</p>
                <p v-if="detail.returnReason"><span>原因说明</span>{{ detail.returnReason }}</p>
                <p v-if="detail.remark"><span>备注</span>{{ detail.remark }}</p>
              </div>
            </section>
          </BusinessDetailWorkbenchCard>
        </div>
      </DialogScrollArea>

      <DialogFooter class="items-center justify-between gap-3">
        <Button variant="outline" :disabled="busy" @click="reject">{{ isSubmit ? '暂不提交' : '暂不取消' }}</Button>
        <Button
          :variant="isSubmit ? 'default' : 'destructive'"
          :disabled="busy || Boolean(loadError)"
          data-testid="return-action-confirm"
          @click="isSubmit ? askSubmit() : askCancel()"
        >
          {{ isSubmit ? '提交审核' : '确认取消' }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <ConfirmDialog
    v-model:open="confirmState.open"
    placement="app-content"
    :title="confirmState.title"
    :description="confirmState.description"
    :confirm-text="confirmState.confirmText"
    :variant="confirmState.variant"
    :loading="submitting"
    data-ai-return-action-confirm-dialog
    @confirm="runConfirm"
  />
</template>

<style scoped>
.return-workbench-section { padding: 18px 20px; }
.return-workbench-section + .return-workbench-section { border-top: 1px solid #e5eaf0; }
.return-workbench-info__title { margin: 0 0 14px; color: var(--foreground); font-size: 14px; font-weight: 650; line-height: 20px; }
.return-workbench-info__facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 42px; margin: 0; }
.return-workbench-info__fact { display: grid; grid-template-columns: 88px minmax(0, 1fr); column-gap: 10px; align-items: start; min-width: 0; color: var(--foreground); font-size: 13px; line-height: 20px; }
.return-workbench-info__fact dt { color: var(--muted-foreground); font-size: inherit; white-space: nowrap; }
.return-workbench-info__fact dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.return-workbench-info__fact dd > strong { font-weight: 600; }
.return-workbench-info__fact dd > small { margin-left: 7px; color: var(--muted-foreground); font-size: inherit; }
.return-workbench-info__fact dd > code { font-size: inherit; }
.return-workbench-notes { display: grid; gap: 8px; color: var(--muted-foreground); font-size: 12px; line-height: 20px; }
.return-workbench-notes p { margin: 0; padding: 8px 10px; border-radius: 8px; background: var(--muted); }
.return-workbench-notes span { margin-right: 8px; color: var(--foreground); font-weight: 600; }

@media (max-width: 640px) {
  .return-workbench-info__facts { grid-template-columns: 1fr; gap: 9px; }
}
</style>