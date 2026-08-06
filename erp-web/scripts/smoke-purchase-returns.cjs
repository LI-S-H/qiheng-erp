const fs = require('fs');
const path = require('path');
const {
  runSmoke,
  tableRow,
  assertFixedTableLayout,
  assertRequiredLabels,
  assertSharedListChrome,
  assertContentSizedFilter,
  clickQueryAndAssertLoading,
  clickRefreshAndAssertLoading,
  clickResetAndAssertLoading,
} = require('./smoke-helpers.cjs');

const screenshotDirectory = path.resolve(process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/purchase-returns');
const screenshotPath = filename => path.join(screenshotDirectory, filename);
fs.mkdirSync(screenshotDirectory, { recursive: true });

async function selectRemoteOption(page, dialog, comboboxIndex, optionPattern) {
  await dialog.getByRole('combobox').nth(comboboxIndex).click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.locator('input').fill('');
  await content.locator('[data-select-option]').filter({ hasText: optionPattern }).first().click();
}

async function selectReturnProduct(page, dialog, optionPattern) {
  await dialog.locator('[data-return-form-items] [role="combobox"]').first().click();
  const content = page.locator('[data-remote-search-select-content]').last();
  await content.locator('input').fill('');
  await content.locator('[data-select-option]:not([disabled])').filter({ hasText: optionPattern }).first().click();
}

async function selectRowAction(page, returnNo, actionLabel) {
  const row = tableRow(page, returnNo);
  await row.getByRole('button', { name: `更多 ${returnNo} 操作` }).click();
  await page.getByRole('menuitem', { name: actionLabel, exact: true }).click();
}

runSmoke({
  route: '/purchase/returns',
  screenshot: screenshotPath('purchase-returns-normal.png'),
  async test(page) {
    await page.getByRole('heading', { name: '采购退回' }).waitFor();
    await assertSharedListChrome(page, { summaryLabel: '采购退回数据汇总', filterLabel: '采购退回筛选' });
    await assertContentSizedFilter(page, [220, 220, 280, 280, 168]);
    await assertFixedTableLayout(page, 9);
    await tableRow(page, 'PR202607001').waitFor();
    for (const expected of ['PR202607001', 'PR202607002', '待提交', '等待审核', '待退货出库', '退货出库中', '已完成', '已取消']) {
      await page.getByText(expected, { exact: true }).first().waitFor();
    }
    const menuLink = page.locator('[data-menu-path="/purchase/returns"]');
    if (await menuLink.getAttribute('href') !== '/purchase/returns' || await menuLink.getAttribute('aria-current') !== 'page') {
      throw new Error('采购退回菜单入口或当前页标记不正确');
    }

    await clickRefreshAndAssertLoading(page, screenshotPath('purchase-returns-refresh.png'));
    await page.getByPlaceholder('如 PR202607001').fill('PR202607001');
    await page.getByPlaceholder('请选择原采购单').fill('PO');
    await clickQueryAndAssertLoading(page, screenshotPath('purchase-returns-query.png'));
    await tableRow(page, 'PR202607001').waitFor();
    await clickResetAndAssertLoading(page);

    const readonlyReturns = ['PR202607004', 'PR202607005', 'PR202607006'];
    for (const returnNo of readonlyReturns) {
      if (await tableRow(page, returnNo).getByRole('button', { name: `更多 ${returnNo} 操作` }).count()) {
        throw new Error(`只读采购退回单 ${returnNo} 不应显示流转操作菜单`);
      }
    }

    await selectRowAction(page, 'PR202607001', '编辑采购退回');
    const editDialog = page.getByRole('dialog', { name: '编辑采购退回' });
    await editDialog.getByText('PR202607001', { exact: true }).waitFor();
    await editDialog.getByText('采购退回（固定）', { exact: true }).waitFor();
    await assertRequiredLabels(editDialog, ['原采购单', '退货出库仓库']);
    if ((await editDialog.innerText()).includes('来源订单号') || (await editDialog.innerText()).includes('产品 ID')) {
      throw new Error('采购退回编辑弹窗暴露了只读技术字段');
    }
    await editDialog.getByRole('button', { name: '取消', exact: true }).click();

    await selectRowAction(page, 'PR202607001', '提交采购退回');
    const submitPreview = page.getByRole('dialog', { name: '采购退回详情' });
    await submitPreview.getByText('请先核对退回单头和全部明细，再提交进入待审核。').waitFor();
    await submitPreview.getByRole('button', { name: '提交采购退回', exact: true }).click();
    const submitConfirm = page.getByRole('alertdialog', { name: '提交采购退回' });
    await submitConfirm.getByText('提交后将占用申请数量并进入待审核').waitFor();
    await submitConfirm.getByRole('button', { name: '取消', exact: true }).click();
    await submitPreview.getByRole('button', { name: '关闭', exact: true }).click();

    await selectRowAction(page, 'PR202607002', '审核采购退回');
    const approvePreview = page.getByRole('dialog', { name: '采购退回详情' });
    await approvePreview.getByText('审核通过后将生成待确认采购退货出库单，不直接扣减库存。').waitFor();
    const approvalInputs = approvePreview.locator('[data-return-detail-items] input[type="number"]');
    if (await approvalInputs.count() === 0) throw new Error('采购退回审核缺少逐行审核数量');
    await approvePreview.getByRole('button', { name: '关闭', exact: true }).click();

    await page.getByRole('button', { name: '新增采购退回' }).click();
    const createDialog = page.getByRole('dialog', { name: '新增采购退回草稿' });
    await createDialog.getByText('保存后由系统生成').waitFor();
    await createDialog.getByText('采购退回（固定）', { exact: true }).waitFor();
    await assertRequiredLabels(createDialog, ['原采购单', '退货出库仓库']);
    await createDialog.getByRole('button', { name: '保存草稿', exact: true }).click();
    const validationText = await createDialog.innerText();
    if (!validationText.includes('请选择原采购单') || !validationText.includes('请选择退货出库仓库') || !validationText.includes('请至少添加一条退货明细')) {
      throw new Error('采购退回新增缺少来源、仓库或明细必填校验');
    }
    await selectRemoteOption(page, createDialog, 0, 'PO');
    await selectReturnProduct(page, createDialog, 'P');
    const itemRow = createDialog.locator('[data-return-form-items] tbody tr').last();
    await itemRow.waitFor();
    const formTableViewport = createDialog.locator('[data-return-form-items]').locator('..');
    const formTableOverflow = await formTableViewport.evaluate(element => element.scrollWidth - element.clientWidth);
    if (formTableOverflow > 2) throw new Error(`桌面端采购退回明细不应出现无意义横向滚动：${formTableOverflow}`);
    const quantityInput = itemRow.locator('input[type="number"]');
    await quantityInput.fill('999999');
    await createDialog.getByRole('button', { name: '保存草稿', exact: true }).click();
    if (!(await itemRow.innerText()).includes('不能超过剩余可退')) throw new Error('申请数量未按服务端剩余可退量校验');
    await quantityInput.fill('1');
    await createDialog.getByRole('button', { name: '保存草稿', exact: true }).click();
    await createDialog.waitFor({ state: 'hidden' });
    await page.getByText('采购退回草稿已创建', { exact: true }).waitFor();

    await page.setViewportSize({ width: 1115, height: 838 });
    await assertContentSizedFilter(page, [220, 220, 280, 280, 168]);
    const tableViewport = page.locator('[data-slot="table-container"]').first();
    const narrowState = await tableViewport.evaluate(element => ({
      overflow: element.scrollWidth - element.clientWidth,
      stickyCount: element.querySelectorAll('[data-table-sticky-edge], .sticky').length,
      pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    }));
    if (narrowState.overflow <= 2 || narrowState.stickyCount !== 0 || narrowState.pageOverflow > 1) {
      throw new Error(`较窄视口下采购退回表格滚动或页面溢出异常：${JSON.stringify(narrowState)}`);
    }
    await page.screenshot({ path: screenshotPath('purchase-returns-1115.png'), fullPage: true });
  },
}).then(() => {
  console.log('SMOKE_OK: 采购退回列表、筛选、草稿、审核预览、数量边界与响应式布局通过');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
