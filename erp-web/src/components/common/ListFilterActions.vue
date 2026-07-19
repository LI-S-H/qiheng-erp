<script setup lang="ts">
import { LoaderCircle } from 'lucide-vue-next';

import { Button } from '@/components/ui/button';

withDefaults(defineProps<{
  busy?: boolean;
  disabled?: boolean;
  queryLabel?: string;
  resetLabel?: string;
}>(), {
  busy: false,
  disabled: false,
  queryLabel: '查询',
  resetLabel: '重置',
});

const emit = defineEmits<{
  query: [];
  reset: [];
}>();
</script>

<template>
  <div class="list-filter-actions" data-list-filter-actions :aria-busy="busy">
    <Button
      type="button"
      size="sm"
      :disabled="disabled || busy"
      :aria-label="busy ? `${queryLabel}中` : queryLabel"
      @click="emit('query')"
    >
      <LoaderCircle v-if="busy" class="animate-spin" aria-hidden="true" />
      {{ busy ? `${queryLabel}中` : queryLabel }}
    </Button>
    <Button
      type="button"
      size="sm"
      variant="outline"
      :disabled="disabled || busy"
      :aria-label="resetLabel"
      @click="emit('reset')"
    >
      {{ resetLabel }}
    </Button>
  </div>
</template>

<style scoped>
.list-filter-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.list-filter-actions :deep([data-slot='button']) {
  min-width: 78px;
}

@media (max-width: 640px) {
  .list-filter-actions {
    width: 100%;
  }

  .list-filter-actions :deep([data-slot='button']) {
    flex: 1;
  }
}
</style>
