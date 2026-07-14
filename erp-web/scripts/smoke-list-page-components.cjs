const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/list-page-components',
);
fs.mkdirSync(screenshotDirectory, { recursive: true });

async function mountComponentHarness(page) {
  await page.evaluate(async () => {
    const [{ createApp, h }, { default: ListSummaryStrip }, { default: ListFilterPanel }] = await Promise.all([
      import('/@id/vue'),
      import('/src/components/common/ListSummaryStrip.vue'),
      import('/src/components/common/ListFilterPanel.vue'),
    ]);

    const host = document.createElement('div');
    host.id = 'list-page-component-harness';
    host.style.cssText = [
      'position:fixed',
      'inset:0',
      'z-index:9999',
      'overflow:auto',
      'padding:64px',
      'background:var(--background)',
    ].join(';');
    document.body.append(host);

    const field = (label, placeholder) => h('div', { class: 'space-y-1' }, [
      h('label', { 'data-slot': 'label' }, label),
      h('input', {
        'data-slot': 'input',
        class: 'flex w-full rounded-md border px-3 py-1 text-sm outline-none',
        placeholder,
      }),
    ]);

    createApp({
      render: () => h('main', { class: 'mx-auto max-w-[1120px] space-y-5' }, [
        h('div', {}, [
          h('h1', { class: 'page-title' }, '列表页共享组件验收'),
          h('p', { class: 'page-description' }, '验证统一汇总层级、筛选布局和响应式表现'),
        ]),
        h(ListSummaryStrip, {
          ariaLabel: '测试汇总',
          items: [
            { label: '全部记录', value: 128 },
            { label: '正常', value: 96, tone: 'positive' },
            { label: '需要关注', value: 24, tone: 'warning' },
            { label: '异常', value: 8, tone: 'danger' },
          ],
        }),
        h(ListFilterPanel, { ariaLabel: '测试筛选' }, {
          default: () => [
            field('业务单号', '请输入业务单号'),
            field('往来单位', '请输入名称或编码'),
          ],
          actions: () => [
            h('button', { 'data-slot': 'button', class: 'h-9 rounded-md border px-4 text-sm' }, '重置'),
            h('button', { 'data-slot': 'button', class: 'h-9 rounded-md bg-primary px-4 text-sm text-primary-foreground' }, '查询'),
          ],
          footer: () => h('p', { class: 'text-xs text-muted-foreground' }, '已生效 2 个筛选条件'),
        }),
        h('div', {
          id: 'list-summary-edge-cases',
          'aria-hidden': 'true',
          style: 'position:absolute;left:-10000px;top:0;width:800px',
        }, [
          ...[0, 1, 2, 3].map(count => h(ListSummaryStrip, {
            ariaLabel: `${count} 项汇总`,
            items: Array.from({ length: count }, (_, index) => ({
              label: `指标 ${index + 1}`,
              value: index + 1,
            })),
          })),
        ]),
      ]),
    }).mount(host);
  });
  await page.getByRole('region', { name: '测试汇总' }).waitFor();
}

