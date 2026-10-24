const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('playwright/test');

// 只读真实历史会话，用真实帧时钟验证布局过渡，不发送模型请求或创建业务数据。
async function main() {
  const base = process.env.AI_GRAPH_UI_URL || 'http://127.0.0.1:5173';
  const out = path.resolve(__dirname, '../../docs/qa-screenshots/2026-10-16-ai-panel-motion');
  fs.mkdirSync(out, { recursive: true });
  const login = await (await fetch(base + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.SMOKE_USERNAME || 'admin', password: process.env.SMOKE_PASSWORD || '123456' }),
  })).json();
  assert.equal(login.code, 0);
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'no-preference' });
    await context.addInitScript(({ token, tokenName }) => {
      localStorage.setItem('erp_auth_token', token);
      localStorage.setItem('erp_auth_token_name', tokenName);
    }, login.data);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/ai/assistant');
    await expect(page.locator('.plan-artifact').first()).toBeVisible();
    assert.ok(await page.locator('.plan-artifact').first().evaluate(el => el.getBoundingClientRect().width) <= 360);
    await page.locator('.plan-artifact').first().click();
    await page.waitForTimeout(300);
    const samples = [];
    for (const [selector, close, open, expanded] of [
      ['.graph-workspace > .conversation-sidebar', '收起会话列表', '展开会话列表', 224],
      ['.desktop-plan', '关闭任务计划', '展开任务计划', 352],
    ]) {
      for (const [label, start, end] of [[close, expanded, 52], [open, 52, expanded]]) {
        const widths = await page.evaluate(async ({ selector, label }) => {
          const panel = document.querySelector(selector);
          const result = [panel.getBoundingClientRect().width];
          document.querySelector(`button[aria-label="${label}"]`).click();
          const began = performance.now();
          while (performance.now() - began < 350) {
            await new Promise(requestAnimationFrame);
            result.push(panel.getBoundingClientRect().width);
          }
          return result;
        }, { selector, label });
        assert.ok(Math.abs(widths[0] - start) < 1);
        assert.ok(Math.abs(widths.at(-1) - end) < 1);
        assert.ok(widths.filter(width => width > 54 && width < expanded - 2).length >= 2, label + '应有中间过渡帧');
        assert.equal(await page.locator(selector).evaluate(el => el.scrollLeft), 0, '聚焦不能横向滚动栏内容');
        samples.push({ label, widths });
      }
      // 快速反转不应遗留半展开状态。
      await page.getByRole('button', { name: close, exact: true }).click();
      await page.waitForTimeout(50);
      await page.getByRole('button', { name: open, exact: true }).click();
      await page.waitForTimeout(300);
      assert.ok(Math.abs(await page.locator(selector).evaluate(el => el.getBoundingClientRect().width) - expanded) < 1);
      assert.equal(await page.locator(selector).evaluate(el => el.scrollLeft), 0);
    }
    const handle = page.getByRole('separator', { name: '调整会话栏宽度' });
    const box = await handle.boundingBox();
    await page.mouse.move(box.x, box.y + 200);
    await page.mouse.down();
    await page.mouse.move(box.x + 48, box.y + 200);
    const drag = await page.locator('.graph-workspace > .conversation-sidebar').evaluate(el => ({
      width: el.getBoundingClientRect().width, duration: getComputedStyle(el).transitionDuration,
    }));
    assert.equal(drag.duration, '0s');
    assert.ok(Math.abs(drag.width - 272) < 1);
    await page.mouse.up();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.getByRole('button', { name: '收起会话列表', exact: true }).click();
    assert.equal(await page.locator('.graph-workspace > .conversation-sidebar').evaluate(el => el.getBoundingClientRect().width), 52);
    await page.getByRole('button', { name: '展开会话列表', exact: true }).click();
    await page.getByRole('button', { name: '关闭任务计划', exact: true }).click();
    const alignment = await page.evaluate(() => {
      const rect = selector => document.querySelector(selector).getBoundingClientRect();
      return { assistant: rect('.message:not(.is-user) .message-avatar').left - rect('.ai-composer').left,
        user: rect('.message.is-user .message-avatar').right - rect('.ai-composer').right };
    });
    assert.ok(Math.abs(alignment.assistant) <= 7 && Math.abs(alignment.user) <= 7);
    await expect(page.locator('[data-page-loading]')).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);
    await page.locator('.plan-artifact').first().scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(out, '01-compact-card-aligned-1440.png'), fullPage: true });
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ status: 'PASSED', samples, drag, alignment, errors }, null, 2));
    console.log(JSON.stringify({ status: 'AI_PANEL_MOTION_PASSED', transitions: samples.length, drag, alignment, errors }));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
