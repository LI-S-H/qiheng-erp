const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/dashboard-bell',
);
fs.mkdirSync(screenshotDirectory, { recursive: true });

runSmoke({
  route: '/dashboard',
  screenshot: path.join(screenshotDirectory, 'bell-loaded.png'),
  async test(page) {
    // 1. 工作台数据加载完成后,顶栏铃铛应该出现且数字 = pendingCount
    await page.locator('[data-dashboard-skeleton]').waitFor({ state: 'hidden' });
    await page.locator('[data-notification-trigger]').waitFor({ state: 'visible' });

    const trigger = page.locator('[data-notification-trigger]');
    const triggerAriaLabel = await trigger.getAttribute('aria-label');
    if (!triggerAriaLabel?.includes('待处理通知')) {
      throw new Error(`铃铛 aria-label 缺失: ${triggerAriaLabel}`);
    }

    // 2. 打开弹层
    await trigger.click();
    const popover = page.locator('[data-notification-popover]');
    await popover.waitFor({ state: 'visible' });

    const item = popover.locator('button[aria-label$="待办详情"]').first();
    await item.waitFor({ state: 'visible' });
    const label = await item.getAttribute('aria-label');
    if (!label?.startsWith('查看')) {
      throw new Error(`铃铛条目 aria-label 格式错误: ${label}`);
    }

    // 3. 点击条目,跳转到工作台详情
    await item.click();
    await page.waitForURL(url => (
      url.pathname === '/dashboard'
      && typeof url.searchParams.get('todoId') === 'string'
    ), { timeout: 5000 });
    await popover.waitFor({ state: 'hidden', timeout: 1500 });

    const dialog = page.getByRole('dialog');
    await dialog.waitFor({ state: 'visible' });
    const detail = dialog.locator('.dashboard-todo-workbench__detail');
    await detail.waitFor({ state: 'visible' });

    // 4. 详情标题应包含铃铛条目标题
    const detailTitle = (await detail.locator('h3').first().innerText()).trim();
    if (!label.includes(detailTitle)) {
      throw new Error(`铃铛条目与详情标题不一致: bell="${label}" detail="${detailTitle}"`);
    }

    // 5. 关闭详情,URL 的 todoId 参数被清除
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'hidden' });
    await page.waitForURL(url => (
      url.pathname === '/dashboard' && !url.searchParams.has('todoId')
    ), { timeout: 3000 });
  },
}).then(() => runSmoke({
  route: '/dashboard?todoId=missing',
  screenshot: path.join(screenshotDirectory, 'bell-invalid-todo.png'),
  async test(page) {
    // 无效 todoId:toast 提示后,URL 的 todoId 被清除
    await page.locator('[data-dashboard-skeleton]').waitFor({ state: 'hidden' });
    await page.waitForURL(url => (
      url.pathname === '/dashboard' && !url.searchParams.has('todoId')
    ), { timeout: 5000 });
    // 弹窗不应打开
    if (await page.getByRole('dialog').count()) {
      throw new Error('无效 todoId 不应打开待办详情弹窗');
    }
  },
})).then(() => runSmoke({
  route: '/dashboard',
  screenshot: path.join(screenshotDirectory, 'bell-bottom-cta.png'),
  async test(page) {
    // 弹层底部"前往工作台"按钮:不携带 todoId
    await page.locator('[data-dashboard-skeleton]').waitFor({ state: 'hidden' });
    await page.locator('[data-notification-trigger]').click();
    const popover = page.locator('[data-notification-popover]');
    await popover.waitFor({ state: 'visible' });
    await popover.getByRole('button', { name: '前往工作台' }).click();
    await page.waitForURL(url => (
      url.pathname === '/dashboard' && !url.searchParams.has('todoId')
    ), { timeout: 3000 });
    // 不应打开详情弹窗
    if (await page.getByRole('dialog').count()) {
      throw new Error('点击"前往工作台"按钮不应打开待办详情');
    }
  },
})).then(() => {
  console.log('SMOKE_OK: 铃铛复用工作台 overview、无效 todoId 回退与底部 CTA 均通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
