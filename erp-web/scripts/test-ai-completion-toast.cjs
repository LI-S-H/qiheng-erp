const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-27-ai-run-notices');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = (id, title, updatedAt, awaitingUser = false) => ({
  conversationId: id, title, description: '', updatedAt, awaitingUser,
});

function installMocks(page, conversations, remembered = '101', history = {}) {
  return Promise.all([
    page.addInitScript(({ remembered }) => {
      localStorage.setItem('erp_auth_token', 'test-token');
      localStorage.setItem('erp_auth_token_name', 'satoken');
      localStorage.setItem('erp.ai.draft.v2:1:new:activeConversation:', JSON.stringify(remembered));
      localStorage.setItem('erp_ai_conversation_read_at:1', JSON.stringify({
        '101': '2026-11-27 00:00:00',
        '102': '2026-11-27 00:00:00',
        '103': '2026-11-27 00:00:00',
      }));
      const original = window.fetch.bind(window);
      window.__streams = {};
      window.__emit = (conversationId, event, data) => {
        window.__streams[conversationId]?.emit(event, data);
      };
      window.__closeStream = conversationId => {
        window.__streams[conversationId]?.close();
      };
      window.fetch = async (...args) => {
        const url = String(args[0]);
        if (!url.includes('/messages/stream')) return original(...args);
        const conversationId = url.match(/conversations\/(\d+)\/messages\/stream/)?.[1] || 'unknown';
        const encoder = new TextEncoder();
        return new Response(new ReadableStream({
          start(controller) {
            window.__streams[conversationId] = {
              emit: (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)),
              close: () => controller.close(),
            };
          },
        }), { headers: { 'content-type': 'text/event-stream' } });
      };
    }, { remembered }),
    page.route('**/api/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (!url.pathname.startsWith('/api/')) return route.continue();
      const pathname = url.pathname.replace(/^\/api/, '');
      const method = request.method();
      let data = null;
      if (pathname === '/auth/me') data = user;
      else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversations[0].updatedAt, quickPrompts: [], conversations: [] };
      else if (pathname === '/ai/assistant/conversations' && method === 'GET') data = { records: conversations, hasMore: false, nextBeforeTime: null, nextBeforeId: null };
      else if (/^\/ai\/assistant\/conversations\/\d+$/.test(pathname)) {
        const id = pathname.split('/').pop();
        data = conversations.find(item => item.conversationId === id) || conversations[0];
      } else if (pathname.endsWith('/messages')) {
        const conversationId = pathname.match(/conversations\/(\d+)\/messages/)?.[1] || '';
        const records = history[conversationId] || [];
        data = { records, pageNum: 1, pageSize: 50, total: records.length };
      } else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
      else if (pathname.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
      else if (pathname.endsWith('/reports')) data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    }),
  ]);
}

async function openWorkspace(page) {
  await page.goto(`${BASE}/ai/assistant`);
  await page.locator('[data-testid="ai-graph-workspace"]').waitFor({ timeout: 15000 });
}

async function waitForActiveTitle(page, title) {
  await page.waitForFunction(expected => {
    const heading = document.querySelector('.workspace-title h1');
    return heading?.textContent?.includes(expected);
  }, title, { timeout: 15000 });
}

async function startRun(page, conversationId) {
  await page.getByLabel('经营问题').fill('分析一下库存');
  await page.getByRole('button', { name: '发送需求' }).click();
  await page.waitForFunction(id => Boolean(window.__streams?.[id]), conversationId);
  await page.evaluate(id => {
    window.__emit(id, 'run_started', { runId: `run-${id}`, conversationId: id });
    window.__emit(id, 'text_delta', { messageId: 'm1', delta: '正在分析库存…' });
  }, conversationId);
}

async function switchTo(page, title) {
  await page.locator('.conversation-row').filter({ hasText: title }).locator('.conversation-main').click();
  await waitForActiveTitle(page, title);
}

async function emit(page, conversationId, event, data) {
  await page.evaluate(({ conversationId, event, data }) => window.__emit(conversationId, event, data), { conversationId, event, data });
}

function notice(page, text) {
  return page.locator('[data-ai-run-notice]').filter({ hasText: text });
}

async function closeNotice(page, text) {
  await notice(page, text).locator('.ai-run-notice__close').click();
  await notice(page, text).waitFor({ state: 'detached', timeout: 5000 });
}

function row(page, title) {
  return page.locator('.conversation-row').filter({ hasText: title });
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const errors = [];
  try {
    const conversations = [
      conversation('101', '后台分析会话', '2026-11-27 01:00:00'),
      conversation('102', '当前查看会话', '2026-11-27 00:50:00'),
      conversation('103', '另一个后台会话', '2026-11-27 00:40:00'),
    ];
    const history = {};
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    page.on('pageerror', error => errors.push(error.message));
    await installMocks(page, conversations, '101', history);
    await openWorkspace(page);
    await waitForActiveTitle(page, '后台分析会话');
    await startRun(page, '101');
    assert.equal(await row(page, '后台分析会话').locator('.conversation-running').count(), 1, '运行中应显示侧栏转圈');
    await switchTo(page, '当前查看会话');

    // 待回答：文案、琥珀点、重复事件去重
    await emit(page, '101', 'question', {
      questionRequestId: 'q1', toolCallId: 't1',
      questions: [{ questionId: 'q1-1', title: '确认统计范围', options: [] }],
    });
    const askNotice = notice(page, '需要你补充信息');
    await askNotice.waitFor({ timeout: 10000 });
    assert.equal(await askNotice.getAttribute('data-kind'), 'pending');
    assert.ok((await askNotice.innerText()).includes('点击继续回答'));
    const amber = await askNotice.locator('.ai-run-notice__icon').evaluate(node => getComputedStyle(node).backgroundColor);
    assert.equal(amber, 'rgb(253, 243, 227)', '待回答图标应为琥珀色底');
    const row101 = row(page, '后台分析会话');
    assert.equal(await row101.locator('.conversation-pending-dot').count(), 1, '后台会话应显示待处理琥珀点');
    assert.equal(await row101.locator('.conversation-unread-dot').count(), 0, '待处理点应优先于未读点');
    assert.equal(await row101.locator('.conversation-running').count(), 0, '等待回答时侧栏不应显示运行中动画');
    assert.equal(await row101.locator('.conversation-pending-dot').evaluate(node => getComputedStyle(node).backgroundColor), 'rgb(217, 119, 6)');
    await emit(page, '101', 'question', {
      questionRequestId: 'q1', toolCallId: 't1',
      questions: [{ questionId: 'q1-1', title: '确认统计范围', options: [] }],
    });
    assert.equal(await page.locator('[data-ai-run-notice]').filter({ hasText: '需要你补充信息' }).count(), 1, '同一 requestId 不应重复弹提醒');
    await page.screenshot({ path: path.join(screenshotDir, 'pending-ask.png') });

    // 打开会话不清待处理提醒，也不清侧栏琥珀点；处理完成后才消失
    await switchTo(page, '后台分析会话');
    await page.waitForTimeout(500);
    assert.equal(await notice(page, '需要你补充信息').count(), 1, '打开会话后待处理提醒应保留');
    assert.equal(await page.locator('.workspace-actions .agent-running-status').count(), 0, '等待回答时顶栏不应显示运行中动画');
    await switchTo(page, '当前查看会话');
    assert.equal(await row101.locator('.conversation-pending-dot').count(), 1, '打开再切走后琥珀点仍应保留');
    await emit(page, '101', 'question_answered', { questionRequestId: 'q1' });
    await notice(page, '需要你补充信息').waitFor({ state: 'detached', timeout: 5000 });
    await page.waitForTimeout(200);
    assert.equal(await row101.locator('.conversation-pending-dot').count(), 0, '处理完成后琥珀点应消失');

    // run_completed(WAITING_USER) 不能清掉待办；点击提醒跳转会话
    await emit(page, '101', 'purchase_action', { actionRequestId: 'pa1', purchaseOrderId: '1', orderNo: 'PO1', action: 'SUBMIT' });
    await notice(page, '等你确认提交采购单').waitFor({ timeout: 10000 });
    await emit(page, '101', 'run_completed', { runId: 'run-101', status: 'WAITING_USER' });
    await page.waitForTimeout(300);
    assert.equal(await notice(page, '等你确认提交采购单').count(), 1, 'WAITING_USER 结束不能清掉待办提醒');
    assert.equal(await row101.locator('.conversation-pending-dot').count(), 1, 'WAITING_USER 结束不能清掉琥珀点');
    history['101'] = [{
      messageId: 'tc-pa1', requestId: 'run-101', seq: '2', role: 'assistant', kind: 'TOOL_CALL',
      createdAt: '2026-11-27 01:00:00',
      content: {
        type: 'ORDER_TOOL',
        data: {
          callId: 'pa1', name: 'submit_purchase_order', action: 'SUBMIT',
          orderType: 'PURCHASE', purchaseOrderId: '1', orderNo: 'PO1', status: 'waiting',
        },
      },
    }];
    await notice(page, '等你确认提交采购单').locator('.ai-run-notice__main').click();
    await waitForActiveTitle(page, '后台分析会话');
    await notice(page, '等你确认提交采购单').waitFor({ state: 'detached', timeout: 5000 });
    await switchTo(page, '当前查看会话');
    await emit(page, '101', 'purchase_action_resolved', { actionRequestId: 'pa1' });
    await page.waitForTimeout(300);
    history['101'] = [];
    assert.equal(await row101.locator('.conversation-pending-dot').count(), 0);

    // 各类单据文案
    await emit(page, '101', 'purchase_action', { actionRequestId: 'pa2', purchaseOrderId: '2', orderNo: 'PO2', action: 'SUBMIT' });
    await notice(page, '等你确认提交采购单').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认提交采购单');

    await emit(page, '101', 'sales_order_action', { actionRequestId: 'sa1', salesOrderId: '1', orderNo: 'SO1', action: 'CANCEL' });
    await notice(page, '等你确认取消销售单').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认取消销售单');

    await emit(page, '101', 'return_order_action', { callId: 'ra1', action: 'SUBMIT', returnType: 'SALES_RETURN', returnOrderId: '1', orderNo: 'SR1' });
    await notice(page, '等你确认提交销售退货单').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认提交销售退货单');

    await emit(page, '101', 'return_order_action', { callId: 'ra2', action: 'CANCEL', returnType: 'PURCHASE_RETURN', returnOrderId: '2', orderNo: 'PR2' });
    await notice(page, '等你确认取消采购退货单').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认取消采购退货单');

    await emit(page, '101', 'purchase_draft', {
      callId: 'pd1',
      arguments: JSON.stringify({ request: { supplierId: '1', warehouseId: '1', items: [{ productId: '1', quantity: 2 }] } }),
    });
    await notice(page, '等你确认采购单草稿').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认采购单草稿');

    await emit(page, '101', 'return_order_draft', {
      callId: 'rd1',
      arguments: JSON.stringify({ request: { returnType: 'PURCHASE_RETURN', sourceOrderId: '1', warehouseId: '1', reasonCode: 'OTHER', items: [{ sourceOrderItemId: '1', requestedQty: 1 }] } }),
    });
    await notice(page, '等你确认采购退货单草稿').waitFor({ timeout: 10000 });
    await closeNotice(page, '等你确认采购退货单草稿');

    // 折叠堆叠与悬停展开
    await emit(page, '101', 'question', {
      questionRequestId: 'q3', toolCallId: 't3',
      questions: [{ questionId: 'q3-1', title: '确认统计范围', options: [] }],
    });
    await emit(page, '101', 'purchase_action', { actionRequestId: 'pa3', purchaseOrderId: '3', orderNo: 'PO3', action: 'SUBMIT' });
    await notice(page, '需要你补充信息').waitFor({ timeout: 10000 });
    await notice(page, '等你确认提交采购单').waitFor({ timeout: 10000 });
    await page.waitForTimeout(700);
    const panelBox = await page.locator('.conversation-panel').boundingBox();
    const frontToast = page.locator('.ai-notice-toaster [data-sonner-toast][data-front="true"]');
    const frontBox = await frontToast.boundingBox();
    assert.ok(panelBox && frontBox, '提醒堆栈或会话面板不存在');
    assert.ok(Math.abs(frontBox.x + frontBox.width - (panelBox.x + panelBox.width - 20)) <= 2, '提醒应距会话区右侧 20px');
    assert.ok(Math.abs(frontBox.y - (panelBox.y + 68)) <= 2, '提醒应在顶栏下方 68px');
    assert.ok(Math.abs(frontBox.width - 336) <= 2, '提醒宽度应为 336px');
    assert.equal(await page.locator('.ai-notice-toaster [data-sonner-toast]').count(), 2, '两条待办应折叠在同一堆');
    await page.screenshot({ path: path.join(screenshotDir, 'pending-stack-collapsed.png') });
    await frontToast.hover();
    await page.waitForFunction(() => {
      const items = [...document.querySelectorAll('.ai-notice-toaster [data-sonner-toast]')];
      return items.length > 0 && items.every(item => item.getAttribute('data-expanded') === 'true');
    }, null, { timeout: 5000 });
    await page.screenshot({ path: path.join(screenshotDir, 'pending-stack-expanded.png') });
    await page.mouse.move(10, 10);

    // 输出完成：待办收起，绿色提醒 6 秒自动消失
    await emit(page, '101', 'run_completed', { runId: 'run-101', status: 'COMPLETED', finalMessageId: 'f1' });
    const completed = notice(page, '输出完成');
    await completed.waitFor({ timeout: 10000 });
    assert.equal(await completed.getAttribute('data-kind'), 'completed');
    const green = await completed.locator('.ai-run-notice__icon').evaluate(node => getComputedStyle(node).backgroundColor);
    assert.equal(green, 'rgb(230, 243, 238)', '完成提醒图标应为绿色底');
    await notice(page, '需要你补充信息').waitFor({ state: 'detached', timeout: 5000 });
    assert.equal(await notice(page, '需要你补充信息').count(), 0, '本轮结束后应收起待办提醒');
    assert.equal(await row101.locator('.conversation-running').count(), 0, '完成后即使连接未关闭也不应显示转圈');
    await page.screenshot({ path: path.join(screenshotDir, 'completed-top-right.png') });
    const startedAt = Date.now();
    await page.waitForTimeout(5000);
    assert.equal(await notice(page, '输出完成').count(), 1, '完成提醒 5 秒内不应消失');
    await completed.waitFor({ state: 'detached', timeout: 4000 });
    assert.ok(Date.now() - startedAt >= 5500, '完成提醒不应早于 6 秒消失');
    await switchTo(page, '后台分析会话');
    await page.waitForTimeout(500);
    assert.equal(await page.locator('.workspace-actions .agent-running-status').count(), 0, '已完成的会话顶栏不应显示运行中动画');

    // 窄屏：左右各留 12px
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    mobile.on('pageerror', error => errors.push(error.message));
    await installMocks(mobile, conversations);
    await openWorkspace(mobile);
    await waitForActiveTitle(mobile, '后台分析会话');
    await startRun(mobile, '101');
    await mobile.getByLabel('打开会话列表').click();
    await mobile.locator('.conversation-row').filter({ hasText: '当前查看会话' }).locator('.conversation-main').click();
    await waitForActiveTitle(mobile, '当前查看会话');
    await emit(mobile, '101', 'question', {
      questionRequestId: 'qm1', toolCallId: 'tm1',
      questions: [{ questionId: 'qm1-1', title: '确认统计范围', options: [] }],
    });
    const mobileNotice = notice(mobile, '需要你补充信息');
    await mobileNotice.waitFor({ timeout: 10000 });
    await mobile.waitForTimeout(700);
    const mobilePanel = await mobile.locator('.conversation-panel').boundingBox();
    const mobileBox = await mobileNotice.boundingBox();
    assert.ok(mobilePanel && mobileBox, '移动端提醒或会话面板不存在');
    assert.ok(Math.abs(mobileBox.x - mobilePanel.x - 12) <= 2, '移动端提醒应距会话区左侧 12px');
    assert.ok(Math.abs(mobilePanel.x + mobilePanel.width - (mobileBox.x + mobileBox.width) - 12) <= 2, '移动端提醒应距会话区右侧 12px');
    await mobile.screenshot({ path: path.join(screenshotDir, 'pending-mobile.png') });

    // 刷新后：列表 awaitingUser 恢复琥珀点，未读仍显示蓝点
    const refreshConversations = [
      conversation('101', '后台分析会话', '2026-11-27 01:00:00', true),
      conversation('102', '当前查看会话', '2026-11-27 01:00:00', false),
      conversation('103', '另一个后台会话', '2026-11-27 00:30:00', false),
    ];
    const refreshPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    refreshPage.on('pageerror', error => errors.push(error.message));
    await installMocks(refreshPage, refreshConversations, '103');
    await openWorkspace(refreshPage);
    await waitForActiveTitle(refreshPage, '另一个后台会话');
    const refreshed101 = row(refreshPage, '后台分析会话');
    const refreshed102 = row(refreshPage, '当前查看会话');
    assert.equal(await refreshed101.locator('.conversation-pending-dot').count(), 1, '刷新后待处理会话应恢复琥珀点');
    assert.equal(await refreshed101.locator('.conversation-unread-dot').count(), 0, '琥珀点应优先于蓝点');
    assert.equal(await refreshed102.locator('.conversation-unread-dot').count(), 1, '刷新后未读会话应显示蓝点');
    await refreshPage.screenshot({ path: path.join(screenshotDir, 'refresh-pending-unread.png') });

    assert.deepEqual(errors, []);
    console.log('AI_RUN_NOTICES_OK');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });