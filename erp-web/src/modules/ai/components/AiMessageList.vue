<script setup lang="ts">
import { computed, ref } from 'vue';
import { Bot, ChevronDown, ChevronRight, Database, LoaderCircle, UserRound } from 'lucide-vue-next';
import AiMarkdownContent from './AiMarkdownContent.vue';
import AiReportMessage from './AiReportMessage.vue';
import AiClarificationHistory from '../clarification/AiClarificationHistory.vue';
import { toolIcon } from './tool-icons';
import type { AiConversationMessage, AiMessageContent } from '../types';
import type { ClarificationQuestion } from '../clarification/clarification-types';
import { clarificationAnswers } from '../contracts/message-contract';
import type { LiveItem } from '../composables/use-conversation-runs';
import type { AiReportCard, AiReportProcess } from '../contracts/report-contract';

const props = defineProps<{
  messages: AiConversationMessage[];
  loading: boolean;
  pendingText: string;
  liveText?: string;
  liveItems?: LiveItem[];
  thinking?: boolean;
  activeQuestionId?: string;
  userId?: string;
  conversationId?: string;
  reportCards?: AiReportCard[];
  reportProcesses?: Record<string, AiReportProcess>;
  reportRunId?: string;
  finalMessageId?: string;
  runTerminal?: boolean;
  recentAnswers?: Record<string, { callId: string; questions: ClarificationQuestion[]; answer: string; questionAnswers: Record<string, string>; note: string }>;
}>();
const emit = defineEmits<{ 'open-analysis': [id?: string]; 'open-report-process': [id: string]; 'open-report': [report: AiReportCard] }>();
/** 工作过程行：把内容类型与数据提到同一层，模板直接引用 step.data。 */
type VisibleStep = Omit<AiConversationMessage, 'content'> & {
  [K in AiMessageContent['type']]: {
    contentType: K;
    content: Extract<AiMessageContent, { type: K }>;
    data: Extract<AiMessageContent, { type: K }>['data'];
  };
}[AiMessageContent['type']];
/** 复制一份带数据引用的过程行，避免模板重复展开 content.data。 */
function toVisibleStep(message: AiConversationMessage): VisibleStep {
  return { ...message, contentType: message.content.type, data: message.content.data } as VisibleStep;
}
/** 同一调用编号在不同轮次出现时，仍能打开对应的报告过程。 */
function reportProcessKey(requestId: string, callId: string) {
  return JSON.stringify([requestId, callId]);
}
const answers = computed(() => clarificationAnswers(props.messages));
const answerDetails = computed(() => new Map(props.messages.flatMap(message => message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT' && message.content.data.callId ? [[message.content.data.callId, message.content.data] as const] : [])));
const questionIds = computed(() => new Set(props.messages.flatMap(message => message.content.type === 'CLARIFICATION' ? [message.content.data.callId] : [])));
const liveQuestionIds = computed(() => new Set((props.liveItems || []).filter(item => item.type === 'clarification').map(item => item.id)));
const finalRuns = computed(() => new Set(props.messages.filter(message => message.kind === 'ASSISTANT_FINAL' && message.requestId).map(message => message.requestId)));
const reportReply = computed(() => {
  const items = props.liveItems || [];
  const last = props.finalMessageId
    ? items.find(item => item.type === 'text' && item.id === props.finalMessageId)
    : items[items.length - 1];
  return last?.type === 'text' ? last : undefined;
});
const liveReportCards = computed(() => finalRuns.value.has(props.reportRunId || '') ? []
  : (props.reportCards || []).filter(card => card.requestId === props.reportRunId
    && Boolean(props.reportProcesses?.[reportProcessKey(card.requestId, card.toolCallId)]?.report)));
/** 主回复未保存时，仍可以打开已经通过审核并保存的报告。 */
const unattachedReports = computed(() => (props.reportCards || []).filter(card => {
  if (finalRuns.value.has(card.requestId)) {
    return false;
  }
  if (card.requestId === props.reportRunId) {
    return props.runTerminal && (!props.finalMessageId || !reportReply.value);
  }
  return props.messages.some(message => message.requestId === card.requestId);
}));
/** 仅把本轮报告渲染到自己的最终答复。 */
function cardsFor(requestId: string) {
  return (props.reportCards || []).filter(card => card.requestId === requestId);
}
const stepsByRun = computed(() => {
  const result = new Map<string, VisibleStep[]>();
  for (const message of props.messages) {
    if (message.kind === 'REPORT_STEP' && message.content.type === 'REPORT') {
      const callId = message.content.data.callId;
      const hasTool = props.messages.some(item => item.requestId === message.requestId
        && item.kind === 'TOOL_CALL' && item.content.type === 'TOOL_STEP' && item.content.data.callId === callId);
      if (!hasTool) {
        const steps = result.get(message.requestId) || [];
        steps.push(toVisibleStep(message));
        result.set(message.requestId, steps);
      }
      continue;
    }
    // 只收集能放进“工作过程”的行；分析过程行也在其中，由模板决定如何呈现。
    if (message.kind !== 'ASSISTANT_STEP' && message.kind !== 'TOOL_CALL' && message.kind !== 'ANALYSIS_STEP') {
      continue;
    }
    const steps = result.get(message.requestId) || [];
    steps.push(toVisibleStep(message));
    result.set(message.requestId, steps);
  }
  return result;
});
/** 运行中断、没有最终答复或工具展示行时仍可查看已保存的过程。 */
const orphanReportProcesses = computed(() => props.messages.filter(message => {
  if (message.kind !== 'REPORT_STEP' || message.content.type !== 'REPORT' || finalRuns.value.has(message.requestId)) {
    return false;
  }
  const callId = message.content.data.callId;
  return !(props.liveItems || []).some(item => item.type === 'tool' && item.id === callId)
    && !props.messages.some(item => item.requestId === message.requestId && item.kind === 'TOOL_CALL'
      && item.content.type === 'TOOL_STEP' && item.content.data.callId === callId);
}));
const expandedRuns = ref(new Set<string>());
/** 已有分析工具展示行的轮次不再重复渲染分析过程行。 */
const analysisToolRuns = computed(() => new Set(props.messages
  .filter(message => (message.kind === 'ASSISTANT_STEP' && message.content.type === 'TEXT'
      && /^子智能体分析\s*·/.test(message.content.data.text))
    || isAnalysisToolStep(message))
  .map(message => message.requestId)));

/** 分析工具的耗时摘要直接作为打开面板的入口。 */
function isAnalysisSummary(step: AiConversationMessage) {
  return toolSummary(step)?.[1] === '子智能体分析';
}

/** 分析工具的结构化展示行；点击同样打开分析面板。 */
function isAnalysisToolStep(step: AiConversationMessage) {
  return step.kind === 'TOOL_CALL' && step.content.type === 'TOOL_STEP'
    && step.content.data.name === 'analyzeData';
}

/** 工具展示行右侧的耗时文案。 */
function toolDuration(durationMs: number) {
  return `${(durationMs / 1000).toFixed(1)} 秒`;
}

/** 取这一行过程对应的分析调用编号；摘要行缺少编号时回退到同轮最近一次分析工具调用。 */
function analysisBlockId(step: AiConversationMessage): string {
  if (step.content.type === 'ANALYSIS' || step.content.type === 'TOOL_STEP') {
    return step.content.data.callId;
  }
  if (step.content.type === 'TEXT' && step.content.data.callId) {
    return step.content.data.callId;
  }
  const steps = stepsByRun.value.get(step.requestId) || [];
  const index = steps.findIndex(item => item.messageId === step.messageId);
  for (let cursor = index - 1; cursor >= 0; cursor--) {
    const previous = steps[cursor];
    if (previous.content.type === 'TOOL_STEP' && previous.content.data.name === 'analyzeData') {
      return previous.content.data.callId;
    }
  }
  return '';
}

/** 按运行记录控制折叠，保留每一轮独立的展开状态。 */
function toggleSteps(id: string) {
  const next = new Set(expandedRuns.value);
  if (next.has(id)) {
    next.delete(id);
  } else {
    next.add(id);
  }
  expandedRuns.value = next;
}

/** 工具耗时摘要由服务端保存；普通规划文字仍按 Markdown 展示。 */
function toolSummary(step: AiConversationMessage) {
  if (step.content.type !== 'TEXT') {
    return null;
  }
  return /^(.+?)\s*·\s*(\d+(?:\.\d+)?\s*秒)$/.exec(step.content.data.text.trim());
}
</script>

<template>
  <div class="message-list" :aria-busy="loading">
    <p v-if="loading && !messages.length" class="list-note" role="status">正在读取会话记录…</p>
    <article
      v-for="message in messages.filter(item => !['ANALYSIS_STEP', 'REPORT_STEP'].includes(item.kind) && (item.content.type !== 'CLARIFICATION' || !liveQuestionIds.has(item.content.data.callId)) && (item.kind !== 'TOOL_RESULT' || (item.content.type === 'TEXT' && item.content.data.callId && !questionIds.has(item.content.data.callId) && !recentAnswers?.[item.content.data.callId])) && (!['ASSISTANT_STEP', 'TOOL_CALL'].includes(item.kind) || !finalRuns.has(item.requestId)))"
      :key="message.messageId"
      class="message"
      :class="{ 'is-user': message.role === 'user', 'is-clarification': message.content.type === 'CLARIFICATION' }"
      :data-message-id="message.messageId"
    >
      <span v-if="message.content.type !== 'CLARIFICATION'" class="message-avatar" aria-hidden="true"
        ><UserRound v-if="message.role === 'user'" class="size-4" /><Bot v-else class="size-4"
      /></span>
      <div class="message-content">
        <div v-if="message.content.type !== 'CLARIFICATION'" class="message-meta">
          <span>{{ message.role === 'user' ? '你' : '经营助手' }}</span
          ><time>{{ message.createdAt }}</time>
        </div>
        <section v-if="message.kind === 'ASSISTANT_FINAL' && stepsByRun.get(message.requestId)?.length" class="work-steps">
          <button type="button" class="work-toggle" :aria-expanded="expandedRuns.has(message.requestId)" @click="toggleSteps(message.requestId)">
            <ChevronDown class="work-chevron" :class="{ 'is-open': expandedRuns.has(message.requestId) }" aria-hidden="true" />
            {{ expandedRuns.has(message.requestId) ? '收起工作过程' : '查看工作过程' }}
          </button>
          <div class="work-reveal" :class="{ 'is-open': expandedRuns.has(message.requestId) }" :inert="!expandedRuns.has(message.requestId)">
            <div class="work-reveal-inner">
              <template v-for="step in stepsByRun.get(message.requestId)" :key="step.messageId">
                <AiClarificationHistory v-if="step.contentType === 'CLARIFICATION' && !liveQuestionIds.has(step.data.callId)" :questions="step.data.questions" :answer="answers.get(step.data.callId) || recentAnswers?.[step.data.callId]?.answer" :question-answers="answerDetails.get(step.data.callId)?.questionAnswers || recentAnswers?.[step.data.callId]?.questionAnswers" :note="answerDetails.get(step.data.callId)?.note || recentAnswers?.[step.data.callId]?.note" :tags="[]" />
                <button v-else-if="step.contentType === 'TEXT' && isAnalysisSummary(step)" type="button" class="work-tool work-analysis" @click="emit('open-analysis', analysisBlockId(step))">
                  <Bot class="work-tool-icon" aria-hidden="true" />
                  <span>分析专家完成分析</span>
                  <span class="work-duration">{{ toolSummary(step)?.[2] }}</span>
                  <span class="work-analysis-hint">查看过程</span>
                  <ChevronRight class="work-analysis-arrow" aria-hidden="true" />
                </button>
                <button v-else-if="isAnalysisToolStep(step) && step.contentType === 'TOOL_STEP'" type="button" class="work-tool work-analysis" @click="emit('open-analysis', analysisBlockId(step))">
                  <Bot class="work-tool-icon" aria-hidden="true" />
                  <span>分析专家完成分析</span>
                  <span class="work-duration">{{ toolDuration(step.data.durationMs) }}</span>
                  <span class="work-analysis-hint">查看过程</span>
                  <ChevronRight class="work-analysis-arrow" aria-hidden="true" />
                </button>
                <button v-else-if="step.contentType === 'TOOL_STEP' && step.data.name === 'createReport' && reportProcesses?.[reportProcessKey(step.requestId, step.data.callId)]"
                  type="button" class="work-tool work-analysis" @click="emit('open-report-process', reportProcessKey(step.requestId, step.data.callId))">
                  <component :is="toolIcon('createReport')" class="work-tool-icon" aria-hidden="true" />
                  <span>报告专家完成报告编写与讨论</span>
                  <span class="work-duration">{{ toolDuration(step.data.durationMs) }}</span>
                  <span class="work-analysis-hint">查看过程</span>
                  <ChevronRight class="work-analysis-arrow" aria-hidden="true" />
                </button>
                <button v-else-if="step.contentType === 'REPORT'" type="button" class="work-tool work-analysis" @click="emit('open-report-process', reportProcessKey(step.requestId, step.data.callId))">
                  <component :is="toolIcon('createReport')" class="work-tool-icon" aria-hidden="true" />
                  <span>查看报告编写与讨论过程</span>
                  <span class="work-analysis-hint">查看过程</span>
                  <ChevronRight class="work-analysis-arrow" aria-hidden="true" />
                </button>
                <p v-else-if="step.contentType === 'TOOL_STEP'" class="work-tool" :class="{ 'is-failed': step.data.status === 'failed' }">
                  <component :is="toolIcon(step.data.name)" class="work-tool-icon" aria-hidden="true" />
                  <span>{{ step.data.label }}</span>
                  <span class="work-duration">{{ toolDuration(step.data.durationMs) }}</span>
                </p>
                <button v-else-if="step.kind === 'ANALYSIS_STEP' && step.contentType === 'ANALYSIS' && !analysisToolRuns.has(step.requestId)" type="button" class="work-tool work-analysis" @click="emit('open-analysis', analysisBlockId(step))">
                  <Bot class="work-tool-icon" aria-hidden="true" />
                  <span>分析专家完成分析</span>
                  <span class="work-analysis-hint">查看过程</span>
                  <ChevronRight class="work-analysis-arrow" aria-hidden="true" />
                </button>
                <p v-else-if="step.contentType === 'TEXT' && toolSummary(step)" class="work-tool"><Database class="work-tool-icon" aria-hidden="true" /><span>{{ toolSummary(step)?.[1] }}</span><span class="work-duration">{{ toolSummary(step)?.[2] }}</span></p>
                <AiMarkdownContent v-else-if="step.contentType === 'TEXT'" :content="step.data.text" />
              </template>
            </div>
          </div>
        </section>
        <details v-if="message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT'" class="answer-receipt">
          <summary>补充回答</summary><p class="message-bubble">{{ message.content.data.text }}</p>
        </details>
        <p v-else-if="message.role === 'user'" class="message-bubble">{{ message.content.type === 'TEXT' ? message.content.data.text : '' }}</p>
        <template v-else-if="message.content.type === 'CLARIFICATION'">
          <slot v-if="message.messageId === activeQuestionId" name="clarification" />
          <AiClarificationHistory v-else :questions="message.content.data.questions" :answer="answers.get(message.content.data.callId) || recentAnswers?.[message.content.data.callId]?.answer" :question-answers="answerDetails.get(message.content.data.callId)?.questionAnswers || recentAnswers?.[message.content.data.callId]?.questionAnswers" :note="answerDetails.get(message.content.data.callId)?.note || recentAnswers?.[message.content.data.callId]?.note" :tags="[]" />
        </template>
        <AiReportMessage v-else-if="message.kind === 'ASSISTANT_FINAL' && message.content.type === 'TEXT'"
          :text="message.content.data.text" :reports="cardsFor(message.requestId)" active @open="emit('open-report', $event)" />
        <button v-else-if="message.content.type === 'TOOL_STEP' && message.content.data.name === 'createReport' && reportProcesses?.[reportProcessKey(message.requestId, message.content.data.callId)]"
          type="button" class="work-tool work-analysis" @click="emit('open-report-process', reportProcessKey(message.requestId, message.content.data.callId))">
          <span>查看报告编写与讨论过程</span><ChevronRight class="work-analysis-arrow" aria-hidden="true" />
        </button>
        <AiMarkdownContent v-else :content="message.content.type === 'TEXT' ? message.content.data.text : ''" />
      </div>
    </article>
    <article v-for="recentAnswer in Object.values(recentAnswers || {}).filter(answer => !liveQuestionIds.has(answer.callId) && !messages.some(message => message.content.type === 'CLARIFICATION' && message.content.data.callId === answer.callId))" :key="recentAnswer.callId" class="message is-clarification">
      <div class="message-content"><AiClarificationHistory :questions="recentAnswer.questions" :answer="recentAnswer.answer" :question-answers="recentAnswer.questionAnswers" :note="recentAnswer.note" :tags="[]" /></div>
    </article>
    <article v-if="pendingText" class="message is-user">
      <span class="message-avatar"><UserRound class="size-4" /></span>
      <div class="message-content">
        <div class="message-meta">你 · 正在提交</div>
        <p class="message-bubble">{{ pendingText }}</p>
      </div>
    </article>
    <article v-if="liveItems?.length || thinking" class="message">
      <span class="message-avatar" aria-hidden="true"><Bot class="size-4" /></span>
      <div class="message-content">
        <div class="message-meta">经营助手</div>
        <template v-for="item in liveItems" :key="item.type + item.id">
          <AiReportMessage v-if="item.type === 'text'" :text="item.text"
            :reports="item.id === reportReply?.id && (finalMessageId === '' || item.id === finalMessageId) ? liveReportCards : []"
            :active="item.id === finalMessageId" :streaming="!finalMessageId" @open="emit('open-report', $event)" />
          <AiClarificationHistory v-else-if="item.type === 'clarification'" :questions="item.questions" :answer="answers.get(item.id) || recentAnswers?.[item.id]?.answer" :question-answers="answerDetails.get(item.id)?.questionAnswers || recentAnswers?.[item.id]?.questionAnswers" :note="answerDetails.get(item.id)?.note || recentAnswers?.[item.id]?.note" :tags="[]" />
          <button
            v-else-if="item.type === 'tool' && item.tool === 'analyzeData'"
            type="button"
            class="tool-progress analysis-entry"
            :class="{ 'is-running': item.status === 'running' }"
            @click="emit('open-analysis', item.id)"
          >
            <LoaderCircle v-if="item.status === 'running'" class="work-tool-icon animate-spin" aria-hidden="true" />
            <Bot v-else class="work-tool-icon" aria-hidden="true" />
            <span class="flow-text">{{ item.status === 'running' ? '分析专家正在分析' : '分析专家完成分析' }}</span>
            <span class="analysis-entry-hint">查看过程<ChevronRight class="analysis-entry-arrow" aria-hidden="true" /></span>
          </button>
          <button v-else-if="item.type === 'tool' && item.tool === 'createReport'" type="button"
            class="tool-progress analysis-entry" :class="{ 'is-running': item.status === 'running' }"
            :disabled="!reportProcesses?.[reportProcessKey(reportRunId || '', item.id)]" @click="emit('open-report-process', reportProcessKey(reportRunId || '', item.id))">
            <LoaderCircle v-if="item.status === 'running'" class="work-tool-icon animate-spin" aria-hidden="true" />
            <component v-else :is="toolIcon(item.tool)" class="work-tool-icon" aria-hidden="true" />
            <span class="flow-text">{{ item.status === 'running' ? '报告专家正在完成报告编写与讨论' : '报告专家完成报告编写与讨论' }}</span>
            <span class="analysis-entry-hint">查看报告过程<ChevronRight class="analysis-entry-arrow" aria-hidden="true" /></span>
          </button>
          <p v-else class="tool-progress" :class="{ 'is-running': item.status === 'running' }"><component :is="toolIcon(item.type === 'tool' ? item.tool : undefined)" class="work-tool-icon" aria-hidden="true" /><span class="flow-text">{{ item.status === 'running' ? item.label : item.label.replace(/^正在/, '') }}</span><span v-if="typeof item.durationMs === 'number' && Number.isFinite(item.durationMs)" class="work-duration">{{ (item.durationMs / 1000).toFixed(1) }} 秒</span></p>
        </template>
        <p v-if="thinking" class="tool-progress is-running" role="status"><span class="flow-text">正在思考</span></p>
      </div>
    </article>
    <template v-for="message in orphanReportProcesses" :key="message.messageId">
      <button v-if="message.content.type === 'REPORT'" type="button" class="work-tool work-analysis" @click="emit('open-report-process', reportProcessKey(message.requestId, message.content.data.callId))">
        <span>查看报告编写与讨论过程</span><ChevronRight class="work-analysis-arrow" aria-hidden="true" />
      </button>
    </template>
    <section v-if="unattachedReports.length" class="message-content" aria-label="已保存报告">
      <AiReportMessage text="" :reports="unattachedReports" active @open="emit('open-report', $event)" />
    </section>
  </div>
</template>

<style scoped>
.message-list {
  display: grid;
  gap: 28px;
  min-width: 0;
}
.list-note {
  color: var(--muted-foreground);
  font-size: 13px;
}
.answer-receipt summary { color: var(--muted-foreground); font-size: 12px; cursor: pointer; }
.work-steps { margin: 0 0 14px; padding: 8px 12px; border-left: 2px solid #cbd5e1; color: #536276; }
.work-toggle { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 13px; color: #536276; }
.work-toggle:hover { color: #1f2937; }
.work-toggle:focus-visible { outline: 2px solid #6b8ec9; outline-offset: 3px; border-radius: 3px; }
.work-chevron { width: 14px; height: 14px; transform: rotate(-90deg); transition: transform 220ms ease; }
.work-chevron.is-open { transform: rotate(0); }
.work-reveal { display: grid; grid-template-rows: 0fr; opacity: 0; transition: grid-template-rows 220ms ease, opacity 180ms ease; }
.work-reveal.is-open { grid-template-rows: 1fr; opacity: 1; }
.work-reveal-inner { min-height: 0; overflow: hidden; }
.work-tool { display: flex; align-items: center; gap: 7px; margin: 10px 0; font-size: 13px; line-height: 1.5; color: #4b5e73; }
.work-tool.is-failed { color: #b45309; }
.work-tool-icon { width: 14px; height: 14px; flex: none; }
.work-duration { color: #64748b; font-variant-numeric: tabular-nums; }
.tool-progress { display: flex; align-items: center; gap: 7px; margin: 10px 0; font-size: 14px; color: #526276; }
.analysis-entry {
  width: fit-content;
  max-width: 100%;
  margin: 8px 0;
  padding: 4px 10px 4px 6px;
  border: 1px solid color-mix(in srgb, #6b8ec9 34%, transparent);
  border-radius: 8px;
  background: color-mix(in srgb, #6b8ec9 6%, transparent);
  cursor: pointer;
  transition: background 160ms ease, border-color 160ms ease;
}
.analysis-entry:hover { border-color: color-mix(in srgb, #6b8ec9 60%, transparent); background: color-mix(in srgb, #6b8ec9 11%, transparent); }
.analysis-entry:focus-visible { outline: 2px solid #6b8ec9; outline-offset: 2px; }
.analysis-entry-hint { display: inline-flex; align-items: center; gap: 2px; margin-left: 4px; font-size: 12px; color: #6b8ec9; }
.analysis-entry-arrow { width: 12px; height: 12px; }
.work-analysis { display: flex; width: 100%; align-items: center; gap: 7px; padding: 2px 0; cursor: pointer; color: #4b5e73; text-align: left; }
.work-analysis:hover { color: #1f2937; }
.work-analysis:focus-visible { outline: 2px solid #6b8ec9; outline-offset: 3px; border-radius: 3px; }
.work-analysis-arrow { width: 12px; height: 12px; flex: none; color: #6b8ec9; }
.work-analysis-hint { margin-left: 2px; color: #6b8ec9; font-size: 12px; }
@supports (background-clip: text) {
  .tool-progress.is-running .flow-text { color: transparent; background: linear-gradient(90deg, #526276 15%, #aab9cc 50%, #526276 85%) 100% 0 / 220% 100%; background-clip: text; animation: work-glow 1.6s linear infinite; }
}
@keyframes work-glow { to { background-position: -100% 0; } }
@media (prefers-reduced-motion: reduce) { .work-reveal, .work-chevron { transition: none; } .tool-progress.is-running .flow-text { animation: none; color: #526276; background: none; } }
.answer-receipt .message-bubble { margin-top: 8px; }
.message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
.message.is-clarification { margin-left: 42px; }
.message-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--muted);
  color: var(--muted-foreground);
}
.message-content {
  min-width: 0;
  flex: 1;
}
.message.is-user {
  flex-direction: row-reverse;
}
.is-user .message-content {
  flex: 0 1 auto;
  max-width: min(75%, 560px);
}
.is-user .message-meta {
  justify-content: flex-end;
}
.is-user .message-bubble {
  margin-left: auto;
  border-radius: 12px 12px 3px 12px;
  background: color-mix(in srgb, var(--primary) 9%, var(--card));
}
.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  min-height: 27px;
  color: var(--muted-foreground);
  font-size: 12px;
}
.message-meta time {
  font-variant-numeric: tabular-nums;
}
.message-bubble {
  width: fit-content;
  max-width: 100%;
  border-radius: 8px;
  padding: 10px 14px;
  background: var(--muted);
  color: var(--foreground);
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.historic-questions {
  margin-top: 10px;
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: 8px;
  color: var(--muted-foreground);
  font-size: 13px;
  line-height: 1.8;
}
.answered-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 5px;
  font-size: 12px;
}
@media (max-width: 640px) {
  .message {
    gap: 8px;
  }
  .message-avatar {
    width: 26px;
    height: 26px;
  }
  .is-user .message-content {
    max-width: calc(100% - 42px);
  }
}
</style>
