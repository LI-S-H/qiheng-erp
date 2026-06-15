<script setup lang="ts">
import type { ScrollAreaRootProps } from 'reka-ui';
import type { HTMLAttributes } from 'vue';
import { reactiveOmit } from '@vueuse/core';
import { cn } from '@/lib/utils';
import { ScrollArea } from '@/components/ui/scroll-area';

defineOptions({
  inheritAttrs: false,
});

const props = defineProps<ScrollAreaRootProps & {
  class?: HTMLAttributes['class'];
  contentClass?: HTMLAttributes['class'];
}>();

const delegatedProps = reactiveOmit(props, 'class', 'contentClass');
</script>

<template>
  <ScrollArea
    data-dialog-scroll-area
    v-bind="{ ...$attrs, ...delegatedProps }"
    :class="cn('dialog-scroll-area min-h-0 flex-1', props.class)"
  >
    <div
      data-slot="dialog-scroll-content"
      :class="cn('min-w-0 pr-4', props.contentClass)"
    >
      <slot />
    </div>
  </ScrollArea>
</template>
