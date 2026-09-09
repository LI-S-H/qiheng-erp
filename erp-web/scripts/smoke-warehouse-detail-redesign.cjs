const fs = require('fs');
const path = require('path');
const { runSmoke } = require('./smoke-helpers.cjs');

const output = path.resolve(process.env.QA_SCREENSHOT_DIR || 'qa-artifacts/warehouse-detail-redesign');
fs.mkdirSync(output, { recursive: true });

const pages = [
  ['inbound-bills', '/warehouse/inbound-bills', 'data-stock-bill-detail-host-id', 'data-stock-bill-detail-id', 'data-stock-bill-detail-loading-id', '.stock-bill-detail-card', '**/api/warehouse/inbound-bills/*', '商品明细', '[data-stock-bill-detail-toggle-icon]', '.stock-bill-table-scroll > [data-slot="table-container"]'],
  ['outbound-bills', '/warehouse/outbound-bills', 'data-stock-bill-detail-host-id', 'data-stock-bill-detail-id', 'data-stock-bill-detail-loading-id', '.stock-bill-detail-card', '**/api/warehouse/outbound-bills/*', '商品明细', '[data-stock-bill-detail-toggle-icon]', '.stock-bill-table-scroll > [data-slot="table-container"]'],
  ['stock-bills', '/warehouse/stock-bills', 'data-stock-ledger-detail-host-id', 'data-stock-ledger-detail-id', 'data-stock-ledger-detail-loading-id', '.stock-ledger-detail-card', '**/api/warehouse/stock-bills/*', '变动明细', '[data-stock-ledger-detail-toggle-icon]', '.stock-ledger-table-scroll [data-slot="table-container"]'],
];

