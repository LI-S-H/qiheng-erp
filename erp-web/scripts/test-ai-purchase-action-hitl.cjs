const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-purchase-hitl');
fs.mkdirSync(screenshotDir, { recursive: true });

/**
 * 读取指定区域截图并统计深色像素数量。
 *
 * 全页加载遮罩接近纯白，会把对话文字整块盖住；确认后如果消息区域仍有足够深色文字像素，
 * 说明背景内容没有被刷白。用像素证据判断视觉结果，而不是只看 DOM 节点。
 */
async function countDarkPixels(page, clip) {
  const shot = await page.screenshot({ clip });
  const base64 = shot.toString('base64');
  return page.evaluate(async dataUrl => {
    const image = new Image();
    image.src = dataUrl;
    await image.decode();
    const canvas = document.createElement('canvas');
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext('2d');
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let dark = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      const luminance = 0.2126 * pixels[index] + 0.7152 * pixels[index + 1] + 0.0722 * pixels[index + 2];
      if (luminance < 140) {
        dark += 1;
      }
    }
    return { dark, total: canvas.width * canvas.height };
  }, `data:image/png;base64,${base64}`);
}

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = { conversationId: '123', title: '采购审批会话', description: '', updatedAt: '2026-11-25 10:00:00' };
const baseOrder = {
  purchaseOrderId: '7001', purchaseNo: 'PO001', status: 'DRAFT', version: 1,
  supplierId: '5', supplierCode: 'S1', supplierName: '测试供应商',
  warehouseId: '6', warehouseName: '测试仓库', expectedArrivalDate: '2026-12-02',
  totalAmount: '10.00', remark: '',
  items: [{
    purchaseOrderItemId: '71', purchaseOrderId: '7001', supplierProductId: '51',
    productId: '4', productCode: 'P1', productName: '测试产品', unitName: '件',
    quantityPrecision: 0, quantity: 1, unitPrice: '10.00', totalAmount: '10.00',
    remark: '', inboundQty: 0, selectedSupplierScore: null,
  }],
  fulfillmentSummary: { totalAmount: '10.00', inboundAmount: '0.00', completionRate: 0 },
  timeline: [],
};

