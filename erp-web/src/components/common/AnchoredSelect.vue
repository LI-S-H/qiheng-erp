<script setup lang="ts">
import { computed, ref } from 'vue';
import { ChevronsUpDown } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Command, CommandGroup, CommandItem, CommandList } from '@/components/ui/command';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';

interface Option {
  value: string | number;
  label: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<{
  modelValue?: string | number;
  options: Option[];
  placeholder?: string;
}>(), {
  placeholder: '请选择',
});

const emit = defineEmits<{
  'update:modelValue': [value: string | number];
}>();

const open = ref(false);
const normalizedValue = computed(() => String(props.modelValue ?? ''));
const selectedLabel = computed(() => props.options.find(option => String(option.value) === normalizedValue.value)?.label || props.placeholder);

function selectOption(value: string | number) {
  emit('update:modelValue', value);
  open.value = false;
}
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <Button
        data-anchored-select-trigger
        variant="outline"
        role="combobox"
        :aria-expanded="open"
        class="h-9 w-full justify-between bg-transparent px-3 text-sm font-normal shadow-xs"
      >
        <span class="truncate">{{ selectedLabel }}</span>
        <ChevronsUpDown class="ml-2 size-4 shrink-0 text-muted-foreground" />
      </Button>
    </PopoverTrigger>
    <PopoverContent
      data-anchored-select-content
      side="bottom"
      align="start"
      :side-offset="4"
      :avoid-collisions="false"
      style="width: var(--reka-popover-trigger-width)"
      class="p-1"
    >
      <Command :model-value="normalizedValue">
        <CommandList>
          <CommandGroup>
            <CommandItem
              v-for="option in props.options"
              :key="String(option.value)"
              :value="String(option.value)"
              :disabled="option.disabled"
              class="cursor-pointer"
              @select="selectOption(option.value)"
            >
              {{ option.label }}
            </CommandItem>
          </CommandGroup>
        </CommandList>
      </Command>
    </PopoverContent>
  </Popover>
</template>
