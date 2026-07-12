import { useDebounceFn } from '@vueuse/core';
import type { ComputedRef, Ref } from 'vue';
import { useListRefresh } from './use-list-refresh';

interface PagedQueryState {
  pageNum: number;
  pageSize: number;
}

interface UsePagedQueryOptions<TQuery extends PagedQueryState> {
  query: TQuery;
  busy: ComputedRef<boolean>;
  pending: Ref<boolean>;
  load: () => Promise<unknown>;
  resetFilters: () => void;
  searchDelay?: number;
  pageDelay?: number;
}

/**
 * 统一列表页的查询、重置、分页与刷新入口。
 *
 * 数据加载、请求序号和业务筛选字段仍由页面持有，避免公共层猜测领域查询参数。
 */
export function usePagedQuery<TQuery extends PagedQueryState>({
  query,
  busy,
  pending,
  load,
  resetFilters,
  searchDelay = 250,
  pageDelay = 180,
}: UsePagedQueryOptions<TQuery>) {
  const debouncedSearch = useDebounceFn(() => {
    query.pageNum = 1;
    void load();
  }, searchDelay);

  const debouncedPageChange = useDebounceFn((pageNum: number, pageSize: number) => {
    query.pageNum = pageNum;
    query.pageSize = pageSize;
    void load();
  }, pageDelay);

  function handleSearch() {
    if (busy.value) return;
    pending.value = true;
    debouncedSearch();
  }

  function handleReset() {
    if (busy.value) return;
    resetFilters();
    query.pageNum = 1;
    pending.value = true;
    debouncedSearch();
  }

  function handlePageChange(pageNum: number) {
    if (busy.value) return;
    pending.value = true;
    debouncedPageChange(pageNum, query.pageSize);
  }

  function handlePageSizeChange(pageSize: number) {
    if (busy.value) return;
    pending.value = true;
    debouncedPageChange(1, pageSize);
  }

  return {
    handleSearch,
    handleReset,
    handlePageChange,
    handlePageSizeChange,
    refreshList: useListRefresh(busy, pending, load, pageDelay),
  };
}
