const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('playwright/test');

// 挂起各会话的 SSE 响应，验证真实页面在后台运行期间的隔离；不调用模型。
const ids = ['1960000000000000101', '1960000000000000102', '1960000000000000103'];
const names = ['并行会话 A', '并行会话 B', '新建并行会话'];
const user = { userId: '1900000000000000001', username: 'qa', realName: '并发联调',
  deptId: '1900000000000000100', deptName: '行政部', isAdmin: true,
  roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'], lastLoginAt: '2026-10-01 10:00:00' };
const event = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const rows = new Map(ids.slice(0, 2).map((id, index) => [id, { conversationId: id, title: names[index],
  description: '', updatedAt: '2026-10-01 10:00:00', running: false, runStatus: 'IDLE', runNode: null, runLabel: null }]));
const histories = new Map(ids.map(id => [id, []]));
const pending = new Map();
const calls = [];
let creates = 0;
let disconnectProgress = 0;
let delayedDetail = null;
const append = (id, role, content) => {
  const history = histories.get(id);
  history.push({ messageId: String(BigInt(id) + BigInt(history.length + 100)), requestId: `request-${history.length}`,
    seq: String(history.length + 1), role, kind: role === 'user' ? 'USER_INPUT' : 'ASSISTANT_FINAL', content,
    createdAt: '2026-10-23 10:00:00' });
};
function finish(id, pause = false) {
  const row = rows.get(id);
  row.running = false;
  row.runStatus = pause ? 'WAITING_HUMAN' : 'NEEDS_READY';
  const content = pause ? { type: 'CLARIFICATION', data: { text: '请确认范围', threadId: id, pauseId: `pause-${id}`,
    questions: [{ id: 'scope', question: '选择分析范围', options: ['全部仓库', '一号仓库', '二号仓库'] }] } }
    : { type: 'NEEDS', data: { text: `${row.title} 已完成需求梳理`, threadId: id,
      needs: [{ id: 'N1', intent: 'DATA', need: `${row.title} 专属需求`, depends: [], status: 'PENDING',
        evidence_plan: [{ id: 'N1-E1', label: '库存数据', instruction: '查询库存', source: 'SQL', depends: [] }] }] } };
  // 故意不保存澄清卡：切回后仍须能使用事件中的恢复锚点。
  if (!pause) append(id, 'assistant', content);
  pending.get(id).resolve(event('node-start', { node: 'planner', label: '需求规划' }) +
    (pause ? event('clarification_required', { checkpointId: 'checkpoint', content }) :
      event('artifact', { type: 'NEEDS', checkpointId: 'checkpoint', content }) + event('run_finished', { status: 'NEEDS_READY' })));
  pending.delete(id);
}

async function main() {
  const out = path.resolve(__dirname, '../../docs/qa-screenshots', `${new Date().toISOString().replace(/[:.]/g, '-')}-ai-parallel`);
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH ||
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await context.addInitScript(() => { localStorage.setItem('erp_auth_token', 'controlled-parallel'); localStorage.setItem('erp_auth_token_name', 'x-erp-session'); });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.clock.install({ time: new Date('2026-10-23T10:00:00+08:00') });
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    const json = data => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    if (pathname === '/auth/me') return json(user);
    if (pathname === '/ai/assistant/overview') return json({ refreshedAt: '2026-10-23 10:00:00', quickPrompts: [], conversations: [...rows.values()] });
    if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') {
      creates++;
      const row = { conversationId: ids[2], title: names[2], description: '', updatedAt: '2026-10-23 10:00:00',
        running: false, runStatus: 'IDLE', runNode: null, runLabel: null };
      rows.set(ids[2], row);
      return json(row);
    }
    const match = /^\/ai\/assistant\/conversations\/(\d+)(\/messages)?$/.exec(pathname);
    if (match) {
      if (match[2]) return json({ records: histories.get(match[1]), total: histories.get(match[1]).length, pageNum: 1, pageSize: 50 });
      const snapshot = { ...rows.get(match[1]) };
      if (delayedDetail?.id === match[1]) {
        const gate = delayedDetail;
        gate.requested.resolve();
        await gate.release.promise;
      }
      return json(snapshot);
    }
    const progress = /^\/ai\/assistant\/conversations\/(\d+)\/progress\/stream$/.exec(pathname);
    if (progress) {
      disconnectProgress++;
      if (disconnectProgress === 1) return route.fulfill({ contentType: 'text/event-stream', body: '' });
      const gate = deferred();
      pending.set(progress[1], gate);
      return route.fulfill({ contentType: 'text/event-stream', body: await gate.promise });
    }
    if (pathname === '/ai/graph/stream' || pathname === '/ai/graph/resume/stream') {
      const payload = request.postDataJSON();
      const id = payload.conversationId || payload.threadId;
      calls.push({ pathname, ...payload });
      rows.get(id).running = true;
      rows.get(id).runStatus = 'RUNNING';
      rows.get(id).updatedAt = '2026-10-23 10:00:00';
      append(id, 'user', { type: 'TEXT', data: { text: payload.query || payload.answer } });
      if (payload.query === '模拟断流') return route.fulfill({ contentType: 'text/event-stream', body: '' });
      const gate = deferred();
      pending.set(id, gate);
      return route.fulfill({ contentType: 'text/event-stream', body: await gate.promise });
    }
    return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 403, message: '受控场景未开放', data: null }) });
  });
  const side = page.locator('.graph-workspace > .conversation-sidebar');
  const input = page.getByRole('textbox', { name: '经营问题', exact: true });
  const send = page.getByRole('button', { name: '发送需求', exact: true });
  const select = async index => {
    await side.locator('.conversation-main').filter({ hasText: names[index] }).click();
    await expect(page.locator('[data-page-loading]')).toBeHidden();
    await expect(side.locator('.conversation-row.is-active .conversation-title')).toHaveText(names[index]);
  };
  try {
    await page.goto(`${process.env.AI_GRAPH_UI_URL || 'http://127.0.0.1:5173'}/ai/assistant`);
    await expect(input).toBeEnabled();
    await select(0);
    await input.fill('请分析 A 库存');
    await send.click();
    await expect.poll(() => pending.has(ids[0])).toBe(true);
    await expect(side.locator('.conversation-row.is-active .conversation-running')).toBeVisible();
    await expect(side.locator('.conversation-group').filter({ has: page.getByText('今天', { exact: true }) })
      .locator('.conversation-title').filter({ hasText: names[0] })).toBeVisible();
    await side.getByRole('button', { name: '新建会话', exact: true }).click();
    await expect(input).toBeEnabled();
    assert.equal(creates, 1, 'A 运行时仍可新建会话');
    // 更早分组在创建后仍保留原展开状态。
    await select(1);
    await input.fill('请分析 B 销量');
    await send.click();
    await expect.poll(() => pending.size).toBe(2);
    finish(ids[0]);
    await expect(side.locator('.conversation-running')).toHaveCount(1);
    await expect(side.locator('.conversation-row.is-active .conversation-title')).toHaveText(names[1]);
    await expect(input).toBeDisabled();
    await expect(page.locator('.conversation-scroll')).not.toContainText(`${names[0]} 专属需求`);
    await select(0);
    await expect(input).toBeEnabled();
    await expect(page.getByText(`${names[0]} 已完成需求梳理`, { exact: true })).toBeVisible();
    assert.equal(calls.length, 2, '切回运行会话不会重复启动');
    await select(1);
    await expect(input).toBeDisabled();
    finish(ids[1], true);
    await expect(page.getByRole('radio', { name: '全部仓库', exact: true })).toBeVisible();
    await select(0);
    await select(1);
    await expect(page.getByRole('radio', { name: '全部仓库', exact: true })).toBeVisible();
    await page.getByRole('radio', { name: '一号仓库', exact: true }).click();
    await expect(page.getByRole('heading', { name: '确认回答', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '提交回答', exact: true }).click();
    await expect.poll(() => pending.has(ids[1])).toBe(true);
    assert.equal(calls.at(-1).pauseId, `pause-${ids[1]}`);
    await select(0);
    await input.fill('A 的未提交草稿');
    finish(ids[1]);
    await expect(side.locator('.conversation-running')).toHaveCount(0);
    await expect(input).toHaveValue('A 的未提交草稿');
    await select(1);
    await expect(page.getByText(`${names[1]} 已完成需求梳理`, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '已拆解 1 条需求 查看需求与待取证据' }).click();
    await expect(page.getByText(`${names[1]} 专属需求`, { exact: true }).first()).toBeVisible();
    // 订阅断开不代表后端停止：禁发重复执行，通过重新读取恢复进度订阅。
    await select(2);
    await input.fill('模拟断流');
    await send.click();
    await expect(page.getByTestId('graph-request-error')).toContainText('进度连接中断');
    await expect(input).toBeDisabled();
    const startCount = calls.length;
    await page.getByRole('button', { name: '重新读取会话', exact: true }).click();
    await expect.poll(() => pending.has(ids[2])).toBe(true);
    finish(ids[2]);
    await expect(input).toBeEnabled();
    await expect(input).toHaveValue('');
    assert.equal(calls.length, startCount, '断流恢复只订阅，不重复执行');
    assert.equal(disconnectProgress, 2);
    // 切回的详情已取到旧 RUNNING；终态在切换锁期间到达，必须补读新历史。
    await select(0);
    await input.fill('验证切换收尾竞态');
    await send.click();
    await expect.poll(() => pending.has(ids[0])).toBe(true);
    await select(1);
    delayedDetail = { id: ids[0], requested: deferred(), release: deferred() };
    await side.locator('.conversation-main').filter({ hasText: names[0] }).click();
    await delayedDetail.requested.promise;
    finish(ids[0]);
    await expect.poll(() => pending.has(ids[0])).toBe(false);
    delayedDetail.release.resolve();
    delayedDetail = null;
    await expect(input).toBeEnabled();
    await expect(page.locator('.conversation-scroll').getByText(`${names[0]} 已完成需求梳理`, { exact: true })).toHaveCount(2);
    await page.screenshot({ path: path.join(out, 'parallel-complete.png'), fullPage: true });
    assert.deepEqual(errors, []);
    console.log(`AI_CONVERSATION_PARALLEL_PASSED: 并行发送、运行时新建切换、后台完成不串线、即时日期、未落库澄清恢复、草稿隔离、断流安全重订阅、切换终态竞态\n${out}`);
  } catch (error) {
    console.error('BROWSER_DIAGNOSTICS', errors, page.url(), (await page.locator('body').innerText()).slice(0, 3000));
    await page.screenshot({ path: path.join(out, 'failed.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    for (const gate of pending.values()) gate.resolve('');
    await browser.close();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
