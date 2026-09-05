<script setup lang="ts">
import { EyeOff, Inbox } from 'lucide-vue-next';
import type { DashboardAccessState } from '@/modules/dashboard/types';

defineProps<{
  state: DashboardAccessState;
}>();
</script>

<template>
  <div class="dashboard-access-panel" role="status" aria-live="polite">
    <span class="dashboard-access-panel__icon" aria-hidden="true">
      <EyeOff v-if="state === 'DENIED'" class="h-5 w-5" />
      <Inbox v-else class="h-5 w-5" />
    </span>
    <span class="dashboard-access-panel__body">
      <strong>{{ state === 'DENIED' ? '暂不可查看' : '暂无数据' }}</strong>
      <small v-if="state === 'DENIED'">当前角色暂不支持查看此模块</small>
    </span>
  </div>
</template>

<style scoped>
.dashboard-access-panel {
  display: flex;
  min-height: 184px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  border: 1px solid color-mix(in srgb, var(--border) 84%, white);
  border-radius: 10px;
  background: color-mix(in srgb, var(--muted) 40%, white);
  color: var(--muted-foreground);
  text-align: center;
}

.dashboard-access-panel__icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--border) 72%, white);
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted) 68%, white);
}

.dashboard-access-panel__icon :deep(svg) {
  width: 22px;
  height: 22px;
}

.dashboard-access-panel__body {
  display: grid;
  justify-items: center;
  gap: 4px;
}

.dashboard-access-panel strong {
  color: var(--foreground);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
}

.dashboard-access-panel__body small {
  max-width: 18em;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.5;
}
</style>
