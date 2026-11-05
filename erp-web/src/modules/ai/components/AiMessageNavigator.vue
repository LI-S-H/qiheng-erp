<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import type { AiConversationMessage } from '../types';

const props = defineProps<{
  messages: AiConversationMessage[];
  scrollContainer: HTMLElement | null;
}>();
const turns = computed(() => props.messages.filter(message => message.kind === 'USER_INPUT'));
const activeId = ref('');
const navigation = ref<HTMLElement | null>(null);
let pulse: Animation | undefined;

function jumpTo(messageId: string) {
  const root = props.scrollContainer;
  const target = Array.from(root?.querySelectorAll<HTMLElement>('[data-message-id]') || [])
    .find(element => element.dataset.messageId === messageId);
  if (!root || !target) return;
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  root.scrollTo({
    top: root.scrollTop + target.getBoundingClientRect().top - root.getBoundingClientRect().top - 20,
    behavior: reduced ? 'auto' : 'smooth',
  });
  activeId.value = messageId;
  pulse?.cancel();
  const bubble = target.querySelector<HTMLElement>('.message-bubble');
  if (bubble && !reduced) {
    pulse = bubble.animate([
      { backgroundColor: 'color-mix(in srgb, var(--primary) 22%, var(--card))' },
      { backgroundColor: getComputedStyle(bubble).backgroundColor },
    ], { duration: 850, easing: 'ease-out' });
  }
}

watch([
  () => props.scrollContainer,
  () => turns.value.map(message => message.messageId).join('|'),
], ([root], _, onCleanup) => {
  if (!root) return;
  const ids = new Set(turns.value.map(message => message.messageId));
  const anchors = Array.from(root.querySelectorAll<HTMLElement>('[data-message-id]'))
    .filter(element => ids.has(element.dataset.messageId || ''));
  let frame = 0;
  function update() {
    frame = 0;
    const container = root!;
    const edge = container.getBoundingClientRect().top + 40;
    let current = anchors[0];
    for (const anchor of anchors) {
      if (anchor.getBoundingClientRect().top <= edge) current = anchor;
      else break;
    }
    // 最后一轮可能很短，消息区到底时仍应标记最后一条提问。
    if (container.scrollTop + container.clientHeight >= container.scrollHeight - 2) {
      current = anchors[anchors.length - 1];
    }
    activeId.value = current?.dataset.messageId || '';
    const rail = navigation.value;
    const button = rail?.querySelector<HTMLElement>(`[data-turn-id="${activeId.value}"]`);
    if (rail && button) {
      const top = button.offsetTop;
      if (top < rail.scrollTop) rail.scrollTop = top;
      else if (top + button.offsetHeight > rail.scrollTop + rail.clientHeight) {
        rail.scrollTop = top + button.offsetHeight - rail.clientHeight;
      }
    }
  }
  const schedule = () => { if (!frame) frame = requestAnimationFrame(update); };
  root.addEventListener('scroll', schedule, { passive: true });
  const observer = new ResizeObserver(schedule);
  observer.observe(root);
  if (root.firstElementChild) observer.observe(root.firstElementChild);
  update();
  onCleanup(() => {
    root.removeEventListener('scroll', schedule);
    observer.disconnect();
    cancelAnimationFrame(frame);
  });
}, { immediate: true, flush: 'post' });

onBeforeUnmount(() => pulse?.cancel());
</script>

<template>
  <nav v-if="turns.length" ref="navigation" class="message-navigator" aria-label="按用户消息跳转">
    <TooltipProvider :delay-duration="120" :skip-delay-duration="80">
      <Tooltip v-for="(message, index) in turns" :key="message.messageId">
        <TooltipTrigger as-child>
          <button
            type="button"
            class="turn-marker"
            :data-turn-id="message.messageId"
            :aria-label="`跳转到第 ${index + 1} 条用户消息`"
            :aria-current="activeId === message.messageId ? 'step' : undefined"
            @click="jumpTo(message.messageId)"
          ><span aria-hidden="true" /></button>
        </TooltipTrigger>
        <TooltipContent :aria-label="message.content.data.text" side="right" :side-offset="8" class="ai-turn-preview !block !max-w-[min(360px,calc(100vw-48px))] !bg-popover !text-popover-foreground border border-border !p-3 shadow-md">
          <span class="turn-preview-label">用户消息 {{ index + 1 }}</span>
          <p class="turn-preview-text">{{ message.content.data.text }}</p>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  </nav>
</template>

<style scoped>
.message-navigator {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  width: 20px;
  max-height: calc(100% - 32px);
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}
.turn-marker {
  display: grid;
  align-items: center;
  justify-items: start;
  width: 20px;
  height: 15px;
  padding-left: 4px;
  cursor: pointer;
}
.turn-marker > span {
  width: 6px;
  height: 2px;
  border-radius: 2px;
  background: var(--muted-foreground);
  opacity: 0.35;
  transition: width 160ms ease, opacity 160ms ease, background-color 160ms ease;
}
.turn-marker[aria-current] > span { opacity: 1; }
.turn-marker:hover > span,
.turn-marker:focus-visible > span {
  width: 14px;
  opacity: 1;
  background: var(--foreground);
}
.turn-marker:focus-visible {
  outline: none;
}
.turn-preview-label {
  display: block;
  margin-bottom: 6px;
  color: var(--muted-foreground);
  font-size: 11px;
}
:global(.ai-turn-preview svg) {
  display: none !important;
}
.turn-preview-text {
  max-height: min(320px, 60dvh);
  overflow-y: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.7;
}
@media (prefers-reduced-motion: reduce) {
  .turn-marker > span { transition: none; }
}
@media (max-width: 640px) {
  .message-navigator,
  .turn-marker { width: 14px; }
  .turn-marker > span { width: 6px; }
  .turn-marker:hover > span,
  .turn-marker:focus-visible > span { width: 11px; }
}
</style>
