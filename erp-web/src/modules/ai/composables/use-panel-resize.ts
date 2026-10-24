import { onBeforeUnmount, ref } from 'vue';

interface PanelResizeOptions {
  /** 本地存储键：同一用户下次进入仍保持上次拖出来的宽度。 */
  storageKey: string;
  min: number;
  max: number;
  initial: number;
  /** 右侧面板的手柄在左边缘，向左拖动才是变宽。 */
  invert?: boolean;
}

/**
 * 分栏拖拽：只管宽度和落盘，不关心面板内容。
 *
 * <p>用 pointer 事件而不是 mouse 事件，触屏和触控笔也能拖动。</p>
 */
export function usePanelResize(options: PanelResizeOptions) {
  const width = ref(readStoredWidth());
  const dragging = ref(false);
  let startX = 0;
  let startWidth = 0;

  function readStoredWidth() {
    const stored = Number(localStorage.getItem(options.storageKey));
    const valid = Number.isFinite(stored) && stored >= options.min && stored <= options.max;
    return valid ? stored : options.initial;
  }

  function clamp(value: number) {
    return Math.min(options.max, Math.max(options.min, value));
  }

  function applyWidth(next: number) {
    width.value = clamp(next);
  }

  function onPointerMove(event: PointerEvent) {
    const delta = (event.clientX - startX) * (options.invert ? -1 : 1);
    applyWidth(startWidth + delta);
  }

  function stop(commit: boolean) {
    if (!dragging.value) {
      return;
    }
    dragging.value = false;
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerCancel);
    if (commit) {
      localStorage.setItem(options.storageKey, String(width.value));
    }
  }

  function onPointerUp() {
    stop(true);
  }

  function onPointerCancel() {
    stop(false);
  }

  /** 拖动中：按下手柄后才监听全局移动，避免常驻监听。 */
  function startResize(event: PointerEvent) {
    event.preventDefault();
    startX = event.clientX;
    startWidth = width.value;
    dragging.value = true;
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onPointerCancel);
  }

  /** 键盘微调，方向键每次 16 像素，并立即落盘。 */
  function nudge(step: number) {
    applyWidth(width.value + step);
    localStorage.setItem(options.storageKey, String(width.value));
  }

  onBeforeUnmount(() => stop(false));

  return { width, dragging, startResize, nudge };
}
