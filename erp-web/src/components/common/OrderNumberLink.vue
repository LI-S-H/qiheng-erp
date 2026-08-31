<script setup lang="ts">
import { computed } from 'vue';
import { toast } from 'vue-sonner';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

const props = defineProps<{
  value: string;
  label: string;
}>();

const displayValue = computed(() => {
  const value = props.value.trim();
  const match = /^([A-Za-z]+)(\d{8})(.+)$/.exec(value);
  return match ? `${match[1].toUpperCase()}-${match[2]}-${match[3]}` : value;
});

async function copyValue() {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.value);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = props.value;
      textarea.style.position = 'fixed';
      textarea.style.opacity = '0';
      document.body.appendChild(textarea);
      textarea.select();
      const copied = document.execCommand('copy');
      textarea.remove();
      if (!copied) throw new Error('copy command failed');
    }
    toast.success(`已复制${props.label}`);
  } catch {
    toast.error(`${props.label}复制失败，请手动复制`);
  }
}
</script>

<template>
  <Tooltip>
    <TooltipTrigger as-child>
      <button
        type="button"
        class="order-number-link"
        :aria-label="`复制${label} ${value}`"
        data-order-number
        data-order-number-copy
        @click="copyValue"
      >
        {{ displayValue }}
      </button>
    </TooltipTrigger>
    <TooltipContent>点击复制{{ label }}</TooltipContent>
  </Tooltip>
</template>

<style scoped>
.order-number-link {
  display: inline-flex;
  width: max-content;
  max-width: none;
  border: 0;
  border-radius: 4px;
  padding: 2px 4px;
  background: transparent;
  color: #172033;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  cursor: copy;
  transition: color 160ms ease, background-color 160ms ease;
}

.order-number-link:hover,
.order-number-link:focus-visible {
  background: #eff6ff;
  color: #1d4ed8;
  outline: none;
}

.order-number-link:focus-visible {
  box-shadow: 0 0 0 2px rgb(59 130 246 / 28%);
}
</style>