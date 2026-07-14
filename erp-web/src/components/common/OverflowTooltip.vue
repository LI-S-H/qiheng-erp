<script setup lang="ts">
import type { HTMLAttributes } from 'vue';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { cn } from '@/lib/utils';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

defineOptions({ inheritAttrs: false });

const props = withDefaults(defineProps<{
  text?: string | null;
  fallback?: string;
  lines?: number;
  class?: HTMLAttributes['class'];
  contentClass?: HTMLAttributes['class'];
}>(), {
  text: '',
  fallback: '-',
  lines: 1,
  class: undefined,
  contentClass: undefined,
});

const triggerRef = ref<HTMLElement | null>(null);
const isOverflowing = ref(false);
const isPointerInside = ref(false);
const isFocused = ref(false);
const interactionDismissed = ref(false);
const normalizedLines = computed(() => Math.max(1, Math.floor(props.lines)));
const displayText = computed(() => props.text?.trim() || props.fallback);
const hasTooltipContent = computed(() => Boolean(props.text?.trim()));
const tooltipOpen = computed(() => (
  isOverflowing.value
  && !interactionDismissed.value
  && (isPointerInside.value || isFocused.value)
));
let resizeObserver: ResizeObserver | null = null;

function measureOverflow() {
  const element = triggerRef.value;
  if (!element || !hasTooltipContent.value) {
    isOverflowing.value = false;
    return;
  }
  isOverflowing.value = element.scrollWidth > element.clientWidth + 1
    || element.scrollHeight > element.clientHeight + 1;
}

function scheduleMeasure() {
  void nextTick(measureOverflow);
}

function handlePointerEnter() {
  isPointerInside.value = true;
  interactionDismissed.value = false;
}

function handlePointerLeave() {
  isPointerInside.value = false;
  if (!isFocused.value) interactionDismissed.value = false;
}

function handleFocus() {
  isFocused.value = true;
  interactionDismissed.value = false;
}

function handleBlur() {
  isFocused.value = false;
  if (!isPointerInside.value) interactionDismissed.value = false;
}

function handleEscape() {
  interactionDismissed.value = true;
}

watch(() => [props.text, props.lines], scheduleMeasure, { flush: 'post' });

onMounted(() => {
  resizeObserver = new ResizeObserver(measureOverflow);
  if (triggerRef.value) resizeObserver.observe(triggerRef.value);
  scheduleMeasure();
  void document.fonts?.ready.then(measureOverflow);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
});
</script>

<template>
  <Tooltip :open="tooltipOpen">
    <TooltipTrigger as-child>
      <span
        ref="triggerRef"
        v-bind="$attrs"
        :class="cn('overflow-tooltip', normalizedLines === 1 ? 'overflow-tooltip--single' : 'overflow-tooltip--multi', props.class)"
        :style="{ '--overflow-tooltip-lines': normalizedLines }"
        :tabindex="isOverflowing ? 0 : undefined"
        :data-overflowing="isOverflowing ? 'true' : undefined"
        data-overflow-tooltip
        @pointerenter="handlePointerEnter"
        @pointerleave="handlePointerLeave"
        @focus="handleFocus"
        @blur="handleBlur"
        @keydown.esc="handleEscape"
      >
        {{ displayText }}
      </span>
    </TooltipTrigger>
    <TooltipContent
      :class="cn('max-w-sm whitespace-pre-wrap break-words leading-5', props.contentClass)"
    >
      {{ props.text }}
    </TooltipContent>
  </Tooltip>
</template>

<style scoped>
.overflow-tooltip {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.overflow-tooltip--single {
  white-space: nowrap;
}

.overflow-tooltip--multi {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--overflow-tooltip-lines);
  white-space: normal;
}

.overflow-tooltip[data-overflowing='true'] {
  cursor: help;
}

.overflow-tooltip:focus-visible {
  border-radius: 3px;
  outline: 2px solid color-mix(in srgb, var(--ring) 72%, transparent);
  outline-offset: 2px;
}
</style>
