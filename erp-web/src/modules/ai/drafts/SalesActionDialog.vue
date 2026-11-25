<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { getSalesOrderDetail, updateSalesOrder, updateSalesOrderStatus } from '@/modules/sales/api';
import type { SalesOrderDetail, SalesOrderFormPayload } from '@/modules/sales/types';
import { resolveSalesAction, type SalesActionRequest } from './sales-action';

const props = defineProps<{
  open: boolean;
  request: SalesActionRequest | null;
  conversationId: string;
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [context: { conversationId: string; callId: string }];
}>();

const loading = ref(false);
const loadError = ref('');
const submitting = ref(false);
const order = ref<SalesOrderDetail | null>(null);
const deliveryDate = ref('');
const reason = ref('');

const isSubmit = computed(() => props.request?.action === 'SUBMIT');
const orderNo = computed(() => order.value?.salesNo || props.request?.orderNo || '');
const totalAmount = computed(() => order.value?.totalAmount ?? 0);
const busy = computed(() => loading.value || submitting.value || Boolean(loadError.value));

/** 每次打开都按当前动作重新读取单据。 */
watch(() => [props.open, props.request?.actionRequestId], ([open]) => {
  if (open) {
    reset();
    void load();
  }
}, { immediate: true });

/** 读取单据详情；提交需要预计发货，取消需要原因。 */
async function load() {
  const request = props.request;
  if (!request) return;
  loading.value = true;
  loadError.value = '';
  try {
    const detail = await getSalesOrderDetail(request.salesOrderId);
    order.value = detail;
    deliveryDate.value = detail.expectedDeliveryDate || '';
    reason.value = request.reason || '';
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '销售单读取失败，请重新发起';
  } finally {
    loading.value = false;
  }
}

/** 打开前清空状态；关闭动画期间保留原单据内容。 */
function reset() {
  order.value = null;
  deliveryDate.value = '';
  reason.value = '';
  loadError.value = '';
}

/** 提交前保留发货日期校验，点击后直接执行。 */
function runConfirm() {
  if (busy.value) return;
  if (isSubmit.value) {
    if (!deliveryDate.value) { toast.error('提交审核前需要填写预计发货日期'); return; }
    void submitOrder();
  } else {
    void cancelOrder();
  }
}

/** 用户选择暂不执行，把原因交回模型。 */
async function reject() {
  const request = props.request;
  if (!request || busy.value) return;
  const conversationId = props.conversationId;
  submitting.value = true;
  try {
    await resolveSalesAction(conversationId, request, {
      status: 'rejected',
      salesOrderId: request.salesOrderId,
      orderNo: orderNo.value,
      reason: reason.value.trim() || undefined,
    });
    emit('settled', { conversationId, callId: request.actionRequestId });
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  } finally {
    submitting.value = false;
  }
}

/** 成功后同时关闭两层弹窗，让遮罩和预览同步退出。 */
function finishConfirmation(conversationId: string, request: SalesActionRequest) {
  emit('settled', { conversationId, callId: request.actionRequestId });
}

/** 提交审核：先补发货日期（有变化时），再提交。 */
async function submitOrder() {
  const request = props.request;
  const detail = order.value;
  if (!request || !detail) return;
  const conversationId = props.conversationId;
  const delivery = deliveryDate.value;
  const fallbackOrderNo = orderNo.value;
  submitting.value = true;
  try {
    let current = detail;
    if (delivery !== (detail.expectedDeliveryDate || '')) {
      await updateSalesOrder(detail.salesOrderId, buildPayload(current, delivery), { skipPageLoading: true });
      current = await getSalesOrderDetail(detail.salesOrderId);
      if (props.conversationId === conversationId && props.request?.actionRequestId === request.actionRequestId) order.value = current;
    }
    await updateSalesOrderStatus(current.salesOrderId, 'submit', current.version, { skipPageLoading: true });
    await resolveSalesAction(conversationId, request, {
      status: 'submitted', salesOrderId: request.salesOrderId,
      orderNo: current.salesNo || fallbackOrderNo, orderStatus: 'SUBMITTED',
      deliveryDate: delivery || undefined,
    });
    toast.success(`销售单 ${current.salesNo} 已提交审核`);
    finishConfirmation(conversationId, request);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '提交审核失败，请稍后重试', conversationId, request, detail, fallbackOrderNo);
  } finally {
    submitting.value = false;
  }
}

