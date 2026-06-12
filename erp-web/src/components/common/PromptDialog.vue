<script setup lang="ts">
import { ref, watch } from 'vue';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface Props {
  open: boolean;
  title: string;
  description?: string;
  inputType?: string;
  inputPattern?: RegExp;
  inputErrorMessage?: string;
  inputPlaceholder?: string;
  confirmText?: string;
  cancelText?: string;
  loading?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  description: '',
  inputType: 'text',
  inputErrorMessage: '输入格式不正确',
  inputPlaceholder: '',
  confirmText: '确认',
  cancelText: '取消',
  loading: false,
});

const emit = defineEmits<{
  'update:open': [value: boolean];
  confirm: [value: string];
  cancel: [];
}>();

const inputValue = ref('');
const inputError = ref('');

watch(() => props.open, (val) => {
  if (val) {
    inputValue.value = '';
    inputError.value = '';
  }
});

function handleConfirm() {
  if (props.loading) return;
  if (props.inputPattern && !props.inputPattern.test(inputValue.value)) {
    inputError.value = props.inputErrorMessage;
    return;
  }
  emit('confirm', inputValue.value);
}

function handleCancel() {
  emit('cancel');
  emit('update:open', false);
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault();
    handleConfirm();
  }
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogContent class="sm:max-w-[425px]">
      <DialogHeader>
        <DialogTitle>{{ props.title }}</DialogTitle>
        <DialogDescription v-if="props.description">{{ props.description }}</DialogDescription>
      </DialogHeader>
      <div class="grid gap-2 py-2">
        <Label v-if="props.inputPlaceholder || props.description" class="text-sm text-muted-foreground">
          {{ props.inputPlaceholder || '请输入' }}
        </Label>
        <Input
          v-model="inputValue"
          :type="props.inputType"
          :placeholder="props.inputPlaceholder"
          @keydown="handleKeydown"
        />
        <p v-if="inputError" class="text-sm text-destructive">{{ inputError }}</p>
      </div>
      <DialogFooter>
        <Button variant="outline" :disabled="props.loading" @click="handleCancel">{{ props.cancelText }}</Button>
        <Button :disabled="props.loading" @click="handleConfirm">{{ props.loading ? '处理中...' : props.confirmText }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
