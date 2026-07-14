const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5173';
const devServerUrl = new URL(baseUrl);
const devServerHost = devServerUrl.hostname || '127.0.0.1';
const devServerPort = devServerUrl.port || '5173';
const previewBaseUrl = process.env.SMOKE_PREVIEW_BASE_URL
  || `${devServerUrl.protocol}//${devServerHost}:4173`;

function createCleanEnv() {
  const clean = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (key.toLowerCase() === 'path' && Object.keys(clean).some(item => item.toLowerCase() === 'path')) continue;
    clean[key] = value;
  }
  return clean;
}

async function canReachServer(targetBaseUrl) {
  try {
    return (await fetch(`${targetBaseUrl}/login`)).ok;
  } catch {
    return false;
  }
}

async function ensureServer(serverMode, targetBaseUrl) {
  if (await canReachServer(targetBaseUrl)) return null;

  const targetUrl = new URL(targetBaseUrl);
  const targetHost = targetUrl.hostname || devServerHost;
  const targetPort = targetUrl.port || (serverMode === 'preview' ? '4173' : devServerPort);

  const viteArguments = [path.join(process.cwd(), 'node_modules/vite/bin/vite.js')];
  if (serverMode === 'preview') viteArguments.push('preview');
  viteArguments.push('--host', targetHost, '--port', targetPort, '--strictPort');

  const child = spawn(
    process.execPath,
    viteArguments,
    { cwd: process.cwd(), env: createCleanEnv(), stdio: ['ignore', 'pipe', 'pipe'] },
  );

  let output = '';
  child.stdout.on('data', chunk => { output += chunk.toString(); });
  child.stderr.on('data', chunk => { output += chunk.toString(); });

  const startedAt = Date.now();
  while (Date.now() - startedAt < 20000) {
    if (await canReachServer(targetBaseUrl)) return child;
    await new Promise(resolve => setTimeout(resolve, 350));
  }

  child.kill();
  throw new Error(`Vite 服务未能启动：\n${output}`);
}

async function stopServer(child, targetBaseUrl) {
  if (!child) return;
  const exited = new Promise(resolve => {
    if (child.exitCode !== null) {
      resolve();
      return;
    }
    child.once('exit', resolve);
  });
  child.kill();
  await Promise.race([
    exited,
    new Promise(resolve => setTimeout(resolve, 3000)),
  ]);

  const stoppedAt = Date.now();
  while (Date.now() - stoppedAt < 3000 && await canReachServer(targetBaseUrl)) {
    await new Promise(resolve => setTimeout(resolve, 100));
  }
}

async function loginIfNeeded(page, route, targetBaseUrl) {
  if (!page.url().includes('/login')) return;
  await page.getByLabel('登录账号').fill('admin');
  await page.getByLabel('登录密码').fill('123456');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL(url => url.pathname !== '/login', { timeout: 10000 });
  await page.goto(`${targetBaseUrl}${route}`, { waitUntil: 'domcontentloaded' });
}

async function runSmoke({
  route,
  screenshot,
  test,
  viewport = { width: 1440, height: 900 },
  reducedMotion = 'no-preference',
  autoLogin = true,
  serverMode = 'dev',
}) {
  const targetBaseUrl = serverMode === 'preview' ? previewBaseUrl : baseUrl;
  const devServer = await ensureServer(serverMode, targetBaseUrl);
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport, reducedMotion });
  const errors = [];

  page.on('console', message => {
    if (message.type() === 'error') errors.push(`[console:error] ${message.text()}`);
  });
  page.on('pageerror', error => errors.push(`[pageerror] ${error.message}`));

  try {
    await page.goto(`${targetBaseUrl}${route}`, { waitUntil: 'domcontentloaded' });
    if (autoLogin) await loginIfNeeded(page, route, targetBaseUrl);
    await test(page);
    if (errors.length > 0) throw new Error(errors.join('\n'));
    await page.screenshot({ path: screenshot, fullPage: true });
  } catch (error) {
    await page.screenshot({ path: screenshot.replace('.png', '-failed.png'), fullPage: true }).catch(() => undefined);
    throw error;
  } finally {
    await browser.close();
    await stopServer(devServer, targetBaseUrl);
  }
}

function tableRow(page, text) {
  return page.getByRole('row').filter({ hasText: text }).first();
}

async function assertFixedTableLayout(page, expectedColumns) {
  const table = page.locator('[data-slot="table"]').first();
  const state = await table.evaluate(element => ({
    tableLayout: getComputedStyle(element).tableLayout,
    columnCount: element.querySelector(':scope > colgroup')?.querySelectorAll('col').length ?? 0,
  }));
  if (state.tableLayout !== 'fixed' || state.columnCount !== expectedColumns) {
    throw new Error(`表格列宽未固定：${JSON.stringify(state)}`);
  }
}

async function assertRequiredLabels(dialog, labels) {
  for (const label of labels) {
    const text = await dialog.locator('label').filter({ hasText: label }).first().innerText();
    if (!text.includes('*')) throw new Error(`必填字段未标记星号：${label}`);
  }
}

async function assertDialogScrollGutter(dialog, minimumGap = 8, requireScrollbar = false) {
  const scrollArea = dialog.locator('[data-dialog-scroll-area]').first();
  await scrollArea.waitFor();
  await scrollArea.hover();
  await dialog.page().waitForTimeout(120);
  const state = await scrollArea.evaluate((element) => {
    const content = element.querySelector('[data-slot="dialog-scroll-content"]');
    const scrollbar = element.querySelector('[data-slot="scroll-area-scrollbar"]');
    if (!content) return null;

    const contentRect = content.getBoundingClientRect();
    const scrollbarRect = scrollbar?.getBoundingClientRect();
    const paddingRight = Number.parseFloat(getComputedStyle(content).paddingRight) || 0;
    const scrollbarLeft = scrollbarRect && scrollbarRect.width > 0
      ? scrollbarRect.left
      : element.getBoundingClientRect().right;
    return {
      gap: scrollbarLeft - (contentRect.right - paddingRight),
      paddingRight,
      scrollbarWidth: scrollbarRect?.width || 0,
    };
  });
  if (!state || state.gap < minimumGap) {
    throw new Error(`长弹窗内容与滚动条间距不足：${JSON.stringify(state)}`);
  }
  if (requireScrollbar && state.scrollbarWidth <= 0) {
    throw new Error(`长弹窗在内容溢出时未显示滚动条：${JSON.stringify(state)}`);
  }
}

