<script setup lang="ts">
import { Inbox, ShieldAlert } from 'lucide-vue-next';

const props = defineProps<{
  state: 'EMPTY' | 'DENIED';
  title: string;
  description?: string;
  cover?: boolean;
  layout?: 'inline' | 'stacked';
}>();
</script>

<template>
  <div
    :class="[
      'dashboard-empty-panel',
      `dashboard-empty-panel--${props.state.toLowerCase()}`,
      `dashboard-empty-panel--${props.layout ?? 'inline'}`,
      { 'dashboard-empty-panel--cover': props.cover },
    ]"
    role="status"
    aria-live="polite"
  >
    <div class="dashboard-empty-panel__content">
      <span class="dashboard-empty-panel__icon" aria-hidden="true">
        <ShieldAlert v-if="props.state === 'DENIED'" class="h-6 w-6" />
        <Inbox v-else class="h-6 w-6" />
      </span>
      <div class="dashboard-empty-panel__body">
        <strong>{{ title }}</strong>
        <p v-if="description" class="dashboard-empty-panel__description">{{ description }}</p>
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

.dashboard-empty-panel--cover {
  min-height: inherit;
  height: 100%;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.dashboard-empty-panel__content {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* 工作台各业务卡片的权限说明采用纵向信息组，并略上移以避开内容区的视觉重心。 */
.dashboard-empty-panel--stacked .dashboard-empty-panel__content {
  flex-direction: column;
  gap: 7px;
  max-width: min(100%, 420px);
  transform: translateY(-20px);
}

.dashboard-empty-panel--stacked .dashboard-empty-panel__body {
  justify-items: center;
  text-align: center;
}

.dashboard-empty-panel--stacked .dashboard-empty-panel__description {
  max-width: 100%;
}

.dashboard-empty-panel__icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 8px;
  background: white;
  color: var(--muted-foreground);
  box-shadow: inset 0 0 0 1px var(--border);
}

.dashboard-empty-panel__body {
  display: grid;
  gap: 5px;
  min-width: 0;
}

.dashboard-empty-panel__body > strong {
  color: var(--foreground);
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
}

.dashboard-empty-panel__description {
  margin: 0;
  font-size: 14px;
  line-height: 21px;
}

.dashboard-empty-panel--denied .dashboard-empty-panel__icon {
  color: #b45309;
  background: #fffbeb;
}

.dashboard-empty-panel--empty .dashboard-empty-panel__icon {
  color: #64748b;
}
</style>
