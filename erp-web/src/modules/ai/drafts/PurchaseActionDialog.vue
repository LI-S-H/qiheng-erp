<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { toast } from 'vue-sonner';
import { getApiErrorMessage } from '@/api/http';
import ConfirmDialog from '@/components/common/ConfirmDialog.vue';
import OrderDatePicker from '@/components/common/OrderDatePicker.vue';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogScrollArea, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { getPurchaseOrderDetail, updatePurchaseOrder, updatePurchaseOrderStatus } from '@/modules/purchase/api';
import type { PurchaseOrderDetail, PurchaseOrderFormPayload } from '@/modules/purchase/types';
import { resolvePurchaseAction, type PurchaseActionRequest } from './purchase-action';

const props = defineProps<{
  open: boolean;
  request: PurchaseActionRequest | null;
  conversationId: string;
}>();

const emit = defineEmits<{
  'update:open': [value: boolean];
  settled: [];
}>();

const loading = ref(false);
const loadError = ref('');
const submitting = ref(false);
const order = ref<PurchaseOrderDetail | null>(null);
const arrivalDate = ref('');
const reason = ref('');
const confirmState = reactive({
  open: false,
  title: '',
  description: '',
  confirmText: '',
  variant: 'default' as 'default' | 'warning' | 'destructive',
});

const isSubmit = computed(() => props.request?.action === 'SUBMIT');
const orderNo = computed(() => order.value?.purchaseNo || props.request?.orderNo || '');
const totalAmount = computed(() => order.value?.totalAmount ?? 0);
const busy = computed(() => loading.value || submitting.value || Boolean(loadError.value));

/** 每次打开都按当前动作重新读取单据。 */
watch(() => [props.open, props.request?.actionRequestId], ([open]) => {
  if (open) {
    void load();
  } else {
    reset();
  }
}, { immediate: true });

/** 读取单据详情；提交需要预计到货，取消需要原因。 */
async function load() {
  const request = props.request;
  if (!request) return;
  loading.value = true;
  loadError.value = '';
  try {
    const detail = await getPurchaseOrderDetail(request.purchaseOrderId);
    order.value = detail;
    arrivalDate.value = detail.expectedArrivalDate || '';
    reason.value = request.reason || '';
  } catch (error) {
    loadError.value = getApiErrorMessage(error) || '采购单读取失败，请重新发起';
  } finally {
    loading.value = false;
  }
}

/** 关闭后清空状态，避免沿用上一张单据。 */
function reset() {
  order.value = null;
  arrivalDate.value = '';
  reason.value = '';
  loadError.value = '';
  confirmState.open = false;
}

/** 提交前的二次确认。 */
function askSubmit() {
  if (busy.value) return;
  if (!arrivalDate.value) {
    toast.error('提交审核前需要填写预计到货日期');
    return;
  }
  Object.assign(confirmState, {
    open: true,
    title: '确认提交审核？',
    description: `采购单 ${orderNo.value} 将提交审核，提交后由主管审核，草稿不能再编辑。`,
    confirmText: '提交审核',
    variant: 'default',
  });
}

/** 取消前的二次确认，取消是终态。 */
function askCancel() {
  if (busy.value) return;
  Object.assign(confirmState, {
    open: true,
    title: '确认取消采购单？',
    description: `采购单 ${orderNo.value} 取消后不可恢复。如果已生成待确认入库单，会一并作废。`,
    confirmText: '确认取消',
    variant: 'destructive',
  });
}

/** 用户选择暂不执行，把原因交回模型。 */
async function reject() {
  const request = props.request;
  if (!request || busy.value) return;
  submitting.value = true;
  try {
    await resolvePurchaseAction(props.conversationId, request, {
      status: 'rejected',
      purchaseOrderId: request.purchaseOrderId,
      orderNo: orderNo.value,
      reason: reason.value.trim() || undefined,
    });
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  } finally {
    submitting.value = false;
  }
}

/** 执行二次确认选定的动作。 */
function runConfirm() {
  confirmState.open = false;
  if (isSubmit.value) {
    void submitOrder();
  } else {
    void cancelOrder();
  }
}

