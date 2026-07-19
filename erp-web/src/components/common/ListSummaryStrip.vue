<script setup lang="ts">
import { computed } from 'vue';
import { Card } from '@/components/ui/card';

type SummaryTone = 'default' | 'positive' | 'warning' | 'danger';

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
</script>

<template>
  <Card
    class="summary-strip list-summary-strip !grid gap-0 py-0"
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
      v-for="item in items"
      :key="item.key || item.label"
      class="summary-item list-summary-strip__item"
      :data-tone="item.tone || 'default'"
    >
      <dt>{{ item.label }}</dt>
      <dd>{{ item.value }}</dd>
    </dl>
    <p v-if="items.length === 0" class="list-summary-strip__empty">暂无汇总数据</p>
  </Card>
</template>

<style scoped>
.list-summary-strip {
  grid-template-columns: repeat(var(--list-summary-columns), minmax(0, 1fr));
  border-color: color-mix(in srgb, var(--foreground) 9%, var(--border));
  background: color-mix(in srgb, var(--card) 98%, var(--primary));
  box-shadow:
    0 1px 2px rgb(15 23 42 / 5%),
    0 12px 30px -22px rgb(15 23 42 / 34%),
    inset 0 1px 0 rgb(255 255 255 / 78%);
}

.list-summary-strip__item {
  position: relative;
  margin: 0;
  transition: background-color var(--motion-duration-fast) ease;
}

.list-summary-strip__item::after {
  position: absolute;
  inset: auto 16px 0;
  height: 2px;
  border-radius: 999px 999px 0 0;
  background: color-mix(in srgb, var(--primary) 52%, transparent);
  content: '';
  opacity: 0;
  transition: opacity var(--motion-duration-fast) ease;
}

.list-summary-strip__item:hover {
  background: color-mix(in srgb, var(--primary) 3%, var(--card));
}

.list-summary-strip__item:hover::after {
  opacity: 1;
}

.list-summary-strip__item[data-tone='positive']::after {
  background: color-mix(in srgb, #059669 58%, transparent);
}

.list-summary-strip__item[data-tone='warning']::after {
  background: color-mix(in srgb, #d97706 58%, transparent);
}

.list-summary-strip__item[data-tone='danger']::after {
  background: color-mix(in srgb, #dc2626 56%, transparent);
}

.list-summary-strip__empty {
  grid-column: 1 / -1;
  margin: 0;
  padding: 18px 16px;
  color: var(--muted-foreground);
  text-align: center;
}

.list-summary-strip__item dt {
  color: var(--muted-foreground);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.45;
}

.list-summary-strip__item dd {
  margin: 6px 0 0;
  color: var(--foreground);
  font-size: 24px;
  font-weight: 650;
  line-height: 1.15;
  letter-spacing: -0.02em;
}

@media (max-width: 980px) {
  .list-summary-strip {
    grid-template-columns: repeat(var(--list-summary-compact-columns), minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .list-summary-strip {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
