<script setup lang="ts">
import { computed } from 'vue';
import { ClipboardCheck, ClipboardX } from 'lucide-vue-next';
import AiSalesDraftHistory from '../drafts/AiSalesDraftHistory.vue';
import type { AiMessageContent } from '../types';

/** 草稿工具的一次调用或结果；调用带参数，结果带业务回执。 */
type OrderToolContent = Extract<AiMessageContent, { type: 'ORDER_TOOL' }>;

const props = defineProps<{ data: OrderToolContent['data']; showRow?: boolean }>();
const emit = defineEmits<{ open: [] }>();

/** 解析工具结果；还没有结果时返回空对象。 */
const result = computed<Record<string, unknown>>(() => {
  if (!props.data.result) return {};
  try {
    const parsed = JSON.parse(props.data.result);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
});

const pending = computed(() => !props.data.result);
const orderId = computed(() => {
  const value = result.value.orderId ?? result.value.salesOrderId;
  return typeof value === 'string' ? value : '';
});
const hasOrder = computed(() => orderId.value.length > 0);
const label = computed(() => {
  if (props.data.label) return props.data.label;
  switch (props.data.action) {
    case 'UPDATE': return '修改销售草稿单';
    case 'SUBMIT': return '提交销售草稿单';
    case 'CANCEL': return '取消销售草稿单';
    default: return '创建销售草稿单';
  }
});
const statusText = computed(() => {
  if (pending.value) return '等待确认';
  if (hasOrder.value) return '已完成';
  return result.value.status === 'rejected' ? '已驳回' : '失败';
});

/** 已有单据时交给历史卡片按单号查详情。 */
const historyData = computed(() => ({
  action: props.data.action,
  status: (result.value.status as 'created' | 'updated' | 'edited' | 'rejected' | 'failed' | 'submitted' | 'cancelled') || 'created',
  salesOrderId: orderId.value,
  orderNo: result.value.orderNo as string | undefined,
  orderStatus: result.value.orderStatus as string | undefined,
  error: result.value.error as string | undefined,
  reasons: result.value.reasons as string[] | undefined,
  suggestion: result.value.suggestion as string | undefined,
}));
</script>

<template>
  <div class="order-tool" data-ai-order-tool>
    <p v-if="props.showRow !== false" class="order-tool-row" :class="{ 'is-failed': !pending && !hasOrder }">
      <ClipboardX v-if="!pending && !hasOrder" class="size-4" aria-hidden="true" />
      <ClipboardCheck v-else class="size-4" aria-hidden="true" />
      <span>{{ label }}</span>
      <span class="order-tool-status">{{ statusText }}</span>
    </p>
    <button v-if="pending" type="button" class="order-tool-pending" data-testid="order-tool-open" @click="emit('open')">
      <span>{{ label }}待确认，点开查看完整信息后再确认或驳回</span>
      <span class="order-tool-action">查看详情</span>
    </button>
    <AiSalesDraftHistory v-else-if="hasOrder" :data="historyData" />
  </div>
</template>

<style scoped>
.order-tool { margin-top: 12px; }
.order-tool-row { display: flex; align-items: center; gap: 6px; color: var(--muted-foreground); font-size: 12px; }
.order-tool-row.is-failed { color: var(--destructive); }
.order-tool-status { margin-left: auto; }
.order-tool-pending { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--card); padding: 10px 12px; color: var(--foreground); font-size: 13px; text-align: left; }
.order-tool-action { color: var(--primary); white-space: nowrap; }
</style>