async function assertTreeCollapseStability(page, {
  parentText,
  childText,
  followingText,
  collapseButtonName,
  expandButtonName,
  screenshotPath,
}) {
  const parentRow = tableRow(page, parentText);
  const followingRow = tableRow(page, followingText);
  const table = page.locator('[data-slot="table"]').first();
  const tableContainer = page.locator('[data-slot="table-container"]').first();
  const main = page.locator('main').first();
  const anchorCell = page.locator('[data-slot="table-header"] [data-slot="table-head"]').first();

  await tableContainer.evaluate((element) => {
    // 保留少量横向滚动，同时让树展开按钮仍在可视区；避免自动化工具
    // 为点击不可见元素主动滚回左侧，干扰对业务动画的测量。
    element.scrollLeft = Math.min(24, Math.max(0, element.scrollWidth - element.clientWidth));
  });

  async function snapshot(time) {
    const [mainState, containerState, tableState, anchorX, followingRowState, collapsingRows] = await Promise.all([
      main.evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      tableContainer.evaluate(element => ({
        clientWidth: element.clientWidth,
        scrollWidth: element.scrollWidth,
        scrollLeft: element.scrollLeft,
      })),
      table.evaluate(element => ({
        x: element.getBoundingClientRect().x,
        width: element.getBoundingClientRect().width,
      })),
      anchorCell.evaluate(element => element.getBoundingClientRect().x),
      followingRow.evaluate(element => ({
        top: element.getBoundingClientRect().top,
        x: element.getBoundingClientRect().x,
      })),
      page.locator('tr[data-tree-row-collapsing="true"]').evaluateAll(rows => rows.map((row) => {
        const rowStyle = getComputedStyle(row);
        const cells = [...row.children];
        const reveals = cells.map(cell => cell.querySelector(':scope > .tree-table-cell-reveal')).filter(Boolean);
        return {
          height: row.getBoundingClientRect().height,
          visibility: rowStyle.visibility,
          borderBottomWidth: rowStyle.borderBottomWidth,
          lineHeights: cells.map(cell => getComputedStyle(cell).lineHeight),
          revealHeights: reveals.map(reveal => reveal.getBoundingClientRect().height),
        };
      })),
    ]);
    return { time, mainState, containerState, tableState, anchorX, followingRowState, collapsingRows };
  }

  function assertHorizontalState(baseline, sample, stage) {
    const checks = [
      ['main.clientWidth', baseline.mainState.clientWidth, sample.mainState.clientWidth],
      ['main.scrollWidth', baseline.mainState.scrollWidth, sample.mainState.scrollWidth],
      ['main.scrollLeft', baseline.mainState.scrollLeft, sample.mainState.scrollLeft],
      ['tableContainer.clientWidth', baseline.containerState.clientWidth, sample.containerState.clientWidth],
      ['tableContainer.scrollWidth', baseline.containerState.scrollWidth, sample.containerState.scrollWidth],
      ['tableContainer.scrollLeft', baseline.containerState.scrollLeft, sample.containerState.scrollLeft],
      ['table.x', baseline.tableState.x, sample.tableState.x],
      ['table.width', baseline.tableState.width, sample.tableState.width],
      ['anchorCell.x', baseline.anchorX, sample.anchorX],
      ['followingRow.x', baseline.followingRowState.x, sample.followingRowState.x],
    ];
    const changed = checks.find(([, expected, actual]) => Math.abs(expected - actual) > 0.75);
    if (changed) {
      throw new Error(`树表收起${stage}发生横向位移：${changed[0]} ${changed[1]} -> ${changed[2]}`);
    }
  }

  const baseline = await snapshot(-1);
  await parentRow.getByRole('button', { name: collapseButtonName }).click();
  const startedAt = Date.now();
  const samples = [];
  for (const targetTime of [0, 24, 64, 120, 190, 250, 310, 380, 460]) {
    const waitTime = targetTime - (Date.now() - startedAt);
    if (waitTime > 0) await page.waitForTimeout(waitTime);
    samples.push(await snapshot(targetTime));
    if (screenshotPath && targetTime === 120) {
      await page.screenshot({ path: screenshotPath, fullPage: true });
    }
  }

  for (const sample of samples) {
    assertHorizontalState(baseline, sample, `第 ${sample.time}ms`);
    if (sample.collapsingRows.some(row => row.visibility === 'collapse')) {
      throw new Error(`树表收起第 ${sample.time}ms 使用了 visibility: collapse，可能触发表格轨道重算`);
    }
  }

  if (!samples.some(sample => sample.collapsingRows.length > 0)) {
    throw new Error('树表收起采样期间未捕获到折叠行，测试证据无效');
  }

  const animatedHeights = samples
    .map(sample => ({ time: sample.time, height: sample.collapsingRows.reduce((sum, row) => sum + row.height, 0) }))
    .filter(sample => sample.height > 0.5);
  for (let index = 1; index < animatedHeights.length; index += 1) {
    if (animatedHeights[index].height > animatedHeights[index - 1].height + 0.75) {
      throw new Error(`树表收起高度不连续：${JSON.stringify(animatedHeights)}`);
    }
  }

  const followingTops = [baseline, ...samples].map(sample => ({
    time: sample.time,
    top: sample.followingRowState.top,
  }));
  for (let index = 1; index < followingTops.length; index += 1) {
    if (followingTops[index].top > followingTops[index - 1].top + 0.75) {
      throw new Error(`树表相邻行位置出现反向回跳：${JSON.stringify(followingTops)}`);
    }
  }

  const stableBeforeRemoval = samples.find(sample => sample.collapsingRows.length > 0 && sample.collapsingRows.every(row => (
    row.height <= 0.75
    && row.borderBottomWidth === '0px'
    && row.lineHeights.every(lineHeight => lineHeight === '0px')
    && row.revealHeights.every(height => height <= 0.75)
  )));
  const removedAfterAnimation = samples.find(sample => sample.time >= 250 && sample.collapsingRows.length === 0);
  if (!stableBeforeRemoval && !removedAfterAnimation) {
    throw new Error('树表收起既未采到移除前稳定终态，也未在动画窗口后按预期移除');
  }
  const afterRemoval = samples.filter(sample => sample.time >= 380);
  if (afterRemoval.some(sample => sample.collapsingRows.length > 0)) {
    throw new Error('树表折叠行在移除稳定窗口仍残留 DOM');
  }
  if (Math.abs(afterRemoval[0].followingRowState.top - afterRemoval.at(-1).followingRowState.top) > 0.75) {
    throw new Error('树表折叠行移除后相邻行位置仍未稳定');
  }
  await tableRow(page, childText).waitFor({ state: 'detached', timeout: 1000 });

  await parentRow.getByRole('button', { name: expandButtonName }).click();
  await tableRow(page, childText).waitFor({ state: 'visible' });
  await page.waitForTimeout(280);

  const reverseBaseline = await snapshot(-1);
  await parentRow.getByRole('button', { name: collapseButtonName }).click();
  await page.waitForTimeout(80);
  await parentRow.getByRole('button', { name: expandButtonName }).click();
  await page.waitForTimeout(300);
  const reverseSample = await snapshot(300);
  assertHorizontalState(reverseBaseline, reverseSample, '快速反向展开后');
  if (reverseSample.collapsingRows.length > 0) {
    throw new Error('快速反向展开后仍残留折叠状态');
  }
  const reversedChildHeight = await tableRow(page, childText).evaluate(element => element.getBoundingClientRect().height);
  if (reversedChildHeight < 20) {
    throw new Error(`快速反向展开后子行未恢复可见高度：${reversedChildHeight}`);
  }

  await page.emulateMedia({ reducedMotion: 'reduce' });
  const reducedBaseline = await snapshot(-1);
  await parentRow.getByRole('button', { name: collapseButtonName }).click();
  await page.waitForTimeout(32);
  const reducedSample = await snapshot(32);
  assertHorizontalState(reducedBaseline, reducedSample, '减弱动效第 32ms');
  const reducedHeight = reducedSample.collapsingRows.reduce((sum, row) => sum + row.height, 0);
  if (reducedHeight > 0.75) {
    throw new Error(`减弱动效下树表未快速稳定：折叠行总高度 ${reducedHeight}`);
  }
  if (reducedSample.collapsingRows.some(row => row.visibility === 'collapse')) {
    throw new Error('减弱动效下仍使用了 visibility: collapse');
  }
  await tableRow(page, childText).waitFor({ state: 'detached', timeout: 1000 });
  await page.emulateMedia({ reducedMotion: 'no-preference' });
}

