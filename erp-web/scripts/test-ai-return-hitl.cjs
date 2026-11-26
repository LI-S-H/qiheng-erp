const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-return-hitl');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = { conversationId: '123', title: '退货审批会话', description: '', updatedAt: '2026-11-26 10:00:00' };
const salesOrder = {
  salesOrderId: '6001', salesNo: 'SO001', customerId: '5', customerCode: 'C1', customerName: '测试客户',
  warehouseId: '6', warehouseName: '测试仓库', expectedDeliveryDate: '2026-12-01',
  status: 'OUTBOUND_DONE', totalAmount: '50.00', lockedAt: null, remark: '',
  createdById: '1', createdByName: '测试用户', submittedById: null, submittedByName: null,
  approvedById: '1', approvedByName: '测试用户', version: 1,
  items: [{
    salesOrderItemId: '61', salesOrderId: '6001', salesNo: 'SO001', productId: '4',
    productCode: 'P1', productName: '测试产品', unitName: '件', quantityPrecision: 0,
    quantity: 5, lockedQty: 0, outboundQty: 5, unitPrice: '10.00', totalAmount: '50.00', remark: '',
  }],
};
const returnOrder = {
  returnOrderId: '8001', returnNo: 'SR001', returnType: 'SALES_RETURN',
  sourceOrderId: '6001', sourceOrderNo: 'SO001', partyId: '5', partyCode: 'C1', partyName: '测试客户',
  warehouseId: '6', warehouseName: '测试仓库', expectedExecutionDate: '2026-12-02',
  handlingType: 'REFUND', reasonCode: 'QUALITY_ISSUE', returnReason: '质量问题', totalAmount: '20.00',
  status: 'DRAFT', statusReason: '', createdById: '1', createdByName: '测试用户',
  submittedAt: null, approvedById: null, approvedByName: '', approvedAt: null,
  createTime: '2026-11-26 10:00:00', updateTime: '2026-11-26 10:00:00', remark: '', version: 1,
  items: [{
    returnOrderItemId: '81', returnOrderId: '8001', sourceOrderItemId: '61', productId: '4',
    productCode: 'P1', productName: '测试产品', unitName: '件', quantityPrecision: 0,
    sourceFulfilledQty: 5, requestedQty: 2, approvedQty: 0, processedQty: 0,
    unitPrice: '10.00', totalAmount: '20.00', createTime: '2026-11-26 10:00:00',
    updateTime: '2026-11-26 10:00:00', remark: '',
  }],
};
const sourceItems = [{
  sourceOrderItemId: '61', productId: '4', productCode: 'P1', productName: '测试产品',
  unitName: '件', quantityPrecision: 0, sourceFulfilledQty: 5, stockAvailableQty: 0,
  occupiedQty: 0, availableReturnQty: 5, unitPrice: '10.00',
}];

const scenarios = [
  { action: 'CREATE', label: '创建销售退货单', expectedWrite: 'POST /returns', receiptStatus: 'created' },
  { action: 'UPDATE', label: '修改销售退货单', expectedWrite: 'PUT /returns/8001', receiptStatus: 'updated', returnOrderId: '8001' },
];

