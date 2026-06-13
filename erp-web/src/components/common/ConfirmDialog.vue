<script setup lang="ts">
import { computed } from 'vue';
import { CircleHelp, Trash2, TriangleAlert } from 'lucide-vue-next';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';

interface Props {
  open: boolean;
  title: string;
  description: string;
  confirmText?: string;
  cancelText?: string;
  variant?: 'default' | 'destructive' | 'warning';
  loading?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  confirmText: '确认',
  cancelText: '取消',
  variant: 'default',
  loading: false,
});

const emit = defineEmits<{
  'update:open': [value: boolean];
  confirm: [];
  cancel: [];
}>();

const iconComponent = computed(() => {
  if (props.variant === 'destructive') return Trash2;
  if (props.variant === 'warning') return TriangleAlert;
  return CircleHelp;
});

const iconClass = computed(() => {
  if (props.variant === 'destructive') return 'bg-red-50 text-red-600 ring-red-100';
  if (props.variant === 'warning') return 'bg-amber-50 text-amber-600 ring-amber-100';
  return 'bg-blue-50 text-blue-600 ring-blue-100';
});

function handleConfirm() {
  if (props.loading) return;
  emit('confirm');
}

function handleCancel() {
  emit('cancel');
  emit('update:open', false);
}
</script>

<template>
  <AlertDialog :open="props.open" @update:open="emit('update:open', $event)">
    <AlertDialogContent data-confirm-dialog class="gap-0 overflow-hidden p-0 sm:max-w-[440px]">
      <AlertDialogHeader class="flex-row items-start gap-4 px-6 pb-5 pt-6 text-left">
        <div
          data-confirm-dialog-icon
          class="mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-xl ring-1 ring-inset"
          :class="iconClass"
        >
          <component :is="iconComponent" class="size-5" aria-hidden="true" />
        </div>
        <div class="min-w-0 flex-1">
          <AlertDialogTitle class="text-base font-semibold leading-6 text-foreground">
            {{ props.title }}
          </AlertDialogTitle>
          <AlertDialogDescription class="mt-1.5 text-sm leading-6 text-muted-foreground">
            {{ props.description }}
          </AlertDialogDescription>
        </div>
      </AlertDialogHeader>
      <AlertDialogFooter
        data-confirm-dialog-footer
        class="!mx-0 !mb-0 !rounded-none border-t bg-muted/30 px-6 py-5 sm:space-x-2"
      >
        <AlertDialogCancel :disabled="props.loading" @click="handleCancel">{{ props.cancelText }}</AlertDialogCancel>
        <AlertDialogAction
          :disabled="props.loading"
          :class="{
            'bg-destructive text-destructive-foreground hover:bg-destructive/90': props.variant === 'destructive',
            'bg-amber-600 text-white hover:bg-amber-700': props.variant === 'warning',
          }"
          @click="handleConfirm"
        >
          {{ props.loading ? '处理中...' : props.confirmText }}
        </AlertDialogAction>
      </AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
</template>
