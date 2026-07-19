<script setup lang="ts">
import { ref, computed } from 'vue';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { ScrollArea } from '@/components/ui/scroll-area';
import { ChevronDown, X } from 'lucide-vue-next';

interface Option {
  value: string;
  label: string;
}

interface Props {
  modelValue: string[];
  options: Option[];
  placeholder?: string;
}

const props = withDefaults(defineProps<Props>(), {
  placeholder: '请选择',
});

const emit = defineEmits<{
  'update:modelValue': [value: string[]];
}>();

const isOpen = ref(false);

const selectedLabels = computed(() => {
  return props.modelValue
    .map(v => props.options.find(o => o.value === v)?.label)
    .filter(Boolean) as string[];
});

function toggleOption(value: string) {
  const current = [...props.modelValue];
  const idx = current.indexOf(value);
  if (idx >= 0) {
    current.splice(idx, 1);
  } else {
    current.push(value);
  }
  emit('update:modelValue', current);
}

function removeOption(value: string, event: Event) {
  event.stopPropagation();
  const current = props.modelValue.filter(v => v !== value);
  emit('update:modelValue', current);
}

function isChecked(value: string) {
  return props.modelValue.includes(value);
}
</script>

<template>
  <Popover v-model:open="isOpen">
    <PopoverTrigger as-child>
      <Button
        variant="outline"
        role="combobox"
        :aria-expanded="isOpen"
        class="w-full justify-between px-[11px] font-normal leading-none min-h-[36px] h-auto py-1"
      >
        <div class="flex flex-wrap gap-1 flex-1 items-center">
          <template v-if="selectedLabels.length === 0">
            <span class="text-muted-foreground">{{ props.placeholder }}</span>
          </template>
          <template v-else>
            <Badge
              v-for="(label, i) in selectedLabels"
              :key="props.modelValue[i]"
              variant="secondary"
              class="text-xs h-5 gap-1 pr-1"
            >
              {{ label }}
              <button
                type="button"
                class="rounded-full hover:bg-accent p-0.5"
                @click="removeOption(props.modelValue[i], $event)"
              >
                <X class="h-3 w-3" />
              </button>
            </Badge>
          </template>
        </div>
        <ChevronDown class="ml-2 h-4 w-4 shrink-0 opacity-50" />
      </Button>
    </PopoverTrigger>
    <PopoverContent class="w-[--reka-popover-trigger-width] p-0" align="start">
      <ScrollArea class="h-[200px] p-2">
        <div
          v-for="option in props.options"
          :key="option.value"
          data-select-option
          class="flex items-center gap-2 rounded-sm px-2 py-1.5 text-sm hover:bg-accent cursor-pointer"
          @click="toggleOption(option.value)"
        >
          <Checkbox :model-value="isChecked(option.value)" class="pointer-events-none" />
          <span>{{ option.label }}</span>
        </div>
      </ScrollArea>
    </PopoverContent>
  </Popover>
</template>
