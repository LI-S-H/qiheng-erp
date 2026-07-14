const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-07-14-135451-sticky-column-elevation',
);

fs.mkdirSync(screenshotDirectory, { recursive: true });

function durationToMilliseconds(value) {
  return value.split(',').reduce((maximum, duration) => {
    const normalized = duration.trim();
    const milliseconds = normalized.endsWith('ms')
      ? Number.parseFloat(normalized)
      : Number.parseFloat(normalized) * 1000;
    return Math.max(maximum, milliseconds);
  }, 0);
}

async function assertSharedTableBehavior(page, reducedMotion) {
  await page.getByRole('heading', { name: '入库单' }).waitFor();

  const viewport = page.locator('.stock-bill-table-scroll [data-slot="table-container"]').first();
  await viewport.waitFor();
  await page.waitForFunction(
    element => element?.getAttribute('data-horizontal-overflow') === 'true',
    await viewport.elementHandle(),
  );

  const initialState = await viewport.evaluate(element => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    scrollStart: element.getAttribute('data-scroll-start'),
    scrollEnd: element.getAttribute('data-scroll-end'),
    role: element.getAttribute('role'),
    tabIndex: element.tabIndex,
    ariaLabel: element.getAttribute('aria-label'),
    boxShadow: getComputedStyle(element).boxShadow,
    transitionDuration: getComputedStyle(element).transitionDuration,
  }));

  if (initialState.scrollWidth <= initialState.clientWidth) {
    throw new Error(`宽表没有形成容器内横向滚动：${JSON.stringify(initialState)}`);
  }
  if (initialState.scrollStart !== null || initialState.scrollEnd !== 'true') {
    throw new Error(`表格初始滚动方向提示错误：${JSON.stringify(initialState)}`);
  }
  if (initialState.role !== 'region' || initialState.tabIndex !== 0 || !initialState.ariaLabel) {
    throw new Error(`横向滚动区域缺少可访问入口：${JSON.stringify(initialState)}`);
  }
  if (initialState.boxShadow === 'none') {
    throw new Error('表格仍可向右滚动时没有显示右侧边缘提示');
  }

  const startEdge = viewport.locator('[data-slot="table-cell"][data-table-sticky-edge="start"]').first();
  const endEdge = viewport.locator('[data-slot="table-cell"][data-table-sticky-edge="end"]').first();
  const readEdgeState = edge => edge.evaluate((element) => {
    const style = getComputedStyle(element, '::after');
    const rect = element.getBoundingClientRect();
    return {
      opacity: Number.parseFloat(style.opacity),
      backgroundImage: style.backgroundImage,
      transitionDuration: style.transitionDuration,
      x: rect.x,
      width: rect.width,
    };
  });
  const [initialStartEdge, initialEndEdge] = await Promise.all([
    readEdgeState(startEdge),
    readEdgeState(endEdge),
  ]);
  if (initialStartEdge.opacity > 0.01 || initialEndEdge.opacity < 0.5 || initialEndEdge.backgroundImage === 'none') {
    throw new Error(`固定列初始边缘层次错误：${JSON.stringify({ initialStartEdge, initialEndEdge })}`);
  }

  const transitionMilliseconds = durationToMilliseconds(initialState.transitionDuration);
  if (reducedMotion && transitionMilliseconds > 1) {
    throw new Error(`减少动态效果模式下过渡未关闭：${initialState.transitionDuration}`);
  }
  if (!reducedMotion && transitionMilliseconds < 100) {
    throw new Error(`普通模式下缺少克制的状态过渡：${initialState.transitionDuration}`);
  }

  await viewport.focus();
  const focusOutline = await viewport.evaluate(element => getComputedStyle(element).outlineStyle);
  if (focusOutline === 'none') throw new Error('横向滚动区域的键盘焦点不可见');

  await viewport.evaluate(element => {
    element.scrollLeft = (element.scrollWidth - element.clientWidth) / 2;
    element.dispatchEvent(new Event('scroll'));
  });
  await page.waitForFunction(
    element => element?.getAttribute('data-scroll-start') === 'true'
      && element?.getAttribute('data-scroll-end') === 'true',
    await viewport.elementHandle(),
  );
  await page.waitForTimeout(reducedMotion ? 5 : 170);
  const [middleStartEdge, middleEndEdge] = await Promise.all([
    readEdgeState(startEdge),
    readEdgeState(endEdge),
  ]);
  if (middleStartEdge.opacity < 0.5 || middleEndEdge.opacity < 0.5) {
    throw new Error(`固定列在中段滚动时未显示双向边缘层次：${JSON.stringify({ middleStartEdge, middleEndEdge })}`);
  }

  const stickyRow = startEdge.locator('xpath=parent::tr');
  const beforeHover = await Promise.all([readEdgeState(startEdge), readEdgeState(endEdge)]);
  await stickyRow.hover();
  await page.waitForTimeout(reducedMotion ? 5 : 180);
  const afterHover = await Promise.all([readEdgeState(startEdge), readEdgeState(endEdge)]);
  if (afterHover.some(edge => edge.opacity < 0.95)) {
    throw new Error(`固定列 hover 层次不明显：${JSON.stringify(afterHover)}`);
  }
  for (let index = 0; index < afterHover.length; index += 1) {
    if (Math.abs(afterHover[index].x - beforeHover[index].x) > 0.5 || Math.abs(afterHover[index].width - beforeHover[index].width) > 0.5) {
      throw new Error(`固定列 hover 引发布局位移：${JSON.stringify({ beforeHover, afterHover })}`);
    }
  }
  if (!reducedMotion) {
    await page.screenshot({ path: path.join(screenshotDirectory, 'sticky-column-middle-hover.png'), fullPage: true });
  }

  const actualMaxScrollLeft = await viewport.evaluate(element => {
    element.scrollLeft = Number.MAX_SAFE_INTEGER;
    element.dispatchEvent(new Event('scroll'));
    return element.scrollLeft;
  });

  await viewport.evaluate((element, maximum) => {
    element.scrollLeft = Math.max(0, maximum - 8);
    element.dispatchEvent(new Event('scroll'));
  }, actualMaxScrollLeft);
  await page.waitForFunction(
    element => element?.getAttribute('data-scroll-start') === 'true'
      && element?.getAttribute('data-scroll-end') === 'true',
    await viewport.elementHandle(),
  );

  await viewport.evaluate((element, maximum) => {
    element.scrollLeft = maximum;
    element.dispatchEvent(new Event('scroll'));
  }, actualMaxScrollLeft);
  await page.waitForTimeout(150);
  const endState = await viewport.evaluate(element => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    scrollLeft: element.scrollLeft,
    scrollStart: element.getAttribute('data-scroll-start'),
    scrollEnd: element.getAttribute('data-scroll-end'),
  }));
  if (Math.abs(endState.scrollLeft - actualMaxScrollLeft) > 1) {
    throw new Error(`表格没有稳定滚动到浏览器实际末端：${JSON.stringify({ actualMaxScrollLeft, ...endState })}`);
  }
  if (endState.scrollStart !== 'true' || endState.scrollEnd !== null) {
    throw new Error(`表格滚动到末端后的方向提示错误：${JSON.stringify(endState)}`);
  }
  const [endStartEdge, endEndEdge] = await Promise.all([
    readEdgeState(startEdge),
    readEdgeState(endEdge),
  ]);
  if (endStartEdge.opacity < 0.5 || endEndEdge.opacity > 0.01) {
    throw new Error(`固定列末端边缘层次错误：${JSON.stringify({ endStartEdge, endEndEdge })}`);
  }

  const edgeTransitionMilliseconds = durationToMilliseconds(middleStartEdge.transitionDuration);
  if (reducedMotion && edgeTransitionMilliseconds > 1) {
    throw new Error(`减少动态效果模式下固定列边缘过渡未关闭：${middleStartEdge.transitionDuration}`);
  }
  if (!reducedMotion && edgeTransitionMilliseconds < 100) {
    throw new Error(`普通模式下固定列边缘缺少克制过渡：${middleStartEdge.transitionDuration}`);
  }

  await page.setViewportSize({ width: 2560, height: 900 });
  await page.waitForTimeout(150);
  const noOverflowState = await viewport.evaluate(element => ({
    overflow: element.getAttribute('data-horizontal-overflow'),
    scrollStart: element.getAttribute('data-scroll-start'),
    scrollEnd: element.getAttribute('data-scroll-end'),
  }));
  const [wideStartEdge, wideEndEdge] = await Promise.all([
    readEdgeState(startEdge),
    readEdgeState(endEdge),
  ]);
  if (noOverflowState.overflow !== null || noOverflowState.scrollStart !== null || noOverflowState.scrollEnd !== null
    || wideStartEdge.opacity > 0.01 || wideEndEdge.opacity > 0.01) {
    throw new Error(`无横向滚动时仍显示固定列阴影：${JSON.stringify({ noOverflowState, wideStartEdge, wideEndEdge })}`);
  }
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.waitForTimeout(100);

  const pageOverflow = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  if (pageOverflow.scrollWidth > pageOverflow.clientWidth + 1) {
    throw new Error(`宽表导致页面级横向溢出：${JSON.stringify(pageOverflow)}`);
  }
}

async function run() {
  await runSmoke({
    route: '/warehouse/inbound-bills',
    viewport: { width: 1280, height: 720 },
    screenshot: path.join(screenshotDirectory, 'shared-table-normal.png'),
    test: page => assertSharedTableBehavior(page, false),
  });

  await runSmoke({
    route: '/warehouse/inbound-bills',
    viewport: { width: 1280, height: 720 },
    reducedMotion: 'reduce',
    screenshot: path.join(screenshotDirectory, 'shared-table-reduced-motion.png'),
    test: page => assertSharedTableBehavior(page, true),
  });

  console.log('SMOKE_OK: 公共表格滚动提示、键盘焦点与减少动态效果通过');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
