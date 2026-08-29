<script setup lang="ts">
import { ShieldAlert } from 'lucide-vue-next';
import type { DashboardEmptyPanelProps } from '@/modules/dashboard/types';

defineProps<DashboardEmptyPanelProps>();
</script>

<template>
  <div class="dashboard-empty-panel" role="status" aria-live="polite">
    <span class="dashboard-empty-panel__icon" aria-hidden="true">
      <ShieldAlert class="h-5 w-5" />
    </span>
    <div class="dashboard-empty-panel__body">
      <strong>{{ title }}</strong>
      <p v-if="description" class="dashboard-empty-panel__description">{{ description }}</p>
      <div v-if="requiredPermissions.length" class="dashboard-empty-panel__permissions">
        <span>请联系管理员分配：</span>
        <code v-for="code in requiredPermissions" :key="code">{{ code }}</code>
      </div>
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

.dashboard-empty-panel__permissions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.dashboard-empty-panel__permissions code {
  border-radius: 4px;
  background: white;
  padding: 1px 6px;
  color: var(--foreground);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  font-weight: 600;
}
</style>
