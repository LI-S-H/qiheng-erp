<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { Check, ChevronsUpDown, Loader2, Search } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useExclusiveDropdown } from '@/shared/composables/use-exclusive-dropdown';

export interface RemoteSearchOption {
  value: string | number;
  label: string;
  disabled?: boolean;
}

const props = withDefaults(defineProps<{
  modelValue?: string | number;
  selectedLabel?: string;
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  disabled?: boolean;
  invalid?: boolean;
  clearable?: boolean;
  clearValue?: string | number;
  clearLabel?: string;
  fetchOptions: (keyword: string) => Promise<RemoteSearchOption[]>;
  debounceMs?: number;
  maxResults?: number;
}>(), {
  selectedLabel: '',
  placeholder: '请选择',
  searchPlaceholder: '输入关键字搜索',
  emptyText: '暂无匹配选项',
  clearable: false,
  clearValue: 'all',
  clearLabel: '全部',
  debounceMs: 250,
  maxResults: 10,
});

const emit = defineEmits<{
  (e: 'update:modelValue', value: string | number): void;
  (e: 'select', option: RemoteSearchOption): void;
}>();

const open = ref(false);
const keyword = ref('');
const loading = ref(false);
const options = ref<RemoteSearchOption[]>([]);
const selectedOption = ref<RemoteSearchOption | null>(null);
let requestSequence = 0;

const { setOpen } = useExclusiveDropdown(open);

const normalizedValue = computed(() => props.modelValue === undefined || props.modelValue === null ? '' : String(props.modelValue));
const triggerLabel = computed(() => {
  if (props.selectedLabel) return props.selectedLabel;
  if (selectedOption.value) return selectedOption.value.label;
  return options.value.find(item => String(item.value) === normalizedValue.value)?.label || props.placeholder;
});

async function loadOptions() {
  const sequence = ++requestSequence;
  loading.value = true;
  try {
    const result = await props.fetchOptions(keyword.value.trim());
    if (sequence !== requestSequence) return;
    options.value = result.slice(0, props.maxResults);
    const matched = options.value.find(item => String(item.value) === normalizedValue.value);
    if (matched) selectedOption.value = matched;
  } finally {
    if (sequence === requestSequence) loading.value = false;
  }
}

const debouncedLoad = useDebounceFn(loadOptions, props.debounceMs);

function handleOpenUpdate(value: boolean) {
  setOpen(value);
  if (value) loadOptions();
}

function selectOption(option: RemoteSearchOption) {
  if (option.disabled) return;
  selectedOption.value = option;
  emit('update:modelValue', option.value);
  emit('select', option);
  setOpen(false);
}

function selectClear() {
  const option = { value: props.clearValue, label: props.clearLabel };
  selectedOption.value = null;
  emit('update:modelValue', option.value);
  emit('select', option);
  setOpen(false);
}

watch(keyword, () => {
  if (open.value) debouncedLoad();
});
</script>

<template>
  <Popover :open="open" @update:open="handleOpenUpdate">
    <PopoverTrigger as-child>
      <Button
        type="button"
        variant="outline"
        role="combobox"
        :aria-expanded="open"
        :aria-invalid="invalid"
        :disabled="disabled"
        class="h-9 w-full justify-between px-3 font-normal"
        :class="[
          !normalizedValue ? 'text-muted-foreground' : '',
          invalid ? 'border-destructive focus-visible:ring-destructive' : '',
        ]"
      >
        <span class="truncate">{{ triggerLabel }}</span>
        <ChevronsUpDown class="ml-2 size-4 shrink-0 opacity-50" />
      </Button>
    </PopoverTrigger>
    <PopoverContent align="start" data-remote-search-select-content class="w-[--reka-popover-trigger-width] p-0">
      <div class="border-b p-2">
        <div class="relative">
          <Search class="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input v-model="keyword" class="h-8 pl-8" :placeholder="searchPlaceholder" />
        </div>
      </div>
      <div class="max-h-64 overflow-auto p-1">
        <button
          v-if="clearable"
          type="button"
          class="flex min-h-9 w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm outline-none hover:bg-accent hover:text-accent-foreground"
          @click="selectClear"
        >
          <Check class="size-4 shrink-0" :class="String(clearValue) === normalizedValue ? 'opacity-100' : 'opacity-0'" />
          <span class="truncate">{{ clearLabel }}</span>
        </button>
        <div v-if="loading" class="flex h-20 items-center justify-center gap-2 text-sm text-muted-foreground">
          <Loader2 class="size-4 animate-spin" />
          <span>加载中</span>
        </div>
        <div v-else-if="options.length === 0" class="flex h-20 items-center justify-center px-3 text-center text-sm text-muted-foreground">
          {{ emptyText }}
        </div>
        <template v-else>
          <button
            v-for="option in options"
            :key="String(option.value)"
            type="button"
            class="flex min-h-9 w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm outline-none hover:bg-accent hover:text-accent-foreground disabled:pointer-events-none disabled:opacity-50"
            :disabled="option.disabled"
            @click="selectOption(option)"
          >
            <Check class="size-4 shrink-0" :class="String(option.value) === normalizedValue ? 'opacity-100' : 'opacity-0'" />
            <span class="truncate">{{ option.label }}</span>
          </button>
        </template>
      </div>
    </PopoverContent>
  </Popover>
</template>
