import { defineStore } from 'pinia';
import { ref } from 'vue';
import { getDashboardOverview } from '@/modules/dashboard/api';
import type { DashboardOverview } from '@/modules/dashboard/types';

/**
 * 工作台 overview 缓存。
 *
 * <p>顶栏铃铛和工作台主页共用同一份聚合数据；store 内部按 60 秒节流，避免
 * 标签页切回或反复打开铃铛时频繁请求后端。进入工作台、用户主动刷新、错误态重试
 * 这三种场景必须传 {@code force=true} 绕过节流。</p>
 */
export const useDashboardOverviewStore = defineStore('dashboardOverview', () => {
  const overview = ref<DashboardOverview | null>(null);
  const loading = ref(false);
  const loadedAt = ref(0);

  /**
   * 加载或刷新 overview。
   *
   * @param force true 强制刷新,绕过 60 秒节流;用于工作台 mount、用户主动点刷新、错误态重试
   * @returns 加载成功返回 true,被节流或正在加载返回 false
   */
  async function refresh(force = false): Promise<boolean> {
    if (loading.value) return false;
    if (!force && overview.value && Date.now() - loadedAt.value < 60_000) return false;
    loading.value = true;
    try {
      overview.value = await getDashboardOverview();
      loadedAt.value = Date.now();
      return true;
    } finally {
      loading.value = false;
    }
  }

  return { overview, loading, refresh };
});
