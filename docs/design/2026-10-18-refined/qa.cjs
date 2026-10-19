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
    const base = 'http://127.0.0.1:5191/docs/design/2026-10-18-refined/';
    const settle = () => page.waitForFunction(() => !document.querySelector('.table-panel[aria-busy="true"]'));
    await page.goto(base);
    await page.locator('tbody tr').first().waitFor();
    await page.evaluate(() => document.fonts.ready);
    assert.deepEqual(await page.locator('thead th').allTextContents(), ['采购单号', '供应商', '入库仓库', '状态', '订单金额', '预计到货', '创建人', '更新时间', '操作']);
    assert.equal(await page.locator('tbody tr').count(), 10);
    assert.deepEqual(await page.locator('.summary-line dd').allTextContents(), ['1', '2', '3', '4']);
    const alignment = await page.evaluate(() => ({
      heads: [...document.querySelectorAll('thead th')].map(cell => getComputedStyle(cell).textAlign),
      cells: [...document.querySelector('tbody tr').children].map(cell => getComputedStyle(cell).textAlign),
      controls: [...document.querySelectorAll('.filter-bar input, .filter-bar select, .filter-actions button')].map(element => ({ height: element.getBoundingClientRect().height, bottom: element.getBoundingClientRect().bottom })),
      title: getComputedStyle(document.querySelector('.table-heading h2')).fontSize,
    }));
    assert.deepEqual(alignment.heads, alignment.cells);
    assert.deepEqual(alignment.heads, ['left', 'left', 'left', 'center', 'right', 'center', 'center', 'left', 'center']);
    assert.ok(alignment.controls.every(control => control.height === 36));
    assert.ok(Math.max(...alignment.controls.map(control => control.bottom)) - Math.min(...alignment.controls.map(control => control.bottom)) < 1);
    assert.equal(alignment.title, '14px');
    results.push('表头/内容按字段类型对齐、筛选与按钮36px同一基线、列表标题14px');
    const source = JSON.parse(fs.readFileSync(path.join(__dirname, 'source-pages.json'), 'utf8'));
    const tableText = await page.locator('tbody').innerText();
    for (const row of source.purchase.rows) for (const [index, cell] of row.entries()) {
      // 用户明确删除状态副说明，其余展示内容仍逐项核对原快照。
      const parts = index === 3 ? [cell.split('\n')[0]] : cell.split('\n');
      for (const part of parts) assert.ok(tableText.replace(/\s/g, '').includes(part.replace(/\s/g, '')), `缺少源内容：${part}`);
    }
    assert.equal(await page.locator('tbody tr td:nth-child(4) .secondary').count(), 0);
    assert.equal(await page.locator('tbody tr td:nth-child(4) .tag').count(), 10);
    results.push('状态仅保留标签，无重复副说明；其它源字段保持');
    await page.screenshot({ path: path.join(__dirname, 'purchase-1440.png') });
    results.push('采购9列、10条、4项汇总及首屏全部源内容保持');
    await page.getByLabel('采购单号', { exact: true }).fill('PO-20260700-3');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: '查询中', exact: true }).isDisabled(), true);
    await settle();
    assert.equal(await page.locator('tbody tr').count(), 1);
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await settle();
    await page.getByRole('button', { name: '下一页', exact: true }).click();
    await settle();
    assert.equal(await page.locator('tbody tr').count(), 1);
    await page.getByRole('button', { name: '上一页', exact: true }).click();
    await settle();
    await page.locator('[data-order]').first().click();
    assert.equal(await page.locator('#info-dialog').isVisible(), true);
    const centers = await page.evaluate(() => {
      const dialog = document.querySelector('#info-dialog').getBoundingClientRect();
      const workspace = document.querySelector('.workspace').getBoundingClientRect();
      return { dialog: dialog.x + dialog.width / 2, workspace: workspace.x + workspace.width / 2 };
    });
    assert.ok(Math.abs(centers.dialog - centers.workspace) <= 1, JSON.stringify(centers));
    await page.screenshot({ path: path.join(__dirname, 'purchase-detail-1440.png') });
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
    await settle();
    assert.ok((await page.locator('tbody').innerText()).includes('速溶黑咖啡'));
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await settle();
    await page.getByRole('button', { name: '查看风险库存', exact: true }).click();
    await settle();
    assert.ok(!((await page.locator('tbody').innerText()).includes('库存正常')));
    assert.equal(await page.getByRole('button', { name: '查看风险库存', exact: true }).getAttribute('aria-pressed'), 'true');
    results.push('库存10列、当前页汇总、产品查询、风险预设保持');
    for (const viewport of [{ width: 1920, height: 1080 }, { width: 1115, height: 838 }, { width: 390, height: 844 }]) {
      await page.setViewportSize(viewport);
      await page.goto(base + '#purchase');
      await page.locator('tbody tr').first().waitFor();
      if (viewport.width === 390) {
        await page.waitForFunction(() => document.querySelector('#sidebar').getBoundingClientRect().right <= 0);
        await page.getByRole('button', { name: '打开导航', exact: true }).click();
        assert.ok((await page.locator('#sidebar').getAttribute('class')).includes('open'));
        assert.equal(await page.locator('.workspace').evaluate(element => element.inert), true);
        await page.keyboard.press('Escape');
        assert.ok(!(await page.locator('#sidebar').getAttribute('class')).includes('open'));
        assert.equal(await page.locator('.workspace').evaluate(element => element.inert), false);
      }
      if (viewport.width === 1920) {
        const center = await page.evaluate(() => {
          const main = document.querySelector('main').getBoundingClientRect();
          const workspace = document.querySelector('.workspace').getBoundingClientRect();
          return { main: main.x + main.width / 2, workspace: workspace.x + workspace.width / 2 };
        });
        assert.ok(Math.abs(center.main - center.workspace) <= 1);
      }
      const dimensions = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: innerWidth }));
      assert.ok(dimensions.width <= dimensions.viewport + 1, JSON.stringify(dimensions));
      await page.screenshot({ path: path.join(__dirname, `purchase-${viewport.width}.png`), animations: 'disabled' });
      results.push(`${viewport.width}px 页面无外层横向溢出，宽表保留容器内滚动`);
    }
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(base);
    await page.locator('tbody tr').first().waitFor();
    await page.getByLabel('采购单号', { exact: true }).fill('没有此采购单');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await settle();
    assert.equal(await page.locator('.empty-row').innerText(), '暂无采购订单');
    await page.screenshot({ path: path.join(__dirname, 'purchase-empty-1440.png') });
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await settle();
    await page.getByLabel('每页条数', { exact: true }).selectOption('20');
    await settle();
    assert.equal(await page.locator('tbody tr').count(), 11);
    await page.getByRole('button', { name: '刷新', exact: true }).click();
    await settle();
    assert.equal(await page.locator('tbody tr').count(), 11);
    await page.getByLabel('每页条数', { exact: true }).selectOption('10');
    await settle();
    const tableGeometry = await page.evaluate(() => {
      const table = document.querySelector('.table-scroll');
      const header = document.querySelector('thead').getBoundingClientRect();
      const row = document.querySelector('tbody tr').getBoundingClientRect();
      const viewport = table.getBoundingClientRect();
      return { height: table.clientHeight, header: header.height, row: row.height, bottom: viewport.bottom, paginationBottom: document.querySelector('.pagination').getBoundingClientRect().bottom };
    });
    assert.equal(tableGeometry.row, 64);
    assert.ok(Math.abs((tableGeometry.height - tableGeometry.header) % tableGeometry.row) < 1, JSON.stringify(tableGeometry));
    assert.ok(tableGeometry.paginationBottom <= 900, JSON.stringify(tableGeometry));
    results.push('空态、20条分页与刷新可用；64px完整行结束、桌面分页可见');
    await page.locator('.table-scroll').evaluate(element => { element.scrollTop = element.scrollHeight; });
    const sticky = await page.locator('thead th').first().evaluate(element => getComputedStyle(element).backgroundColor);
    assert.equal(sticky, 'rgb(246, 248, 251)');
    await page.locator('.table-scroll').evaluate(element => { element.scrollTop = 0; });
    await page.screenshot({ path: path.join(__dirname, 'purchase-1440.png') });
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(__dirname, 'qa-results.json'), JSON.stringify({ passed: true, results, errors }, null, 2));
    console.log(JSON.stringify({ passed: true, results }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
