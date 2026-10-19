import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue';

interface TextTableColumnDefinition {
  key: string;
  minWidth?: number;
  fixedWidth?: number;
}

/** 根据实际字形而非元素盒宽排列表格；会话内保留已见最长值，减少翻页跳动。 */
export function useTextTableColumns(root: Ref<HTMLElement | null>, records: () => unknown, options?: { definitions: () => readonly TextTableColumnDefinition[] }) {
  const defaults = [140, 150, 110, 80, 112, 120, 96, 130, 76];
  const definitions = (): readonly TextTableColumnDefinition[] => options?.definitions() ?? defaults.map((_, index) => ({ key: String(index) }));
  const columns = ref(options ? definitions().map(column => column.fixedWidth ?? column.minWidth ?? 100) : defaults);
  const tableWidth = ref(columns.value.reduce((sum, width) => sum + width, 0));
  // 可隐藏列必须按字段而非当前位置缓存，否则恢复时会套用其它字段的宽度。
  const contentWidths = new Map<string, number>();
  let observer: ResizeObserver | undefined;
  let frame = 0;
  let disposed = false;
  let hasResults = false;
  const number = (value: string) => Number.parseFloat(value) || 0;

  function measure() {
    if (!hasResults || document.fonts.status === 'loading') return;
    const page = root.value;
    const table = page?.querySelector<HTMLTableElement>('.business-data-table');
    const viewport = table?.parentElement;
    if (!page || !table || !viewport || !page.parentElement) return;
    const context = document.createElement('canvas').getContext('2d');
    if (!context) return;
    const visible = definitions();
    if (!visible.length) return;
    const measured = visible.map(column => column.fixedWidth ?? column.minWidth ?? 0);

    for (const row of table.rows) {
      // 展开明细中的嵌套表不能参与主表列宽测量，即使两张表恰好列数相同。
      if (row.closest('table') !== table) continue;
      if (row.cells.length !== visible.length) continue;
      Array.from(row.cells).forEach((cell, index) => {
        const cellStyle = getComputedStyle(cell);
        const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
        let width = 0;
        let text: Node | null;
        while ((text = walker.nextNode())) {
          const value = text.textContent?.trim();
          const parent = text.parentElement;
          if (!value || !parent || parent.closest('.sr-only')) continue;
          const style = getComputedStyle(parent);
          if (style.display === 'none' || style.visibility === 'hidden') continue;
          // Range 只框住文本节点，保留真实字距与等宽数字排版，不包含 div 盒子的空白。
          const range = document.createRange();
          range.selectNodeContents(text);
          let textWidth = range.getBoundingClientRect().width;
          if (!textWidth) {
            context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
            textWidth = context.measureText(value).width;
          }
          // 两行信息取较长的一行；仅追加真实内边距，不读取占满整格的 div 宽度。
          for (let element: HTMLElement | null = parent; element && element !== cell; element = element.parentElement) {
            const inner = getComputedStyle(element);
            const cap = inner.maxWidth.endsWith('px') ? Number.parseFloat(inner.maxWidth) : Number.NaN;
            if (Number.isFinite(cap)) textWidth = Math.min(textWidth, cap);
            textWidth += number(inner.paddingLeft) + number(inner.paddingRight) + number(inner.borderLeftWidth) + number(inner.borderRightWidth);
          }
          width = Math.max(width, textWidth);
        }
        measured[index] = Math.max(measured[index]!, Math.ceil(width + number(cellStyle.paddingLeft) + number(cellStyle.paddingRight) + 2));
      });
    }

    const widths = visible.map((column, index) => {
      const width = column.fixedWidth ?? Math.max(contentWidths.get(column.key) || 0, measured[index]!);
      contentWidths.set(column.key, width);
      return width;
    });
    const naturalWidth = widths.reduce((sum, width) => sum + width, 0);
    if (!naturalWidth) return;
    const pageStyle = getComputedStyle(page);
    const padding = number(pageStyle.paddingLeft) + number(pageStyle.paddingRight);
    const gutter = Math.max(0, viewport.offsetWidth - viewport.clientWidth);
    // 页面始终铺满工作区；文字决定各列基数，剩余空间均匀分配，避免单列兜底。
    const available = page.parentElement.clientWidth - padding - gutter;
    const flexibleCount = visible.filter(column => column.fixedWidth == null).length;
    const extra = flexibleCount ? Math.max(0, (available - naturalWidth) / flexibleCount) : 0;
    columns.value = widths.map((width, index) => width + (visible[index]!.fixedWidth == null ? extra : 0));
    tableWidth.value = columns.value.reduce((sum, width) => sum + width, 0);
  }

  function schedule() {
    if (disposed) return;
    cancelAnimationFrame(frame);
    frame = requestAnimationFrame(measure);
  }

  watch(records, async () => { hasResults = true; await nextTick(); schedule(); }, { flush: 'post' });
  onMounted(() => {
    observer = new ResizeObserver(schedule);
    if (root.value?.parentElement) observer.observe(root.value.parentElement);
    void document.fonts.ready.then(() => { if (!disposed) schedule(); });
    document.fonts.addEventListener('loadingdone', schedule);
    schedule();
  });
  onBeforeUnmount(() => {
    disposed = true;
    observer?.disconnect();
    cancelAnimationFrame(frame);
    document.fonts.removeEventListener('loadingdone', schedule);
  });

  return { columns, tableWidth };
}
