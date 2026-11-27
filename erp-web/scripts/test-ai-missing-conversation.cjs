const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-27-ai-missing-conversation');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = (id, title, updatedAt) => ({ conversationId: id, title, description: '', updatedAt, awaitingUser: false });
const conversations = [
  conversation('201', '已删除的会话', '2026-11-27 10:00:00'),
  conversation('202', '最近回复的会话', '2026-11-27 09:00:00'),
];

async function installMocks(page) {
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
    localStorage.setItem('erp.ai.draft.v2:1:new:activeConversation:', JSON.stringify('201'));
  });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    if (pathname === '/ai/assistant/conversations/201/runtime') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 10002, message: '会话不存在或无权访问' }),
      });
    }
    let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversations[0].updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'GET') data = { records: conversations, hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (/^\/ai\/assistant\/conversations\/\d+$/.test(pathname)) {
      const id = pathname.split('/').pop();
      data = conversations.find(item => item.conversationId === id) || conversations[0];
    } else if (pathname.endsWith('/messages')) data = { records: [], pageNum: 1, pageSize: 50, total: 0 };
    else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
    else if (pathname.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
    else if (pathname.endsWith('/reports')) data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await installMocks(page);
  try {
    await page.goto(`${BASE}/ai/assistant`);
    await page.locator('[data-testid="ai-graph-workspace"]').waitFor({ timeout: 15000 });
    await page.waitForFunction(() => {
      const title = document.querySelector('.workspace-title h1');
      return title?.textContent?.includes('最近回复的会话');
    }, null, { timeout: 15000 });
    assert.ok((await page.locator('.request-warning').innerText()).includes('会话已被删除，已切换到最近回复的会话'));
    assert.equal(await page.locator('[data-sonner-toast]').count(), 0, '运行状态接口的会话不存在不应弹全局错误提示');
    assert.equal(await page.locator('.workspace-actions .agent-running-status').count(), 0);
    assert.equal(await page.locator('.conversation-row.is-active .conversation-title').innerText(), '最近回复的会话');
    await page.screenshot({ path: path.join(screenshotDir, 'missing-conversation-fallback.png') });
    assert.deepEqual(errors, []);
    console.log('MISSING_CONVERSATION_FALLBACK_OK');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });