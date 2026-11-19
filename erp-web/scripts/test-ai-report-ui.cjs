const assert = require('node:assert/strict');
const { mkdir } = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

const base = process.env.AI_UI_URL || 'http://127.0.0.1:5198';
const screenshots = process.env.AI_REPORT_SCREENSHOTS
  || path.resolve(__dirname, '../../erp-server/target/report-browser-qa');
const title = '库存损失分析报告';
const report = { reportId: 'report_qa', requestId: 'r', toolCallId: 'report-call', title, placeholder: `[[REPORT:${title}]]` };
const content = '# 正文误写的标题\n\n## 主要结论\n\n确认损失率 **2%**。 [查看计算口径](#计算口径)\n\n| 商品 | 损失 |\n| --- | --- |\n| A | 12 件 |\n\n<a id="计算口径"></a>\n\n<details><summary>口径说明</summary><p>以 600 件为分母。</p></details>';
const conversation = { conversationId: '123', title: '仓库库存测试', description: '', updatedAt: new Date().toISOString() };

/** 提供可逐条发送的事件和可重新加载的历史数据。 */
async function mountFixture(page, includeMarker) {
  let finished = false;
  let detailReads = 0;
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
    const fetchApi = window.fetch.bind(window);
    window.fetch = async (...args) => {
      if (!String(args[0]).includes('/messages/stream')) {
        return fetchApi(...args);
      }
      const encoder = new TextEncoder();
      const stream = new ReadableStream({ start(controller) {
        window.emitReport = (event, data) => controller.enqueue(
          encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
        );
        window.finishReport = () => controller.close();
        window.emitReport('run_started', { runId: 'r', conversationId: '123' });
        window.emitReport('tool_started', { runId: 'r', toolCallId: 'report-call', tool: 'createReport', label: '正在生成报告' });
      } });
      return new Response(stream, { headers: { 'content-type': 'text/event-stream' } });
    };
  });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const pathWithPrefix = new URL(request.url()).pathname;
    if (!pathWithPrefix.startsWith('/api/')) {
      return route.continue();
    }
    const pathname = pathWithPrefix.slice('/api'.length);
    let data = null;
    if (pathname === '/auth/me') {
      data = { userId: '1', username: 'qa', realName: '测试用户', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
    } else if (pathname.endsWith('/overview')) {
      data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [conversation] };
    } else if (pathname.endsWith('/conversations')) {
      data = request.method() === 'POST' ? conversation
        : { records: [conversation], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    } else if (pathname.endsWith('/runtime')) {
      data = { status: 'IDLE' };
    } else if (pathname.endsWith('/messages')) {
      const row = (messageId, seq, kind, role, text) => ({ messageId, requestId: 'r', seq, role, kind,
        createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text } } });
      const records = finished ? [row('1', '1', 'USER_INPUT', 'user', '分析仓库库存'),
        { messageId: '3', requestId: 'r', seq: '2', role: 'assistant', kind: 'REPORT_STEP',
          createdAt: conversation.updatedAt, content: { type: 'REPORT', data: { toolCallId: report.toolCallId,
            ...(includeMarker ? { taskId: 'f81d4fae-7dec-11d0-a765-00a0c91e6bf6' } : {}),
            status: 'COMPLETED', steps: [{ phase: 'WRITING', text: '根据已整理的资料编写报告。', stepSeq: 1 }], report } } },
        row('2', '3', 'ASSISTANT_FINAL', 'assistant',
          includeMarker ? `确认损失率 2%。\n\n${report.placeholder}\n\n以上为确认口径。` : '确认损失率 2%。')] : [];
      data = { records, pageNum: 1, pageSize: 50, total: records.length };
    } else if (pathname.endsWith('/reports')) {
      data = finished ? [report] : [];
    } else if (pathname.endsWith('/reports/report_qa')) {
      detailReads += 1;
      data = { ...report, content };
    } else if (pathname.endsWith('/123')) {
      data = conversation;
    }
    await route.fulfill({ json: { code: 0, message: '成功', data } });
  });
  return { finish() { finished = true; }, detailReads: () => detailReads };
}

