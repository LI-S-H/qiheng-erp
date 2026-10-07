const path = require('path');
const { runSmoke, tableRow } = require('./smoke-helpers.cjs');

runSmoke({
  route: '/warehouse/inbound-bills',
  screenshot: path.resolve('qa-artifacts/inbound-expected-arrival-date.png'),
  async test(page) {
    await page.getByRole('heading', { name: '入库单' }).waitFor();
    const row = tableRow(page, 'IB-20260815-00010');
    await row.waitFor();

    const expectedDate = await row.locator('[data-stock-bill-expected-arrival-date]').innerText();
    if (expectedDate.trim() !== '2026-08-16') {
      throw new Error(`入库单列表预计到货日期错误：${expectedDate}`);
    }

    await page.locator('[data-stock-bill-column-trigger]').click();
    await page.locator('[data-stock-bill-column-key="expectedArrivalDate"]').waitFor();
    await page.keyboard.press('Escape');

    await row.getByRole('button', { name: '查看', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '入库单详情' });
    await dialog.waitFor();
    const detailExpectedDate = await dialog.locator('[data-stock-bill-detail-expected-arrival-date]').innerText();
    if (!detailExpectedDate.includes('预计到货日期') || !detailExpectedDate.includes('2026-08-16')) {
      throw new Error(`入库单详情预计到货日期错误：${detailExpectedDate}`);
    }
    await page.screenshot({
      path: path.resolve('qa-artifacts/inbound-expected-arrival-date-detail.png'),
      fullPage: true,
    });
    await dialog.getByRole('button', { name: 'Close' }).click();

    await page.goto(`${new URL(page.url()).origin}/warehouse/outbound-bills`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '出库单' }).waitFor();
    await page.locator('[data-stock-bill-column-trigger]').click();
    if (await page.locator('[data-stock-bill-column-key="expectedArrivalDate"]').count() !== 0) {
      throw new Error('出库单列设置不应出现预计到货日期');
    }
  },
}).then(() => {
  console.log('SMOKE_OK: inbound expected arrival date');
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});
