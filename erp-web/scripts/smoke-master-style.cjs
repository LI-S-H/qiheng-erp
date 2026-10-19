const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.SMOKE_BASE_URL || 'http://127.0.0.1:5192';
const name = process.argv[2] || 'categories';
const routes = { categories: '/product/categories', products: '/product/products', users: '/system/users', roles: '/system/roles', depts: '/system/depts', permissions: '/system/permissions' };
const dir = path.resolve(`../docs/qa-screenshots/2026-10-19-master-style/${name}`);
fs.mkdirSync(dir, { recursive: true });
let page; const results = [];
const tree = ['categories', 'depts'].includes(name);
const rows = () => page.locator('.business-data-table > tbody > tr');
const viewport = () => page.locator('.purchase-list-body > [data-slot="table-container"]');
const check = title => { results.push({ title, passed: true }); console.log('PASS', title); };
async function settle() { await page.waitForFunction(() => !document.querySelector('[data-list-loading],[data-page-loading]')); await page.waitForTimeout(300); }
async function shot(label) { await page.waitForTimeout(200); await page.screenshot({ path: path.join(dir, `${label}.png`) }); }
async function close(dialog) { await dialog.locator('[data-slot="dialog-close"]').click(); await dialog.waitFor({ state: 'hidden' }); }
async function query(label) {
  await page.getByRole('button', { name: label, exact: true }).click();
  await page.locator('[data-list-loading]').waitFor();
  const state = await page.locator('[data-list-loading]').evaluate(el => ({ busy: el.parentElement.querySelector('[data-list-filter-actions]')?.getAttribute('aria-busy'), inert: el.parentElement.querySelector('[data-slot="table-container"]').inert, leaving: el.classList.contains('page-loading-leave-active') }));
  assert(state.inert || state.leaving); await settle();
}
async function size(n) { await page.locator('[data-table-pagination] [role="combobox"]').click(); await page.getByRole('option', { name: `${n} 条`, exact: true }).click(); await page.locator('[data-list-loading]').waitFor(); await settle(); }
async function action(row, label) { await row.locator('[data-row-actions-trigger]').click(); await page.getByRole('menuitem', { name: label, exact: true }).click(); }
async function layout(count) {
  for (const [width, height] of [[1440, 900], [1115, 838], [390, 844], [1920, 1080]]) {
    await page.setViewportSize({ width, height }); await settle(); await page.locator('main').evaluate(el => { el.scrollTop = 0; }); await viewport().evaluate(el => { el.scrollLeft = 0; el.scrollTop = 0; });
    const geometry = await viewport().evaluate(el => {
      const table = el.querySelector('table'), row = table.querySelector('tbody tr'), root = document.querySelector('.catalog-pilot-page');
      return { count: table.querySelectorAll('col').length, page: root.getBoundingClientRect().width, main: root.parentElement.clientWidth, overflow: document.documentElement.scrollWidth - innerWidth, vertical: el.scrollHeight - el.clientHeight, row: row.getBoundingClientRect().height,
        cells: [...table.querySelectorAll('thead th'), ...row.children].map(cell => ({ font: getComputedStyle(cell).fontSize, align: getComputedStyle(cell).textAlign })),
        fields: [...root.querySelectorAll('[data-filter-size]')].map(field => field.getBoundingClientRect().width) };
    });
    assert.equal(geometry.count, count); assert(Math.abs(geometry.page - geometry.main) <= 1 && geometry.overflow <= 1); assert(geometry.vertical <= 1 && Math.abs(geometry.row - 64) <= 1, JSON.stringify(geometry));
    assert(geometry.cells.every(c => c.font === '14px' && c.align === 'center'), JSON.stringify(geometry.cells)); assert(geometry.fields.every(w => w <= 241));
    if (name === 'products') assert(await page.locator('[data-list-filter-panel]').evaluate(el => Math.abs(el.getBoundingClientRect().right - el.querySelector('[data-list-filter-actions]').getBoundingClientRect().right) <= 1), '产品档案查询和重置必须靠筛选区右边对齐');
    if (name === 'users') {
      const accounts = await rows().evaluateAll(list => list.map(row => { const cell = row.children[1], group = cell.querySelector('.mx-auto'), avatar = group.querySelector('[data-slot="avatar"]'), label = group.querySelector('.min-w-0'); const r = group.getBoundingClientRect(), c = cell.getBoundingClientRect(); return { avatar: avatar.getBoundingClientRect().left, label: label.getBoundingClientRect().left, align: getComputedStyle(group).textAlign, center: Math.abs(r.left + r.width / 2 - c.left - c.width / 2) }; }));
      assert(accounts.every(a => Math.abs(a.avatar - accounts[0].avatar) <= 1 && Math.abs(a.label - accounts[0].label) <= 1 && a.align === 'left' && a.center <= 1), JSON.stringify(accounts));
    }
    const fixed = await viewport().evaluate(async el => { const pins = [...el.querySelector('tbody tr').querySelectorAll('[data-table-sticky-edge]')]; const before = pins.map(c => c.getBoundingClientRect().x); el.scrollLeft = el.scrollWidth; await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); return pins.map((c, i) => Math.abs(c.getBoundingClientRect().x - before[i])); });
    assert(fixed.length && fixed.every(delta => delta <= 1)); await viewport().evaluate(el => { el.scrollLeft = 0; }); await shot(`list-${width}x${height}`); check(`${width}满宽、14px居中、64px行、筛选宽度与固定列`);
  }
  await page.setViewportSize({ width: 1440, height: 1600 }); await shot('overview'); await page.setViewportSize({ width: 1440, height: 900 });
  if (!tree) {
    await size(20); await size(50); await viewport().evaluate(el => { el.scrollTop = el.scrollHeight; }); await size(10);
    assert(await viewport().evaluate(el => el.scrollHeight - el.clientHeight <= 1 && el.scrollTop === 0)); check('10条完整展开、20/50条滚动和恢复');
  }
}
async function formCheck(dialog) {
  const fonts = await dialog.evaluate(el => ({ title: getComputedStyle(el.querySelector('[data-slot="dialog-title"]')).fontSize, controls: [...el.querySelectorAll('input[data-slot="input"], textarea, [role="combobox"]')].map(c => ({ font: getComputedStyle(c).fontSize, height: c.getBoundingClientRect().height, tag: c.tagName, multi: c.hasAttribute('data-multi-select-trigger') })), labels: [...el.querySelectorAll('[data-slot="label"]')].map(c => getComputedStyle(c).fontSize) }));
  assert.equal(fonts.title, '16px'); assert(fonts.labels.every(v => v === '13px')); assert(fonts.controls.every(c => c.font === '13px' && (c.tag === 'TEXTAREA' || (c.multi ? c.height >= 36 : c.height === 36))), JSON.stringify(fonts));
  for (const [width, height] of [[1115, 640], [390, 844]]) { await page.setViewportSize({ width, height }); await settle(); assert(await dialog.evaluate(el => { const r = el.getBoundingClientRect(), footer = el.querySelector('[data-slot="dialog-footer"]').getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth && footer.bottom <= innerHeight && el.querySelector('[data-slot="scroll-area-viewport"]').clientHeight > 0; })); await shot(`dialog-${width}x${height}`); }
  await page.setViewportSize({ width: 1440, height: 900 });
}
async function permissions() {
  await layout(10); const filter = page.locator('[data-list-filter-panel]');
  await filter.getByPlaceholder('如 product:query').fill('product:query'); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('产品查询')); await query('重置');
  await filter.getByPlaceholder('请输入权限名称').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  await filter.locator('[data-anchored-select-trigger]').first().click(); await page.getByRole('option', { name: '产品中心', exact: true }).click(); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('产品中心'))); await query('重置'); await query('刷新'); check('原十列、独立权限码名称及模块筛选、引用数和空态');
  await page.getByRole('button', { name: '新增权限码', exact: true }).click(); const form = page.locator('[data-master-form-dialog]'); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.locator('[aria-invalid="true"]').first().waitFor();
  await form.getByPlaceholder('例如 system:user:query').fill('style:visual:query'); await form.getByPlaceholder('请输入权限名称').fill('外观验证权限'); await form.getByPlaceholder('说明该权限允许执行的业务操作').fill('原权限说明字段保留');
  await form.getByRole('combobox').first().click(); await page.getByRole('option', { name: '产品中心', exact: true }).click();
  await form.getByRole('combobox').nth(1).click(); await page.getByRole('option', { name: '查询', exact: true }).click(); await formCheck(form); await shot('create');
  assert(await form.locator('input[type="number"]').evaluate(el => getComputedStyle(el).appearance === 'textfield')); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filter.getByPlaceholder('如 product:query').fill('style:visual:query'); await query('查询'); const created = rows().first(); await created.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert(await form.getByPlaceholder('例如 system:user:query').isDisabled()); assert.equal(await form.locator('textarea').inputValue(), '原权限说明字段保留'); await close(form); check('新增校验、模块与操作下拉、13px表单、移动滚动、无数字箭头、保存和编码只读');
  await query('重置'); await filter.getByPlaceholder('如 product:query').fill('product:query'); await query('查询'); await rows().first().getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); await form.getByRole('radio', { name: '停用', exact: true }).click(); await form.getByRole('button', { name: '保存', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); assert(await form.evaluate(el => el.inert)); await shot('referenced-confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await close(form); check('已引用权限码停用二次确认与底层inert');
}
async function roles() {
  await layout(8); const filter = page.locator('[data-list-filter-panel]');
  await filter.getByPlaceholder('如 SUPER_ADMIN').fill('PURCHASE'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('PURCHASE'))); await query('重置');
  await filter.getByPlaceholder('如 超级管理员').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置'); await query('刷新'); check('八列及编码名称筛选、权限计数、空态与刷新');
  await page.getByRole('button', { name: '新增角色', exact: true }).click(); const form = page.locator('[data-role-form-dialog]'); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.getByText('请选择权限码', { exact: true }).waitFor();
  await form.locator('input').nth(0).fill('STYLE_QA'); await form.locator('input').nth(1).fill('外观验证角色'); await form.getByPlaceholder('请输入备注').fill('角色原备注保留');
  await form.locator('#role-perm-all').click(); assert.equal(await form.locator('#role-perm-all').getAttribute('data-state'), 'checked');
  await form.locator('[id="role-perm-product:query"]').click(); assert.equal(await form.locator('#role-perm-all').getAttribute('data-state'), 'unchecked');
  await formCheck(form); await shot('create'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filter.getByPlaceholder('如 SUPER_ADMIN').fill('STYLE_QA'); await query('查询'); const created = rows().first(); assert((await created.innerText()).includes('1 项权限码')); await created.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert(await form.locator('input').first().isDisabled()); assert.equal(await form.getByPlaceholder('请输入备注').inputValue(), '角色原备注保留'); await close(form); check('新增校验、通配符与单项互斥语义、13px表单、移动权限网格、Mock保存与编辑回显');
  await action(created, '查看权限'); const preview = page.locator('[data-role-preview-dialog]'); await preview.waitFor(); await preview.locator('[data-permission-preview-content]').getByText('product:query', { exact: true }).waitFor();
  await formCheck(preview); await page.setViewportSize({ width: 390, height: 844 }); await settle(); const bounds = await preview.evaluate(el => { const sidebar = el.querySelector('[data-permission-preview-sidebar]').getBoundingClientRect(), content = el.querySelector('[data-permission-preview-content]').getBoundingClientRect(); return { below: content.top >= sidebar.bottom, width: content.width }; }); assert(bounds.below && bounds.width >= 280, JSON.stringify(bounds)); await shot('permissions-preview-mobile'); await close(preview); await page.setViewportSize({ width: 1440, height: 900 });
  await action(created, '权限配置'); const config = page.locator('[data-role-permissions-dialog]'); await config.waitFor(); await config.locator('[id="perm-form-warehouse:query"]').click(); await formCheck(config); await shot('permissions-config'); await config.getByRole('button', { name: '保存', exact: true }).click(); await config.waitFor({ state: 'hidden' }); await settle(); assert((await rows().first().innerText()).includes('2 项权限码'));
  await rows().first().getByRole('checkbox').click(); await page.getByRole('button', { name: '批量停用', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); await shot('confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); check('分模块权限预览、手机顶栏、授权保存回显及批量确认取消');
  await query('重置'); await action(rows().filter({ hasText: 'SUPER_ADMIN' }), '查看权限'); await preview.waitFor(); assert(await preview.locator('[data-permission-preview-sidebar] button').count() > 3);
  await page.setViewportSize({ width: 390, height: 844 }); await settle(); assert(await preview.locator('[data-permission-preview-sidebar] > div').evaluate(el => el.scrollWidth > el.clientWidth)); await preview.locator('[data-permission-preview-sidebar] button').last().click(); assert((await preview.locator('[data-permission-preview-content]').innerText()).includes('ai:assistant:use')); await shot('all-permissions-mobile'); await close(preview); check('超级管理员通配权限、多分组手机横滚及末项内容可访问');
}
async function depts() {
  await layout(7); const input = page.getByPlaceholder('如 采购部');
  await input.fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  await input.fill('采购部'); await query('查询'); assert((await rows().allTextContents()).every(text => !text.includes('销售部'))); await query('重置'); await query('刷新');
  const { assertTreeCollapseStability } = require('./smoke-helpers.cjs'); await assertTreeCollapseStability(page, { parentText: '采购部', childText: '采购跟单组', followingText: '销售部', collapseButtonName: '收起当前部门', expandButtonName: '展开当前部门', screenshotPath: path.join(dir, 'tree-collapse.png') }); check('原七列全量部门树、独立筛选、快速反向展开与减弱动效');
  await page.getByRole('button', { name: '新增部门', exact: true }).click(); const form = page.locator('[data-master-form-dialog]'); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.getByText('请输入部门名称', { exact: true }).waitFor();
  await form.getByPlaceholder('请输入部门名称').fill('外观验证部门'); await form.getByRole('combobox').click(); await page.locator('[data-tree-select-content]').getByText('无上级部门', { exact: true }).click(); await formCheck(form); await shot('create'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  const created = rows().filter({ hasText: '外观验证部门' }); await created.waitFor(); await created.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert.equal(await form.getByPlaceholder('请输入部门名称').inputValue(), '外观验证部门'); await close(form); check('部门树选择、必填校验、13px表单、移动滚动、新增和编辑回显');
  const parent = rows().filter({ has: page.getByRole('button', { name: /展开当前部门|收起当前部门/ }) }).filter({ hasText: '采购部' }).first(); await parent.getByRole('button', { name: '编辑', exact: true }).click(); await form.getByRole('radio', { name: '停用', exact: true }).click(); await form.getByRole('button', { name: '保存', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); assert(await form.evaluate(el => el.inert)); assert((await confirm.innerText()).includes('下级')); await shot('confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await close(form); check('级联停用预览、用户归属说明和底层inert保留');
}
async function users() {
  await layout(8); const filter = page.locator('[data-list-filter-panel]');
  await filter.getByPlaceholder('请输入登录账号').fill('purchase'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('purchase'))); assert(await page.locator('[data-active-user-filters]').isVisible());
  await filter.getByPlaceholder('请输入用户姓名').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置'); await query('刷新'); check('原八列、独立账号姓名AND筛选、已生效条件、空态与加载');
  await page.getByRole('button', { name: '新增用户', exact: true }).click(); const form = page.locator('[data-user-form-dialog]'); await form.waitFor(); await form.getByRole('button', { name: '保存', exact: true }).click(); assert((await form.innerText()).includes('请输入'));
  await form.locator('input').nth(0).fill('style_user'); await form.locator('input').nth(1).fill('外观验证用户'); await form.locator('input[type="password"]').fill('style123456');
  await form.getByRole('combobox').first().click(); const dept = page.locator('[data-tree-select-content]'); await dept.getByText('采购部', { exact: true }).click();
  await form.locator('[data-multi-select-trigger]').click(); const multi = page.locator('[data-multi-select-content]'); await multi.locator('[data-select-option]').filter({ hasText: '采购员' }).click(); await shot('role-multi-select'); await page.keyboard.press('Escape'); await multi.waitFor({ state: 'hidden' });
  await formCheck(form); await shot('create'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filter.getByPlaceholder('请输入登录账号').fill('style_user'); await query('查询'); const created = rows().first(); assert((await created.innerText()).includes('外观验证用户'));
  await created.getByRole('button', { name: '编辑', exact: true }).click(); await form.waitFor(); assert(await form.locator('input').nth(0).isDisabled()); assert.equal(await form.locator('input[type="password"]').inputValue(), ''); await close(form); check('用户新增、部门与角色选择、13px表单、移动滚动、Mock保存、账号只读及密码留空');
  await action(created, '绑定角色'); const binding = page.locator('[data-user-roles-dialog]'); await binding.waitFor(); await binding.locator('[data-multi-select-trigger]').click();
  for (const option of await multi.locator('[data-select-option]').all()) if (!(await option.locator('[role="checkbox"]').getAttribute('data-state') === 'checked')) await option.click();
  await page.keyboard.press('Escape'); await multi.waitFor({ state: 'hidden' }); await formCheck(binding); assert(await binding.locator('[data-multi-select-trigger]').evaluate(el => el.scrollHeight <= el.clientHeight + 1)); await shot('binding');
  await close(binding);
  await action(created, '重置密码'); const prompt = page.locator('[data-prompt-dialog]'); await prompt.waitFor(); await prompt.locator('input').fill('1'); await prompt.getByRole('button', { name: '确认重置', exact: true }).click(); await prompt.getByText('密码长度为 6-32 个字符且不能全为空格', { exact: true }).waitFor(); await shot('password'); await prompt.getByRole('button', { name: '取消', exact: true }).click();
  await created.getByRole('checkbox').click(); await page.getByRole('button', { name: '重置密码', exact: true }).click(); await prompt.waitFor(); assert((await prompt.innerText()).includes('1 个账号')); await prompt.getByRole('button', { name: '取消', exact: true }).click();
  await action(created, '停用账号'); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); await shot('confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); check('多角色自动增高、绑定、单批密码校验与取消、停用确认');
}
async function products() {
  await layout(10); const filter = page.locator('[data-list-filter-panel]');
  await filter.getByPlaceholder('如 P000001').fill('P000001'); await query('查询'); assert.equal(await rows().count(), 1); assert((await rows().innerText()).includes('经典原味苏打水')); await query('重置');
  await filter.getByPlaceholder('请输入产品名称').fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  await filter.getByPlaceholder('请输入品牌名称').fill('晨岛'); await query('查询'); assert((await rows().allTextContents()).every(t => t.includes('晨岛'))); await query('重置'); await query('刷新'); check('原十列、编码名称品牌独立筛选、空态与加载');
  const form = page.getByRole('dialog', { name: '新增产品', exact: true }); await page.getByRole('button', { name: '新增产品', exact: true }).click(); await form.waitFor();
  assert(await form.locator('[data-product-code-display]').evaluate(el => el.readOnly)); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.getByText('请输入产品名称', { exact: true }).waitFor();
  await form.getByPlaceholder('请输入产品名称').fill('外观验证产品'); await form.getByPlaceholder('补充产品采购、销售或仓储注意事项').fill('原备注字段保留');
  for (let index = 0; index < await form.locator('[role="combobox"]').count(); index++) { const trigger = form.locator('[role="combobox"]').nth(index); await trigger.click(); await page.locator('[data-anchored-select-content][data-state="open"]').waitFor(); await page.keyboard.press('Escape'); await page.locator('[data-anchored-select-content]').waitFor({ state: 'hidden' }); }
  await formCheck(form); await shot('create'); await form.getByRole('button', { name: '保存', exact: true }).click(); await form.waitFor({ state: 'hidden' }); await settle();
  await filter.getByPlaceholder('请输入产品名称').fill('外观验证产品'); await query('查询'); const created = rows().first(); assert((await created.innerText()).includes('未分类')); await created.getByRole('button', { name: '编辑', exact: true }).click(); const edit = page.getByRole('dialog', { name: '编辑产品', exact: true }); await edit.waitFor(); assert(await edit.locator('[data-product-code-display]').evaluate(el => el.readOnly && el.value.startsWith('P'))); assert.equal(await edit.locator('textarea').inputValue(), '原备注字段保留'); await shot('edit'); await close(edit); await query('重置'); check('系统生成编码、全部表单字段、下拉互斥、13px表单、移动滚动、新增保存与编辑回显');
  const existing = rows().filter({ hasText: 'P000001' }); await action(existing, '调整参考采购价'); const price = page.getByRole('dialog', { name: '调整参考采购价', exact: true }); await price.waitFor(); await price.locator('input[type="number"]').fill('-1'); await price.getByRole('button', { name: '确认调整', exact: true }).click(); await price.locator('[aria-invalid="true"]').waitFor();
  await price.locator('input[type="number"]').fill('2.50'); await formCheck(price); await shot('reference-price'); const currency = await price.locator('.relative:has(> input[type="number"])').evaluate(el => ({ gap: el.querySelector('input').getBoundingClientRect().left - el.querySelector('span').getBoundingClientRect().right, appearance: getComputedStyle(el.querySelector('input')).appearance })); assert(Math.abs(currency.gap - 8) <= 1 && currency.appearance === 'textfield');
  await price.getByRole('button', { name: '确认调整', exact: true }).click(); await price.waitFor({ state: 'hidden' }); await settle(); await action(existing, '删除产品'); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); assert((await confirm.innerText()).includes('引用')); await confirm.getByRole('button', { name: '取消', exact: true }).click(); check('参考采购价独立入口、校验、框外人民币、保存与业务引用保护');
}
async function categories() {
  await layout(7);
  const input = page.getByPlaceholder('如 食品饮料');
  await input.fill('不存在'); await query('查询'); assert((await rows().innerText()).includes('暂无')); await query('重置');
  await input.fill('办公用品'); await query('查询'); assert((await rows().allTextContents()).every(t => !t.includes('食品饮料'))); await query('重置'); await query('刷新'); check('筛选、空态、重置、刷新与柔和加载');
  const { assertTreeCollapseStability } = require('./smoke-helpers.cjs');
  await assertTreeCollapseStability(page, { parentText: '食品饮料', childText: '饮料冲调', followingText: '办公用品', collapseButtonName: '收起当前分类', expandButtonName: '展开当前分类', screenshotPath: path.join(dir, 'tree-collapse.png') }); check('原全量树、缩进与整行展开收起动画');
  const dialog = page.locator('[data-master-form-dialog]'); await page.getByRole('button', { name: '新增分类', exact: true }).click(); await dialog.waitFor(); await dialog.getByRole('button', { name: '保存', exact: true }).click(); await dialog.getByText('请输入分类名称', { exact: true }).waitFor();
  await dialog.getByPlaceholder('请输入分类名称').fill('外观验证分类'); await dialog.getByRole('combobox').click(); const options = page.locator('[data-tree-select-content]'); await options.waitFor(); await shot('tree-select'); await options.getByText('无上级分类', { exact: true }).click(); await shot('create'); await formCheck(dialog); await dialog.getByRole('button', { name: '保存', exact: true }).click(); await dialog.waitFor({ state: 'hidden' }); await settle();
  const created = rows().filter({ hasText: '外观验证分类' }); await created.waitFor(); await created.getByRole('button', { name: '编辑', exact: true }).click(); assert.equal(await dialog.getByPlaceholder('请输入分类名称').inputValue(), '外观验证分类'); await close(dialog); check('完整新增、树选择、13px表单、移动滚动、Mock保存和编辑回显');
  const parent = rows().filter({ hasText: '食品饮料' }).first(); await parent.getByRole('button', { name: '编辑', exact: true }).click(); await dialog.getByRole('radio', { name: '停用', exact: true }).click(); await dialog.getByRole('button', { name: '保存', exact: true }).click(); const confirm = page.locator('[data-confirm-dialog]'); await confirm.waitFor(); assert(await dialog.evaluate(el => el.inert)); await shot('confirm'); await confirm.getByRole('button', { name: '取消', exact: true }).click(); await close(dialog); check('级联停用二次确认与底层inert保留');
}
(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true });
  try {
    page = await browser.newPage({ viewport: { width: 1440, height: 900 } }); const errors = []; page.on('pageerror', e => errors.push(e.message)); await page.goto(`${base}${routes[name]}`);
    if (page.url().includes('/login')) { await page.locator('#username').fill('admin'); await page.locator('#password').fill('123456'); await page.getByRole('button', { name: '登录', exact: true }).click(); await page.waitForURL(u => !u.pathname.includes('/login')); await page.goto(`${base}${routes[name]}`); }
    await page.locator('table tbody tr').first().waitFor(); await settle(); assert.equal(await page.evaluate(() => localStorage.getItem('erp_auth_token')), 'mock-token-admin');
    if (process.argv.includes('--baseline')) { for (const [key, route] of Object.entries(routes)) { await page.goto(`${base}${route}`); await page.locator('table tbody tr').first().waitFor(); await settle(); await page.screenshot({ path: path.resolve(dir, `../${key}-before.png`) }); } return; }
    if (name === 'categories') await categories(); else if (name === 'products') await products(); else if (name === 'users') await users(); else if (name === 'roles') await roles(); else if (name === 'depts') await depts(); else if (name === 'permissions') await permissions(); else throw new Error(`未接入：${name}`);
    assert.equal(errors.length, 0, errors.join('\n')); check('无页面脚本错误'); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(results, null, 2)); console.log(`MASTER_STYLE_OK ${name}: ${results.length}组`);
  } catch (error) { await shot('failed').catch(() => {}); fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify({ completed: results, error: String(error) }, null, 2)); throw error; } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
