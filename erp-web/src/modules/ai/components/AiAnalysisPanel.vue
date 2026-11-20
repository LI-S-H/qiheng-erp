<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Bot, ChevronDown, LoaderCircle, PanelRightClose } from 'lucide-vue-next';
import AiAnalysisBlockView from './AiAnalysisBlockView.vue';
import type { AiAnalysisBlock } from '../types';

const props = withDefaults(defineProps<{
  blocks: AiAnalysisBlock[];
  /** panel：宽屏右侧独立面板；inline：窄屏对话流内可折叠卡片。 */
  variant?: 'panel' | 'inline';
}>(), { variant: 'panel' });
const emit = defineEmits<{ close: [] }>();

const open = ref(false);
const scroller = ref<HTMLElement | null>(null);
/** 是否有分析仍在进行；用于面板运行中提示。 */
const running = computed(() => props.blocks.some(block => block.status === 'running'));

/** 最近一次分析的任务目标；折叠态用它提示当前在分析什么。 */
const latestGoal = computed(() => props.blocks[props.blocks.length - 1]?.goal || '');

/** 面板跟随最新过程：用户停留在底部时才自动滚动，避免打断向上查看。 */
watch(
  () => props.blocks.map(block => `${block.id}:${block.steps.length}:${block.summary.length}`).join('|'),
  async () => {
    // 折叠卡片不自动滚动，滚动容器只存在于右侧面板。
    if (props.variant !== 'panel' || !scroller.value) {
      return;
    }
    const element = scroller.value;
    const following = element.scrollHeight - element.scrollTop - element.clientHeight < 80;
    if (!following) {
      return;
    }
    await nextTick();
    element.scrollTop = element.scrollHeight;
  },
);
</script>

<template>
  <aside v-if="variant === 'panel'" class="analysis-panel" aria-label="分析专家分析过程" data-testid="ai-analysis-panel">
    <header class="analysis-panel-header">
      <span class="analysis-panel-icon" aria-hidden="true"><Bot class="size-4" /></span>
      <h2>分析专家</h2>
      <span v-if="running" class="analysis-running" role="status"><LoaderCircle class="size-3.5 animate-spin" aria-hidden="true" />运行中</span>
      <button type="button" class="analysis-close" aria-label="收起分析面板" title="收起" @click="emit('close')">
        <PanelRightClose class="size-4" aria-hidden="true" />
      </button>
    </header>
    <div ref="scroller" class="analysis-panel-scroll">
      <AiAnalysisBlockView v-for="block in blocks" :key="block.id" :block="block" />
    </div>
  </aside>
  <section v-else class="analysis-card" :class="{ 'is-open': open }" data-testid="ai-analysis-card">
    <button
      type="button"
      class="analysis-card-toggle"
      :aria-expanded="open"
      aria-controls="ai-analysis-card-body"
      @click="open = !open"
    >
      <ChevronDown class="analysis-card-chevron" aria-hidden="true" />
      <span class="analysis-panel-icon" aria-hidden="true"><Bot class="size-4" /></span>
      <span class="analysis-card-title">分析专家分析过程</span>
      <span class="analysis-card-meta">{{ blocks.length }} 项 · {{ running ? '分析中' : '已完成' }}</span>
      <LoaderCircle v-if="running" class="size-3.5 animate-spin" aria-hidden="true" />
      <span v-else-if="!open" class="analysis-card-goal">{{ latestGoal }}</span>
    </button>
    <div id="ai-analysis-card-body" class="analysis-card-reveal" :inert="!open">
      <div class="analysis-card-inner">
        <AiAnalysisBlockView v-for="block in blocks" :key="block.id" :block="block" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.analysis-panel {
  display: flex;
  width: var(--analysis-width, 360px);
  flex-shrink: 0;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid var(--border);
  background: var(--card);
}
.analysis-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  flex-shrink: 0;
  padding: 0 10px 0 16px;
  border-bottom: 1px solid var(--border);
}
.analysis-panel-icon {
  display: inline-flex;
  flex: none;
  color: var(--primary);
}
.analysis-panel-header h2 {
  font-size: 13px;
  font-weight: 500;
}
.analysis-running {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  color: var(--primary);
  font-size: 12px;
}
.analysis-close {
  display: grid;
  width: 28px;
  height: 28px;
  margin-left: auto;
  flex: none;
  place-items: center;
  border-radius: 6px;
  color: var(--muted-foreground);
  cursor: pointer;
}
.analysis-running + .analysis-close {
  margin-left: 8px;
}
.analysis-close:hover {
  background: var(--muted);
  color: var(--foreground);
}
.analysis-close:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: 1px;
}
.analysis-panel-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  scrollbar-width: thin;
}
.analysis-card {
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--card);
  overflow: hidden;
}
.analysis-card-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  color: var(--muted-foreground);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.analysis-card-toggle:hover {
  color: var(--foreground);
}
.analysis-card-toggle:focus-visible {
  outline: 2px solid var(--ring);
  outline-offset: -2px;
}
.analysis-card-chevron {
  width: 14px;
  height: 14px;
  flex: none;
  transform: rotate(-90deg);
  transition: transform 220ms ease;
}
.analysis-card.is-open .analysis-card-chevron {
  transform: rotate(0);
}
.analysis-card-title {
  flex: none;
  color: var(--foreground);
}
.analysis-card-meta {
  flex: none;
  font-size: 12px;
}
.analysis-card-goal {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  opacity: 0.8;
}
.analysis-card-reveal {
  display: grid;
  grid-template-rows: 0fr;
  opacity: 0;
  transition: grid-template-rows 220ms ease, opacity 180ms ease;
}
.analysis-card.is-open .analysis-card-reveal {
  grid-template-rows: 1fr;
  opacity: 1;
}
.analysis-card-inner {
  min-height: 0;
  overflow: hidden;
  padding: 0 14px;
}
.analysis-card.is-open .analysis-card-inner {
  padding-bottom: 16px;
}
@media (prefers-reduced-motion: reduce) {
  .analysis-card-chevron,
  .analysis-card-reveal {
    transition: none;
  }
}
</style>