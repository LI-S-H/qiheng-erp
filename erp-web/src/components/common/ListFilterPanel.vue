<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { Card } from '@/components/ui/card';

withDefaults(defineProps<{
  gridClass?: HTMLAttributes['class'];
  ariaLabel?: string;
  layout?: 'grid' | 'content';
}>(), {
  gridClass: undefined,
  ariaLabel: '筛选条件',
  layout: 'grid',
});
</script>

<template>
  <Card
    class="filter-panel list-filter-panel !gap-0"
    :aria-label="ariaLabel"
    role="search"
    data-list-filter-panel
  >
    <div
      class="filter-grid list-filter-panel__grid"
      :class="[gridClass, { 'list-filter-panel__grid--content': layout === 'content' }]"
      :data-filter-layout="layout"
    >
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
  border-radius: 10px;
  padding: 16px;
  border-color: color-mix(in srgb, var(--foreground) 8%, var(--border));
  background: color-mix(in srgb, var(--card) 97%, var(--muted));
  box-shadow:
    0 1px 2px rgb(15 23 42 / 4%),
    0 6px 18px -16px rgb(15 23 42 / 24%);
}

.list-filter-panel__grid {
  gap: 12px 14px;
}

.list-filter-panel__grid--content {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
}

.list-filter-panel__grid--content :slotted(*) {
  min-width: 0;
  flex: 0 1 220px;
}

.list-filter-panel__grid--content :slotted([data-filter-size='compact']) {
  flex-basis: 168px;
  max-width: 190px;
}

.list-filter-panel__grid--content :slotted([data-filter-size='standard']) {
  flex-basis: 220px;
  max-width: 240px;
}

.list-filter-panel__grid--content :slotted([data-filter-size='wide']) {
  flex-basis: 280px;
  max-width: 320px;
}

.list-filter-panel :deep([data-slot='label']) {
  color: color-mix(in srgb, var(--foreground) 82%, var(--muted-foreground));
  font-size: 13px;
  font-weight: 600;
}

.list-filter-panel :deep([data-slot='input']),
.list-filter-panel :deep([role='combobox']) {
  min-height: 36px;
  border-color: color-mix(in srgb, var(--foreground) 9%, var(--border));
  background: var(--card);
  box-shadow: 0 1px 2px rgb(15 23 42 / 2%);
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
  border-color: color-mix(in srgb, var(--ring) 42%, var(--border));
  box-shadow:
    0 0 0 2px color-mix(in srgb, var(--ring) 10%, transparent),
    0 1px 2px rgb(15 23 42 / 3%);
}

.list-filter-panel :deep([data-slot='input']:disabled),
.list-filter-panel :deep([role='combobox'][aria-disabled='true']) {
  border-color: color-mix(in srgb, var(--foreground) 5%, var(--border));
  background: color-mix(in srgb, var(--muted) 72%, var(--card));
  color: var(--muted-foreground);
  cursor: not-allowed;
  opacity: 0.72;
}

.list-filter-panel__actions {
  align-items: center;
  min-height: 36px;
  padding: 2px;
}

.list-filter-panel__grid--content .list-filter-panel__actions {
  flex: 0 0 auto;
  margin-left: auto;
}

.list-filter-panel__actions :deep([data-slot='button']) {
  min-width: 72px;
}

.list-filter-panel__footer {
  margin-top: 12px;
  border-top: 1px solid color-mix(in srgb, var(--foreground) 7%, var(--border));
  padding-top: 12px;
  color: var(--muted-foreground);
}

@media (max-width: 640px) {
  .list-filter-panel {
    padding: 14px;
  }

  .list-filter-panel__grid--content :slotted([data-filter-size]) {
    width: 100%;
    max-width: none;
    flex-basis: 100%;
  }

  .list-filter-panel__grid--content .list-filter-panel__actions {
    width: 100%;
    margin-left: 0;
  }
}
</style>
