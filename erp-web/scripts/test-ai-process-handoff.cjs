// 用可控事件和历史记录验证运行过程的顺序、去重与历史交接。
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

async function check(browser, missingHistory) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let saved = false;
  const user = { userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
  const conversation = { conversationId: '123', title: '测试会话', description: '', updatedAt: '2026-11-25 10:00:00' };
  const row = (seq, kind, type, data) => ({ messageId: String(seq), requestId: 'r', seq: String(seq), role: kind === 'USER_INPUT' ? 'user' : 'assistant', kind, createdAt: conversation.updatedAt, content: { type, data } });
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
    const original = window.fetch.bind(window);
    window.fetch = async (...args) => {
      if (!String(args[0]).includes('/messages/stream')) return original(...args);
      const encoder = new TextEncoder();
      const stream = new ReadableStream({ start(controller) {
        window.__emitLive = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
        window.__finishLive = () => { window.__emitLive('run_completed', { runId: 'r', status: 'COMPLETED', finalMessageId: 'final-live' }); controller.close(); };
        window.__emitLive('run_started', { runId: 'r', conversationId: '123' });
      } });
      return new Response(stream, { headers: { 'content-type': 'text/event-stream' } });
    };
  });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const pathname = path.replace(/^\/api/, '');
    let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
    else if (pathname.endsWith('/messages')) {
      const records = [row(1, 'USER_INPUT', 'TEXT', { text: '请分析数据' })];
      if (saved) {
        if (!missingHistory) records.push(row(2, 'ASSISTANT_STEP', 'TEXT', { text: '先查询客户。' }));
        records.push(row(3, 'TOOL_CALL', 'TOOL_CALL', { callId: 'tool-1', name: 'query_sql_data', label: '查询客户', status: 'done', durationMs: 1200 }));
        records.push(row(4, 'ASSISTANT_STEP', 'TEXT', { text: '再查询仓库。' }));
        records.push(row(5, 'TOOL_CALL', 'TOOL_CALL', { callId: 'tool-2', name: 'query_sql_data', label: '查询仓库', status: 'done', durationMs: 2000 }));
        records.push(row(6, 'ASSISTANT_FINAL', 'TEXT', { text: '查询完成。' }));
      }
      data = { records, pageNum: 1, pageSize: 50, total: 1 };
    } else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
  try {
    await page.goto(`${process.env.AI_UI_URL || 'http://127.0.0.1:5173'}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 10000 }).catch(async error => {
      console.error('页面状态：', page.url(), (await page.locator('body').innerText()).slice(0, 1200), errors);
      throw error;
    });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('请分析数据');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    const turn = page.locator('.message[data-run-id="r"]');
    await page.evaluate(() => {
      window.__emitLive('text_delta', { messageId: 'text-1', delta: '先查询' });
      window.__emitLive('tool_started', { toolCallId: 'tool-1', tool: 'query_sql_data', label: '正在查询客户' });
      window.__emitLive('text_delta', { messageId: 'text-1', delta: '客户。' });
      window.__emitLive('text_completed', { messageId: 'text-1', text: '先查询客户。' });
      window.__emitLive('tool_finished', { toolCallId: 'tool-1', label: '查询客户', status: 'done', durationMs: 1200 });
      window.__emitLive('text_completed', { messageId: 'text-2', text: '再查询仓库。' });
      window.__emitLive('tool_started', { toolCallId: 'tool-2', tool: 'query_sql_data', label: '正在查询仓库' });
      window.__emitLive('tool_finished', { toolCallId: 'tool-2', label: '查询仓库', status: 'done', durationMs: 2000 });
    });
    await turn.getByText('查询仓库', { exact: true }).waitFor({ state: 'visible' });
    const text = await turn.innerText();
    for (const part of ['先查询客户。', '查询客户', '再查询仓库。', '查询仓库']) assert.ok(text.includes(part), `实时过程缺少 ${part}`);
    assert.ok(text.indexOf('先查询客户。') < text.indexOf('查询客户\n') && text.indexOf('查询客户\n') < text.indexOf('再查询仓库。') && text.indexOf('再查询仓库。') < text.indexOf('查询仓库\n'), '实时文本与工具顺序应与事件一致');
    assert.equal((text.match(/先查询客户。/g) || []).length, 1, '跨工具事件的同一文本不能重复');
    saved = true;
    await page.evaluate(() => {
      window.__emitLive('text_completed', { messageId: 'final-live', text: '查询完成。' });
      window.__finishLive();
    });
    await page.waitForTimeout(1000);
    if (missingHistory) {
      assert.equal(await page.getByText('部分回复尚未同步到历史，请重新进入会话确认后再继续。').isVisible(), true, '缺失历史时必须提示');
      assert.equal(await turn.getByText('先查询客户。', { exact: true }).isVisible(), true, '历史缺失时不得丢失实时正文');
      assert.equal(await turn.getByText('查询客户', { exact: true }).isVisible(), true, '历史缺失时已完成工具仍可见');
    } else {
      assert.equal(await page.getByText('部分回复尚未同步到历史，请重新进入会话确认后再继续。').count(), 0, '完整交接不应提示缺失');
      await turn.getByRole('button', { name: '查看工作过程' }).click();
      for (const part of ['先查询客户。', '查询客户', '再查询仓库。', '查询仓库']) assert.equal(await turn.getByText(part, { exact: true }).count(), 1, `历史接管后 ${part} 应出现一次`);
    }
    assert.deepEqual(errors, []);
  } finally {
    await page.close();
  }
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    await check(browser, false);
    await check(browser, true);
    console.log('AI_PROCESS_HANDOFF_OK: 实时顺序、工具完成、历史接管及缺失正文保留通过');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