/** 取消销售单。 */
async function cancelOrder() {
  const request = props.request;
  const detail = order.value;
  if (!request || !detail) return;
  const conversationId = props.conversationId;
  const fallbackOrderNo = orderNo.value;
  const cancelReason = reason.value.trim();
  submitting.value = true;
  try {
    await updateSalesOrderStatus(detail.salesOrderId, 'cancel', detail.version, { skipPageLoading: true });
    await resolveSalesAction(conversationId, request, {
      status: 'cancelled', salesOrderId: request.salesOrderId,
      orderNo: detail.salesNo || fallbackOrderNo, orderStatus: 'CANCELLED',
      reason: cancelReason || undefined,
    });
    toast.success(`销售单 ${detail.salesNo} 已取消`);
    finishConfirmation(conversationId, request);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '取消失败，请稍后重试', conversationId, request, detail, fallbackOrderNo);
  } finally {
    submitting.value = false;
  }
}

/** 接口拒绝时把原因和当前单据参数交回模型。 */
async function reportFailure(message: string, conversationId: string, request: SalesActionRequest, detail: SalesOrderDetail, fallbackOrderNo: string) {
  toast.error(message);
  try {
    await resolveSalesAction(conversationId, request, {
      status: 'failed', salesOrderId: request.salesOrderId, orderNo: fallbackOrderNo,
      error: message, orderParams: currentParams(detail),
    });
    finishConfirmation(conversationId, request);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  }
}

/** 当前单据参数：接口拒绝时供模型判断。 */
function currentParams(detail: SalesOrderDetail) {
  return {
    customerId: detail.customerId, warehouseId: detail.warehouseId,
    expectedDeliveryDate: detail.expectedDeliveryDate || null, remark: detail.remark || null,
    items: detail.items.map(item => ({ productId: item.productId, quantity: item.quantity,
      unitPrice: item.unitPrice, remark: item.remark || null })),
  };
}

/** 组装更新请求：补日期时带完整单据参数。 */
function buildPayload(detail: SalesOrderDetail, delivery: string): SalesOrderFormPayload {
  return {
    version: detail.version,
    customerId: detail.customerId,
    warehouseId: detail.warehouseId,
    expectedDeliveryDate: delivery || null,
    remark: detail.remark || '',
    items: detail.items.map(item => ({
      salesOrderItemId: item.salesOrderItemId,
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      remark: item.remark || '',
    })),
  };
}

/** 状态中文口径与销售单列表一致。 */
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
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent
      placement="app-content"
      overlay-class="sales-approval-overlay"
      class="flex h-[min(620px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-2xl"
      data-sales-action-dialog
    >
      <DialogHeader>
        <DialogTitle>{{ isSubmit ? '提交销售单审核' : '取消销售单' }}</DialogTitle>
        <DialogDescription>
          {{ isSubmit ? '确认单据信息后提交审核；提交会校验并锁定可用库存，预计发货为空时需要先补上。' : '取消后不可恢复，已锁定的库存会释放，请确认这张单据确实不再需要。' }}
        </DialogDescription>
      </DialogHeader>

      <DialogScrollArea>
        <div v-if="loading" class="flex h-32 items-center justify-center text-sm text-muted-foreground">正在读取销售单…</div>
        <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive">{{ loadError }}</div>
        <div v-else-if="order" class="space-y-4 p-1">
          <div class="grid grid-cols-2 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
            <div><span class="text-muted-foreground">销售单号</span><div class="mt-1 font-medium">{{ order.salesNo }}</div></div>
            <div><span class="text-muted-foreground">当前状态</span><div class="mt-1 font-medium">{{ statusLabel(order.status) }}</div></div>
            <div><span class="text-muted-foreground">客户</span><div class="mt-1 font-medium">{{ order.customerCode }} {{ order.customerName }}</div></div>
            <div><span class="text-muted-foreground">出库仓库</span><div class="mt-1 font-medium">{{ order.warehouseName }}</div></div>
            <div><span class="text-muted-foreground">订单金额</span><div class="mt-1 font-medium">{{ formatMoney(totalAmount) }}</div></div>
          </div>

          <div v-if="isSubmit" class="space-y-1">
            <Label>预计发货 <span class="text-destructive">*</span></Label>
            <OrderDatePicker v-model="deliveryDate" />
            <p class="text-xs text-muted-foreground">提交审核必须填写预计发货日期；可以是给客户的交货承诺。</p>
          </div>

          <div v-else class="space-y-1">
            <Label>取消原因</Label>
            <Textarea v-model="reason" rows="2" maxlength="200" placeholder="可选：说明为什么取消，AI 会据此调整方案" />
          </div>
        </div>
      </DialogScrollArea>

      <DialogFooter class="items-center justify-between gap-3">
        <Button variant="outline" :disabled="busy" @click="reject">{{ isSubmit ? '暂不提交' : '暂不取消' }}</Button>
        <Button
          :variant="isSubmit ? 'default' : 'destructive'"
          :disabled="busy || !order"
          @click="runConfirm"
        >
          {{ submitting ? '处理中…' : (isSubmit ? '提交审核' : '确认取消') }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

</template>

<style>
[data-sales-action-dialog][data-state='closed'],
.sales-approval-overlay[data-state='closed'] { animation: none !important; }
</style>
