<script setup lang="ts">
import type { AlertDialogContentEmits, AlertDialogContentProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  AlertDialogContent,
  AlertDialogOverlay,
  AlertDialogPortal,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "@/lib/utils"

defineOptions({
  inheritAttrs: false,
})

const props = withDefaults(
  defineProps<AlertDialogContentProps & {
    class?: HTMLAttributes["class"]
    size?: "default" | "sm"
    placement?: "viewport" | "app-content"
  }>(),
  {
    size: "default",
    placement: "viewport",
  },
)
const emits = defineEmits<AlertDialogContentEmits>()

const delegatedProps = reactiveOmit(props, "class", "size", "placement")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <AlertDialogPortal>
    <AlertDialogOverlay
      data-slot="alert-dialog-overlay"
      style="z-index: 70; pointer-events: auto"
      class="pointer-events-auto data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 fixed inset-0 bg-slate-950/30 backdrop-blur-[3px] duration-150"
    />
    <AlertDialogContent
      data-slot="alert-dialog-content"
      :data-size="size"
      style="z-index: 80; pointer-events: auto"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="
        cn(
          'data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95 bg-popover text-popover-foreground ring-foreground/10 max-h-[calc(100dvh-2rem)] gap-4 overflow-y-auto overscroll-contain rounded-xl p-5 text-[15px] ring-1 shadow-2xl duration-150 data-[size=default]:max-w-xs data-[size=sm]:max-w-xs data-[size=default]:sm:max-w-sm group/alert-dialog-content fixed z-[80] grid w-[calc(100%-2rem)] outline-none',
          props.placement === 'app-content'
            ? 'top-0 right-0 bottom-0 left-[var(--app-shell-sidebar-width)] m-auto h-fit'
            : 'top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2',
          props.class,
        )
      "
    >
      <slot />
    </AlertDialogContent>
  </AlertDialogPortal>
</template>
