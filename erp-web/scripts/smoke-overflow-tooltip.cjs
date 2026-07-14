const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/overflow-tooltip',
);
fs.mkdirSync(screenshotDirectory, { recursive: true });

async function mountOverflowHarness(page) {
  await page.evaluate(async () => {
    const [{ createApp, h, nextTick, ref }, { default: OverflowTooltip }, { default: TooltipProvider }] = await Promise.all([
      import('/@id/vue'),
      import('/src/components/common/OverflowTooltip.vue'),
      import('/src/components/ui/tooltip/TooltipProvider.vue'),
    ]);

    const host = document.createElement('div');
    host.id = 'overflow-tooltip-harness';
    host.style.cssText = [
      'position:fixed',
      'inset:0',
      'z-index:40',
      'overflow:auto',
      'padding:64px',
      'background:var(--background)',
    ].join(';');
    document.body.append(host);

    const dynamicText = ref('尺寸充足时不显示提示');
    const dynamicWidth = ref(420);
    window.__overflowTooltipHarness = {
      async setText(text) {
        dynamicText.value = text;
        await nextTick();
      },
      async setWidth(width) {
        dynamicWidth.value = width;
        await nextTick();
      },
    };

    createApp({
      render: () => h(TooltipProvider, { delayDuration: 300 }, () => h('main', { class: 'mx-auto max-w-[920px] space-y-8' }, [
        h('div', {}, [
          h('h1', { class: 'page-title' }, '省略文本悬停组件验收'),
          h('p', { class: 'page-description' }, '只有实际溢出的内容才显示完整文本提示'),
        ]),
        h('section', { class: 'space-y-2 rounded-xl border bg-card p-5' }, [
          h('strong', {}, '短文本'),
          h(OverflowTooltip, {
            text: '内容完整可见',
            class: 'block w-[320px] text-sm',
            'data-overflow-case': 'short',
          }),
        ]),
        h('section', { class: 'space-y-2 rounded-xl border bg-card p-5' }, [
          h('strong', {}, '单行省略'),
          h(OverflowTooltip, {
            text: '仓库备注：仅用于华东区域成品收发，夜间到货请提前联系值班管理员确认月台。',
            class: 'block w-[220px] text-sm text-muted-foreground',
            'data-overflow-case': 'single',
          }),
        ]),
        h('section', { class: 'space-y-2 rounded-xl border bg-card p-5' }, [
          h('strong', {}, '两行省略'),
          h(OverflowTooltip, {
            text: '这是一段较长的业务说明，用于验证多行文本在第二行结束后显示省略号，并在悬停或键盘聚焦时展示没有截断的完整说明内容。',
            lines: 2,
            class: 'block w-[280px] text-sm leading-5 text-muted-foreground',
            'data-overflow-case': 'multi',
          }),
        ]),
        h('section', { class: 'space-y-2 rounded-xl border bg-card p-5' }, [
          h('strong', {}, '动态内容与尺寸'),
          h(OverflowTooltip, {
            text: dynamicText.value,
            class: 'block text-sm text-muted-foreground',
            style: `width:${dynamicWidth.value}px`,
            'data-overflow-case': 'dynamic',
          }),
        ]),
      ])),
    }).mount(host);
  });
  await page.getByRole('heading', { name: '省略文本悬停组件验收' }).waitFor();
}

