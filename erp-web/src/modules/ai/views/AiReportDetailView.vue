<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { ArrowLeft, FileText, LoaderCircle, X } from 'lucide-vue-next';
import { getApiErrorMessage } from '@/api/http';
import { getAiReport } from '../api';
import AiMarkdownContent from '../components/AiMarkdownContent.vue';
import AiChartCard from '../components/AiChartCard.vue';
import { splitReportMessage, type AiReportDetail } from '../contracts/report-contract';
import { useAuthStore } from '@/modules/auth/stores/authStore';

const route = useRoute();
const auth = useAuthStore();
const report = ref<AiReportDetail | null>(null);
const loading = ref(true);
const error = ref('');
const parts = computed(() => report.value
  ? splitReportMessage(report.value.content, [], false, false)
  : []);

watch(() => [route.params.conversationId, route.params.reportId, auth.token] as const,
  async ([conversationId, reportId], _, onCleanup) => {
    if (typeof conversationId !== 'string' || typeof reportId !== 'string') {
      return;
    }
    const controller = new AbortController();
    onCleanup(() => controller.abort());
    report.value = null;
    loading.value = true;
    error.value = '';
    try {
      const result = await getAiReport(conversationId, reportId, controller.signal);
      if (!controller.signal.aborted) {
        report.value = result;
      }
    } catch (cause) {
      if (!controller.signal.aborted) {
        error.value = getApiErrorMessage(cause) || '暂时无法查看这份报告。';
      }
    } finally {
      if (!controller.signal.aborted) {
        loading.value = false;
      }
    }
  }, { immediate: true });
</script>

<template>
  <section class="report-view" data-testid="ai-report-detail">
    <div class="report-toolbar">
      <RouterLink :to="{ name: 'ai-assistant', query: { conversationId: String(route.params.conversationId) } }" class="report-back">
        <ArrowLeft :size="16" aria-hidden="true" />返回会话
      </RouterLink>
      <RouterLink
        :to="{ name: 'ai-assistant', query: { conversationId: String(route.params.conversationId) } }"
        class="report-close"
        aria-label="关闭报告，返回会话"
        title="关闭报告"
      >
        <X :size="18" aria-hidden="true" />
      </RouterLink>
    </div>
    <article class="report-document" :aria-busy="loading">
      <p v-if="loading" class="report-status" role="status"><LoaderCircle :size="18" class="animate-spin" aria-hidden="true" />正在读取报告</p>
      <p v-else-if="error" class="report-status" role="alert">{{ error }}</p>
      <template v-else-if="report">
        <header class="report-heading"><FileText :size="24" aria-hidden="true" /><h1>{{ report.title }}</h1></header>
        <template v-for="part in parts" :key="part.key">
          <AiChartCard v-if="'chart' in part" :chart="part.chart" />
          <AiMarkdownContent v-else-if="'text' in part" :content="part.text" report-html />
        </template>
      </template>
    </article>
  </section>
</template>

<style scoped>
.report-view { min-height: 100%; padding: 16px clamp(16px, 5vw, 64px) 40px; background: var(--background); }
.report-toolbar, .report-document { width: min(100%, 1040px); margin: 0 auto; }
/* 滚动报告时保持退出入口可见，避免必须滑回顶部才能返回会话 */
.report-toolbar {
  position: sticky;
  top: 0;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0 16px;
  background: color-mix(in srgb, var(--background) 92%, transparent);
  backdrop-filter: blur(6px);
}
.report-back { display: inline-flex; gap: 7px; align-items: center; padding: 6px 0; color: var(--primary); font-size: 14px; }
.report-back:hover { text-decoration: underline; }
.report-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 999px;
  color: var(--muted-foreground);
}
.report-close:hover { background: color-mix(in srgb, var(--foreground) 8%, transparent); color: var(--foreground); }
.report-document { min-height: 190px; padding: clamp(18px, 4vw, 48px); border: 1px solid var(--border); border-radius: 14px; background: var(--card); }
.report-heading { display: flex; align-items: start; gap: 12px; border-bottom: 1px solid var(--border); padding-bottom: 20px; margin-bottom: 24px; }
.report-heading svg { flex: none; color: var(--primary); margin-top: 5px; }
.report-heading h1 { font-size: clamp(22px, 3vw, 32px); font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; margin: 0; }
.report-status { display: flex; align-items: center; gap: 8px; font-size: 14px; color: var(--muted-foreground); }
</style>
