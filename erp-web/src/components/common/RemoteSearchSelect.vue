<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useDebounceFn } from '@vueuse/core';
import { CircleHelp, ChevronsUpDown, Loader2, Search } from 'lucide-vue-next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { useExclusiveDropdown } from '@/shared/composables/use-exclusive-dropdown';
import OverflowTooltip from '@/components/common/OverflowTooltip.vue';

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
  searchHint?: string;
  emptyText?: string;
  disabled?: boolean;
  invalid?: boolean;
  clearable?: boolean;
  clearValue?: string | number;
  clearLabel?: string;
  fetchOptions: (keyword: string) => Promise<RemoteSearchOption[]>;
  debounceMs?: number;
  maxResults?: number;
  compact?: boolean;
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
  compact: false,
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
const resolvedSearchHint = computed(() => {
  if (props.searchHint?.trim()) return props.searchHint.trim();

  const target = props.searchPlaceholder.trim()
    .replace(/^输入/, '')
    .replace(/搜索$/, '');
  if (!target || target === '关键字') return '';
  return target.includes('编码或名称') ? `支持按${target}单独搜索` : `支持按${target}搜索`;
});
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
        class="w-full justify-between font-normal leading-none"
        :class="[
          compact ? 'h-8 px-2 text-xs' : 'h-9 px-[11px]',
          !normalizedValue ? 'text-muted-foreground' : '',
          invalid ? 'border-destructive focus-visible:ring-destructive' : '',
        ]"
      >
        <OverflowTooltip :text="triggerLabel" class="truncate" />
        <ChevronsUpDown class="ml-2 shrink-0 opacity-50" :class="compact ? 'size-3.5' : 'size-4'" />
      </Button>
    </PopoverTrigger>
    <PopoverContent
      align="start"
      data-remote-search-select-content
      class="w-[--reka-popover-trigger-width] p-0"
      :class="compact ? 'text-xs' : ''"
    >
      <div class="border-b" :class="compact ? 'p-1.5' : 'p-2'">
        <div class="relative">
          <Search class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" :class="compact ? 'size-3.5' : 'size-4'" />
          <Input v-model="keyword" :class="compact ? 'h-7 pl-7 pr-7 text-xs' : 'h-8 pl-8 pr-8'" :placeholder="searchPlaceholder" />
          <Tooltip v-if="resolvedSearchHint" :delay-duration="0" :skip-delay-duration="0">
            <TooltipTrigger as-child>
              <button
                type="button"
                data-remote-search-hint
                class="absolute right-1.5 top-1/2 inline-flex -translate-y-1/2 items-center justify-center rounded-sm p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                :class="compact ? 'size-5' : 'size-6'"
                aria-label="查看搜索说明"
              >
                <CircleHelp :class="compact ? 'size-3.5' : 'size-4'" />
              </button>
            </TooltipTrigger>
            <TooltipContent class="max-w-[260px] !animate-none">{{ resolvedSearchHint }}</TooltipContent>
          </Tooltip>
        </div>
      </div>
      <div class="max-h-64 overflow-auto p-1">
        <button
          v-if="clearable"
          data-select-option
          type="button"
          class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left outline-none hover:bg-accent hover:text-accent-foreground"
          :class="compact ? 'min-h-8 text-xs' : 'min-h-9 text-sm'"
          @click="selectClear"
        >
          <span class="truncate">{{ clearLabel }}</span>
        </button>
        <div v-if="loading" class="flex items-center justify-center gap-2 text-muted-foreground" :class="compact ? 'h-16 text-xs' : 'h-20 text-sm'">
          <Loader2 class="animate-spin" :class="compact ? 'size-3.5' : 'size-4'" />
          <span>加载中</span>
        </div>
        <div v-else-if="options.length === 0" class="flex items-center justify-center px-3 text-center text-muted-foreground" :class="compact ? 'h-16 text-xs' : 'h-20 text-sm'">
          {{ emptyText }}
        </div>
        <template v-else>
          <button
          v-for="option in options"
          :key="String(option.value)"
          type="button"
          data-select-option
            class="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left outline-none hover:bg-accent hover:text-accent-foreground disabled:pointer-events-none disabled:opacity-50"
            :class="compact ? 'min-h-8 text-xs' : 'min-h-9 text-sm'"
            :disabled="option.disabled"
            @click="selectOption(option)"
          >
            <span class="truncate">{{ option.label }}</span>
          </button>
        </template>
      </div>
    </PopoverContent>
  </Popover>
</template>
