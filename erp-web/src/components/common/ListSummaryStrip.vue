<script setup lang="ts">
import { computed } from 'vue';
import { Card } from '@/components/ui/card';

type SummaryTone = 'default' | 'positive' | 'warning' | 'danger';
type ResolvedSummaryTone = SummaryTone | 'info' | 'accent' | 'teal';

interface SummaryItem {
  key?: string;
  label: string;
  value: string | number;
  tone?: SummaryTone;
}

const props = withDefaults(defineProps<{
  items: readonly SummaryItem[];
  ariaLabel?: string;
}>(), {
  ariaLabel: '页面汇总',
});

const columnCount = computed(() => Math.min(Math.max(props.items.length, 1), 4));
const compactColumnCount = computed(() => Math.min(columnCount.value, 2));

const toneAlternatives: Record<ResolvedSummaryTone, readonly ResolvedSummaryTone[]> = {
  default: ['default', 'info', 'accent', 'teal', 'warning', 'positive', 'danger'],
  positive: ['positive', 'teal', 'info', 'accent', 'default', 'warning', 'danger'],
  warning: ['warning', 'info', 'accent', 'teal', 'default', 'positive', 'danger'],
  danger: ['danger', 'warning', 'accent', 'info', 'teal', 'default', 'positive'],
  info: ['info', 'accent', 'teal', 'default', 'warning', 'positive', 'danger'],
  accent: ['accent', 'info', 'teal', 'default', 'warning', 'positive', 'danger'],
  teal: ['teal', 'info', 'accent', 'default', 'warning', 'positive', 'danger'],
};

function preferredTone(item: SummaryItem, index: number): ResolvedSummaryTone {
  const semanticKey = `${item.key || ''} ${item.label}`.toLowerCase();
  if (/失败|风险|预警|异常|逾期|缺货|danger|risk|failed/.test(semanticKey)) return 'danger';
  if (/inbound-pending|outbound-pending|next-run|executing|processing|partial|入库未完成|出库未完成|待执行|部分执行/.test(semanticKey)) return 'info';
  if (/草稿|取消|停用|禁用|失效|draft|cancelled|disabled/.test(semanticKey)) return 'default';
  if (/待审核|待处理|审核中|submitted|pending/.test(semanticKey)) return 'warning';
  if (/启用|正常|已确认|已完成|可用|enabled|approved|confirmed|completed/.test(semanticKey)) return 'positive';
  if (/管理员|权限|模块|admin|permission|module/.test(semanticKey)) return 'accent';
  if (/绑定|用户|员工|引用|role-bound|users|employees|bound/.test(semanticKey)) return 'info';
  if (/库存|产品|仓库|供应商|客户|任务|stock|product|warehouse|supplier|customer|task/.test(semanticKey)) return 'teal';
  if (/金额|额度|销售|采购|毛利|收入|订单|总数|credit|amount|sales|purchase|margin|revenue|order|total/.test(semanticKey)) return 'info';
  if (item.tone && item.tone !== 'default') return item.tone;
  return ['info', 'accent', 'teal', 'default'][index % 4] as ResolvedSummaryTone;
}

const summaryTones = computed(() => {
  const occupied = new Set<ResolvedSummaryTone>();
  return props.items.map((item, index) => {
    const preferred = preferredTone(item, index);
    const resolved = toneAlternatives[preferred].find(tone => !occupied.has(tone)) || preferred;
    occupied.add(resolved);
    return resolved;
  });
});
</script>

<template>
  <Card
    class="summary-strip list-summary-strip !grid py-0"
    :class="{ 'list-summary-strip--empty': items.length === 0 }"
    :style="{
      '--list-summary-columns': columnCount,
      '--list-summary-compact-columns': compactColumnCount,
    }"
    :aria-label="ariaLabel"
    role="region"
    aria-live="polite"
    data-list-summary
  >
    <dl
      v-for="(item, index) in items"
      :key="item.key || item.label"
      class="summary-item list-summary-strip__item"
      :data-tone="summaryTones[index]"
    >
      <div class="list-summary-strip__head">
        <dt>{{ item.label }}</dt>
        <i aria-hidden="true" />
      </div>
      <dd>{{ item.value }}</dd>
    </dl>
    <p v-if="items.length === 0" class="list-summary-strip__empty">暂无汇总数据</p>
  </Card>
</template>

<style scoped>
.list-summary-strip {
  grid-template-columns: repeat(var(--list-summary-columns), minmax(0, 1fr));
  gap: 10px;
  overflow: visible;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.list-summary-strip__item {
  position: relative;
  display: grid;
  min-height: 104px;
  gap: 8px;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--muted) 36%, white), white 62%);
  padding: 14px 15px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  transition: border-color var(--motion-duration-fast) ease, box-shadow var(--motion-duration-fast) ease;
}

.list-summary-strip__item::after {
  position: absolute;
  inset: 0 0 auto;
  height: 3px;
  background: #64748b;
  content: '';
}

.list-summary-strip__item:hover {
  border-color: color-mix(in srgb, var(--primary) 28%, var(--border));
  box-shadow: 0 4px 12px rgb(15 23 42 / 7%);
}

.list-summary-strip__item[data-tone='positive'] {
  background: linear-gradient(135deg, #f0fdf7, white 62%);
}

.list-summary-strip__item[data-tone='warning'] {
  background: linear-gradient(135deg, #fffbeb, white 62%);
}

.list-summary-strip__item[data-tone='danger'] {
  background: linear-gradient(135deg, #fff5f7, white 62%);
}

.list-summary-strip__item[data-tone='info'] {
  background: linear-gradient(135deg, #f2f7ff, white 62%);
}

.list-summary-strip__item[data-tone='accent'] {
  background: linear-gradient(135deg, #faf5ff, white 62%);
}

.list-summary-strip__item[data-tone='teal'] {
  background: linear-gradient(135deg, #f0fdfa, white 62%);
}

.list-summary-strip__item[data-tone='positive']::after { background: #059669; }
.list-summary-strip__item[data-tone='warning']::after { background: #d97706; }
.list-summary-strip__item[data-tone='danger']::after { background: #e11d48; }
.list-summary-strip__item[data-tone='info']::after { background: #2563eb; }
.list-summary-strip__item[data-tone='accent']::after { background: #7c3aed; }
.list-summary-strip__item[data-tone='teal']::after { background: #0891b2; }

.list-summary-strip__empty {
  grid-column: 1 / -1;
  margin: 0;
  padding: 18px 16px;
  color: var(--muted-foreground);
  text-align: center;
}

.list-summary-strip__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.list-summary-strip__item dt {
  overflow: hidden;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-summary-strip__head > i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: #64748b;
}

.list-summary-strip__item[data-tone='positive'] .list-summary-strip__head > i { background: #059669; }
.list-summary-strip__item[data-tone='warning'] .list-summary-strip__head > i { background: #d97706; }
.list-summary-strip__item[data-tone='danger'] .list-summary-strip__head > i { background: #e11d48; }
.list-summary-strip__item[data-tone='info'] .list-summary-strip__head > i { background: #2563eb; }
.list-summary-strip__item[data-tone='accent'] .list-summary-strip__head > i { background: #7c3aed; }
.list-summary-strip__item[data-tone='teal'] .list-summary-strip__head > i { background: #0891b2; }

.list-summary-strip__item dd {
  align-self: end;
  overflow: hidden;
  margin: 0;
  color: #172033;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 980px) {
  .list-summary-strip {
    grid-template-columns: repeat(var(--list-summary-compact-columns), minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .list-summary-strip {
    grid-template-columns: repeat(var(--list-summary-compact-columns), minmax(0, 1fr));
  }

  .list-summary-strip__item {
    min-height: 98px;
  }
}
</style>
