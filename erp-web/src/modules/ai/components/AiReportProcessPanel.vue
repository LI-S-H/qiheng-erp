<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Check, FileText, ListChecks, LoaderCircle, PanelRightClose, TriangleAlert } from 'lucide-vue-next';
import type { AiReportProcess } from '../contracts/report-contract';

const props = withDefaults(defineProps<{ process: AiReportProcess; closeButton?: boolean }>(), { closeButton: true });
const emit = defineEmits<{ close: [] }>();
const scrollArea = ref<HTMLElement | null>(null);
const actionLabels: Record<string, string> = {
  PREPARING: '整理报告资料',
  WRITING: '编写报告',
  REVIEWING: '审阅报告',
  FORMAT_RETRY: '修正输出格式',
  WRITER_FORMAT_RETRY: '编写报告',
  REVIEW_FORMAT_RETRY: '审阅报告',
  REVIEW_RETRY: '重新核对审阅结果',
  REVISING: '审阅报告',
  SAVING: '保存报告',
  FAILED: '报告生成失败',
};
const running = computed(() => !props.process.historical && !props.process.report
  && props.process.steps[props.process.steps.length - 1]?.phase !== 'FAILED');
watch(() => props.process.steps.length, async () => {
  const element = scrollArea.value;
  if (!element || element.scrollHeight - element.scrollTop - element.clientHeight > 72) {
    return;
  }
  await nextTick();
  element.scrollTop = element.scrollHeight;
});
</script>

<template>
  <aside class="report-panel" aria-label="报告生成过程" data-testid="ai-report-process-panel">
    <header class="report-panel-header">
      <FileText :size="16" class="report-icon" aria-hidden="true" />
      <h2>报告生成过程</h2>
      <button v-if="closeButton" type="button" class="report-close" aria-label="收起报告过程" @click="emit('close')"><PanelRightClose :size="16" aria-hidden="true" /></button>
    </header>
    <div ref="scrollArea" class="report-panel-scroll">
      <div class="report-process-head">
        <ListChecks :size="16" aria-hidden="true" />
        <span>执行过程</span>
        <span class="report-count">{{ process.steps.length }} 步</span>
      </div>
      <ol class="report-timeline">
        <li v-for="(step, index) in process.steps" :key="step.stepSeq" :class="{ 'is-failed': step.phase === 'FAILED' }">
          <p>{{ step.text }}</p>
          <span class="report-action">
            <TriangleAlert v-if="step.phase === 'FAILED'" :size="15" aria-hidden="true" />
            <LoaderCircle v-else-if="running && index === process.steps.length - 1" :size="15" class="animate-spin" aria-hidden="true" />
            <Check v-else :size="15" aria-hidden="true" />
            {{ actionLabels[step.phase] }}
          </span>
        </li>
        <li v-if="process.report && !process.steps.some(step => step.text === '报告已保存')">
          <p>报告已保存，可以查看完整报告。</p>
          <span class="report-action"><Check :size="15" aria-hidden="true" />报告已保存</span>
        </li>
        <li v-else-if="process.historical && process.steps[process.steps.length - 1]?.phase !== 'FAILED'">
          <p>以上是已保存的过程，当前运行状态暂无法从历史记录确认。</p>
        </li>
      </ol>
    </div>
  </aside>
</template>

<style scoped>
.report-panel { display: flex; flex-direction: column; width: 100%; min-height: 0; height: 100%; background: var(--card); }
.report-panel-header { display: flex; align-items: center; gap: 8px; padding: 0 12px 0 16px; height: 56px; flex: none; border-bottom: 1px solid var(--border); }
.report-panel-header h2 { font-size: 14px; font-weight: 600; }
.report-icon { color: var(--primary); }
.report-close { display: grid; place-items: center; width: 28px; height: 28px; margin-left: auto; border-radius: 6px; }
.report-close:hover { background: var(--muted); }
.report-panel-scroll { overflow: auto; min-height: 0; padding: 16px; }
.report-process-head { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; }
.report-count { margin-left: auto; color: var(--muted-foreground); font-size: 12px; font-weight: 400; }
.report-timeline { display: grid; gap: 18px; margin: 16px 0 0; padding: 0; list-style: none; }
.report-timeline li { color: var(--foreground); font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.report-timeline p { margin: 0 0 8px; }
.report-action { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border: 1px solid var(--border); border-radius: 999px; color: var(--muted-foreground); font-size: 12px; }
.report-action :deep(svg) { flex: none; color: var(--primary); }
.report-timeline .is-failed :deep(svg) { color: var(--destructive); }
</style>
