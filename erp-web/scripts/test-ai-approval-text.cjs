// 检查审批前正文在等待、最终回复及历史接管三个阶段的归属。
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

(async () => {
  const base = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const page = await browser.newPage();
  try {
    await page.goto(base);
    const moduleSource = await (await page.request.get(`${base}/src/modules/ai/components/AiMessageList.vue`)).text();
    const vueUrl = moduleSource.match(/\/node_modules\/\.vite\/deps\/vue\.js\?v=[^"']+/)?.[0];
    assert.ok(vueUrl, '未找到 Vue 模块');
    await page.evaluate(async vuePath => {
      const { createApp, h, reactive } = await import(vuePath);
      const Component = (await import('/src/modules/ai/components/AiMessageList.vue')).default;
      const row = (seq, kind, type, data) => ({ messageId: String(seq), requestId: 'r', seq: String(seq), role: kind === 'USER_INPUT' ? 'user' : 'assistant', kind, createdAt: '2026-11-25 10:00:00', content: { type, data } });
      const input = row(1, 'USER_INPUT', 'TEXT', { text: '请创建销售单' });
      const step = row(2, 'ASSISTANT_STEP', 'TEXT', { text: '请确认销售草稿。', callId: 'call-1' });
      const tool = row(3, 'TOOL_CALL', 'ORDER_TOOL', { callId: 'call-1', name: 'sales_order', action: 'CREATE', status: 'waiting', arguments: '{}' });
      const final = row(4, 'ASSISTANT_FINAL', 'TEXT', { text: '销售单已创建。' });
      const state = reactive({ messages: [input, step, tool], liveItems: [{ type: 'text', id: 'step-live', text: '请确认销售草稿。', completed: true }], reportRunId: 'r', finalMessageId: '', runTerminal: false, paused: true });
      const root = document.createElement('div'); root.id = 'approval-text-test'; document.body.appendChild(root);
      createApp({ setup: () => () => h(Component, { ...state, loading: false, pendingText: '', conversationId: '123' }) }).mount(root);
      window.__approvalFinal = () => { state.messages.push(final); state.liveItems.push({ type: 'text', id: 'final-live', text: '销售单已创建。', completed: true }); state.finalMessageId = 'final-live'; state.runTerminal = true; state.paused = false; };
      window.__approvalHistory = () => { state.liveItems = []; state.reportRunId = ''; };
    }, vueUrl);
    const root = page.locator('#approval-text-test');
    const processText = root.getByText('请确认销售草稿。', { exact: true });
    const finalText = root.getByText('销售单已创建。', { exact: true });
    await processText.waitFor({ state: 'visible' });
    assert.equal(await processText.count(), 1, '等待审批时正文必须仅显示一次');
    assert.equal(await root.locator('.work-reveal').getByText('请确认销售草稿。', { exact: true }).count(), 0, '等待审批的提示应直接可见');
    await page.evaluate(() => window.__approvalFinal());
    const toggle = root.getByRole('button', { name: '查看工作过程' });
    await toggle.waitFor({ state: 'visible' });
    assert.equal(await processText.count(), 1, '实时和历史正文不得重复');
    assert.equal(await root.locator('.work-reveal').getByText('请确认销售草稿。', { exact: true }).count(), 1, '审批正文必须归入工作过程');
    assert.equal(await root.locator('.work-reveal').getByText('销售单已创建。', { exact: true }).count(), 0, '最终回复不能进入工作过程');
    assert.equal(await finalText.count(), 1, '最终回复应仅出现一次');
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false', '最终回复出现后工作过程默认折叠');
    await toggle.click();
    await processText.waitFor({ state: 'visible' });
    await page.evaluate(() => window.__approvalHistory());
    assert.equal(await processText.count(), 1, '历史接管后正文不能重复');
    assert.equal(await finalText.count(), 1, '历史接管后最终回复不能重复');
    console.log('AI_APPROVAL_TEXT_OK: 审批正文、最终回复和实时历史去重通过');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
