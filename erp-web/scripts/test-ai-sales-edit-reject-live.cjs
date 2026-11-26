const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:5137';
const SALES_ORDER_NO = process.env.AI_SALES_ORDER_NO || 'SO2026112600001';
const SALES_ORDER_ID = process.env.AI_SALES_ORDER_ID || '2125915562834964481';
const screenshotDir = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-sales-edit-reject');
fs.mkdirSync(screenshotDir, { recursive: true });

/**
 * 销售单编辑态驳回真实链路回归：
 * 编辑态直接出现驳回入口 -> 第二层驳回 -> 回执 rejected -> 单据未写入。
 */
(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  try {
    await page.goto(`${BASE_URL}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 20000 });
      await page.goto(`${BASE_URL}/ai/assistant`);
    }
    const before = await readOrderSnapshot(page);
    assert.equal(before.salesNo, SALES_ORDER_NO, '销售单号不匹配');

    await page.getByRole('button', { name: '新建会话' }).click();
    await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
    await page.getByLabel('经营问题').fill(
      `请把销售单 ${SALES_ORDER_NO} 的预计发货日期改为 2026-12-10，备注改为“AI 编辑验证”，其余不变。先查详情，然后直接调用修改销售单工具交给我确认；不要使用追问工具。`,
    );
    await page.getByRole('button', { name: '发送需求' }).click();

    await waitForSalesDraft(page);
    await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-01-card.png') });
    await page.getByTestId('sales-draft-open').click();

    const dialog = page.locator('[data-sales-draft-dialog]');
    await dialog.waitFor({ timeout: 15000 });
    await dialog.getByRole('button', { name: '保存修改', exact: true }).waitFor({ timeout: 15000 });
    await page.waitForTimeout(400);
    const rejectEntry = dialog.getByRole('button', { name: '驳回并说明', exact: true });
    assert.equal(await rejectEntry.isVisible(), true, '销售单编辑态缺少驳回入口');
    await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-02-edit.png') });

    await rejectEntry.click();
    const reject = page.locator('[data-sales-draft-reject]');
    await reject.waitFor({ timeout: 10000 });
    assert.equal(
      await page.evaluate(() => document.querySelector('[data-sales-draft-dialog]')?.inert === true),
      true,
      '驳回打开后父弹窗未锁定',
    );
    await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-03-layer.png') });

    await reject.locator('button[aria-pressed]').first().click();
    await reject.locator('textarea').fill('仅验证编辑态驳回入口，不保存');
    await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-04-filled.png') });

    const resolvePromise = page.waitForResponse(
      response => response.url().includes('/sales-drafts/') && response.request().method() === 'POST',
      { timeout: 120000 },
    );
    await page.getByTestId('sales-draft-reject-confirm').click();
    const resolve = await resolvePromise;
    assert.equal(resolve.status(), 200, `驳回回执失败：${resolve.status()}`);
    assert.equal(resolve.request().postDataJSON().status, 'rejected', '驳回回执状态不正确');
    await page.waitForFunction(() => !document.querySelector('[data-sales-draft-dialog]'), null, { timeout: 15000 });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-05-settled.png') });

    const after = await readOrderSnapshot(page);
    assert.equal(after.version, before.version, '驳回后销售单版本被改变');
    assert.equal(after.expectedDeliveryDate, before.expectedDeliveryDate, '驳回后预计发货日期被改变');
    assert.equal(after.remark, before.remark, '驳回后备注被改变');
    assert.deepEqual(errors, []);
    console.log('SALES EDIT REJECT OK', JSON.stringify({ orderNo: SALES_ORDER_NO, version: after.version, date: after.expectedDeliveryDate, remark: after.remark }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

/** 等销售草稿卡片；模型若追问，自动选择确认类选项继续。 */
async function waitForSalesDraft(page) {
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    if (await page.locator('[data-ai-sales-draft]').isVisible().catch(() => false)) return;
    const question = page.locator('[data-ai-question-dock]');
    if (await question.isVisible().catch(() => false)) {
      await question.locator('[data-testid^="question-"]').first().click();
      await question.getByRole('button', { name: /确认回答|下一题/ }).first().click();
      continue;
    }
    await page.waitForTimeout(800);
  }
  const body = await page.locator('body').innerText();
  await page.screenshot({ path: path.join(screenshotDir, 'sales-edit-reject-entry-flow-no-card.png') });
  throw new Error(`未出现销售草稿卡片：${body.slice(-1200)}`);
}

/** 通过登录会话直接读取销售单快照，验证驳回没有写入。 */
async function readOrderSnapshot(page) {
  return page.evaluate(async orderId => {
    const token = localStorage.getItem('erp_auth_token');
    const header = localStorage.getItem('erp_auth_token_name') || 'satoken';
    const response = await fetch(`/api/sales/orders/${orderId}`, { headers: { [header]: token } });
    const payload = await response.json();
    const data = payload.data;
    return {
      salesNo: data.salesNo,
      version: data.version,
      expectedDeliveryDate: data.expectedDeliveryDate,
      remark: data.remark,
    };
  }, SALES_ORDER_ID);
}