async function clickQueryAndAssertLoading(page, screenshotPath) {
  await page.getByRole('button', { name: '查询', exact: true }).click();
  const overlay = page.locator('[data-list-loading]');
  await overlay.waitFor({ state: 'visible', timeout: 1000 });
  const busyButton = page.getByRole('button', { name: '查询中', exact: true });
  if (!(await busyButton.isDisabled())) throw new Error('查询进行中按钮未禁用');
  const state = await overlay.evaluate((element) => {
    const spinner = element.querySelector('.page-loading-spinner');
    return {
      position: getComputedStyle(element).position,
      pointerEvents: getComputedStyle(element).pointerEvents,
      spinnerAnimation: spinner ? getComputedStyle(spinner).animationName : '',
    };
  });
  if (state.position !== 'absolute' || state.pointerEvents !== 'auto' || state.spinnerAnimation !== 'page-loading-spin') {
    throw new Error(`查询加载反馈样式异常：${JSON.stringify(state)}`);
  }
  if (screenshotPath) await page.screenshot({ path: screenshotPath, fullPage: true });
  await overlay.waitFor({ state: 'hidden', timeout: 5000 });
}

async function clickPaginationAndAssertLoading(page, label) {
  const pagination = page.locator('[data-table-pagination]');
  await pagination.getByText(label, { exact: true }).click();
  const overlay = page.locator('[data-list-loading]');
  await overlay.waitFor({ state: 'visible', timeout: 1000 });
  if ((await pagination.getAttribute('aria-busy')) !== 'true') {
    throw new Error(`分页切换期间未进入忙碌状态：${label}`);
  }
  const pointerEvents = await pagination.evaluate(element => getComputedStyle(element).pointerEvents);
  if (pointerEvents !== 'none') throw new Error(`分页切换期间未阻止重复点击：${label}`);
  await overlay.waitFor({ state: 'hidden', timeout: 5000 });
}

