<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';
import { AlignLeft, LoaderCircle, MessageSquare } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import type { ClarificationDraft, ClarificationQuestion } from './clarification-types';

const props = defineProps<{
  questions: ClarificationQuestion[];
  busy: boolean;
  compact?: boolean;
}>();
const draft = defineModel<ClarificationDraft>({ required: true });
const emit = defineEmits<{ submit: [answer: string] }>();
const formElement = ref<HTMLFormElement | null>(null);
const errors = ref<Record<string, string>>({});
const formError = ref('');
const formId = useId();
const customValue = '__custom_answer__';
const transitioning = ref(false);
const pendingAdvance = ref(false);
const controlsLocked = computed(() => props.busy || transitioning.value || pendingAdvance.value);
let advanceTimer: ReturnType<typeof setTimeout> | undefined;
let selectionRevision = 0;
let disposed = false;

function cancelAutomaticAdvance() {
  selectionRevision += 1;
  if (advanceTimer !== undefined) clearTimeout(advanceTimer);
  advanceTimer = undefined;
  pendingAdvance.value = false;
}

function fixedOptions(question: ClarificationQuestion) {
  // “其他”由组件提供唯一自填入口，避免与模型同名候选产生两种相反行为。
  return [...new Set(question.options)].filter(option => !['其他', customValue].includes(option.trim())).slice(0, 3);
}

function customText(question: ClarificationQuestion) {
  const answer = draft.value.answers[question.id];
  if (question.type === 'MULTIPLE_CHOICE') return answer?.custom || '';
  // 旧草稿可能选过现已收进“其他”的候选，保留原答案供回看和修改。
  if (answer?.selected && answer.selected !== customValue && question.options.includes(answer.selected) && !fixedOptions(question).includes(answer.selected)) {
    return answer.selected;
  }
  return answer?.custom || '';
}

function answerText(question: ClarificationQuestion) {
  const answer = draft.value.answers[question.id];
  if (question.type === 'MULTIPLE_CHOICE') {
    return [...(answer?.selectedOptions || []), answer?.custom.trim() || ''].filter(Boolean).join('、');
  }
  if (!question.options.length || answer?.selected === customValue) return answer?.custom.trim() || '';
  return answer && question.options.includes(answer.selected) ? answer.selected : '';
}

const activeIndex = computed<number | null>(() => {
  const current = draft.value.currentQuestionId;
  if (current === null) return null;
  if (typeof current === 'string') {
    const found = props.questions.findIndex(question => question.id === current);
    if (found !== -1) return found;
  }
  // 兼容无步骤信息的旧草稿，以及父级挂载后才恢复草稿的情况。
  const missing = props.questions.findIndex(question => !answerText(question));
  return missing === -1 ? null : missing;
});
const activeQuestion = computed(() => activeIndex.value === null ? undefined : props.questions[activeIndex.value]);
const currentOptions = computed(() => activeQuestion.value?.options.length
  ? [...fixedOptions(activeQuestion.value), customValue]
  : []);
const usesCustom = computed(() => {
  const question = activeQuestion.value;
  if (!question) return false;
  const selected = draft.value.answers[question.id]?.selected;
  if (question.type === 'MULTIPLE_CHOICE') return selected === customValue;
  return !question.options.length || selected === customValue ||
    Boolean(selected && !fixedOptions(question).includes(selected));
});
const stepKey = computed(() => activeQuestion.value ? `question:${activeQuestion.value.id}` : 'review');

function focusCurrent() {
  void nextTick(() => {
    if (controlsLocked.value || !formElement.value) return;
    const body = formElement.value.querySelector<HTMLElement>('[data-testid="clarification-body"]');
    if (body) body.scrollTop = 0;
    const selector = usesCustom.value ? '[data-question-answer]'
      : activeQuestion.value ? '[role="radio"][aria-checked="true"], [role="radio"][tabindex="0"], [role="checkbox"][tabindex="0"]'
        : '[data-question-title]';
    formElement.value.querySelector<HTMLElement>(selector)?.focus({ preventScroll: true });
  });
}

function activate(index: number | null) {
  if (props.busy || transitioning.value) return;
  cancelAutomaticAdvance();
  const changesStep = index !== activeIndex.value;
  if (changesStep) transitioning.value = true;
  draft.value = {
    ...draft.value,
    currentQuestionId: index === null ? null : props.questions[index]!.id,
  };
  formError.value = '';
  if (!changesStep) focusCurrent();
}

