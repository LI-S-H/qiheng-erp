<script setup lang="ts">
import { LoaderCircle, Send } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';

const inputMessage = defineModel<string>({ default: '' });
const props = withDefaults(defineProps<{
  placeholder?: string;
  sending?: boolean;
}>(), {
  placeholder: '输入经营问题，例如：分析 A4复印纸未来 14 天补货建议',
  sending: false,
});
const emit = defineEmits<{
  submit: [];
}>();

function submitMessage() {
  if (props.sending || !inputMessage.value.trim()) return;
  emit('submit');
}
</script>

<template>
  <div class="ai-composer-dock" data-ai-composer-dock>
    <form class="ai-composer" @submit.prevent="submitMessage">
      <Textarea
        v-model="inputMessage"
        class="ai-composer__input"
        :placeholder="placeholder"
        aria-label="经营问题"
        aria-describedby="ai-composer-hint"
        :disabled="sending"
        @keydown.enter.exact.prevent="submitMessage"
      />
      <span id="ai-composer-hint" class="sr-only">按 Enter 发送，按 Shift+Enter 换行</span>
      <Button
        class="ai-composer__send"
        type="submit"
        size="sm"
        :aria-label="sending ? '正在分析' : '发送消息'"
        :disabled="sending || !inputMessage.trim()"
      >
        <LoaderCircle v-if="sending" class="h-4 w-4 animate-spin" />
        <Send v-else class="h-4 w-4" />
      </Button>
    </form>
  </div>
</template>

<style scoped>
.ai-composer-dock {
  position: relative;
  z-index: 8;
  display: grid;
  border-top: 1px solid var(--border);
  background: white;
  padding: 14px 22px 18px;
}

.ai-composer {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) 38px;
  gap: 8px;
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #f8fafc;
  padding: 7px;
  transition:
    border-color var(--motion-duration-base) ease,
    box-shadow var(--motion-duration-base) ease,
    background-color var(--motion-duration-base) ease;
}

.ai-composer:hover {
  border-color: #d1d5db;
}

.ai-composer:focus-within {
  border-color: color-mix(in srgb, var(--primary) 38%, var(--border));
  background: white;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 10%, transparent);
}

.ai-composer__input {
  min-height: 38px;
  max-height: 96px;
  border: 0;
  background: transparent;
  resize: none;
  font-size: 13px;
  line-height: 1.5;
}

.ai-composer__input:focus-visible {
  border-color: transparent;
  box-shadow: none;
}

.ai-composer__send {
  width: 38px;
  height: 38px;
  align-self: end;
  padding: 0;
}

@media (max-width: 640px) {
  .ai-composer-dock {
    padding: 12px 14px 14px;
  }
}
</style>
