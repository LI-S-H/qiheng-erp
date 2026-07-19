<script setup lang="ts">
import { computed } from 'vue';
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationNext,
  PaginationPrevious,
} from '@/components/ui/pagination';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

interface Props {
  total?: number | null;
  pageNum: number;
  pageSize: number;
  loading?: boolean;
  simple?: boolean;
  hasNext?: boolean;
  currentCount?: number;
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  simple: false,
  total: null,
  hasNext: undefined,
  currentCount: undefined,
});

const emit = defineEmits<{
  'update:pageNum': [value: number];
  'update:pageSize': [value: number];
}>();

const simpleMode = computed(() => props.simple || props.total === null || props.total === undefined || props.total < 0);
const totalPages = computed(() => Math.max(1, Math.ceil((props.total ?? 0) / props.pageSize)));
const currentCountText = computed(() => (typeof props.currentCount === 'number' ? props.currentCount : '-'));
const canNext = computed(() => {
  if (!simpleMode.value) return props.pageNum < totalPages.value;
  if (typeof props.hasNext === 'boolean') return props.hasNext;
  return typeof props.currentCount === 'number' ? props.currentCount >= props.pageSize : true;
});
const simplePaginationTotal = computed(() => {
  // 无总数分页仅暴露当前页和可前往的下一页，仍复用统一的箭头分页组件。
  const visiblePageCount = props.pageNum + (canNext.value ? 1 : 0);
  return visiblePageCount * props.pageSize;
});
const pageSizeValue = computed({
  get: () => String(props.pageSize),
  set: value => emit('update:pageSize', Number(value)),
});

function goToPage(page: number) {
  if (props.loading || page < 1 || page === props.pageNum) return;
  if (!simpleMode.value && page > totalPages.value) return;
  if (simpleMode.value && page > props.pageNum && !canNext.value) return;
  emit('update:pageNum', page);
}
</script>

<template>
  <div
    data-table-pagination
    :aria-busy="props.loading"
    class="grid min-h-[52px] grid-cols-[1fr_auto_1fr] items-center border-t border-border px-4 text-xs text-muted-foreground max-sm:grid-cols-1 max-sm:gap-2 max-sm:py-3"
    :class="{ 'pointer-events-none opacity-60': props.loading }"
  >
    <div class="flex items-center gap-3 justify-self-start max-sm:justify-self-center">
      <span v-if="simpleMode">本页 {{ currentCountText }} 条</span>
      <span v-else>共 {{ props.total }} 条</span>
      <div class="flex items-center gap-1.5">
        <span>每页</span>
        <Select v-model="pageSizeValue" :disabled="props.loading">
          <SelectTrigger size="sm" class="h-8 w-[82px] text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent position="popper">
            <SelectItem value="10">10 条</SelectItem>
            <SelectItem value="20">20 条</SelectItem>
            <SelectItem value="50">50 条</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>

    <Pagination
      v-if="!simpleMode"
      :page="props.pageNum"
      :total="props.total || 0"
      :items-per-page="props.pageSize"
      :sibling-count="1"
      :disabled="props.loading"
      show-edges
      class="w-auto justify-self-center"
      @update:page="goToPage"
    >
      <PaginationContent v-slot="{ items }">
        <PaginationPrevious size="sm" />
        <template v-for="(item, index) in items" :key="index">
          <PaginationItem
            v-if="item.type === 'page'"
            :value="item.value"
            :is-active="item.value === props.pageNum"
            size="sm"
            :data-current-page="item.value === props.pageNum ? '' : undefined"
            class="border-0 bg-transparent shadow-none"
          >
            {{ item.value }}
          </PaginationItem>
          <PaginationEllipsis v-else :index="index" />
        </template>
        <PaginationNext size="sm" />
      </PaginationContent>
    </Pagination>
    <Pagination
      v-else
      :page="props.pageNum"
      :total="simplePaginationTotal"
      :items-per-page="props.pageSize"
      :disabled="props.loading"
      class="w-auto justify-self-center"
      @update:page="goToPage"
    >
      <PaginationContent>
        <PaginationPrevious size="sm" />
        <PaginationItem
          :value="props.pageNum"
          :is-active="true"
          size="sm"
          data-current-page
          class="border-0 bg-transparent shadow-none"
        >
          {{ props.pageNum }}
        </PaginationItem>
        <PaginationNext size="sm" />
      </PaginationContent>
    </Pagination>

    <span v-if="simpleMode" class="justify-self-end max-sm:justify-self-center">不统计总数</span>
    <span v-else class="justify-self-end max-sm:justify-self-center">共 {{ totalPages }} 页</span>
  </div>
</template>
