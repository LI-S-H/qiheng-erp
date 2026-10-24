<script setup lang="ts">
import { computed } from 'vue';
import { Bot, ClipboardList, UserRound } from 'lucide-vue-next';
import AiMarkdownContent from './AiMarkdownContent.vue';
import AiClarificationHistory from './AiClarificationHistory.vue';
import { submittedAnswerTags } from '../clarification-history';
import type { PlanningArtifact } from '../graph-types';
import type { AiConversationMessage } from '../types';

const props = defineProps<{
  messages: AiConversationMessage[];
  loading: boolean;
  pendingText: string;
  activeQuestionId?: string;
  userId?: string;
  conversationId?: string;
  activeArtifact?: PlanningArtifact | null;
}>();
function artifactExpanded(artifact: PlanningArtifact) {
  return Boolean(props.activeArtifact && JSON.stringify(props.activeArtifact) === JSON.stringify(artifact));
}
const emit = defineEmits<{ openPlan: [plan: PlanningArtifact] }>();
const answers = computed(() => {
  const result = new Map<string, string>();
  props.messages.forEach(message => {
    if (message.role === 'user' && message.content.type === 'TEXT' && message.content.data.replyToMessageId) {
      result.set(message.content.data.replyToMessageId, message.content.data.text);
    }
  });
  return result;
});
const questionIds = computed(() => new Set(props.messages.filter(message => message.content.type === 'CLARIFICATION').map(message => message.messageId)));
function answerTags(message: AiConversationMessage): string[] {
  if (message.content.type !== 'CLARIFICATION' || !props.userId || !props.conversationId) return [];
  const answer = answers.value.get(message.messageId);
  if (!answer || !message.content.data.pauseId) return [];
  try {
    const key = `erp.ai.draft.v2:${props.userId}:${props.conversationId}:submitted:${message.content.data.pauseId}`;
    return submittedAnswerTags(JSON.parse(localStorage.getItem(key) || 'null'), message.content.data.questions, answer);
  } catch { return []; }
}
</script>

<template>
  <div class="message-list" :aria-busy="loading">
    <p v-if="loading && !messages.length" class="list-note" role="status">正在读取会话记录…</p>
    <article
      v-for="message in messages"
      :key="message.messageId"
      class="message"
      :class="{ 'is-user': message.role === 'user' }"
      :data-message-id="message.messageId"
    >
      <span class="message-avatar" aria-hidden="true"
        ><UserRound v-if="message.role === 'user'" class="size-4" /><Bot v-else class="size-4"
      /></span>
      <div class="message-content">
        <div class="message-meta">
          <span>{{ message.role === 'user' ? '你' : '经营助手' }}</span
          ><time>{{ message.createdAt }}</time>
        </div>
        <details v-if="message.role === 'user' && message.content.type === 'TEXT' && message.content.data.replyToMessageId && questionIds.has(message.content.data.replyToMessageId)" class="answer-receipt">
          <summary>补充回答</summary><p class="message-bubble">{{ message.content.data.text }}</p>
        </details>
        <p v-else-if="message.role === 'user'" class="message-bubble">{{ message.content.data.text }}</p>
        <template v-else-if="message.content.type === 'CLARIFICATION'">
          <slot v-if="message.messageId === activeQuestionId" name="clarification" />
          <AiClarificationHistory v-else :questions="message.content.data.questions" :answer="answers.get(message.messageId)" :tags="answerTags(message)" />
        </template>
        <template v-else-if="message.content.type === 'PLAN'">
          <p>计划如下：</p>
          <button
            class="plan-artifact"
            type="button"
            :aria-expanded="artifactExpanded(message.content)"
            @click="emit('openPlan', message.content)"
          >
            <ClipboardList class="size-5" /><span
              ><strong>{{ message.content.data.plan.title }}</strong
              ><small
                >{{ message.content.data.plan.steps.length }} 个分析步骤 · 查看完整计划</small
              ></span
            >
          </button>
        </template>
        <template v-else-if="message.content.type === 'NEEDS'">
          <AiMarkdownContent v-if="message.content.data.text" class="needs-summary" :content="message.content.data.text" />
          <button class="plan-artifact" type="button" :aria-expanded="artifactExpanded(message.content)" @click="emit('openPlan', message.content)">
            <ClipboardList class="size-5" /><span><strong>已拆解 {{ message.content.data.needs.length }} 条需求</strong><small>查看需求与待取证据</small></span>
          </button>
        </template>
        <div v-else-if="message.content.type === 'ERROR'" class="saved-error" :class="{ 'is-incomplete': message.content.data.status === 'FORMAT_RETRY_EXHAUSTED' }" role="alert" :data-error-status="message.content.data.status">
          <span class="error-kind">{{ message.content.data.status === 'FORMAT_RETRY_EXHAUSTED' ? '规划未完成' : '执行失败' }}</span>
          <p>{{ message.content.data.text }}</p>
        </div>
        <AiMarkdownContent v-else :content="message.content.data.text" />
      </div>
    </article>
    <article v-if="pendingText" class="message is-user">
      <span class="message-avatar"><UserRound class="size-4" /></span>
      <div class="message-content">
        <div class="message-meta">你 · 正在提交</div>
        <p class="message-bubble">{{ pendingText }}</p>
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
.answer-receipt .message-bubble { margin-top: 8px; }
.message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}
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
.plan-artifact {
  display: flex;
  align-items: center;
  gap: 12px;
  width: fit-content;
  max-width: min(360px, 100%);
  margin-top: 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  padding: 12px 14px;
  text-align: left;
  color: var(--primary);
  cursor: pointer;
}
.plan-artifact:hover {
  background: var(--muted);
}
.plan-artifact > svg {
  flex-shrink: 0;
}
.plan-artifact > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
.plan-artifact strong {
  display: block;
  color: var(--foreground);
  font-size: 14px;
  font-weight: 500;
}
.plan-artifact small {
  display: block;
  margin-top: 4px;
  color: var(--muted-foreground);
  font-size: 12px;
}
.saved-error {
  color: var(--destructive);
  font-size: 14px;
  line-height: 1.8;
}
.saved-error.is-incomplete { color: var(--muted-foreground); }
.error-kind { display: block; margin-bottom: 3px; font-size: 12px; }
.needs-summary { margin-bottom: 12px; overflow-wrap: anywhere; line-height: 1.8; }
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
