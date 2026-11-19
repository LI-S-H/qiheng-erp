<script setup lang="ts">
import { computed } from 'vue';
import { ChevronRight, FileText } from 'lucide-vue-next';
import AiMarkdownContent from './AiMarkdownContent.vue';
import { splitReportMessage, type AiReportCard } from '../contracts/report-contract';

const props = withDefaults(defineProps<{
  text: string;
  reports: AiReportCard[];
  active?: boolean;
  streaming?: boolean;
}>(), { active: false, streaming: false });
const emit = defineEmits<{ open: [report: AiReportCard] }>();
const parts = computed(() => splitReportMessage(props.text, props.reports, props.streaming, props.active));
</script>

<template>
  <template v-for="part in parts" :key="part.key">
    <AiMarkdownContent v-if="'text' in part" :content="part.text" />
    <button
      v-else type="button" class="report-card" :class="{ 'is-pending': !active }"
      :disabled="!active" :aria-label="'查看报告：' + part.report.title"
      data-testid="ai-report-card" @click="emit('open', part.report)"
    >
      <span class="report-icon"><FileText :size="20" aria-hidden="true" /></span>
      <span class="report-title" :title="part.report.title">{{ part.report.title }}</span>
      <span class="report-action">{{ active ? '查看报告' : '等待回复完成' }}<ChevronRight :size="16" aria-hidden="true" /></span>
    </button>
  </template>
</template>

<style scoped>
.report-card {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  height: 64px;
  min-width: 0;
  margin: 12px 0;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--card);
  color: var(--foreground);
  text-align: left;
}
.report-card:not(:disabled):hover { border-color: var(--primary); background: var(--muted); }
.report-card:focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; }
.report-icon { display: grid; width: 36px; height: 36px; place-items: center; flex: none; border-radius: 8px; color: var(--primary); background: color-mix(in srgb, var(--primary) 9%, transparent); }
.report-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 600; }
.report-action { display: inline-flex; align-items: center; gap: 2px; white-space: nowrap; flex: none; font-size: 12px; color: var(--primary); }
.is-pending .report-action { color: var(--muted-foreground); }
</style>
