<script setup lang="ts">
withDefaults(
  defineProps<{
    switchKey: string | number;
    contentClass?: string;
  }>(),
  {
    contentClass: '',
  },
);
</script>

<template>
  <div class="ai-fade-switch" data-ai-fade-switch>
    <Transition name="ai-fade-switch" mode="out-in">
      <div :key="switchKey" class="ai-fade-switch__content" :class="contentClass" data-ai-fade-switch-content>
        <slot />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.ai-fade-switch,
.ai-fade-switch__content {
  min-width: 0;
}

.ai-fade-switch-enter-active,
.ai-fade-switch-leave-active {
  transition: opacity var(--motion-duration-base) ease;
}

.ai-fade-switch-enter-from,
.ai-fade-switch-leave-to {
  opacity: 0;
}
</style>
