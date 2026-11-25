const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-25-ai-approval-handoff');
fs.mkdirSync(screenshotDir, { recursive: true });

async function check(browser, action) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const user = { userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
  const conversation = { conversationId: '123', title: '测试会话', description: '', updatedAt: '2026-11-25 10:00:00' };
  const order = { salesOrderId: '1', salesNo: 'SO-TEST', status: 'DRAFT', version: 1, customerId: '2', customerCode: 'C1', customerName: '测试客户', warehouseId: '3', warehouseName: '测试仓库', expectedDeliveryDate: '2026-12-15', totalAmount: '10.00', items: [] };
  let resolved = false; let started = false; let runtimeRequests = 0; const runtimeTimes = [];
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token'); localStorage.setItem('erp_auth_token_name', 'satoken');
    const original = window.fetch.bind(window);
    window.fetch = async (...args) => {
      if (!String(args[0]).includes('/messages/stream')) return original(...args);
      const encoder = new TextEncoder();
      return new Response(new ReadableStream({ start(controller) {
        window.__emitLive = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
        window.__finishLive = () => controller.close();
        window.__emitLive('run_started', { runId: 'r', conversationId: '123' });
      }}), { headers: { 'content-type': 'text/event-stream' } });
    };
  });
  await page.route('**/api/**', async route => {
    const request = route.request(); const path = new URL(request.url()).pathname; if (!path.startsWith('/api/')) return route.continue(); const pathname = path.replace(/^\/api/, ''); let data = null;
    if (pathname === '/auth/me') data = user;
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') data = conversation;
    else if (pathname === '/ai/assistant/conversations') data = { records: [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (pathname.endsWith('/runtime')) { runtimeRequests++; runtimeTimes.push(Date.now()); data = { status: resolved ? 'COMPLETED' : 'WAITING_USER' }; }
    else if (pathname.endsWith('/messages')) { const records = [{ messageId: '1', requestId: 'r', seq: '1', role: 'user', kind: 'USER_INPUT', createdAt: conversation.updatedAt, content: { type: 'TEXT', data: { text: '测试订单' } } }]; if (started) { records.push({ messageId: '2', requestId:'r', seq:'2', role:'assistant', kind:'ASSISTANT_STEP', createdAt:conversation.updatedAt, content:{type:'TEXT',data:{text:'确认前说明：请检查订单。'}} }); records.push({ messageId:'3', requestId:'r', seq:'3', role:'assistant',kind:'TOOL_CALL',createdAt:conversation.updatedAt,content:{type:'ORDER_TOOL',data:{callId:'call-1',name:'sales_order',action,orderType:'SALES',status:'waiting',salesOrderId:'1',orderNo:'SO-TEST',arguments:JSON.stringify({salesOrderId:'1',customerId:'2',warehouseId:'3',items:[{productId:'4',quantity:1,unitPrice:10}]})}}}); } data={records,pageNum:1,pageSize:50,total:records.length}; }
    else if (pathname === '/ai/assistant/conversations/123') data = conversation;
    else if (/sales-(drafts|actions).*\/resolve/.test(pathname)) { resolved = true; data = null; }
    else if (pathname.startsWith('/sales/orders')) { if (request.method() !== 'GET') await new Promise(resolve => setTimeout(resolve, 300)); data = order; }
    else if (pathname.startsWith('/sales/customers/')) data = { customerCode: 'C1', customerName: '测试客户' };
    else if (pathname.startsWith('/warehouse/warehouses/')) data = { warehouseName: '测试仓库' };
    else if (pathname.startsWith('/product/products/')) data = { productCode: 'P1', productName: '测试产品', unitName: '件', quantityPrecision: 0, referenceSalePrice: 10 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
  try {
    await page.goto(`${process.env.AI_UI_URL || 'http://127.0.0.1:5137'}/ai/assistant`);
    await page.locator('.welcome-state').waitFor({ timeout: 10000 }).catch(async e => { console.error('页面状态', page.url(), (await page.locator('body').innerText()).slice(0,1200), errors); throw e; });
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill('测试订单'); await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__emitLive === 'function');
    started = true;
    await page.evaluate(action => {
      window.__emitLive('text_completed', { messageId: 'before', text: '确认前说明：请检查订单。' });
      if (action === 'CREATE' || action === 'UPDATE') window.__emitLive('sales_order_draft', { callId: 'call-1', action, arguments: JSON.stringify({ salesOrderId: '1', customerId: '2', warehouseId: '3', items: [{ productId: '4', quantity: 1, unitPrice: 10 }] }) });
      else window.__emitLive('sales_order_action', { actionRequestId: 'call-1', action, salesOrderId: '1', orderNo: 'SO-TEST', reason: '测试' });
      window.__emitLive('run_completed', { runId: 'r', status: 'WAITING_USER' }); window.__finishLive();
    }, action);
    const isDraft = action === 'CREATE' || action === 'UPDATE';
    const dock = page.locator(isDraft ? '[data-ai-sales-draft-dock]' : '[data-ai-sales-action-dock]');
    await dock.waitFor({ timeout: 10000 }).catch(async e=>{ console.error('DOCK', await page.locator('body').innerText(), errors); throw e; });
    await dock.getByRole('button', { name: '查看详情' }).click();
    const dialog = page.locator(isDraft ? '[data-sales-draft-dialog]' : '[data-sales-action-dialog]');
    await dialog.waitFor(); const button = { CREATE: '确认创建', UPDATE: '保存修改', SUBMIT: '提交审核', CANCEL: '确认取消' }[action];
    await page.waitForFunction(({selector,button}) => [...document.querySelectorAll(`${selector} button`)].some(x=>x.textContent.trim()===button && !x.disabled), { selector: isDraft ? '[data-sales-draft-dialog]' : '[data-sales-action-dialog]', button }, { timeout: 10000 }).catch(async e=>{console.error('DIALOG', await dialog.innerText(), errors);throw e;});
    await page.screenshot({ path: path.join(screenshotDir, `${action}-direct-before.png`) });
    await page.waitForFunction(() => !document.querySelector('[data-page-loading]'), null, { timeout: 5000 });
    const before = runtimeRequests; const startTime = Date.now();
    await page.evaluate(({selector}) => {
      window.__closeTrace = []; const start=performance.now();
      const timer=setInterval(()=>{const el=document.querySelector(selector); const dock=document.querySelector('[data-ai-sales-draft-dock],[data-ai-sales-action-dock]'); const stream=document.querySelector('.conversation-stream'); window.__closeTrace.push({t:performance.now()-start, dialog:el?.dataset.state||'gone', dock:!!dock, text:document.querySelector('.conversation-scroll')?.innerText.includes('确认前说明：请检查订单。'), opacity:stream ? Number(getComputedStyle(stream).opacity) : null, mask:!!document.querySelector('[data-page-loading]'), overlay:!!document.querySelector('.sales-approval-overlay'), confirm:!!document.querySelector('[data-confirm-dialog][data-state="open"]')}); if(performance.now()-start>1500)clearInterval(timer);}, 8);
    }, { selector: isDraft ? '[data-sales-draft-dialog]' : '[data-sales-action-dialog]' });
    await dialog.getByRole('button', { name: button, exact: true }).click();
    await page.waitForTimeout(50); await page.screenshot({ path: path.join(screenshotDir, `${action}-closing.png`) });
    await page.waitForTimeout(1350);
    const trace = await page.evaluate(() => window.__closeTrace); const exiting = trace.filter(x=>x.dialog==='closed');
    assert.ok(resolved, `${action} 未提交审批回执`); assert.ok(!trace.some(x => x.mask), `${action} 业务请求期间出现全局白色加载遮罩`); assert.ok(trace.some(x => x.dialog === 'gone' && !x.overlay), `${action} 弹窗或遮罩未关闭`);
    assert.ok(trace.every(x => !x.confirm), `${action} 意外出现二次确认`);
    assert.ok(trace.every(x => x.opacity === null || x.opacity > .99), `${action} 会话背景发生淡化: ${JSON.stringify(trace.filter(x => x.opacity !== null && x.opacity <= .99))}`);
    assert.ok(exiting.every(x=>x.dock && x.text), `${action} 退出期间会话发生变化: ${JSON.stringify(exiting)}`);
    assert.ok(runtimeRequests > before, `${action} 关闭后未重新连接`);
    const lastOpen = trace.filter(x => x.dialog !== 'gone').at(-1); assert.ok(!lastOpen || runtimeTimes.slice(before).every(t => t >= startTime + lastOpen.t - 20), `${action} 动画结束前已重连: ${JSON.stringify({runtimeTimes:runtimeTimes.slice(before).map(t=>t-startTime), exiting})}`);
    assert.deepEqual(errors, []); console.log(`${action} OK exitSamples=${exiting.length} runtimeRequests=${runtimeRequests-before}`);
  } finally { await page.close(); }
}
(async()=>{const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'});try{for (const action of ['CREATE', 'UPDATE', 'SUBMIT', 'CANCEL']) await check(browser,action);}finally{await browser.close();}})().catch(error=>{console.error(error);process.exitCode=1});
