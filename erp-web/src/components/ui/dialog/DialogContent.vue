<script setup lang="ts">
import { XIcon } from '@lucide/vue';

import type { DialogContentEmits, DialogContentProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  DialogClose,
  DialogContent,
  DialogPortal,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "@/lib/utils"
import { Button } from '@/components/ui/button'
import DialogOverlay from "./DialogOverlay.vue"

defineOptions({
  inheritAttrs: false,
})

type DialogPlacement = 'viewport' | 'app-content'

const props = withDefaults(defineProps<DialogContentProps & {
  class?: HTMLAttributes["class"]
  placement?: DialogPlacement
  showCloseButton?: boolean
}>(), {
  placement: 'viewport',
  showCloseButton: true,
})
const emits = defineEmits<DialogContentEmits>()

const delegatedProps = reactiveOmit(props, "class", "placement", "showCloseButton")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DialogPortal>
    <DialogOverlay />
    <DialogContent
      data-slot="dialog-content"
      style="z-index: 50; pointer-events: auto"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="cn(
        'bg-popover text-popover-foreground data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95 ring-foreground/10 grid max-h-[calc(100dvh-2rem)] max-w-[calc(100%-2rem)] gap-4 overflow-y-auto overscroll-contain rounded-xl p-5 text-[15px] ring-1 duration-100 sm:max-w-sm fixed z-[60] w-full outline-none',
        props.placement === 'app-content'
          ? 'top-0 right-0 bottom-0 left-[var(--app-shell-sidebar-width)] m-auto h-fit'
          : 'top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2',
        props.class,
      )"
    >
      <slot />

      <DialogClose
        v-if="showCloseButton"
        data-slot="dialog-close"
        as-child
      >
        <Button variant="ghost" class="absolute top-2 right-2" size="icon-sm">
          <XIcon />
          <span class="sr-only">Close</span>
        </Button>
      </DialogClose>
    </DialogContent>
  </DialogPortal>
</template>
