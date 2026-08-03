<script setup lang="ts">
withDefaults(defineProps<{
  columns?: 2 | 3 | 4;
}>(), {
  columns: 4,
});
</script>

<template>
  <dl class="business-detail-facts" :class="`business-detail-facts--${columns}`">
    <slot />
  </dl>
</template>

<style scoped>
.business-detail-facts { display: grid; gap: 1px; overflow: hidden; margin: 0; border: 1px solid var(--border); border-radius: calc(var(--radius) - 2px); background: var(--border); }
.business-detail-facts--2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.business-detail-facts--3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.business-detail-facts--4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.business-detail-facts :deep(.business-detail-fact) { min-width: 0; padding: 11px 13px; background: var(--card); }
.business-detail-facts :deep(.business-detail-fact dt) { color: var(--muted-foreground); font-size: 12px; line-height: 16px; }
.business-detail-facts :deep(.business-detail-fact dd) { min-width: 0; margin: 5px 0 0; color: var(--foreground); font-size: 14px; line-height: 19px; }
.business-detail-facts :deep(.business-detail-fact dd > code),
.business-detail-facts :deep(.business-detail-fact dd > strong),
.business-detail-facts :deep(.business-detail-fact dd > small) { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.business-detail-facts :deep(.business-detail-fact dd > strong) { font-weight: 600; }
.business-detail-facts :deep(.business-detail-fact dd > small) { margin-top: 2px; color: var(--muted-foreground); font-size: 12px; line-height: 16px; }

@media (max-width: 960px) {
  .business-detail-facts--4, .business-detail-facts--3 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .business-detail-facts--2, .business-detail-facts--3, .business-detail-facts--4 { grid-template-columns: 1fr; }
}
</style>
