<script setup lang="ts">
import { ShieldAlert } from 'lucide-vue-next';
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <div class="dashboard-empty-panel" role="status" aria-live="polite">
    <span class="dashboard-empty-panel__icon" aria-hidden="true">
      <ShieldAlert class="h-5 w-5" />
    </span>
    <div class="dashboard-empty-panel__body">
      <strong>{{ title }}</strong>
      <p v-if="description" class="dashboard-empty-panel__description">{{ description }}</p>
    </div>
  </div>
</template>

<style scoped>
.dashboard-empty-panel {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: color-mix(in srgb, var(--muted) 32%, white);
  padding: 14px 16px;
  color: var(--muted-foreground);
}

.dashboard-empty-panel__icon {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: white;
  color: var(--muted-foreground);
  box-shadow: inset 0 0 0 1px var(--border);
}

.dashboard-empty-panel__body {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.dashboard-empty-panel__body > strong {
  color: var(--foreground);
  font-size: 13px;
  font-weight: 700;
}

.dashboard-empty-panel__description {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
}
</style>