function updateAnswer(field: 'selected' | 'custom', value: string) {
  const question = activeQuestion.value;
  if (controlsLocked.value || !question) return;
  draft.value = {
    ...draft.value,
    currentQuestionId: question.id,
    answers: {
      ...draft.value.answers,
      [question.id]: {
        selected: draft.value.answers[question.id]?.selected || '',
        custom: draft.value.answers[question.id]?.custom || '',
        selectedOptions: draft.value.answers[question.id]?.selectedOptions || [],
        ...(field === 'custom' ? { selected: customValue } : {}),
        ...(field === 'selected' && value === customValue ? { custom: customText(question) } : {}),
        [field]: value,
      },
    },
  };
  delete errors.value[question.id];
  formError.value = '';
}

function questionError(question: ClarificationQuestion) {
  if (answerText(question)) return '';
  if (question.type === 'MULTIPLE_CHOICE') return '请选择至少一项，或自行填写';
  return !question.options.length || draft.value.answers[question.id]?.selected === customValue
    ? '请填写这项信息'
    : '请选择一项，或自行填写';
}

function advance() {
  const question = activeQuestion.value;
  const index = activeIndex.value;
  if (controlsLocked.value || !question || index === null) return;
  const error = questionError(question);
  if (error) {
    errors.value = { ...errors.value, [question.id]: error };
    void nextTick(() => formElement.value?.querySelector<HTMLElement>('[data-question-answer], [role="radio"], [role="checkbox"]')?.focus());
    return;
  }
  // 回改已答问题后仍按题目顺序前进，不跳过其余已答题。
  activate(index + 1 < props.questions.length ? index + 1 : null);
}

function confirmOption(value: string) {
  const question = activeQuestion.value;
  const questionId = question?.id;
  if (controlsLocked.value || !question || !questionId) return;
  if (question.type === 'MULTIPLE_CHOICE') {
    const previous = draft.value.answers[questionId] || { selected: '', custom: '', selectedOptions: [] };
    const selected = previous.selectedOptions || [];
    const options = value === customValue ? selected : selected.includes(value)
      ? selected.filter(item => item !== value) : [...selected, value];
    // 多选时保持当前题，直到用户主动确认，避免选中第一项后自动进入摘要。
    draft.value = { ...draft.value, currentQuestionId: questionId, answers: { ...draft.value.answers, [questionId]: {
      ...previous, selectedOptions: options,
      selected: value === customValue ? (previous.selected === customValue ? '' : customValue) : previous.selected,
      custom: value === customValue && previous.selected === customValue ? '' : previous.custom,
    } } };
    delete errors.value[questionId];
    if (value === customValue) focusCurrent();
    return;
  }
  cancelAutomaticAdvance();
  updateAnswer('selected', value);
  const revision = selectionRevision;
  if (value !== customValue) pendingAdvance.value = true;
  // 先等待父级 v-model 回写，再保留短暂选中反馈；旧题和重复点击不能推进新题。
  void nextTick(() => {
    if (disposed || revision !== selectionRevision || !formElement.value || props.busy ||
      transitioning.value || activeQuestion.value?.id !== questionId ||
      draft.value.answers[questionId]?.selected !== value) return;
    if (value === customValue) focusCurrent();
    else {
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      advanceTimer = setTimeout(() => {
        advanceTimer = undefined;
        if (disposed || revision !== selectionRevision || props.busy || transitioning.value ||
          activeQuestion.value?.id !== questionId || draft.value.answers[questionId]?.selected !== value) return;
        pendingAdvance.value = false;
        advance();
      }, reduced ? 0 : 150);
    }
  });
}

function optionKeydown(event: KeyboardEvent, index: number) {
  if (controlsLocked.value || event.isComposing || event.keyCode === 229) return;
  if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) {
    event.preventDefault();
    const direction = event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 1;
    const next = (index + direction + currentOptions.value.length) % currentOptions.value.length;
    if (activeQuestion.value?.type !== 'MULTIPLE_CHOICE') updateAnswer('selected', currentOptions.value[next]!);
    void nextTick(() => formElement.value?.querySelector<HTMLElement>(`[data-option-index="${next}"]`)?.focus());
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    if (!event.repeat) confirmOption(currentOptions.value[index]!);
  }
}

function questionKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  if (!event.repeat && !controlsLocked.value) advance();
}

