// 诊断：打开指定会话，检查分析面板是否渲染了步骤文字。
const { chromium } = require('playwright');
const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:15174';
const ROW_TIME = process.env.PROBE_ROW_TIME || '19:49';

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
    await page.goto(`${BASE_URL}/login`);
    await page.locator('#username').fill('admin');
    await page.locator('#password').fill('123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 });
    await page.goto(`${BASE_URL}/ai/assistant`);
    const row = page.locator('.conversation-sidebar .conversation-row').filter({ hasText: ROW_TIME }).first();
    await row.waitFor({ timeout: 60000 });
    await row.click();
    const toggle = page.getByRole('button', { name: '查看工作过程' }).first();
    await toggle.waitFor({ timeout: 60000 });
    await toggle.click();
    await page.locator('.work-analysis').first().click();
    await page.waitForFunction(() => {
      const dock = document.querySelector('[data-testid="ai-analysis-dock"]');
      return Boolean(dock && dock.getBoundingClientRect().width > 200);
    }, null, { timeout: 5000 });
    const steps = await page.locator('.analysis-step').count();
    const texts = await page.locator('.analysis-step .ai-markdown-content').count();
    const sample = await page.locator('.analysis-step .ai-markdown-content').first().innerText().catch(() => '');
    if (process.env.AI_QA_SCREENSHOT_DIR) {
      await page.screenshot({ path: `${process.env.AI_QA_SCREENSHOT_DIR}/analysis-steps-boxed.png` });
    }
    console.log(JSON.stringify({ rowTime: ROW_TIME, steps, textBlocks: texts, sample }, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });