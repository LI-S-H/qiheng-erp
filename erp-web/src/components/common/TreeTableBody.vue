<script setup lang="ts">
import { onBeforeUpdate, onUpdated, ref } from 'vue';

const bodyRef = ref<HTMLTableSectionElement | null>(null);
const previousKeys = new Set<string>();
const ANIMATION_DURATION = 240;
const ANIMATION_EASING = 'cubic-bezier(0.22, 1, 0.36, 1)';

interface CellMetrics {
  cell: HTMLElement;
  reveal: HTMLElement;
  height: number;
  paddingTop: string;
  paddingBottom: string;
  borderColor: string;
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
    cell.style.overflow = 'hidden';
    cell.style.willChange = 'height, padding, border-color';

    reveal.style.height = '0px';
    reveal.style.opacity = '0';
    reveal.style.overflow = 'hidden';
    reveal.style.transform = 'translateY(-4px)';
    reveal.style.willChange = 'height, opacity, transform';
  });
}

function setExpandedStart(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, revealHeight }) => {
    cell.style.height = `${height}px`;
    cell.style.paddingTop = paddingTop;
    cell.style.paddingBottom = paddingBottom;
    cell.style.borderColor = borderColor;
    cell.style.overflow = 'hidden';
    cell.style.willChange = 'height, padding, border-color';

    reveal.style.height = `${revealHeight}px`;
    reveal.style.opacity = '1';
    reveal.style.overflow = 'hidden';
    reveal.style.transform = 'translateY(0)';
    reveal.style.willChange = 'height, opacity, transform';
  });
}

function clearInlineStyles(metrics: CellMetrics[]) {
  metrics.forEach(({ cell, reveal }) => {
    cell.style.height = '';
    cell.style.paddingTop = '';
    cell.style.paddingBottom = '';
    cell.style.borderColor = '';
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
  setCollapsed(metrics);

  requestAnimationFrame(() => {
    let finishedAnimations = 0;
    const expectedAnimations = metrics.length;
    const handleFinish = () => {
      finishedAnimations += 1;
      if (finishedAnimations >= expectedAnimations) clearInlineStyles(metrics);
    };

    metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, revealHeight }) => {
      const cellAnimation = cell.animate(
        [
          { height: '0px', paddingTop: '0px', paddingBottom: '0px', borderColor: 'transparent' },
          { height: `${height}px`, paddingTop, paddingBottom, borderColor },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING },
      );

      reveal.animate(
        [
          { height: '0px', opacity: 0, transform: 'translateY(-4px)' },
          { height: `${revealHeight}px`, opacity: 1, transform: 'translateY(0)' },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING },
      );

      cellAnimation.onfinish = handleFinish;
    });
  });
}

function animateRowCollapse(row: Element, metrics: CellMetrics[]) {
  if (row.getAttribute('data-tree-row-collapse-animated') === 'true') return;
  row.setAttribute('data-tree-row-collapse-animated', 'true');

  if (row instanceof HTMLElement) {
    row.style.borderColor = 'transparent';
  }

  stopAnimations(metrics);
  setExpandedStart(metrics);

  requestAnimationFrame(() => {
    metrics.forEach(({ cell, reveal, height, paddingTop, paddingBottom, borderColor, revealHeight }) => {
      cell.animate(
        [
          { height: `${height}px`, paddingTop, paddingBottom, borderColor },
          { height: '0px', paddingTop: '0px', paddingBottom: '0px', borderColor: 'transparent' },
        ],
        { duration: ANIMATION_DURATION, easing: ANIMATION_EASING, fill: 'forwards' },
      );

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
    if (isCollapsing) collapsingRows.push({ row, metrics });
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