runSmoke({
  route: '/system/users',
  screenshot: path.join(screenshotDirectory, 'list-page-components-final.png'),
  async test(page) {
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
    await mountComponentHarness(page);

    const summary = page.locator('#list-page-component-harness main > [data-list-summary]');
    const filter = page.locator('#list-page-component-harness [data-list-filter-panel]');
    await page.getByRole('region', { name: '测试汇总' }).waitFor();
    await page.getByRole('search', { name: '测试筛选' }).waitFor();
    const desktopState = await page.evaluate(() => {
      const summaryElement = document.querySelector('#list-page-component-harness [data-list-summary]');
      const filterElement = document.querySelector('#list-page-component-harness [data-list-filter-panel]');
      const summaryStyle = getComputedStyle(summaryElement);
      const filterStyle = getComputedStyle(filterElement);
      const input = filterElement.querySelector('[data-slot="input"]');
      return {
        summaryColumns: summaryStyle.gridTemplateColumns.split(' ').length,
        summaryShadow: summaryStyle.boxShadow,
        summaryBorder: summaryStyle.borderTopWidth,
        metricCount: summaryElement.querySelectorAll('dl').length,
        definitionCount: summaryElement.querySelectorAll('dt').length,
        valueCount: summaryElement.querySelectorAll('dd').length,
        filterShadow: filterStyle.boxShadow,
        filterBorder: filterStyle.borderTopWidth,
        inputHeight: input.getBoundingClientRect().height,
        actionOrder: [...filterElement.querySelectorAll('.filter-actions > *')].map(item => item.textContent.trim()),
        hasFooter: Boolean(filterElement.querySelector('.list-filter-panel__footer')),
      };
    });
    if (desktopState.summaryColumns !== 4 || desktopState.metricCount !== 4
      || desktopState.definitionCount !== 4 || desktopState.valueCount !== 4
      || desktopState.summaryShadow === 'none' || desktopState.filterShadow === 'none'
      || desktopState.summaryBorder === '0px' || desktopState.filterBorder === '0px'
      || desktopState.inputHeight < 36 || desktopState.actionOrder.join(',') !== '重置,查询'
      || !desktopState.hasFooter) {
      throw new Error(`列表页共享组件桌面布局异常：${JSON.stringify(desktopState)}`);
    }

    const desktopEdgeStates = await page.locator('#list-summary-edge-cases [data-list-summary]').evaluateAll(elements => elements.map(element => ({
      columns: getComputedStyle(element).gridTemplateColumns.split(' ').length,
      itemCount: element.querySelectorAll('.summary-item').length,
      emptyText: element.querySelector('.list-summary-strip__empty')?.textContent?.trim() || '',
    })));
    const desktopEdgeInvalid = desktopEdgeStates.some((state, index) => (
      state.columns !== Math.max(index, 1)
      || state.itemCount !== index
      || (index === 0 && state.emptyText !== '暂无汇总数据')
    ));
    if (desktopEdgeInvalid) {
      throw new Error(`汇总组件桌面边界项布局异常：${JSON.stringify(desktopEdgeStates)}`);
    }

    const firstMetric = summary.locator('.summary-item').first();
    const beforeHover = await firstMetric.evaluate(element => getComputedStyle(element).backgroundColor);
    await firstMetric.hover();
    await page.waitForTimeout(180);
    const afterHover = await firstMetric.evaluate(element => ({
      background: getComputedStyle(element).backgroundColor,
      accentOpacity: Number.parseFloat(getComputedStyle(element, '::after').opacity),
    }));
    if (beforeHover === afterHover.background || afterHover.accentOpacity < 0.9) {
      throw new Error(`汇总指标悬停层级不清晰：${JSON.stringify({ beforeHover, afterHover })}`);
    }

    const firstInput = filter.locator('[data-slot="input"]').first();
    await firstInput.focus();
    const focusState = await firstInput.evaluate(element => ({
      outline: getComputedStyle(element).outlineStyle,
      shadow: getComputedStyle(element).boxShadow,
    }));
    if (focusState.outline === 'none' && focusState.shadow === 'none') {
      throw new Error(`筛选控件缺少键盘焦点反馈：${JSON.stringify(focusState)}`);
    }
    await page.screenshot({ path: path.join(screenshotDirectory, 'list-page-components-desktop.png'), fullPage: true });

    await page.setViewportSize({ width: 900, height: 900 });
    await page.waitForTimeout(100);
    const tabletColumns = await page.locator('#list-summary-edge-cases [data-list-summary]').evaluateAll(elements => (
      elements.map(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)
    ));
    if (tabletColumns.join(',') !== '1,1,2,2') {
      throw new Error(`汇总组件平板边界项布局异常：${JSON.stringify(tabletColumns)}`);
    }

    await page.setViewportSize({ width: 520, height: 900 });
    await page.waitForTimeout(100);
    const mobileState = await page.evaluate(() => {
      const summaryElement = document.querySelector('#list-page-component-harness [data-list-summary]');
      const filterElement = document.querySelector('#list-page-component-harness [data-list-filter-panel]');
      return {
        summaryColumns: getComputedStyle(summaryElement).gridTemplateColumns.split(' ').length,
        filterColumns: getComputedStyle(filterElement.querySelector('.filter-grid')).gridTemplateColumns.split(' ').length,
        pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      };
    });
    if (mobileState.summaryColumns !== 1 || mobileState.filterColumns !== 1 || mobileState.pageOverflow > 1) {
      throw new Error(`列表页共享组件移动端布局异常：${JSON.stringify(mobileState)}`);
    }
    await page.screenshot({ path: path.join(screenshotDirectory, 'list-page-components-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 900 });
  },
}).then(() => {
  console.log('SMOKE_OK: 列表页汇总与筛选共享组件视觉、交互和响应式布局通过');
}).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
