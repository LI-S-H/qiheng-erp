// 仅验证独立原型的真实浏览器行为，不请求业务接口。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(path.resolve(__dirname, '../../../erp-web/node_modules/playwright'));
const baseUrl = 'http://127.0.0.1:5188/docs/design/2026-10-16-agent-desktop/';
const out = path.join(__dirname, 'evidence/prototype');
const checks = [];
function record(name, details) { checks.push({ name, status: '通过', details }); }

async function main() {
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce', acceptDownloads: true });
  const errors = [];
  const apiCalls = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) apiCalls.push(request.url()); });
  const capture = async name => {
    await page.evaluate(() => document.fonts.ready);
    if (await page.locator('#toast').isVisible()) await page.locator('#toast').waitFor({ state: 'hidden' });
    await page.screenshot({ path: path.join(out, name + '.png'), animations: 'disabled' });
  };
  const navigate = async route => {
    const button = page.locator(`#navigation [data-route="${route}"]`);
    if (await page.locator('#mobile-menu').isVisible() && !(await page.locator('#sidebar').getAttribute('class')).includes('open')) {
      await page.locator('#mobile-menu').click();
    }
    await button.click();
    await page.waitForTimeout(100);
    assert.ok((await page.locator('#main').innerText()).length > 50, `页面 ${route} 未渲染`);
  };
  const noOverflow = async label => {
    const dimensions = await page.evaluate(() => ({ viewport: innerWidth, width: document.documentElement.scrollWidth }));
    assert.ok(dimensions.width <= dimensions.viewport + 1, `${label} 外层横向溢出 ${JSON.stringify(dimensions)}`);
    record(label + '外层无横向溢出', dimensions);
  };
  try {
    await page.goto(baseUrl);
    await page.locator('#main h1').first().waitFor();
    for (const route of ['dashboard', 'purchase', 'inventory', 'assistant', 'canvas', 'system']) {
      await navigate(route);
      await noOverflow('1440/' + route);
      if (['dashboard', 'purchase', 'canvas', 'system'].includes(route)) await capture('desktop-' + route);
    }
    record('六个导航页面均可点击渲染');
    await page.keyboard.press('Control+k');
    assert.ok(await page.locator('#command-dialog').isVisible());
    await page.locator('#command-input').fill('库存');
    assert.ok((await page.locator('#command-results').innerText()).includes('库存'));
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#command-dialog').isVisible(), false);
    record('Ctrl+K导航筛选与Esc关闭');

    await navigate('purchase');
    await page.locator('#order-filter [name="supplier"]').fill('上海');
    await page.locator('#order-filter [name="status"]').selectOption('待审核');
    await page.locator('#order-filter').getByRole('button', { name: '查询', exact: true }).click();
    const filtered = await page.locator('#order-rows tr').allTextContents();
    assert.ok(filtered.length > 0 && filtered.every(text => text.includes('上海') && text.includes('待审核')));
    record('采购供应商与状态按AND筛选');
    await page.locator('[data-action="reset-orders"]').click();
    await page.locator('[aria-label="下一页"]').click();
    assert.equal(await page.locator('[aria-label="第 2 页"]').getAttribute('class'), 'active');
    await page.locator('[aria-label="上一页"]').click();
    record('采购重置、上一页、下一页');
    await page.locator('#order-rows [data-order]').first().click();
    assert.ok(await page.locator('#detail-dialog').isVisible());
    await capture('desktop-order-detail');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#detail-dialog').isVisible(), false);
    assert.ok(await page.locator('#order-rows [data-order]').first().evaluate(el => document.activeElement === el));
    record('采购详情打开、Esc关闭并恢复焦点');
    await page.locator('#order-rows [data-order]').first().click();
    await page.locator('[data-order-ai]').click();
    assert.ok((await page.locator('#ai-input').inputValue()).includes('演示订单'));
    record('采购详情携带演示单号进入AI');
    await page.locator('#ai-input').fill('分析华东中心仓的库存风险，先制定补货分析计划。');
    await page.getByRole('button', { name: '发送问题', exact: true }).click();
    await page.locator('#clarify-form').waitFor();
    await page.locator('#assistant-scroll').evaluate(el => { el.scrollTop = 0; });
    const bubble = await page.locator('#user-question').evaluate(el => {
      const rect = el.getBoundingClientRect();
      const row = el.parentElement.getBoundingClientRect();
      const style = getComputedStyle(el);
      return { left: rect.left, right: rect.right, rowLeft: row.left, rowRight: row.right, radius: parseFloat(style.borderTopLeftRadius), background: style.backgroundColor, width: rect.width, rowWidth: row.width };
    });
    assert.ok(Math.abs(bubble.right - bubble.rowRight) < 2 && bubble.left > bubble.rowLeft, '用户消息没有靠右');
    assert.ok(bubble.radius > 0 && bubble.background !== 'rgba(0, 0, 0, 0)', '用户消息缺少气泡');
    assert.ok(bubble.width <= Math.min(560, bubble.rowWidth * .75) + 2);
    record('用户消息靠右、蓝色气泡、宽度受限', bubble);
    await capture('desktop-user-bubble');
    await page.locator('#clarify-form [name="warehouse"]').selectOption('华东中心仓');
    await page.locator('#clarify-form [name="budget"]').fill('50000');
    await page.locator('#clarify-form').getByRole('button', { name: /生成分析计划/ }).click();
    assert.ok(await page.locator('#plan-panel').isVisible());
    assert.ok((await page.locator('#plan-body').innerText()).includes('尚未执行'));
    await capture('desktop-assistant-plan');
    await page.locator('[data-plan-tab="basis"]').click();
    assert.ok((await page.locator('#plan-body').innerText()).includes('不自动'));
    await page.locator('[data-plan-tab="plan"]').click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('[data-action="export-plan"]').click();
    const download = await downloadPromise;
    const saved = path.join(out, 'exported-plan.md');
    await download.saveAs(saved);
    const planText = fs.readFileSync(saved, 'utf8');
    assert.ok(planText.includes('50000') && planText.includes('尚未执行') && planText.includes('华东中心仓'));
    record('澄清范围生成计划、依据切换、本地Markdown导出');
    await page.setViewportSize({ width: 1115, height: 838 });
    await page.waitForTimeout(100);
    assert.ok(await page.locator('#plan-panel').evaluate(el => el.contains(document.activeElement)), '抽屉打开后焦点未进入');
    assert.ok(await page.locator('.assistant-work').evaluate(el => el.inert), '抽屉背景未隔离');
    await capture('tablet-plan-drawer');
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#plan-panel').isVisible(), false);
    assert.equal(await page.locator('.assistant-work').evaluate(el => el.inert), false);
    record('1115计划抽屉、Esc关闭');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: /计划画布/, exact: false }).first().click();
    await capture('mobile-plan-drawer');
    await page.keyboard.press('Escape');
    await page.locator('#assistant-scroll').evaluate(el => { el.scrollTop = 0; });
    await capture('mobile-user-bubble');
    await noOverflow('390/AI计划');
    await page.setViewportSize({ width: 1440, height: 900 });
    await navigate('canvas');
    await page.locator('[data-canvas-mode="ai"]').click();
    await page.locator('[data-zone="right"]').click();
    assert.ok((await page.locator('#zone-info').innerText()).includes('画布'));
    await capture('desktop-canvas-ai');
    for (const link of await page.locator('.evidence-links a').all()) {
      const response = await page.request.get(new URL(await link.getAttribute('href'), baseUrl).href);
      assert.ok(response.ok(), '现状证据链接不可访问');
    }
    record('方案画布模式/区域切换、现状截图入口');
    await navigate('system');
    const standard = await page.locator('.density-demo tbody tr').first().evaluate(el => el.getBoundingClientRect().height);
    await page.locator('[data-density="compact"]').click();
    const compact = await page.locator('.density-demo tbody tr').first().evaluate(el => el.getBoundingClientRect().height);
    assert.ok(compact < standard && compact >= 44);
    await page.locator('[data-example="error"]').click();
    assert.ok((await page.locator('#state-example').innerText()).includes('失败'));
    await page.locator('[data-action="retry-state"]').click();
    await page.getByText('暂时没有采购订单', { exact: true }).waitFor();
    record('52/44行高切换、错误和重试恢复', { standard, compact });
    await navigate('purchase');
    await page.setViewportSize({ width: 1366, height: 768 });
    await noOverflow('1366/采购');
    await capture('laptop-purchase');
    const tableY = await page.locator('#main thead').first().evaluate(el => el.getBoundingClientRect().top);
    record('1366采购表头位置', { y: tableY });
    await page.setViewportSize({ width: 390, height: 844 });
    for (const route of ['dashboard', 'purchase', 'inventory', 'assistant', 'canvas', 'system']) {
      await navigate(route);
      await noOverflow('390/' + route);
      if (['purchase', 'canvas', 'system'].includes(route)) await capture('mobile-' + route);
    }
    record('390移动导航可打开、切页并关闭');
    await navigate('assistant');
    await page.locator('[data-action="new-chat"]').click();
    const longQuestion = '请分析库存。' + 'A'.repeat(180) + '<img src=x onerror=alert(1)>';
    await page.locator('#ai-input').fill(longQuestion);
    await page.getByRole('button', { name: '发送问题', exact: true }).click();
    assert.equal(await page.locator('#user-question').innerText(), longQuestion);
    assert.equal(await page.locator('#user-question img').count(), 0);
    await page.locator('#assistant-scroll').evaluate(el => { el.scrollTop = 0; });
    const wrap = await page.locator('#user-question').evaluate(el => ({ width: el.clientWidth, scrollWidth: el.scrollWidth, height: el.clientHeight }));
    assert.ok(wrap.scrollWidth <= wrap.width + 1 && wrap.height > 40);
    await capture('mobile-long-user-bubble');
    record('长连续字符自动换行，用户输入按纯文本呈现', wrap);
    assert.deepEqual(errors, [], '浏览器存在未捕获异常');
    assert.deepEqual(apiCalls, [], '原型不应调用业务API');
    record('无浏览器未捕获异常、无业务API请求');
    fs.writeFileSync(path.join(out, 'results.json'), JSON.stringify({ status: '通过', checks, errors, apiCalls }, null, 2));
    console.log(JSON.stringify({ status: '通过', count: checks.length, checks }, null, 2));
  } catch (error) {
    await capture('failure').catch(() => {});
    console.error(error);
    console.error((await page.locator('body').innerText()).slice(-2200));
    process.exitCode = 1;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