runSmoke({
  route: '/system/users',
  screenshot: path.join(screenshotDirectory, 'overflow-tooltip-final.png'),
  async test(page) {
    await page.getByRole('heading', { name: '用户管理' }).waitFor();
    await mountOverflowHarness(page);

    const shortText = page.locator('[data-overflow-case="short"]');
    const singleLine = page.locator('[data-overflow-case="single"]');
    const multiLine = page.locator('[data-overflow-case="multi"]');
    const dynamic = page.locator('[data-overflow-case="dynamic"]');
    await page.waitForFunction(() => document.querySelector('[data-overflow-case="single"]')?.dataset.overflowing === 'true');

    if (await shortText.getAttribute('data-overflowing') || await shortText.getAttribute('tabindex')) {
      throw new Error('短文本不应启用 Tooltip 或进入 Tab 顺序');
    }
    await shortText.hover();
    await page.waitForTimeout(360);
    if (await page.locator('[data-slot="tooltip-content"]').count()) throw new Error('短文本悬停时不应显示 Tooltip');

    for (const target of [singleLine, multiLine]) {
      if (await target.getAttribute('data-overflowing') !== 'true' || await target.getAttribute('tabindex') !== '0') {
        throw new Error('被省略文本必须可悬停并可键盘聚焦');
      }
    }

    await singleLine.hover();
    const tooltip = page.locator('[data-slot="tooltip-content"]');
    await page.waitForTimeout(500);
    if (await tooltip.count() === 0) {
      const debugState = await page.evaluate(() => {
        const trigger = document.querySelector('[data-overflow-case="single"]');
        return {
          triggerHtml: trigger?.outerHTML,
          triggerState: trigger?.getAttribute('data-state'),
          tooltipRoots: document.querySelectorAll('[data-slot="tooltip"]').length,
          tooltipContents: document.querySelectorAll('[data-slot="tooltip-content"]').length,
        };
      });
      throw new Error(`溢出文本悬停后 Tooltip 未打开：${JSON.stringify(debugState)}`);
    }
    await tooltip.waitFor();
    const describedBy = await singleLine.getAttribute('aria-describedby');
    const accessibleDescription = describedBy ? page.locator(`#${describedBy}`) : null;
    if (!accessibleDescription || await accessibleDescription.count() !== 1) {
      throw new Error(`Tooltip 无障碍描述关联异常：${JSON.stringify({ describedBy })}`);
    }
    await tooltip.getByText('仓库备注：仅用于华东区域成品收发，夜间到货请提前联系值班管理员确认月台。', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(screenshotDirectory, 'overflow-tooltip-hover.png'), fullPage: true });

    await page.mouse.move(900, 860);
    await tooltip.waitFor({ state: 'hidden' });
    await multiLine.focus();
    await tooltip.waitFor();
    const focusOutline = await multiLine.evaluate(element => getComputedStyle(element).outlineStyle);
    if (focusOutline === 'none') throw new Error('省略文本键盘聚焦时缺少可见焦点');
    await page.screenshot({ path: path.join(screenshotDirectory, 'overflow-tooltip-focus.png'), fullPage: true });

    await multiLine.hover();
    await page.mouse.move(900, 860);
    await page.waitForTimeout(120);
    if (!await tooltip.isVisible()) throw new Error('文本保持键盘焦点时，pointerleave 不应关闭 Tooltip');
    await page.keyboard.press('Tab');
    await tooltip.waitFor({ state: 'hidden' });

    await singleLine.hover();
    await tooltip.waitFor();
    await singleLine.focus();
    await singleLine.evaluate(element => element.blur());
    await page.waitForTimeout(120);
    if (!await tooltip.isVisible()) throw new Error('指针仍悬停文本时，blur 不应关闭 Tooltip');
    await page.mouse.move(900, 860);
    await tooltip.waitFor({ state: 'hidden' });

    await singleLine.focus();
    await tooltip.waitFor();
    await page.keyboard.press('Escape');
    await tooltip.waitFor({ state: 'hidden' });
    await page.keyboard.press('Tab');

    await page.evaluate(() => window.__overflowTooltipHarness.setText(
      '动态内容更新后会变成长文本，此时组件必须重新计算是否发生溢出，并自动启用完整内容提示。',
    ));
    await page.waitForFunction(() => document.querySelector('[data-overflow-case="dynamic"]')?.dataset.overflowing === 'true');
    await page.evaluate(() => window.__overflowTooltipHarness.setWidth(760));
    await page.waitForFunction(() => !document.querySelector('[data-overflow-case="dynamic"]')?.dataset.overflowing);
    await page.evaluate(() => window.__overflowTooltipHarness.setWidth(120));
    await page.waitForFunction(() => document.querySelector('[data-overflow-case="dynamic"]')?.dataset.overflowing === 'true');
    await page.evaluate(() => window.__overflowTooltipHarness.setText('短内容'));
    await page.waitForFunction(() => !document.querySelector('[data-overflow-case="dynamic"]')?.dataset.overflowing);

    const geometry = await Promise.all([singleLine, multiLine].map(locator => locator.evaluate(element => ({
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    }))));
    if (geometry[0].scrollWidth <= geometry[0].clientWidth || geometry[1].scrollHeight <= geometry[1].clientHeight) {
      throw new Error(`省略状态与真实几何溢出不一致：${JSON.stringify(geometry)}`);
    }
  },
}).then(() => {
  console.log('SMOKE_OK: 省略文本 Tooltip 的短文本、单行、多行、悬停、焦点与动态重算通过');
}).catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
