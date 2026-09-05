import { readonly, ref } from 'vue';

/**
 * 页面切换的最短反馈时长。略长于普通控件动效，避免本地接口过快导致遮罩一闪而过。
 */
const MIN_VISIBLE_DURATION = 240;

const loading = ref(false);
let pendingCount = 0;
let shownAt = 0;
let hideTimer: number | undefined;

export const isPageLoading = readonly(loading);

/**
 * 开始一次会影响当前页面内容的异步操作。
 *
 * 多个路由或接口请求共用计数，确保数据返回前只有一层加载反馈。
 */
export function beginPageLoading() {
  pendingCount += 1;
  window.clearTimeout(hideTimer);

  if (pendingCount === 1 && !loading.value) {
    shownAt = Date.now();
    loading.value = true;
  }

  let completed = false;
  return () => {
    if (completed) return;
    completed = true;
    pendingCount = Math.max(0, pendingCount - 1);
    if (pendingCount > 0) return;

    if (!loading.value) return;

    const remaining = Math.max(0, MIN_VISIBLE_DURATION - (Date.now() - shownAt));
    hideTimer = window.setTimeout(() => {
      if (pendingCount === 0) loading.value = false;
    }, remaining);
  };
}
