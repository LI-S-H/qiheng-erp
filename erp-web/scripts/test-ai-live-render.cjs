// 验证历史用户消息存在时，流尚未结束的文字能真正显示在页面上。
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

async function main() {
  const base = process.env.AI_UI_URL || 'http://127.0.0.1:5173';
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => {
      localStorage.setItem('erp_auth_token', 'test-token');
      localStorage.setItem('erp_auth_token_name', 'satoken');
      const original = window.fetch.bind(window);
      window.fetch = async (...args) => {
        if (!String(args[0]).includes('/messages/stream')) return original(...args);
        const encoder = new TextEncoder();
        const stream = new ReadableStream({ start(controller) {
          window.__emitLive = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          window.__finishLive = () => { window.__emitLive('run_completed', { runId: 'r', status: 'COMPLETED' }); controller.close(); };
          window.__emitLive('run_started', { runId: 'r', conversationId: '123' });
        } });
        return new Response(stream, { headers: { 'content-type': 'text/event-stream' } });
      };
    });
    const user = { userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
    const conversation = { conversationId: '123', title: '测试会话', description: '', updatedAt: '2026-11-25 10:00:00' };
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
      else if (pathname.endsWith('/messages')) data = { records: [{ messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '请分析数据' } } }], pageNum: 1, pageSize: 50, total: 1 };
      else if (pathname === '/ai/assistant/conversations/123') data = conversation;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    });
    await page.goto(`${base}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 10000 }).catch(async error => {
      console.error('页面状态：', page.url(), (await page.locator('body').innerText()).slice(0, 1800), errors);
      throw error;
    });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('请分析数据');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    await page.locator('.message.is-user[data-message-id="1"]').filter({ hasText: '请分析数据' }).waitFor();
    await page.evaluate(() => window.__emitLive('text_delta', { messageId: 'live-1', delta: '实时内容第一段' }));
    const message = page.locator('.message[data-run-id="r"]');
    await message.getByText('实时内容第一段', { exact: true }).waitFor({ state: 'visible' });
    assert.equal(await message.locator('template').count(), 0, '实时内容不能放入不显示的原生 template');
    await page.evaluate(() => window.__emitLive('text_delta', { messageId: 'live-1', delta: '，继续输出。' }));
    await message.getByText('实时内容第一段，继续输出。', { exact: true }).waitFor({ state: 'visible' });
    assert.equal(await page.getByText('实时内容第一段，继续输出。', { exact: true }).count(), 1, '实时内容不能重复显示');
    assert.deepEqual(errors, [], '页面不应出现脚本异常');
    await page.evaluate(() => window.__finishLive());
    console.log('AI_LIVE_RENDER_OK: 历史用户输入存在时，完成事件之前两段实时文字均可见且不重复');
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