async function clickRefreshAndAssertLoading(page, screenshotPath) {
  const refreshButton = page.getByRole('button', { name: '刷新', exact: true });
  await refreshButton.click();
  const overlay = page.locator('[data-list-loading]');
  await overlay.waitFor({ state: 'visible', timeout: 1000 });
  if (!(await refreshButton.isDisabled())) throw new Error('刷新进行中按钮未禁用');
  if (screenshotPath) await page.screenshot({ path: screenshotPath, fullPage: true });
  await overlay.waitFor({ state: 'hidden', timeout: 5000 });
}

async function clickResetAndAssertLoading(page, screenshotPath) {
  const resetButton = page.getByRole('button', { name: '重置', exact: true });
  await resetButton.click();
  const overlay = page.locator('[data-list-loading]');
  await overlay.waitFor({ state: 'visible', timeout: 1000 });
  if (!(await resetButton.isDisabled())) throw new Error('重置查询进行中按钮未禁用');
  if (screenshotPath) await page.screenshot({ path: screenshotPath, fullPage: true });
  await overlay.waitFor({ state: 'hidden', timeout: 5000 });
}

async function assertPolishedFilterPanel(page) {
  const panel = page.locator('[data-list-filter-panel], .filter-panel--polished').first();
  await panel.waitFor();
  const state = await panel.evaluate(element => {
    const style = getComputedStyle(element);
    const accent = getComputedStyle(element, '::before');
    const label = element.querySelector('[data-slot="label"]');
    const control = element.querySelector('[data-slot="input"], [role="combobox"]');
    const labelStyle = label ? getComputedStyle(label) : null;
    const controlStyle = control ? getComputedStyle(control) : null;
    return {
      backgroundImage: style.backgroundImage,
      backgroundColor: style.backgroundColor,
      borderColor: style.borderColor,
      borderRadius: Number.parseFloat(style.borderRadius),
      borderTopWidth: Number.parseFloat(style.borderTopWidth),
      boxShadow: style.boxShadow,
      paddingTop: Number.parseFloat(style.paddingTop),
      accentHeight: Number.parseFloat(accent.height),
      accentBackground: accent.backgroundImage,
      accentColor: accent.backgroundColor,
      labelFontSize: labelStyle ? Number.parseFloat(labelStyle.fontSize) : 0,
      labelWeight: labelStyle ? Number.parseInt(labelStyle.fontWeight, 10) : 0,
      controlHeight: controlStyle ? Number.parseFloat(controlStyle.height) : 0,
    };
  });
  const hasPanelBackground = state.backgroundImage !== 'none' || state.backgroundColor !== 'rgba(0, 0, 0, 0)';
  const hasAccent = state.accentBackground !== 'none' || state.accentColor !== 'rgba(0, 0, 0, 0)';
  if (!hasPanelBackground || state.boxShadow === 'none' || state.paddingTop !== 16
    || state.borderTopWidth !== 1 || state.borderRadius !== 10 || hasAccent
    || state.labelFontSize < 13 || state.labelWeight < 600 || state.controlHeight < 36) {
    throw new Error(`筛选区视觉层级或控件尺寸异常：${JSON.stringify(state)}`);
  }

  const actionTexts = await panel.locator('.filter-actions button').allTextContents();
  if (!actionTexts[0]?.includes('查询') || !actionTexts[1]?.includes('重置')) {
    throw new Error(`筛选操作主次顺序异常：${JSON.stringify(actionTexts)}`);
  }
  const firstControl = panel.locator('[data-slot="input"], [role="combobox"]').first();
  await firstControl.focus();
  const focusShadow = await firstControl.evaluate(element => getComputedStyle(element).boxShadow);
  if (!focusShadow || focusShadow === 'none') throw new Error('筛选控件缺少清晰焦点反馈');
}

