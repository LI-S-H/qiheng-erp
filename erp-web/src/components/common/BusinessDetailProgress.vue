<script setup lang="ts">
export interface BusinessDetailProgressStep {
  label: string;
  state: 'done' | 'current' | 'pending' | 'cancelled';
  hint?: string;
}

defineProps<{ steps: BusinessDetailProgressStep[] }>();
</script>

<template>
  <ol class="business-detail-progress" :style="{ '--business-detail-progress-count': String(steps.length) }" aria-label="业务流程">
    <li v-for="(step, index) in steps" :key="step.label" class="business-detail-progress__step" :class="`is-${step.state}`">
      <span class="business-detail-progress__rail" aria-hidden="true"><i /></span>
      <span class="business-detail-progress__copy"><strong>{{ step.label }}</strong></span>
      <span class="business-detail-progress__dot">{{ index + 1 }}</span>
      <small v-if="step.hint" class="business-detail-progress__hint">{{ step.hint }}</small>
    </li>
  </ol>
</template>

<style scoped>
.business-detail-progress {
  display: grid;
  grid-template-columns: repeat(var(--business-detail-progress-count), minmax(0, 1fr));
  margin: 0;
  padding: 2px 4px 0;
  list-style: none;
}

.business-detail-progress__step {
  position: relative;
  display: grid;
  grid-template-rows: auto 24px auto;
  min-width: 0;
  gap: 3px;
  justify-items: center;
  color: var(--muted-foreground);
  text-align: center;
}

.business-detail-progress__rail {
  position: absolute;
  top: 32px;
  left: calc(50% + 12px);
  /* 延伸到下一节点圆点的左缘，避免节点之间出现断线。 */
  width: calc(100% - 24px);
  height: 2px;
  overflow: hidden;
  background: var(--border);
}

.business-detail-progress__rail i { display: block; width: 100%; height: 100%; background: var(--primary); transform: translateX(-100%); }
.business-detail-progress__step:last-child .business-detail-progress__rail { display: none; }
.business-detail-progress__dot { position: relative; z-index: 1; display: grid; width: 24px; height: 24px; place-items: center; border: 1px solid var(--border); border-radius: 999px; background: var(--card); font-size: 11px; font-weight: 650; }
.business-detail-progress__copy { display: block; min-width: 0; }
.business-detail-progress__copy strong, .business-detail-progress__hint { display: block; overflow: hidden; max-width: 100%; text-overflow: ellipsis; white-space: nowrap; }
.business-detail-progress__copy strong { font-size: 13px; font-weight: 600; line-height: 1.35; }
.business-detail-progress__hint { font-size: 11px; line-height: 1.35; }
.is-done .business-detail-progress__dot, .is-current .business-detail-progress__dot { border-color: var(--primary); background: var(--primary); color: var(--primary-foreground); }
.is-done .business-detail-progress__rail i { transform: translateX(0); }
.is-current { color: var(--foreground); }
.is-current .business-detail-progress__copy strong { color: var(--primary); }
.is-cancelled .business-detail-progress__dot { border-color: color-mix(in srgb, var(--destructive) 45%, var(--border)); color: var(--destructive); }

@media (max-width: 740px) {
  .business-detail-progress { grid-template-columns: 1fr; gap: 10px; }
  .business-detail-progress__step { grid-template-columns: 24px minmax(0, 1fr); grid-template-rows: auto auto; justify-items: start; column-gap: 10px; text-align: left; }
  .business-detail-progress__copy { grid-column: 2; grid-row: 1; padding-top: 2px; }
  .business-detail-progress__dot { grid-column: 1; grid-row: 1 / span 2; }
  .business-detail-progress__hint { grid-column: 2; grid-row: 2; }
  .business-detail-progress__rail { top: 24px; left: 11px; width: 2px; height: calc(100% + 10px); }
  .business-detail-progress__rail i { width: 100%; height: 100%; transform: translateY(-100%); }
  .is-done .business-detail-progress__rail i { transform: translateY(0); }
}
</style>
