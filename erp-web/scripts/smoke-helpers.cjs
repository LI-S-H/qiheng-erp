const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5173';
const devServerUrl = new URL(baseUrl);
const devServerHost = devServerUrl.hostname || '127.0.0.1';
const devServerPort = devServerUrl.port || '5173';

function createCleanEnv() {
  const clean = {};
  for (const [key, value] of Object.entries(process.env)) {
    if (key.toLowerCase() === 'path' && Object.keys(clean).some(item => item.toLowerCase() === 'path')) continue;
    clean[key] = value;
  }
  return clean;
}

async function canReachServer() {
  try {
    return (await fetch(`${baseUrl}/login`)).ok;
  } catch {
    return false;
  }
}

async function ensureDevServer() {
  if (await canReachServer()) return null;

  const child = spawn(
    process.execPath,
    [path.join(process.cwd(), 'node_modules/vite/bin/vite.js'), '--host', devServerHost, '--port', devServerPort, '--strictPort'],
    { cwd: process.cwd(), env: createCleanEnv(), stdio: ['ignore', 'pipe', 'pipe'] },
  );

  let output = '';
  child.stdout.on('data', chunk => { output += chunk.toString(); });
  child.stderr.on('data', chunk => { output += chunk.toString(); });

  const startedAt = Date.now();
  while (Date.now() - startedAt < 20000) {
    if (await canReachServer()) return child;
    await new Promise(resolve => setTimeout(resolve, 350));
  }

  child.kill();
  throw new Error(`Vite 服务未能启动：\n${output}`);
}

async function loginIfNeeded(page, route) {
  if (!page.url().includes('/login')) return;
  await page.getByLabel('登录账号').fill('admin');
  await page.getByLabel('登录密码').fill('123456');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL(url => url.pathname !== '/login', { timeout: 10000 });
  await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
}

async function runSmoke({ route, screenshot, test, viewport = { width: 1440, height: 900 } }) {
  const devServer = await ensureDevServer();
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport });
  const errors = [];

  page.on('console', message => {
    if (message.type() === 'error') errors.push(`[console:error] ${message.text()}`);
  });
  page.on('pageerror', error => errors.push(`[pageerror] ${error.message}`));

  try {
    await page.goto(`${baseUrl}${route}`, { waitUntil: 'domcontentloaded' });
    await loginIfNeeded(page, route);
    await test(page);
    if (errors.length > 0) throw new Error(errors.join('\n'));
    await page.screenshot({ path: screenshot, fullPage: true });
  } catch (error) {
    await page.screenshot({ path: screenshot.replace('.png', '-failed.png'), fullPage: true }).catch(() => undefined);
    throw error;
  } finally {
    await browser.close();
    if (devServer) devServer.kill();
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

module.exports = {
  baseUrl,
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  assertDialogScrollGutter,
  clickQueryAndAssertLoading,
  clickPaginationAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
};