/** 验证卡片与报告位置、页面跳转和历史恢复。 */
async function controlled(browser, includeMarker) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const fixture = await mountFixture(page, includeMarker);
    await page.goto(`${base}/ai/assistant`);
    assert.equal(new URL(page.url()).pathname, '/ai/assistant', '受控测试应通过登录路由检查');
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.getByLabel('经营问题').fill('分析仓库库存');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.emitReport === 'function');
    const emit = (event, data) => page.evaluate(([event, data]) => window.emitReport(event, data), [event, data]);
    for (const [index, phase, text] of [[1, 'PREPARING', '正在整理报告需求和资料。'],
      [2, 'WRITING', '根据已整理的资料编写报告。'],
      [3, 'WRITER_FORMAT_RETRY', '报告输出存在格式问题，正在修复。'],
      [4, 'REVIEWING', '报告编写完成，开始审阅报告。'],
      [5, 'REVIEW_FORMAT_RETRY', '审阅结果存在格式问题，正在修复。'],
      [6, 'REVISING', '审阅发现报告需要修改：关键数字的口径不清'],
      [7, 'WRITING', '正在根据审阅意见修改报告。'],
      [8, 'REVIEWING', '报告修改完成，开始重新审阅。']]) {
      await emit('report_progress', { runId: 'r', toolCallId: report.toolCallId, phase, text, stepSeq: index });
    }
    await page.getByRole('button', { name: /查看报告过程/ }).click();
    const panel = page.getByTestId('ai-report-process-panel');
    const steps = panel.locator('.report-timeline > li');
    await steps.nth(2).getByText('报告输出存在格式问题，正在修复。').waitFor();
    assert.equal(await steps.nth(2).getByText('编写报告').count(), 1);
    assert.equal(await steps.nth(4).getByText('审阅报告').count(), 1);
    assert.equal(await steps.nth(5).getByText('审阅报告').count(), 1);
    assert.equal(await steps.nth(6).getByText('编写报告').count(), 1);
    await steps.nth(7).getByText('报告修改完成，开始重新审阅。').waitFor();
    if (includeMarker) {
      await panel.screenshot({ path: path.join(screenshots, '01-报告生成过程.png') });
    }
    await page.getByRole('button', { name: '收起报告过程' }).click();
    await emit('report_ready', { runId: 'r', ...report });
    await emit('tool_finished', { runId: 'r', toolCallId: report.toolCallId, status: 'returned', label: '生成报告', durationMs: 3000 });
    const card = page.getByTestId('ai-report-card');
    await emit('text_delta', { messageId: '2', delta: '确认损失率 2%。' });
    if (includeMarker) {
      await emit('text_delta', { messageId: '2', delta: '\n\n[[REP' });
      assert.equal(await page.getByText('[[REP', { exact: false }).count(), 0);
      await emit('text_delta', { messageId: '2', delta: `ORT:${title}]]\n\n以上为确认口径。` });
      await card.waitFor();
      assert.equal(await card.isDisabled(), true);
      assert.equal((await card.boundingBox()).height, 64);
      await page.screenshot({ path: path.join(screenshots, '02-流式回复中的卡片.png') });
    } else {
      assert.equal(await card.count(), 0);
    }
    assert.equal(fixture.detailReads(), 0);
    fixture.finish();
    await emit('run_completed', { runId: 'r', status: 'COMPLETED', finalMessageId: '2' });
    await page.evaluate(() => window.finishReport());
    await page.waitForFunction(() => document.querySelector('[data-testid="ai-report-card"]:enabled'));
    assert.equal(await card.count(), 1);
    assert.equal((await card.boundingBox()).height, 64);
    if (includeMarker) {
      const paragraph = await page.getByText('以上为确认口径。').boundingBox();
      assert.ok((await card.boundingBox()).y < paragraph.y);
      await page.screenshot({ path: path.join(screenshots, '03-回复完成后的卡片.png') });
    }
    await card.click();
    await page.getByRole('heading', { name: title }).waitFor();
    assert.equal(await page.getByRole('heading', { level: 1 }).count(), 1);
    await page.getByRole('table').getByText('12 件').waitFor();
    assert.equal(fixture.detailReads(), 1);
    assert.equal(await page.getByText('以 600 件为分母。').isVisible(), false);
    assert.equal(await page.locator('.app-shell.ai-pilot-theme').count(), 1);
    await page.getByRole('link', { name: '查看计算口径' }).click();
    assert.equal(decodeURIComponent(new URL(page.url()).hash), '#ai-report-section-计算口径');
    assert.equal(await page.locator('[id="ai-report-section-计算口径"]').count(), 1);
    await page.getByText('口径说明').click();
    await page.getByText('以 600 件为分母。').waitFor();
    if (includeMarker) {
      await page.screenshot({ path: path.join(screenshots, '04-报告详情.png') });
    }
    await page.getByRole('link', { name: '返回会话' }).click();
    await card.waitFor();
    await page.reload();
    await card.waitFor();
    assert.equal(await card.count(), 1);
    await page.getByRole('button', { name: '查看工作过程' }).click();
    await page.getByRole('button', { name: '查看报告编写与讨论过程' }).click();
    await page.getByTestId('ai-report-process-panel').getByText('根据已整理的资料编写报告。').waitFor();
    assert.equal(await page.getByTestId('ai-report-process-panel').locator('.animate-spin').count(), 0);
    assert.deepEqual(errors, []);
    console.log(includeMarker ? '通过：分片卡片、过程抽屉、详情和刷新' : '通过：未输出占位符时在末尾补充报告卡片');
  } catch (error) {
    await page.screenshot({ path: path.join(screenshots, '受控链路-失败.png') });
    console.error('受控页面路径：', new URL(page.url()).pathname);
    throw error;
  } finally {
    await page.close();
  }
}

