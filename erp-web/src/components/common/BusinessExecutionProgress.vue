<script setup lang="ts">
import { computed } from 'vue';
import BusinessDetailProgress from '@/components/common/BusinessDetailProgress.vue';
import type { BusinessDetailProgressStep } from '@/components/common/BusinessDetailProgress.vue';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

export interface BusinessExecutionMetric {
  label: string;
  value: number;
  pending?: boolean;
}

const props = defineProps<{
  steps: BusinessDetailProgressStep[];
  description: string;
  amountLabel: string;
  completedAmount: number;
  completionRate: number;
  completionRateHint: string;
  metrics: BusinessExecutionMetric[];
}>();

// 金额进度可能来自明细汇总；限制展示值避免异常数据撑破进度条。
const normalizedCompletionRate = computed(() => Math.min(100, Math.max(0, Number(props.completionRate) || 0)));

function formatMoney(value: number) {
  return `￥${Number(value || 0).toFixed(2)}`;
}

function formatRate(value: number) {
  const rounded = Math.round(value * 100) / 100;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}
</script>

<template>
  <section class="rounded-lg border border-border bg-card px-4 py-4">
    <div class="mb-4 flex items-center justify-between gap-3">
      <div>
        <h3 class="text-sm font-semibold">业务进度</h3>
        <p class="mt-1 text-xs text-muted-foreground">{{ description }}</p>
      </div>
    </div>
    <BusinessDetailProgress :steps="steps" />
    <div class="business-execution-summary">
      <div class="business-execution-summary__heading">
        <div><span>{{ amountLabel }}</span><strong>{{ formatMoney(completedAmount) }}</strong></div>
        <b class="business-execution-summary__rate" aria-label="按金额核算的完成率">{{ formatRate(normalizedCompletionRate) }}%</b>
      </div>
      <Tooltip :delay-duration="0" :skip-delay-duration="0">
        <TooltipTrigger as-child>
          <button type="button" class="business-execution-summary__track" aria-label="查看按金额核算的完成率"><i :style="{ width: `${normalizedCompletionRate}%` }" /></button>
        </TooltipTrigger>
        <TooltipContent class="max-w-[280px] !animate-none">{{ completionRateHint }}</TooltipContent>
      </Tooltip>
      <dl>
        <div v-for="metric in metrics" :key="metric.label"><dt>{{ metric.label }}</dt><dd :class="{ 'is-pending': metric.pending }">{{ metric.value }} 项</dd></div>
      </dl>
    </div>
  </section>
</template>

<style scoped>
.business-execution-summary { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border); }
.business-execution-summary__heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; }
.business-execution-summary__heading span, .business-execution-summary dl dt { display: block; color: var(--muted-foreground); font-size: 12px; }
.business-execution-summary__heading strong { display: block; margin-top: 3px; font-size: 14px; font-weight: 650; }
.business-execution-summary__rate { color: var(--primary); font-size: 18px; font-weight: 700; line-height: 1; }
.business-execution-summary__track { display: block; width: 100%; height: 6px; margin-top: 10px; padding: 0; overflow: hidden; border: 0; border-radius: 999px; background: var(--muted); cursor: help; }
.business-execution-summary__track i { display: block; height: 100%; border-radius: inherit; background: var(--primary); pointer-events: none; transition: width .2s ease; }
.business-execution-summary__track:focus-visible { outline: 2px solid color-mix(in srgb, var(--primary) 35%, transparent); outline-offset: 3px; }
.business-execution-summary dl { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 14px 0 0; }
.business-execution-summary dl > div { min-width: 0; padding: 0 14px; }
.business-execution-summary dl > div:first-child { padding-left: 0; }
.business-execution-summary dl > div + div { border-left: 1px solid var(--border); }
.business-execution-summary dl dd { margin: 4px 0 0; color: var(--foreground); font-size: 14px; font-weight: 650; }
.business-execution-summary dl dd.is-pending { color: #b45309; }

@media (max-width: 640px) {
  .business-execution-summary dl { grid-template-columns: 1fr; gap: 10px; }
  .business-execution-summary dl > div, .business-execution-summary dl > div:first-child { padding: 0; }
  .business-execution-summary dl > div + div { padding-top: 10px; border-top: 1px solid var(--border); border-left: 0; }
}
</style>
