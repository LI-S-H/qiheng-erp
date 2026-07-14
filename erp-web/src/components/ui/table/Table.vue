<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue"
import type { HTMLAttributes } from "vue"
import { cn } from "@/lib/utils"

const props = defineProps<{
  class?: HTMLAttributes["class"]
  scrollLabel?: string
}>()

const viewportRef = ref<HTMLDivElement | null>(null)
const hasHorizontalOverflow = ref(false)
const canScrollStart = ref(false)
const canScrollEnd = ref(false)
let resizeObserver: ResizeObserver | undefined

function updateScrollState() {
  const viewport = viewportRef.value
  if (!viewport) return

  const styles = window.getComputedStyle(viewport)
  const borderWidth = Number.parseFloat(styles.borderLeftWidth)
    + Number.parseFloat(styles.borderRightWidth)
  const scrollbarGutterWidth = Math.max(
    0,
    viewport.offsetWidth - viewport.clientWidth - borderWidth,
  )
  const maxScrollLeft = Math.max(
    0,
    viewport.scrollWidth - viewport.clientWidth - scrollbarGutterWidth,
  )
  hasHorizontalOverflow.value = maxScrollLeft > 2
  canScrollStart.value = hasHorizontalOverflow.value && viewport.scrollLeft > 2
  canScrollEnd.value = hasHorizontalOverflow.value && viewport.scrollLeft < maxScrollLeft - 2
}

onMounted(() => {
  const viewport = viewportRef.value
  if (!viewport) return

  viewport.addEventListener("scroll", updateScrollState, { passive: true })
  resizeObserver = new ResizeObserver(updateScrollState)
  resizeObserver.observe(viewport)

  const table = viewport.querySelector("table")
  if (table) resizeObserver.observe(table)

  void nextTick(updateScrollState)
})

onBeforeUnmount(() => {
  viewportRef.value?.removeEventListener("scroll", updateScrollState)
  resizeObserver?.disconnect()
})
</script>

<template>
  <div
    ref="viewportRef"
    data-slot="table-container"
    class="table-scroll-viewport relative w-full overflow-x-auto"
    :data-scroll-start="canScrollStart ? 'true' : undefined"
    :data-scroll-end="canScrollEnd ? 'true' : undefined"
    :data-horizontal-overflow="hasHorizontalOverflow ? 'true' : undefined"
    :role="hasHorizontalOverflow ? 'region' : undefined"
    :aria-label="hasHorizontalOverflow ? (props.scrollLabel || '可横向滚动的数据表格') : undefined"
    :tabindex="hasHorizontalOverflow ? 0 : undefined"
  >
    <table data-slot="table" :class="cn('erp-data-table w-full caption-bottom text-sm', props.class)">
      <slot />
    </table>
  </div>
</template>
