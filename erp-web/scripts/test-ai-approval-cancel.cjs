const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');

const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-25-ai-approval-cancel');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = { conversationId: '123', title: '测试会话', description: '', updatedAt: '2026-11-25 10:00:00' };

/** 统一的接口桩；两个场景只替换需要的数据。 */
async function installRoutes(page, options) {
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    // 只拦截接口请求，避免把 /src/api/** 模块脚本也当成数据接口。
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') {
      data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: options.conversations };
    } else if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') {
      data = { records: options.conversations, hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    } else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (pathname.endsWith('/messages') || pathname === '/ai/assistant/conversations/123/messages') {
      const records = options.messages;
      data = { records, pageNum: 1, pageSize: 50, total: records.length };
    } else if (pathname.endsWith('/runtime')) data = options.runtime || { status: 'IDLE' };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
}

async function openPage(browser, options) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') console.error('控制台', message.text()); });
  page.on('requestfailed', request => console.error('请求失败', request.url(), request.failure()?.errorText));
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
  });
  await installRoutes(page, options);
  return { page, errors };
}

/** 场景一：销售草稿待确认时，输入框与发送按钮必须禁用。 */
async function checkComposerLocked(browser) {
  const options = { conversations: [], messages: [] };
  const { page, errors } = await openPage(browser, options);
  try {
    await page.addInitScript(() => {
      const original = window.fetch.bind(window);
      window.fetch = async (...args) => {
        if (!String(args[0]).includes('/messages/stream')) return original(...args);
        const encoder = new TextEncoder();
        return new Response(new ReadableStream({
          start(controller) {
            window.__emitLive = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
            window.__finishLive = () => controller.close();
            window.__emitLive('run_started', { runId: 'r', conversationId: '123' });
          },
        }), { headers: { 'content-type': 'text/event-stream' } });
      };
    });
    await page.goto(`${process.env.AI_UI_URL || 'http://127.0.0.1:5137'}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 10000 }).catch(async error => {
      console.error('页面状态', page.url(), (await page.locator('body').innerText()).slice(0, 1200), errors);
      throw error;
    });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('给客户 C1 创建销售单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    // 后端在中断时会把待确认调用写入历史；补上这条记录，刷新后的恢复逻辑才和真实链路一致。
    options.messages = [
      { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '给客户 C1 创建销售单' } } },
      { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-1', name: 'prepare_sales_order_draft', label: '创建销售草稿单', action: 'CREATE', orderType: 'SALES', status: 'waiting', arguments: JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }) } } },
    ];
    await page.evaluate(() => {
      window.__emitLive('sales_order_draft', {
        callId: 'call-1', action: 'CREATE', orderType: 'SALES',
        arguments: JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }),
      });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    });
    await page.locator('[data-ai-sales-draft-dock]').waitFor({ timeout: 10000 });
    await page.waitForFunction(() => document.querySelector('#graph-query')?.disabled === true, null, { timeout: 5000 });
    const sendDisabled = await page.getByRole('button', { name: '发送需求' }).isDisabled();
    assert.equal(sendDisabled, true, '待确认时发送按钮应禁用');
    await page.screenshot({ path: path.join(screenshotDir, 'composer-locked.png') });
    assert.deepEqual(errors, []);
    console.log('composer locked OK');
  } finally {
    await page.close();
  }
}

/** 场景二：历史里已有 cancelled 结果时，旧卡不再复活，工作过程显示已放弃。 */
async function checkCancelledHistory(browser) {
  const messages = [
    { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '给客户 C1 创建销售单' } } },
    { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'ASSISTANT_STEP', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '我先准备这张销售单，请确认。' } } },
    { messageId: '3', requestId: 'r', seq: '3', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-1', name: 'prepare_sales_order_draft', label: '创建销售草稿单', action: 'CREATE', orderType: 'SALES', status: 'waiting', arguments: JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }) } } },
    { messageId: '4', requestId: 'r', seq: '4', role: 'assistant', kind: 'TOOL_RESULT', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-1', name: 'prepare_sales_order_draft', action: 'CREATE', orderType: 'SALES', result: JSON.stringify({ status: 'cancelled', message: '用户未确认，已放弃本次操作。' }) } } },
    { messageId: '5', requestId: 'r', seq: '5', role: 'assistant', kind: 'ASSISTANT_FINAL', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '好的，已放弃这次创建。' } } },
  ];
  const { page, errors } = await openPage(browser, { conversations: [conversation], messages });
  try {
    await page.goto(`${process.env.AI_UI_URL || 'http://127.0.0.1:5137'}/ai/assistant`);
    await page.locator('.conversation-main').filter({ hasText: '测试会话' }).click();
    await page.getByText('好的，已放弃这次创建。').waitFor({ timeout: 10000 });
    const toggle = page.getByRole('button', { name: '查看工作过程' });
    if (await toggle.count()) await toggle.first().click();
    await page.getByText('创建销售草稿单 · 已放弃', { exact: false }).waitFor({ timeout: 5000 }).catch(async error => {
      console.error('历史页文本', (await page.locator('body').innerText()).slice(0, 1500));
      throw error;
    });
    assert.equal(await page.locator('[data-ai-sales-draft-dock]').count(), 0, '已作废的草稿不应恢复底部确认卡');
    await page.screenshot({ path: path.join(screenshotDir, 'history-cancelled.png') });
    assert.deepEqual(errors, []);
    console.log('cancelled history OK');
  } finally {
    await page.close();
  }
}

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  try {
    await checkComposerLocked(browser);
    await checkCancelledHistory(browser);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });