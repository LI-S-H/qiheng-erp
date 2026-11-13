const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const path = require('node:path');

// 分析子智能体过程面板：宽屏独立面板、窄屏折叠卡片、历史回看与实时追加。
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
    await page.addInitScript(() => {
      localStorage.setItem('erp_auth_token', 'test-token');
      localStorage.setItem('erp_auth_token_name', 'satoken');
      const original = window.fetch.bind(window);
      const encoder = new TextEncoder();
      window.fetch = async (...args) => {
        if (!String(args[0]).includes('/messages/stream') && !String(args[0]).includes('/events')) return original(...args);
        const stream = new ReadableStream({ start(controller) {
          window.__analysisEmit = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          window.__analysisFinish = () => {
            window.__analysisEmit('run_completed', { runId: 'run-live', status: 'COMPLETED' });
            controller.close();
          };
        } });
        return new Response(stream, { headers: { 'content-type': 'text/event-stream' } });
      };
    });
    const user = { userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
    const updatedAt = '2026-11-10 10:00:00';
    const conversation = { conversationId: '300', title: '分析会话', description: '', updatedAt };
    await page.route('**/api/**', async route => {
      const request = route.request();
      const url = new URL(request.url());
      if (!url.pathname.startsWith('/api/')) return route.continue();
      const pathname = url.pathname.replace(/^\/api/, '');
      let data = null;
      if (pathname === '/auth/me') data = user;
      else if (pathname === '/ai/assistant/overview') data = { refreshedAt: updatedAt, quickPrompts: [], conversations: [conversation] };
      else if (pathname === '/ai/assistant/conversations' && request.method() === 'GET') data = { records: [conversation], hasMore: false, nextBeforeTime: null, nextBeforeId: null };
      else if (pathname === '/ai/assistant/conversations/300') data = conversation;
      else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
      else if (pathname.endsWith('/messages')) {
        const record = (id, kind, role, content) => ({ messageId: id, requestId: 'run-old', seq: id, role, kind, createdAt: updatedAt, content });
        const records = [
          record('1', 'USER_INPUT', 'user', { type: 'TEXT', data: { text: '上次的库存分析' } }),
          record('2', 'ANALYSIS_STEP', 'assistant', { type: 'ANALYSIS', data: {
            toolCallId: 'call-old', goal: '盘点各仓库库存总量', completion: 'COMPLETED', summary: '一号仓库存充足。',
            steps: [{ text: '先看当前会话已有数据', tools: [{ name: 'listDatasets', label: '查看数据集' }] }],
          } }),
          record('3', 'ASSISTANT_FINAL', 'assistant', { type: 'TEXT', data: { text: '上次分析已完成。' } }),
        ];
        data = { records, pageNum: 1, pageSize: 50, total: records.length };
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    });
    await page.goto(`${baseUrl}/ai/assistant`);
    const panel = page.getByTestId('ai-analysis-panel');
    const dock = page.getByTestId('ai-analysis-dock');
    const dockWidth = () => dock.evaluate(element => element.getBoundingClientRect().width);
    // 面板默认收起：历史分析存在也不自动弹出。
    await page.getByText('上次分析已完成。', { exact: true }).waitFor();
    assert.ok(await dockWidth() < 1, '分析面板默认不显示');
    // 从历史工作过程里点击“子智能体分析”打开面板。
    await page.getByRole('button', { name: '查看工作过程' }).click();
    const historyEntry = page.locator('.work-analysis');
    await historyEntry.waitFor();
    await historyEntry.click();
    await panel.getByText('盘点各仓库库存总量', { exact: true }).waitFor();
    await page.waitForTimeout(400);
    assert.ok(await dockWidth() > 200, '点击后展开右侧面板');
    assert.equal(await panel.getByText('查看数据集', { exact: true }).count(), 1, '历史分析步骤应保留工具文案');
    assert.equal(await panel.getByText('一号仓库存充足。', { exact: true }).count(), 1, '历史分析应展示结论');
    assert.equal(await page.getByTestId('ai-analysis-card').count(), 0, '宽屏使用独立面板而不是内联卡片');
    // 面板可以收回，并播放收放过渡。
    await panel.getByRole('button', { name: '收起分析面板' }).click();
    await page.waitForTimeout(400);
    assert.ok(await dockWidth() < 1, '关闭后右侧面板收回');
    assert.notEqual(await dock.evaluate(element => getComputedStyle(element).transitionDuration), '0s', '关闭应带过渡动画');

    await page.getByLabel('经营问题').fill('再分析一次库存');
    await page.getByRole('button', { name: '发送需求' }).click();
    await page.waitForFunction(() => typeof window.__analysisEmit === 'function');
    await page.evaluate(() => {
      window.__analysisEmit('run_started', { runId: 'run-live', conversationId: '300' });
      window.__analysisEmit('tool_started', { runId: 'run-live', toolCallId: 'call-new', tool: 'analyzeData', label: '子智能体正在分析' });
    });
    const entry = page.locator('.analysis-entry');
    await entry.getByText('子智能体正在分析', { exact: true }).waitFor();
    assert.ok(await dockWidth() < 1, '运行中的分析也不自动展开面板');
    await entry.click();
    await page.waitForTimeout(400);
    assert.ok(await dockWidth() > 200, '点击分析行展开面板');
    // 再次点击同一条分析行可关闭，再点重新展开。
    await entry.click();
    await page.waitForTimeout(400);
    assert.ok(await dockWidth() < 1, '再次点击分析行收回面板');
    await entry.click();
    await page.waitForTimeout(400);
    assert.ok(await dockWidth() > 200, '再次点击重新展开面板');
    await page.evaluate(() => {
      window.__analysisEmit('analysis_started', { runId: 'run-live', toolCallId: 'call-new', goal: '比较近两个月出库量' });
    });
    await panel.getByText('比较近两个月出库量', { exact: true }).waitFor();
    assert.equal(await panel.getByRole('status').filter({ hasText: '运行中' }).count(), 1, '运行中应显示进行状态');
    await page.evaluate(() => {
      window.__analysisEmit('analysis_step', { runId: 'run-live', toolCallId: 'call-new', text: '先读取近两个月出库记录', tools: [{ name: 'query_sql_data', label: '查询业务数据' }] });
    });
    await panel.getByText('先读取近两个月出库记录', { exact: true }).waitFor();
    assert.equal(await panel.getByText('查询业务数据', { exact: true }).count(), 1, '分析步骤应展示工具文案而不是参数');
    assert.ok(await panel.locator('.analysis-tool svg').count() >= 1, '工具应带图标');
    await page.evaluate(() => {
      window.__analysisEmit('analysis_summary', { runId: 'run-live', toolCallId: 'call-new', goal: '比较近两个月出库量', completion: 'COMPLETED',
        steps: [{ text: '先读取近两个月出库记录', tools: [{ name: 'query_sql_data', label: '查询业务数据' }] }],
        summary: '本月出库量环比增长 **12%**。\n\n- **结果文件**：top10_stock.csv（resultId abc）、all_stock.csv（resultId def）' });
      window.__analysisFinish();
    });
    await panel.getByText('本月出库量环比增长').waitFor();
    assert.equal(await panel.locator('strong').filter({ hasText: '12%' }).count(), 1, '结论应按 Markdown 渲染');
    assert.equal(await panel.getByText('top10_stock.csv', { exact: true }).count(), 1, '产物文件单独展示');
    assert.equal(await panel.getByText(/resultId/).count(), 0, '产物文件不暴露内部结果编号');
    assert.equal(await panel.getByRole('status').filter({ hasText: '运行中' }).count(), 0, '结束后不再显示运行状态');
    assert.equal(await panel.getByText('比较近两个月出库量', { exact: true }).count(), 1, '总结事件不能重复插入分析块');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'analysis-panel-wide.png') });

    // 窄屏降级为可折叠的内联卡片，点开后才展开过程。
    await page.setViewportSize({ width: 700, height: 900 });
    const card = page.getByTestId('ai-analysis-card');
    await card.waitFor();
    assert.equal(await panel.count(), 0, '窄屏不显示右侧面板');
    const toggle = card.getByRole('button', { name: /子智能体分析过程/ });
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false', '内联卡片默认收起');
    const body = card.locator('#ai-analysis-card-body');
    const bodyHeight = () => body.evaluate(element => element.getBoundingClientRect().height);
    assert.ok(await bodyHeight() < 1, '收起时不占用对话空间');
    await toggle.click();
    assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
    await card.getByText('盘点各仓库库存总量', { exact: true }).waitFor();
    await page.waitForTimeout(400);
    assert.ok(await bodyHeight() > 40, '展开后展示全部分析过程');
    if (process.env.AI_QA_SCREENSHOT_DIR) await page.screenshot({ path: path.join(process.env.AI_QA_SCREENSHOT_DIR, 'analysis-card-narrow.png') });
    await toggle.click();
    await page.waitForTimeout(400);
    assert.ok(await bodyHeight() < 1, '可再次收起');

    assert.deepEqual(errors, []);
    console.log('AI_ANALYSIS_PANEL_OK');
  } catch (error) {
    if (page) console.error('页面状态：', page.url(), (await page.locator('body').innerText()).slice(0, 1000));
    throw error;
  } finally { await browser.close(); if (server) server.kill(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });