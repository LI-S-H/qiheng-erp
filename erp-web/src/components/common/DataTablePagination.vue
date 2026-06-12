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
  total: number;
  pageNum: number;
  pageSize: number;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  'update:pageNum': [value: number];
  'update:pageSize': [value: number];
}>();

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));
const pageSizeValue = computed({
  get: () => String(props.pageSize),
  set: value => emit('update:pageSize', Number(value)),
});

function goToPage(page: number) {
  if (page >= 1 && page <= totalPages.value && page !== props.pageNum) {
    emit('update:pageNum', page);
  }
}
</script>

<template>
  <div data-table-pagination class="grid min-h-[52px] grid-cols-[1fr_auto_1fr] items-center border-t border-border px-4 text-xs text-muted-foreground max-sm:grid-cols-1 max-sm:gap-2 max-sm:py-3">
    <div class="flex items-center gap-3 justify-self-start max-sm:justify-self-center">
      <span>共 {{ props.total }} 条</span>
      <div class="flex items-center gap-1.5">
        <span>每页</span>
        <Select v-model="pageSizeValue">
          <SelectTrigger size="sm" class="h-8 w-[72px] text-xs">
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
      :page="props.pageNum"
      :total="props.total"
      :items-per-page="props.pageSize"
      :sibling-count="1"
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

    <span class="justify-self-end max-sm:justify-self-center">共 {{ totalPages }} 页</span>
  </div>
</template>
