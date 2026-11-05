// 真实开发环境验证新主智能体会话和追问；仅创建独立测试会话。
const { chromium } = require('playwright');

/** 对照实时片段和完整事件，验证同一段文本能得到最终校正。 */
function compareTextEvents(stream) {
  const partials = new Map();
  const checks = [];
  for (const frame of stream.split(/\r?\n\r?\n/)) {
    const event = /^event:\s*(.+)$/m.exec(frame)?.[1];
    const data = /^data:\s*(.+)$/m.exec(frame)?.[1];
    if (!data || (event !== 'text_delta' && event !== 'text_completed')) continue;
    const value = JSON.parse(data);
    if (event === 'text_delta') partials.set(value.messageId, (partials.get(value.messageId) || '') + value.delta);
    else checks.push({ streamed: partials.get(value.messageId) || '', completed: value.text });
  }
  if (!checks.length) throw new Error('真实流没有完整文本校正事件');
  return { completed: checks.length, corrected: checks.filter(item => item.streamed !== item.completed).length,
    lostTail: checks.filter(item => item.completed.startsWith(item.streamed) && item.streamed !== item.completed).length };
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const failures = [];
  await page.addInitScript(() => {
    window.__aiFrames = [];
    const original = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const response = await original(...args);
      if (String(args[0]).includes('/messages/stream')) {
        response.clone().text().then(body => window.__aiFrames.push(body)).catch(() => {});
      }
      return response;
    };
  });
  page.on('pageerror', error => failures.push(error.message));
  await page.goto('http://127.0.0.1:15173/ai/assistant');
  if (page.url().includes('/login')) {
    await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
    await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 20000 });
    await page.goto('http://127.0.0.1:15173/ai/assistant');
  }
  await page.getByRole('button', { name: '新建会话' }).click();
  const stream = page.waitForResponse(response => response.url().includes('/messages/stream') && response.request().method() === 'POST', { timeout: 30000 });
  await page.getByLabel('经营问题').fill('请先用追问工具问我需要查询哪个仓库，等我回答后再简单确认，不要查询数据。');
  await page.getByRole('button', { name: '发送需求' }).click();
  await page.locator('[data-testid="clarification-form"]').waitFor({ timeout: 120000 });
  const form = page.locator('[data-testid="clarification-form"]');
  while (!(await form.getByRole('button', { name: '提交回答' }).isVisible())) {
    const input = form.locator('textarea:visible').first();
    if (await input.count()) {
      await input.fill('一号仓库');
    } else {
      const option = form.locator('[data-testid^="question-"][data-testid*="-option-"]:visible').first();
      const singleChoice = await option.getAttribute('role') === 'radio';
      await option.click();
      // 单选会自动进入下一步，多选需要用户主动确认。
      if (singleChoice) {
        await page.waitForTimeout(600);
        continue;
      }
    }
    await form.getByRole('button', { name: /确认回答|下一题/ }).click();
  }
  await form.getByRole('button', { name: '提交回答' }).click();
  await page.waitForFunction(() => !document.querySelector('[data-testid="clarification-form"]'), null, { timeout: 30000 });
  await page.waitForFunction(() => !document.querySelector('.agent-running-status'), null, { timeout: 120000 });
  const frames = await (await stream).text();
  for (const name of ['run_started', 'question', 'text_delta', 'run_completed']) {
    if (!frames.includes(`event:${name}`) && !frames.includes(`event: ${name}`)) throw new Error(`缺少事件 ${name}`);
  }
  await page.evaluate(() => {
    window.__aiStatuses = [];
    new MutationObserver(() => {
      const status = document.querySelector('.agent-running-status')?.textContent?.trim();
      if (status && window.__aiStatuses.at(-1) !== status) window.__aiStatuses.push(status);
    }).observe(document.body, { subtree: true, childList: true, characterData: true });
  });
  const beforeSql = await page.evaluate(() => window.__aiFrames.length);
  await page.getByLabel('经营问题').fill('请查询系统所有仓库，列出前五个仓库的编号和名称即可，不需要查询库存。');
  await page.getByRole('button', { name: '发送需求' }).click();
  await page.waitForFunction(() => window.__aiStatuses?.includes('正在查询业务数据'), null, { timeout: 120000 });
  await page.waitForFunction(count => window.__aiFrames.length > count, beforeSql, { timeout: 180000 });
  const sqlFrames = await page.evaluate(() => window.__aiFrames.at(-1));
  for (const name of ['tool_started', 'tool_finished', 'run_completed']) {
    if (!new RegExp(`event:\\s*${name}`).test(sqlFrames)) throw new Error(`真实查询缺少事件 ${name}`);
  }
  await page.waitForFunction(() => !document.querySelector('.agent-running-status'), null, { timeout: 30000 });
  const statuses = await page.evaluate(() => window.__aiStatuses);
  if (!statuses.includes('正在查询业务数据')) throw new Error('页面未展示查询状态');
  console.log(JSON.stringify({ status: 'PASS', body: (await page.locator('main.conversation-panel').innerText()).slice(-2500), events: [...frames.matchAll(/event:\s*([^\r\n]+)/g)].map(item => item[1]), sqlEvents: [...sqlFrames.matchAll(/event:\s*([^\r\n]+)/g)].map(item => item[1]), textComparison: { question: compareTextEvents(frames), sql: compareTextEvents(sqlFrames) }, statuses, failures }));
  if (failures.length) throw new Error('页面存在脚本错误');
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
