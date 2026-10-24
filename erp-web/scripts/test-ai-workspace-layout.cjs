const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, expect } = require('playwright/test');

// 真实前端 + 受控 API：不调用模型、不写入真实会话或业务数据。
const base = process.env.AI_GRAPH_UI_URL || 'http://127.0.0.1:5173';
const out = path.resolve(__dirname, '../../docs/qa-screenshots/2026-10-16-ai-workspace-layout');
const user = { userId: '1900000000000000001', username: 'layout-qa', realName: '布局验证', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: ['ai:assistant:use'] };
const entries = [
  ['1', '库存补货分析', '2026-10-16 10:00:00'],
  ['2', '今日零点会话', '2026-10-16 00:00:00'],
  ['3', '昨日零点会话', '2026-10-15 00:00:00'],
  ['4', '近七天边界会话', '2026-10-10 00:00:00'],
  ['5', '更早边界会话', '2026-10-09 23:59:59'],
].map(([id, title, updatedAt]) => ({ conversationId: '196000000000000000' + id, title, description: '', updatedAt }));
const plan = { title: '库存补货分析计划', objective: '核对销售、库存与在途采购，明确缺货分析步骤。', needs_clarification: false, clarification_questions: [], steps: [{ step: 1, type: 'SQL_QUERY', instruction: '规划查询华东仓最近30天销售与库存，尚未执行。' }, { step: 2, type: 'PYTHON_ANALYSIS', instruction: '规划分析库存缺口与补货建议，尚未执行。' }] };
const message = (id, role, content) => ({ messageId: '196300000000000000' + id, requestId: 'layout-qa', seq: id, role, kind: role === 'user' ? 'USER_INPUT' : 'ASSISTANT_FINAL', createdAt: '2026-10-16 10:00:00', content });

async function main() {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, timezoneId: 'Asia/Shanghai', reducedMotion: 'reduce' });
  await context.addInitScript(() => { localStorage.setItem('erp_auth_token', 'layout-qa-token'); localStorage.setItem('erp_auth_token_name', 'x-erp-session'); });
  const page = await context.newPage();
  await page.clock.install({ time: new Date('2026-10-16T12:00:00+08:00') });
  await page.clock.setFixedTime(new Date('2026-10-16T12:00:00+08:00'));
  const errors = [];
  const writes = [];
  let list = [...entries];
  let longMessage = false;
  let navigationScenario = false;
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.replace(/^\/api/, '');
    const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    if (pathname === '/auth/me') return json(user);
    if (pathname === '/ai/assistant/overview') return json({ refreshedAt: '2026-10-16 12:00:00', conversations: list, quickPrompts: [] });
    if (pathname === '/ai/assistant/conversations' && request.method() === 'POST') {
      writes.push('create');
      const created = { conversationId: '1960000000000000099', title: '新的经营会话', description: '', updatedAt: '2026-10-16 12:00:00' };
      list.unshift(created);
      return json({ ...created, runStatus: 'IDLE', running: false, runNode: null, runLabel: null });
    }
    const match = pathname.match(/^\/ai\/assistant\/conversations\/(\d+)(\/messages)?$/);
    if (match) {
      const entry = list.find(item => item.conversationId === match[1]);
      if (request.method() === 'PATCH') { writes.push('rename'); entry.title = request.postDataJSON().title; return json(null); }
      if (request.method() === 'DELETE') { writes.push('delete'); list = list.filter(item => item !== entry); return json(null); }
      if (match[2]) {
        if (navigationScenario) {
          const all = Array.from({ length: 28 }, (_, index) => [
            message(String(100 + index * 2), 'user', { type: 'TEXT', data: { text: `第${index + 1}轮：请分析华东仓库存与补货建议。\n保留商品范围和在途采购口径，核对最近30天出库。` } }),
            message(String(101 + index * 2), 'assistant', { type: 'TEXT', data: { text: '正在核对库存、销量与采购在途，按照商品汇总分析。\n\n'.repeat(6) } }),
          ]).flat();
          const pageNum = Number(url.searchParams.get('pageNum'));
          const pageSize = Number(url.searchParams.get('pageSize'));
          return json({ records: all.slice((pageNum - 1) * pageSize, pageNum * pageSize), pageNum, pageSize, total: all.length });
        }
        const records = entry.conversationId.endsWith('99') ? [] : [
          message('1', 'user', { type: 'TEXT', data: { text: longMessage ? '请分析这条业务记录：' + 'https://example.test/'.repeat(18) : '请分析华东仓库存，先制定补货分析计划。' } }),
          message('2', 'assistant', { type: 'PLAN', data: { text: '执行步骤：\n1. [SQL_QUERY] 规划查询华东仓最近30天销售与库存，尚未执行。', plan, threadId: 'layout-thread' } }),
        ];
        return json({ records, pageNum: Number(url.searchParams.get('pageNum')), pageSize: Number(url.searchParams.get('pageSize')), total: records.length });
      }
      return json({ ...entry, runStatus: entry.conversationId.endsWith('99') ? 'IDLE' : 'PLAN_READY', running: false, runNode: null, runLabel: null });
    }
    return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ code: 403, message: '受控测试：未授权接口', data: null }) });
  });
  const capture = async name => {
    await expect(page.locator('[data-page-loading]')).toHaveCount(0);
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: path.join(out, name + '.png'), animations: 'disabled' });
  };
  const sidebar = page.locator('.graph-workspace > .conversation-sidebar');
  const composer = page.getByRole('textbox', { name: '经营问题', exact: true });
  const dimensions = () => page.locator('.conversation-panel').evaluate(el => el.getBoundingClientRect().width);
  try {
    await page.goto(base + '/ai/assistant');
    await expect(composer).toBeEnabled();
    await expect(sidebar.getByRole('button', { name: '新建会话', exact: true })).toBeVisible();
    for (const [label, title] of [['今天', '今日零点会话'], ['昨天', '昨日零点会话'], ['近 7 天', '近七天边界会话'], ['更早', '更早边界会话']]) {
      const head = sidebar.locator('.group-head').filter({ hasText: label });
      if (await head.getAttribute('aria-expanded') !== 'true') await head.click();
      await expect(head.locator('..').getByText(title, { exact: true })).toBeVisible();
    }
    const todayHead = sidebar.locator('.group-head').filter({ hasText: '今天' });
    await todayHead.click();
    await page.clock.fastForward(60_001);
    await expect(todayHead).toHaveAttribute('aria-expanded', 'false');
    await todayHead.click();
    const createBox = await sidebar.locator('.create-button').boundingBox();
    const listBox = await sidebar.locator('.sidebar-list').boundingBox();
    assert.ok(createBox.y + createBox.height <= listBox.y + 1, '新建按钮应在滚动列表上方');
    await composer.fill('保留这段尚未发送的草稿');
    await expect(page.locator('.message-list')).toContainText('计划如下：');
    await expect(page.locator('.message-list')).not.toContainText('执行步骤：');
    await expect(page.locator('.message-list')).not.toContainText('[SQL_QUERY]');
    const planCardWidth = await page.locator('.plan-artifact').evaluate(el => el.getBoundingClientRect().width);
    assert.ok(planCardWidth <= 360, '计划卡片应保持紧凑宽度');
    await page.locator('.plan-artifact').click();
    await expect(page.locator('.desktop-plan')).not.toHaveClass(/is-collapsed/);
    await expect(page.locator('.desktop-plan')).toContainText(plan.steps[0].instruction);
    const leftResize = page.getByRole('separator', { name: '调整会话栏宽度' });
    const rightResize = page.getByRole('separator', { name: '调整计划栏宽度' });
    const drag = async (handle, dx) => {
      const box = await handle.boundingBox();
      await page.mouse.move(box.x, box.y + 180);
      await page.mouse.down();
      await page.mouse.move(box.x + dx, box.y + 180, { steps: 6 });
      await page.mouse.up();
    };
    await drag(leftResize, 40);
    await expect(leftResize).toHaveAttribute('aria-valuenow', '264');
    await drag(rightResize, -32);
    await expect(rightResize).toHaveAttribute('aria-valuenow', '384');
    await expect(composer).toHaveValue('保留这段尚未发送的草稿');
    await leftResize.focus();
    await page.keyboard.press('ArrowRight');
    await expect(leftResize).toHaveAttribute('aria-valuenow', '280');
    await rightResize.focus();
    await page.keyboard.press('ArrowRight');
    await expect(rightResize).toHaveAttribute('aria-valuenow', '368');
    await page.reload();
    await expect(leftResize).toHaveAttribute('aria-valuenow', '280');
    await page.locator('.plan-artifact').click();
    await expect(rightResize).toHaveAttribute('aria-valuenow', '368');
    await capture('09-resized-and-restored-1440');
    await drag(leftResize, 1000);
    await expect(leftResize).toHaveAttribute('aria-valuenow', '320');
    await drag(rightResize, -1000);
    await expect(rightResize).toHaveAttribute('aria-valuenow', '416');
    await page.setViewportSize({ width: 1360, height: 900 });
    assert.ok(await dimensions() >= 400, '两栏最大宽度也要保留可读的对话空间');
    await drag(leftResize, -1000);
    await expect(leftResize).toHaveAttribute('aria-valuenow', '200');
    await drag(rightResize, 1000);
    await expect(rightResize).toHaveAttribute('aria-valuenow', '280');
    await drag(leftResize, 24);
    await drag(rightResize, -72);
    await page.setViewportSize({ width: 1440, height: 900 });
    const expandedWidth = await dimensions();
    await capture('01-both-expanded-1440');
    await page.getByRole('button', { name: '收起会话列表', exact: true }).click();
    await expect(leftResize).toHaveCount(0);
    await expect(page.getByRole('button', { name: '展开会话列表', exact: true })).toBeFocused();
    assert.ok(await dimensions() > expandedWidth + 150);
    await capture('02-left-collapsed-1440');
    await page.getByRole('button', { name: '关闭任务计划', exact: true }).click();
    await expect(rightResize).toHaveCount(0);
    await expect(page.getByRole('button', { name: '展开任务计划', exact: true })).toBeFocused();
    await expect(composer).toHaveValue('保留这段尚未发送的草稿');
    await capture('03-both-collapsed-1440');
    await page.getByRole('button', { name: '展开会话列表', exact: true }).click();
    await expect(leftResize).toHaveAttribute('aria-valuenow', '224');
    await expect(page.locator('.desktop-plan')).toHaveClass(/is-collapsed/);
    await capture('04-right-collapsed-1440');
    const bubble = await page.locator('.message.is-user').first().evaluate(el => {
      const row = el.getBoundingClientRect(); const avatar = el.querySelector('.message-avatar').getBoundingClientRect(); const body = el.querySelector('.message-bubble').getBoundingClientRect();
      return { rowRight: row.right, avatarRight: avatar.right, bubbleRight: body.right, bubbleLeft: body.left, rowLeft: row.left };
    });
    assert.ok(Math.abs(bubble.avatarRight - bubble.rowRight) < 1 && bubble.bubbleLeft > bubble.rowLeft, '用户头像与气泡应位于右侧');
    const activeRow = sidebar.locator('.conversation-row.is-active');
    await activeRow.hover();
    await activeRow.getByRole('button', { name: '重命名会话', exact: true }).click();
    await page.getByRole('textbox', { name: '会话名称', exact: true }).fill('重命名后的库存会话');
    await page.getByRole('textbox', { name: '会话名称', exact: true }).press('Enter');
    await expect(sidebar.getByText('重命名后的库存会话', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '新建会话', exact: true }).click();
    await expect(composer).toHaveValue('');
    await page.getByRole('button', { name: '展开任务计划', exact: true }).click();
    await expect(page.getByText('任务计划将在这里显示', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '关闭任务计划', exact: true }).click();
    await sidebar.locator('.conversation-row.is-active').hover();
    await sidebar.locator('.conversation-row.is-active').getByRole('button', { name: '删除会话', exact: true }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '删除会话', exact: true }).click();
    await expect(sidebar.getByText('新的经营会话', { exact: true })).toHaveCount(0);
    await sidebar.getByText('重命名后的库存会话', { exact: true }).click();
    await expect(composer).toHaveValue('保留这段尚未发送的草稿');
    await page.clock.setFixedTime(new Date('2026-10-17T00:01:00+08:00'));
    await page.clock.fastForward(60_001);
    await expect(sidebar.locator('.group-head').filter({ hasText: '昨天' })).toHaveAttribute('aria-expanded', 'true');
    await page.reload();
    await expect(sidebar.locator('.group-head').filter({ hasText: '昨天' })).toHaveAttribute('aria-expanded', 'true');
    await expect(sidebar.locator('.group-head').filter({ hasText: '昨天' }).locator('..').getByText('今日零点会话', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '查看任务计划', exact: true }).click();
    await page.setViewportSize({ width: 1115, height: 838 });
    await expect(page.getByRole('dialog')).toBeVisible();
    await capture('05-plan-drawer-1115');
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('separator')).toHaveCount(0);
    await page.getByRole('button', { name: '打开会话列表', exact: true }).click();
    await expect(page.getByRole('dialog').getByRole('button', { name: '新建会话', exact: true })).toBeVisible();
    await capture('06-conversations-390');
    await page.keyboard.press('Escape');
    await capture('07-user-bubble-390');
    longMessage = true;
    await page.reload();
    await expect(page.locator('.message.is-user .message-bubble')).toContainText('https://example.test/');
    const longGeometry = await page.locator('.message.is-user .message-bubble').evaluate(el => ({ client: el.clientWidth, scroll: el.scrollWidth }));
    assert.ok(longGeometry.scroll <= longGeometry.client + 1, '长用户文本不应撑开气泡');
    await expect(page.locator('[data-page-loading]')).toHaveCount(0);
    await page.locator('.conversation-scroll').evaluate(el => { el.scrollTop = 0; });
    await capture('08-long-user-bubble-390');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    const mobileDock = await page.locator('[data-ai-composer-dock]').boundingBox();
    assert.ok(mobileDock.x + mobileDock.width <= 391, '移动端输入框不能被内容区裁切');
    navigationScenario = true;
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.reload();
    const navigation = page.getByRole('navigation', { name: '按用户消息跳转' });
    await expect(navigation.getByRole('button')).toHaveCount(3);
    await page.getByRole('button', { name: '加载较早消息', exact: true }).click();
    await expect(navigation.getByRole('button')).toHaveCount(28);
    const firstTurn = navigation.getByRole('button', { name: '跳转到第 1 条用户消息', exact: true });
    await firstTurn.hover();
    await page.clock.runFor(250);
    const openPreview = page.locator('[data-slot="tooltip-content"]:not([data-state="closed"])');
    await expect(openPreview).toBeVisible();
    await expect(openPreview).toContainText('第1轮：请分析华东仓库存与补货建议。');
    const dockBeforeJump = await page.locator('[data-ai-composer-dock]').boundingBox();
    await firstTurn.click();
    await page.clock.runFor(100);
    await expect(firstTurn).toHaveAttribute('aria-current', 'step');
    const jumpGeometry = await page.locator('.conversation-scroll').evaluate(el => ({
      delta: el.querySelector('.message.is-user').getBoundingClientRect().top - el.getBoundingClientRect().top,
      assistantAlignment: el.querySelector('.message:not(.is-user) .message-avatar').getBoundingClientRect().left - document.querySelector('.ai-composer').getBoundingClientRect().left,
      userAlignment: el.querySelector('.message.is-user .message-avatar').getBoundingClientRect().right - document.querySelector('.ai-composer').getBoundingClientRect().right,
    }));
    assert.ok(Math.abs(jumpGeometry.delta - 20) < 2, '第一条用户消息定位到消息区顶部');
    assert.ok(Math.abs(jumpGeometry.assistantAlignment) <= 7, '助手头像对齐输入框左沿');
    assert.ok(Math.abs(jumpGeometry.userAlignment) <= 7, '用户头像对齐输入框右沿');
    await page.mouse.move(1300, 850);
    await firstTurn.hover();
    await page.clock.runFor(250);
    await expect(openPreview).toBeVisible();
    await capture('10-user-turn-navigation-preview-1440');
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    const middleTurn = navigation.getByRole('button', { name: '跳转到第 14 条用户消息', exact: true });
    await middleTurn.click();
    await page.clock.runFor(1500);
    await expect(middleTurn).toHaveAttribute('aria-current', 'step');
    const dockAfterJump = await page.locator('[data-ai-composer-dock]').boundingBox();
    assert.equal(dockBeforeJump.y, dockAfterJump.y, '跳转不移动输入底座');
    await page.locator('.conversation-scroll').evaluate(el => { el.scrollTop = el.scrollHeight; });
    await page.clock.runFor(100);
    await expect(navigation.getByRole('button', { name: '跳转到第 28 条用户消息', exact: true })).toHaveAttribute('aria-current', 'step');
    await page.setViewportSize({ width: 390, height: 844 });
    await firstTurn.click();
    await page.clock.runFor(1500);
    await expect(firstTurn).toHaveAttribute('aria-current', 'step');
    await navigation.getByRole('button', { name: '跳转到第 2 条用户消息', exact: true }).focus();
    await page.keyboard.press('Shift+Tab');
    await page.clock.runFor(250);
    await expect(firstTurn).toBeFocused();
    await expect(openPreview).toContainText('第1轮：');
    await capture('11-user-turn-navigation-390');
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1440, height: 900 });
    await sidebar.getByText('今日零点会话', { exact: true }).click();
    await expect(navigation.getByRole('button')).toHaveCount(3);
    assert.deepEqual(errors, []);
    assert.deepEqual(writes, ['rename', 'create', 'delete']);
    console.log(JSON.stringify({ status: 'AI_WORKSPACE_LAYOUT_PASSED', bubble, expandedWidth, jumpGeometry, screenshots: out, checks: ['用户消息刻度、悬停预览、平滑跳转、滚动高亮、旧消息加载与切换', '计划摘要与卡片打开完整步骤', '双侧拖拽、方向键、宽度记忆及上下限', '四种折叠组合与焦点', '草稿不丢', '顶部新建', '日历分组及零点边界', '改名删除', '空计划区', '跨日活动分组', '右侧用户气泡', '中屏与移动抽屉'] }, null, 2));
  } catch (error) {
    await capture('failure');
    console.error({ errors, url: page.url() });
    console.error((await page.locator('body').innerText()).slice(-2300));
    throw error;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
