const fs = require('fs');
const path = require('path');
const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(
  process.env.QA_SCREENSHOT_DIR || 'docs/qa-screenshots/2026-08-10-order-workbench',
);
const screenshotPath = filename => path.join(screenshotDirectory, filename);

fs.mkdirSync(screenshotDirectory, { recursive: true });

const cases = [
  { route: '/sales/orders', row: 'SO202607003', dialog: '销售单详情', primary: '提交销售单', facts: 9, infoSelector: '.sales-workbench-info__facts', factSelector: '.sales-workbench-info__fact', expectedLabels: ['制单人', '提交人', '审核人', '备注'], hideAuditIds: true, cancelDialog: 'confirm', screenshot: 'sales-workbench.png' },
  { route: '/purchase/orders', row: 'PO202607003', dialog: '采购单详情', primary: '提交采购单', facts: 9, infoSelector: '.purchase-workbench-info__facts', factSelector: '.purchase-workbench-info__fact', expectedLabels: ['制单人', '提交人', '审核人', '备注'], hideAuditIds: true, cancelDialog: 'confirm', screenshot: 'purchase-workbench.png' },
  { route: '/sales/returns', row: 'SR202607001', dialog: '销售退货详情', primary: '提交销售退货', facts: 7, infoSelector: '.return-workbench-info__facts', factSelector: '.return-workbench-info__fact', expectedLabels: ['处理方式', '退回原因'], cancelDialog: 'prompt', screenshot: 'sales-return-workbench.png' },
  { route: '/purchase/returns', row: 'PR202607001', dialog: '采购退回详情', primary: '提交采购退回', facts: 7, infoSelector: '.return-workbench-info__facts', factSelector: '.return-workbench-info__fact', expectedLabels: ['处理方式', '退回原因'], cancelDialog: 'prompt', screenshot: 'purchase-return-workbench.png' },
  { route: '/warehouse/inbound-bills', row: 'IB202606140003', dialog: '入库单详情', primary: '提交确认', facts: 9, infoSelector: '.stock-workbench-info__facts', factSelector: '.stock-workbench-info__fact', expectedLabels: ['确认信息'], cancelDialog: 'confirm', screenshot: 'inbound-workbench.png' },
  { route: '/warehouse/outbound-bills', row: 'OB202607010002', dialog: '出库单详情', primary: '提交确认', facts: 9, infoSelector: '.stock-workbench-info__facts', factSelector: '.stock-workbench-info__fact', expectedLabels: ['确认信息'], cancelDialog: 'confirm', screenshot: 'outbound-workbench.png' },
];

async function assertCentered(dialog) {
  const position = await dialog.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const sidebar = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-shell-sidebar-width')) || 0;
    return { actual: rect.left + rect.width / 2, expected: sidebar + (window.innerWidth - sidebar) / 2 };
  });
  if (Math.abs(position.actual - position.expected) > 2) {
    throw new Error(`详情工作台未在业务内容区居中：${JSON.stringify(position)}`);
  }
}

async function assertDialogMask(page, workbench, popup) {
  const overlay = await popup.getAttribute('data-confirm-dialog') !== null
    ? page.locator('[data-slot="alert-dialog-overlay"]').last()
    : page.locator('[data-slot="dialog-overlay"]').last();
  const mask = await overlay.evaluate((element) => {
    const style = getComputedStyle(element);
    return { backdropFilter: style.backdropFilter, zIndex: style.zIndex };
  });
  if (mask.backdropFilter === 'none') throw new Error('取消弹层缺少背景模糊遮罩');
  if (!await workbench.evaluate(element => element.inert)) throw new Error('取消弹层打开后，底层业务详情仍可交互');
}

async function runCase(item) {
  await runSmoke({
    route: item.route,
    screenshot: screenshotPath(item.screenshot),
    async test(page) {
      const row = tableRow(page, item.row);
      await row.waitFor();
      const actionButton = row.getByRole('button', { name: '处理', exact: true });
      await actionButton.waitFor();
      if (await row.getByRole('button', { name: /更多 .* 操作/ }).count()) {
        throw new Error(`${item.row} 仍显示三点行操作菜单`);
      }
      await actionButton.click();
      const dialog = page.getByRole('dialog', { name: item.dialog });
      await dialog.waitFor();
      const workbench = page.locator('[data-order-workbench]');
      await workbench.waitFor();
      await assertCentered(dialog);
      await dialog.getByText('业务进度', { exact: true }).waitFor();
      const workbenchCards = await dialog.locator('.business-detail-workbench-card').count();
      if (workbenchCards !== 3) throw new Error(`${item.dialog}应有 3 张独立工作台卡片，实际为 ${workbenchCards}`);
      const cardChrome = await dialog.locator('.business-detail-workbench-card').evaluateAll((cards) => cards.map((element) => {
        const style = getComputedStyle(element);
        return { borderTopWidth: style.borderTopWidth, boxShadow: style.boxShadow };
      }));
      if (cardChrome.some(card => card.borderTopWidth !== '1px' || card.boxShadow === 'none')) {
        throw new Error(`${item.dialog}的业务进度、业务信息和流程记录卡片未使用统一边框与柔影`);
      }
      const informationBoard = dialog.locator(item.infoSelector || '.business-detail-facts--3').first();
      await informationBoard.waitFor();
      const factCount = await informationBoard.locator(item.factSelector || '.business-detail-fact').count();
      if (factCount !== item.facts) throw new Error(`${item.dialog}业务信息板应有 ${item.facts} 个字段，实际为 ${factCount}`);
      for (const label of item.expectedLabels || []) await informationBoard.getByText(label, { exact: true }).waitFor();
      const factFontSizes = await informationBoard.locator('dt, dd, dd > strong, dd > small, dd > code').evaluateAll((nodes) => [...new Set(nodes.map(node => getComputedStyle(node).fontSize))]);
      if (factFontSizes.length !== 1 || factFontSizes[0] !== '13px') {
        throw new Error(`${item.dialog}的业务信息字段字号未统一为 13px：${factFontSizes.join(',')}`);
      }
      if (item.hideAuditIds && /\b\d{15,}\b/.test(await informationBoard.innerText())) {
        throw new Error(`${item.dialog}的业务信息仍展示了人员 ID`);
      }
      await dialog.getByRole('button', { name: item.primary, exact: true }).waitFor();
      await dialog.getByRole('button', { name: '编辑', exact: true }).waitFor();
      await dialog.getByRole('button', { name: /取消/ }).first().waitFor();
      if (await dialog.getByRole('button', { name: '关闭', exact: true }).count()) {
        throw new Error(`${item.name}详情工作台不应保留底部关闭按钮`);
      }
      const cancelButton = dialog.getByRole('button', { name: /取消/ }).first();
      await cancelButton.click();
      const popup = item.cancelDialog === 'prompt'
        ? page.locator('[data-prompt-dialog]')
        : page.locator('[data-confirm-dialog]');
      await popup.waitFor();
      await assertCentered(popup);
      await assertDialogMask(page, workbench, popup);
      await popup.getByRole('button', { name: '取消', exact: true }).click();
      await popup.waitFor({ state: 'hidden' });
    },
  });
}

cases.reduce((chain, item) => chain.then(() => runCase(item)), Promise.resolve())
  .then(() => console.log('SMOKE_OK: 所有订单的单一处理入口、详情工作台与进度条通过'))
  .catch(error => {
    console.error(error);
    process.exitCode = 1;
  });
