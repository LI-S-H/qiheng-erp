<script setup lang="ts">
import { Card } from '@/components/ui/card';
</script>

<template>
  <Card size="sm" class="business-detail-workbench-card !gap-0 !py-0 !ring-0">
    <slot />
  </Card>
</template>

<style scoped>
.business-detail-workbench-card {
  border: 1px solid color-mix(in srgb, var(--border) 86%, transparent);
  background: var(--card);
  /* `!ring-0` 会以重要声明清空 shadcn 的 box-shadow，因此此处同样提升优先级保留柔和层次。 */
  box-shadow: 0 1px 3px rgb(15 23 42 / 4%), 0 8px 18px rgb(15 23 42 / 7%) !important;
}
</style>