function formKeydown(event: KeyboardEvent) {
  if (controlsLocked.value || event.defaultPrevented || event.isComposing || event.keyCode === 229 ||
    event.ctrlKey || event.metaKey || event.altKey || event.repeat) return;
  const target = event.target as HTMLElement;
  if (target.closest('input, textarea, [contenteditable="true"]')) return;
  if (activeQuestion.value && /^[1-4]$/.test(event.key)) {
    const value = currentOptions.value[Number(event.key) - 1];
    if (value !== undefined) {
      event.preventDefault();
      confirmOption(value);
    }
  } else if (event.key === 'Enter' && activeQuestion.value && !target.closest('button')) {
    event.preventDefault();
    advance();
  }
}

function beforeStepLeave(element: Element) {
  cancelAutomaticAdvance();
  transitioning.value = true;
  // out-in 期间离场旧题仍在 DOM 中，必须阻止它继续接受点击和键盘输入。
  (element as HTMLElement).inert = true;
}

function afterStepEnter(element?: Element) {
  if (element) (element as HTMLElement).inert = false;
  transitioning.value = false;
  focusCurrent();
}

function submit() {
  if (props.busy || transitioning.value) return;
  // 最终提交立即处理，不能被尚未到期的选项反馈计时器吞掉。
  cancelAutomaticAdvance();
  const nextErrors: Record<string, string> = {};
  const answers: string[] = [];
  props.questions.forEach(question => {
    const error = questionError(question);
    if (error) nextErrors[question.id] = error;
    else answers.push(`${question.question}\n${answerText(question)}`);
  });
  errors.value = nextErrors;
  const missing = props.questions.findIndex(question => Boolean(nextErrors[question.id]));
  if (missing !== -1) {
    activate(missing);
    return;
  }
  const note = draft.value.note.trim();
  if (note) answers.push(`其他补充：${note}`);
  const answer = answers.join('\n\n');
  if (!answer || answer.length > 10_000) {
    formError.value = '回答合计需为 1 到 10000 字，请检查后提交。';
    return;
  }
  emit('submit', answer);
}

onMounted(focusCurrent);
watch(() => props.busy, (busy, previous) => {
  if (busy) cancelAutomaticAdvance();
  if (previous && !busy) focusCurrent();
});
watch(stepKey, cancelAutomaticAdvance);
watch(() => props.questions, cancelAutomaticAdvance, { deep: true });
onBeforeUnmount(() => {
  disposed = true;
  cancelAutomaticAdvance();
});
defineExpose({ submit });
</script>

