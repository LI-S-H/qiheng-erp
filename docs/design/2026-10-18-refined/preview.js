/* 独立外观预览只读取已导出的 Mock 数据，不访问业务 API 或执行单据写入。 */
const escapeText = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${window.YuncangIcons[name] || window.YuncangIcons.file}</svg>`;
const statusMap = {
  DRAFT: ['草稿', 'gray'], SUBMITTED: ['待审核', 'amber'],
  APPROVED: ['待入库', 'blue'], PARTIAL_INBOUND: ['部分入库', 'blue'],
  INBOUND_DONE: ['已入库', 'green'], CANCELLED: ['已取消', 'gray'],
};
const state = { route: 'purchase', page: 1, size: 10, filters: {}, data: null, busy: false };
let updateSequence = 0;
const main = document.querySelector('#main');
const modules = [
  ['工作台', 'home', []], ['系统权限', 'settings', ['用户管理', '角色管理', '部门管理', '权限码配置']],
  ['产品中心', 'boxes', ['产品分类', '产品档案']],
  ['仓储库存', 'boxes', ['仓库管理', '库存管理', '入库单', '出库单', '库存流水']],
  ['采购业务', 'shopping', ['供应商管理', '供货产品', '采购订单', '采购退回']],
  ['销售业务', 'file', ['客户管理', '销售订单', '销售退货']], ['智能助手', 'inbox', ['智能经营助手']],
];
const routeFor = title => title === '采购订单' ? 'purchase' : title === '库存管理' ? 'stock' : '';
function renderNavigation() {
  const navigation = document.querySelector('#navigation');
  if (navigation.dataset.route === state.route) return;
  navigation.dataset.route = state.route;
  document.querySelector('#navigation').innerHTML = modules.map(([title, name, children]) => children.length
    ? `<details class="nav-group" ${children.some(item => routeFor(item) === state.route) ? 'open' : ''}><summary>${icon(name)}${title}<svg class="icon nav-chevron" viewBox="0 0 24 24" aria-hidden="true">${window.YuncangIcons.right}</svg></summary><div class="nav-children">${children.map(item => `<button data-nav="${item}" ${routeFor(item) === state.route ? 'class="active" aria-current="page"' : ''}>${item}</button>`).join('')}</div></details>`
    : `<button data-nav="${title}">${icon(name)}${title}</button>`).join('');
}
const tag = (text, tone) => `<span class="tag ${tone}">${escapeText(text)}</span>`;
const options = (items, first) => `<option value="">${first}</option>${items.map(([value, label]) => `<option value="${escapeText(value)}">${escapeText(label)}</option>`).join('')}`;
const distinct = (items, key, label) => [...new Map(items.map(item => [item[key], [item[key], item[label]]])).values()];
const field = (label, name, body, extra = '') => `<label class="field ${extra}"><span>${label}</span>${body || `<input name="${name}" aria-label="${label}">`}</label>`;
const select = (label, name, items, first, extra) => field(label, name, `<span class="select-control"><select name="${name}" aria-label="${label}">${options(items, first)}</select>${icon('right')}</span>`, extra);
const purchaseNumber = number => /^PO\d+$/.test(number) ? `PO-${number.slice(2, -1)}-${number.slice(-1)}` : number;
const money = number => `￥${Number(number).toFixed(2)}`;
const matched = (value, query) => String(value).toLowerCase().includes(String(query || '').trim().toLowerCase());
function health(row) {
  if (row.stockQty === 0) return ['零库存', 'red', 'OUT_OF_STOCK'];
  if (row.availableQty === 0) return ['无可用库存', 'red', 'NO_AVAILABLE'];
  if (row.availableQty <= row.safetyStockQty) return ['低库存', 'amber', 'LOW_STOCK'];
  return ['库存正常', 'green', 'NORMAL'];
}
function reservation(row) {
  if (row.lockedQty === 0) return ['未锁定', 'gray', 'UNLOCKED'];
  if (row.lockedQty === row.stockQty) return ['全部锁定', 'blue', 'FULLY_LOCKED'];
  return ['部分锁定', 'blue', 'PARTIALLY_LOCKED'];
}
function filterRows() {
  const f = state.filters;
  if (state.route === 'purchase') return state.data.purchase.records.filter(row =>
    (matched(row.purchaseNo, f.purchaseNo) || matched(purchaseNumber(row.purchaseNo), f.purchaseNo))
    && (!f.supplierId || row.supplierId === f.supplierId)
    && (!f.warehouseId || row.warehouseId === f.warehouseId) && (!f.status || row.status === f.status));
  return state.data.stock.records.filter(row => (!f.warehouseId || row.warehouseId === f.warehouseId)
    && matched(row.productCode, f.productCode) && matched(row.productName, f.productName)
    && (!f.inventoryHealth || health(row)[2] === f.inventoryHealth)
    && (!f.reservationState || reservation(row)[2] === f.reservationState)
    && (!f.riskOnly || row.availableQty <= 0 || (row.safetyStockQty > 0 && row.availableQty <= row.safetyStockQty)));
}
function render() {
  const purchase = state.route === 'purchase';
  const title = purchase ? '采购订单' : '库存管理';
  document.querySelector('#module-title').textContent = purchase ? '采购业务' : '仓储库存';
  document.querySelector('#page-title').textContent = title;
  renderNavigation();
  const allRows = filterRows();
  state.page = Math.min(state.page, Math.max(1, Math.ceil(allRows.length / state.size)));
  const rows = allRows.slice((state.page - 1) * state.size, state.page * state.size);
  const summaries = purchase ? [
    ['本页草稿', rows.filter(row => row.status === 'DRAFT').length],
    ['本页待审核', rows.filter(row => row.status === 'SUBMITTED').length],
    ['本页待入库', rows.filter(row => row.status === 'APPROVED').length],
    ['本页入库未完成', rows.filter(row => ['APPROVED', 'PARTIAL_INBOUND'].includes(row.status)).length],
  ] : [
    ['本页仓库', new Set(rows.map(row => row.warehouseId)).size], ['本页产品', new Set(rows.map(row => row.productId)).size],
    ['本页低库存', rows.filter(row => row.availableQty > 0 && row.availableQty <= row.safetyStockQty).length],
    ['本页已锁定', rows.filter(row => row.lockedQty > 0).length],
  ];
  const filters = purchase ? [
    field('采购单号', 'purchaseNo', '<input name="purchaseNo" aria-label="采购单号" placeholder="如 PO202606001">'),
    select('供应商', 'supplierId', distinct(state.data.purchase.records, 'supplierId', 'supplierName'), '全部供应商', 'wide'),
    select('入库仓库', 'warehouseId', distinct(state.data.purchase.records, 'warehouseId', 'warehouseName'), '全部仓库'),
    select('订单状态', 'status', Object.entries(statusMap).map(([key, value]) => [key, value[0]]), '全部状态', 'compact'),
  ] : [
    select('仓库', 'warehouseId', distinct(state.data.stock.records, 'warehouseId', 'warehouseName'), '全部仓库'),
    field('产品编码', 'productCode', '<input name="productCode" aria-label="产品编码" placeholder="如 P000001">'),
    field('产品名称', 'productName', '<input name="productName" aria-label="产品名称" placeholder="请输入产品名称">'),
    select('库存健康', 'inventoryHealth', [['NORMAL', '正常库存'], ['LOW_STOCK', '低库存'], ['NO_AVAILABLE', '无可用库存'], ['OUT_OF_STOCK', '零库存']], '全部健康状态'),
    select('占用情况', 'reservationState', [['UNLOCKED', '未锁定'], ['PARTIALLY_LOCKED', '部分锁定'], ['FULLY_LOCKED', '全部锁定']], '全部占用情况'),
    `<div class="field risk-field"><span>风险预设</span><button class="button" type="button" data-risk aria-pressed="${Boolean(state.filters.riskOnly)}">查看风险库存</button></div>`,
  ];
  const headers = purchase ? ['采购单号', '供应商', '入库仓库', '状态', '订单金额', '预计到货', '创建人', '更新时间', '操作']
    : ['仓库', '产品', '单位', '当前库存', '锁定库存', '可用库存', '安全库存', '库存健康', '占用情况', '更新时间'];
  const widths = purchase ? [156, 160, 104, 96, 104, 104, 88, 128, 72] : [150, 170, 52, 82, 82, 88, 82, 100, 100, 136];
  const centeredColumns = purchase ? [3, 5, 6, 8] : [2, 7, 8];
  main.innerHTML = `<div class="page-heading"><div><h1>${title}</h1><p class="subtitle">${purchase ? '创建采购草稿、提交审核，并追踪采购入库进度；库存变化统一由仓库入库单确认' : '按仓库和产品查看当前库存、销售锁定库存与可用库存'}</p></div></div>
    <dl class="summary-line" aria-label="当前页汇总">${summaries.map(([label, value]) => `<div><dt>${label}</dt><dd>${value}</dd></div>`).join('')}</dl>
    <form class="filter-bar ${purchase ? '' : 'stock-filters'}" id="filters">${filters.join('')}<div class="filter-actions"><button class="button primary" type="submit" id="query-button">${icon('search')}查询</button><button class="button" type="button" data-reset>重置</button></div></form>
    <section class="table-panel" aria-label="${purchase ? '采购订单列表' : '库存余额'}"><div class="table-title"><div class="table-heading"><h2>${purchase ? '采购订单列表' : '库存余额'}</h2><p>${purchase ? '点击“处理”查看详情并完成后续操作；审核动作只生成待确认入库单，实际入库由仓库确认本次数量' : '库存变更请通过出入库或库存调整业务完成'}</p></div><div class="table-actions"><button class="button" data-refresh>刷新</button>${purchase ? '<button class="button primary" data-new>新增采购单</button>' : ''}</div></div>
    <div class="table-scroll" tabindex="0" aria-label="${title}表格，可横向滚动"><table class="data-table ${purchase ? '' : 'stock-table'}"><colgroup>${widths.map(width => `<col style="width:${width}px">`).join('')}</colgroup><thead><tr>${headers.map((text, index) => `<th scope="col" class="${purchase && index === 8 ? 'action-cell' : ''} ${centeredColumns.includes(index) ? 'centered' : ''} ${purchase && index === 4 || !purchase && index >= 3 && index <= 6 ? 'numeric' : ''}">${text}</th>`).join('')}</tr></thead><tbody>${rows.length ? rows.map(purchase ? purchaseRow : stockRow).join('') : `<tr><td colspan="${headers.length}" class="empty-row">${purchase ? '暂无采购订单' : '暂无库存数据'}</td></tr>`}</tbody></table><div class="table-loading" hidden><span class="loading-label" role="status">${icon('refresh')}正在更新列表</span></div></div>
    <div class="pagination"><div class="pagination-left"><span>${purchase ? `共 ${allRows.length} 条` : `本页 ${rows.length} 条`}</span><label>每页<select id="page-size" aria-label="每页条数">${[10, 20, 50].map(size => `<option ${size === state.size ? 'selected' : ''}>${size}</option>`).join('')}</select> 条</label></div><div class="pages"><button data-page="${state.page - 1}" ${state.page === 1 ? 'disabled' : ''} aria-label="上一页">${icon('left')}</button>${purchase ? Array.from({ length: Math.max(1, Math.ceil(allRows.length / state.size)) }, (_, i) => `<button data-page="${i + 1}" class="${state.page === i + 1 ? 'active' : ''}" ${state.page === i + 1 ? 'aria-current="page"' : ''}>${i + 1}</button>`).join('') : `<span>${state.page}</span>`}<button data-page="${state.page + 1}" ${state.page * state.size >= allRows.length ? 'disabled' : ''} aria-label="下一页">${icon('right')}</button></div><span>${purchase ? `共 ${Math.max(1, Math.ceil(allRows.length / state.size))} 页` : '不统计总数'}</span></div></section>
    `;
  for (const [name, value] of Object.entries(state.filters)) if (document.forms.filters.elements[name]) document.forms.filters.elements[name].value = value;
  document.querySelector('#filters').addEventListener('submit', event => {
    event.preventDefault();
    const filters = Object.fromEntries(new FormData(event.currentTarget));
    if (state.route === 'stock' && state.filters.riskOnly) filters.riskOnly = true;
    updatePreview(() => { state.filters = filters; state.page = 1; }, '#query-button');
  });
  document.querySelector('#page-size').addEventListener('change', event => {
    const size = Number(event.target.value);
    updatePreview(() => { state.size = size; state.page = 1; }, '#page-size');
  });
  fitTableViewport();
}
function fitTableViewport() {
  const scroll = document.querySelector('.table-scroll');
  if (!scroll) return;
  const headerHeight = document.querySelector('thead').getBoundingClientRect().height;
  const rowHeight = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--table-row-height'));
  const top = scroll.getBoundingClientRect().top + window.scrollY;
  const footer = document.querySelector('.pagination').getBoundingClientRect().height;
  const available = innerWidth <= 760 ? 430 : Math.min(650, innerHeight - top - footer - 24);
  // 可视区以完整行结束，避免在标签或副说明中间截断；较矮窗口允许页面继续滚动。
  const count = Math.max(3, Math.floor((available - headerHeight) / rowHeight));
  scroll.style.maxHeight = `${headerHeight + count * rowHeight}px`;
}
async function updatePreview(update, focusSelector) {
  if (state.busy) return;
  const sequence = ++updateSequence;
  state.busy = true;
  update();
  document.querySelector('.table-panel').setAttribute('aria-busy', 'true');
  document.querySelector('.table-loading').hidden = false;
  document.querySelector('.loading-label .icon').classList.add('spin');
  document.querySelector('#query-button').innerHTML = `${icon('refresh')}查询中`;
  document.querySelector('#query-button .icon').classList.add('spin');
  document.querySelectorAll('.filter-bar input, .filter-bar select, .filter-bar button, .table-panel button, .pagination select').forEach(element => { element.disabled = true; });
  // 保留旧数据并演示统一的忙碌反馈；路由切换后忽略旧轮次，避免覆盖新页面。
  await new Promise(resolve => setTimeout(resolve, 220));
  if (sequence !== updateSequence) return;
  state.busy = false;
  render();
  document.querySelector(focusSelector)?.focus({ preventScroll: true });
}
function purchaseRow(row) {
  const [status, tone] = statusMap[row.status];
  const action = ['DRAFT', 'SUBMITTED', 'APPROVED'].includes(row.status) ? '处理' : '查看';
  return `<tr><td class="identifier">${escapeText(purchaseNumber(row.purchaseNo))}</td><td title="${escapeText(row.supplierName)}"><span class="primary-text">${escapeText(row.supplierName)}</span><span class="secondary">${escapeText(row.supplierCode)}</span></td><td>${escapeText(row.warehouseName)}</td><td class="centered">${tag(status, tone)}</td><td class="numeric">${money(row.totalAmount)}</td><td class="centered timestamp">${escapeText(row.expectedArrivalDate || '未设置')}</td><td class="centered">${escapeText(row.createdByName)}</td><td class="timestamp"><span>${escapeText(row.updateTime.slice(0, 10))}</span><span class="secondary">${escapeText(row.updateTime.slice(11))}</span></td><td class="action-cell centered"><button class="table-link" data-order="${row.purchaseOrderId}">${action}</button></td></tr>`;
}
function stockRow(row) {
  const [healthName, tone] = health(row);
  const [reserved, reservedTone] = reservation(row);
  return `<tr><td><span>${escapeText(row.warehouseName)}</span><span class="secondary">${escapeText(row.warehouseCode)}</span></td><td title="${escapeText(row.productName)}"><span class="primary-text">${escapeText(row.productName)}</span><span class="secondary">${escapeText(row.productCode)}</span></td><td class="centered">${escapeText(row.unitName)}</td>${[row.stockQty, row.lockedQty, row.availableQty, row.safetyStockQty].map((value, index) => `<td class="numeric ${index === 2 && tone === 'red' ? 'critical-number' : index === 2 && tone === 'amber' ? 'risk-number' : ''}">${value}</td>`).join('')}<td class="centered">${tag(healthName, tone)}</td><td class="centered">${tag(reserved, reservedTone)}</td><td class="timestamp">${escapeText(row.updateTime.slice(0, 10))}<span class="secondary">${escapeText(row.updateTime.slice(11))}</span></td></tr>`;
}
function showInfo(title, body) {
  document.querySelector('#dialog-title').textContent = title;
  document.querySelector('#dialog-body').innerHTML = body;
  document.querySelector('#info-dialog').showModal();
}
function navigate() {
  updateSequence += 1;
  state.busy = false;
  state.route = location.hash === '#stock' ? 'stock' : 'purchase';
  state.filters = {}; state.page = 1; render();
}
document.addEventListener('click', event => {
  const button = event.target.closest('button'); if (!button) return;
  if (button.hasAttribute('data-close')) document.querySelector('#info-dialog').close();
  if (button.dataset.nav) {
    const route = routeFor(button.dataset.nav);
    if (route) { if (location.hash === '#' + route) navigate(); else location.hash = route; setSidebarOpen(false); }
    else showInfo(button.dataset.nav, '<p>此页面保留原有导航。本轮仅制作采购订单和库存管理的外观预览。</p>');
  }
  if (button.hasAttribute('data-reset')) updatePreview(() => { state.filters = {}; state.page = 1; document.forms.filters.reset(); }, '[data-reset]');
  if (button.hasAttribute('data-refresh')) updatePreview(() => {}, '[data-refresh]');
  if (button.hasAttribute('data-risk')) {
    const filters = { ...Object.fromEntries(new FormData(document.forms.filters)), riskOnly: true };
    updatePreview(() => { state.filters = filters; state.page = 1; }, '[data-risk]');
  }
  if (button.dataset.page) updatePreview(() => { state.page = Number(button.dataset.page); }, `[data-page="${button.dataset.page}"]`);
  if (button.hasAttribute('data-new')) showInfo('新增采购单', '<p>此入口仅展示按钮外观，未连接业务写入。实际采购草稿、明细和提交校验保持现有系统逻辑。</p>');
  if (button.dataset.order) {
    const row = state.data.purchase.records.find(item => item.purchaseOrderId === button.dataset.order);
    const labels = ['采购单号', '供应商', '入库仓库', '状态', '订单金额', '预计到货', '创建人', '更新时间'];
    const values = [purchaseNumber(row.purchaseNo), `${row.supplierCode} ${row.supplierName}`, row.warehouseName, statusMap[row.status][0], money(row.totalAmount), row.expectedArrivalDate, row.createdByName, row.updateTime];
    showInfo('采购订单', `<dl>${labels.map((label, index) => `<dt>${label}</dt><dd>${escapeText(values[index])}</dd>`).join('')}</dl><p>只读外观预览，未执行审核、入库或其他单据变更。</p>`);
  }
});
document.querySelector('#mobile-menu').innerHTML = icon('menu');
const mobileViewport = matchMedia('(max-width:760px)');
function setSidebarOpen(open, restoreFocus = false) {
  const sidebar = document.querySelector('#sidebar');
  const wasOpen = sidebar.classList.contains('open');
  sidebar.classList.toggle('open', open && mobileViewport.matches);
  sidebar.inert = mobileViewport.matches && !open;
  document.querySelector('.workspace').inert = mobileViewport.matches && open;
  document.querySelector('#mobile-menu').setAttribute('aria-expanded', String(open && mobileViewport.matches));
  if (open && mobileViewport.matches) sidebar.querySelector('a, button')?.focus();
  else if (wasOpen && restoreFocus && mobileViewport.matches) document.querySelector('#mobile-menu').focus();
}
document.querySelector('#mobile-menu').addEventListener('click', () => setSidebarOpen(!document.querySelector('#sidebar').classList.contains('open')));
document.querySelector('#sidebar-shade').addEventListener('click', () => setSidebarOpen(false, true));
mobileViewport.addEventListener('change', () => setSidebarOpen(false));
setSidebarOpen(false);
document.querySelector('#notification').insertAdjacentHTML('afterbegin', icon('bell'));
document.querySelector('#notification').addEventListener('click', () => showInfo('通知', '<p>通知数量沿用原页面，此预览不模拟通知处理。</p>'));
document.querySelector('[data-close].icon-button').innerHTML = icon('close');
document.addEventListener('keydown', event => {
  const sidebar = document.querySelector('#sidebar');
  if (event.key === 'Escape') setSidebarOpen(false, true);
  if (event.key === 'Tab' && sidebar.classList.contains('open') && !document.querySelector('dialog[open]')) {
    const elements = [...sidebar.querySelectorAll('a, button, summary')].filter(element => element.getClientRects().length);
    const first = elements[0], last = elements.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
});
window.addEventListener('hashchange', navigate);
let resizeFrame;
window.addEventListener('resize', () => {
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(fitTableViewport);
});
fetch('data.json').then(response => { if (!response.ok) throw new Error('无法读取预览数据'); return response.json(); }).then(data => { state.data = data; navigate(); }).catch(error => { main.textContent = error.message; });
