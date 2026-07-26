<script setup lang="ts">
withDefaults(defineProps<{
  maxHeight?: string;
  maxWidth?: string;
}>(), {
  maxHeight: 'min(42vh, 420px)',
  maxWidth: '900px',
});
</script>

<template>
  <div class="warehouse-detail-table-frame detail-table-floating overflow-hidden rounded-md bg-background" :style="{ '--warehouse-detail-table-max-width': maxWidth }">
    <div class="warehouse-detail-table-scroll w-full" :style="{ '--warehouse-detail-table-max-height': maxHeight }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.warehouse-detail-table-frame {
  width: min(100%, var(--warehouse-detail-table-max-width));
  border: 1px solid color-mix(in srgb, var(--primary) 28%, var(--border));
  border-left: 3px solid var(--primary);
  background: color-mix(in srgb, var(--muted) 36%, white);
}

.warehouse-detail-table-scroll :deep([data-slot="table"]) {
  font-size: 12px;
}

.warehouse-detail-table-scroll :deep([data-slot="table-container"]) {
  max-height: var(--warehouse-detail-table-max-height);
  overflow: auto;
  /* 明细未溢出时不预留滚动条槽，避免右侧出现无内容的白边。 */
  scrollbar-gutter: auto;
}

.warehouse-detail-table-scroll :deep([data-slot="table-head"]),
.warehouse-detail-table-scroll :deep([data-slot="table-cell"]) {
  height: 38px;
  padding: 6px 10px;
  font-size: 12px;
  vertical-align: middle;
}

/* 表格布局不会把 td 的最小高度稳定计入行轨道，使用上下内边距让正文行与表头等高。 */
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
</style>
