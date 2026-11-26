<script setup lang="ts">
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';

/**
 * 驳回草稿的公共弹层。
 *
 * 采用业务页 PromptDialog 的分层方式：遮罩抬到 z=70、内容抬到 z=80，
 * 父草稿弹窗通过 inert 锁住，形成真正的第二层，而不是两个 Dialog 同层叠加。
 */
defineOptions({ inheritAttrs: false });

const props = withDefaults(defineProps<{
  open: boolean;
  title: string;
  description: string;
  options: readonly string[];
  reasons: string[];
  note: string;
  error?: string;
  busy?: boolean;
  confirmText?: string;
  confirmTestId?: string;
  noteRequired?: boolean;
  notePlaceholder?: string;
}>(), {
  error: '',
  busy: false,
  confirmText: '提交驳回',
  confirmTestId: 'draft-reject-confirm',
  noteRequired: false,
  notePlaceholder: '补充说明，例如：只退 1 件，原因选商品损坏',
});

const emit = defineEmits<{
  'update:open': [value: boolean];
  'update:reasons': [value: string[]];
  'update:note': [value: string];
  confirm: [];
}>();

/** 切换驳回原因，保持父组件传入的顺序。 */
function toggleReason(reason: string) {
  emit('update:reasons', props.reasons.includes(reason)
    ? props.reasons.filter(item => item !== reason)
    : [...props.reasons, reason]);
}

function close() {
  if (props.busy) return;
  emit('update:open', false);
}
</script>

<template>
  <Dialog :open="open" @update:open="emit('update:open', $event)">
    <DialogContent
      v-bind="$attrs"
      placement="app-content"
      overlay-class="!z-[70] !bg-slate-950/30 !backdrop-blur-[3px]"
      class="!z-[80] sm:max-w-lg"
    >
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>{{ description }}</DialogDescription>
      </DialogHeader>
      <div class="space-y-3">
        <div class="flex flex-wrap gap-2">
          <button
            v-for="reason in options"
            :key="reason"
            type="button"
            class="rounded-md border px-3 py-1.5 text-sm transition-colors"
            :class="reasons.includes(reason) ? 'border-primary bg-primary/10 text-primary' : 'border-border text-muted-foreground hover:bg-muted/40'"
            :aria-pressed="reasons.includes(reason)"
            @click="toggleReason(reason)"
          >
            {{ reason }}
          </button>
        </div>
        <div class="space-y-1">
          <Label>补充要求 <span v-if="noteRequired" class="text-destructive">*</span></Label>
          <Textarea
            :model-value="note"
            rows="3"
            maxlength="500"
            :placeholder="notePlaceholder"
            @update:model-value="emit('update:note', String($event))"
          />
        </div>
        <p v-if="error" class="text-xs text-destructive">{{ error }}</p>
      </div>
      <DialogFooter>
        <Button variant="outline" :disabled="busy" @click="close">取消</Button>
        <Button
          variant="outline"
          class="border-rose-200 bg-rose-50 text-rose-700 hover:border-rose-300 hover:bg-rose-100 hover:text-rose-800"
          :disabled="busy"
          :data-testid="confirmTestId"
          @click="emit('confirm')"
        >
          {{ busy ? '提交中...' : confirmText }}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>