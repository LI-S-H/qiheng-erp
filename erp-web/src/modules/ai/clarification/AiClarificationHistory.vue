<script setup lang="ts">
import { ChevronDown, MessageSquare } from 'lucide-vue-next';
import type { ClarificationQuestion } from './clarification-types';

defineProps<{ questions: ClarificationQuestion[]; answer?: string; questionAnswers?: Record<string, string>; note?: string; tags: string[] }>();
</script>

<template>
  <details class="clarification-history" data-testid="clarification-history">
    <summary :aria-label="answer ? '回看补充信息' : '查看历史补充问题'">
      <span class="history-heading">
        <MessageSquare class="size-3.5" aria-hidden="true" />
        补充信息
        <ChevronDown class="history-chevron size-3.5" aria-hidden="true" />
      </span>
    </summary>
    <div class="history-body">
      <ol><li v-for="question in questions" :key="question.id"><span class="question-text">{{ question.question }}</span><p v-if="questionAnswers?.[question.id]" class="history-answer">{{ questionAnswers[question.id] }}</p></li></ol>
      <template v-if="answer && !questionAnswers"><h4>你的补充</h4><p>{{ answer }}</p></template>
      <template v-if="note"><h4>其他补充</h4><p>{{ note }}</p></template>
      <p v-if="!answer" class="history-hint">这是一条历史问题记录，当前不可提交。</p>
    </div>
  </details>
</template>

<style scoped>
.clarification-history { color: var(--foreground); font-size: 13px; margin: 10px 0; }
summary { display: block; width: fit-content; max-width: 100%; cursor: pointer; list-style: none; border-radius: 6px; }
summary::-webkit-details-marker { display: none; }
summary:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }
.history-heading { display: flex; align-items: center; gap: 7px; color: #4b5e73; font-size: 13px; }
.history-chevron { transition: transform 160ms ease; }
details[open] .history-chevron { transform: rotate(180deg); }
.history-body { margin: 6px 0 0 21px; line-height: 1.6; overflow-wrap: anywhere; }
ol { display: grid; gap: 8px; padding-left: 17px; list-style: decimal; }
.question-text { color: #475569; }
.history-answer { margin-top: 2px; color: #1e293b; white-space: pre-wrap; }
h4 { margin-top: 8px; color: #475569; font-weight: 500; }
.history-body p { white-space: pre-wrap; }
.history-hint { color: var(--muted-foreground); font-size: 12px; }
@media (prefers-reduced-motion: reduce) { .history-chevron { transition: none; } }
</style>
