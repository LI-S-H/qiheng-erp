const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-conversation-selection');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = (id, title, updatedAt) => ({ conversationId: id, title, description: '', updatedAt });
const conversations = [
  conversation('104', '读取失败的会话', '2026-11-26 23:00:00'),
  conversation('101', '最近回复的会话', '2026-11-26 22:00:00'),
  conversation('102', '有未读消息的会话', '2026-11-26 21:00:00'),
  conversation('103', '更早的会话', '2026-11-26 20:00:00'),
];

async function installMocks(page) {
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
    localStorage.setItem('erp.ai.draft.v2:1:new:activeConversation:', JSON.stringify('104'));
    localStorage.setItem('erp_ai_conversation_read_at:1', JSON.stringify({
      '104': '2026-11-26 23:00:00',
      '101': '2026-11-26 22:00:00',
      '102': '2026-11-26 20:30:00',
      '103': '2026-11-26 20:00:00',
    }));
  });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversations[0].updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'GET') data = { records: conversations, hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname === '/ai/assistant/conversations/104') {
      return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '会话已被删除' }) });
    } else if (/^\/ai\/assistant\/conversations\/\d+$/.test(pathname)) {
      const id = pathname.split('/').pop();
      data = conversations.find(item => item.conversationId === id) || conversations[1];
    } else if (pathname.endsWith('/messages')) data = { records: [], pageNum: 1, pageSize: 50, total: 0 };
    else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
    else if (pathname.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'event: run_completed\ndata: {"runId":"r","status":"COMPLETED"}\n\n' });
    else if (pathname.endsWith('/reports')) data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
}

async function activeTitle(page) {
  return page.locator('.conversation-row.is-active .conversation-title').first().innerText();
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await installMocks(page);
  try {
    await page.goto(`${BASE}/ai/assistant`);
    await page.locator('[data-testid="ai-graph-workspace"]').waitFor({ timeout: 15000 });
    // 记得的会话读取失败 -> 自动切换到最近回复的会话
    await page.waitForFunction(() => {
      const title = document.querySelector('.conversation-row.is-active .conversation-title');
      return title?.textContent?.includes('最近回复的会话');
    }, null, { timeout: 15000 });
    assert.equal(await page.getByText('会话读取失败，请重试').count(), 0, '不应保留旧读取错误');
    await page.screenshot({ path: path.join(screenshotDir, 'fallback-to-latest.png') });

    // 未读点：102 的最近回复晚于本地已读时间
    const unreadRow = page.locator('.conversation-row').filter({ hasText: '有未读消息的会话' });
    assert.equal(await unreadRow.locator('.conversation-unread-dot').count(), 1, '未读会话应显示蓝点');
    await page.screenshot({ path: path.join(screenshotDir, 'unread-dot.png') });
    assert.deepEqual(errors, []);
    console.log('CONVERSATION_FALLBACK_UNREAD_OK');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });