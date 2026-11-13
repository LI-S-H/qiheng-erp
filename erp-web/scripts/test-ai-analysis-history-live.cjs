// 真实环境验证分析过程历史回看：重新打开会话后，面板从 ai_message 的 ANALYSIS_STEP 还原。
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:15174';

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
    const failures = [];
    page.on('pageerror', error => failures.push(error.message));
    await page.goto(`${BASE_URL}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 });
      await page.goto(`${BASE_URL}/ai/assistant`);
    }
    // 打开会话列表第一条（刚才真实分析的会话）。
    const first = page.locator('.conversation-sidebar .conversation-row').first();
    await first.waitFor({ timeout: 30000 });
    await first.click();
    // 面板默认收起：从工作过程点击“子智能体分析”再打开。
    const panel = page.getByTestId('ai-analysis-panel');
    const workToggle = page.getByRole('button', { name: '查看工作过程' }).first();
    await workToggle.waitFor({ timeout: 60000 });
    assert.ok(await page.getByTestId('ai-analysis-dock').evaluate(element => element.getBoundingClientRect().width) < 1, '历史分析面板默认不显示');
    await workToggle.click();
    await page.locator('.work-analysis').first().click();
    await page.waitForFunction(() => {
      const dock = document.querySelector('[data-testid="ai-analysis-dock"]');
      return Boolean(dock && dock.getBoundingClientRect().width > 200);
    }, null, { timeout: 5000 });
    await panel.waitFor({ timeout: 30000 });
    const goal = (await panel.locator('.analysis-goal').first().innerText()).trim();
    const steps = await panel.locator('.analysis-step').count();
    const tools = await panel.locator('.analysis-tool').count();
    const summary = (await panel.locator('.analysis-summary').first().innerText()).trim();
    if (!goal || steps < 1 || tools < 1 || !summary) throw new Error('历史回看面板内容不完整');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: `${process.env.AI_QA_SCREENSHOT_DIR}/analysis-live-history.png` });
    if (failures.length) throw new Error(`页面存在脚本错误：${failures.join('；')}`);
    console.log(JSON.stringify({ status: 'PASS', goal: goal.slice(0, 80), steps, tools, summary: summary.slice(0, 160) }, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });