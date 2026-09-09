<script setup lang="ts">
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';

export interface WarehouseDetailMetric {
  label: string;
  value: string;
  fullValue?: string;
  tone?: 'default' | 'positive' | 'danger' | 'muted';
  priority?: 'primary' | 'secondary' | 'tertiary';
}

defineProps<{
  items: WarehouseDetailMetric[];
}>();
</script>

<template>
  <div class="warehouse-detail-metrics" data-warehouse-detail-metrics>
    <div
      v-for="item in items"
      :key="item.label"
      class="warehouse-detail-metrics__item"
      :class="`warehouse-detail-metrics__item--${item.tone || 'default'}`"
      :data-priority="item.priority || 'secondary'"
      :aria-label="`${item.label}：${item.fullValue || item.value}`"
    >
      <span class="warehouse-detail-metrics__label">{{ item.label }}</span>
      <OverflowTooltip :text="item.fullValue || item.value" class="warehouse-detail-metrics__value" />
    </div>
  </div>
</template>

<style scoped>
.warehouse-detail-metrics { display: flex; min-width: 0; align-items: center; justify-content: flex-end; overflow: hidden; }
.warehouse-detail-metrics__item { display: flex; min-width: 0; align-items: baseline; gap: 6px; padding-inline: 10px; border-left: 1px solid var(--border); color: var(--foreground); font-size: 13px; line-height: 22px; white-space: nowrap; }
.warehouse-detail-metrics__label { flex: none; color: var(--muted-foreground); }
.warehouse-detail-metrics__value { min-width: 0; color: inherit; font-size: 15px; font-weight: 600; font-variant-numeric: tabular-nums; }
.warehouse-detail-metrics__item--positive { color: #16754c; }
.warehouse-detail-metrics__item--danger { color: #b42318; }
.warehouse-detail-metrics__item--muted { color: var(--muted-foreground); }
@media (max-width: 980px) { .warehouse-detail-metrics__item[data-priority='tertiary'] { display: none; } }
@media (max-width: 760px) { .warehouse-detail-metrics__item[data-priority='secondary'] { display: none; } }
</style>