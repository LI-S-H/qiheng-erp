const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');

const baseUrl = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5173';

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
    [path.join(process.cwd(), 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '5173', '--strictPort'],
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
  await page.waitForURL(/dashboard|system\//, { timeout: 10000 });
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
    columnCount: element.querySelectorAll('colgroup col').length,
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

module.exports = { baseUrl, runSmoke, tableRow, assertFixedTableLayout, assertRequiredLabels };
