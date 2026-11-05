const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const path = require('node:path');

// 受控事件测试页面状态和多选提交；真实模型链路由 test-mainagent-live.cjs 验证。
async function main() {
  const baseUrl = process.env.AI_UI_URL || 'http://127.0.0.1:5173';
  let server;
  try { await fetch(`${baseUrl}/login`); } catch {
    const url = new URL(baseUrl);
    server = spawn(process.execPath, [path.join(process.cwd(), 'node_modules/vite/bin/vite.js'), '--host', url.hostname, '--port', url.port || '5173', '--strictPort'], { cwd: process.cwd(), windowsHide: true, stdio: 'ignore' });
    let ready = false;
    for (let i = 0; i < 100; i++) {
      try { await fetch(`${baseUrl}/login`); ready = true; break; } catch { await new Promise(resolve => setTimeout(resolve, 200)); }
    }
    if (!ready) { server.kill(); throw new Error('测试前端服务启动失败'); }
  }
  let browser;
  try { browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' }); }
  catch (error) { if (server) server.kill(); throw error; }
  let page;
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = [];
    page.on('pageerror', error => { errors.push(error.message); console.error('页面错误：', error.message); });
    page.on('console', message => { if (message.type() === 'error') console.error('控制台：', message.text()); });
    page.on('response', response => { if (response.status() >= 400) console.error('请求失败：', response.status(), response.url()); });
    await page.addInitScript(() => {
      localStorage.setItem('erp_auth_token', 'test-token');
      localStorage.setItem('erp_auth_token_name', 'satoken');
      const original = window.fetch.bind(window);
      const encoder = new TextEncoder();
      window.fetch = async (...args) => {
        const reconnect = String(args[0]).includes('/events');
        if (!String(args[0]).includes('/messages/stream') && !reconnect) return original(...args);
        const stream = new ReadableStream({ start(controller) {
          window.__emitAi = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          window.__finishAi = () => { window.__emitAi('text_delta', { messageId: 'm2', delta: '已收到回答。' }); window.__emitAi('run_completed', { runId: 'r', status: 'COMPLETED' }); controller.close(); };
          window.__failAi = () => { window.__emitAi('run_failed', { runId: 'r', status: 'FAILED', message: '测试运行失败' }); controller.close(); };
          if (reconnect) {
            if (localStorage.getItem('qa_drop_snapshot') === 'yes') return;
            const waiting = localStorage.getItem('qa_waiting') === 'yes';
            const question = waiting ? { questionRequestId: 'q', toolCallId: 'c1', questions: [{ id: 'metrics', question: '分析哪些指标？', type: 'MULTIPLE_CHOICE', options: ['数量', '金额'] }] } : null;
            const sendSnapshot = () => window.__emitAi('run_snapshot', { runId: 'r', status: waiting ? 'WAITING_USER' : 'RUNNING', eventSeq: '3', question, items: [
              { type: 'text', id: 'm1', text: '先确认分析范围。', completed: true },
              { type: 'tool', id: 'c', label: '正在查询业务数据', status: 'running' },
              ...(waiting ? [{ type: 'clarification', id: 'c1', questionRequestId: 'q', questions: question.questions }] : []),
            ] });
            if (localStorage.getItem('qa_delay_snapshot') === 'yes') setTimeout(sendSnapshot, 1200);
            else sendSnapshot();
            return;
          }
          window.__emitAi('run_started', { runId: 'r', conversationId: '123' });
          window.__emitAi('text_delta', { messageId: 'm1', delta: '先确认分析范' });
          window.__emitAi('text_completed', { messageId: 'm1', text: '先确认分析范围。' });
          window.__emitAi('text_delta', { messageId: 'm1', delta: '范围。' });
        } });
        return new Response(stream, { headers: { 'content-type': 'text/event-stream' } });
      };
    });
    const user = { userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
    const today = new Date();
    const localDate = [today.getFullYear(), String(today.getMonth() + 1).padStart(2, '0'), String(today.getDate()).padStart(2, '0')].join('-');
    const conversation = { conversationId: '123', title: '测试会话', description: '', updatedAt: `${localDate} 10:00:00` };
    let answer;
    let answered = false;
    let renamed;
    let deleted = false;
    let paginationMode = false;
    let listRequests = 0;
    let reconnecting = false;
    let slowHistory = false;
    let slowCreate = false;
    let createCompleted = false;
    let nextCreatedId = null;
    let slowRuntime = false;
    await page.route('**/api/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const pathname = path.replace(/^\/api/, '');
      let data = null;
      if (pathname === '/auth/me') data = user;
      else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversation.updatedAt, quickPrompts: [], conversations: answered ? [conversation] : [] };
      else if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') {
        if (slowCreate) await new Promise(resolve => setTimeout(resolve, 800));
        createCompleted = true;
        data = nextCreatedId ? { ...conversation, conversationId: nextCreatedId, title: '新的测试会话' } : conversation;
      }
      else if (pathname === '/ai/assistant/conversations' && request.method() === 'GET') {
        listRequests += 1;
        if (paginationMode) {
          const next = new URL(request.url()).searchParams.has('beforeTime');
          const start = next ? 50 : 0;
          const size = next ? 3 : 50;
          data = { records: Array.from({ length: size }, (_, i) => ({ ...conversation, conversationId: String(2000 - start - i), title: `分页会话 ${start + i + 1}` })), hasMore: !next,
            nextBeforeTime: next ? null : conversation.updatedAt, nextBeforeId: next ? null : '1951' };
        } else data = { records: answered ? [conversation] : [], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
      }
      else if (pathname.endsWith('/runtime')) {
        if (slowRuntime) await new Promise(resolve => setTimeout(resolve, 900));
        data = reconnecting ? { runId: 'r', status: 'RUNNING' } : { status: 'IDLE' };
      }
      else if (pathname.endsWith('/messages')) {
        if (slowHistory) await new Promise(resolve => setTimeout(resolve, 1000));
        const record = (id, kind, role, payload) => ({ messageId: id, requestId: 'r', seq: id, role, kind, createdAt: conversation.updatedAt, content: { type: kind, data: payload } });
        const questions = [{ id: 'metrics', question: '分析哪些指标？', type: 'MULTIPLE_CHOICE', options: ['数量', '金额'] }];
        const records = answered ? [
          { ...record('0', 'USER_INPUT', 'user', {}), content: { type: 'TEXT', data: { text: '请分析数据' } } },
          record('1', 'TOOL_CALL', 'assistant', { callId: 'c1', arguments: JSON.stringify({ questions }) }),
          record('2', 'TOOL_RESULT', 'tool', { callId: 'c1', result: JSON.stringify({ answers: answer.answers, note: '补充说明' }) }),
          { ...record('3', 'ASSISTANT_STEP', 'assistant', {}), content: { type: 'TEXT', data: { text: '先确认查询范围。' } } },
          { ...record('4', 'ASSISTANT_STEP', 'assistant', {}), content: { type: 'TEXT', data: { text: '查询业务数据 · 2.5 秒' } } },
          { ...record('5', 'ASSISTANT_STEP', 'assistant', {}), content: { type: 'TEXT', data: { text: '接下来分析查询结果。' } } },
          { ...record('6', 'ASSISTANT_FINAL', 'assistant', {}), content: { type: 'TEXT', data: { text: '| 商品 | 数量 |\n|---|---|\n| A | 10 |' } } },
        ] : [{ ...record('1', 'USER_INPUT', 'user', {}), content: { type: 'TEXT', data: { text: '请分析数据' } } }];
        data = { records, pageNum: 1, pageSize: 50, total: records.length };
      }
      else if (pathname === '/ai/assistant/conversations/123' && request.method() === 'PATCH') {
        renamed = request.postDataJSON();
        conversation.title = renamed.title;
      }
      else if (pathname === '/ai/assistant/conversations/123' && request.method() === 'DELETE') deleted = true;
      else if (pathname === '/ai/assistant/conversations/123') data = conversation;
      else if (paginationMode && /^\/ai\/assistant\/conversations\/\d+$/.test(pathname)) {
        const id = pathname.split('/').at(-1);
        data = { ...conversation, conversationId: id, title: `分页会话 ${2001 - Number(id)}` };
      }
      else if (pathname.endsWith('/questions/q/answer')) {
        answer = request.postDataJSON();
        await new Promise(resolve => setTimeout(resolve, 500));
        answered = true;
        reconnecting = false;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data: null }) });
        await page.evaluate(() => window.__finishAi());
        return;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    });
    await page.goto(`${baseUrl}/ai/assistant`);
    // 从欢迎页创建新会话时，旧画面应保留到新画面可以展示，不能出现空白帧。
    await page.locator('.welcome-state').waitFor();
    slowCreate = true;
    createCompleted = false;
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.locator('.conversation-switching').waitFor();
    assert.equal(await page.locator('.welcome-state').isVisible(), true, '创建期间不能先隐藏欢迎内容');
    assert.equal(createCompleted, false, '接口未返回时必须保持切换状态');
    await page.waitForTimeout(200);
    const switchingOpacity = await page.locator('.conversation-switching').evaluate(el => Number(getComputedStyle(el).opacity));
    assert.ok(switchingOpacity >= 0.35 && switchingOpacity <= 0.45, '切换时内容应明显变淡');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'new-conversation-switch.png') });
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    assert.equal(createCompleted, true, '接口返回前不能结束过渡');
    slowCreate = false;
    // 即使接口立即返回，过渡期间也不能插入空白画面。
    const fastStart = Date.now();
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.locator('.conversation-switching').waitFor();
    assert.equal(await page.locator('.welcome-state').isVisible(), true, '快速创建期间应保留欢迎内容');
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    assert.ok(Date.now() - fastStart >= 250, '快速请求也要保持统一的最短过渡');
    await page.getByLabel('经营问题').fill('请分析数据');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.getByText('先确认分析范围。').waitFor();
    assert.equal(await page.getByText('先确认分析范围。', { exact: true }).count(), 1, '完整文本校正不能重复显示');
    await page.evaluate(() => window.__emitAi('tool_started', { runId: 'r', toolCallId: 'c', tool: 'query_sql_data', label: '正在查询业务数据' }));
    await page.locator('.agent-running-status').filter({ hasText: '正在查询业务数据' }).waitFor();
    assert.equal(await page.locator('.conversation-stream .agent-running-status').count(), 0, '会话底部不能重复显示运行状态');
    const runningTool = page.locator('.tool-progress.is-running').filter({ hasText: '正在查询业务数据' });
    await runningTool.waitFor();
    assert.notEqual(await runningTool.locator('.flow-text').evaluate(el => getComputedStyle(el).animationName), 'none', '运行工具应有流动提示');
    // 创建新会话时，旧会话尚未落库的流式内容也必须保留到切换完成。
    nextCreatedId = '124';
    slowCreate = true;
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.locator('.conversation-switching').waitFor();
    assert.equal(await page.getByText('先确认分析范围。', { exact: true }).isVisible(), true, '创建期间不能隐藏旧会话流式文字');
    assert.equal(await runningTool.isVisible(), true, '创建期间不能隐藏旧会话运行状态');
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    slowCreate = false;
    nextCreatedId = null;
    await page.locator('.conversation-sidebar').getByText('测试会话', { exact: true }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.locator('.tool-progress.is-running').filter({ hasText: '正在查询业务数据' }).waitFor();
    // 刷新后通过运行快照恢复已有文本和工具，不重新提交用户请求。
    reconnecting = true;
    await page.evaluate(() => localStorage.setItem('qa_delay_snapshot', 'yes'));
    await page.reload();
    await page.locator('.conversation-switching').waitFor();
    await page.waitForTimeout(450);
    assert.equal(await page.locator('.conversation-switching').count(), 1, '运行快照尚未到达时不能提前结束切换');
    await page.getByText('先确认分析范围。', { exact: true }).waitFor();
    await page.locator('.tool-progress.is-running').filter({ hasText: '正在查询业务数据' }).waitFor();
    await page.evaluate(() => localStorage.removeItem('qa_delay_snapshot'));
    assert.equal(await page.getByText('NaN 秒').count(), 0, '运行快照没有耗时时不能显示 NaN');
    assert.equal(await page.getByText('正在提交', { exact: false }).count(), 0, '消息保存后不能仍显示正在提交');
    assert.equal(await page.getByText('先确认分析范围。', { exact: true }).count(), 1, '恢复后不应重复显示历史文本');
    console.log('AI_RECONNECT_SNAPSHOT_OK');
    // 连接存在但快照永远不来时，应提示失败并允许用户继续操作。
    await page.evaluate(() => localStorage.setItem('qa_drop_snapshot', 'yes'));
    await page.reload();
    await page.getByText('运行快照读取超时，请重新进入会话重试。').waitFor({ timeout: 20000 });
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.evaluate(() => localStorage.removeItem('qa_drop_snapshot'));
    await page.reload();
    await page.locator('.tool-progress.is-running').filter({ hasText: '正在查询业务数据' }).waitFor();
    // 切离业务页面后返回，原来的流式状态仍应继续展示。
    await page.locator('.app-sidebar').getByText('工作台', { exact: true }).click();
    await page.waitForURL('**/dashboard');
    await page.locator('.app-sidebar').getByText('智能经营助手', { exact: true }).click();
    await page.waitForURL('**/ai/assistant');
    await page.locator('.agent-running-status').filter({ hasText: '正在查询业务数据' }).waitFor();
    await page.evaluate(() => window.__emitAi('tool_finished', { runId: 'r', eventSeq: '4', toolCallId: 'c', status: 'returned', durationMs: '1500' }));
    await page.locator('.tool-progress:not(.is-running)').filter({ hasText: '查询业务数据' }).getByText('1.5 秒').waitFor();
    await page.evaluate(() => window.__emitAi('tool_started', { runId: 'r', eventSeq: '4', toolCallId: 'duplicate', label: '重复事件' }));
    assert.equal(await page.getByText('重复事件').count(), 0, '字符串序号也必须参与事件去重');
    await page.evaluate(() => window.__emitAi('tool_started', { runId: 'r', eventSeq: '5', toolCallId: 'invalid', label: '正在查询业务数据' }));
    await page.evaluate(() => window.__emitAi('tool_finished', { runId: 'r', eventSeq: '6', toolCallId: 'invalid', status: 'invalid_arguments', label: '参数错误，正在调整', durationMs: '12' }));
    await page.locator('.tool-progress:not(.is-running)').filter({ hasText: '参数错误，正在调整' }).waitFor();
    await page.locator('.agent-running-status').filter({ hasText: '正在思考' }).waitFor();
    await page.locator('.tool-progress.is-running').filter({ hasText: '正在思考' }).waitFor();
    await page.evaluate(() => window.__emitAi('question', { runId: 'r', questionRequestId: 'q', toolCallId: 'c1', questions: [{ id: 'metrics', question: '分析哪些指标？', type: 'MULTIPLE_CHOICE', options: ['数量', '金额'] }] }));
    await page.locator('[data-testid="clarification-form"]').waitFor();
    const liveQuestion = page.locator('.message').filter({ hasText: '先确认分析范围。' });
    assert.equal(await liveQuestion.count(), 1, '追问应与本轮流式内容位于同一助手消息');
    assert.equal(await liveQuestion.getByTestId('clarification-history').count(), 1, '流式内容中应有且只有一个追问入口');
    assert.equal(await liveQuestion.getByText('查看问题与回答', { exact: true }).count(), 0, '不应重复显示追问入口');
    assert.equal(await liveQuestion.evaluate(element => {
      const text = [...element.querySelectorAll('p')].find(item => item.textContent.includes('先确认分析范围。'));
      const question = element.querySelector('[data-testid="clarification-history"]');
      return Boolean(text && question && (text.compareDocumentPosition(question) & Node.DOCUMENT_POSITION_FOLLOWING));
    }), true, '追问应保留在说明文字之后');
    await page.evaluate(() => localStorage.setItem('qa_waiting', 'yes'));
    await page.reload();
    const form = page.locator('[data-testid="clarification-form"]');
    await form.waitFor();
    assert.equal(await page.locator('.message').filter({ hasText: '先确认分析范围。' }).getByTestId('clarification-history').count(), 1, '重连后追问应保持在同一条工作过程中');
    console.log('AI_RECONNECT_QUESTION_OK');
    await form.getByRole('checkbox', { name: '数量' }).click();
    await form.getByRole('checkbox', { name: '金额' }).click();
    await form.getByRole('button', { name: '确认回答' }).click();
    slowHistory = true;
    const backgroundHistory = page.waitForRequest(request => new URL(request.url()).pathname.endsWith('/messages'));
    await form.getByRole('button', { name: '提交回答' }).click();
    assert.equal(await page.locator('[data-page-loading]').count(), 0, '提交追问时不能出现全局页面遮罩');
    await backgroundHistory;
    assert.equal(await page.locator('[data-ai-conversation-region]').getAttribute('aria-busy'), 'false', '同会话后台同步不能显示整区加载遮罩');
    slowHistory = false;
    await page.waitForFunction(() => !document.querySelector('[data-testid="clarification-form"]'));
    assert.deepEqual(answer.answers[0].selectedOptions, ['数量', '金额']);
    await page.reload();
    await page.locator('.conversation-sidebar').getByText('测试会话', { exact: true }).click();
    const work = page.locator('.work-steps');
    const toggle = work.getByRole('button', { name: /查看工作过程|收起工作过程/ });
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    await toggle.click();
    const history = work.getByTestId('clarification-history');
    await history.getByText('补充信息', { exact: true }).waitFor();
    await history.locator('summary').click();
    await history.getByText('分析哪些指标？').waitFor();
    await history.getByText('数量、金额').waitFor();
    await history.getByText('补充说明').waitFor();
    assert.ok(await history.locator('.history-answer').evaluate(el => el.getBoundingClientRect().height < 40), '简短答案不应保留输入框高度');
    await toggle.click();
    await page.waitForTimeout(260);
    assert.equal(await work.locator(':scope > .work-tool').count(), 0, '收起时不应在工作过程外重复展示工具调用');
    assert.ok((await work.locator('.work-reveal').boundingBox()).height < 1, '收起时工作过程不应占用高度');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'work-collapsed.png') });
    const animationHeights = await toggle.evaluate(button => new Promise(resolve => {
      const reveal = button.parentElement.querySelector('.work-reveal');
      const heights = [reveal.getBoundingClientRect().height];
      button.click();
      const start = performance.now();
      function sample(now) {
        heights.push(reveal.getBoundingClientRect().height);
        if (now - start < 300) requestAnimationFrame(sample);
        else resolve(heights);
      }
      requestAnimationFrame(sample);
    }));
    assert.ok(animationHeights.some(height => height > 0) && animationHeights.every((height, index) => index === 0 || height + 1 >= animationHeights[index - 1]), '展开时高度应平滑递增');
    await work.getByText('查询业务数据', { exact: true }).waitFor();
    await work.getByText('2.5 秒', { exact: true }).waitFor();
    assert.equal(await work.locator(':scope > .work-tool').count(), 0, '展开后不能重复展示工具摘要');
    const orderedWork = await work.locator('.work-reveal-inner').innerText();
    const positions = ['先确认查询范围。', '查询业务数据', '接下来分析查询结果。'].map(text => orderedWork.indexOf(text));
    assert.ok(positions.every(position => position >= 0) && positions[0] < positions[1] && positions[1] < positions[2], '工作过程文字和工具调用应保持原始顺序');
    assert.equal(await work.getByRole('button', { name: '收起工作过程' }).getAttribute('aria-expanded'), 'true');
    const reveal = work.locator('.work-reveal');
    const transition = await reveal.evaluate(el => getComputedStyle(el).transitionProperty);
    assert.match(transition, /grid-template-rows/, '工作过程应有高度过渡');
    await page.waitForTimeout(260);
    assert.ok((await reveal.boundingBox()).height > 0, '展开后应能看到工作内容');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'work-expanded.png') });
    const tableFont = await page.locator('.ai-markdown-table table').evaluate(el => getComputedStyle(el).fontSize);
    assert.ok(parseFloat(tableFont) <= 14, `表格字体过大: ${tableFont}`);
    await work.getByRole('button', { name: '收起工作过程' }).click();
    assert.equal(await work.getByRole('button', { name: '查看工作过程' }).getAttribute('aria-expanded'), 'false');
    await page.waitForTimeout(260);
    assert.ok((await reveal.boundingBox()).height < 1, '收起后不应保留内容占位');
    const marker = page.locator('.turn-marker').first();
    await marker.waitFor();
    const before = await marker.locator('span').evaluate(el => el.getBoundingClientRect().width);
    await marker.hover();
    await page.waitForTimeout(250);
    const hover = await marker.locator('span').evaluate(el => el.getBoundingClientRect().width);
    const markerStyle = await marker.evaluate(el => ({ background: getComputedStyle(el).backgroundColor, shadow: getComputedStyle(el).boxShadow }));
    assert.ok(hover > before, '悬停时跳转线应变长');
    assert.equal(markerStyle.background, 'rgba(0, 0, 0, 0)', '悬停不应出现背景框');
    assert.equal(markerStyle.shadow, 'none', '悬停不应出现边框');
    await page.locator('.ai-turn-preview').waitFor();
    await marker.click();
    assert.deepEqual(await marker.getAttribute('aria-current'), 'step');
    const row = page.locator('.conversation-row').filter({ hasText: '测试会话' });
    await row.hover();
    await row.getByRole('button', { name: '重命名会话' }).click();
    const rename = page.locator('[data-conversation-rename-dialog]');
    await rename.waitFor();
    const assertCentered = async dialog => {
      const dialogBox = await dialog.boundingBox();
      const panelBox = await page.locator('[data-testid="ai-graph-workspace"]').boundingBox();
      assert.ok(Math.abs(dialogBox.x + dialogBox.width / 2 - panelBox.x - panelBox.width / 2) < 2, '弹窗应水平居中于会话列表和聊天区的整体');
      assert.ok(Math.abs(dialogBox.y + dialogBox.height / 2 - page.viewportSize().height / 2) < 2, '弹窗应垂直居中于窗口');
    };
    await assertCentered(rename);
    if (process.env.AI_QA_SCREENSHOT_DIR) { await page.waitForTimeout(250); await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'rename-1440.png') }); }
    await page.setViewportSize({ width: 1115, height: 838 });
    await page.waitForTimeout(250);
    await assertCentered(rename);
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'rename-1115.png') });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(250);
    await assertCentered(rename);
    const nameInput = rename.getByLabel('会话名称');
    assert.equal(await nameInput.inputValue(), '测试会话');
    await nameInput.fill('  ');
    await rename.getByRole('button', { name: '保存' }).click();
    await rename.getByRole('alert').getByText('请输入会话名称').waitFor();
    await page.keyboard.press('Escape');
    await rename.waitFor({ state: 'hidden' });
    assert.equal(renamed, undefined, '取消重命名不能提交请求');
    await row.hover();
    await row.getByRole('button', { name: '重命名会话' }).click();
    await rename.waitFor();
    await nameInput.fill('库存分析');
    await rename.getByRole('button', { name: '保存' }).click();
    await page.locator('.conversation-sidebar').getByText('库存分析', { exact: true }).waitFor();
    assert.deepEqual(renamed, { title: '库存分析' });
    const renamedRow = page.locator('.conversation-row').filter({ hasText: '库存分析' });
    await renamedRow.hover();
    await renamedRow.getByRole('button', { name: '删除会话' }).click();
    const confirm = page.locator('[data-confirm-dialog]');
    await confirm.waitFor();
    await assertCentered(confirm);
    if (process.env.AI_QA_SCREENSHOT_DIR) { await page.waitForTimeout(250); await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'delete-1440.png') }); }
    await confirm.getByRole('button', { name: '取消' }).click();
    assert.equal(deleted, false, '取消删除不能提交请求');
    await page.evaluate(() => { window.__failAi = undefined; });
    await page.getByLabel('经营问题').fill('检查错误提示位置');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__failAi === 'function');
    await page.evaluate(() => window.__failAi());
    const requestError = page.getByTestId('graph-request-error');
    await requestError.waitFor();
    assert.equal(await requestError.evaluate(el => !el.nextElementSibling), true, '运行错误应位于消息流末尾');
    assert.equal(await page.evaluate(() => {
      const scroll = document.querySelector('.conversation-scroll');
      const alert = document.querySelector('[data-testid="graph-request-error"]');
      return !!scroll && !!alert && alert.getBoundingClientRect().bottom <= scroll.getBoundingClientRect().bottom + 1;
    }), true, '运行错误出现时应滚动到可见位置');
    if (process.env.AI_QA_SCREENSHOT_DIR) {
      await page.waitForTimeout(500);
      await requestError.waitFor();
      await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'error-at-bottom.png') });
    }
    paginationMode = true;
    listRequests = 0;
    // 分页数据集与前面的会话无关，避免恢复上一场景的活动会话。
    await page.evaluate(() => localStorage.removeItem('erp.ai.draft.v2:1:new:activeConversation:'));
    await page.reload();
    const rows = page.locator('.conversation-sidebar .conversation-row');
    await page.locator('.conversation-sidebar').getByText('分页会话 1', { exact: true }).waitFor();
    assert.equal(await rows.count(), 5, '分组默认只显示 5 条');
    for (let i = 0; i < 9; i++) await page.getByRole('button', { name: '显示更多' }).click();
    assert.equal(await rows.count(), 50, '本地每次展开 5 条');
    assert.equal(listRequests, 1, '本地数据未用完时不请求下一批');
    await page.getByRole('button', { name: '显示更多' }).click();
    await page.locator('.conversation-sidebar').getByText('分页会话 53', { exact: true }).waitFor();
    assert.equal(await rows.count(), 53, '游标续页后显示下一批');
    assert.equal(listRequests, 2);
    // 慢速读取时从第二条切回今天第一条，切换期间不出现白色加载遮罩。
    slowHistory = true;
    await page.locator('.conversation-sidebar').getByText('分页会话 2', { exact: true }).click();
    await page.locator('.workspace-title h1').getByText('分页会话 2').waitFor();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    slowRuntime = true;
    await page.evaluate(() => {
      window.__switchMaskSeen = false;
      window.__switchMaskObserver = new MutationObserver(() => {
        if (document.querySelector('[data-page-loading]')) window.__switchMaskSeen = true;
      });
      window.__switchMaskObserver.observe(document.body, { childList: true, subtree: true });
    });
    const runtimeRequest = page.waitForRequest(request => request.url().includes('/conversations/2000/runtime'));
    await page.locator('.conversation-sidebar').getByText('分页会话 1', { exact: true }).click();
    await page.locator('.conversation-switching').waitFor();
    await runtimeRequest;
    await page.waitForTimeout(100);
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'switch-runtime.png') });
    assert.equal(await page.locator('[data-page-loading]').count(), 0, '切换首条会话不能出现全局遮罩');
    assert.equal(await page.locator('.conversation-body [role="status"]').filter({ hasText: '正在读取会话' }).count(), 0, '切换首条会话不能出现读取遮罩');
    await page.locator('.workspace-title h1').getByText('分页会话 1').waitFor();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    assert.equal(await page.evaluate(() => { window.__switchMaskObserver.disconnect(); return window.__switchMaskSeen; }), false, '切换全过程不能出现全局遮罩');
    slowHistory = false;
    slowRuntime = false;
    slowCreate = true;
    await page.getByRole('button', { name: '新建会话' }).click();
    await page.locator('.conversation-switching').waitFor();
    assert.equal(await page.locator('[data-page-loading]').count(), 0, '新建会话不能出现全局遮罩');
    assert.equal(await page.locator('.conversation-body [role="status"]').filter({ hasText: '正在读取会话' }).count(), 0, '新建会话不能出现读取遮罩');
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    slowCreate = false;
    assert.deepEqual(errors, []);
    console.log('AI_MAINAGENT_UI_OK');
  } catch (error) {
    if (page) console.error('页面状态：', page.url(), (await page.locator('body').innerText()).slice(0, 1000));
    throw error;
  } finally { await browser.close(); if (server) server.kill(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
