<script setup lang="ts">
import { computed, ref } from 'vue';
import { FileText, ListChecks, LoaderCircle, Sparkles } from 'lucide-vue-next';
import AiMarkdownContent from './AiMarkdownContent.vue';
import { toolIcon } from './tool-icons';
import type { AiAnalysisBlock } from '../types';

const props = defineProps<{ block: AiAnalysisBlock }>();

const goalExpanded = ref(false);
const goalIsLong = computed(() => props.block.goal.length > 140);
const statusText = computed(() => completionLabel(props.block));
const summary = computed(() => splitSummary(props.block.summary));

const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成',
  PARTIAL: '部分完成',
  NEED_INFO: '需要补充信息',
  FAILED: '执行失败',
};

/** 结束状态由模型交回完成情况表达，失败时统一显示执行失败。 */
function completionLabel(block: AiAnalysisBlock) {
  if (block.status === 'running') {
    return '分析中';
  }
  if (block.status === 'failed') {
    return '执行失败';
  }
  return STATUS_LABELS[block.completion] || '已完成';
}

/** 最后一步的工具仍在执行，用于给当前步骤加运行提示。 */
function isRunningStep(index: number) {
  return props.block.status === 'running' && index === props.block.steps.length - 1;
}

/** 结论与产物文件分开：文件行是模型交付说明的一部分，单独展示更清楚。 */
function splitSummary(text: string): { main: string; files: string[] } {
  if (!text) {
    return { main: '', files: [] };
  }
  // 兼容 Markdown 写法：列表符号、加粗和小标题前缀都视为同一行文件说明。
  const fileLine = /^[\s>*+\-#•\d.、）)]*(结果文件|输出文件|产物文件|生成文件|交付文件)\s*\**\s*[:：]\s*/;
  const lines = text.split('\n');
  const index = lines.findIndex(line => fileLine.test(line));
  if (index < 0) {
    return { main: text, files: [] };
  }
  // 文件行可能一次列出多个文件，逐一拆开并去掉 resultId 说明。
  const files = lines[index]
    .replace(fileLine, '')
    .split(/[、,，;；]/)
    .map(item => item.replace(/[（(]\s*resultId[^)）]*[)）]/gi, '').replace(/[。.；;]\s*$/, '').trim())
    .filter(Boolean);
  // 文件行整行移除，其余内容作为结论文本。
  const main = [...lines.slice(0, index), ...lines.slice(index + 1)].join('\n').trim();
  return { main, files };
}
</script>

<template>
  <section class="analysis-block">
    <div class="analysis-goal">
      <span class="analysis-kicker">任务目标</span>
      <p class="analysis-goal-text" :class="{ 'is-clamped': goalIsLong && !goalExpanded }">{{ block.goal }}</p>
      <button v-if="goalIsLong" type="button" class="analysis-goal-toggle" @click="goalExpanded = !goalExpanded">
        {{ goalExpanded ? '收起目标' : '展开全部' }}
      </button>
    </div>

    <section v-if="block.steps.length || block.status === 'running'" class="analysis-process">
      <div class="analysis-process-head">
        <ListChecks class="analysis-process-icon" aria-hidden="true" />
        <span class="analysis-process-title">执行过程</span>
        <span class="analysis-process-meta">{{ block.steps.length ? `${block.steps.length} 步` : '进行中' }}</span>
      </div>
      <ol v-if="block.steps.length" class="analysis-timeline">
        <li v-for="(step, index) in block.steps" :key="index" class="analysis-step">
          <AiMarkdownContent v-if="step.text" :content="step.text" />
          <ul v-if="step.tools.length" class="analysis-tools">
            <li v-for="(tool, toolIndex) in step.tools" :key="toolIndex" class="analysis-tool" :class="{ 'is-active': isRunningStep(index) }">
              <LoaderCircle v-if="isRunningStep(index)" class="analysis-tool-icon animate-spin" aria-hidden="true" />
              <component :is="toolIcon(tool.name)" v-else class="analysis-tool-icon" aria-hidden="true" />
              {{ tool.label }}
            </li>
          </ul>
        </li>
      </ol>
      <p v-else class="analysis-placeholder" role="status">正在理解任务…</p>
    </section>

    <div v-if="summary.main" class="analysis-summary">
      <div class="analysis-summary-head">
        <Sparkles class="analysis-summary-icon" aria-hidden="true" />
        <span class="analysis-summary-title">结论</span>
        <span class="analysis-summary-status" :class="{ 'is-failed': block.status === 'failed' }">{{ statusText }}</span>
      </div>
      <AiMarkdownContent :content="summary.main" />
      <div v-if="summary.files.length" class="analysis-files">
        <span class="analysis-files-title">产物文件</span>
        <span v-for="file in summary.files" :key="file" class="analysis-file">
          <FileText class="analysis-file-icon" aria-hidden="true" />{{ file }}
        </span>
      </div>
    </div>
    <p v-else-if="block.status !== 'running'" class="analysis-placeholder">{{ statusText }}</p>
  </section>
</template>

<style scoped>
.analysis-block + .analysis-block {
  margin-top: 22px;
  padding-top: 22px;
  border-top: 1px dashed var(--border);
}
.analysis-goal {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--primary) 3%, var(--card));
}
.analysis-kicker {
  display: inline-block;
  margin-bottom: 4px;
  color: var(--primary);
  font-size: 11px;
  letter-spacing: 0.08em;
}
.analysis-goal-text {
  font-size: 13px;
  line-height: 1.75;
  color: var(--foreground);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.analysis-goal-text.is-clamped {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}
.analysis-goal-toggle {
  margin-top: 4px;
  color: var(--primary);
  font-size: 12px;
  cursor: pointer;
}
.analysis-goal-toggle:hover {
  text-decoration: underline;
}
.analysis-process {
  margin-top: 12px;
  padding: 10px 12px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
}
/* 卡片带轻微悬浮阴影，和面板背景拉开层次；悬停时再抬一点。 */
.analysis-goal,
.analysis-process,
.analysis-summary {
  box-shadow:
    0 1px 2px color-mix(in srgb, #101828 6%, transparent),
    0 4px 12px color-mix(in srgb, #101828 5%, transparent);
  transition: box-shadow 180ms ease, border-color 180ms ease, transform 180ms ease;
}
.analysis-goal:hover,
.analysis-process:hover,
.analysis-summary:hover {
  border-color: color-mix(in srgb, var(--primary) 26%, var(--border));
  box-shadow:
    0 2px 4px color-mix(in srgb, #101828 8%, transparent),
    0 10px 24px color-mix(in srgb, #101828 8%, transparent);
  transform: translateY(-1px);
}
@media (prefers-reduced-motion: reduce) {
  .analysis-goal,
  .analysis-process,
  .analysis-summary {
    transition: none;
  }
}
.analysis-process-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.analysis-process-icon {
  width: 14px;
  height: 14px;
  color: var(--muted-foreground);
}
.analysis-process-title {
  color: var(--foreground);
  font-size: 13px;
  font-weight: 500;
}
.analysis-process-meta {
  margin-left: auto;
  color: var(--muted-foreground);
  font-size: 11px;
}
.analysis-timeline {
  display: grid;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.analysis-step {
  min-width: 0;
}
.analysis-step :deep(.ai-markdown-content) {
  margin-top: 0;
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.75;
}
.analysis-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  padding: 0;
  list-style: none;
}
.analysis-tool {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.6;
}
.analysis-tool.is-active {
  border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
  color: var(--primary);
}
.analysis-tool-icon {
  width: 12px;
  height: 12px;
  flex: none;
}
.analysis-placeholder {
  margin-top: 12px;
  color: var(--muted-foreground);
  font-size: 12px;
}
.analysis-process .analysis-placeholder {
  margin-top: 0;
}
.analysis-summary {
  margin-top: 16px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--card);
}
.analysis-summary-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 2px;
}
.analysis-summary-icon {
  width: 14px;
  height: 14px;
  color: var(--primary);
}
.analysis-summary-title {
  color: var(--foreground);
  font-size: 13px;
  font-weight: 500;
}
.analysis-summary-status {
  margin-left: auto;
  padding: 1px 7px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 10%, var(--card));
  color: var(--primary);
  font-size: 11px;
}
.analysis-summary-status.is-failed {
  background: color-mix(in srgb, var(--destructive) 10%, var(--card));
  color: var(--destructive);
}
.analysis-summary :deep(.ai-markdown-content) {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.8;
}
.analysis-files {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.analysis-files-title {
  color: var(--muted-foreground);
  font-size: 11px;
}
.analysis-file {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--muted);
  color: var(--foreground);
  font-size: 12px;
}
.analysis-file-icon {
  width: 12px;
  height: 12px;
  color: var(--muted-foreground);
}
</style>