<script setup lang="ts">
import { onBeforeUpdate, onUpdated, ref } from 'vue';

const bodyRef = ref<HTMLTableSectionElement | null>(null);
const previousKeys = new Set<string>();
const ANIMATION_DURATION = 240;
const ANIMATION_EASING = 'cubic-bezier(0.22, 1, 0.36, 1)';
const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

interface CellMetrics {
  cell: HTMLElement;
  reveal: HTMLElement;
  height: number;
  paddingTop: string;
  paddingBottom: string;
  borderColor: string;
  lineHeight: string;
  revealHeight: number;
}

let hadRowsBeforeUpdate = false;
let animationFrame = 0;

function getRowKey(row: Element, index: number) {
  return row.getAttribute('data-tree-row-key') || String(index);
}

function getRows() {
  return Array.from(bodyRef.value?.querySelectorAll(':scope > tr') || []);
}

function getCells(row: Element) {
  return Array.from(row.children).filter((child): child is HTMLElement => child instanceof HTMLElement);
}

function getReveal(cell: HTMLElement) {
  return cell.querySelector(':scope > .tree-table-cell-reveal') as HTMLElement | null;
}

function stopAnimations(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal }) => {
    cell.getAnimations().forEach(animation => animation.cancel());
    reveal.getAnimations().forEach(animation => animation.cancel());
  });
}

function prefersReducedMotion() {
  return window.matchMedia(REDUCED_MOTION_QUERY).matches;
}

function measureRow(row: Element): CellMetrics[] {
  return getCells(row).map((cell) => {
    const reveal = getReveal(cell) || cell;
    const style = window.getComputedStyle(cell);
    return {
      cell,
      reveal,
      height: cell.getBoundingClientRect().height,
      paddingTop: style.paddingTop,
      paddingBottom: style.paddingBottom,
      borderColor: style.borderColor,
      lineHeight: style.lineHeight,
      revealHeight: reveal.scrollHeight || reveal.getBoundingClientRect().height,
    };
  });
}

function setCollapsed(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal }) => {
    cell.style.height = '0px';
    cell.style.paddingTop = '0px';
    cell.style.paddingBottom = '0px';
    cell.style.borderColor = 'transparent';
    cell.style.lineHeight = '0px';
    cell.style.overflow = 'hidden';
    cell.style.willChange = 'height, padding, border-color, line-height';

    reveal.style.height = '0px';
    reveal.style.opacity = '0';
    reveal.style.overflow = 'hidden';
    reveal.style.transform = 'translateY(-4px)';
    reveal.style.willChange = 'height, opacity, transform';
  });
}

function setExpandedStart(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, lineHeight, revealHeight }) => {
    cell.style.height = `${height}px`;
    cell.style.paddingTop = paddingTop;
    cell.style.paddingBottom = paddingBottom;
    cell.style.borderColor = borderColor;
    cell.style.lineHeight = lineHeight;
    cell.style.overflow = 'hidden';
    cell.style.willChange = 'height, padding, border-color, line-height';

    reveal.style.height = `${revealHeight}px`;
    reveal.style.opacity = '1';
    reveal.style.overflow = 'hidden';
    reveal.style.transform = 'translateY(0)';
    reveal.style.willChange = 'height, opacity, transform';
  });
}

function lockExpandedStyles(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, lineHeight, revealHeight }) => {
    cell.style.height = `${height}px`;
    cell.style.paddingTop = paddingTop;
    cell.style.paddingBottom = paddingBottom;
    cell.style.borderColor = borderColor;
    cell.style.lineHeight = lineHeight;
    cell.style.overflow = 'hidden';
    cell.style.willChange = '';

    reveal.style.height = `${revealHeight}px`;
    reveal.style.opacity = '1';
    reveal.style.overflow = 'hidden';
    reveal.style.transform = 'translateY(0)';
    reveal.style.willChange = '';
  });
}

function lockCollapsedStyles(metrics: CellMetrics[]) {
  setCollapsed(metrics);
  metrics.forEach(({ cell, reveal }) => {
    cell.style.willChange = '';
    reveal.style.willChange = '';
  });
}

function resetAnimatedStyles(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal }) => {
    cell.style.height = '';
    cell.style.paddingTop = '';
    cell.style.paddingBottom = '';
    cell.style.borderColor = '';
    cell.style.lineHeight = '';
    cell.style.overflow = '';
    cell.style.willChange = '';

    reveal.style.height = '';
    reveal.style.opacity = '';
    reveal.style.overflow = '';
    reveal.style.transform = '';
    reveal.style.willChange = '';
  });
}

