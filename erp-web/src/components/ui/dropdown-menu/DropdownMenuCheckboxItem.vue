<script setup lang="ts">
import { CheckIcon } from '@lucide/vue';

import type { DropdownMenuCheckboxItemEmits, DropdownMenuCheckboxItemProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  DropdownMenuCheckboxItem,
  DropdownMenuItemIndicator,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "@/lib/utils"

const props = withDefaults(defineProps<DropdownMenuCheckboxItemProps & {
  class?: HTMLAttributes["class"]
  indicatorStyle?: 'check' | 'checkbox'
}>(), {
  indicatorStyle: 'check',
})
const emits = defineEmits<DropdownMenuCheckboxItemEmits>()

const delegatedProps = reactiveOmit(props, "class", "indicatorStyle")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DropdownMenuCheckboxItem
    data-slot="dropdown-menu-checkbox-item"
    v-bind="forwarded"
    :class="cn(
      'focus:bg-accent focus:text-accent-foreground focus:**:text-accent-foreground gap-2 rounded-md py-1 text-sm data-inset:pl-7 [&_svg:not([class*=size-])]:size-4 relative flex cursor-default items-center outline-hidden select-none data-disabled:pointer-events-none data-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:shrink-0',
      indicatorStyle === 'checkbox'
        ? 'group/dropdown-checkbox pr-2 pl-8'
        : 'pr-8 pl-1.5',
      props.class,
    )"
  >
    <span
      v-if="indicatorStyle === 'checkbox'"
      class="absolute left-2 flex size-4 items-center justify-center rounded-[4px] border border-input bg-background text-primary shadow-xs transition-colors pointer-events-none group-data-[state=checked]/dropdown-checkbox:border-primary group-data-[state=checked]/dropdown-checkbox:bg-primary group-data-[state=checked]/dropdown-checkbox:text-primary-foreground"
      data-slot="dropdown-menu-checkbox-item-indicator"
    >
      <DropdownMenuItemIndicator>
        <slot name="indicator-icon">
          <CheckIcon class="!size-3.5" />
        </slot>
      </DropdownMenuItemIndicator>
    </span>
    <span
      v-else
      class="absolute right-2 flex items-center justify-center pointer-events-none"
      data-slot="dropdown-menu-checkbox-item-indicator"
    >
      <DropdownMenuItemIndicator>
        <slot name="indicator-icon">
          <CheckIcon />
        </slot>
      </DropdownMenuItemIndicator>
    </span>
    <slot />
  </DropdownMenuCheckboxItem>
</template>
