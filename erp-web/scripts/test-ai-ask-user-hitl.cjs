const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-ask-user-hitl');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = { conversationId: '123', title: '追问会话', description: '', updatedAt: '2026-11-26 10:00:00' };
const questions = [
  {
    id: 'warehouse', question: '需要查哪个仓库？', type: 'SINGLE_CHOICE',
    options: ['一号仓', '二号仓'],
  },
  {
    id: 'range', question: '时间范围？', type: 'TEXT', options: [],
  },
];

/** 安装 SSE 与接口 Mock；live 场景用于实时追问，history 场景用于刷新恢复。 */
async function installMocks(page, state) {
  page.on('pageerror', error => state.errors.push(error.message));
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
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

  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    const method = request.method();
    let data = null;
    let message = '成功';
    let code = 0;

    if (pathname === '/auth/me') {
      data = user;
    } else if (pathname === '/ai/assistant/overview') {
      data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    } else if (pathname === '/ai/assistant/conversations' && method === 'POST') {
      data = conversation;
    } else if (pathname === '/ai/assistant/conversations') {
      data = { records: [conversation], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    } else if (pathname === '/ai/assistant/conversations/123') {
      data = conversation;
    } else if (pathname === '/ai/assistant/conversations/123/reports') {
      data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
    } else if (pathname.endsWith('/runtime')) {
      state.runtimeCalls += 1;
      data = { status: state.runtimeStatus };
    } else if (pathname.endsWith('/events')) {
      state.eventCalls += 1;
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'event: run_completed\ndata: {"runId":"r","status":"COMPLETED"}\n\n',
      });
    } else if (pathname.endsWith('/messages')) {
      data = { records: state.messages, pageNum: 1, pageSize: 50, total: state.messages.length };
    } else if (pathname === '/ai/assistant/conversations/123/questions/q1/answer') {
      state.answerCalls.push(request.postDataJSON());
      if (state.answerError) {
        code = 10001;
        message = state.answerError;
      } else {
        state.runtimeStatus = 'RUNNING';
      }
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code, message, data }),
    });
  });
}

function questionMessages() {
  return [
    { messageId: 'm1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '查库存' } } },
    { messageId: 'm2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'TOOL_CALL', data: { callId: 'q1', name: 'ask_user', arguments: JSON.stringify({ questions }) } } },
  ];
}

/** 实时追问：按钮禁用、错误保留、重新提交成功。 */
async function checkLive(browser) {
  const state = { errors: [], messages: [], runtimeStatus: 'IDLE', answerCalls: [], answerError: '', runtimeCalls: 0, eventCalls: 0 };
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  await installMocks(page, state);
  try {
    await page.goto(`${BASE}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 15000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('查库存');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    state.messages = questionMessages();
    await page.evaluate(({ questions }) => {
      window.__emitLive('question', { questionRequestId: 'q1', toolCallId: 'q1', questions });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    }, { questions });

    const dock = page.locator('[data-ai-question-dock]');
    await dock.waitFor({ timeout: 15000 });
    const input = page.locator('#graph-query');
    assert.equal(await input.isVisible(), true, '主输入框应保留位置');
    assert.equal(await input.isDisabled(), true, '追问期间主输入框应禁用');
    assert.equal(await input.evaluate(el => getComputedStyle(el).cursor), 'not-allowed', '主输入框应显示禁止光标');
    assert.equal(await input.getAttribute('title'), null, '不应使用浏览器原生 title 提示');
    await page.locator('.ai-composer').hover();
    await page.getByText('请先完成上方补充信息').waitFor({ timeout: 5000 });
    assert.equal(await page.getByText('请先完成上方补充信息').isVisible(), true, '应显示项目内 Tooltip');
    await page.screenshot({ path: path.join(screenshotDir, 'ask-live-tooltip.png') });
    await page.mouse.move(0, 0);
    await page.keyboard.press('Escape');
    await page.screenshot({ path: path.join(screenshotDir, 'ask-live-question-open.png') });

    const next = dock.getByRole('button', { name: /下一题|下一步|确认回答/ }).first();
    assert.equal(await next.isDisabled(), true, '未答完不能进入下一题');

    await dock.locator('[data-testid="question-0-option-0"]').click();
    await page.waitForFunction(() => document.querySelector('[data-testid="clarification-progress"]')?.textContent?.includes('2 / 2'), null, { timeout: 5000 });
    await dock.locator('[data-question-answer]').fill('本月');
    await page.waitForTimeout(300);
    const confirm = dock.getByRole('button', { name: '确认回答' }).first();
    assert.equal(await confirm.isDisabled(), false, '答完当前题后允许确认');
    await confirm.click();
    await page.waitForTimeout(400);
    const submit = dock.getByRole('button', { name: '提交回答' }).first();
    await submit.waitFor({ timeout: 5000 });
    assert.equal(await submit.isDisabled(), false, '全部答完后允许提交');

    state.answerError = '问题 warehouse 的选项不在候选中：三号仓';
    await submit.click();
    await page.waitForTimeout(600);
    const errorBox = dock.locator('[data-ai-question-error]');
    assert.equal(await errorBox.count(), 1, '回答失败应显示错误');
    const errorText = await errorBox.innerText();
    assert.ok(errorText.includes('选项不在候选中'), `错误应转为中文文本：${errorText}`);
    assert.ok(!errorText.includes('{') && !errorText.includes('"code"'), '错误不应直接展示 JSON');
    assert.equal(await dock.count(), 1, '回答失败后问题卡片应保留');
    await page.screenshot({ path: path.join(screenshotDir, 'ask-live-answer-error.png') });

    state.answerError = '';
    await submit.click();
    await page.waitForTimeout(800);
    assert.equal(state.answerCalls.length, 2, '修正后应重新提交');
    assert.equal(state.answerCalls[1].answers.length, 2);
    assert.equal(state.answerCalls[1].answers[0].questionId, 'warehouse');
    assert.deepEqual(state.answerCalls[1].answers[0].selectedOptions, ['一号仓']);
    await page.waitForFunction(() => !document.querySelector('[data-ai-question-dock]'), null, { timeout: 8000 });
    assert.equal(await dock.count(), 0, '回答成功后卡片应消失');
    await page.waitForTimeout(1200);
    assert.ok(state.runtimeCalls > 0, '回答成功后应重新查询运行状态');
    assert.ok(state.eventCalls > 0, '回答成功后应重新订阅事件通道');
    assert.deepEqual(state.errors, []);
    console.log('ASK_USER_LIVE_OK answers=', state.answerCalls.length);
  } finally {
    await page.close();
  }
}

/** 刷新恢复：历史里未回答的问题恢复，取消结果转成用户可读文本。 */
async function checkHistoryRestore(browser) {
  const state = {
    errors: [], runtimeStatus: 'WAITING_USER', answerCalls: [], answerError: '',
    messages: [
      { messageId: 'm1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '查库存' } } },
      { messageId: 'm2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'TOOL_CALL', data: { callId: 'q-old', name: 'ask_user', arguments: JSON.stringify({ questions }) } } },
      { messageId: 'm3', requestId: 'r', seq: '3', role: 'tool', kind: 'TOOL_RESULT', createdAt: conversation.updatedAt, content: { type: 'TOOL_RESULT', data: { callId: 'q-old', name: 'ask_user', result: JSON.stringify({ status: 'cancelled' }) } } },
      { messageId: 'm4', requestId: 'r', seq: '4', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'TOOL_CALL', data: { callId: 'q1', name: 'ask_user', arguments: JSON.stringify({ questions }) } } },
    ],
  };
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  await installMocks(page, state);
  try {
    await page.goto(`${BASE}/ai/assistant?conversationId=123`);
    const dock = page.locator('[data-ai-question-dock]');
    await dock.waitFor({ timeout: 15000 });
    assert.ok((await dock.innerText()).includes('需要查哪个仓库'), '刷新后应从历史恢复追问');
    const histories = page.locator('[data-testid="clarification-history"]');
    for (let index = 0; index < await histories.count(); index++) {
      await histories.nth(index).locator('summary').click().catch(() => undefined);
    }
    const canceledCard = page.locator('[data-testid="clarification-history"]').filter({ hasText: '本次追问已取消' });
    assert.equal(await canceledCard.count(), 1, '取消结果应显示在对应问题卡片中');
    assert.ok(!(await canceledCard.innerText()).includes('{"status":"cancelled"}'), '取消结果不应展示原始 JSON');
    assert.deepEqual(state.errors, []);
    await page.screenshot({ path: path.join(screenshotDir, 'ask-history-restored.png') });
    console.log('ASK_USER_HISTORY_OK');
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
    await checkLive(browser);
    await checkHistoryRestore(browser);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });