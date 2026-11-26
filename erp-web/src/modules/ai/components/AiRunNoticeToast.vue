<script setup lang="ts">
import { CircleCheck, Clock3, X } from 'lucide-vue-next';

const props = withDefaults(defineProps<{
  /** pending 表示等待用户回答或确认，completed 表示后台输出完成。 */
  kind: 'pending' | 'completed';
  title: string;
  hint: string;
  /** 点击卡片正文时的动作，由页面负责跳转会话。 */
  onOpen: () => void;
  /** sonner 注入的关闭动作，自定义卡片需要显式调用。 */
  onCloseToast?: () => void;
  /** sonner 注入的悬停暂停标记；声明后避免作为普通属性落到根节点。 */
  isPaused?: boolean;
}>(), { onCloseToast: undefined, isPaused: false });

function handleClose() {
  props.onCloseToast?.();
}
</script>

<template>
  <div class="ai-run-notice" :class="`is-${kind}`" data-ai-run-notice :data-kind="kind">
    <button type="button" class="ai-run-notice__main" @click="props.onOpen()">
      <span class="ai-run-notice__icon" aria-hidden="true">
        <Clock3 v-if="kind === 'pending'" class="size-4" />
        <CircleCheck v-else class="size-4" />
      </span>
      <span class="ai-run-notice__text">
        <strong>{{ title }}</strong>
        <span>{{ hint }}</span>
      </span>
    </button>
    <button type="button" class="ai-run-notice__close" aria-label="关闭提醒" @click.stop="handleClose">
      <X class="size-3.5" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
/* 颜色跟随 AI 助手页的浅蓝灰体系：琥珀表示等待处理，绿色表示已完成。 */
.ai-run-notice {
  display: flex;
  align-items: flex-start;
  width: 100%;
  border: 1px solid #e3e9f2;
  border-radius: 10px;
  background: #fff;
  color: #34465e;
  box-shadow: 0 10px 28px #283c5817, 0 2px 6px #283c580d;
}
.ai-run-notice__main {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 11px 6px 11px 12px;
  border: 0;
  border-radius: 10px 0 0 10px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 160ms ease;
}
.ai-run-notice__icon {
  display: grid;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 7px;
}
.ai-run-notice.is-pending .ai-run-notice__icon {
  background: #fdf3e3;
  color: #b45309;
}
.ai-run-notice.is-completed .ai-run-notice__icon {
  background: #e6f3ee;
  color: #357861;
}
.ai-run-notice__text {
  display: grid;
  flex: 1;
  gap: 1px;
  min-width: 0;
}
.ai-run-notice__text strong {
  overflow: hidden;
  color: #34465e;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-run-notice__text span {
  color: #8a98aa;
  font-size: 12px;
  line-height: 1.5;
}
.ai-run-notice__main:hover { background: #f7f9fc; }
.ai-run-notice__close {
  display: grid;
  flex: 0 0 26px;
  width: 26px;
  height: 26px;
  margin: 8px 8px 0 0;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #9aa9be;
  cursor: pointer;
}
.ai-run-notice__close:hover { background: #eef3fa; color: #5677a3; }
.ai-run-notice :is(button):focus-visible {
  outline: 1px solid #b6c2d2;
  outline-offset: -1px;
}
</style>