<script lang="ts" setup>
import {
  CircleCheckIcon,
  InfoIcon,
  TriangleAlertIcon,
  OctagonXIcon,
  Loader2Icon,
  XIcon,
} from '@lucide/vue';


import type { ToasterProps } from "vue-sonner"
import { Toaster as Sonner } from "vue-sonner"
import { cn } from "@/lib/utils"

const props = defineProps<ToasterProps>()
</script>

<template>
  <Sonner
    :class="cn('toaster group', props.class)"
    :style="{
      '--normal-bg': 'var(--popover)',
      '--normal-text': 'var(--popover-foreground)',
      '--normal-border': 'var(--border)',
      '--success-bg': 'hsl(143, 70%, 96%)',
      '--success-text': 'hsl(140, 70%, 28%)',
      '--success-border': 'hsl(143, 50%, 87%)',
      '--warning-bg': 'hsl(45, 90%, 96%)',
      '--warning-text': 'hsl(38, 80%, 30%)',
      '--warning-border': 'hsl(45, 70%, 88%)',
      '--error-bg': 'hsl(0, 70%, 96%)',
      '--error-text': 'hsl(0, 70%, 35%)',
      '--error-border': 'hsl(0, 50%, 88%)',
      '--info-bg': 'hsl(210, 70%, 96%)',
      '--info-text': 'hsl(210, 70%, 35%)',
      '--info-border': 'hsl(210, 50%, 88%)',
      '--border-radius': 'var(--radius)',
      '--gray2': 'hsl(var(--popover) / 0.9)',
      '--gray3': 'var(--border)',
      '--gray4': 'var(--border)',
      '--gray5': 'var(--border)',
      '--gray12': 'var(--popover-foreground)',
    }"
    v-bind="props"
  >
    <template #success-icon>
      <CircleCheckIcon class="size-4" />
    </template>
    <template #info-icon>
      <InfoIcon class="size-4" />
    </template>
    <template #warning-icon>
      <TriangleAlertIcon class="size-4" />
    </template>
    <template #error-icon>
      <OctagonXIcon class="size-4" />
    </template>
    <template #loading-icon>
      <div>
        <Loader2Icon class="size-4 animate-spin" />
      </div>
    </template>
    <template #close-icon>
      <XIcon class="size-4" />
    </template>
  </Sonner>
</template>
