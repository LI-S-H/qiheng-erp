// 真实环境验证工具展示行：历史里 TOOL_CALL 是结构化数据，页面按 label 与耗时渲染。
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:15173';
const SHOT_DIR = process.env.AI_QA_SCREENSHOT_DIR || '';

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
    const failures = [];
    let conversationId = '';
    page.on('pageerror', error => failures.push(error.message));
    // 会话编号出现在运行流请求地址里，页面路由本身不带编号。
    page.on('request', request => {
      const matched = /\/conversations\/(\d+)\/messages\/stream/.exec(request.url());
      if (matched) conversationId = matched[1];
    });
    await page.goto(`${BASE_URL}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 });
      await page.goto(`${BASE_URL}/ai/assistant`);
    }
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.getByLabel('经营问题').fill('不要追问，直接查询：用一句 SQL 统计全部仓库库存数量最多的前 5 个商品，给出商品名称、单位和数量。');
    await page.getByRole('button', { name: '发送需求' }).click();
    // 等本轮结束：运行状态消失即代表图已跑完。
    await page.waitForFunction(() => !document.querySelector('.agent-running-status'), null, { timeout: 600000 });

    // 从历史接口确认写入的是结构化工具行，而不是旧的文本摘要。
    assert.ok(conversationId, '未能从运行流请求中取得会话编号');
    const history = await page.evaluate(async (id) => {
      const response = await fetch(`/api/ai/assistant/conversations/${id}/messages?pageNum=1&pageSize=50`);
      return response.json();
    }, conversationId);
    const records = history?.data?.records || [];
    const toolRows = records.filter(item => item.kind === 'TOOL_CALL' && !item.content?.data?.arguments);
    assert.ok(toolRows.length >= 1, '历史里应存在结构化的工具展示行');
    const row = toolRows.at(-1);
    assert.ok(row.content.data.name, '工具行应带工具名');
    assert.ok(row.content.data.label, '工具行应带展示文案');
    // 项目统一把 long 序列化为字符串，避免前端精度丢失，两种形态都接受。
    const durationMs = Number(row.content.data.durationMs);
    assert.ok(Number.isFinite(durationMs) && durationMs >= 0, '工具行应带可解析的耗时');
    assert.ok(['done', 'failed'].includes(row.content.data.status), '工具行应带状态');

    // 页面展开工作过程后，工具行显示文案与耗时。
    const toggle = page.getByRole('button', { name: '查看工作过程' }).first();
    await toggle.waitFor({ timeout: 60000 });
    await toggle.click();
    const tool = page.locator('.work-tool').filter({ hasText: row.content.data.label }).first();
    await tool.waitFor({ timeout: 30000 });
    const text = (await tool.innerText()).replace(/\s+/g, ' ').trim();
    const seconds = (durationMs / 1000).toFixed(1);
    assert.ok(text.includes(seconds), `工具行应显示耗时 ${seconds} 秒，实际：${text}`);
    assert.ok(await tool.locator('svg').count() > 0, '工具行应显示图标');
    if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/tool-step-live.png` });

    if (failures.length) throw new Error(`页面存在脚本错误：${failures.join('；')}`);
    console.log(JSON.stringify({
      status: 'PASS',
      toolName: row.content.data.name,
      label: row.content.data.label,
      durationMs,
      statusText: text,
    }, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });