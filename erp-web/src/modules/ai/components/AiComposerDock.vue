<script setup lang="ts">
import { LoaderCircle, LockKeyhole, Send } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

const inputMessage = defineModel<string>({ default: '' });
const props = withDefaults(
  defineProps<{
    placeholder?: string;
    sending?: boolean;
    disabled?: boolean;
    clarification?: boolean;
    awaitingAnswer?: boolean;
    error?: string;
  }>(),
  {
    placeholder: '描述你的问题或分析目标…',
    sending: false,
    disabled: false,
    clarification: false,
    awaitingAnswer: false,
    error: '',
  },
);
const emit = defineEmits<{ submit: [] }>();

function submitMessage() {
  if (props.awaitingAnswer || props.sending || props.disabled || (!props.clarification && !inputMessage.value.trim())) {
    return;
  }
  emit('submit');
}
function onEnter(event: KeyboardEvent) {
  if (event.isComposing) {
    return;
  }
  event.preventDefault();
  submitMessage();
}
</script>

<template>
  <div class="ai-composer-dock" :class="{ 'has-questions': awaitingAnswer }" data-ai-composer-dock>
    <div class="composer-inner">
      <slot name="question" />
      <div v-if="awaitingAnswer" class="ai-composer-placeholder" data-ai-composer-placeholder role="status" aria-label="等待回答补充问题">
        <LockKeyhole class="placeholder-lock" aria-hidden="true" />
        <span>先完成上方补充，助手将继续处理</span>
        <span class="placeholder-send" aria-hidden="true"><Send />发送</span>
      </div>
      <div v-else-if="clarification" class="composer-mode">
        补充信息<span>选择上方答案，也可以在这里补充说明</span>
      </div>
      <form v-if="!awaitingAnswer" class="ai-composer" @submit.prevent="submitMessage">
        <Textarea
          id="graph-query"
          v-model="inputMessage"
          class="ai-composer__input"
          :placeholder="clarification ? '其他补充（选填）…' : placeholder"
          rows="1"
          :aria-label="clarification ? '其他补充' : '经营问题'"
          :aria-invalid="Boolean(error)"
          aria-describedby="ai-composer-hint ai-composer-error"
          :disabled="sending || disabled"
          maxlength="10000"
          @keydown.enter.exact="onEnter"
        />
        <Button
          class="ai-composer__send"
          type="submit"
          size="sm"
          :aria-label="clarification ? '提交补充信息' : '发送需求'"
          :disabled="sending || disabled || (!clarification && !inputMessage.trim())"
        >
          <LoaderCircle v-if="sending" class="size-4 animate-spin" />
          <Send v-else class="size-4" />
          <span>{{ clarification ? '确认并继续' : '发送' }}</span>
        </Button>
      </form>
      <p v-if="error && !awaitingAnswer" id="ai-composer-error" class="composer-error" role="alert">{{ error }}</p>
      <div id="ai-composer-hint" class="composer-hint">
        <span>{{ awaitingAnswer ? '回答过程中保留输入位置，避免视线来回跳动' : 'Enter 发送 · Shift + Enter 换行' }}</span><span>当前支持需求规划与信息澄清</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-composer-dock {
  position: relative;
  z-index: 2;
  border-top: 1px solid var(--border);
  background: var(--background);
  padding: 16px 28px 12px;
}
.composer-inner {
  max-width: 820px;
  margin: 0 auto;
}
.ai-composer-dock.has-questions {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: min(600px, calc(100dvh - 210px));
}
.has-questions .composer-inner {
  display: grid;
  grid-template-rows: minmax(0, 1fr) auto auto;
  min-height: 0;
  width: 100%;
  flex: 1;
}
.ai-composer-placeholder {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  padding: 8px 11px;
  border: 1px solid #e9edf3;
  border-radius: 8px;
  background: #fafbfd;
  color: #9ba9bc;
  font-size: 12px;
  line-height: 21px;
}
.placeholder-lock { width: 13px; height: 13px; flex-shrink: 0; color: #a0aec0; }
.placeholder-send { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: #a0aec0; font-size: 11px; white-space: nowrap; }
.placeholder-send svg { width: 12px; height: 12px; }
.composer-mode {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--primary);
  font-size: 13px;
  font-weight: 500;
}
.composer-mode span {
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 400;
}
.ai-composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  padding: 8px;
}
.ai-composer:focus-within {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--primary) 10%, transparent);
}
.ai-composer__input {
  min-height: 40px;
  max-height: 160px;
  border: 0;
  background: transparent;
  resize: vertical;
  padding: 8px 10px;
  font-size: 14px;
  line-height: 24px;
}
.ai-composer__input:focus-visible {
  box-shadow: none;
}
/* 单行输入与发送按钮纵向对齐；输入多行时仍从左侧自然排版。 */
.ai-composer .ai-composer__input {
  min-height: 49px;
  padding-block: 13px;
  resize: none;
}
.ai-composer .ai-composer__send { align-self: center; margin-bottom: 0; }
.ai-composer__send {
  height: 36px;
  margin-bottom: 2px;
  font-size: 13px;
}
.composer-hint {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 4px 16px;
  margin-top: 8px;
  color: var(--muted-foreground);
  font-size: 12px;
}
.composer-error {
  margin-top: 6px;
  color: var(--destructive);
  font-size: 12px;
}
@media (max-width: 640px) {
  .ai-composer-dock {
    padding: 12px 14px;
  }
  .composer-hint span:last-child {
    display: none;
  }
  .ai-composer__send span {
    display: none;
  }
  .ai-composer-placeholder { font-size: 11px; }
}
</style>