async function assertContentSizedFilter(page, expectedWidths) {
  const filter = page.locator('[data-list-filter-panel]');
  const metrics = await filter.evaluate((element) => {
    const fields = [...element.querySelectorAll('[data-filter-size]')];
    const actions = element.querySelector('.filter-actions');
    return {
      layout: element.querySelector('[data-filter-layout]')?.getAttribute('data-filter-layout'),
      widths: fields.map(field => Math.round(field.getBoundingClientRect().width)),
      panelOverflow: element.scrollWidth - element.clientWidth,
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      actionsOverflow: actions
        ? actions.getBoundingClientRect().right - element.getBoundingClientRect().right
        : null,
    };
  });
  if (metrics.layout !== 'content' || metrics.widths.join(',') !== expectedWidths.join(',')
    || metrics.panelOverflow > 1 || metrics.pageOverflow > 1
    || metrics.actionsOverflow === null || metrics.actionsOverflow > 1) {
    throw new Error(`内容适配筛选布局异常：${JSON.stringify(metrics)}`);
  }
}

async function assertSharedListChrome(page, { summaryLabel, filterLabel }) {
  const summary = page.getByRole('region', { name: summaryLabel });
  const filter = page.getByRole('search', { name: filterLabel });
  await Promise.all([summary.waitFor(), filter.waitFor()]);
  const summaryState = await summary.evaluate(element => ({
    metricCount: element.querySelectorAll('.summary-item').length,
    definitionCount: element.querySelectorAll('dt').length,
    valueCount: element.querySelectorAll('dd').length,
    shadow: getComputedStyle(element).boxShadow,
  }));
  if (summaryState.metricCount !== 4 || summaryState.definitionCount !== 4
    || summaryState.valueCount !== 4 || summaryState.shadow === 'none') {
    throw new Error(`列表页汇总组件结构或层级异常：${JSON.stringify(summaryState)}`);
  }
  if (await filter.getAttribute('data-list-filter-panel') === null) {
    throw new Error('列表页筛选区未使用共享组件');
  }
  const actions = filter.locator('.list-filter-panel__actions');
  if (await actions.count() !== 1) {
    throw new Error('列表页筛选操作区未使用共享组件的 actions 插槽');
  }
  const actionLabels = (await actions.getByRole('button').allTextContents()).map(label => label.trim());
  if (actionLabels.length !== 2 || !['查询', '查询中'].includes(actionLabels[0]) || actionLabels[1] !== '重置') {
    throw new Error(`列表页筛选操作按钮顺序异常：${JSON.stringify(actionLabels)}`);
  }
  await assertPolishedFilterPanel(page);
}

module.exports = {
  baseUrl,
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  assertDialogScrollGutter,
  assertTreeCollapseStability,
  clickQueryAndAssertLoading,
  clickPaginationAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
  assertPolishedFilterPanel,
  assertContentSizedFilter,
  assertSharedListChrome,
};
