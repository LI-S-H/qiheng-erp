const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('playwright/test');

// 不拦截接口：浏览器 → Vite → 真实 Java、MySQL、Redis；模型使用本地受控场景。
const base = process.env.AI_GRAPH_REAL_UI_URL || 'http://127.0.0.1:5174';
const screenshots = process.env.AI_GRAPH_SCREENSHOT_DIR || path.resolve(__dirname,
  '../../docs/qa-screenshots/2026-10-16-170012-ai-ledger-real-integration');

async function main() {
  fs.mkdirSync(screenshots, { recursive: true });
  const response = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'graph-test', password: 'graph-test' }),
  });
  const login = await response.json();
  assert.equal(login.code, 0);
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(({ token, tokenName }) => {
    localStorage.setItem('erp_auth_token', token);
    localStorage.setItem('erp_auth_token_name', tokenName);
  }, login.data);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const capture = async name => {
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshots, name), fullPage: true, animations: 'disabled' });
  };
  try {
    await page.goto(`${base}/ai/assistant`);
    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    const composer = page.getByRole('textbox', { name: '经营问题', exact: true });
    await composer.fill('分析库存情况，给出补货建议');
    await page.getByRole('button', { name: '发送需求', exact: true }).click();
    const period = page.getByRole('radio', { name: '最近30天', exact: true });
    await expect(period).toBeVisible();
    await period.check();
    await page.getByRole('textbox', { name: '需要分析哪些仓库或商品范围？', exact: true }).fill('一号仓库，重点关注办公纸品');
    await page.getByRole('textbox', { name: '其他补充', exact: true }).fill('优先考虑缺货风险');
    await capture('01-human-choice-and-input-1440.png');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('radio', { name: '最近30天', exact: true }).scrollIntoViewIfNeeded();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await capture('09-mobile-human-choice-390.png');
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.reload();
    await expect(page.getByRole('radio', { name: '最近30天', exact: true })).toBeChecked();
    await expect(page.getByRole('textbox', { name: '需要分析哪些仓库或商品范围？', exact: true })).toHaveValue('一号仓库，重点关注办公纸品');
    await capture('02-human-restored-from-mysql-1440.png');
    await page.getByRole('button', { name: '提交补充信息', exact: true }).click();
    const limit = page.getByRole('textbox', { name: '是否有需要优先考虑的补货目标或限制？', exact: true });
    await expect(limit).toBeVisible();
    await limit.fill('优先保障销量前20商品，补货预算5万元以内');
    await capture('03-second-human-round-1440.png');
    await page.getByRole('button', { name: '提交补充信息', exact: true }).click();
    await expect(page.getByTestId('ai-plan-panel')).toContainText('库存与补货分析计划');
    await expect(composer).toBeEnabled();
    await capture('04-plan-ready-1440.png');
    await page.reload();
    await page.getByRole('button', { name: '查看任务计划', exact: true }).click();
    await expect(page.getByTestId('ai-plan-panel')).toContainText('数据查询');
    await capture('05-plan-restored-from-mysql-1440.png');
    await page.setViewportSize({ width: 1024, height: 900 });
    await expect(page.getByRole('dialog')).toBeVisible();
    await capture('06-plan-drawer-1024.png');
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '查看任务计划', exact: true }).click();
    await capture('07-mobile-plan-390.png');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '打开会话列表', exact: true }).click();
    await capture('08-mobile-conversations-390.png');
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(JSON.stringify({ status: 'AI_GRAPH_REAL_BROWSER_OK', screenshots,
      checks: ['真实登录与SSE', '人工选择和补充', 'MySQL刷新与草稿恢复', '连续两轮Human',
        '最终计划刷新恢复', '1024与390响应布局', '无未捕获浏览器异常'] }, null, 2));
  } catch (error) {
    console.error({ url: page.url(), body: (await page.locator('body').innerText()).slice(0, 2400), errors });
    await capture('failure.png');
    throw error;
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
