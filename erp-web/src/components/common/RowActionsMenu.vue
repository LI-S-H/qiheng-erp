<script setup lang="ts">
import { MoreHorizontal } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

export interface RowActionOption {
  key: string;
  label: string;
  variant?: 'default' | 'destructive';
  disabled?: boolean;
  disabledReason?: string;
  separated?: boolean;
}

const props = withDefaults(defineProps<{
  actions: readonly RowActionOption[];
  label?: string;
  disabled?: boolean;
}>(), {
  label: '更多操作',
  disabled: false,
});

const emit = defineEmits<{
  select: [key: string];
}>();

function handleSelect(action: RowActionOption) {
  if (props.disabled || action.disabled) return;
  emit('select', action.key);
}
</script>

<template>
  <DropdownMenu v-if="actions.length > 0">
    <DropdownMenuTrigger as-child>
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        data-row-actions-trigger
        :disabled="disabled"
        :aria-label="label"
        :title="label"
      >
        <MoreHorizontal aria-hidden="true" />
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="min-w-36">
      <template v-for="action in actions" :key="action.key">
        <DropdownMenuSeparator v-if="action.separated" />
        <DropdownMenuItem
          :variant="action.variant || 'default'"
          :disabled="action.disabled"
          :title="action.disabled ? action.disabledReason : undefined"
          @select="handleSelect(action)"
        >
          {{ action.label }}
        </DropdownMenuItem>
      </template>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
