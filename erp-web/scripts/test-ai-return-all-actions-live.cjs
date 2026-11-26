const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const BASE_URL = process.env.AI_LIVE_URL || 'http://127.0.0.1:5137';
const RETURN_TYPE = process.env.AI_RETURN_TYPE || 'SALES_RETURN';
const SOURCE_SALES_NO = process.env.AI_SOURCE_SALES_NO || 'SO2026112000630';
const SOURCE_PURCHASE_NO = process.env.AI_SOURCE_PURCHASE_NO || 'PO2026111600004';
const SOURCE_NO = RETURN_TYPE === 'PURCHASE_RETURN' ? SOURCE_PURCHASE_NO : SOURCE_SALES_NO;
const PRODUCT_CODE = process.env.AI_PRODUCT_CODE || (RETURN_TYPE === 'PURCHASE_RETURN' ? 'P000087' : 'P000438');
const RETURN_LABEL = RETURN_TYPE === 'PURCHASE_RETURN' ? '采购退货单' : '销售退货单';
const SOURCE_LABEL = RETURN_TYPE === 'PURCHASE_RETURN' ? '采购单' : '销售单';
const PARTY_LABEL = RETURN_TYPE === 'PURCHASE_RETURN' ? '供应商' : '客户';
const screenshotBase = path.resolve(__dirname, '../../docs/qa-screenshots/2026-11-26-ai-return-hitl');
const SHOT_PREFIX = RETURN_TYPE === 'PURCHASE_RETURN' ? 'purchase' : 'sales';
const screenshotDir = path.join(screenshotBase, SHOT_PREFIX);
fs.mkdirSync(screenshotDir, { recursive: true });

const report = { flows: [], writes: [], resolves: [], failures: [] };
let createdReturn = null;

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_BROWSER_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on('pageerror', error => report.failures.push(`pageerror: ${error.message}`));
  page.on('response', async response => {
    const request = response.request();
    const url = response.url();
    const method = request.method();
    if (method === 'GET') return;
    if (url.includes('/return-drafts/') || url.includes('/return-actions/')) {
      let body = '';
      try { body = await response.text(); } catch { /* 忽略读取失败 */ }
      report.resolves.push({ method, url: url.replace(/^.*\/api/, ''), status: response.status(), body: body.slice(0, 4000) });
      return;
    }
    if (url.includes('/returns')) {
      let body = '';
      try { body = await response.text(); } catch { /* 忽略读取失败 */ }
      report.writes.push({ method, url: url.replace(/^.*\/api/, ''), status: response.status(), body: body.slice(0, 4000) });
    }
  });

  try {
    await page.goto(`${BASE_URL}/ai/assistant`);
    if (page.url().includes('/login')) {
      await page.locator('#username').fill(process.env.AI_QA_USERNAME || 'admin');
      await page.locator('#password').fill(process.env.AI_QA_PASSWORD || '123456');
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await page.waitForURL(url => !url.pathname.includes('/login'), { timeout: 20000 });
      await page.goto(`${BASE_URL}/ai/assistant`);
    }

    // ---------- 1. 创建销售退货单 ----------
    if (process.env.AI_RETURN_NO && process.env.AI_RETURN_ID) {
      createdReturn = { no: process.env.AI_RETURN_NO, id: process.env.AI_RETURN_ID };
      report.flows.push({ flow: 'create', reused: createdReturn });
      console.log('REUSED', JSON.stringify(createdReturn));
    } else {
      const createPrompt = `请为${SOURCE_LABEL} ${SOURCE_NO} 创建一张${RETURN_LABEL}草稿：商品 ${PRODUCT_CODE} 退 1 件，处理方式选退款，退货原因选质量问题，原因说明写“来货外包装破损”，预计执行日期 2026-12-05，退货仓库按来源单仓库。先查可退明细，然后直接调用创建工具把草稿交给我确认；不要再使用追问工具。`;
      const createDialog = await sendAndWait(page, createPrompt, 'return-draft', 'create');
      const createScroll = await assertDialogBody(page, createDialog, 'create');
      const createText = await createDialog.innerText();
      if (!createText.includes(PRODUCT_CODE)) throw new Error(`创建弹窗未展示商品明细：${createText.slice(0, 300)}`);
      const expectParty = RETURN_TYPE === 'PURCHASE_RETURN' ? '供应商' : '客户';
      if (!createText.includes(expectParty)) throw new Error(`创建弹窗未展示${expectParty}信息：${createText.slice(0, 400)}`);
      report.flows.push({ flow: 'create', scrollHeight: createScroll.height, dialogText: createText.slice(0, 500) });
      await confirmDraft(page, createDialog, 'create');

      const createWrite = lastWrite('POST', '/returns');
      if (!createWrite) throw new Error('创建未产生 POST /returns 写请求');
      const createPayload = JSON.parse(createWrite.body).data;
      createdReturn = { no: createPayload.returnNo, id: String(createPayload.returnOrderId) };
      console.log('CREATED', JSON.stringify(createdReturn));
    }

    // ---------- 2. 编辑 ----------
    const editPrompt = `请把${RETURN_LABEL} ${createdReturn.no} 的本次退货数量改为 3，原因说明改为“来货包装破损需退回”，其余不变。先查详情，然后直接调用修改工具把修改交给我确认；不要再使用追问工具。`;
    const editDialog = await sendAndWait(page, editPrompt, 'return-draft', 'edit');
    const editScroll = await assertDialogBody(page, editDialog, 'edit');
    const qtyInput = editDialog.locator('input[type="number"]').first();
    if (await qtyInput.isDisabled()) throw new Error('编辑弹窗数量输入仍被禁用，未进入可编辑态');
    const qtyBefore = await qtyInput.inputValue();
    await qtyInput.fill('3');
    report.flows.push({ flow: 'edit', scrollHeight: editScroll.height, qtyBefore, dialogText: (await editDialog.innerText()).slice(0, 500) });
    await page.screenshot({ path: path.join(screenshotDir, 'live-all-edit-filled.png') });
    await confirmDraft(page, editDialog, 'edit');
    const editResolve = lastResolve('return-drafts');
    if (!editResolve) throw new Error('编辑未产生 return-drafts resolve 回执');
    const editWrite = lastWrite('PUT');
    if (!editWrite) throw new Error('编辑未产生 PUT /returns 写请求');

    // ---------- 3. 提交审核 ----------
    const submitPrompt = `请把退货单 ${createdReturn.no} 提交审核，预计执行日期保持 2026-12-05。先查详情，然后直接调用提交工具把单据交给我确认；不要再使用追问工具，我会在确认卡片上确认。`;
    const submitDialog = await sendAndWait(page, submitPrompt, 'return-action', 'submit');
    const submitScroll = await assertDialogBody(page, submitDialog, 'submit');
    const submitText = await submitDialog.innerText();
    if (!submitText.includes(createdReturn.no)) throw new Error(`提交弹窗未展示单号：${submitText.slice(0, 300)}`);
    report.flows.push({ flow: 'submit', scrollHeight: submitScroll.height, dialogText: submitText.slice(0, 500) });
    await page.screenshot({ path: path.join(screenshotDir, 'live-all-submit-dialog.png') });
    // 小窗口下也必须能看到单据内容，避免再次出现“只剩标题和按钮”。
    await page.setViewportSize({ width: 600, height: 500 });
    await page.waitForTimeout(400);
    const smallScroll = await assertDialogBody(page, submitDialog, 'submit-small');
    if (smallScroll.height < 180) throw new Error(`小窗口提交弹窗内容区高度异常：${smallScroll.height}`);
    await page.screenshot({ path: path.join(screenshotDir, 'live-all-submit-small.png') });
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.waitForTimeout(400);
    await confirmAction(page, submitDialog, 'submit');
    const submitResolve = lastResolve('return-actions');
    if (!submitResolve) throw new Error('提交未产生 return-actions resolve 回执');
    if (!lastWrite('POST', '/submit')) throw new Error('提交未调用提交审核接口');

    // ---------- 4. 取消 ----------
    const cancelPrompt = `请取消退货单 ${createdReturn.no}，取消原因写“客户取消订单”。先查详情，然后直接调用取消工具把单据交给我确认；不要再使用追问工具，我会在确认卡片上确认。`;
    const cancelDialog = await sendAndWait(page, cancelPrompt, 'return-action', 'cancel');
    const cancelScroll = await assertDialogBody(page, cancelDialog, 'cancel');
    const cancelText = await cancelDialog.innerText();
    if (!cancelText.includes(createdReturn.no)) throw new Error(`取消弹窗未展示单号：${cancelText.slice(0, 300)}`);
    report.flows.push({ flow: 'cancel', scrollHeight: cancelScroll.height, dialogText: cancelText.slice(0, 500) });
    await page.screenshot({ path: path.join(screenshotDir, 'live-all-cancel-dialog.png') });
    await confirmAction(page, cancelDialog, 'cancel');
    const cancelResolve = lastResolve('return-actions', 2);
    if (!cancelResolve) throw new Error('取消未产生 return-actions resolve 回执');
    if (!lastWrite('POST', '/cancel')) throw new Error('取消未调用取消接口');

    await page.waitForTimeout(8000);
    await page.screenshot({ path: path.join(screenshotDir, 'live-all-done.png') });
    report.finalText = await page.locator('[data-run-id]').last().innerText().catch(() => '');
  } finally {
    fs.writeFileSync(path.join(screenshotDir, 'live-all-report.json'), JSON.stringify(report, null, 2), 'utf8');
    await browser.close();
  }
}

/** 新建会话、发送需求并等待目标确认面板出现。 */
async function sendAndWait(page, prompt, kind, label) {
  await page.getByRole('button', { name: '新建会话' }).click();
  await page.waitForFunction(() => !document.querySelector('.conversation-switching'));
  await page.getByLabel('经营问题').fill(prompt);
  await page.getByRole('button', { name: '发送需求' }).click();
  const dock = kind === 'return-draft' ? '[data-ai-return-draft]' : '[data-ai-return-action]';
  await waitForDock(page, dock, label);
  const openTestId = kind === 'return-draft' ? 'return-draft-open' : 'return-action-open';
  await page.getByTestId(openTestId).click();
  const dialog = page.locator(kind === 'return-draft' ? '[data-ai-return-draft-dialog]' : '[data-ai-return-action-dialog]');
  await dialog.waitFor({ timeout: 15000 });
  // 等待单据信息加载完成：内容区真实高度 > 0 才算用户能看到。
  await page.waitForFunction(selector => {
    const el = document.querySelector(selector);
    const scroll = el?.querySelector('[data-dialog-scroll-area]');
    return Boolean(scroll && scroll.getBoundingClientRect().height > 100);
  }, kind === 'return-draft' ? '[data-ai-return-draft-dialog]' : '[data-ai-return-action-dialog]', { timeout: 30000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(screenshotDir, `live-all-${label}-dialog.png`) });
  return dialog;
}

/** 等待确认面板；模型若改用追问工具，自动选择“确认/提交/取消”类选项继续。 */
async function waitForDock(page, dock, label) {
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    if (await page.locator(dock).isVisible().catch(() => false)) return;
    const questionDock = page.locator('[data-ai-question-dock]');
    if (await questionDock.isVisible().catch(() => false)) {
      await answerClarification(page, questionDock, label);
      continue;
    }
    await page.waitForTimeout(800);
  }
  const body = await page.locator('body').innerText();
  await page.screenshot({ path: path.join(screenshotDir, `live-all-${label}-no-dock.png`) });
  throw new Error(`${label} 未出现确认面板：\n${body.slice(-1500)}`);
}

/** 选中符合意图的选项并提交回答。 */
async function answerClarification(page, questionDock, label) {
  const options = questionDock.locator('[data-testid^="question-"]');
  const count = await options.count();
  let target = null;
  for (let index = 0; index < count; index++) {
    const text = await options.nth(index).innerText();
    if (/确认提交|提交|确认取消|取消|按当前|继续/.test(text)) {
      target = options.nth(index);
      break;
    }
  }
  await (target || options.first()).click();
  await page.screenshot({ path: path.join(screenshotDir, `live-all-${label}-question.png`) });
  await questionDock.getByRole('button', { name: /确认回答|下一题/ }).first().click();
  await page.waitForTimeout(1000);
}

async function assertDialogBody(page, dialog, label) {
  const box = await dialog.locator('[data-dialog-scroll-area]').boundingBox();
  if (!box || box.height < 150) {
    await page.screenshot({ path: path.join(screenshotDir, `live-all-${label}-collapsed.png`) });
    throw new Error(`${label} 弹窗内容区高度异常：${JSON.stringify(box)}`);
  }
  return box;
}

async function confirmDraft(page, dialog, label) {
  const responsePromise = page.waitForResponse(
    response => response.url().includes('/return-drafts/') && response.request().method() === 'POST',
    { timeout: 120000 },
  );
  await page.getByTestId('return-draft-confirm').click();
  const response = await responsePromise;
  if (response.status() !== 200) throw new Error(`${label} 保存回执失败：${response.status()}`);
  await dialog.waitFor({ state: 'detached', timeout: 30000 });
}

async function confirmAction(page, dialog, label) {
  const responsePromise = page.waitForResponse(
    response => response.url().includes('/return-actions/') && response.request().method() === 'POST',
    { timeout: 120000 },
  );
  await page.getByTestId('return-action-confirm').click();
  const confirm = page.locator('[data-confirm-dialog]');
  await confirm.waitFor({ timeout: 10000 });
  await page.screenshot({ path: path.join(screenshotDir, `live-all-${label}-confirm.png`) });
  await confirm.getByRole('button', { name: label === 'submit' ? '提交审核' : '确认取消', exact: true }).click();
  const response = await responsePromise;
  if (response.status() !== 200) throw new Error(`${label} 处理回执失败：${response.status()}`);
  await dialog.waitFor({ state: 'detached', timeout: 30000 });
}

/** 返回最近一条指定 resolve 接口回执体。 */
function lastResolve(kind, offset = 1) {
  const list = report.resolves.filter(item => item.url.includes(kind));
  const item = list[list.length - offset];
  if (!item) return null;
  try {
    const parsed = JSON.parse(item.body);
    return parsed.data ?? parsed;
  } catch {
    return null;
  }
}

/** 返回最近一条写请求。 */
function lastWrite(method, suffix = '') {
  const list = report.writes.filter(item => item.method === method && item.url.includes(suffix));
  return list[list.length - 1] || null;
}

main().then(() => {
  console.log('ALL FLOWS OK');
  console.log(JSON.stringify(report.flows, null, 2));
}).catch(error => {
  console.error('FAILED:', error.message);
  console.error(JSON.stringify(report, null, 2).slice(0, 3000));
  process.exitCode = 1;
});