async function check(browser, scenario) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const writes = [];
  const receipts = [];
  let resolved = false;
  let messages = [];
  const draft = {
    returnType: 'SALES_RETURN', sourceOrderId: '6001', warehouseId: '6',
    expectedExecutionDate: '2026-12-02', handlingType: 'REFUND', reasonCode: 'QUALITY_ISSUE',
    returnReason: '客户反馈质量问题', remark: '',
    items: [{ sourceOrderItemId: '61', requestedQty: 2, remark: '' }],
  };
  if (scenario.returnOrderId) {
    draft.returnOrderId = scenario.returnOrderId;
    draft.items[0].requestedQty = 1;
  }
  const argumentsText = JSON.stringify(draft);

  page.on('pageerror', error => errors.push(error.message));
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
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (pathname.endsWith('/runtime')) data = { status: resolved ? 'COMPLETED' : 'WAITING_USER' };
    else if (pathname.endsWith('/messages')) data = { records: messages, pageNum: 1, pageSize: 50, total: messages.length };
    else if (/return-drafts\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      resolved = true;
      data = null;
    } else if (pathname.startsWith('/returns/source-orders/')) data = sourceItems;
    else if (pathname === '/returns' || pathname === '/returns/8001') {
      if (method !== 'GET') {
        writes.push(`${method} ${pathname}`);
        await new Promise(resolve => setTimeout(resolve, 300));
        data = { ...returnOrder, requestedQty: 1 };
      } else {
        data = returnOrder;
      }
    } else if (pathname === '/sales/orders/6001') data = salesOrder;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill(scenario.label);
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    messages = [
      { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: scenario.label } } },
      { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-1', name: scenario.action === 'UPDATE' ? 'update_return_order_draft' : 'prepare_return_order_draft', label: scenario.action === 'UPDATE' ? '修改退货草稿单' : '创建退货草稿单', action: scenario.action, orderType: 'RETURN', returnType: 'SALES_RETURN', returnOrderId: scenario.returnOrderId, status: 'waiting', arguments: argumentsText } } },
    ];
    await page.evaluate(({ action, args, returnOrderId }) => {
      window.__emitLive('return_order_draft', {
        callId: 'call-1', action, orderType: 'RETURN', returnType: 'SALES_RETURN',
        returnOrderId, arguments: args,
      });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    }, { action: scenario.action, args: argumentsText, returnOrderId: scenario.returnOrderId });

    await page.locator('[data-ai-return-draft]').waitFor({ timeout: 10000 });
    // 刷新后应从历史待确认记录恢复退货卡片，而不是误恢复成销售卡片。
    await page.reload();
    await page.locator('[data-ai-return-draft]').waitFor({ timeout: 10000 });
    await page.getByTestId('return-draft-open').click();
    const dialog = page.locator('[data-ai-return-draft-dialog]');
    await dialog.waitFor({ timeout: 10000 });
    assert.equal(await page.getByLabel('经营问题').isDisabled(), true, '退货确认期间主输入框应禁用');
    await page.screenshot({ path: path.join(screenshotDir, `return-${scenario.action.toLowerCase()}-open.png`) });
    assert.equal(await dialog.locator('text=测试产品').count() > 0, true, '退货明细未展示');
    // 必须验证内容真实可见：之前 ScrollArea 高度塌成 0，文本存在但用户看不到。
    assert.equal(await dialog.getByText('测试产品').first().isVisible(), true, '退货明细不可见');
    const scrollBox = await dialog.locator('[data-dialog-scroll-area]').boundingBox();
    assert.ok(scrollBox && scrollBox.height > 100, `退货弹窗内容区高度异常：${JSON.stringify(scrollBox)}`);
    if (scenario.action === 'CREATE') {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.waitForTimeout(300);
      await page.screenshot({ path: path.join(screenshotDir, 'return-create-mobile.png') });
      const mobileDialog = await dialog.boundingBox();
      assert.ok(mobileDialog && mobileDialog.width <= 390, `移动端弹窗横向溢出：${JSON.stringify(mobileDialog)}`);
      await page.setViewportSize({ width: 1440, height: 900 });
    }
    await page.getByTestId('return-draft-confirm').click();
    await page.waitForFunction(() => !document.querySelector('[data-ai-return-draft-dialog]'), null, { timeout: 8000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshotDir, `return-${scenario.action.toLowerCase()}-settled.png`) });
    assert.ok(resolved, `${scenario.action} 未提交 AI 回执`);
    assert.ok(writes.includes(scenario.expectedWrite), `${scenario.action} 未发出预期写请求，实际 ${JSON.stringify(writes)}`);
    assert.equal(receipts[0]?.status, scenario.receiptStatus, `${scenario.action} 回执状态不正确：${JSON.stringify(receipts[0])}`);
    assert.equal(receipts[0]?.returnType, 'SALES_RETURN', '回执缺少退货方向');
    assert.equal(await page.locator('[data-ai-return-draft]').count(), 0, '确认后不应残留退货待确认卡片');
    assert.deepEqual(errors, []);
    console.log(`return ${scenario.action} OK writes=${JSON.stringify(writes)} receipt=${receipts[0]?.status}`);
  } finally {
    await page.close();
  }
}

async function checkAction(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const writes = [];
  const receipts = [];
  let resolved = false;
  let messages = [];
  page.on('pageerror', error => errors.push(error.message));
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
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (pathname.endsWith('/runtime')) data = { status: resolved ? 'COMPLETED' : 'WAITING_USER' };
    else if (pathname.endsWith('/messages')) data = { records: messages, pageNum: 1, pageSize: 50, total: messages.length };
    else if (/return-actions\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      resolved = true;
      data = null;
    } else if (pathname === '/returns/8001/submit') {
      writes.push(`${method} ${pathname}`);
      data = null;
    } else if (pathname === '/returns/8001') data = returnOrder;
    else if (pathname === '/returns' && method === 'GET' && url.searchParams.get('returnNo') === 'SR001') {
      data = { records: [returnOrder], total: 1, pageNum: 1, pageSize: 5 };
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('提交退货单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    messages = [
      { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '提交退货单' } } },
      { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'act-1', name: 'submit_return_order', label: '提交退货单', action: 'SUBMIT', orderType: 'RETURN', returnOrderId: 'SR001', orderNo: 'SR001', status: 'waiting' } } },
    ];
    await page.evaluate(() => {
      window.__emitLive('return_order_action', {
        // 故意传单号而不是数字 ID，验证前端会按单号回查真实 ID。
        callId: 'act-1', action: 'SUBMIT', orderType: 'RETURN', returnOrderId: 'SR001', orderNo: 'SR001', reason: '',
      });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    });
    await page.locator('[data-ai-return-action]').waitFor({ timeout: 10000 });
    await page.getByTestId('return-action-open').click();
    const dialog = page.locator('[data-ai-return-action-dialog]');
    await dialog.waitFor({ timeout: 10000 });
    await page.screenshot({ path: path.join(screenshotDir, 'return-action-submit-open.png') });
    // 动作弹窗也要在业务内容区居中，且窄窗口不向右溢出。
    for (const [width, height] of [[1366, 768], [900, 600], [600, 500]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(300);
      await assertCentered(page, '[data-ai-return-action-dialog]', `动作弹窗 ${width}x${height}`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(300);
    assert.equal(await dialog.getByText('SR001').first().isVisible(), true, '退货单号不可见');
    const actionScrollBox = await dialog.locator('[data-dialog-scroll-area]').boundingBox();
    assert.ok(actionScrollBox && actionScrollBox.height > 100, `退货动作弹窗内容区高度异常：${JSON.stringify(actionScrollBox)}`);
    await page.getByTestId('return-action-confirm').click();
    const confirm = page.locator('[data-confirm-dialog]');
    await confirm.waitFor({ timeout: 10000 });
    await confirm.getByRole('button', { name: '提交审核', exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('[data-ai-return-action-dialog]'), null, { timeout: 8000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshotDir, 'return-action-submit-settled.png') });
    assert.ok(resolved, '提交回执未交回模型');
    assert.ok(writes.includes('POST /returns/8001/submit'), `未调用提交接口：${JSON.stringify(writes)}`);
    assert.equal(receipts[0]?.status, 'submitted', `提交回执状态不正确：${JSON.stringify(receipts[0])}`);
    assert.deepEqual(errors, []);
    console.log('return ACTION OK');
  } finally {
    await page.close();
  }
}

/** 断言弹窗在业务内容区水平居中、在视口垂直居中，且不向右溢出。 */
async function assertCentered(page, selector, label) {
  const metrics = await page.evaluate(sel => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    const sidebar = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-shell-sidebar-width')) || 0;
    return {
      cx: rect.x + rect.width / 2,
      cy: rect.y + rect.height / 2,
      right: rect.right,
      width: innerWidth,
      height: innerHeight,
      sidebar,
    };
  }, selector);
  assert.ok(metrics, `${label} 未找到`);
  const expectedX = metrics.sidebar + (metrics.width - metrics.sidebar) / 2;
  assert.ok(Math.abs(metrics.cx - expectedX) <= 1, `${label} X 轴未在内容区居中：${JSON.stringify(metrics)}`);
  assert.ok(Math.abs(metrics.cy - metrics.height / 2) <= 1, `${label} Y 轴未在视口居中：${JSON.stringify(metrics)}`);
  assert.ok(metrics.right <= metrics.width - 8, `${label} 右侧溢出：${JSON.stringify(metrics)}`);
}

