const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE = process.env.AI_UI_URL || 'http://127.0.0.1:5137';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-27-ai-completion-toast');
fs.mkdirSync(screenshotDir, { recursive: true });

const user = {
  userId: '1', username: 'qa', realName: '测试用户', deptId: '2', deptName: '测试',
  isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'],
};
const conversation = (id, title, updatedAt) => ({ conversationId: id, title, description: '', updatedAt });
const conversations = [
  conversation('101', '后台分析会话', '2026-11-27 00:10:00'),
  conversation('102', '当前查看会话', '2026-11-27 00:09:00'),
];

async function installMocks(page) {
  await page.addInitScript(() => {
    localStorage.setItem('erp_auth_token', 'test-token');
    localStorage.setItem('erp_auth_token_name', 'satoken');
    localStorage.setItem('erp.ai.draft.v2:1:new:activeConversation:', JSON.stringify('101'));
    const original = window.fetch.bind(window);
    window.fetch = async (...args) => {
      if (!String(args[0]).includes('/messages/stream')) return original(...args);
      const encoder = new TextEncoder();
      return new Response(new ReadableStream({
        start(controller) {
          window.__emitRun = (event, data) => controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
          window.__finishRun = () => controller.close();
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
    else if (pathname === '/ai/assistant/overview') data = { refreshedAt: conversations[0].updatedAt, quickPrompts: [], conversations: [] };
    else if (pathname === '/ai/assistant/conversations' && method === 'GET') data = { records: conversations, hasMore: false, nextBeforeTime: null, nextBeforeId: null };
    else if (/^\/ai\/assistant\/conversations\/\d+$/.test(pathname)) {
      const id = pathname.split('/').pop();
      data = conversations.find(item => item.conversationId === id) || conversations[0];
    } else if (pathname.endsWith('/messages')) data = { records: [], pageNum: 1, pageSize: 50, total: 0 };
    else if (pathname.endsWith('/runtime')) data = { status: 'IDLE' };
    else if (pathname.endsWith('/events')) return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' });
    else if (pathname.endsWith('/reports')) data = { records: [], total: 0, pageNum: 1, pageSize: 10 };
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
  });
}

async function waitForActiveTitle(page, title) {
  await page.waitForFunction(expected => {
    const heading = document.querySelector('.workspace-title h1');
    return heading?.textContent?.includes(expected);
  }, title, { timeout: 15000 });
}

async function openWorkspace(page) {
  await page.goto(`${BASE}/ai/assistant`);
  await page.locator('[data-testid="ai-graph-workspace"]').waitFor({ timeout: 15000 });
  await waitForActiveTitle(page, '后台分析会话');
}

async function startBackgroundRun(page) {
  await page.getByLabel('经营问题').fill('分析一下库存');
  await page.getByRole('button', { name: '发送需求' }).click();
  await page.waitForFunction(() => typeof window.__emitRun === 'function');
  await page.evaluate(() => {
    window.__emitRun('run_started', { runId: 'r', conversationId: '101' });
    window.__emitRun('text_delta', { messageId: 'm2', delta: '正在分析库存…' });
  });
}

async function switchToConversation(page, title) {
  await page.locator('.conversation-row').filter({ hasText: title }).locator('.conversation-main').click();
  await waitForActiveTitle(page, title);
}

async function completeBackgroundRun(page) {
  await page.evaluate(() => {
    window.__emitRun('run_completed', { runId: 'r', status: 'COMPLETED' });
    window.__finishRun();
  });
  const toast = page.locator('[data-ai-completion-toast]');
  await toast.waitFor({ timeout: 15000 });
  return toast;
}

async function assertTopRight(page, toast, viewportWidth, label) {
  const box = await toast.boundingBox();
  const panel = await page.locator('.conversation-panel').boundingBox();
  assert.ok(box && panel, `${label}：提示卡片或会话面板不存在`);
  const expectedTop = panel.y + 68;
  const expectedRight = panel.x + panel.width - 20;
  assert.ok(Math.abs(box.y - expectedTop) <= 2, `${label}：顶部位置应距会话区顶部 68px，实际 ${box.y - panel.y}px`);
  assert.ok(Math.abs(box.x + box.width - expectedRight) <= 2, `${label}：右侧应距会话面板右边缘 20px`);
  assert.ok(Math.abs(box.width - 336) <= 2, `${label}：桌面宽度应为 336px，实际 ${box.width}px`);
  assert.ok(box.x > viewportWidth / 2, `${label}：提示应位于右侧半屏`);
}

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' });
  const errors = [];
  try {
    const desktop = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    desktop.on('pageerror', error => errors.push(error.message));
    await installMocks(desktop);
    await openWorkspace(desktop);
    await startBackgroundRun(desktop);
    await switchToConversation(desktop, '当前查看会话');
    const toast = await completeBackgroundRun(desktop);
    assert.ok((await toast.innerText()).includes('后台分析会话'), '完成提示应包含会话标题');
    assert.ok((await toast.innerText()).includes('输出完成'), '完成提示文案不正确');
    await desktop.waitForTimeout(260);
    await assertTopRight(desktop, toast, 1440, '桌面');
    const visuals = await toast.evaluate(node => {
      const box = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      const title = node.querySelector('.ai-completion-toast__text strong');
      const icon = node.querySelector('.ai-completion-toast__icon');
      return {
        top: box.top,
        background: style.backgroundColor,
        borderColor: style.borderColor,
        borderRadius: style.borderRadius,
        titleSize: title ? getComputedStyle(title).fontSize : '',
        titleColor: title ? getComputedStyle(title).color : '',
        iconBackground: icon ? getComputedStyle(icon).backgroundColor : '',
      };
    });
    const header = await desktop.locator('.workspace-header').boundingBox();
    assert.ok(header && visuals.top >= header.y + header.height + 8, '提示不应压住顶栏');
    assert.equal(visuals.background, 'rgb(255, 255, 255)');
    assert.equal(visuals.borderColor, 'rgb(227, 233, 242)');
    assert.equal(visuals.borderRadius, '10px');
    assert.equal(visuals.titleSize, '13px');
    assert.equal(visuals.titleColor, 'rgb(52, 70, 94)');
    assert.equal(visuals.iconBackground, 'rgb(230, 243, 238)');
    await desktop.screenshot({ path: path.join(screenshotDir, 'desktop-top-right.png') });

    assert.equal(await toast.locator('.ai-completion-toast__arrow').count(), 0, '卡片不应再包含右箭头');
    await toast.hover();
    await desktop.waitForTimeout(220);
    const hoverBackground = await toast.locator('.ai-completion-toast__main').evaluate(node => getComputedStyle(node).backgroundColor);
    assert.equal(hoverBackground, 'rgb(247, 249, 252)', '悬停时卡片应使用浅色背景');
    const closeBox = await toast.locator('.ai-completion-toast__close').boundingBox();
    assert.ok(closeBox && Math.abs(closeBox.width - 26) <= 1 && Math.abs(closeBox.height - 26) <= 1, '关闭按钮应为 26px 幽灵按钮');
    await desktop.screenshot({ path: path.join(screenshotDir, 'desktop-hover.png') });

    await toast.locator('.ai-completion-toast__main').click();
    await waitForActiveTitle(desktop, '后台分析会话');
    await desktop.waitForTimeout(300);
    assert.equal(await desktop.locator('[data-ai-completion-toast]').count(), 0, '跳转后应关闭完成提示');
    await desktop.screenshot({ path: path.join(screenshotDir, 'desktop-after-click.png') });

    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    mobile.on('pageerror', error => errors.push(error.message));
    await installMocks(mobile);
    await openWorkspace(mobile);
    await startBackgroundRun(mobile);
    await mobile.getByLabel('打开会话列表').click();
    await mobile.locator('.conversation-row').filter({ hasText: '当前查看会话' }).locator('.conversation-main').click();
    await waitForActiveTitle(mobile, '当前查看会话');
    const mobileToast = await completeBackgroundRun(mobile);
    await mobile.waitForTimeout(260);
    const mobileBox = await mobileToast.boundingBox();
    const mobilePanel = await mobile.locator('.conversation-panel').boundingBox();
    assert.ok(mobileBox && mobilePanel, '移动端：提示卡片或会话面板不存在');
    assert.ok(Math.abs(mobileBox.x - mobilePanel.x - 12) <= 2, '移动端提示应距会话面板左侧 12px');
    assert.ok(Math.abs(mobilePanel.x + mobilePanel.width - (mobileBox.x + mobileBox.width) - 12) <= 2, '移动端提示应距会话面板右侧 12px');
    await mobile.screenshot({ path: path.join(screenshotDir, 'mobile-top-right.png') });

    assert.deepEqual(errors, []);
    console.log('COMPLETION_TOAST_OK');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });