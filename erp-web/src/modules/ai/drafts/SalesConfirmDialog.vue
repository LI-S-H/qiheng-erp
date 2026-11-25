<script setup lang="ts">
import { CircleHelp, Trash2, TriangleAlert } from 'lucide-vue-next';
import { AlertDialogRoot, AlertDialogPortal, AlertDialogOverlay, AlertDialogContent, AlertDialogTitle, AlertDialogDescription, AlertDialogCancel } from 'reka-ui';
import { Button } from '@/components/ui/button';

const props = defineProps<{
  open: boolean;
  title: string;
  description: string;
  confirmText: string;
  variant: 'default' | 'warning' | 'destructive';
  loading: boolean;
}>();
const emit = defineEmits<{ 'update:open': [value: boolean]; confirm: [] }>();

function changeOpen(value: boolean) {
  if (!props.loading) emit('update:open', value);
}

function blockWhileLoading(event: Event) {
  if (props.loading) event.preventDefault();
}
</script>

<template>
  <AlertDialogRoot :open="open" @update:open="changeOpen">
    <AlertDialogPortal>
      <!-- 二次确认遮罩同时压暗底层预览，关闭时与两层弹窗保持同一时长。 -->
      <AlertDialogOverlay data-sales-confirm-overlay class="fixed inset-0 z-[70] data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 duration-150" style="background: rgb(34 43 58 / 22%); backdrop-filter: none" />
      <AlertDialogContent
        data-slot="alert-dialog-content"
        data-confirm-dialog
        class="fixed inset-y-0 right-0 left-[var(--app-shell-sidebar-width)] z-[80] m-auto grid h-fit max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] gap-0 overflow-hidden rounded-xl bg-popover p-0 text-popover-foreground shadow-2xl ring-1 ring-foreground/10 outline-none sm:max-w-[440px] data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 duration-150"
        @escape-key-down="blockWhileLoading"
      >
        <div class="flex items-start gap-4 px-6 pb-5 pt-6 text-left">
          <div class="mt-0.5 grid size-10 shrink-0 place-items-center rounded-xl ring-1 ring-inset"
            :class="variant === 'destructive' ? 'bg-red-50 text-red-600 ring-red-100' : variant === 'warning' ? 'bg-amber-50 text-amber-600 ring-amber-100' : 'bg-blue-50 text-blue-600 ring-blue-100'">
            <Trash2 v-if="variant === 'destructive'" class="size-5" aria-hidden="true" />
            <TriangleAlert v-else-if="variant === 'warning'" class="size-5" aria-hidden="true" />
            <CircleHelp v-else class="size-5" aria-hidden="true" />
          </div>
          <div class="min-w-0 flex-1">
            <AlertDialogTitle class="text-base font-semibold leading-6 text-foreground">{{ title }}</AlertDialogTitle>
            <AlertDialogDescription class="mt-1.5 text-sm leading-6 text-muted-foreground">{{ description }}</AlertDialogDescription>
          </div>
        </div>
        <div class="flex justify-end gap-2 border-t bg-muted/30 px-6 py-5">
          <AlertDialogCancel as-child><Button variant="outline" :disabled="loading">取消</Button></AlertDialogCancel>
          <Button :variant="variant === 'destructive' ? 'destructive' : 'default'" :disabled="loading" @click="emit('confirm')">
            {{ loading ? '处理中...' : confirmText }}
          </Button>
        </div>
      </AlertDialogContent>
    </AlertDialogPortal>
  </AlertDialogRoot>
</template>
