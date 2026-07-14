<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { Card } from '@/components/ui/card';

withDefaults(defineProps<{
  gridClass?: HTMLAttributes['class'];
  ariaLabel?: string;
}>(), {
  gridClass: undefined,
  ariaLabel: '筛选条件',
});
</script>

<template>
  <Card
    class="filter-panel list-filter-panel !gap-0"
    :aria-label="ariaLabel"
    role="search"
    data-list-filter-panel
  >
    <div class="filter-grid list-filter-panel__grid" :class="gridClass">
      <slot />
      <div v-if="$slots.actions" class="filter-actions list-filter-panel__actions">
        <slot name="actions" />
      </div>
    </div>
    <div v-if="$slots.footer" class="list-filter-panel__footer">
      <slot name="footer" />
    </div>
  </Card>
</template>

<style scoped>
.list-filter-panel {
  position: relative;
  overflow: hidden;
  padding: 18px;
  border-color: color-mix(in srgb, var(--foreground) 9%, var(--border));
  background: color-mix(in srgb, var(--card) 98%, var(--primary));
  box-shadow:
    0 1px 2px rgb(15 23 42 / 5%),
    0 12px 30px -22px rgb(15 23 42 / 34%),
    inset 0 1px 0 rgb(255 255 255 / 78%);
}

.list-filter-panel::before {
  position: absolute;
  inset: 0 18px auto;
  height: 2px;
  border-radius: 0 0 999px 999px;
  background: color-mix(in srgb, var(--primary) 46%, transparent);
  content: '';
  opacity: 0.72;
  pointer-events: none;
}

.list-filter-panel__grid {
  gap: 14px 16px;
}

.list-filter-panel :deep([data-slot='label']) {
  color: color-mix(in srgb, var(--foreground) 82%, var(--muted-foreground));
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.01em;
}

.list-filter-panel :deep([data-slot='input']),
.list-filter-panel :deep([role='combobox']) {
  min-height: 36px;
  border-color: color-mix(in srgb, var(--foreground) 9%, var(--border));
  background: var(--card);
  box-shadow: 0 1px 2px rgb(15 23 42 / 3%);
  transition:
    border-color var(--motion-duration-fast) ease,
    background-color var(--motion-duration-fast) ease,
    box-shadow var(--motion-duration-fast) ease;
}

.list-filter-panel :deep([data-slot='input']:hover:not(:disabled)),
.list-filter-panel :deep([role='combobox']:hover:not(:disabled)) {
  border-color: color-mix(in srgb, var(--primary) 28%, var(--border));
}

.list-filter-panel :deep([data-slot='input']:focus-visible),
.list-filter-panel :deep([role='combobox']:focus-visible) {
  border-color: color-mix(in srgb, var(--ring) 58%, var(--border));
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--ring) 8%, transparent),
    0 0 10px color-mix(in srgb, var(--ring) 14%, transparent);
}

.list-filter-panel__actions {
  align-items: center;
  min-height: 36px;
  padding: 2px;
}

.list-filter-panel__actions :deep([data-slot='button']) {
  min-width: 72px;
}

.list-filter-panel__footer {
  margin-top: 12px;
  border-top: 1px solid color-mix(in srgb, var(--foreground) 7%, var(--border));
  padding-top: 12px;
}
</style>
