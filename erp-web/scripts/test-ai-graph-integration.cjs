const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('playwright/test');

// 受控接口浏览器回归：使用当前真实前端与协议，不调用模型或写入真实业务库。
// 真实 Java + Redis 联调另由 test-ai-graph-real-integration.cjs 执行。
const baseUrl = process.env.AI_GRAPH_UI_URL || 'http://127.0.0.1:5173';
const screenshotDir =
  process.env.AI_GRAPH_SCREENSHOT_DIR ||
  path.resolve(
    __dirname,
    '../../docs/qa-screenshots',
    `${new Date().toISOString().replace(/[:.]/g, '-')}-ai-graph-controlled`,
  );
fs.mkdirSync(screenshotDir, { recursive: true });
const user = {
  userId: '1900000000000000001',
  username: 'qa',
  realName: '联调用户',
  deptId: '1900000000000000100',
  deptName: '行政部',
  isAdmin: true,
  roleCodes: ['SUPER_ADMIN'],
  permissionCodes: ['ai:assistant:use'],
  lastLoginAt: '2026-10-16 10:00:00',
};
const id = '1960000000000000001';
const otherId = '1960000000000000002';
const summary = {
  conversationId: id,
  title: '库存与补货分析',
  description: '',
  updatedAt: '2026-10-16 10:00:00',
};
const questions = [
  { id: 'period', question: '分析哪个时间范围？', options: ['最近7天', '最近30天', '最近90天'] },
  { id: 'scope', question: '需要分析哪些仓库或商品范围？', options: [] },
];
const extraQuestions = [
  { id: 'budget', question: '是否有补货预算限制？', options: ['不限预算', '50000元以内'] },
];
const finalPlan = {
  title: '库存补货分析计划',
  objective: '评估一号仓库办公纸品库存，并给出补货分析步骤',
  needs_clarification: false,
  clarification_questions: [],
  steps: [
    { step: 1, type: 'SQL_QUERY', instruction: '查询最近30天销售、当前库存与采购在途数量。' },
    {
      step: 2,
      type: 'PYTHON_ANALYSIS',
      instruction: '分析周转与缺货风险，输出预算范围内的补货建议。',
    },
  ],
};
const acceptedNeed = {
  id: 'N1', intent: 'DATA', need: '分析一号仓库办公纸品库存与缺货风险', depends: [], status: 'PENDING',
  // 无条件需求的 condition 被服务端全局 non_null 省略。
  evidence_plan: [{ id: 'N1-E1', label: '库存与销量', instruction: '查询最近30天销售、库存与采购在途数量', source: 'SQL', depends: [] }],
};
const result = (data) => ({ code: 0, message: '成功', data });
const event = (name, data) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath:
      process.env.PLAYWRIGHT_BROWSER_PATH ||
      'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: { dir: path.join(screenshotDir, 'videos'), size: { width: 1440, height: 900 } },
  });
  await context.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'controlled-ai-token');
    localStorage.setItem('erp_auth_token_name', 'x-erp-session');
  });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('pageerror', (error) => consoleErrors.push(error.message));
  let conversations = [];
  let messages = [];
  let status = 'IDLE';
  let creates = 0;
  let starts = 0;
  let resumes = 0;
  let failResume = true;
  let failAcceptedResume = false;
  let failHistoryReads = 0;
  const resumedPauseIds = [];
  let currentQuestionMessage;
  const submittedAnswers = [];
  const historyRequests = [];
  const append = (role, content) => {
    const value = {
      messageId: String(1963000000000000000n + BigInt(messages.length + 1)),
      requestId: `req-${messages.length + 1}`,
      seq: String(messages.length + 1),
      role,
      kind: role === 'user' ? 'USER_INPUT' : 'ASSISTANT_FINAL',
      content,
      createdAt: '2026-10-16 10:00:00',
    };
    messages.push(value);
    return value;
  };
  /** 暂停轮事件：澄清卡先落库（可被弹掉模拟保存失败），内容携带恢复所需暂停点。 */
  const pauseEvent = (questionList, pauseId) => {
    const data = {
      text: '请确认以下信息，我将继续梳理需求。',
      threadId: 'thread-test',
      pauseId,
      questions: questionList,
    };
    const message = append('assistant', { type: 'CLARIFICATION', data });
    currentQuestionMessage = message;
    status = 'WAITING_HUMAN';
    return event('clarification_required', { checkpointId: `ckpt-${pauseId}`, content: message.content });
  };
  /** 完成轮事件：产物定稿拍先行推送 artifact，终态 run_finished 只带状态。 */
  const readyEvent = (ckpt) => {
    const data = {
      text: `已拆解出 1 条需求：\n- N1 ${acceptedNeed.need}`,
      needs: [acceptedNeed],
      threadId: 'thread-test',
    };
    const message = append('assistant', { type: 'NEEDS', data });
    currentQuestionMessage = null;
    status = 'NEEDS_READY';
    return (
      event('artifact', { type: 'NEEDS', checkpointId: ckpt, content: message.content }) +
      event('run_finished', { status: 'NEEDS_READY' })
    );
  };
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) {
      return route.continue();
    }
    const pathname = url.pathname.replace(/^\/api/, '');
    const json = (data) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(result(data)),
      });
    if (pathname === '/auth/me') {
      return json(user);
    }
    if (pathname === '/ai/assistant/overview') {
      return json({ refreshedAt: summary.updatedAt, quickPrompts: [], conversations });
    }
    if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') {
      creates += 1;
      await new Promise((resolve) => setTimeout(resolve, 180));
      conversations = [summary];
      // 独立保留历史 PLAN，验证新需求协议接入后仍能打开旧卡片。
      append('assistant', { type: 'PLAN', data: { text: '历史计划', plan: finalPlan } });
      append('assistant', { type: 'CLARIFICATION', data: {
        text: '缺少恢复锚点的旧问题', questions: [{ id: 'old', question: '历史范围确认', options: ['旧候选'] }],
      } });
      return json({ ...summary, running: false, runStatus: status, runNode: null, runLabel: null });
    }
    if (pathname.endsWith('/messages')) {
      if (failHistoryReads > 0) {
        failHistoryReads -= 1;
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ code: 503, message: '受控测试：历史暂时不可用', data: null }),
        });
      }
      const pageNum = Number(url.searchParams.get('pageNum'));
      const pageSize = Number(url.searchParams.get('pageSize'));
      historyRequests.push(pageNum);
      return json({
        records: messages.slice((pageNum - 1) * pageSize, pageNum * pageSize),
        pageNum,
        pageSize,
        total: messages.length,
      });
    }
    if (pathname === `/ai/assistant/conversations/${id}`) {
      return json({
        ...summary,
        running: status === 'RUNNING',
        runStatus: status,
        runNode: null,
        runLabel: null,
      });
    }
    if (pathname === `/ai/assistant/conversations/${otherId}`) {
      await new Promise((resolve) => setTimeout(resolve, 160));
      return json({
        ...summary,
        conversationId: otherId,
        title: '另一个会话',
        running: false,
        runStatus: 'IDLE',
        runNode: null,
        runLabel: null,
      });
    }
    if (pathname === '/ai/graph/stream' || pathname === '/ai/graph/resume/stream') {
      const payload = request.postDataJSON();
      if (pathname.endsWith('/resume/stream')) {
        resumedPauseIds.push(payload.pauseId);
        submittedAnswers.push(payload.answer);
      }
      await new Promise((resolve) => setTimeout(resolve, 180));
      const stream = (body) => route.fulfill({ contentType: 'text/event-stream', body });
      if (payload.query === '模拟只收到完成事件') {
        append('user', { type: 'TEXT', data: { text: payload.query } });
        append('assistant', { type: 'NEEDS', data: { text: '新需求已就绪', needs: [{ ...acceptedNeed, need: '第二轮需求：重新核对销售范围' }], threadId: 'thread-test' } });
        status = 'NEEDS_READY';
        return stream(event('run_finished', { status: 'NEEDS_READY' }));
      }
      if (payload.query === '模拟保存失败') {
        append('user', { type: 'TEXT', data: { text: payload.query } });
        const body = pauseEvent(extraQuestions, 'pause-unsaved');
        // 澄清卡未入库：前端必须使用事件里的暂停点渲染表单并继续恢复
        messages.pop();
        failAcceptedResume = true;
        return stream(body);
      }
      if (pathname.endsWith('/resume/stream') && failAcceptedResume) {
        failAcceptedResume = false;
        status = 'FAILED';
        return stream(
          event('run_stopped', { status: 'FAILED', reason: '受控测试：已消费暂停点后模型失败' }),
        );
      }
      if (payload.query === '模拟失败需求') {
        return stream(event('run_stopped', { status: 'FAILED', reason: '受控测试：启动失败' }));
      }
      if (pathname.endsWith('/resume/stream')) {
        resumes += 1;
        if (failResume) {
          failResume = false;
          return stream(
            event('run_stopped', { status: 'FAILED', reason: '受控测试：暂时无法提交，请重试' }),
          );
        }
        append('user', {
          type: 'TEXT',
          data: { text: payload.answer, replyToMessageId: currentQuestionMessage.messageId },
        });
      } else {
        starts += 1;
        append('user', { type: 'TEXT', data: { text: payload.query } });
      }
      let body;
      if (starts === 1 && resumes === 0) {
        body = pauseEvent(questions, 'pause-1');
      } else if (resumes === 2) {
        body = pauseEvent(extraQuestions, 'pause-2');
        // 新问题未存且历史重读失败，前端仍必须使用这份更晚的 pause-2
        messages.pop();
        failHistoryReads = 1;
      } else {
        body = readyEvent('ckpt-3');
      }
      return stream(
        event('node-start', { node: 'planner', label: '需求规划' }) +
          event('node-end', { node: 'planner', label: '需求规划', durationMs: 20 }) +
          body,
      );
    }
    return route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 403, message: '受控场景未开放', data: null }),
    });
  });
  async function capture(name) {
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(250);
    await page.screenshot({
      path: path.join(screenshotDir, name),
      fullPage: true,
      animations: 'disabled',
    });
    if (name.includes('agent-') && await page.getByTestId('clarification-form').count()) {
      await page.getByTestId('clarification-form').screenshot({
        path: path.join(screenshotDir, name.replace(/\.png$/, '-detail.png')),
        animations: 'disabled',
      });
    }
  }
  async function expectQuestionDock(review = false) {
    const form = page.getByTestId('clarification-form');
    await expect(form).toHaveCount(1);
    await expect(form).toBeVisible();
    await expect(page.locator('.question-dock-enter-active, .question-dock-leave-active, .question-step-enter-active, .question-step-leave-active')).toHaveCount(0);
    await page.locator('[data-page-loading]').waitFor({ state: 'hidden' });
    await expect(page.locator('.conversation-scroll [data-testid="clarification-form"]')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toHaveCount(0);
    await expect(page.locator('[data-ai-composer-dock]')).toHaveCount(1);
    await expect(page.locator('[data-ai-composer-dock] [data-ai-question-dock]')).toHaveCount(1);
    await expect(page.locator('[data-ai-composer-dock] [data-testid="clarification-form"]')).toHaveCount(1);
    await expect(form.getByRole('tab')).toHaveCount(0);
    await expect(form.locator('.question-actions svg')).toHaveCount(0);
    await expect(form.locator('.question-header button, .question-tabs')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '其他补充', exact: true })).toHaveCount(review ? 1 : 0);
    await expect(page.getByRole('button', { name: '提交补充信息', exact: true })).toHaveCount(0);
    const placeholder = page.locator('[data-ai-composer-placeholder]');
    await expect(placeholder).toBeVisible();
    await expect(placeholder.locator('textarea, input, button')).toHaveCount(0);
    const alignment = await form.evaluate((element) => {
      const panel = element.getBoundingClientRect();
      const placeholder = document.querySelector('[data-ai-composer-placeholder]').getBoundingClientRect();
      return { left: panel.left - placeholder.left, width: panel.width - placeholder.width, gap: placeholder.top - panel.bottom };
    });
    assert.ok(Math.abs(alignment.left) < 2 && Math.abs(alignment.width) < 2, '问题面板与静态输入占位必须同宽同轴');
    assert.ok(alignment.gap >= 0 && alignment.gap <= 20, '问题面板放在输入占位正上方');
    const send = page.getByRole('button', { name: '发送需求', exact: true });
    if (await send.count()) await expect(send).toBeDisabled();
    return form;
  }
  async function expectQuestion(index, total = 2) {
    await expect(page.getByTestId('clarification-progress')).toHaveText(`问题 ${index} / ${total}`);
    // 切题包含选中反馈和 out-in 动画，等待新题布局稳定后再进行键盘与截图取证。
    await expect(page.locator('.question-step-enter-active, .question-step-leave-active')).toHaveCount(0);
    await expect(page.getByRole('button', { name: index === total ? '确认回答' : '下一题', exact: true })).toBeEnabled();
  }
  try {
    await page.goto(`${baseUrl}/ai/assistant`);
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toBeEnabled();
    const composerAlignment = await page.getByRole('textbox', { name: '经营问题', exact: true }).evaluate(input => {
      const style = getComputedStyle(input);
      const textCenter = input.getBoundingClientRect().top + parseFloat(style.borderTopWidth) + parseFloat(style.paddingTop) + parseFloat(style.lineHeight) / 2;
      const form = input.closest('form').getBoundingClientRect();
      return Math.abs(textCenter - (form.top + form.height / 2));
    });
    assert.ok(composerAlignment <= 2, '单行提示文字应纵向居中，保持左侧起笔');
    await capture('01-welcome-1440x900.png');
    await page
      .getByRole('textbox', { name: '经营问题', exact: true })
      .fill('请分析库存情况，给出补货建议');
    await page.getByRole('button', { name: '发送需求', exact: true }).evaluate((button) => {
      button.click();
      button.click();
    });
    await expect(page.getByRole('radio', { name: '最近30天', exact: true })).toBeVisible();
    assert.equal(creates, 1, '首次双击只能创建一次会话');
    assert.equal(starts, 1, '首次双击只能启动一次图');
    await expectQuestionDock();
    await expectQuestion(1);
    await expect(page.getByRole('radio', { name: '最近7天', exact: true })).toBeFocused();
    // 畸形旧缓存不得阻断最新问题；不能把未知结构当成已答或确认页。
    await page.evaluate(({ userId, conversationId }) => {
      localStorage.setItem(`erp.ai.draft.v2:${userId}:${conversationId}:clarification:pause-1`, JSON.stringify({ answers: [], note: 42, currentQuestionId: null }));
    }, { userId: user.userId, conversationId: id });
    await page.reload();
    await expectQuestion(1);
    await expect(page.getByRole('radio', { name: '最近30天', exact: true })).not.toBeChecked();
    await expectQuestionDock();
    await page.getByRole('button', { name: '继续回答补充问题', exact: true }).click();
    await expect(page.locator('[data-question-title]')).toBeFocused();
    await expect(page.getByRole('radio')).toHaveCount(4);
    await expect(page.getByRole('textbox', { name: questions[1].question, exact: true })).toHaveCount(0);
    await capture('02-agent-question-1-1440x900.png');
    // 使用真实的三固定候选加“其他”，检查短屏中选项和操作可达。
    await page.setViewportSize({ width: 390, height: 640 });
    await expectQuestionDock();
    const questionGeometry = await page.getByTestId('clarification-form').evaluate((element) => {
      const body = element.querySelector('[data-testid="clarification-body"]');
      const rect = element.getBoundingClientRect();
      return { height: rect.height, bottom: rect.bottom, viewport: innerHeight,
        overflowX: document.documentElement.scrollWidth > innerWidth,
        historyHeight: document.querySelector('.conversation-scroll').clientHeight,
        scrollable: body.scrollHeight > body.clientHeight,
        overflowY: getComputedStyle(body).overflowY };
    });
    assert.ok(questionGeometry.height < questionGeometry.viewport, '底部答题面板最大高度应受视口约束');
    assert.ok(questionGeometry.bottom <= questionGeometry.viewport + 1, '操作面板不得溢出屏幕底部');
    assert.equal(questionGeometry.overflowX, false);
    assert.match(questionGeometry.overflowY, /auto|scroll/);
    assert.ok(questionGeometry.historyHeight > 80, '短屏仍需为历史消息保留独立可滚动区域');
    for (const name of ['最近7天', '最近30天', '最近90天', '其他']) {
      await expect(page.getByRole('radio', { name, exact: true })).toBeVisible();
    }
    await page.getByRole('radio', { name: '其他', exact: true }).click({ trial: true });
    await page.getByRole('button', { name: '下一题', exact: true }).click({ trial: true });
    await capture('03-agent-question-mobile-390x640.png');
    await page.setViewportSize({ width: 1440, height: 900 });
    // 无效提交单独验证，避免用错误态截图代替正常视觉交付。
    await page.getByRole('button', { name: '下一题', exact: true }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    assert.equal(resumes, 0, '题目未完成和逐题操作不能发起恢复');
    await page.getByRole('radio', { name: '最近30天', exact: true }).evaluate(button => {
      button.click();
      button.click();
    });
    await expectQuestion(2);
    await expect(page.getByRole('radio', { name: '最近30天', exact: true })).toHaveCount(0);
    await page.getByRole('textbox', { name: questions[1].question, exact: true }).fill('一号仓库，重点关注办公纸品');
    const editor = page.getByTestId('clarification-response-editor');
    await expect(editor).toBeVisible();
    await expect(page.getByTestId('clarification-response-count')).toHaveText(`${'一号仓库，重点关注办公纸品'.length} 字`);
    await expect(page.getByRole('textbox', { name: questions[1].question, exact: true })).toHaveCSS('resize', 'none');
    await editor.evaluate(element => Promise.all(element.getAnimations().map(animation => animation.finished.catch(() => {}))));
    const focusedBorder = await editor.evaluate(element => getComputedStyle(element).borderColor);
    await page.locator('[data-question-title]').focus();
    await expect.poll(() => editor.evaluate(element => getComputedStyle(element).borderColor)).not.toBe(focusedBorder);
    await page.getByRole('textbox', { name: questions[1].question, exact: true }).focus();
    await expect.poll(() => editor.evaluate(element => getComputedStyle(element).borderColor)).toBe(focusedBorder);
    await capture('04-agent-question-2-1440x900.png');
    // 第二题已填也要保持顺序：2→1 修改后不能跳过第二题直接到确认。
    await page.getByRole('button', { name: '上一题', exact: true }).click();
    await expectQuestion(1);
    const periodChoice = page.getByRole('radio', { name: '最近30天', exact: true });
    await expect(periodChoice).toBeChecked();
    const selectionColor = await periodChoice.evaluate(element => {
      const indicator = element.querySelector('.option-indicator');
      return getComputedStyle(indicator).backgroundColor;
    });
    const nextColor = await page.getByRole('button', { name: '下一题', exact: true }).evaluate(element => getComputedStyle(element).backgroundColor);
    assert.equal(selectionColor, nextColor, '选中单选标识与下一步按钮使用同一主色');
    await capture('06-agent-choice-selected-1440x900.png');
    await periodChoice.focus();
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('radio', { name: '最近7天', exact: true })).toBeChecked();
    await expectQuestion(1);
    await expect(page.getByRole('textbox', { name: questions[1].question, exact: true })).toHaveCount(0);
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expectQuestion(2);
    await expect(page.getByRole('textbox', { name: questions[1].question, exact: true })).toHaveValue('一号仓库，重点关注办公纸品');
    await page.getByRole('button', { name: '上一题', exact: true }).click();
    await expectQuestion(1);
    await page.getByRole('radio', { name: '最近30天', exact: true }).focus();
    await page.keyboard.press('2');
    await expectQuestion(2);
    const scopeInput = page.getByRole('textbox', { name: questions[1].question, exact: true });
    await scopeInput.focus();
    await page.keyboard.press('Shift+Enter');
    await expect(scopeInput).toHaveValue(/\n/);
    await scopeInput.fill('一号仓库，重点关注办公纸品');
    await scopeInput.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true });
    await expectQuestion(2);
    await scopeInput.press('Enter');
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await expectQuestionDock(true);
    await expect(page.getByRole('radio')).toHaveCount(0);
    await expect(page.getByTestId('question-0-review')).toContainText('最近30天');
    await page.getByTestId('question-1-review').click();
    await expectQuestion(2);
    await expect(scopeInput).toHaveValue('一号仓库，重点关注办公纸品');
    await scopeInput.press('Enter');
    await page.getByRole('textbox', { name: '其他补充', exact: true }).fill('优先考虑缺货风险');
    await expect(page.locator('.question-step-enter-active, .question-step-leave-active')).toHaveCount(0);
    for (let index = 0; index < questions.length; index += 1) {
      const row = page.getByTestId(`question-${index}-review`);
      const rect = await row.boundingBox();
      const panelRect = await page.getByTestId('clarification-form').boundingBox();
      assert.ok(Math.abs(rect.x - panelRect.x) < 3 && Math.abs(rect.width - panelRect.width) < 3, '确认问答整行覆盖面板左右边缘');
      await page.mouse.move(0, 0);
      const baseBackground = await row.evaluate(element => getComputedStyle(element).backgroundColor);
      for (const x of [rect.x + 3, rect.x + rect.width / 2, rect.x + rect.width - 3]) {
        await page.mouse.move(x, rect.y + rect.height / 2);
        await expect.poll(() => row.evaluate((element, base) => {
          const overlay = getComputedStyle(element, '::before');
          return element.matches(':hover') && (getComputedStyle(element).backgroundColor !== base ||
            (overlay.content !== 'none' && Number(overlay.opacity) > 0));
        }, baseBackground)).toBe(true);
      }
    }
    await capture('08-agent-review-hover-1440x900.png');
    await page.setViewportSize({ width: 390, height: 640 });
    await page.getByRole('textbox', { name: '其他补充', exact: true }).fill('需要复核的经营条件与库存限制。\n'.repeat(24));
    const longReview = await page.getByTestId('clarification-form').evaluate((element) => {
      const body = element.querySelector('[data-testid="clarification-body"]');
      const note = element.querySelector('textarea[aria-label="其他补充"]');
      return {
        scrollable: body.scrollHeight > body.clientHeight || note.scrollHeight > note.clientHeight,
        bottom: element.getBoundingClientRect().bottom,
        viewport: innerHeight,
        overflowX: document.documentElement.scrollWidth > innerWidth,
      };
    });
    assert.equal(longReview.scrollable, true, '真实长文本应在确认页或文本框内滚动');
    assert.ok(longReview.bottom <= longReview.viewport + 1);
    assert.equal(longReview.overflowX, false);
    await page.getByRole('button', { name: '提交回答', exact: true }).click({ trial: true });
    await page.getByRole('textbox', { name: '其他补充', exact: true }).fill('优先考虑缺货风险');
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(50);
    assert.equal(resumes, 0, '选项、键盘确认和回改都不能替代最终统一提交');
    await capture('05-agent-review-1440x900.png');
    await page.reload();
    await expectQuestionDock(true);
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await expect(page.getByTestId('question-0-review')).toContainText('最近30天');
    await expect(page.getByTestId('question-1-review')).toContainText('一号仓库，重点关注办公纸品');
    await expect(page.getByRole('textbox', { name: '其他补充', exact: true })).toHaveValue(
      '优先考虑缺货风险',
    );
    await page.getByRole('button', { name: '提交回答', exact: true }).click();
    await expect(page.getByTestId('graph-request-error')).toContainText('受控测试：暂时无法提交');
    await expectQuestionDock(true);
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await expect(page.getByTestId('question-0-review')).toContainText('最近30天');
    await expect(page.getByRole('textbox', { name: '其他补充', exact: true })).toHaveValue(
      '优先考虑缺货风险',
    );
    await page.getByRole('button', { name: '提交回答', exact: true }).click();
    await expect(page.getByRole('radio', { name: '不限预算', exact: true })).toBeVisible();
    await expectQuestionDock();
    await expect(page.getByRole('radio', { name: '不限预算', exact: true })).toBeFocused();
    await expect(page.getByText('历史刷新失败：受控测试：历史暂时不可用')).toBeVisible();
    await expect(page.getByRole('radio', { name: '最近30天', exact: true })).toHaveCount(0);
    await expectQuestion(1, 1);
    await page.getByRole('radio', { name: '其他', exact: true }).click();
    await expect(page.getByRole('textbox', { name: extraQuestions[0].question, exact: true })).toBeFocused();
    await page.getByRole('button', { name: '确认回答', exact: true }).click();
    await expect(page.getByText('请填写这项信息', { exact: true })).toBeVisible();
    await page.getByRole('textbox', { name: extraQuestions[0].question, exact: true }).fill('50000元以内，优先补充纸品');
    await capture('07-agent-choice-other-1440x900.png');
    await page.getByRole('textbox', { name: extraQuestions[0].question, exact: true }).press('Enter');
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await expectQuestionDock(true);
    await expect(page.getByRole('textbox', { name: '其他补充', exact: true })).toHaveValue('');
    await page.getByRole('button', { name: '提交回答', exact: true }).click();
    await expect(page.getByTestId('ai-needs-detail')).toContainText(acceptedNeed.need);
    await expect(page.getByTestId('ai-needs-detail')).toContainText('待处理');
    await expect(page.getByTestId('ai-needs-detail').locator('[data-slot="badge"][data-status="PENDING"]')).toHaveText('待处理');
    await expect(page.getByTestId('ai-needs-detail').getByText('需求已拆解', { exact: true })).toHaveCount(0);
    await expect(page.getByTestId('ai-needs-detail').getByText('完成要求', { exact: true })).toHaveCount(0);
    await expect(page.locator('.needs-summary ul li')).toHaveCount(1);
    await expect(page.locator('.needs-summary ul li')).toContainText(acceptedNeed.need);
    await page.getByTestId('ai-needs-detail').getByText('待取证据 · 1 项', { exact: true }).click();
    await expect(page.getByTestId('ai-needs-detail')).toContainText('库存与销量');
    assert.equal(await page.getByTestId('ai-needs-detail').locator('.evidence-list').evaluate(list => getComputedStyle(list).listStyleType), 'disc', '证据清单按分点展示');
    await expect(page.getByTestId('ai-needs-detail')).not.toContainText(acceptedNeed.evidence_plan[0].instruction);
    await expect(page.getByTestId('ai-needs-detail').locator('.evidence-list')).not.toContainText('N1-E1');
    const hierarchy = await page.getByTestId('ai-needs-detail').evaluate(panel => ({
      heading: parseFloat(getComputedStyle(panel.querySelector('.need-heading')).fontSize),
      status: parseFloat(getComputedStyle(panel.querySelector('.need-status')).fontSize),
      description: parseFloat(getComputedStyle(panel.querySelector('.need-description')).fontSize),
      detail: parseFloat(getComputedStyle(panel.querySelector('summary')).fontSize),
    }));
    assert.ok(hierarchy.heading > hierarchy.description && hierarchy.heading > hierarchy.detail, '需求标题层级必须高于说明及展开项');
    assert.ok(hierarchy.status > hierarchy.detail, '标题行的处理状态也应高于下方展开项字号');
    assert.equal(resumedPauseIds.at(-1), 'pause-2', '历史不可用时仍必须提交新问题的暂停点');
    assert.equal(typeof submittedAnswers.at(-1), 'string', '恢复协议保持字符串回答');
    assert.match(submittedAnswers[0], /分析哪个时间范围？\n最近30天/);
    assert.match(submittedAnswers[0], /一号仓库，重点关注办公纸品/);
    assert.match(submittedAnswers[0], /其他补充：优先考虑缺货风险/);
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toBeEnabled();
    const answeredHistory = page.getByTestId('clarification-history').filter({ has: page.locator('summary[aria-label="回看补充信息"]') }).first();
    await expect(answeredHistory).not.toHaveAttribute('open', '');
    await expect(answeredHistory.locator('summary')).toContainText('最近30天');
    await answeredHistory.locator('summary').click();
    await expect(answeredHistory.locator('.history-body')).toContainText('一号仓库，重点关注办公纸品');
    await answeredHistory.locator('summary').click();
    await expect(answeredHistory).not.toHaveAttribute('open', '');
    const readOnlyHistory = page.getByTestId('clarification-history').filter({ hasText: '历史范围确认' });
    await readOnlyHistory.locator('summary').click();
    await expect(readOnlyHistory).toContainText('当前不可提交');
    await expect(readOnlyHistory.getByRole('radio')).toHaveCount(0);
    await readOnlyHistory.locator('summary').click();
    await capture('03-needs-ready-1440x900.png');
    // 使用后端全部真实枚举，验证每种状态都有独立配色且保留可读文字。
    const needMessage = [...messages].reverse().find(message => message.content.type === 'NEEDS');
    const originalNeedContent = needMessage.content;
    const needStates = [
      ['PENDING', '待处理'], ['RUNNING', '处理中'], ['DONE', '已完成'],
      ['SKIPPED', '已跳过'], ['BLOCKED', '受阻'], ['INFEASIBLE', '不可行'],
    ];
    needMessage.content = { ...originalNeedContent, data: { ...originalNeedContent.data,
      needs: needStates.map(([needStatus], index) => ({ ...acceptedNeed, id: `N${index + 1}`, status: needStatus,
        evidence_plan: acceptedNeed.evidence_plan.map(item => ({ ...item, id: `N${index + 1}-E1` })),
      })),
    } };
    await page.reload();
    await page.getByRole('button', { name: /已拆解 6 条需求/ }).click();
    const needColors = [];
    for (const [needStatus, label] of needStates) {
      const badge = page.getByTestId('ai-needs-detail').locator(`[data-slot="badge"][data-status="${needStatus}"]`);
      await expect(badge).toHaveText(label);
      needColors.push(await badge.evaluate(element => getComputedStyle(element).color));
    }
    assert.equal(new Set(needColors).size, needStates.length, '后端六种需求状态必须有各自的配色');
    await capture('03b-needs-status-colors-1440x900.png');
    await page.getByTestId('ai-needs-detail').evaluate(panel => { panel.scrollTop = panel.scrollHeight; });
    await capture('03c-needs-status-colors-bottom-1440x900.png');
    needMessage.content = originalNeedContent;
    await page.reload();
    await expect(page.getByRole('button', { name: '查看任务计划', exact: true })).toBeVisible();
    await page.getByRole('button', { name: /库存补货分析计划.*查看完整计划/ }).click();
    await expect(page.getByTestId('ai-plan-panel')).toContainText('库存补货分析计划');
    await expect(page.getByTestId('ai-plan-panel')).toContainText('数据查询');
    await page.getByRole('button', { name: /已拆解 1 条需求/ }).click();
    await expect(page.getByTestId('ai-needs-detail')).toContainText(acceptedNeed.need);
    const needTag = page.getByRole('button', { name: /已拆解 1 条需求/ });
    await expect(needTag).toHaveAttribute('aria-expanded', 'true');
    await needTag.click();
    await expect(page.getByRole('button', { name: '展开任务计划', exact: true })).toBeVisible();
    await expect(needTag).toHaveAttribute('aria-expanded', 'false');
    await needTag.click();
    await expect(page.getByTestId('ai-needs-detail')).toBeVisible();
    const marker = page.getByRole('button', { name: '跳转到第 1 条用户消息', exact: true });
    await marker.hover();
    const markerGeometry = await marker.evaluate(button => ({
      line: button.querySelector('span').getBoundingClientRect().width,
      gap: document.querySelector('.message:not(.is-user) .message-avatar').getBoundingClientRect().left - button.getBoundingClientRect().right,
    }));
    assert.ok(markerGeometry.line <= 10 && markerGeometry.gap >= 16, '缩短跳转刻度，与消息头像保持留白');
    await page.setViewportSize({ width: 1115, height: 838 });
    await expect(page.getByRole('dialog')).toBeVisible();
    await capture('04-needs-drawer-1115x838.png');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    const geometry = await page.evaluate(() => {
      const chat = document.querySelector('.conversation-panel').getBoundingClientRect();
      const scroll = document.querySelector('.conversation-scroll').getBoundingClientRect();
      const dock = document.querySelector('[data-ai-composer-dock]').getBoundingClientRect();
      return {
        chatWidth: chat.width,
        dockBottom: dock.bottom,
        viewportHeight: innerHeight,
        sidebarWidth: document.querySelector('.app-sidebar').getBoundingClientRect().width,
        headerHeight: document.querySelector('.app-shell__header').getBoundingClientRect().height,
        gap: dock.top - scroll.bottom,
        overflow: document.documentElement.scrollWidth > innerWidth,
      };
    });
    assert.ok(geometry.chatWidth >= 650, '保留原导航后，中屏对话区不被常驻计划挤压');
    assert.equal(geometry.sidebarWidth, 224, 'AI 页面复用原导航宽度');
    assert.equal(geometry.headerHeight, 56, 'AI 页面复用已确认的浅色应用壳顶栏');
    assert.ok(geometry.dockBottom <= geometry.viewportHeight + 1, '输入框位于顶栏下方剩余视口内');
    assert.ok(Math.abs(geometry.gap) < 2, '输入底座与消息滚动区相邻');
    assert.equal(geometry.overflow, false);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '查看任务计划', exact: true }).click();
    await expect(page.getByTestId('ai-needs-detail')).toContainText(acceptedNeed.need);
    await capture('05-mobile-needs-390x844.png');
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, '390px 下长需求与答案标签不能撑宽页面');
    await page.getByRole('button', { name: '打开会话列表', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await capture('06-mobile-sessions-390x844.png');
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 1440, height: 900 });
    // 清除本机快照模拟跨设备回读：降级为服务端已关联全文，不能从自然语言猜选项。
    await page.evaluate(() => {
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith('erp.ai.draft.v2:') && key.includes(':submitted:')) localStorage.removeItem(key);
      }
    });
    await page.reload();
    const serverHistory = page.getByTestId('clarification-history').filter({ has: page.locator('summary[aria-label="回看补充信息"]') }).first();
    await expect(serverHistory.locator('summary')).toContainText('查看问题与回答');
    await expect(serverHistory.locator('summary .answer-tag')).toHaveCount(0);
    await expect(serverHistory).not.toHaveAttribute('open', '');
    await serverHistory.locator('summary').click();
    await expect(serverHistory.locator('.history-body')).toContainText('最近30天');
    await expect(serverHistory.locator('.history-body')).toContainText('一号仓库，重点关注办公纸品');
    await serverHistory.locator('summary').click();
    // 验证长历史只加载首/末页，用户操作后再补较早页。
    await page.getByRole('button', { name: /已拆解 1 条需求/ }).click();
    await page.getByRole('textbox', { name: '经营问题', exact: true }).fill('模拟只收到完成事件');
    await page.getByRole('button', { name: '发送需求', exact: true }).click();
    await expect(page.getByTestId('ai-needs-detail')).toContainText('第二轮需求：重新核对销售范围');
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toBeEnabled();
    while (messages.length < 122) {
      append('user', { type: 'TEXT', data: { text: `较长历史消息 ${messages.length}` } });
    }
    historyRequests.length = 0;
    await page.reload();
    await expect(page.getByRole('button', { name: '加载较早消息', exact: true })).toBeVisible();
    assert.deepEqual(historyRequests.slice(0, 2), [1, 3]);
    await page.getByRole('button', { name: '加载较早消息', exact: true }).click();
    await expect.poll(() => historyRequests.includes(2)).toBeTruthy();
    await page.getByRole('textbox', { name: '经营问题', exact: true }).fill('模拟失败需求');
    await page.getByRole('button', { name: '发送需求', exact: true }).click();
    await expect(page.getByTestId('graph-request-error')).toContainText('受控测试：启动失败');
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toHaveValue(
      '模拟失败需求',
    );
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toBeEnabled();
    await page.getByRole('textbox', { name: '经营问题', exact: true }).fill('模拟保存失败');
    await page.getByRole('button', { name: '发送需求', exact: true }).click();
    await expect(page.getByRole('radio', { name: '不限预算', exact: true })).toBeVisible();
    await page.getByRole('radio', { name: '不限预算', exact: true }).click();
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await page.getByRole('button', { name: '提交回答', exact: true }).click();
    await expect(page.getByTestId('graph-request-error')).toContainText('已消费暂停点后模型失败');
    await expect(page.getByRole('radio', { name: '不限预算', exact: true })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '经营问题', exact: true })).toBeEnabled();
    // 专用兼容用例：异常超限返回不用于正常展示，旧草稿中第4项仍必须保留为“其他”。
    const overflowQuestion = {
      id: 'overflow-period', question: '确认统计周期？',
      options: ['最近7天', '最近30天', '最近90天', '本月', '上月'],
    };
    pauseEvent([overflowQuestion], 'pause-overflow');
    await page.reload();
    await expectQuestionDock();
    await expect(page.getByRole('radio')).toHaveCount(4);
    await expect(page.getByRole('radio', { name: '本月', exact: true })).toHaveCount(0);
    await expect(page.getByRole('radio', { name: '上月', exact: true })).toHaveCount(0);
    await page.evaluate(({ key, questionId }) => {
      localStorage.setItem(key, JSON.stringify({
        answers: { [questionId]: { selected: '本月', custom: '' } },
        note: '', currentQuestionId: questionId,
      }));
    }, { key: `erp.ai.draft.v2:${user.userId}:${id}:clarification:pause-overflow`, questionId: overflowQuestion.id });
    await page.reload();
    await expect(page.getByRole('radio', { name: '其他', exact: true })).toBeChecked();
    const overflowInput = page.getByRole('textbox', { name: overflowQuestion.question, exact: true });
    await expect(overflowInput).toHaveValue('本月');
    await overflowInput.fill('本月，仅分析已审核单据');
    await overflowInput.press('Enter');
    await expect(page.getByTestId('question-0-review')).toContainText('本月，仅分析已审核单据');
    await page.reload();
    await page.getByTestId('question-0-review').click();
    await expect(overflowInput).toHaveValue('本月，仅分析已审核单据');
    pauseEvent([{ id: 'custom-name', question: '确认分析范围？', options: ['全部仓库', '其他', '指定仓库'] }], 'pause-other');
    await page.reload();
    await expect(page.getByRole('radio')).toHaveCount(3);
    await expect(page.getByRole('radio', { name: '其他', exact: true })).toHaveCount(1);
    await page.getByRole('radio', { name: '其他', exact: true }).click();
    await expect(page.getByRole('textbox', { name: '确认分析范围？', exact: true })).toBeFocused();
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.getByRole('radio', { name: '全部仓库', exact: true }).click();
    await expect(page.getByTestId('clarification-review')).toBeVisible();
    await expect(page.locator('.question-step-enter-active, .question-step-leave-active')).toHaveCount(0);
    await page.getByRole('button', { name: '上一题', exact: true }).click();
    await expectQuestion(1, 1);
    const reducedDurations = await page.getByRole('radio', { name: '全部仓库', exact: true }).evaluate(element => getComputedStyle(element).transitionDuration);
    assert.ok(reducedDurations.split(',').every(value => parseFloat(value) <= 0.001), '减少动态效果沿用项目全局的零时长或0.01ms过渡');
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await page.getByRole('button', { name: '工作台', exact: true }).click();
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(page.locator('.app-sidebar')).toBeVisible();
    await expect(page.locator('.app-shell__header')).toBeVisible();
    assert.equal(
      await page
        .locator('.app-sidebar')
        .evaluate((element) => Math.round(element.getBoundingClientRect().width)),
      224,
      '返回ERP页面恢复原有导航宽度',
    );
    user.isAdmin = false;
    user.roleCodes = [];
    user.permissionCodes = [];
    await page.reload();
    await expect(page.locator('.app-sidebar')).toBeVisible();
    await expect(
      page.locator('.app-sidebar').getByRole('button', { name: '智能助手', exact: true }),
    ).toHaveCount(0);
    assert.equal(consoleErrors.length, 0, consoleErrors.join('\n'));
    console.log(
      JSON.stringify(
        {
          status: 'AI_GRAPH_CONTROLLED_PASSED',
          creates,
          starts,
          resumes,
          geometry,
          screenshots: screenshotDir,
          checks:
            '常驻composer内唯一提问slot与静态占位同轴、无tabs和导航svg、主色一致、最多三固定加其他、短屏可达、顺序回改与240ms切题等待、输入聚焦字数禁resize、确认行左右中心hover、异常旧草稿兼容、IME保护、确认页唯一提交、刷新失败保留、历史与NEEDS旧PLAN兼容、分页',
        },
        null,
        2,
      ),
    );
  } catch (error) {
    console.error({
      url: page.url(),
      body: (await page.locator('body').innerText()).slice(0, 1800),
      consoleErrors,
    });
    await page.screenshot({ path: path.join(screenshotDir, 'failure.png'), fullPage: true });
    throw error;
  } finally {
    await context.close();
    console.log(`AI_GRAPH_CONTROLLED_VIDEO=${await page.video().path()}`);
    await browser.close();
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