/** 提交审核：先补到货日期（有变化时），再提交。 */
async function submitOrder() {
  const request = props.request;
  const detail = order.value;
  if (!request || !detail) return;
  submitting.value = true;
  try {
    let current = detail;
    if (arrivalDate.value !== (detail.expectedArrivalDate || '')) {
      await updatePurchaseOrder(detail.purchaseOrderId, buildPayload(current, arrivalDate.value));
      current = await getPurchaseOrderDetail(detail.purchaseOrderId);
      order.value = current;
    }
    await updatePurchaseOrderStatus(current.purchaseOrderId, 'submit', current.version);
    await resolvePurchaseAction(props.conversationId, request, {
      status: 'submitted',
      purchaseOrderId: request.purchaseOrderId,
      orderNo: current.purchaseNo || orderNo.value,
      orderStatus: 'SUBMITTED',
      arrivalDate: arrivalDate.value || undefined,
    });
    toast.success(`采购单 ${current.purchaseNo} 已提交审核`);
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '提交审核失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

/** 取消采购单。 */
async function cancelOrder() {
  const request = props.request;
  const detail = order.value;
  if (!request || !detail) return;
  submitting.value = true;
  try {
    await updatePurchaseOrderStatus(detail.purchaseOrderId, 'cancel', detail.version);
    await resolvePurchaseAction(props.conversationId, request, {
      status: 'cancelled',
      purchaseOrderId: request.purchaseOrderId,
      orderNo: detail.purchaseNo || orderNo.value,
      orderStatus: 'CANCELLED',
      reason: reason.value.trim() || undefined,
    });
    toast.success(`采购单 ${detail.purchaseNo} 已取消`);
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    await reportFailure(getApiErrorMessage(error) || '取消失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

/** 接口拒绝时把原因和当前单据参数交回模型。 */
async function reportFailure(message: string) {
  const request = props.request;
  if (!request) return;
  toast.error(message);
  try {
    await resolvePurchaseAction(props.conversationId, request, {
      status: 'failed',
      purchaseOrderId: request.purchaseOrderId,
      orderNo: orderNo.value,
      error: message,
      orderParams: order.value ? currentParams(order.value) : undefined,
    });
    emit('settled');
    emit('update:open', false);
  } catch (error) {
    toast.error(getApiErrorMessage(error) || '未能把结果交回 AI，请重新发起');
  }
}

/** 组装更新请求：补日期时带完整单据参数。 */
function buildPayload(detail: PurchaseOrderDetail, arrival: string): PurchaseOrderFormPayload {
  return {
    version: detail.version,
    supplierId: detail.supplierId,
    warehouseId: detail.warehouseId,
    expectedArrivalDate: arrival || null,
    remark: detail.remark || '',
    items: detail.items.map(item => ({
      purchaseOrderItemId: item.purchaseOrderItemId,
      supplierProductId: item.supplierProductId ?? '',
      productId: item.productId,
      quantityPrecision: item.quantityPrecision,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      remark: item.remark || '',
    })),
  };
}

/** 当前单据参数：接口拒绝时供模型判断。 */
function currentParams(detail: PurchaseOrderDetail) {
  return {
    supplierId: detail.supplierId,
    warehouseId: detail.warehouseId,
    expectedArrivalDate: detail.expectedArrivalDate || null,
    remark: detail.remark || null,
    items: detail.items.map(item => ({
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      remark: item.remark || null,
    })),
  };
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
      :inert="confirmState.open ? '' : undefined"
      class="flex h-[min(620px,calc(100dvh-2rem))] max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-2xl"
      data-purchase-action-dialog
    >
      <DialogHeader>
        <DialogTitle>{{ isSubmit ? '提交采购单审核' : '取消采购单' }}</DialogTitle>
        <DialogDescription>
          {{ isSubmit ? '确认单据信息后提交审核；预计到货为空时需要先补上。' : '取消后不可恢复，请确认这张单据确实不再需要。' }}
        </DialogDescription>
      </DialogHeader>

      <DialogScrollArea>
        <div v-if="loading" class="flex h-32 items-center justify-center text-sm text-muted-foreground">正在读取采购单…</div>
        <div v-else-if="loadError" class="rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm text-destructive">{{ loadError }}</div>
        <div v-else-if="order" class="space-y-4 p-1">
          <div class="grid grid-cols-2 gap-3 rounded-md border border-border bg-muted/30 p-3 text-sm max-md:grid-cols-1">
            <div><span class="text-muted-foreground">采购单号</span><div class="mt-1 font-medium">{{ order.purchaseNo }}</div></div>
            <div><span class="text-muted-foreground">当前状态</span><div class="mt-1 font-medium">{{ order.status === 'DRAFT' ? '草稿' : order.status === 'SUBMITTED' ? '待审核' : order.status === 'APPROVED' ? '待入库' : order.status }}</div></div>
            <div><span class="text-muted-foreground">供应商</span><div class="mt-1 font-medium">{{ order.supplierCode }} {{ order.supplierName }}</div></div>
            <div><span class="text-muted-foreground">入库仓库</span><div class="mt-1 font-medium">{{ order.warehouseName }}</div></div>
            <div><span class="text-muted-foreground">采购金额</span><div class="mt-1 font-medium">{{ formatMoney(totalAmount) }}</div></div>
          </div>

          <div v-if="isSubmit" class="space-y-1">
            <Label>预计到货 <span class="text-destructive">*</span></Label>
            <OrderDatePicker v-model="arrivalDate" />
            <p class="text-xs text-muted-foreground">提交审核必须填写预计到货日期；可以是供应商承诺的交期。</p>
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
          @click="isSubmit ? askSubmit() : askCancel()"
        >
          {{ submitting ? '处理中…' : (isSubmit ? '提交审核' : '确认取消') }}
        </Button>
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