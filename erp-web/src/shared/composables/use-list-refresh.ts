import { useDebounceFn } from '@vueuse/core';
import type { ComputedRef, Ref } from 'vue';

export function useListRefresh(
  busy: ComputedRef<boolean>,
  pending: Ref<boolean>,
  refresh: () => Promise<unknown>,
  delay = 180,
) {
  const debouncedRefresh = useDebounceFn(async () => {
    try {
      await refresh();
    } finally {
      pending.value = false;
    }
  }, delay);

  return () => {
    if (busy.value) return;
    pending.value = true;
    debouncedRefresh();
  };
}