/** 执行提交或取消场景；SUBMIT 先清空到货日期再重新选择，覆盖补日期写接口。 */
async function check(browser, action) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const writeCalls = [];
  const receipts = [];
  const putBodies = [];
  let submitBody = null;
  let resolved = false;
  let messages = [];
  // 提交场景留空到货日期，必须走“补日期 + 提交”两个写请求。
  let order = { ...baseOrder, expectedArrivalDate: action === 'SUBMIT' ? '' : baseOrder.expectedArrivalDate };

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
    if (pathname === '/auth/me') {
      data = user;
    } else if (pathname === '/ai/assistant/overview') {
      data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    } else if (pathname === '/ai/assistant/conversations' && method === 'POST') {
      data = conversation;
    } else if (pathname === '/ai/assistant/conversations') {
      data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    } else if (pathname === '/ai/assistant/conversations/123') {
      data = conversation;
    } else if (pathname.endsWith('/runtime')) {
      data = { status: resolved ? 'COMPLETED' : 'WAITING_USER' };
    } else if (pathname.endsWith('/messages') || pathname === '/ai/assistant/conversations/123/messages') {
      data = { records: messages, pageNum: 1, pageSize: 50, total: messages.length };
    } else if (/actions\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      resolved = true;
      data = null;
    } else if (pathname.startsWith('/purchase/suppliers/')) {
      data = { supplierCode: 'S1', supplierName: '测试供应商' };
    } else if (pathname.startsWith('/purchase/orders')) {
      if (method === 'GET') {
        data = order;
      } else {
        writeCalls.push(`${method} ${pathname}`);
        // 模拟真实写接口耗时，让遮罩采样覆盖请求进行中的时间窗。
        await new Promise(resolve => setTimeout(resolve, 300));
        if (method === 'PUT') {
          const body = request.postDataJSON();
          putBodies.push(body);
          order = {
            ...order,
            expectedArrivalDate: body.expectedArrivalDate ?? order.expectedArrivalDate,
            version: order.version + 1,
          };
          data = order;
        } else {
          // 提交和取消返回空数据，写后版本递增，供前端重新读取详情后继续提交。
          if (pathname.endsWith('/submit')) {
            submitBody = request.postDataJSON();
          }
          order = { ...order, status: pathname.endsWith('/submit') ? 'SUBMITTED' : 'CANCELLED', version: order.version + 1 };
          if (!pathname.endsWith('/submit') && !pathname.endsWith('/cancel')) {
            data = order;
          }
        }
      }
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill(action === 'SUBMIT' ? '提交采购单' : '取消采购单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    messages = [
      { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '采购单动作' } } },
      { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-2', name: 'purchase_order_action', label: action === 'SUBMIT' ? '提交采购单' : '取消采购单', action, orderType: 'PURCHASE', status: 'waiting', purchaseOrderId: '7001', orderNo: 'PO001' } } },
    ];
    await page.evaluate(action => {
      window.__emitLive('purchase_action', {
        callId: 'call-2', action, orderType: 'PURCHASE', purchaseOrderId: '7001', orderNo: 'PO001',
      });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    }, action);

    await page.locator('[data-ai-purchase-action]').waitFor({ timeout: 10000 });
    await page.getByTestId('purchase-action-open').click();
    const dialog = page.locator('[data-purchase-action-dialog]');
    await dialog.waitFor({ timeout: 10000 });
    await page.waitForFunction(() => !document.querySelector('[data-page-loading]'), null, { timeout: 5000 });
    if (action === 'SUBMIT') {
      // 预计到货为空，先通过日期选择器补一个未来日期，确保 updatePurchaseOrder 分支被执行。
      await dialog.getByRole('button', { name: '打开日期选择器' }).click();
      const calendar = page.locator('.order-date-picker__content');
      await calendar.waitFor({ timeout: 10000 });
      // 清空后默认回到当前月，切到下个月再选 10 日，避免命中过去日期被校验拦截。
      await calendar.getByRole('button', { name: '下个月' }).click();
      await calendar.locator('.order-date-picker__day', { hasText: /^10$/ }).first().click();
      await page.waitForFunction(() => !document.querySelector('[data-purchase-action-dialog] .order-date-picker[data-empty]'), null, { timeout: 5000 });
    }
    const openButton = action === 'SUBMIT' ? '提交审核' : '确认取消';
    await dialog.getByRole('button', { name: openButton, exact: true }).click();
    const confirm = page.locator('[data-confirm-dialog]');
    await confirm.waitFor({ timeout: 10000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${action.toLowerCase()}-confirm-open.png`) });

    // 记录对话中用户消息的文字区域；确认后该区域若被全页遮罩盖住，深色像素会消失。
    const messageBox = await page.locator('.conversation-stream').getByText('采购单动作', { exact: true }).boundingBox();
    const messageClip = {
      x: Math.max(0, Math.round(messageBox.x) - 8),
      y: Math.max(0, Math.round(messageBox.y) - 8),
      width: Math.round(messageBox.width) + 16,
      height: Math.round(messageBox.height) + 16,
    };
    await page.evaluate(() => {
      window.__maskTrace = [];
      const start = performance.now();
      window.__maskTimer = setInterval(() => {
        window.__maskTrace.push({
          t: Math.round(performance.now() - start),
          mask: !!document.querySelector('[data-page-loading]'),
        });
        if (performance.now() - start > 2500) clearInterval(window.__maskTimer);
      }, 16);
    });

    await confirm.getByRole('button', { name: openButton, exact: true }).click({ timeout: 10000 });
    await page.waitForFunction(() => !document.querySelector('[data-purchase-action-dialog]'), null, { timeout: 8000 });
    await page.waitForTimeout(60);
    const backgroundPixels = await countDarkPixels(page, messageClip);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${action.toLowerCase()}-confirm-closing.png`) });

    await page.waitForFunction(() => (document.body.innerText.match(/等待用户确认/g) || []).length === 0, null, { timeout: 15000 });
    await page.waitForTimeout(700);
    assert.equal(await page.locator('[data-ai-purchase-action]').count(), 0, `${action} 确认后不应残留采购动作卡片`);
    const maskTrace = await page.evaluate(() => {
      clearInterval(window.__maskTimer);
      return window.__maskTrace;
    });
    assert.ok(resolved, `${action} 未提交审批回执`);
    const expectedReceipt = action === 'SUBMIT' ? 'submitted' : 'cancelled';
    assert.equal(receipts[0]?.status, expectedReceipt, `${action} 回执状态不正确：${JSON.stringify(receipts[0])}`);
    const expectedWrite = `POST /purchase/orders/7001/${action.toLowerCase()}`;
    assert.ok(writeCalls.includes(expectedWrite), `${action} 未发出预期写请求 ${expectedWrite}，实际 ${JSON.stringify(writeCalls)}`);
    if (action === 'SUBMIT') {
      assert.ok(writeCalls.includes('PUT /purchase/orders/7001'), `SUBMIT 补日期未发出更新请求，实际 ${JSON.stringify(writeCalls)}`);
      assert.equal(putBodies.length, 1, `SUBMIT 补日期应只发出一次更新，实际 ${putBodies.length}`);
      assert.ok(putBodies[0].expectedArrivalDate, 'SUBMIT 补日期请求未携带预计到货日期');
      assert.equal(receipts[0].arrivalDate, putBodies[0].expectedArrivalDate, '回执中的到货日期与更新请求不一致');
      assert.equal(submitBody?.version, 2, `提交应使用更新后的版本号，实际 ${JSON.stringify(submitBody)}`);
    }
    assert.ok(maskTrace.length > 20, `${action} 遮罩采样点过少：${maskTrace.length}`);
    assert.ok(!maskTrace.some(item => item.mask), `${action} 确认期间出现全局白色加载遮罩`);
    assert.ok(backgroundPixels.dark > 150, `${action} 确认期间对话文字被遮罩刷白，深色像素 ${backgroundPixels.dark}/${backgroundPixels.total}`);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${action.toLowerCase()}-settled.png`) });
    assert.deepEqual(errors, []);
    console.log(`purchase ${action} OK writes=${JSON.stringify(writeCalls)} receipt=${receipts[0]?.status} maskSamples=${maskTrace.filter(item => item.mask).length} darkPixels=${backgroundPixels.dark}`);
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
    for (const action of ['SUBMIT', 'CANCEL']) {
      await check(browser, action);
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });