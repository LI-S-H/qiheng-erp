// 真实环境验证分析子智能体过程展示：真实模型、真实数据库、真实沙箱。
// 校验分析事件随主智能体流下发、面板渲染目标/步骤/结论，并保留现场截图。
const { chromium } = require('playwright');

const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:15173';
const SHOT_DIR = process.env.AI_QA_SCREENSHOT_DIR || '';

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1600, height: 950 } });
    const failures = [];
    page.on('pageerror', error => failures.push(error.message));
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
    await page.goto(`${BASE_URL}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 30000 });
      await page.goto(`${BASE_URL}/ai/assistant`);
    }
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.getByLabel('经营问题').fill('请调用 analyzeData 工具分析当前所有仓库每种商品的库存总量，列出库存最多的前十个商品，并给出结论。');
    await page.getByRole('button', { name: '发送需求' }).click();

    // 面板默认收起：先等主对话出现“子智能体正在分析”，再点击展开。
    const entry = page.locator('.analysis-entry');
    await entry.getByText('子智能体正在分析', { exact: true }).waitFor({ timeout: 300000 });
    if (await page.getByTestId('ai-analysis-dock').evaluate(element => element.getBoundingClientRect().width) > 1) {
      throw new Error('分析面板默认不能自动展开');
    }
    await entry.click();
    await page.waitForTimeout(400);
    const panel = page.getByTestId('ai-analysis-panel');
    await panel.waitFor({ timeout: 30000 });
    await panel.locator('.analysis-goal').first().waitFor({ timeout: 120000 });
    const goal = (await panel.locator('.analysis-goal').first().innerText()).trim();
    if (!goal) throw new Error('面板没有显示主智能体交代的任务');
    if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/analysis-live-running.png` });

    // 等结论出现：一次真实分析通常需要数分钟。
    await panel.locator('.analysis-summary').first().waitFor({ timeout: 900000 });
    await page.waitForFunction(() => !document.querySelector('.agent-running-status'), null, { timeout: 120000 });
    if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/analysis-live-done.png` });

    const frames = (await page.evaluate(() => window.__aiFrames)).join('\n');
    const events = [...frames.matchAll(/event:\s*([^\r\n]+)/g)].map(item => item[1].trim());
    for (const name of ['analysis_started', 'analysis_step', 'analysis_summary']) {
      if (!events.includes(name)) throw new Error(`真实流缺少事件 ${name}`);
    }
    const steps = await panel.locator('.analysis-step').count();
    const tools = await panel.locator('.analysis-tool').count();
    if (steps < 1) throw new Error('面板没有显示分析步骤');
    if (tools < 1) throw new Error('面板没有显示工具调用');
    const summary = (await panel.locator('.analysis-summary').first().innerText()).trim();
    if (!summary) throw new Error('面板没有显示最终结论');
    if (failures.length) throw new Error(`页面存在脚本错误：${failures.join('；')}`);
    console.log(JSON.stringify({
      status: 'PASS',
      goal,
      steps,
      tools,
      summary: summary.slice(0, 400),
      events: [...new Set(events)],
    }, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });