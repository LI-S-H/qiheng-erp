const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { chromium, expect } = require('playwright/test');

// 独立 HTTP 服务真实分批发送 SSE；浏览器直接运行生产源码，不走业务 Mock 或付费模型。
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const conversations = [];
const histories = new Map();
const requests = [];
let nextConversation = 1960000000000000000n;
let nextMessage = 1963000000000000000n;
let historyReadCount = 0;
let interruptedCount = 0;
let failNextHistory = false;
let failNextDelete = false;
const time = () => new Date().toLocaleString('sv-SE');
const message = (role, content, charts = []) => ({ messageId: String(++nextMessage), role, content, createdAt: time(), charts, actionCards: [], sources: [], agentTraces: [], taskCard: null, workbench: null });
const chart = { chartId: 'fixture-chart', type: 'line', title: '最终消息图表', description: '仅用于验证保留的图表组件', xField: 'day', yFields: ['value'], fieldLabels: { value: '数量' }, yUnit: '件', nameField: null, valueField: null, data: [{ day: '周一', value: 2 }, { day: '周二', value: 3 }] };
const result = (response, data, status = 200) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ code: status === 200 ? 0 : status, message: status === 200 ? '成功' : data.message, data: status === 200 ? data : null })); };
const emptyAccess = { state: 'EMPTY' };
const dashboard = { refreshedAt: time(), pendingCount: 0, metrics: [], trend: [], todos: [], stockAlerts: [], orderStages: [], topProducts: [], supplierPerformance: [], orderStagePeriod: { type: 'CURRENT_CALENDAR_MONTH', startAt: '2026-10-01', endAtExclusive: '2026-11-01' }, access: { metrics: { MONTH_SALES: emptyAccess, MONTH_GROSS_PROFIT: emptyAccess, PENDING_ORDERS: emptyAccess, STOCK_RISK_SKU: emptyAccess }, todos: emptyAccess, stockAlerts: emptyAccess, orderStages: emptyAccess, topProducts: emptyAccess, supplierPerformance: emptyAccess } };

async function handleRequest(request, response) {
  const url = new URL(request.url, 'http://fixture');
  let raw = '';
  for await (const chunk of request) raw += chunk;
  const payload = raw ? JSON.parse(raw) : {};
  requests.push({ path: url.pathname, method: request.method, headers: request.headers, payload });
  if (url.pathname === '/auth/me') return result(response, { userId: '1900000000000000001', username: 'sse-tester', realName: '流式测试用户', deptId: '1900000000000000100', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: [] });
  if (url.pathname === '/dashboard/overview') return result(response, dashboard);
  if (url.pathname === '/ai/assistant/overview') return result(response, { refreshedAt: time(), quickPrompts: [], conversations });
  if (url.pathname === '/ai/assistant/conversations' && request.method === 'POST') {
    const conversation = { conversationId: String(++nextConversation), title: `基础会话 ${conversations.length + 1}`, description: '尚未开始对话', updatedAt: time() };
    conversations.unshift(conversation);
    histories.set(conversation.conversationId, []);
    return result(response, conversation);
  }
  const historyMatch = url.pathname.match(/^\/ai\/assistant\/conversations\/(\d+)\/messages$/);
  if (historyMatch) {
    historyReadCount += 1;
    if (failNextHistory) { failNextHistory = false; return result(response, { message: '测试历史同步失败' }, 503); }
    const records = histories.get(historyMatch[1]) || [];
    const pageNum = Number(url.searchParams.get('pageNum') || 1);
    const pageSize = Number(url.searchParams.get('pageSize') || 50);
    return result(response, { records: records.slice((pageNum - 1) * pageSize, pageNum * pageSize), pageNum, pageSize, total: records.length, hasNext: pageNum * pageSize < records.length });
  }
  const conversationMatch = url.pathname.match(/^\/ai\/assistant\/conversations\/(\d+)$/);
  if (conversationMatch && request.method === 'PATCH') {
    const conversation = conversations.find(item => item.conversationId === conversationMatch[1]);
    conversation.title = payload.title;
    return result(response, conversation);
  }
  if (conversationMatch && request.method === 'DELETE') {
    await sleep(600);
    if (failNextDelete) { failNextDelete = false; return result(response, { message: '测试删除失败，请重试' }, 503); }
    const index = conversations.findIndex(item => item.conversationId === conversationMatch[1]);
    if (index >= 0) conversations.splice(index, 1);
    histories.delete(conversationMatch[1]);
    return result(response, null);
  }
  if (url.pathname === '/ai/assistant/messages/stream' && request.method === 'POST') {
    if (payload.message === '服务未启用') return result(response, { message: '模型未配置，请联系管理员' }, 503);
    const history = histories.get(payload.conversationId);
    if (!history) return result(response, { message: '会话不存在' }, 404);
    history.push(message('user', payload.message));
    response.writeHead(200, { 'Content-Type': 'text/event-stream;charset=UTF-8', 'Cache-Control': 'no-cache' });
    response.flushHeaders();
    response.write(': heartbeat\r\n\r\ndata:\r\n\r\n');
    const requestId = `req-${history.length}`;
    const emit = (eventType, fields) => `event: ${eventType}\r\ndata: ${JSON.stringify({ eventType, requestId, conversationId: payload.conversationId, ...fields })}\r\n\r\n`;
    const timers = [];
    const later = (ms, action) => timers.push(setTimeout(() => { if (!response.destroyed && !response.writableEnded) action(); }, ms));
    response.once('close', () => {
      timers.forEach(clearTimeout);
      if (!response.writableEnded) interruptedCount += 1;
    });
    const first = Buffer.from(emit('MESSAGE_DELTA', { contentDelta: '第一段中文' }));
    const split = first.indexOf(Buffer.from('中')) + 1;
    response.write(first.subarray(0, split));
    later(35, () => response.write(first.subarray(split)));
    if (payload.message === '错误事件') {
      later(550, () => { response.write(emit('ERROR', { errorMessage: '测试服务临时不可用，请重试' })); response.end(); });
    } else if (payload.message === '缺少完成事件') {
      later(550, () => response.end());
    } else {
      const long = ['停止测试', '切会话测试', '离页测试'].includes(payload.message);
      later(long ? 4000 : 650, () => response.write(emit('MESSAGE_DELTA', { contentDelta: '，第二段' })));
      later(long ? 6000 : 1300, () => {
        const completed = message('assistant', `完整回复：${payload.message}`, payload.message === '基础发送测试' ? [chart] : []);
        history.push(completed);
        if (payload.message === '历史同步失败测试') failNextHistory = true;
        response.write(emit('MESSAGE_FINAL', { message: completed }));
        response.end();
      });
    }
    return;
  }
  return result(response, { message: `测试服务未定义 ${url.pathname}` }, 404);
}

async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function main() {
  const fixture = http.createServer((req, res) => { handleRequest(req, res).catch(error => { console.error(error); if (!res.headersSent) result(res, { message: error.message }, 500); else res.destroy(); }); });
  let vite;
  let browser;
  let output = '';
  try {
    await new Promise(resolve => fixture.listen(0, '127.0.0.1', resolve));
    const vitePort = await freePort();
    const baseURL = `http://127.0.0.1:${vitePort}`;
    vite = spawn(process.execPath, [path.resolve(__dirname, '../node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', String(vitePort), '--strictPort'], {
      cwd: path.resolve(__dirname, '..'), stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true,
      env: { ...process.env, VITE_PROXY_TARGET: `http://127.0.0.1:${fixture.address().port}`, VITE_USE_MOCK_API: 'false', VITE_USE_MOCK_AUTH: 'false' },
    });
    vite.stdout.on('data', data => { output += data.toString(); });
    vite.stderr.on('data', data => { output += data.toString(); });
    const started = Date.now();
    while (Date.now() - started < 20000) {
      if (await fetch(`${baseURL}/login`).then(response => response.ok).catch(() => false)) break;
      if (vite.exitCode !== null) throw new Error(`Vite 启动失败：${output}`);
      await sleep(100);
    }
    browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.addInitScript(() => { localStorage.setItem('erp_auth_token', 'fixture-auth'); localStorage.setItem('erp_auth_token_name', 'x-erp-fixture-session'); });
    await page.goto(`${baseURL}/ai/assistant`);
    const input = page.getByLabel('经营问题');
    await expect(page.getByText('创建会话后开始提问。')).toBeVisible();
    await expect(input).toBeDisabled();
    await expect(page.getByText('经营任务中心', { exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: '开始新会话', exact: true }).click();
    await expect(input).toBeEnabled();
    const firstRow = page.locator('.ai-conversation-row').first();
    await page.mouse.move(1300, 100);
    await input.focus();
    const rowBefore = await firstRow.boundingBox();
    const borderBefore = await firstRow.evaluate(element => getComputedStyle(element).borderColor);
    await firstRow.hover();
    await page.waitForTimeout(200);
    const rowAfter = await firstRow.boundingBox();
    assert.equal(rowAfter.y, rowBefore.y, '首条会话悬停时不得上浮裁切');
    assert.notEqual(await firstRow.evaluate(element => getComputedStyle(element).borderColor), borderBefore, '会话悬停必须有明显的边框变化');
    const firstId = conversations[0].conversationId;
    const beforeHistory = historyReadCount;
    const send = async text => { await input.fill(text); await input.press('Enter'); };
    const assistantText = page.locator('.ai-chat-message.is-assistant > .ai-chat-bubble > p');
    await send('基础发送测试');
    await expect(assistantText.last()).toHaveText('第一段中文');
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeEnabled();
    await expect(input).toBeDisabled();
    await expect(assistantText.last()).toHaveText('第一段中文，第二段');
    await expect(assistantText.last()).toHaveText('完整回复：基础发送测试');
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toHaveCount(0);
    await expect(page.locator('[data-message-id^="local-"]')).toHaveCount(0);
    await expect(page.locator('.ai-chart-card').first()).toBeVisible();
    assert.ok(historyReadCount > beforeHistory, '成功后必须回读持久化历史');
    await page.reload();
    await expect(assistantText.last()).toHaveText('完整回复：基础发送测试');
    await expect(input).toBeEnabled();
    await page.locator('.ai-row-actions button').first().click();
    await page.getByRole('menuitem', { name: '重命名', exact: true }).click();
    await page.locator('#renameTitle').fill('流式会话一');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.locator('.ai-conversation-row__main').filter({ hasText: '流式会话一' })).toBeVisible();

    await send('历史同步失败测试');
    await expect(assistantText.last()).toHaveText('完整回复：历史同步失败测试');
    await expect(page.locator('.ai-stream-error')).toContainText('回复已完成，但历史同步失败');
    await expect(input).toBeEnabled();
    await expect(input).toHaveValue('');
    await page.reload();
    await expect(assistantText.last()).toHaveText('完整回复：历史同步失败测试');
    await expect(input).toBeEnabled();

    for (const [text, expected] of [['错误事件', '测试服务临时不可用，请重试'], ['缺少完成事件', '未收到完整回复'], ['服务未启用', '模型未配置']]) {
      const reads = historyReadCount;
      await send(text);
      await expect(page.locator('.ai-stream-error')).toContainText(expected);
      await expect(input).toBeEnabled();
      await expect(input).toHaveValue(text);
      await expect(page.locator('[data-message-id^="local-"]')).toHaveCount(0);
      assert.ok(historyReadCount > reads, '失败后必须回读历史');
    }
    await send('停止测试');
    await expect(assistantText.last()).toHaveText('第一段中文');
    await page.getByRole('button', { name: '停止生成', exact: true }).click();
    await expect(page.locator('.ai-stream-error')).toContainText('生成已停止');
    await expect(input).toBeEnabled();
    await expect(input).toHaveValue('停止测试');
    await expect(page.locator('[data-message-id^="local-"]')).toHaveCount(0);

    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    await expect(input).toBeEnabled();
    const secondId = conversations[0].conversationId;
    const secondTitle = conversations[0].title;
    await page.waitForTimeout(300);
    const cards = page.locator('.ai-conversation-row');
    const topCard = await cards.nth(0).boundingBox();
    const nextCard = await cards.nth(1).boundingBox();
    assert.ok(nextCard.y - topCard.y - topCard.height >= 9, '会话之间必须保留 10px 间距');
    const gapPoint = { x: topCard.x + topCard.width / 2, y: topCard.y + topCard.height + 5 };
    const gapIsInteractive = await page.evaluate(({ x, y }) => Boolean(document.elementFromPoint(x, y)?.closest('button, a, [role="button"]')), gapPoint);
    assert.equal(gapIsInteractive, false, '会话间隙不得命中操作按钮');
    const readsBeforeGapClick = historyReadCount;
    await page.mouse.click(gapPoint.x, gapPoint.y);
    await expect(cards.nth(0)).toHaveClass(/is-active/);
    assert.equal(historyReadCount, readsBeforeGapClick, '点击间隙不得切换会话或请求历史');
    await expect(page.locator('[data-ai-fade-switch-content]')).toHaveCSS('user-select', 'none');
    const shadows = await cards.evaluateAll(elements => elements.map(element => getComputedStyle(element).boxShadow));
    assert.ok(shadows.every(shadow => shadow === 'none' || (shadow.includes('inset') && !shadow.includes(','))), '卡片只能有内部选中标记，不能有扩散到间隙的外阴影');
    const nextButton = await cards.nth(1).locator('.ai-conversation-row__main').boundingBox();
    assert.ok(Math.abs(nextButton.width - nextCard.width) <= 2 && Math.abs(nextButton.height - nextCard.height) <= 2, '会话按钮必须覆盖整个卡片内部');
    await cards.nth(1).click({ position: { x: 3, y: 3 } });
    await expect(cards.nth(1)).toHaveClass(/is-active/);
    await expect(input).toBeEnabled();
    await page.mouse.move(1300, 100);
    await input.focus();
    await cards.nth(0).hover();
    await expect(cards.nth(0)).toHaveCSS('background-color', 'rgb(242, 244, 247)');
    await cards.nth(0).locator('strong').click();
    await expect(cards.nth(0)).toHaveClass(/is-active/);
    await expect(input).toBeEnabled();
    await page.locator('.ai-conversation-row__main').filter({ hasText: '流式会话一' }).click();
    await expect(input).toBeEnabled();
    await send('切会话测试');
    await expect(assistantText.last()).toHaveText('第一段中文');
    await page.locator('.ai-conversation-row__main').filter({ hasText: secondTitle }).click();
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toHaveCount(0);
    await expect(page.locator('.ai-chat-message')).toHaveCount(0);
    await expect(page.getByText('暂无消息，可以开始提问。')).toBeVisible();
    await page.waitForTimeout(700);
    await expect(page.locator('.ai-chat-message')).toHaveCount(0);
    assert.equal(histories.get(secondId).length, 0, '其他会话消息不能写入当前会话');
    await send('离页测试');
    await expect(assistantText.last()).toHaveText('第一段中文');
    await page.reload();
    await expect(input).toBeEnabled();
    await expect(page.getByRole('button', { name: '停止生成', exact: true })).toHaveCount(0);
    assert.ok(interruptedCount >= 3, '停止/切会话/离页必须中断真实HTTP长连接');

    await page.locator('.ai-conversation-row__main').filter({ hasText: '流式会话一' }).click();
    await expect(input).toBeEnabled();
    let nativeDialogs = 0;
    page.on('dialog', async dialog => { nativeDialogs += 1; await dialog.dismiss(); });
    const openDelete = async () => {
      await page.locator('.ai-conversation-row').filter({ hasText: secondTitle }).locator('.ai-row-actions button').click();
      await expect(page.locator('.ai-conversation-row.is-active')).toContainText('流式会话一');
      await page.getByRole('menuitem', { name: '删除会话', exact: true }).click();
      await expect(page.locator('[data-confirm-dialog]')).toContainText(secondTitle);
      await page.waitForTimeout(200);
      const centered = await page.locator('[data-confirm-dialog]').evaluate(element => {
        const bounds = element.getBoundingClientRect();
        const sidebarWidth = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-shell-sidebar-width'));
        return { actual: bounds.left + bounds.width / 2, expected: (innerWidth + sidebarWidth) / 2 };
      });
      assert.ok(Math.abs(centered.actual - centered.expected) < 2, '删除弹窗必须在右侧操作区水平居中');
    };
    const deletesBefore = requests.filter(item => item.method === 'DELETE').length;
    await openDelete();
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await expect(page.locator('[data-confirm-dialog]')).toHaveCount(0);
    assert.equal(requests.filter(item => item.method === 'DELETE').length, deletesBefore, '取消不得发送删除请求');
    await openDelete();
    failNextDelete = true;
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    await expect(page.getByRole('button', { name: '处理中...', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '取消', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '删除会话', exact: true })).toBeEnabled();
    await expect(page.locator('[data-confirm-dialog]')).toBeVisible();
    await page.getByRole('button', { name: '删除会话', exact: true }).click();
    await expect(page.locator('.ai-conversation-row__main').filter({ hasText: secondTitle })).toHaveCount(0);
    await expect(page.locator('[data-confirm-dialog]')).toHaveCount(0);
    assert.equal(nativeDialogs, 0, '删除确认不得使用浏览器弹窗');
    assert.equal(requests.filter(item => item.method === 'DELETE').length, deletesBefore + 2, '失败重试每次只能发送一次删除请求');
    await expect(page.locator('.ai-conversation-row.is-active')).toContainText('流式会话一');
    assert.ok(histories.has(firstId));
    const streamRequests = requests.filter(item => item.path === '/ai/assistant/messages/stream');
    assert.ok(streamRequests.length >= 7);
    assert.ok(streamRequests.every(item => item.headers['x-erp-fixture-session'] === 'fixture-auth' && item.headers.accept === 'text/event-stream'));
    assert.ok(!requests.some(item => item.path === '/ai/scheduled-tasks' || item.path === '/ai/assistant/messages'));
    assert.deepEqual(errors, [], '浏览器不得产生未处理异常');
    await expect(page.locator('[data-sonner-toast]')).toHaveCount(0, { timeout: 10000 });

    const screenshots = path.resolve(process.env.QA_SCREENSHOT_DIR || path.join(__dirname, '../docs/qa-screenshots', `${new Date().toISOString().replace(/[:.]/g, '-')}-ai-sse`));
    fs.mkdirSync(screenshots, { recursive: true });
    await page.getByRole('button', { name: '折叠会话', exact: true }).click();
    const tooltip = page.locator('[data-slot="tooltip-content"]:not([data-state="closed"])');
    const collapsedToggle = page.getByRole('button', { name: '收起会话记录', exact: true });
    await collapsedToggle.hover();
    await expect(tooltip).toContainText('收起会话记录');
    await expect(collapsedToggle.locator('.lucide-chevrons-up')).toHaveCount(1);
    await collapsedToggle.click();
    await page.mouse.move(1300, 100);
    await page.getByRole('button', { name: '展开会话记录', exact: true }).hover();
    await expect(tooltip).toContainText('展开会话记录');
    await page.getByRole('button', { name: '展开会话记录', exact: true }).click();
    await page.waitForTimeout(350);
    await page.mouse.move(1300, 100);
    await page.locator('.ai-conversation-row__main').first().hover();
    await expect(tooltip).toContainText('流式会话一');
    await expect(tooltip).toContainText(conversations[0].updatedAt.slice(0, 16));
    await page.getByRole('button', { name: '新建会话', exact: true }).hover();
    await expect(tooltip).toContainText('新建会话');
    await page.getByRole('button', { name: '展开会话', exact: true }).hover();
    await expect(tooltip).toContainText('展开会话');
    await page.screenshot({ path: path.join(screenshots, 'ai-sidebar-collapsed.png'), fullPage: true });
    await page.getByRole('button', { name: '展开会话', exact: true }).click();
    for (const [width, height] of [[1440, 900], [1115, 838], [390, 844]]) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(250);
      const inputFont = await input.evaluate(element => getComputedStyle(element).fontSize);
      const messageFont = await page.locator('.ai-chat-bubble > p').first().evaluate(element => getComputedStyle(element).fontSize);
      assert.equal(inputFont, '14px');
      assert.equal(inputFont, messageFont, `视口 ${width} 输入框与聊天正文字号必须一致`);
      const layout = await page.locator('.ai-chat-stage').evaluate(stage => {
        const dock = stage.querySelector('[data-ai-composer-dock]');
        const scroll = stage.querySelector('.ai-chat-scroll');
        return { dockTop: dock.getBoundingClientRect().top, scrollBottom: scroll.getBoundingClientRect().bottom, dockBackground: getComputedStyle(dock).backgroundColor, horizontalOverflow: document.documentElement.scrollWidth > innerWidth + 1 };
      });
      assert.ok(Math.abs(layout.dockTop - layout.scrollBottom) < 2, '消息滚动区与输入底座必须相邻');
      assert.notEqual(layout.dockBackground, 'rgba(0, 0, 0, 0)');
      assert.equal(layout.horizontalOverflow, false, `视口 ${width} 不得横向溢出`);
      await page.screenshot({ path: path.join(screenshots, `ai-assistant-${width}x${height}.png`), fullPage: true });
    }
    await page.goto(`${baseURL}/ai/tasks`);
    await expect(page).toHaveURL(`${baseURL}/ai/assistant`);
    console.log(`SMOKE_AI_OK: 空会话/真实中文分块SSE/两次增量/最终替换/历史回读及同步失败/图表/改名删除/503与错误及截断/停止/切会话/离页/动态认证/任务入口/三视口布局；截图 ${screenshots}`);
  } finally {
    await browser?.close();
    if (vite && vite.exitCode === null) { const exit = new Promise(resolve => vite.once('exit', resolve)); vite.kill(); await Promise.race([exit, sleep(3000)]); }
    fixture.closeAllConnections();
    await new Promise(resolve => fixture.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
