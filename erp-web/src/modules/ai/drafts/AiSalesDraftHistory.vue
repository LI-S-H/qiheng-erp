<script setup lang="ts">
import { computed, ref } from 'vue';
import { ChevronDown, ClipboardCheck } from 'lucide-vue-next';
import { getSalesOrderDetail } from '@/modules/sales/api';
import type { SalesOrderDetail } from '@/modules/sales/types';

const props = defineProps<{
  data: {
    action?: 'CREATE' | 'UPDATE' | 'SUBMIT' | 'CANCEL';
    status: 'created' | 'updated' | 'edited' | 'rejected' | 'failed' | 'submitted' | 'cancelled';
    salesOrderId?: string;
    orderNo?: string;
    orderStatus?: string;
    changedFields?: string[];
    userNote?: string;
    reasons?: string[];
    suggestion?: string;
    error?: string;
    reason?: string;
  };
}>();

const expanded = ref(false);
const loading = ref(false);
const loadError = ref('');
const order = ref<SalesOrderDetail | null>(null);

/** 卡片标题按动作区分。 */
const title = computed(() => {
  switch (props.data.action) {
    case 'UPDATE': return '销售单修改';
    case 'SUBMIT': return '销售单提交';
    case 'CANCEL': return '销售单取消';
    default: return '销售单草稿';
  }
});

/** 状态标签：动作与结果一起决定文案。 */
const statusMeta = computed(() => {
  const key = `${props.data.action || 'CREATE'}:${props.data.status}`;
  const map: Record<string, { label: string; className: string }> = {
    'CREATE:created': { label: '已确认创建', className: 'border-emerald-200 bg-emerald-50 text-emerald-700' },
    'UPDATE:updated': { label: '已保存', className: 'border-sky-200 bg-sky-50 text-sky-700' },
    'CREATE:edited': { label: '修改后创建', className: 'border-sky-200 bg-sky-50 text-sky-700' },
    'UPDATE:edited': { label: '修改后保存', className: 'border-sky-200 bg-sky-50 text-sky-700' },
    'SUBMIT:submitted': { label: '已提交审核', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    'CANCEL:cancelled': { label: '已取消', className: 'border-muted bg-muted text-muted-foreground' },
    'CREATE:rejected': { label: '已驳回', className: 'border-muted bg-muted text-muted-foreground' },
    'UPDATE:rejected': { label: '修改已驳回', className: 'border-muted bg-muted text-muted-foreground' },
    'SUBMIT:rejected': { label: '暂不提交', className: 'border-muted bg-muted text-muted-foreground' },
    'CANCEL:rejected': { label: '暂不取消', className: 'border-muted bg-muted text-muted-foreground' },
    'CREATE:failed': { label: '创建被拒', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    'UPDATE:failed': { label: '修改被拒', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    'SUBMIT:failed': { label: '提交被拒', className: 'border-amber-200 bg-amber-50 text-amber-700' },
    'CANCEL:failed': { label: '取消被拒', className: 'border-amber-200 bg-amber-50 text-amber-700' },
  };
  return map[key] || { label: '已处理', className: 'border-muted bg-muted text-muted-foreground' };
});

/** 只有真正生成了销售单才有明细可看。 */
const hasOrder = computed(() => Boolean(props.data.salesOrderId));

/** 把变更路径翻译成用户能看懂的说法，模型侧仍然拿原始路径。 */
const changedLabels = computed(() => (props.data.changedFields || []).map(fieldLabel));

const HEADER_LABELS: Record<string, string> = {
  customerId: '客户',
  warehouseId: '出库仓库',
  expectedDeliveryDate: '预计发货',
  remark: '备注',
};

const ITEM_LABELS: Record<string, string> = {
  productId: '产品',
  quantity: '数量',
  unitPrice: '销售单价',
  remark: '明细备注',
};

/** 单条路径的展示文案：表头字段直接换名，明细按行号。 */
function fieldLabel(path: string) {
  if (HEADER_LABELS[path]) return HEADER_LABELS[path];
  if (path === 'items') return '明细行数';
  const match = /^items\[(\d+)\](?:\.(\w+))?$/.exec(path);
  if (match) {
    const row = `第 ${Number(match[1]) + 1} 行`;
    return match[2] ? `${row}${ITEM_LABELS[match[2]] || match[2]}` : row;
  }
  return path;
}

/** 每次展开都按单号重查，卡片状态始终和销售单当前状态一致。 */
async function toggle() {
  if (!hasOrder.value || loading.value) return;
  if (expanded.value) {
    expanded.value = false;
    return;
  }
  // 先取明细再展开：展开动画直接过渡到完整内容高度，不会先撑开再跳高。
  loading.value = true;
  loadError.value = '';
  try {
    order.value = await getSalesOrderDetail(props.data.salesOrderId!);
  } catch {
    loadError.value = '销售单明细读取失败';
  } finally {
    loading.value = false;
  }
  expanded.value = true;
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
  <section class="draft-history" data-ai-sales-draft-history>
    <div class="draft-history-head">
      <span class="draft-history-icon" aria-hidden="true"><ClipboardCheck class="size-4" /></span>
      <div class="draft-history-title">
        <span class="draft-history-label">{{ title }}</span>
        <span class="draft-history-badge" :class="statusMeta.className">{{ statusMeta.label }}</span>
        <span v-if="data.orderNo" class="draft-history-no">{{ data.orderNo }}</span>
      </div>
      <button v-if="hasOrder" type="button" class="draft-history-toggle" :aria-expanded="expanded" @click="toggle">
        {{ loading ? '读取中…' : expanded ? '收起明细' : '查看明细' }}
        <ChevronDown class="size-4 transition-transform" :class="{ 'rotate-180': expanded }" aria-hidden="true" />
      </button>
    </div>

    <p v-if="data.error" class="draft-history-note is-error">{{ data.error }}</p>
    <p v-if="data.reason" class="draft-history-note">原因：{{ data.reason }}</p>
    <p v-if="data.reasons?.length" class="draft-history-note">驳回原因：{{ data.reasons.join('、') }}</p>
    <p v-if="data.suggestion" class="draft-history-note">补充要求：{{ data.suggestion }}</p>
    <p v-if="changedLabels.length" class="draft-history-note">修改内容：{{ changedLabels.join('、') }}</p>
    <p v-if="data.userNote" class="draft-history-note">用户说明：{{ data.userNote }}</p>

    <div class="draft-history-collapse" :class="{ 'is-open': expanded }">
      <div class="draft-history-collapse-inner">
        <div class="draft-history-body">
      <p v-if="loading" class="draft-history-placeholder">正在读取明细…</p>
      <p v-else-if="loadError" class="draft-history-placeholder is-error">{{ loadError }}</p>
      <template v-else-if="order">
        <dl class="draft-history-meta">
          <div><dt>客户</dt><dd>{{ order.customerCode }} {{ order.customerName }}</dd></div>
          <div><dt>出库仓库</dt><dd>{{ order.warehouseName }}</dd></div>
          <div><dt>预计发货</dt><dd>{{ order.expectedDeliveryDate || '未指定' }}</dd></div>
          <div><dt>状态</dt><dd>{{ statusLabel(order.status) }}</dd></div>
          <div class="is-wide"><dt>备注</dt><dd class="is-wrap">{{ order.remark || '无' }}</dd></div>
        </dl>
        <table class="draft-history-table">
          <thead>
            <tr><th>产品</th><th>数量</th><th>销售单价</th><th>小计</th><th>已锁定</th><th>已出库</th><th>明细备注</th></tr>
          </thead>
          <tbody>
            <tr v-for="(line, index) in order.items" :key="`${line.productId}-${index}`">
              <td>{{ line.productCode }} {{ line.productName }}</td>
              <td>{{ line.quantity }}{{ line.unitName }}</td>
              <td>{{ formatMoney(line.unitPrice) }}</td>
              <td>{{ formatMoney(line.totalAmount) }}</td>
              <td>{{ line.lockedQty }}</td>
              <td>{{ line.outboundQty }}</td>
              <td>{{ line.remark || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="draft-history-total">合计 <strong>{{ formatMoney(order.totalAmount) }}</strong></p>
      </template>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.draft-history {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  padding: 10px 12px;
  margin-top: 6px;
}
.draft-history-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.draft-history-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
}
.draft-history-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
  font-size: 13px;
}
.draft-history-label {
  font-weight: 600;
}
.draft-history-badge {
  border: 1px solid;
  border-radius: 999px;
  padding: 0 8px;
  font-size: 11px;
  line-height: 18px;
}
.draft-history-no {
  color: var(--muted-foreground);
  font-size: 12px;
}
.draft-history-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 0;
  background: none;
  padding: 2px 4px;
  color: var(--muted-foreground);
  font-size: 12px;
  cursor: pointer;
}
.draft-history-toggle:hover {
  color: var(--foreground);
}
.draft-history-note {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--muted-foreground);
}
.draft-history-note.is-error {
  color: var(--destructive);
}
.draft-history-collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 220ms cubic-bezier(0.22, 1, 0.36, 1);
}
.draft-history-collapse.is-open {
  grid-template-rows: 1fr;
}
.draft-history-collapse-inner {
  min-height: 0;
  overflow: hidden;
}
.draft-history-body {
  margin-top: 8px;
  border-top: 1px solid var(--border);
  padding-top: 8px;
}

@media (prefers-reduced-motion: reduce) {
  .draft-history-collapse {
    transition: none;
  }
}
.draft-history-placeholder {
  margin: 0;
  font-size: 12px;
  color: var(--muted-foreground);
}
.draft-history-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  margin: 0 0 8px;
  font-size: 12px;
}
.draft-history-meta div {
  display: flex;
  gap: 6px;
  min-width: 0;
}
.draft-history-meta dt {
  color: var(--muted-foreground);
  flex-shrink: 0;
}
.draft-history-meta dd {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.draft-history-meta .is-wide {
  grid-column: 1 / -1;
}
.draft-history-meta .is-wrap {
  overflow: visible;
  white-space: normal;
  line-height: 1.6;
}
.draft-history-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.draft-history-table th,
.draft-history-table td {
  border-bottom: 1px solid var(--border);
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}
.draft-history-table th {
  color: var(--muted-foreground);
  font-weight: 500;
}
.draft-history-total {
  margin: 8px 0 0;
  text-align: right;
  font-size: 12px;
}
</style>