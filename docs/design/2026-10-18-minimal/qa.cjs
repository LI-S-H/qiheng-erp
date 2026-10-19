const { chromium } = require('../../../erp-web/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const results = [];
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const base = 'http://127.0.0.1:5190/docs/design/2026-10-18-minimal/';
    await page.goto(base);
    await page.locator('tbody tr').first().waitFor();
    await page.evaluate(() => document.fonts.ready);
    assert.deepEqual(await page.locator('thead th').allTextContents(), ['采购单号', '供应商', '入库仓库', '状态', '订单金额', '预计到货', '创建人', '更新时间', '操作']);
    assert.equal(await page.locator('tbody tr').count(), 10);
    assert.deepEqual(await page.locator('.summary-line dd').allTextContents(), ['1', '2', '3', '4']);
    const source = JSON.parse(fs.readFileSync(path.join(__dirname, 'source-pages.json'), 'utf8'));
    const tableText = await page.locator('tbody').innerText();
    for (const row of source.purchase.rows) for (const cell of row) for (const part of cell.split('\n')) assert.ok(tableText.replace(/\s/g, '').includes(part.replace(/\s/g, '')), `缺少源内容：${part}`);
    await page.screenshot({ path: path.join(__dirname, 'purchase-1440.png') });
    results.push('采购9列、10条、4项汇总及首屏全部源内容保持');
    await page.getByLabel('采购单号', { exact: true }).fill('PO-20260700-3');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.equal(await page.locator('tbody tr').count(), 1);
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.getByRole('button', { name: '下一页', exact: true }).click();
    assert.equal(await page.locator('tbody tr').count(), 1);
    await page.getByRole('button', { name: '上一页', exact: true }).click();
    await page.locator('[data-order]').first().click();
    assert.equal(await page.locator('#info-dialog').isVisible(), true);
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('#info-dialog').isVisible(), false);
    results.push('查询、重置、分页和只读弹窗可用');
    await page.locator('[data-nav="库存管理"]').evaluate(button => button.closest('details').open = true);
    await page.locator('[data-nav="库存管理"]').click();
    assert.equal(await page.locator('thead th').count(), 10);
    assert.equal(await page.locator('tbody tr').count(), 10);
    assert.deepEqual(await page.locator('.summary-line dd').allTextContents(), ['4', '9', '4', '5']);
    await page.screenshot({ path: path.join(__dirname, 'stock-1440.png') });
    await page.getByLabel('产品编码', { exact: true }).fill('P000002');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.ok((await page.locator('tbody').innerText()).includes('速溶黑咖啡'));
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.getByRole('button', { name: '查看风险库存', exact: true }).click();
    assert.ok(!((await page.locator('tbody').innerText()).includes('库存正常')));
    results.push('库存10列、当前页汇总、产品查询、风险预设保持');
    for (const viewport of [{ width: 1115, height: 838 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      await page.goto(base + '#purchase');
      await page.locator('tbody tr').first().waitFor();
      if (viewport.width === 390) {
        await page.waitForFunction(() => document.querySelector('#sidebar').getBoundingClientRect().right <= 0);
        await page.getByRole('button', { name: '打开导航', exact: true }).click();
        assert.ok((await page.locator('#sidebar').getAttribute('class')).includes('open'));
        await page.keyboard.press('Escape');
        assert.ok(!(await page.locator('#sidebar').getAttribute('class')).includes('open'));
      }
      const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: innerWidth }));
      assert.ok(dimensions.width <= dimensions.viewport + 1, JSON.stringify(dimensions));
      await page.screenshot({ path: path.join(__dirname, `purchase-${viewport.width}.png`), animations: 'disabled' });
      results.push(`${viewport.width}px 页面无外层横向溢出，宽表保留容器内滚动`);
    }
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(__dirname, 'qa-results.json'), JSON.stringify({ passed: true, results, errors }, null, 2));
    console.log(JSON.stringify({ passed: true, results }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
