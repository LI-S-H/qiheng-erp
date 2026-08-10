<script setup lang="ts">
import { Badge } from '@/components/ui/badge';

withDefaults(defineProps<{
  eyebrow: string;
  title: string;
  subtitle?: string;
  statusLabel?: string;
  statusClass?: string;
  metricColumns?: 2 | 3;
  variant?: 'default' | 'canvas';
}>(), {
  subtitle: '',
  statusLabel: '',
  statusClass: '',
  metricColumns: 2,
  variant: 'default',
});
</script>

<template>
  <section class="business-detail-hero" :class="`business-detail-hero--${variant}`">
    <div class="business-detail-hero__main">
      <span class="business-detail-hero__eyebrow">{{ eyebrow }}</span>
      <div class="business-detail-hero__title-row">
        <h3 class="business-detail-hero__title">{{ title }}</h3>
        <Badge v-if="statusLabel" variant="outline" :class="statusClass">{{ statusLabel }}</Badge>
      </div>
      <p v-if="subtitle" class="business-detail-hero__subtitle">{{ subtitle }}</p>
    </div>
    <div class="business-detail-hero__metrics" :class="`business-detail-hero__metrics--${metricColumns}`" aria-label="关键指标">
      <slot name="metrics" />
    </div>
  </section>
</template>

<style scoped>
.business-detail-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 20px;
  /* 让单据标识与右侧指标在同一视觉重心上，避免靠上或靠下失衡。 */
  align-items: center;
  overflow: hidden;
  padding: 14px 16px;
  border: 1px solid color-mix(in srgb, var(--primary) 18%, var(--border));
  border-left: 4px solid var(--primary);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--primary) 5%, var(--card));
}

.business-detail-hero__main { min-width: 0; }

.business-detail-hero__eyebrow {
  display: block;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .08em;
}

.business-detail-hero__title-row {
  display: flex;
  min-width: 0;
  gap: 10px;
  align-items: center;
  margin-top: 3px;
}

.business-detail-hero__title {
  overflow: hidden;
  margin: 0;
  color: var(--foreground);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.business-detail-hero__subtitle {
  overflow: hidden;
  margin: 5px 0 0;
  color: var(--muted-foreground);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.business-detail-hero__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(112px, auto));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 2px);
  background: var(--border);
}

.business-detail-hero__metrics--3 { grid-template-columns: repeat(3, minmax(112px, auto)); }

.business-detail-hero--canvas {
  gap: 24px;
  padding: 10px 8px;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.business-detail-hero--canvas .business-detail-hero__eyebrow { letter-spacing: .02em; }
.business-detail-hero--canvas .business-detail-hero__metrics { border-color: #dfe5ed; background: #dfe5ed; }
.business-detail-hero--canvas .business-detail-hero__metrics :slotted(.business-detail-hero__metric) { background: #fff; }

.business-detail-hero__metrics :slotted(.business-detail-hero__metric) {
  min-width: 112px;
  padding: 10px 12px;
  background: var(--card);
}

.business-detail-hero__metrics :slotted(.business-detail-hero__metric span) {
  display: block;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 16px;
}

.business-detail-hero__metrics :slotted(.business-detail-hero__metric strong) {
  display: block;
  margin-top: 4px;
  color: var(--foreground);
  font-size: 16px;
  font-weight: 650;
  line-height: 20px;
}

@media (max-width: 680px) {
  .business-detail-hero { grid-template-columns: 1fr; }
  .business-detail-hero__metrics, .business-detail-hero__metrics--3 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .business-detail-hero__metrics :slotted(.business-detail-hero__metric) { min-width: 0; }
}
</style>
