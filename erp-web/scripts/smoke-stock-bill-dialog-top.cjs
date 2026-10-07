const path = require('path');
const fs = require('fs');
const { runSmoke } = require('./smoke-helpers.cjs');

const output = path.resolve(__dirname, '..', 'docs', 'qa-screenshots', '2026-09-18-stock-bill-detail');
fs.mkdirSync(output, { recursive: true });

async function verify(page, route, actionClass) {
  await page.locator('[data-page-loading]').waitFor({ state: 'hidden', timeout: 5000 });
  const actions = page.locator(`tbody .${actionClass} button`);
  await actions.first().waitFor({ state: 'visible', timeout: 8000 });
  if (await actions.count() === 0) throw new Error(`${route}: 未找到详情入口`);
  await actions.first().click();
  const hero = page.locator('[data-stock-bill-detail-top]');
  await hero.waitFor();
  const dialog = page.locator('[data-slot="dialog-content"]').filter({ has: hero });
  const state = await hero.evaluate((element) => ({
    metricCount: element.querySelectorAll('.business-detail-hero__metrics').length,
    topRightSummaryCount: document.querySelectorAll('.stock-bill-detail-top__metric, .stock-bill-detail-top__source').length,
    progressCount: document.querySelectorAll('.stock-bill-detail-progress').length,
    title: element.querySelector('.business-detail-hero__title')?.textContent?.trim(),
  }));
  if (state.metricCount !== 0 || state.topRightSummaryCount !== 0 || state.progressCount !== 1 || !state.title) {
    throw new Error(`${route}: 顶部详情结构异常 ${JSON.stringify(state)}`);
  }
  await dialog.screenshot({ path: path.join(output, `${route.split('/').at(-1)}-1440x900-dialog-top.png`) });
  await page.keyboard.press('Escape');
  await hero.waitFor({ state: 'hidden' });
}

(async () => {
  await runSmoke({ route: '/warehouse/inbound-bills', screenshot: path.join(output, 'inbound-bills-1440x900-list.png'), test: page => verify(page, '/warehouse/inbound-bills', 'stock-bill-actions-column') });
  await runSmoke({ route: '/warehouse/outbound-bills', screenshot: path.join(output, 'outbound-bills-1440x900-list.png'), test: page => verify(page, '/warehouse/outbound-bills', 'stock-bill-actions-column') });
  console.log(`SMOKE_OK: 仓储作业单详情顶部无无效汇总，截图目录：${output}`);
})().catch(error => { console.error(error); process.exitCode = 1; });
