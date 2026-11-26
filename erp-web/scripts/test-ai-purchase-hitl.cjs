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
const order = {
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

const scenarios = [
  { action: 'CREATE', openButton: '确认创建', receiptStatus: 'created', expectedWrite: 'POST /purchase/orders' },
  { action: 'UPDATE', openButton: '保存修改', receiptStatus: 'updated', expectedWrite: 'PUT /purchase/orders/7001' },
];

/** 执行一个采购草稿确认场景，覆盖创建和修改两条写路径。 */
async function check(browser, scenario) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const writeCalls = [];
  const receipts = [];
  let resolved = false;
  let messages = [];
  const isUpdate = scenario.action === 'UPDATE';
  const draftArguments = JSON.stringify({
    ...(isUpdate ? { purchaseOrderId: '7001' } : {}),
    supplierId: '5', warehouseId: '6', expectedArrivalDate: '2026-12-02', remark: '',
    items: [{ productId: '4', quantity: 1, unitPrice: 10, remark: '' }],
  });

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
    } else if (/drafts\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      resolved = true;
      data = null;
    } else if (pathname.startsWith('/purchase/suppliers/')) {
      data = { supplierCode: 'S1', supplierName: '测试供应商' };
    } else if (pathname.startsWith('/purchase/supplier-products')) {
      data = {
        records: [{
          supplierProductId: '51', supplierId: '5', productId: '4', productCode: 'P1',
          productName: '测试产品', unitName: '件', quantityPrecision: 0,
          quotedPurchasePrice: '10.00', latestPurchasePrice: '10.00', minOrderQty: 1,
          scoreBasisAmount: '0.00', scoreStatus: 'NOT_READY', status: 1, version: 0,
        }],
        total: 1, pageNum: 1, pageSize: 10,
      };
    } else if (pathname.startsWith('/purchase/orders')) {
      if (method !== 'GET') {
        writeCalls.push(`${method} ${pathname}`);
        // 模拟真实写接口耗时，让遮罩采样覆盖请求进行中的时间窗。
        await new Promise(resolve => setTimeout(resolve, 300));
      }
      data = order;
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill(isUpdate ? '修改采购单' : '创建采购单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    messages = [
      { messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: isUpdate ? '修改采购单' : '创建采购单' } } },
      { messageId: '2', requestId: 'r', seq: '2', role: 'assistant', kind: 'TOOL_CALL', createdAt: conversation.updatedAt, content: { type: 'ORDER_TOOL', data: { callId: 'call-1', name: isUpdate ? 'update_purchase_order_draft' : 'prepare_purchase_order_draft', label: isUpdate ? '修改采购单' : '创建采购草稿单', action: scenario.action, orderType: 'PURCHASE', status: 'waiting', arguments: draftArguments } } },
    ];
    await page.evaluate(({ action, args }) => {
      window.__emitLive('purchase_draft', { callId: 'call-1', action, orderType: 'PURCHASE', arguments: args });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' });
      window.__finishLive();
    }, { action: scenario.action, args: draftArguments });

    await page.locator('[data-ai-purchase-draft]').waitFor({ timeout: 10000 });
    await page.getByTestId('purchase-draft-open').click();
    const dialog = page.locator('[data-purchase-draft-dialog]');
    await dialog.waitFor({ timeout: 10000 });
    await page.waitForFunction(() => !document.querySelector('[data-page-loading]'), null, { timeout: 5000 });
    await dialog.getByRole('button', { name: scenario.openButton, exact: true }).click();
    const confirm = page.locator('[data-confirm-dialog]');
    await confirm.waitFor({ timeout: 10000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${scenario.action.toLowerCase()}-confirm-open.png`) });

    // 记录对话中用户消息的文字区域；确认后该区域若被全页遮罩盖住，深色像素会消失。
    const messageBox = await page.locator('.conversation-stream').getByText(isUpdate ? '修改采购单' : '创建采购单', { exact: true }).boundingBox();
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

    await confirm.getByRole('button', { name: scenario.openButton, exact: true }).click({ timeout: 10000 });
    await page.waitForFunction(() => !document.querySelector('[data-purchase-draft-dialog]'), null, { timeout: 8000 });
    await page.waitForTimeout(60);
    const backgroundPixels = await countDarkPixels(page, messageClip);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${scenario.action.toLowerCase()}-confirm-closing.png`) });

    await page.waitForFunction(() => (document.body.innerText.match(/等待用户确认/g) || []).length === 0, null, { timeout: 15000 });
    await page.waitForTimeout(700);
    assert.equal(await page.locator('[data-ai-purchase-draft]').count(), 0, '确认后不应残留采购待确认卡片');
    const maskTrace = await page.evaluate(() => {
      clearInterval(window.__maskTimer);
      return window.__maskTrace;
    });
    assert.ok(resolved, `${scenario.action} 未提交审批回执`);
    assert.ok(writeCalls.includes(scenario.expectedWrite), `${scenario.action} 未发出预期写请求 ${scenario.expectedWrite}，实际 ${JSON.stringify(writeCalls)}`);
    assert.equal(receipts[0]?.status, scenario.receiptStatus, `${scenario.action} 回执状态不正确：${JSON.stringify(receipts[0])}`);
    if (scenario.action === 'UPDATE') {
      assert.equal(receipts[0]?.finalParams, undefined, '用户未改参数时不应携带最终参数');
      assert.equal(receipts[0]?.originalParams, undefined, '用户未改参数时不应携带比较基线');
    }
    assert.ok(maskTrace.length > 20, `${scenario.action} 遮罩采样点过少：${maskTrace.length}`);
    assert.ok(!maskTrace.some(item => item.mask), `${scenario.action} 确认期间出现全局白色加载遮罩`);
    assert.ok(backgroundPixels.dark > 150, `${scenario.action} 确认期间对话文字被遮罩刷白，深色像素 ${backgroundPixels.dark}/${backgroundPixels.total}`);
    await page.screenshot({ path: path.join(screenshotDir, `purchase-${scenario.action.toLowerCase()}-settled.png`) });
    assert.deepEqual(errors, []);
    console.log(`purchase ${scenario.action} OK writes=${JSON.stringify(writeCalls)} receipt=${receipts[0]?.status} maskSamples=${maskTrace.filter(item => item.mask).length} darkPixels=${backgroundPixels.dark}`);
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

/** 采购驳回：父弹窗锁住、驳回层在上、取消只关上层、确认后两层一起收。 */
async function checkReject(browser) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  const receipts = [];
  let resolved = false;
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
    else if (/drafts\/.*\/resolve/.test(pathname)) {
      receipts.push(request.postDataJSON());
      resolved = true;
      data = null;
    } else if (pathname.startsWith('/purchase/suppliers/')) data = { supplierCode: 'S1', supplierName: '测试供应商' };
    else if (pathname.startsWith('/purchase/supplier-products')) data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
    else if (pathname.startsWith('/purchase/orders')) data = order;
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });

  const draftArguments = JSON.stringify({
    supplierId: '5', warehouseId: '6', expectedArrivalDate: '2026-12-02', remark: '',
    items: [{ productId: '4', quantity: 1, unitPrice: 10, remark: '' }],
  });

  try {
    await page.goto(process.env.AI_UI_URL || 'http://127.0.0.1:5137/ai/assistant');
    await page.locator('.welcome-state').waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('创建采购单');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    await page.evaluate(args => {
      window.__emitLive('purchase_draft', { callId: 'call-1', action: 'CREATE', orderType: 'PURCHASE', arguments: args });
    }, draftArguments);
    await page.locator('[data-testid="purchase-draft-open"]:visible').first().click();
    const dialog = page.locator('[data-purchase-draft-dialog]');
    await dialog.waitFor({ timeout: 10000 });
    await page.waitForFunction(() => !document.querySelector('[data-page-loading]'), null, { timeout: 5000 });
    // 编辑态也必须能直接驳回，不需要先取消编辑。
    await dialog.getByRole('button', { name: '编辑并补充说明', exact: true }).click();
    await page.waitForTimeout(250);
    const editReject = dialog.getByRole('button', { name: '驳回并说明', exact: true });
    assert.equal(await editReject.isVisible(), true, '编辑态缺少驳回入口');
    await page.screenshot({ path: path.join(screenshotDir, 'purchase-edit-reject-button.png') });

    for (const [width, height] of [[1440, 900], [1366, 768], [900, 600]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(300);
      await assertCentered(page, '[data-purchase-draft-dialog]', `采购弹窗 ${width}x${height}`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(300);

    await dialog.getByRole('button', { name: '驳回并说明', exact: true }).click();
    const reject = page.locator('[data-purchase-draft-reject]');
    await reject.waitFor({ timeout: 10000 });
    assert.equal(await page.evaluate(() => document.querySelector('[data-purchase-draft-dialog]').inert), true, '采购驳回打开后父弹窗未 inert');
    const layers = await page.evaluate(() => {
      const parent = document.querySelector('[data-purchase-draft-dialog]');
      const reject = document.querySelector('[data-purchase-draft-reject]');
      const overlays = Array.from(document.querySelectorAll('[data-slot="dialog-overlay"]'));
      return {
        parentZ: Number(getComputedStyle(parent).zIndex),
        rejectZ: Number(getComputedStyle(reject).zIndex),
        overlayZs: overlays.map(el => Number(getComputedStyle(el).zIndex)),
      };
    });
    assert.ok(layers.rejectZ > layers.parentZ, `采购驳回层未高于父弹窗：${JSON.stringify(layers)}`);
    assert.ok(Math.max(...layers.overlayZs) > layers.parentZ, `采购驳回遮罩未盖住父弹窗：${JSON.stringify(layers)}`);
    await assertCentered(page, '[data-purchase-draft-reject]', '采购驳回弹窗');
    await page.screenshot({ path: path.join(screenshotDir, 'purchase-edit-reject-layer.png') });

    await page.keyboard.press('Escape');
    await reject.waitFor({ state: 'detached', timeout: 5000 });
    assert.equal(await dialog.isVisible(), true, 'Esc 后采购父弹窗被误关');
    assert.equal(await page.evaluate(() => document.querySelector('[data-purchase-draft-dialog]').inert), false, 'Esc 后采购父弹窗仍被锁住');

    await dialog.getByRole('button', { name: '驳回并说明', exact: true }).click();
    await reject.waitFor({ timeout: 5000 });
    await reject.locator('button[aria-pressed]').first().click();
    await reject.locator('textarea').fill('仅验证编辑态驳回入口，不保存');
    await page.screenshot({ path: path.join(screenshotDir, 'purchase-edit-reject-filled.png') });
    await page.getByTestId('purchase-draft-reject-confirm').click();
    await page.waitForFunction(() => !document.querySelector('[data-purchase-draft-reject]'), null, { timeout: 5000 });
    await page.waitForFunction(() => !document.querySelector('[data-purchase-draft-dialog]'), null, { timeout: 5000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(screenshotDir, 'purchase-edit-reject-settled.png') });
    assert.ok(resolved, '采购驳回未交回模型');
    assert.equal(receipts[receipts.length - 1]?.status, 'rejected', `采购驳回回执不正确：${JSON.stringify(receipts)}`);
    assert.deepEqual(errors, []);
    console.log('purchase REJECT OK');
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
    await checkReject(browser);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
