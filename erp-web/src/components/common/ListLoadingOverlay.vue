<script setup lang="ts">
withDefaults(defineProps<{
  visible: boolean;
  label?: string;
}>(), {
  label: '数据查询中',
});
</script>

<template>
  <Transition name="page-loading">
    <div
      v-if="visible"
      data-list-loading
      class="absolute inset-0 z-20 grid place-items-center bg-card/75 backdrop-blur-[1px]"
      aria-live="polite"
      :aria-label="label"
    >
      <div class="page-loading-indicator">
        <span class="page-loading-spinner" aria-hidden="true" />
        {{ label }}
      </div>
    </div>
  </Transition>
</template>
