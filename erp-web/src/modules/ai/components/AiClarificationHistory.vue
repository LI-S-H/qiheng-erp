<script setup lang="ts">
import { Check, ChevronDown, MessageSquare } from 'lucide-vue-next';
import type { ClarificationQuestion } from '../graph-types';

defineProps<{ questions: ClarificationQuestion[]; answer?: string; tags: string[] }>();
</script>

<template>
  <details class="clarification-history" data-testid="clarification-history">
    <summary :aria-label="answer ? '回看补充信息' : '查看历史补充问题'">
      <span class="history-heading">
        <Check v-if="answer" class="size-3.5" aria-hidden="true" />
        <MessageSquare v-else class="size-3.5" aria-hidden="true" />
        {{ answer ? `已补充 · ${questions.length} 项` : '历史补充问题' }}
        <ChevronDown class="history-chevron size-3.5" aria-hidden="true" />
      </span>
      <span v-if="tags.length" class="answer-tags">
        <span v-for="(tag, index) in tags" :key="index" class="answer-tag" :title="`${questions[index]?.question}：${tag}`">{{ tag }}</span>
      </span>
      <span v-else-if="answer" class="history-hint">查看问题与回答</span>
    </summary>
    <div class="history-body">
      <ol><li v-for="question in questions" :key="question.id">{{ question.question }}</li></ol>
      <template v-if="answer"><h4>你的补充</h4><p>{{ answer }}</p></template>
      <p v-else class="history-hint">这是一条历史问题记录，当前不可提交。</p>
    </div>
  </details>
</template>

<style scoped>
.clarification-history { color: var(--foreground); font-size: 13px; }
summary { display: grid; gap: 9px; width: fit-content; max-width: 100%; cursor: pointer; list-style: none; border-radius: 6px; }
summary::-webkit-details-marker { display: none; }
summary:focus-visible { outline: 2px solid var(--ring); outline-offset: 4px; }
.history-heading { display: flex; align-items: center; gap: 7px; color: var(--muted-foreground); font-size: 12px; }
.history-chevron { transition: transform 160ms ease; }
details[open] .history-chevron { transform: rotate(180deg); }
.answer-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.answer-tag { max-width: min(280px, 100%); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 1px solid var(--border); border-radius: 6px; background: var(--muted); padding: 4px 9px; }
.history-body { margin-top: 14px; border-top: 1px solid var(--border); padding-top: 12px; line-height: 1.8; overflow-wrap: anywhere; }
ol { padding-left: 20px; list-style: decimal; }
h4 { margin-top: 12px; font-weight: 500; }
.history-body p { white-space: pre-wrap; }
.history-hint { color: var(--muted-foreground); font-size: 12px; }
@media (prefers-reduced-motion: reduce) { .history-chevron { transition: none; } }
</style>