<template>
  <form ref="formElement" class="agent-question" data-testid="clarification-form" :aria-busy="busy"
    :inert="transitioning || undefined"
    @keydown="formKeydown" @submit.prevent="submit">
    <div class="question-header">
      <span class="question-progress" data-testid="clarification-progress" role="status">
        <MessageSquare class="size-3" aria-hidden="true" />
        {{ activeIndex === null ? '补充信息' : `问题 ${(activeIndex ?? 0) + 1} / ${questions.length}` }}
      </span>
    </div>

    <div class="question-body" data-testid="clarification-body">
      <Transition name="question-step" mode="out-in" @before-leave="beforeStepLeave" @after-enter="afterStepEnter"
        @enter-cancelled="afterStepEnter" @leave-cancelled="afterStepEnter">
      <section :key="stepKey" class="question-step" :aria-labelledby="`${formId}-question-title`">
      <template v-if="activeQuestion && activeIndex !== null">
        <h3 :id="`${formId}-question-title`" tabindex="-1" data-question-title>
          {{ activeQuestion.question }}
        </h3>
        <div v-if="currentOptions.length" class="answer-options" :role="activeQuestion.type === 'MULTIPLE_CHOICE' ? 'group' : 'radiogroup'" aria-required="true"
          :aria-labelledby="`${formId}-question-title`" :aria-invalid="Boolean(errors[activeQuestion.id])"
          :aria-describedby="errors[activeQuestion.id] ? `${formId}-error` : undefined">
          <div v-for="(option, index) in currentOptions" :key="option" class="answer-row"
            :class="{ 'is-custom': option === customValue, 'is-editing': option === customValue && usesCustom }">
            <button type="button" :role="activeQuestion.type === 'MULTIPLE_CHOICE' ? 'checkbox' : 'radio'"
            class="question-option" :class="{ 'is-selected': option === customValue ? usesCustom : activeQuestion.type === 'MULTIPLE_CHOICE' ? draft.answers[activeQuestion.id]?.selectedOptions?.includes(option) : draft.answers[activeQuestion.id]?.selected === option }"
            :aria-label="option === customValue ? '其他' : option"
            :aria-checked="option === customValue ? usesCustom : activeQuestion.type === 'MULTIPLE_CHOICE' ? Boolean(draft.answers[activeQuestion.id]?.selectedOptions?.includes(option)) : draft.answers[activeQuestion.id]?.selected === option" :disabled="controlsLocked"
            :tabindex="(option === customValue && usesCustom) || draft.answers[activeQuestion.id]?.selected === option || (!draft.answers[activeQuestion.id]?.selected && index === 0) ? 0 : -1"
            :data-option-index="index" :data-testid="`question-${activeIndex}-option-${index}`"
            @click="confirmOption(option)" @keydown="optionKeydown($event, index)">
            <span class="option-indicator" aria-hidden="true"><span /></span>
            <span class="option-label">{{ option === customValue ? '其他' : option }}</span>
          </button>
          <Textarea v-if="option === customValue && usesCustom" :id="`${formId}-answer`" data-question-answer
            :aria-label="activeQuestion.question" :aria-required="true" :disabled="controlsLocked"
            :aria-invalid="Boolean(errors[activeQuestion.id])"
            :aria-describedby="errors[activeQuestion.id] ? `${formId}-error` : undefined"
            :model-value="customText(activeQuestion)" maxlength="10000"
            placeholder="输入你需要的具体范围…" class="question-answer custom-text" @keydown="questionKeydown"
            @update:model-value="updateAnswer('custom', String($event))" />
          </div>
        </div>
        <div v-if="usesCustom && !currentOptions.length" class="response-editor" data-testid="clarification-response-editor">
          <div class="response-editor-top">
            <label class="editor-label" :for="`${formId}-answer`"><AlignLeft class="size-3" aria-hidden="true" />你的回答</label>
            <span class="editor-required">必填</span>
          </div>
          <Textarea :id="`${formId}-answer`" data-question-answer
            :aria-label="activeQuestion.question" :aria-required="true" :disabled="controlsLocked"
            :aria-invalid="Boolean(errors[activeQuestion.id])"
            :aria-describedby="errors[activeQuestion.id] ? `${formId}-error` : undefined"
            :model-value="customText(activeQuestion)" maxlength="10000"
            placeholder="在这里填写你的回答…" class="question-answer free-input" @keydown="questionKeydown"
            @update:model-value="updateAnswer('custom', String($event))" />
          <div class="response-editor-foot">
            <span>按你的回答继续梳理需求</span>
            <span class="editor-count" data-testid="clarification-response-count">{{ customText(activeQuestion).length }} 字</span>
          </div>
        </div>
        <p v-if="errors[activeQuestion.id]" :id="`${formId}-error`" class="field-error" role="alert">
          {{ errors[activeQuestion.id] }}
        </p>
      </template>
      <div v-else class="answer-review" data-testid="clarification-review">
        <h3 :id="`${formId}-question-title`" tabindex="-1" data-question-title>补充信息</h3>
        <label :for="`${formId}-note`" class="note-label">其他补充 <span>（选填）</span></label>
        <Textarea :id="`${formId}-note`" aria-label="其他补充" :model-value="draft.note" :disabled="controlsLocked"
          placeholder="还有什么需要助手知道？" maxlength="10000" class="question-answer note-text"
          @update:model-value="draft = { ...draft, note: String($event) }" />
      </div>
      <p v-if="formError" class="field-error" role="alert">{{ formError }}</p>
      </section>
      </Transition>
    </div>

    <div class="question-footer">
      <span class="keyboard-hint" v-if="activeQuestion">{{ usesCustom ? 'Enter 确认 · Shift+Enter 换行' : `↑↓ 选择 · 1–${currentOptions.length} 快选 · Enter 确认` }}</span>
      <span class="keyboard-hint" v-else>确认后一次提交全部回答</span>
      <div class="question-actions">
        <Button type="button" variant="ghost" size="sm" :disabled="busy || transitioning || activeIndex === 0"
          @click="activate(activeIndex === null ? questions.length - 1 : activeIndex - 1)">
          上一题
        </Button>
        <Button v-if="activeQuestion" type="button" size="sm" :disabled="controlsLocked" @click="advance">
          {{ activeIndex === questions.length - 1 ? '确认回答' : '下一题' }}
        </Button>
        <Button v-else type="submit" size="sm" aria-label="提交回答" :disabled="controlsLocked">
          <LoaderCircle v-if="busy" class="size-3.5 animate-spin" aria-hidden="true" />
          {{ busy ? '提交中' : '提交回答' }}
        </Button>
      </div>
    </div>
  </form>
</template>

<style scoped>
/* 沿用项目浅色主题；组件宽高由输入区分配，只有正文在受限高度内滚动。 */
.agent-question {
  --question-line: #e5ecf5;
  --question-copy: #657d9e;
  --question-heading: #425a78;
  --question-ease: cubic-bezier(.38, 0, .24, 1);
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  width: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--question-line);
  border-radius: 9px;
  background: var(--card);
  color: var(--question-copy);
  box-shadow: 0 1px 3px #3b567315, 0 8px 24px #3b56730c;
}
.question-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 17px 0;
}
.question-progress { display: inline-flex; align-items: center; gap: 6px; color: #8191a8; font-size: 11px; line-height: 20px; font-variant-numeric: tabular-nums; }
.question-body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-width: thin; scrollbar-color: #c8d1de transparent; }
.question-step { padding: 9px 16px 13px; }
.question-body h3 { margin: 0 0 13px; color: var(--question-heading); font-size: 13px; font-weight: 500; line-height: 22px; overflow-wrap: anywhere; }
.answer-options { display: flex; flex-direction: column; gap: 5px; }
.answer-row { min-width: 0; }
.question-option { display: flex; align-items: center; gap: 9px; box-sizing: border-box; width: 100%; min-height: 34px; padding: 7px 10px; border: 0; border-radius: 6px; background: #f7f9fc; box-shadow: inset 0 0 0 1px transparent; color: var(--question-copy); text-align: start; cursor: pointer; touch-action: manipulation; transition: background-color 200ms var(--question-ease), box-shadow 200ms var(--question-ease), transform 200ms var(--question-ease), color 200ms var(--question-ease); }
.question-option:hover:not(:disabled) { background: #f0f4fa; color: #4c678a; box-shadow: 0 2px 6px #314d7309, inset 0 0 0 1px #dce5f2; transform: translateY(-1px); }
.question-option:active:not(:disabled) { transform: translateY(0); }
.question-option.is-selected { background: color-mix(in srgb, var(--primary) 9%, #fff); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 42%, #fff); color: var(--primary); }
.question-option.is-selected:hover:not(:disabled) { background: color-mix(in srgb, var(--primary) 12%, #fff); box-shadow: 0 2px 6px #3267d60b, inset 0 0 0 1px color-mix(in srgb, var(--primary) 55%, #fff); }
.option-label { flex: 1; min-width: 0; font-size: 13px; font-weight: 400; line-height: 20px; overflow-wrap: anywhere; }
.option-indicator { display: grid; place-items: center; flex: 0 0 14px; width: 14px; height: 14px; border: 1px solid #b6c4d8; border-radius: 50%; background: #fff; }
.option-indicator > span { width: 5px; height: 5px; border-radius: 50%; background: #fff; opacity: 0; transform: scale(.65); transition: opacity 200ms var(--question-ease), transform 200ms var(--question-ease); }
.is-selected .option-indicator { background: var(--primary); border-color: var(--primary); }
.is-selected .option-indicator > span { opacity: 1; transform: scale(1); }
.answer-row.is-editing { overflow: hidden; border-radius: 6px; background: color-mix(in srgb, var(--primary) 9%, #fff); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--primary) 42%, #fff); }
.is-editing .question-option { background: transparent; box-shadow: none; transform: none; }
.is-editing .question-option:hover:not(:disabled) { background: transparent; box-shadow: none; transform: none; }
.question-answer { min-height: 62px; max-height: 140px; margin: 0; resize: none; border: 1px solid #dce5f2; border-radius: 5px; background: #f8fafc; color: #405772; font-size: 13px; line-height: 20px; caret-color: var(--primary); scrollbar-width: thin; scrollbar-color: #c8d1de transparent; }
.custom-text { width: calc(100% - 46px); height: 36px; min-height: 36px; margin: 1px 10px 9px 36px; padding: 7px 9px; }
.response-editor { overflow: hidden; border: 1px solid #e0e7f1; border-radius: 8px; background: #fff; box-shadow: 0 2px 5px #314d7305; transition: border-color 200ms var(--question-ease), box-shadow 200ms var(--question-ease); }
.response-editor:hover { border-color: #c9d6e9; box-shadow: 0 3px 10px #314d7308; }
.response-editor:focus-within { border-color: color-mix(in srgb, var(--primary) 48%, #fff); box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 4%, transparent), 0 3px 10px #314d7306; }
.response-editor-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px 0; font-size: 11px; line-height: 20px; }
.editor-label { display: flex; align-items: center; gap: 6px; color: var(--question-copy); }
.editor-required { color: #98a5b7; font-size: 10px; }
.response-editor .free-input { display: block; width: 100%; height: 88px; min-height: 88px; padding: 8px 12px 10px; border: 0; border-radius: 0; background: transparent; line-height: 23px; }
.response-editor .free-input:focus-visible { border: 0; outline: none; box-shadow: none; }
.response-editor-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px; border-top: 1px solid #edf1f6; background: #fafbfd; color: #8b9bb1; font-size: 10px; line-height: 17px; }
.editor-count { flex-shrink: 0; color: #98a5b7; font-variant-numeric: tabular-nums; }
.review-answers { display: grid; gap: 0; margin: 0 -16px 12px; }
.review-answer { position: relative; isolation: isolate; display: flex; align-items: center; gap: 12px; width: 100%; min-width: 0; min-height: 64px; padding: 11px 16px; border: 0; border-radius: 0; background: transparent; text-align: left; color: #8198ba; cursor: pointer; }
.review-answer::before { position: absolute; z-index: -1; inset: 0; content: ''; background: #f1f5fd; box-shadow: inset 0 1px 0 #e4ecfb, inset 0 -1px 0 #e4ecfb; opacity: 0; transition: opacity 200ms var(--question-ease); }
.review-answer:hover:not(:disabled)::before, .review-answer:focus-visible::before { opacity: 1; }
.review-answer:hover:not(:disabled) > svg { color: var(--primary); }
.review-copy { display: grid; gap: 4px; min-width: 0; flex: 1; }
.review-question { font-size: 11px; line-height: 20px; color: #8b9bb1; overflow-wrap: anywhere; }
.review-value { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; color: #4c678a; font-size: 13px; line-height: 21px; white-space: pre-wrap; }
.review-value.is-missing { color: var(--destructive); }
.note-label { display: block; margin-bottom: 6px; color: #7a8ea9; font-size: 11px; }
.note-label span { color: #98a5b7; }
.note-text { min-height: 34px; height: 34px; padding: 6px 10px; font-size: 12px; }
.question-footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 10px; padding: 9px 13px; border-top: 1px solid #edf1f6; background: #fbfcfe; }
.keyboard-hint { color: #8b9bb1; font-size: 10px; line-height: 20px; }
.question-actions { display: flex; gap: 6px; margin-left: auto; }
.question-actions :deep(button) { font-size: 13px; }
.field-error { margin-top: 7px; color: var(--destructive); font-size: 12px; }
button:disabled { cursor: not-allowed; }
.agent-question[aria-busy='true'] .question-option:disabled, .question-actions button:disabled { opacity: .5; }
button:focus-visible { outline: 2px solid color-mix(in srgb, var(--primary) 42%, #fff); outline-offset: -2px; }
.question-step-enter-active { transition: opacity 240ms var(--question-ease), transform 240ms var(--question-ease); }
.question-step-leave-active { transition: opacity 120ms var(--question-ease), transform 120ms var(--question-ease); }
.question-step-enter-from { opacity: 0; transform: translateY(6px); }
.question-step-leave-to { opacity: 0; transform: translateY(-4px); }
@media (max-width: 640px) {
  .question-header { padding-inline: 13px; }
  .question-step { padding-inline: 12px; }
  .review-answers { margin-inline: -12px; }
  .review-answer { padding-inline: 12px; }
  .question-footer { padding: 8px 10px; }
  .keyboard-hint { font-size: 9px; }
}
@media (prefers-reduced-motion: reduce) {
  .question-step-enter-active, .question-step-leave-active, .question-option, .option-indicator > span,
  .response-editor, .review-answer::before { transition: none; }
  .question-step-enter-from, .question-step-leave-to, .question-option:hover:not(:disabled) { transform: none; }
}
</style>