async function checkReject(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const receipts = [];
  let resolved = false;
  let rejectStatus = 500;
  page.on('pageerror', error => errors.push(error.message));
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
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (pathname.endsWith('/runtime')) data = { status: resolved ? 'COMPLETED' : 'WAITING_USER' };
    else if (pathname.endsWith('/messages')) data = { records: [], pageNum: 1, pageSize: 50, total: 0 };
    else if (/return-drafts\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      if (rejectStatus !== 200) {
        return route.fulfill({ status: rejectStatus, contentType: 'application/json', body: JSON.stringify({ code: rejectStatus, message: '驳回回执失败' }) });
      }
      resolved = true;
      data = null;
    } else if (pathname.startsWith('/returns/source-orders/')) data = sourceItems;
    else if (pathname === '/sales/orders/6001') data = salesOrder;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('创建退货单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    await page.evaluate(args => {
      window.__emitLive('return_order_draft', {
        callId: 'call-1', action: 'CREATE', orderType: 'RETURN', returnType: 'SALES_RETURN', arguments: args,
      });
    }, JSON.stringify({
      returnType: 'SALES_RETURN', sourceOrderId: '6001', warehouseId: '6',
      expectedExecutionDate: '2026-12-02', handlingType: 'REFUND', reasonCode: 'QUALITY_ISSUE',
      returnReason: '客户反馈质量问题', remark: '',
      items: [{ sourceOrderItemId: '61', requestedQty: 2, remark: '' }],
    }));
    await page.locator('[data-testid="return-draft-open"]:visible').first().click();
    const dialog = page.locator('[data-ai-return-draft-dialog]');
    await dialog.waitFor({ timeout: 10000 });

    // 主弹窗居中：1440 / 1366 / 900 / 600 / 390
    for (const [width, height] of [[1440, 900], [1366, 768], [900, 600], [600, 500], [390, 844]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(300);
      await assertCentered(page, '[data-ai-return-draft-dialog]', `主弹窗 ${width}x${height}`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(300);

    // 打开驳回：父弹窗必须锁住，驳回层必须高于父层
    await page.getByRole('button', { name: '驳回', exact: true }).click();
    const reject = page.locator('[data-ai-return-draft-reject]');
    await reject.waitFor({ timeout: 10000 });
    assert.equal(await page.evaluate(() => document.querySelector('[data-ai-return-draft-dialog]').inert), true, '驳回打开后父弹窗未 inert');
    const layers = await page.evaluate(() => {
      const parent = document.querySelector('[data-ai-return-draft-dialog]');
      const reject = document.querySelector('[data-ai-return-draft-reject]');
      const overlays = Array.from(document.querySelectorAll('[data-slot="dialog-overlay"]'));
      return {
        parentZ: Number(getComputedStyle(parent).zIndex),
        rejectZ: Number(getComputedStyle(reject).zIndex),
        overlayZs: overlays.map(el => Number(getComputedStyle(el).zIndex)),
      };
    });
    assert.ok(layers.rejectZ > layers.parentZ, `驳回层未高于父弹窗：${JSON.stringify(layers)}`);
    assert.ok(Math.max(...layers.overlayZs) > layers.parentZ, `驳回遮罩未盖住父弹窗：${JSON.stringify(layers)}`);
    await assertCentered(page, '[data-ai-return-draft-reject]', '驳回弹窗');
    await page.screenshot({ path: path.join(screenshotDir, 'return-reject-layer-open.png') });

    // Esc 只关驳回，父弹窗保留且解锁
    await page.keyboard.press('Escape');
    await reject.waitFor({ state: 'detached', timeout: 5000 });
    assert.equal(await dialog.isVisible(), true, 'Esc 后父弹窗被误关');
    assert.equal(await page.evaluate(() => document.querySelector('[data-ai-return-draft-dialog]').inert), false, 'Esc 后父弹窗仍被锁住');
    await page.waitForTimeout(200);
    assert.equal(await page.evaluate(() => Boolean(document.activeElement?.closest('[data-ai-return-draft-dialog]'))), true, 'Esc 后焦点未回到父弹窗');

    // 驳回失败：两层都不收，错误显示在驳回框内
    await page.getByRole('button', { name: '驳回', exact: true }).click();
    await reject.waitFor({ timeout: 5000 });
    await reject.getByRole('button', { name: '数量不合适' }).click();
    await page.getByTestId('return-draft-reject-confirm').click();
    await page.waitForTimeout(600);
    assert.equal(await reject.isVisible(), true, '驳回失败后驳回框被误关');
    assert.equal(await page.evaluate(() => document.querySelector('[data-ai-return-draft-dialog]').inert), true, '驳回失败后父弹窗被误解锁');
    assert.ok((await reject.innerText()).includes('驳回'), '驳回失败错误未显示在驳回框内');
    await page.screenshot({ path: path.join(screenshotDir, 'return-reject-layer-failed.png') });

    // 驳回成功：两层一次性收完
    rejectStatus = 200;
    await page.getByTestId('return-draft-reject-confirm').click();
    await page.waitForFunction(() => !document.querySelector('[data-ai-return-draft-reject]'), null, { timeout: 5000 });
    await page.waitForFunction(() => !document.querySelector('[data-ai-return-draft-dialog]'), null, { timeout: 5000 });
    assert.ok(resolved, '驳回成功后未交回模型');
    assert.equal(receipts[receipts.length - 1]?.status, 'rejected', `驳回回执状态不正确：${JSON.stringify(receipts)}`);
    assert.deepEqual(errors, []);
    await page.screenshot({ path: path.join(screenshotDir, 'return-reject-layer-settled.png') });
    console.log('return REJECT OK');
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
    for (const scenario of scenarios) {
      await check(browser, scenario);
    }
    await checkAction(browser);
    await checkReject(browser);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });