const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const base = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const username = process.env.ERP_SMOKE_USER || 'admin';
const password = process.env.ERP_SMOKE_PASSWORD || '123456';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-25-ai-approval-cancel');
fs.mkdirSync(screenshotDir, { recursive: true });

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(`${base}/login`);
    await page.locator('#username').waitFor({ timeout: 15000 });
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForFunction(() => !location.pathname.includes('/login'), null, { timeout: 20000 })
      .catch(async error => {
        await page.screenshot({ path: path.join(screenshotDir, 'real-backend-login-failed.png') });
        console.error('登录未跳转', page.url(), (await page.locator('body').innerText()).slice(0, 800));
        throw error;
      });
    await page.goto(`${base}/ai/assistant`);
    await page.locator('.conversation-sidebar').waitFor({ timeout: 15000 });
    await page.locator('#graph-query').waitFor({ timeout: 15000 });
    await page.waitForFunction(() => document.querySelector('#graph-query')?.disabled === false, null, { timeout: 15000 })
      .catch(async error => {
        await page.screenshot({ path: path.join(screenshotDir, 'real-backend-overview-disabled.png') });
        console.error('输入框仍禁用', (await page.locator('body').innerText()).slice(0, 800));
        throw error;
      });
    await page.screenshot({ path: path.join(screenshotDir, 'real-backend-overview.png') });

    const firstConversation = page.locator('.conversation-main').first();
    if (await firstConversation.count()) {
      await firstConversation.click();
      await page.locator('.message-list').waitFor({ timeout: 20000 });
      await page.waitForTimeout(800);
      await page.screenshot({ path: path.join(screenshotDir, 'real-backend-history.png') });
    }
    assert.deepEqual(errors, [], `页面脚本错误: ${errors.join('; ')}`);
    console.log('real backend smoke OK');
  } finally {
    await page.close();
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });