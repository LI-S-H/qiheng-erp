<script setup lang="ts">
withDefaults(defineProps<{
  maxHeight?: string;
  maxWidth?: string;
  title?: string;
  itemCount?: number;
  itemCountLabel?: string;
}>(), {
  maxHeight: 'min(42vh, 420px)',
  maxWidth: '900px',
  title: '',
  itemCount: 0,
  itemCountLabel: '项',
});
</script>

<template>
  <div class="warehouse-detail-table-frame detail-table-floating overflow-hidden rounded-md bg-background" :style="{ '--warehouse-detail-table-max-width': maxWidth }">
    <div v-if="title || $slots.metrics || $slots.header" class="warehouse-detail-table-frame__header">
      <div v-if="title" class="warehouse-detail-table-frame__heading">
        <span>{{ title }}</span>
        <span v-if="itemCount > 0" class="warehouse-detail-table-frame__count">共 {{ itemCount }} {{ itemCountLabel }}</span>
      </div>
      <div v-if="$slots.metrics" class="warehouse-detail-table-frame__metrics"><slot name="metrics" /></div>
      <div v-if="$slots.header" class="warehouse-detail-table-frame__header-extra"><slot name="header" /></div>
    </div>
    <div v-if="$slots.summary" class="warehouse-detail-table-frame__summary"><slot name="summary" /></div>
    <div class="warehouse-detail-table-scroll w-full" :style="{ '--warehouse-detail-table-max-height': maxHeight }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.warehouse-detail-table-frame {
  width: min(100%, var(--warehouse-detail-table-max-width));
  border: 1px solid color-mix(in srgb, var(--primary) 28%, var(--border));
  border-left: 1px solid var(--primary);
  background: var(--background);
}

.warehouse-detail-table-frame__header {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--muted) 34%, white);
}

.warehouse-detail-table-frame__heading {
  flex: none;
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
  color: var(--foreground);
  font-size: 14px;
  line-height: 22px;
  font-weight: 600;
}

.warehouse-detail-table-frame__count {
  flex: none;
  border-radius: 999px;
  background: var(--muted);
  padding: 1px 6px;
  color: var(--muted-foreground);
  font-size: 11px;
  line-height: 18px;
  font-weight: 500;
}

.warehouse-detail-table-frame__header-extra {
  flex: none;
  min-width: 0;
}

.warehouse-detail-table-frame__metrics {
  min-width: 0;
  flex: 1;
  margin-left: auto;
}

.warehouse-detail-table-frame__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--muted) 16%, white);
}

.warehouse-detail-table-scroll :deep([data-slot="table"]) {
  font-size: 12px;
}

.warehouse-detail-table-scroll :deep([data-slot="table-container"]) {
  max-height: var(--warehouse-detail-table-max-height);
  overflow: auto;
  scrollbar-gutter: auto;
}

.warehouse-detail-table-scroll :deep([data-slot="table-head"]),
.warehouse-detail-table-scroll :deep([data-slot="table-cell"]) {
  height: 38px;
  padding: 6px 10px;
  font-size: 12px;
  vertical-align: middle;
}

.warehouse-detail-table-scroll :deep([data-slot="table-cell"]) {
  min-height: 0;
  padding-block: 12px;
}

.warehouse-detail-table-scroll :deep([data-slot="table-head"]) {
  position: sticky;
  top: 0;
  z-index: 20;
  border-bottom-color: color-mix(in srgb, var(--primary) 24%, var(--border));
  background: color-mix(in srgb, var(--primary) 9%, white);
  color: color-mix(in srgb, var(--primary) 78%, var(--foreground));
  font-weight: 600;
}

.warehouse-detail-table-scroll :deep([data-detail-table-group]) {
  top: 0;
  height: 32px;
  padding-block: 4px;
  background: color-mix(in srgb, var(--primary) 11%, white);
  color: color-mix(in srgb, var(--primary) 82%, var(--foreground));
  font-size: 11px;
  font-weight: 600;
}

.warehouse-detail-table-scroll :deep([data-detail-table-column]) {
  top: 32px;
  height: 34px;
  padding-block: 5px;
}

.warehouse-detail-table-scroll :deep([data-detail-table-fixed]) {
  top: 0;
  height: 66px;
}

.warehouse-detail-table-scroll :deep([data-slot="table-head"].text-right),
.warehouse-detail-table-scroll :deep([data-slot="table-cell"].text-right) {
  text-align: right !important;
}

.warehouse-detail-table-scroll :deep([data-slot="table-head"].text-center),
.warehouse-detail-table-scroll :deep([data-slot="table-cell"].text-center) {
  text-align: center !important;
}

.warehouse-detail-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar) {
  width: 10px;
  height: 10px;
}

.warehouse-detail-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-track) {
  border-radius: 999px;
  background: var(--muted);
}

.warehouse-detail-table-scroll :deep([data-slot="table-container"]::-webkit-scrollbar-thumb) {
  border: 2px solid var(--muted);
  border-radius: 999px;
  background: color-mix(in srgb, var(--muted-foreground) 45%, transparent);
}

@media (max-width: 760px) {
  .warehouse-detail-table-frame__header {
    min-height: 44px;
    padding-block: 6px;
  }

  .warehouse-detail-table-frame__metrics,
  .warehouse-detail-table-frame__header-extra { display: none; }

  .warehouse-detail-table-frame__summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
