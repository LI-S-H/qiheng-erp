const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-25-ai-approval-cancel');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = { conversationId: '123', title: '连续审批会话', description: '', updatedAt: '2026-11-25 10:00:00' };
const order = {
  salesOrderId: '1', salesNo: 'SO-TEST', status: 'DRAFT', version: 1,
  customerId: '2', customerCode: 'C1', customerName: '测试客户',
  warehouseId: '3', warehouseName: '测试仓库', expectedDeliveryDate: '2026-12-15',
  totalAmount: '10.00', remark: '',
  items: [{
    salesOrderItemId: '11', salesOrderId: '1', productId: '4', productCode: 'P1', productName: '测试产品',
    unitName: '件', quantityPrecision: 0, quantity: 1, unitPrice: '10.00', totalAmount: '10.00',
    remark: '', lockedQty: 0, outboundQty: 0,
  }],
};
const firstCall = { callId: 'call-1', action: 'CREATE' };
const secondCall = { callId: 'call-2', action: 'UPDATE' };
let resolvedCount = 0;
let messages = [];

const toolCall = (call, action, argumentsJson) => ({
  messageId: `m-${call}`, requestId: 'r', seq: call === 'call-1' ? '3' : '5', role: 'assistant', kind: 'TOOL_CALL',
  createdAt: conversation.updatedAt,
  content: { type: 'ORDER_TOOL', data: {
    callId: call, name: action === 'CREATE' ? 'prepare_sales_order_draft' : 'update_sales_order_draft',
    label: action === 'CREATE' ? '创建销售草稿单' : '修改销售草稿单', action, orderType: 'SALES', status: 'waiting',
    arguments: argumentsJson,
  } },
});
const userRecord = { messageId: 'm-u', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '连续创建并修改销售单' } } };

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(({ token, tokenName }) => {
    localStorage.setItem('erp_auth_token', token);
    localStorage.setItem('erp_auth_token_name', tokenName);
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
  }, { token: 'test-token', tokenName: 'satoken' });

  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (pathname.endsWith('/runtime')) {
      data = resolvedCount === 0 ? { status: 'WAITING_USER' }
        : resolvedCount === 1 ? { status: 'WAITING_USER', salesDraft: {
            callId: secondCall.callId, action: 'UPDATE', orderType: 'SALES',
            arguments: JSON.stringify({ salesOrderId: '1', warehouseId: '3', items: [{ productId: '4', quantity: 2, unitPrice: 10 }] }),
          } }
          : { status: 'COMPLETED' };
    } else if (pathname.endsWith('/messages') || pathname === '/ai/assistant/conversations/123/messages') {
      data = { records: messages, pageNum: 1, pageSize: 50, total: messages.length };
    } else if (/sales-(drafts|actions).*\/resolve/.test(pathname)) {
      resolvedCount += 1;
      if (resolvedCount === 1) {
        messages = [userRecord, toolCall(firstCall.callId, 'CREATE', JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] })),
          toolCall(secondCall.callId, 'UPDATE', JSON.stringify({ salesOrderId: '1', warehouseId: '3', items: [{ productId: '4', quantity: 2, unitPrice: 10 }] }))];
      }
      data = null;
    } else if (pathname.startsWith('/sales/orders')) data = order;
    else if (pathname.startsWith('/sales/customers/')) data = { customerCode: 'C1', customerName: '测试客户' };
    else if (pathname.startsWith('/warehouse/warehouses/')) data = { warehouseName: '测试仓库' };
    else if (pathname.startsWith('/product/products/')) data = { productCode: 'P1', productName: '测试产品', unitName: '件', quantityPrecision: 0, referenceSalePrice: 10 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  try {
    await page.goto(`${process.env.AI_UI_URL || 'http://127.0.0.1:5137'}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('连续创建并修改销售单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    messages = [userRecord, toolCall(firstCall.callId, 'CREATE', JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }))];
    await page.evaluate(() => {
      window.__emitLive('sales_order_draft', { callId: 'call-1', action: 'CREATE', orderType: 'SALES', arguments: JSON.stringify({ customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }) });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    });

    // 第一次审批：创建。
    await page.locator('[data-ai-sales-draft-dock]').waitFor({ timeout: 10000 });
    await page.locator('[data-ai-sales-draft-dock]').getByRole('button', { name: '查看详情' }).click();
    const createDialog = page.locator('[data-sales-draft-dialog]');
    await createDialog.waitFor();
    await createDialog.getByRole('button', { name: '确认创建', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('[data-sales-draft-dialog][data-state="open"]'), null, { timeout: 10000 });
    await page.waitForFunction(() => document.querySelectorAll('[data-ai-sales-draft-dock]').length === 0, null, { timeout: 10000 });

    // 第一次确认后，旧提示必须结束；新一轮待确认（call-2）必须出现。
    await page.waitForFunction(() => (document.body.innerText.match(/等待用户确认/g) || []).length === 1, null, { timeout: 10000 });
    const pendingText = await page.locator('.tool-progress').filter({ hasText: '等待用户确认' }).innerText();
    assert.ok(pendingText.includes('修改销售草稿单'), `应只剩 call-2 的等待提示，实际: ${pendingText}`);
    await page.screenshot({ path: path.join(screenshotDir, 'chain-second-pending.png') });

    // 第二次审批：修改。
    await page.locator('[data-ai-sales-draft-dock]').getByRole('button', { name: '查看详情' }).click();
    const updateDialog = page.locator('[data-sales-draft-dialog]');
    await updateDialog.waitFor();
    await updateDialog.getByRole('button', { name: '保存修改', exact: true }).click({ timeout: 10000 }).catch(async error => {
      console.error('修改弹窗内容', (await updateDialog.innerText()).slice(0, 1200));
      throw error;
    });
    await page.waitForFunction(() => !document.querySelector('[data-sales-draft-dialog][data-state="open"]'), null, { timeout: 10000 });
    await page.waitForFunction(() => (document.body.innerText.match(/等待用户确认/g) || []).length === 0, null, { timeout: 10000 });
    assert.equal(await page.locator('[data-ai-sales-draft-dock],[data-ai-sales-action-dock]').count(), 0, '连续审批结束后不应残留待确认卡片');
    await page.screenshot({ path: path.join(screenshotDir, 'chain-all-settled.png') });
    assert.deepEqual(errors, []);
    console.log('continuous approval OK');
  } finally {
    await page.close();
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });