import { defineStore } from 'pinia';
import { ref } from 'vue';
import { getDashboardOverview } from '@/modules/dashboard/api';
import type { DashboardOverview } from '@/modules/dashboard/types';
import { getApiErrorMessage } from '@/api/http';

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
  const refreshError = ref<string | null>(null);
  let generation = 0;
  let pendingRequest: Promise<boolean> | null = null;

  /** 清除当前会话数据并作废旧请求，避免退出或换账号后显示上一账号的待办。 */
  function reset() {
    generation++;
    overview.value = null;
    loadedAt.value = 0;
    loading.value = false;
    refreshError.value = null;
    pendingRequest = null;
  }

  /**
   * 加载或刷新 overview。
   *
   * @param force true 强制刷新,绕过 60 秒节流;用于工作台 mount、用户主动点刷新、错误态重试
   * @returns 加载成功返回 true，节流或旧会话请求返回 false；并发调用等待同一请求
   */
  async function refresh(force = false): Promise<boolean> {
    if (pendingRequest) return pendingRequest;
    if (!force && overview.value && Date.now() - loadedAt.value < 60_000) return false;
    loading.value = true;
    refreshError.value = null;
    const requestGeneration = generation;
    pendingRequest = (async () => {
      try {
        const data = await getDashboardOverview();
        if (requestGeneration !== generation) return false;
        overview.value = data;
        loadedAt.value = Date.now();
        return true;
      } catch (error) {
        if (requestGeneration !== generation) return false;
        refreshError.value = getApiErrorMessage(error) || '工作台数据刷新失败';
        throw error;
      } finally {
        // reset 后新会话可能已发起请求，旧 finally 不能清理新会话的加载状态。
        if (requestGeneration === generation) {
          loading.value = false;
          pendingRequest = null;
        }
      }
    })();
    return pendingRequest;
  }

  return { overview, loading, refreshError, refresh, reset };
});