function animateRowEnter(metrics: CellMetrics[]) {
  stopAnimations(metrics);
  if (prefersReducedMotion()) {
    lockExpandedStyles(metrics);
    return;
  }
  setCollapsed(metrics);

  requestAnimationFrame(() => {
    let finishedAnimations = 0;
    const expectedAnimations = metrics.length;
    const handleFinish = () => {
      finishedAnimations += 1;
      if (finishedAnimations >= expectedAnimations) {
        requestAnimationFrame(() => {
          lockExpandedStyles(metrics);
          stopAnimations(metrics);
        });
      }
    };

    metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, lineHeight, revealHeight }) => {
      const cellAnimation = cell.animate(
        [
          { height: '0px', paddingTop: '0px', paddingBottom: '0px', borderColor: 'transparent', lineHeight: '0px' },
          { height: `${height}px`, paddingTop, paddingBottom, borderColor, lineHeight },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING, fill: 'forwards' },
      );

      reveal.animate(
        [
          { height: '0px', opacity: 0, transform: 'translateY(-4px)' },
          { height: `${revealHeight}px`, opacity: 1, transform: 'translateY(0)' },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING, fill: 'forwards' },
      );

      cellAnimation.onfinish = handleFinish;
    });
  });
}

function animateRowCollapse(row: Element, metrics: CellMetrics[]) {
  if (row.getAttribute('data-tree-row-collapse-animated') === 'true') return;
  row.setAttribute('data-tree-row-collapse-animated', 'true');

  if (row instanceof HTMLElement) {
    row.style.transition = 'none';
    row.style.borderColor = 'transparent';
    row.style.borderWidth = '0px';
    row.style.borderBottomColor = 'transparent';
    row.style.borderBottomWidth = '0px';
  }

  stopAnimations(metrics);
  setExpandedStart(metrics);

  if (prefersReducedMotion()) {
    lockCollapsedStyles(metrics);
    return;
  }

  requestAnimationFrame(() => {
    let finishedAnimations = 0;
    const expectedAnimations = metrics.length;
    const handleFinish = () => {
      finishedAnimations += 1;
      if (finishedAnimations >= expectedAnimations) {
        requestAnimationFrame(() => {
          if (row.getAttribute('data-tree-row-collapsing') !== 'true') return;
          // 保持单元格为零高直到 Vue 移除节点，避免 visibility: collapse
          // 触发表格轨道的二次重算，造成收起末帧横向变宽或平移。
          lockCollapsedStyles(metrics);
          stopAnimations(metrics);
        });
      }
    };

    metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, lineHeight, revealHeight }) => {
      const cellAnimation = cell.animate(
        [
          { height: `${height}px`, paddingTop, paddingBottom, borderColor, lineHeight },
          { height: '0px', paddingTop: '0px', paddingBottom: '0px', borderColor: 'transparent', lineHeight: '0px' },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING, fill: 'forwards' },
      );

      cellAnimation.onfinish = handleFinish;

      reveal.animate(
        [
          { height: `${revealHeight}px`, opacity: 1, transform: 'translateY(0)' },
          { height: '0px', opacity: 0, transform: 'translateY(-4px)' },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING, fill: 'forwards' },
      );
    });
  });
}

onBeforeUpdate(() => {
  const rows = getRows();
  hadRowsBeforeUpdate = rows.length > 0;
  previousKeys.clear();
  rows.forEach((row, index) => previousKeys.add(getRowKey(row, index)));
});

onUpdated(() => {
  cancelAnimationFrame(animationFrame);
  const rows = getRows();

  const enteringMetrics: CellMetrics[][] = [];
  const collapsingRows: Array<{ row: Element; metrics: CellMetrics[] }> = [];

  rows.forEach((row, index) => {
    const metrics = measureRow(row);
    const isCollapsing = row.getAttribute('data-tree-row-collapsing') === 'true';
    const isEntering = hadRowsBeforeUpdate && !previousKeys.has(getRowKey(row, index)) && !isCollapsing;

    if (isEntering) enteringMetrics.push(metrics);
    if (isCollapsing) {
      collapsingRows.push({ row, metrics });
    } else if (row instanceof HTMLElement) {
      if (row.getAttribute('data-tree-row-collapse-animated') === 'true') {
        // 用户在收起过程中重新展开时，必须先取消旧动画并清除零高样式，
        // 避免旧 onfinish 在新状态后再次把行锁为不可见。
        stopAnimations(metrics);
        resetAnimatedStyles(metrics);
        row.removeAttribute('data-tree-row-collapse-animated');
      }
      row.style.transition = '';
      row.style.borderColor = '';
      row.style.borderWidth = '';
      row.style.borderBottomColor = '';
      row.style.borderBottomWidth = '';
    }
  });

  enteringMetrics.forEach(metrics => animateRowEnter(metrics));
  animationFrame = requestAnimationFrame(() => {
    collapsingRows.forEach(({ row, metrics }) => animateRowCollapse(row, metrics));
  });
});
</script>

<template>
  <tbody
    ref="bodyRef"
    data-slot="table-body"
    class="[&_tr:last-child]:border-0"
  >
    <slot />
  </tbody>
</template>

<style>
tr[data-tree-row-collapsing="true"] {
  border-color: transparent !important;
  border-width: 0 !important;
  border-bottom-color: transparent !important;
  border-bottom-width: 0 !important;
  transition-property: none !important;
}
</style>
