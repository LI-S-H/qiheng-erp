<script setup lang="ts">
import { Check, LoaderCircle, TriangleAlert } from 'lucide-vue-next';
import type { AiProgressStep } from '../types';

defineProps<{
  steps: AiProgressStep[];
  /** 本轮是否已经结束；未结束时提示预计耗时。 */
  finished: boolean;
}>();

/** 毫秒转成人能读的耗时；不足一秒不显示小数。 */
function formatDuration(durationMs: number | null) {
  if (durationMs === null || durationMs <= 0) {
    return '';
  }
  return durationMs < 1000 ? `${durationMs} 毫秒` : `${(durationMs / 1000).toFixed(1)} 秒`;
}
</script>

<template>
  <details
    :open="!finished"
    v-if="steps.length"
    class="progress-feed"
    data-testid="ai-progress-feed"
    aria-live="polite"
    aria-label="任务运行进度"
  >
    <summary>{{ finished ? '查看本轮运行记录' : '正在执行规划' }}</summary>
    <ol class="progress-steps">
      <li
        v-for="(step, index) in steps"
        :key="`${step.node}-${index}`"
        :class="`is-${step.status}`"
      >
        <span class="step-mark" aria-hidden="true">
          <LoaderCircle v-if="step.status === 'running'" class="size-3.5 animate-spin" />
          <Check v-else-if="step.status === 'done'" class="size-3.5" /><TriangleAlert
            v-else
            class="size-3.5"
          />
        </span>
        <span class="step-label">{{ step.label }}</span>
        <time v-if="step.status === 'done'">{{ formatDuration(step.durationMs) }}</time>
        <span v-else class="sr-only">{{
          step.status === 'running' ? '正在执行' : '执行已结束'
        }}</span>
      </li>
    </ol>
    <p v-if="!finished" class="progress-hint">正在整理需求，可在稍后重新打开会话查看结果。</p>
  </details>
</template>

<style scoped>
.progress-feed {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 15px 16px;
  background: var(--muted);
}
.progress-feed summary {
  cursor: pointer;
  color: var(--muted-foreground);
  font-size: 13px;
}
.progress-steps {
  margin-top: 12px;
  display: grid;
  gap: 10px;
}
.progress-steps li {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.7;
}
.progress-steps li.is-running {
  color: #3c5c8a;
}
.step-mark {
  display: grid;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  place-items: center;
  color: var(--muted-foreground);
}
.progress-steps li.is-running .step-mark {
  color: var(--primary);
}
.progress-steps li.is-done .step-mark {
  color: #4c9475;
}
.step-label {
  min-width: 0;
  overflow-wrap: anywhere;
}
.progress-steps li.is-running .step-label {
  font-weight: 500;
}
.progress-steps time {
  margin-left: auto;
  color: var(--muted-foreground);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.progress-hint {
  margin-top: 12px;
  border-top: 1px solid var(--border);
  padding-top: 10px;
  color: var(--muted-foreground);
  font-size: 12px;
}
</style>
