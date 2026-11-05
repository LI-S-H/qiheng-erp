<script setup lang="ts">
import { computed, ref } from 'vue';
import { Bot, ChevronDown, Database, UserRound } from 'lucide-vue-next';
import AiMarkdownContent from './AiMarkdownContent.vue';
import AiClarificationHistory from '../clarification/AiClarificationHistory.vue';
import type { AiConversationMessage } from '../types';
import { clarificationAnswers } from '../contracts/message-contract';
import type { LiveItem } from '../composables/use-conversation-runs';

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
  recentAnswers?: Record<string, { callId: string; questions: import('../clarification/clarification-types').ClarificationQuestion[]; answer: string; questionAnswers: Record<string, string>; note: string }>;
}>();
const answers = computed(() => clarificationAnswers(props.messages));
const answerDetails = computed(() => new Map(props.messages.flatMap(message => message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT' && message.content.data.callId ? [[message.content.data.callId, message.content.data] as const] : [])));
const questionIds = computed(() => new Set(props.messages.flatMap(message => message.content.type === 'CLARIFICATION' ? [message.content.data.callId] : [])));
const liveQuestionIds = computed(() => new Set((props.liveItems || []).filter(item => item.type === 'clarification').map(item => item.id)));
const finalRuns = computed(() => new Set(props.messages.filter(message => message.kind === 'ASSISTANT_FINAL' && message.requestId).map(message => message.requestId)));
const stepsByRun = computed(() => {
  const result = new Map<string, AiConversationMessage[]>();
  for (const message of props.messages) {
    if (message.kind !== 'ASSISTANT_STEP' && message.kind !== 'TOOL_CALL') continue;
    const steps = result.get(message.requestId) || [];
    steps.push(message);
    result.set(message.requestId, steps);
  }
  return result;
});
const expandedRuns = ref(new Set<string>());

/** 按运行记录控制折叠，保留每一轮独立的展开状态。 */
function toggleSteps(id: string) {
  const next = new Set(expandedRuns.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  expandedRuns.value = next;
}

/** 工具耗时摘要由服务端保存；普通规划文字仍按 Markdown 展示。 */
function toolSummary(step: AiConversationMessage) {
  if (step.content.type !== 'TEXT') return null;
  return /^(.+?)\s*·\s*(\d+(?:\.\d+)?\s*秒)$/.exec(step.content.data.text.trim());
}
</script>

<template>
  <div class="message-list" :aria-busy="loading">
    <p v-if="loading && !messages.length" class="list-note" role="status">正在读取会话记录…</p>
    <article
      v-for="message in messages.filter(item => (item.content.type !== 'CLARIFICATION' || !liveQuestionIds.has(item.content.data.callId)) && (item.kind !== 'TOOL_RESULT' || (item.content.type === 'TEXT' && item.content.data.callId && !questionIds.has(item.content.data.callId) && !recentAnswers?.[item.content.data.callId])) && (!['ASSISTANT_STEP', 'TOOL_CALL'].includes(item.kind) || !finalRuns.has(item.requestId)))"
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
                <AiClarificationHistory v-if="step.content.type === 'CLARIFICATION' && !liveQuestionIds.has(step.content.data.callId)" :questions="step.content.data.questions" :answer="answers.get(step.content.data.callId) || recentAnswers?.[step.content.data.callId]?.answer" :question-answers="answerDetails.get(step.content.data.callId)?.questionAnswers || recentAnswers?.[step.content.data.callId]?.questionAnswers" :note="answerDetails.get(step.content.data.callId)?.note || recentAnswers?.[step.content.data.callId]?.note" :tags="[]" />
                <p v-else-if="step.content.type === 'TEXT' && toolSummary(step)" class="work-tool"><Database class="work-tool-icon" aria-hidden="true" /><span>{{ toolSummary(step)?.[1] }}</span><span class="work-duration">{{ toolSummary(step)?.[2] }}</span></p>
                <AiMarkdownContent v-else-if="step.content.type === 'TEXT'" :content="step.content.data.text" />
              </template>
            </div>
          </div>
        </section>
        <details v-if="message.kind === 'TOOL_RESULT' && message.content.type === 'TEXT'" class="answer-receipt">
          <summary>补充回答</summary><p class="message-bubble">{{ message.content.data.text }}</p>
        </details>
        <p v-else-if="message.role === 'user'" class="message-bubble">{{ message.content.data.text }}</p>
        <template v-else-if="message.content.type === 'CLARIFICATION'">
          <slot v-if="message.messageId === activeQuestionId" name="clarification" />
          <AiClarificationHistory v-else :questions="message.content.data.questions" :answer="answers.get(message.content.data.callId) || recentAnswers?.[message.content.data.callId]?.answer" :question-answers="answerDetails.get(message.content.data.callId)?.questionAnswers || recentAnswers?.[message.content.data.callId]?.questionAnswers" :note="answerDetails.get(message.content.data.callId)?.note || recentAnswers?.[message.content.data.callId]?.note" :tags="[]" />
        </template>
        <AiMarkdownContent v-else :content="message.content.data.text" />
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
          <AiMarkdownContent v-if="item.type === 'text'" :content="item.text" />
          <AiClarificationHistory v-else-if="item.type === 'clarification'" :questions="item.questions" :answer="answers.get(item.id) || recentAnswers?.[item.id]?.answer" :question-answers="answerDetails.get(item.id)?.questionAnswers || recentAnswers?.[item.id]?.questionAnswers" :note="answerDetails.get(item.id)?.note || recentAnswers?.[item.id]?.note" :tags="[]" />
          <p v-else class="tool-progress" :class="{ 'is-running': item.status === 'running' }"><Database class="work-tool-icon" aria-hidden="true" /><span class="flow-text">{{ item.status === 'running' ? item.label : item.label.replace(/^正在/, '') }}</span><span v-if="typeof item.durationMs === 'number' && Number.isFinite(item.durationMs)" class="work-duration">{{ (item.durationMs / 1000).toFixed(1) }} 秒</span></p>
        </template>
        <p v-if="thinking" class="tool-progress is-running" role="status"><span class="flow-text">正在思考</span></p>
      </div>
    </article>
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
.work-tool-icon { width: 14px; height: 14px; flex: none; }
.work-duration { color: #64748b; font-variant-numeric: tabular-nums; }
.tool-progress { display: flex; align-items: center; gap: 7px; margin: 10px 0; font-size: 14px; color: #526276; }
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