/** 通过实际登录、模型与数据库完成正式报告的交付。 */
async function live(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await page.goto(`${base}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 20000 });
      await page.goto(`${base}/ai/assistant`);
    }
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.getByLabel('经营问题').fill('请把已确认的十月盘点结论整理成一份可点击查看的正式简报。以下为演练数据，直接用这些数字，无需查库：A 商品基数 600 件，确认损失 12 件，另有 8 件待核实，确认损失率 2%。B 商品基数 200 件，确认损失 1 件，损失率 0.5%。比较两者并说明待核实数据对结论的影响。请使用报告工具生成正式报告。');
    await page.getByRole('button', { name: '发送需求' }).click();
    const card = page.getByTestId('ai-report-card').first();
    try {
      await card.waitFor({ timeout: 240000 });
      await page.waitForFunction(() => document.querySelector('[data-testid="ai-report-card"]:enabled'), { timeout: 90000 });
    } catch (error) {
      await page.screenshot({ path: path.join(screenshots, '真实链路-等待失败.png') });
      throw error;
    }
    await page.screenshot({ path: path.join(screenshots, '05-真实模型报告卡片.png') });
    await card.click();
    await page.getByTestId('ai-report-detail').getByRole('heading', { level: 1 }).waitFor();
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.getByTestId('ai-report-detail').getByText(/\b2(?:\.0+)?%/).first().waitFor();
    await page.screenshot({ path: path.join(screenshots, '06-真实模型报告详情.png') });
    const sectionLink = page.locator('a[href^="#ai-report-section-"]').first();
    if (await sectionLink.count()) {
      await sectionLink.click();
      const id = decodeURIComponent(new URL(page.url()).hash.slice(1));
      assert.ok(await page.evaluate(id => Boolean(document.getElementById(id)), id));
    }
    await page.getByRole('link', { name: '返回会话' }).click();
    await card.waitFor();
    await page.reload();
    await card.waitFor();
    assert.equal(await page.getByTestId('ai-report-card').count(), 1);
    console.log('通过：真实主智能体生成正式报告，浏览器可按报告 ID 打开、返回并刷新');

    await page.getByRole('button', { name: '新建会话' }).click();
    await page.getByLabel('经营问题').fill('只告诉我，120 件商品中有 6 件破损，破损率是多少？');
    const streamResponse = page.waitForResponse(response => response.url().includes('/messages/stream')
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: '发送需求' }).click();
    const frames = await (await streamResponse).text();
    assert.match(frames, /event:\s*run_completed/);
    assert.doesNotMatch(frames, /"tool":\s*"createReport"|event:\s*report_ready/);
    await page.getByText('5%', { exact: false }).first().waitFor();
    assert.equal(await page.getByTestId('ai-report-card').count(), 0);
    console.log('通过：真实简单计算直接回答，未调用报告工具');
  } finally {
    await page.close();
  }
}

async function main() {
  await mkdir(screenshots, { recursive: true });
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    if (process.argv.includes('--live')) {
      await live(browser);
    } else {
      await controlled(browser, true);
      await controlled(browser, false);
    }
  } finally {
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