async function verify(page, scenario, viewport) {
  const [name, , hostAttr, detailAttr, loadingAttr, cardSelector, endpoint, title, iconSelector, mainScrollSelector] = scenario;
  const globalLoading = page.locator('[data-page-loading]');
  await globalLoading.waitFor({ state: 'hidden', timeout: 5000 });
  const hosts = page.locator(`[${hostAttr}]`);
  await hosts.first().waitFor();
  await page.route(endpoint, async request => {
    const response = await request.fetch();
    await new Promise(resolve => setTimeout(resolve, 180));
    await request.fulfill({ response, body: await response.body() });
  });

  let opened;
  for (let i = 0; i < await hosts.count(); i += 1) {
    const host = hosts.nth(i);
    const id = await host.getAttribute(hostAttr);
    const control = `${detailAttr.includes('ledger') ? 'stock-ledger-detail' : 'stock-bill-detail'}-${id}`;
    const trigger = page.locator(`button[aria-controls="${control}"]`);
    if (!id || await trigger.count() !== 1) continue;
    if (await trigger.getAttribute('aria-expanded') !== 'false') throw new Error(`${name}: initial aria-expanded invalid`);
    await trigger.click();
    const detail = page.locator(`[${detailAttr}="${id}"]`);
    const loading = page.locator(`[${loadingAttr}="${id}"]`);
    await loading.waitFor({ state: 'visible', timeout: 5000 });
    if (await globalLoading.isVisible()) throw new Error(`${name}: global loading appeared`);
    await loading.waitFor({ state: 'detached', timeout: 5000 });
    if (await detail.locator(cardSelector).count()) { opened = { trigger, detail }; break; }
    await trigger.click();
  }
  if (!opened) throw new Error(`${name}: no non-empty detail`);

  const card = opened.detail.locator(cardSelector);
  await card.waitFor();
  await page.waitForTimeout(260);
  const iconState = await opened.trigger.locator(iconSelector).evaluate(element => ({
    expanded: element.closest('button')?.getAttribute('aria-expanded'),
    rotated: element.classList.contains('rotate-90'),
  }));
  const state = await card.evaluate((element) => ({
    title: element.querySelector('.warehouse-detail-table-frame__heading')?.textContent || '',
    items: element.querySelectorAll('[data-stock-bill-expanded-item-id], [data-stock-ledger-expanded-item-id]').length,
    summaries: element.querySelectorAll('[data-stock-bill-detail-summary], [data-stock-ledger-detail-summary]').length,
    groupedHeads: element.querySelectorAll('[data-detail-table-group]').length,
    heads: element.querySelectorAll('[data-slot="table-head"]').length,
    stickyHead: (() => { const head = element.querySelector('[data-slot="table-head"]'); const style = head ? getComputedStyle(head) : null; return style ? { position: style.position, top: style.top } : null; })(),
    detailHeader: (() => { const header = element.querySelector('.warehouse-detail-table-frame__header'); return header ? { height: header.getBoundingClientRect().height, overflow: header.scrollHeight - header.clientHeight } : null; })(),
    headerLayout: (() => {
      const header = element.querySelector('.warehouse-detail-table-frame__header')?.getBoundingClientRect();
      const heading = element.querySelector('.warehouse-detail-table-frame__heading')?.getBoundingClientRect();
      const metricBounds = Array.from(element.querySelectorAll('[data-warehouse-detail-metrics] [aria-label]')).map(metric => metric.getBoundingClientRect());
      return header && heading && metricBounds.length > 0 ? {
        headingRight: heading.right,
        metricsLeft: Math.min(...metricBounds.map(metric => metric.left)),
        metricsRight: Math.max(...metricBounds.map(metric => metric.right)),
        headerRight: header.right,
      } : null;
    })(),
    headerExtraCount: element.querySelectorAll('.warehouse-detail-table-frame__header-extra').length,
    metrics: Array.from(element.querySelectorAll('[data-warehouse-detail-metrics] [aria-label]')).map(metric => metric.getAttribute('aria-label') || ''),
    overflow: element.scrollWidth - element.clientWidth,
    documentOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  }));
  if (iconState.expanded !== 'true' || !iconState.rotated || !state.title.includes(title)
    || state.items < 1 || state.summaries !== 0 || state.groupedHeads !== 0 || state.heads < 6
    || state.stickyHead?.position !== 'sticky' || state.stickyHead?.top !== '0px'
    || !state.detailHeader || state.detailHeader.height < 42 || state.detailHeader.height > 46 || state.detailHeader.overflow > 1
    || state.headerExtraCount !== 0
    || !state.headerLayout || state.headerLayout.metricsLeft < state.headerLayout.headingRight + 6
    || state.headerLayout.metricsRight > state.headerLayout.headerRight - 8
    || state.metrics.length < 1 || state.metrics.some(metric => metric.includes('单据状态'))
    || state.overflow > 1 || state.documentOverflow > 1) {
    throw new Error(`${name} detail structure invalid: ${JSON.stringify({ iconState, state })}`);
  }

  const mainScroll = page.locator(mainScrollSelector).first();
  await mainScroll.waitFor();
  await mainScroll.evaluate(element => { element.scrollLeft = element.scrollWidth; });
  await page.waitForTimeout(180);
  const [mainState, cardState] = await Promise.all([
    mainScroll.evaluate(element => { const r = element.getBoundingClientRect(); return { left: r.left, right: r.right, scrollLeft: element.scrollLeft, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth }; }),
    card.evaluate(element => { const r = element.getBoundingClientRect(); return { left: r.left, right: r.right, width: r.width }; }),
  ]);
  const detailScroll = card.locator('[data-slot="table-container"]').first();
  await detailScroll.evaluate(element => { element.scrollLeft = element.scrollWidth; });
  await page.waitForTimeout(120);
  const [detailState, lastHead] = await Promise.all([
    detailScroll.evaluate(element => { const r = element.getBoundingClientRect(); const table = element.querySelector('table')?.getBoundingClientRect(); return { left: r.left, right: r.right, scrollLeft: element.scrollLeft, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth, tableWidth: table?.width || 0 }; }),
    card.locator('[data-slot="table-head"]').last().evaluate(element => { const r = element.getBoundingClientRect(); return { left: r.left, right: r.right }; }),
  ]);
  if (mainState.scrollWidth > mainState.clientWidth + 1 && mainState.scrollLeft < 1
    || cardState.left < mainState.left + 8 || cardState.right > mainState.right + 1
    || detailState.scrollWidth > detailState.clientWidth + 1 && detailState.scrollLeft < 1
    || detailState.scrollWidth <= detailState.clientWidth + 1 && Math.abs(detailState.tableWidth - detailState.clientWidth) > 1
    || lastHead.left < detailState.left - 1 || lastHead.right > detailState.right + 1) {
    throw new Error(`${name} horizontal scroll layout invalid: ${JSON.stringify({ mainState, cardState, detailState, lastHead })}`);
  }
  await page.screenshot({ path: path.join(output, `${name}-${viewport.width}x${viewport.height}-main-scroll-end.png`), fullPage: true });
  await mainScroll.evaluate(element => { element.scrollLeft = 0; });
  await detailScroll.evaluate(element => { element.scrollLeft = 0; });
  await page.screenshot({ path: path.join(output, `${name}-${viewport.width}x${viewport.height}-detail-expanded.png`), fullPage: true });

  await opened.trigger.click();
  await page.waitForTimeout(360);
  const collapsed = await opened.trigger.locator(iconSelector).evaluate(element => ({
    expanded: element.closest('button')?.getAttribute('aria-expanded'),
    rotated: element.classList.contains('rotate-90'),
    detailHeight: document.getElementById(element.closest('button')?.getAttribute('aria-controls') || '')?.getBoundingClientRect().height || 0,
  }));
  if (await globalLoading.isVisible() || collapsed.expanded !== 'false' || collapsed.rotated || collapsed.detailHeight > 1) {
    throw new Error(`${name}: collapse state invalid: ${JSON.stringify(collapsed)}`);
  }
}

(async () => {
  for (const viewport of [{ width: 1440, height: 900 }, { width: 1115, height: 838 }]) {
    for (const scenario of pages) {
      await runSmoke({
        route: scenario[1],
        viewport,
        screenshot: path.join(output, `${scenario[0]}-${viewport.width}x${viewport.height}-final.png`),
        test: page => verify(page, scenario, viewport),
      });
    }
  }
  console.log(`SMOKE_OK: warehouse detail redesign; screenshots: ${output}`);
})().catch(error => { console.error(error); process.exitCode = 1; });
