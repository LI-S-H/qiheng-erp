<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  visible: boolean;
  label?: string;
  initialDelay?: number;
}>(), {
  label: '表格刷新中',
  initialDelay: 240,
});

const rendered = ref(false);
let firstVisibleHandled = false;
let initialTimer: number | undefined;

function clearInitialTimer() {
  if (initialTimer === undefined) return;
  window.clearTimeout(initialTimer);
  initialTimer = undefined;
}

watch(
  () => props.visible,
  visible => {
    clearInitialTimer();
    if (!visible) {
      rendered.value = false;
      return;
    }
    if (!firstVisibleHandled && props.initialDelay > 0) {
      firstVisibleHandled = true;
      initialTimer = window.setTimeout(() => {
        rendered.value = true;
      }, props.initialDelay);
      return;
    }
    rendered.value = true;
    firstVisibleHandled = true;
  },
  { immediate: true },
);

onBeforeUnmount(clearInitialTimer);
</script>

<template>
  <Transition name="page-loading">
    <div
      v-if="rendered"
      data-list-loading
      class="absolute inset-0 z-20 grid place-items-center bg-card/75 backdrop-blur-[1px]"
      aria-live="polite"
      :aria-label="props.label"
    >
      <div class="page-loading-indicator">
        <span class="page-loading-spinner" aria-hidden="true" />
        {{ props.label }}
      </div>
    </div>
  </Transition>
</template>
