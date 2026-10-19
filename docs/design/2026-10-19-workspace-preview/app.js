/* 独立设计画布：所有状态仅在当前浏览器演示，不调用或替换业务接口。 */
const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = (name, cls = '') => `<svg class="icon ${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><use href="icons.svg#${name}"/></svg>`;
const money = value => `￥${Number(value).toLocaleString('zh-CN', { maximumFractionDigits:0 })}`;
const compact = value => `${(value / 10000).toFixed(1)}万`;
const state = { view: location.hash.includes('assistant') ? 'assistant' : 'dashboard', ai: location.hash.replace('#assistant-', '') || 'plan', days:7, table:false, warehouse:'全部仓库', plan:true, collapsed:false, drawer:false, conversation:'库存与补货分析', groupClosed:new Set(), draft:'', chartIndex:null, toastTimer:null, refresh:false, modalTodo:0, conversations:['库存与补货分析','近30日销售趋势分析','供应商采购对比','华东仓库存梳理'] };
if (!['welcome','clarify','plan','running','error'].includes(state.ai)) state.ai = 'plan';
state.plan = ['plan','running'].includes(state.ai);
const products = [
  ['P000001','经典原味苏打水',126800,360,122],['P000026','A4复印纸',98400,220,24],['P000002','速溶黑咖啡',87600,194,7],['P000043','USB-C扩展坞',75800,72,0],['P000007','每日坚果混合装',69400,128,31],['P000008','海盐苏打饼干',53600,104,39],['P000021','中性签字笔',43800,410,30]
];
const suppliers = [
  ['S001','华东饮品供应链',98.1,99.1,1.2],['S005','森纸纸业集团',96.2,97.4,1.8],['S003','谷仓食品批发',92.4,95.2,3.2],['S002','晨岛咖啡贸易',89.7,93.4,3.8],['S004','文仪办公渠道',85.4,90.5,4.6],['S006','拓联数码配件',78.2,82.4,6.1]
];
const risks = [
  ['P000043','USB-C扩展坞','南京备货仓',0,4,'无可用库存','warn'],['P000027','热敏标签纸','华南中心仓',0,40,'零库存','danger'],['P000002','速溶黑咖啡','华东中心仓',7,8,'低库存','warn']
];
const todos = [
  {title:'系统异常待排查',description:'2 条系统异常记录需要排查',count:2,business:'系统',priority:'HIGH',kind:'system',hint:'查看异常原因、影响范围和建议处置方式；此类记录通过系统追踪，不提供手动完成入口。',items:[['EX202610190001','后台服务','错误','处理失败，等待排查'],['EX202610190002','评分更新','警告','任务执行超时，待复核']]},
  {title:'采购退货待审核',description:'1 张采购退货单等待审核',count:1,business:'采购',priority:'HIGH',kind:'order',path:'/purchase/returns?status=SUBMITTED&from=dashboard',hint:'审核通过后将生成对应出库工作单，待办按原业务状态自动更新。',items:[['PR202610190001','谷仓食品批发','￥1,280.00','等待 2 小时']]},
  {title:'采购单待审核',description:'2 张采购单等待审核',count:2,business:'采购',priority:'HIGH',kind:'order',path:'/purchase/orders?status=SUBMITTED&from=dashboard',hint:'前往采购订单完成审核，审核通过或驳回后待办自动更新。',items:[['PO202610190001','华东饮品供应链','￥844.80','等待 1 小时'],['PO202610190002','森纸纸业集团','￥1,566.00','等待 40 分钟']]},
  {title:'销售退货待审核',description:'1 张销售退货单等待审核',count:1,business:'销售',priority:'HIGH',kind:'order',path:'/sales/returns?status=SUBMITTED&from=dashboard',hint:'审核通过后将生成对应入库工作单。',items:[['SR202610190001','上海星河便利店','￥560.00','等待 35 分钟']]},
  {title:'销售单待审核',description:'2 张销售单等待审核',count:2,business:'销售',priority:'HIGH',kind:'order',path:'/sales/orders?status=SUBMITTED&from=dashboard',hint:'审核通过后进入库存锁定和发货准备。',items:[['SO202610190001','上海星河便利店','￥2,680.00','等待 30 分钟'],['SO202610190002','广州天河门店','￥1,440.00','等待 25 分钟']]},
  {title:'库存异常待复核',description:'2 个 SKU 低于安全库存线',count:2,business:'库存',priority:'HIGH',kind:'stock',path:'/warehouse/stocks?riskOnly=true&from=dashboard',hint:'前往库存余额查看风险明细；库存恢复后，按原规则更新待办。',items:[]},
  {title:'待确认入库',description:'2 张入库单等待仓库确认',count:2,business:'仓储',priority:'MEDIUM',kind:'order',path:'/warehouse/inbound-bills?status=PENDING_CONFIRM&from=dashboard',hint:'仓库确认本次入库数量后才更新实际库存。',items:[['IB202610190001','华东中心仓','待确认','等待 20 分钟'],['IB202610190002','华南中心仓','待确认','等待 15 分钟']]},
  {title:'待确认出库',description:'1 张出库单等待发货确认',count:1,business:'仓储',priority:'MEDIUM',kind:'order',path:'/warehouse/outbound-bills?status=PENDING_CONFIRM&from=dashboard',hint:'前往出库单完成发货确认。',items:[['OB202610190001','华东中心仓','待确认','等待 10 分钟']]}
];
const chartColors = ['#3975df','#d79a35','#229d89'];
const series = {
  sales:[218000,244000,231800,268600,353400,277000,286430],
  purchase:[132000,146500,125400,159200,141300,168400,379000],
  profit:[68200,75600,72400,86400,80700,86700,-92500]
};
const planSteps = [
  ['Database','数据查询','查询指定仓库的当前库存、锁定库存、可用库存与安全库存，识别低库存和零可用产品。'],
  ['Database','数据查询','查询近30日产品销量及已审核的采购在途数量，补齐补货判断所需的数据范围。'],
  ['Code2','Python 分析','结合销量、安全库存与在途数量，整理未来两周的补货建议清单和建议数量。']
];
const queryText = '帮我看看华东中心仓未来两周有哪些产品需要补货，结合最近30天的销量来分析。';
function chartDate(index, count = state.days) { const date = new Date(Date.UTC(2026,9,19)); date.setUTCDate(date.getUTCDate()-count+1+index); return `${String(date.getUTCMonth()+1).padStart(2,'0')}-${String(date.getUTCDate()).padStart(2,'0')}`; }
function toast(text) { clearTimeout(state.toastTimer); $('#toast').textContent = text; $('#toast').classList.add('show'); state.toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 2200); }
function fillIcons(root = document) { root.querySelectorAll('[data-icon]').forEach(el => { el.outerHTML = icon(el.dataset.icon); }); }
function renderNav() {
  const entries = [['LayoutDashboard','工作台','dashboard'],['Settings','系统权限','/system/users'],['Package','产品中心','/product/products'],['Boxes','仓储库存','/warehouse/stocks'],['ShoppingCart','采购业务','/purchase/orders'],['FileText','销售业务','/sales/orders'],['MessageSquare','智能助手','assistant']];
  $('#app-nav').innerHTML = entries.map(([i,label,target]) => `<button class="nav-item ${state.view === target ? 'active' : ''}" ${target.startsWith('/') ? `data-link="${target}"` : `data-view="${target}"`}>${icon(i)}<span>${label}</span>${target.startsWith('/') ? icon('ChevronDown','end') : ''}</button>`).join('');
  $$('.preview-tabs button').forEach(button => button.classList.toggle('active', button.dataset.view === state.view));
  $('#breadcrumb').innerHTML = state.view === 'dashboard' ? '工作台' : '智能助手 <span>/</span> 智能经营助手';
  $('#state-picker-label').hidden = state.view !== 'assistant'; $('#state-picker').value = state.ai;
}
function panelHead(title, subtitle, action = '') { return `<div class="panel-head"><div><h2 class="section-title">${title}</h2><p class="section-subtitle">${subtitle}</p></div>${action}</div>`; }
const todoSymbol = todo => ({系统:'TriangleAlert',采购:'ShoppingCart',销售:'FileText',库存:'Boxes',仓储:'Truck'})[todo.business];
const todoTone = todo => ({系统:'rose',采购:'blue',销售:'teal',库存:'amber',仓储:'blue'})[todo.business];
function todoList(modal = false) { return todos.map((todo,index) => `<button class="todo tone-${todoTone(todo)} ${modal && state.modalTodo === index ? 'active' : ''}" data-action="${modal ? 'select-todo' : 'todo'}" data-index="${index}" aria-label="${todo.title}，${todo.count}项" ${modal?`aria-pressed="${state.modalTodo===index}"`:''}><span class="todo-symbol">${icon(todoSymbol(todo))}</span><span class="todo-main"><span class="todo-name">${todo.title}</span><small class="todo-description">${todo.description}</small></span><span class="todo-count">${todo.count}</span>${icon('ChevronRight')}</button>`).join(''); }
function chartData() {
  const count = state.days; const result = { sales:[], purchase:[], profit:[] };
  for (let index = 0; index < count; index++) {
    const scale = .52 + index / count * .48;
    ['sales','purchase','profit'].forEach(key => result[key].push(count === 7 ? series[key][index] : Math.round(series[key][index % 7] * scale)));
  }
  return result;
}
function chartSVG() {
  const data = chartData(), count = state.days, width = 720, height = 214, left = 35, right = 9, top = 9, bottom = 29;
  const min = -150000, max = 450000, x = i => left + i * (width-left-right)/(count-1), y = v => top + (max-v)/(max-min)*(height-top-bottom);
  const grid = [-100000,0,200000,400000].map(value => `<line x1="${left}" x2="${width-right}" y1="${y(value)}" y2="${y(value)}" class="chart-grid" ${value === 0 ? 'stroke-dasharray="4 4"' : ''}/><text x="${left-9}" y="${y(value)+4}" text-anchor="end" class="chart-label">${value/10000}</text>`).join('');
  const curves = ['sales','purchase','profit'].map((key,index) => `<polyline points="${data[key].map((v,i) => `${x(i)},${y(v)}`).join(' ')}" fill="none" stroke="${chartColors[index]}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${data[key].map((v,i) => i === count-1 ? `<circle cx="${x(i)}" cy="${y(v)}" r="3" fill="${chartColors[index]}" stroke="white" stroke-width="2"/>` : '').join('')}`).join('');
  const step = count === 7 ? 1 : count === 15 ? 3 : 5;
  const dates = Array.from({length:count},(_,i) => i % step === 0 || i === count-1 ? `<text x="${x(i)}" y="${height-6}" class="chart-label" text-anchor="middle">${chartDate(i,count)}</text>` : '').join('');
  return `<svg class="trend-svg" viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="销售、采购、毛利经营趋势，金额单位万元"><text x="${left}" y="4" class="chart-label">万元</text>${grid}${curves}${dates}</svg>`;
}
function trendTable() { const data = chartData(); return `<div class="trend-data"><table class="data-table"><thead><tr><th>指标</th>${data.sales.map((_,i) => `<th>第${i+1}日</th>`).join('')}</tr></thead><tbody>${[['sales','销售额'],['purchase','采购额'],['profit','毛利额']].map(([key,label]) => `<tr><td>${label}</td>${data[key].map(v => `<td class="${v<0?'negative':''}">${compact(v)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`; }
function trendPanel() {
  const data = chartData(), sales = data.sales.reduce((a,b)=>a+b,0), purchase = data.purchase.reduce((a,b)=>a+b,0), profit = data.profit.reduce((a,b)=>a+b,0);
  return `${panelHead('经营趋势',`近 ${state.days} 日按当前权限展示销售、采购和毛利`, `<div class="segmented" aria-label="趋势周期">${[7,15,30].map(day => `<button data-action="range" data-days="${day}" class="${day===state.days?'active':''}" aria-pressed="${day===state.days}">${day}天</button>`).join('')}</div>`)}
  <div class="trend-metrics">${[['销售合计',compact(sales),chartColors[0]],['采购合计',compact(purchase),chartColors[1]],['毛利合计',compact(profit),chartColors[2]],['区间毛利率',`${(profit/sales*100).toFixed(1)}%`,'#a0adc0']].map(([label,value,color])=>`<div class="trend-metric"><label><span class="legend-dot" style="background:${color}"></span>${label}</label><strong>${value}</strong></div>`).join('')}</div>
  <div class="chart-wrap" tabindex="0" aria-label="经营趋势图，使用左右方向键查看日期">${chartSVG()}<div class="chart-tooltip" hidden></div></div>
  <div class="chart-legend"><span><i class="legend-dot" style="background:${chartColors[0]}"></i>销售额</span><span><i class="legend-dot" style="background:${chartColors[1]}"></i>采购额</span><span><i class="legend-dot" style="background:${chartColors[2]}"></i>毛利额</span><button class="link" data-action="trend-table">${state.table?'收起数值':'查看数值'} ${icon('ChevronDown')}</button></div>${state.table?trendTable():''}`;
}
function unitFor(row){return ({P000043:'个',P000027:'卷',P000002:'盒'})[row[0]]||'件';}
function filteredRisks(){return risks.filter(row=>state.warehouse==='全部仓库'||row[2]===state.warehouse);}
function riskRows() { return filteredRisks().map(row=>`<div class="risk-row"><div><strong>${row[1]}</strong><small>${row[2]} · ${row[0]} · 可用 ${row[3]} ${unitFor(row)} · 安全库存 ${row[4]} ${unitFor(row)}</small></div><span class="pill ${row[6]}">${row[5]}</span></div>`).join('') || '<div class="small-empty">当前仓库暂无风险库存</div>'; }
function dashboardHTML() {
  return `<main class="view dashboard" aria-label="工作台设计预览"><div class="page-heading"><div><h1>工作台</h1><p>聚合销售、采购、库存和履约风险，优先处理影响收入与交付的事项</p></div><div class="heading-actions"><small id="refreshed-at">更新于 2026-10-19 09:30</small><button class="btn" data-action="refresh">${icon('RefreshCw')}刷新</button></div></div>
  <section class="metrics" aria-label="经营指标">${[
    ['本月销售额','￥286,430','+12.8%','较上月','positive'],['本月毛利额','￥-842,600','+6.4%','较上月','positive'],['待处理订单','7','-8.1%','较昨日','positive','单'],['库存风险 SKU','11','+18.6%','较昨日','negative','个']
  ].map(([label,value,rate,compare,tone,unit],i)=>`<div class="metric"><div class="metric-label">${label}</div><div class="metric-number ${i===1?'negative':''}">${value}${unit?`<small>${unit}</small>`:''}</div><div class="metric-meta"><span class="${tone}">${rate}</span>${compare}</div></div>`).join('')}</section>
  <div class="dash-top"><section class="panel trend-panel" aria-label="经营趋势">${trendPanel()}</section><section class="panel todos-panel">${panelHead('业务待办','按交付影响与优先级排序', '<button class="link" data-action="todo" data-index="0">全部 8 项 '+icon('ArrowRight')+'</button>')}<div class="todo-list">${todoList()}</div></section></div>
  <div class="dash-middle"><section class="panel" aria-label="库存状态">${panelHead('库存状态','仓库范围内的健康分布与风险库存', `<select class="warehouse-select" aria-label="选择库存仓库"><option>全部仓库</option><option>华东中心仓</option><option>华南中心仓</option><option>南京备货仓</option></select>`)}<div class="inventory-state"><div class="health-bar"><span style="width:59%;background:#93bca9"></span><span style="width:32%;background:#d6be97"></span><span style="width:9%;background:#c68b96"></span></div><div class="health-key"><span><i class="dot" style="color:#93bca9"></i>正常<b>13</b></span><span><i class="dot" style="color:#d6be97"></i>低库存<b>7</b></span><span><i class="dot" style="color:#c68b96"></i>无可用<b>2</b></span><span><i class="dot" style="color:#d9dfe8"></i>零库存<b>0</b></span></div><div class="risk-list">${riskRows()}</div></div><button class="link" data-action="risk-all" style="margin-top:10px">查看全部风险库存 ${icon('ArrowRight')}</button></section>
  <section class="panel" aria-label="订单阶段">${panelHead('订单阶段','本月订单进度 · 2026-10-01 至 2026-10-31','<button class="link" data-action="stages">详情 '+icon('ArrowRight')+'</button>')}<div class="stage-head"><span class="stage-title">${icon('ShoppingCart')}采购订单</span><small>12 单</small></div><div class="stage-track">${[['待审核',2],['待入库',3],['部分入库',1],['已入库',6]].map(([label,n])=>`<button class="stage-item" data-action="stages"><b>${n}</b><small>${label}</small></button>`).join('')}</div><div class="stage-head"><span class="stage-title">${icon('FileText')}销售订单</span><small>14 单</small></div><div class="stage-track">${[['待审核',2],['待发货',3],['部分发货',2],['已发货',7]].map(([label,n])=>`<button class="stage-item" data-action="stages"><b>${n}</b><small>${label}</small></button>`).join('')}</div></section></div>
  <div class="dash-lower"><section class="panel">${panelHead('销售商品排行','按近30日净销售额排序','<button class="link" data-action="products">完整排行 '+icon('ArrowRight')+'</button>')}${products.slice(0,5).map((row,i)=>`<div class="ranking-row"><span class="rank-index">${String(i+1).padStart(2,'0')}</span><div class="ranking-name"><strong>${row[1]}</strong><small>${row[0]} · 净销量 ${row[3]} 件</small></div><span class="ranking-value">${money(row[2])}<small>可用库存 ${row[4]}</small></span></div>`).join('')}</section>
  <section class="panel">${panelHead('供应商履约','核心供应商的交付与质量表现','<button class="link" data-action="suppliers">完整明细 '+icon('ArrowRight')+'</button>')}${suppliers.slice(0,5).map((row,i)=>`<div class="ranking-row"><span class="rank-index">${String(i+1).padStart(2,'0')}</span><div class="ranking-name"><strong>${row[1]}</strong><small>${row[0]} · 平均到货 ${row[4]} 天</small></div><div class="supplier-score"><span>${row[2].toFixed(1)}<small>交付</small></span><span>${row[3].toFixed(1)}<small>质量</small></span></div></div>`).join('')}</section></div>
  <footer class="dashboard-foot"><span>所有经营指标沿用现有统计口径与模块权限</span><button class="text-button" data-action="dashboard-states">预览空态与权限状态</button></footer></main>`;
}
function conversationSidebar() {
  return `<aside class="conversation-sidebar ${state.collapsed?'collapsed':''}"><div class="conv-head"><strong>会话</strong><button class="icon-button" data-action="collapse-conversations" aria-label="${state.collapsed?'展开':'收起'}会话栏">${icon(state.collapsed?'PanelLeftOpen':'PanelLeftClose')}</button></div><button class="btn conv-create" data-action="new-conversation">${icon('Plus')}新建会话</button><div class="conv-scroll">${[['今天',state.conversations.slice(0,2)],['昨天',state.conversations.slice(2,3)],['更早',state.conversations.slice(3)]].map(([label,items])=>`<section class="conv-group ${state.groupClosed.has(label)?'collapsed':''}"><button class="conv-group-head" data-action="toggle-group" data-group="${label}">${label}${icon('ChevronDown')}</button><div class="conv-group-items">${items.map(title=>`<div class="conv-item ${title===state.conversation?'active':''}"><button class="conv-title" data-action="conversation" data-title="${esc(title)}" title="${esc(title)}">${esc(title)}</button><button class="icon-button" data-action="conversation-menu" data-title="${esc(title)}" aria-label="${esc(title)}的更多操作">${icon('MoreHorizontal')}</button></div>`).join('')}</div></section>`).join('')}</div><div class="conv-foot">保留每一次需求与分析计划<br/>历史会话可随时继续查看</div></aside>`;
}
function messageMeta(role,time) { return `<div class="message-meta">${role==='assistant'?icon('MessageSquare'):''}<strong>${role==='assistant'?'智能经营助手':'你'}</strong><time>10月19日 ${time}</time></div>`; }
function userMessage(text,time='09:24',id='message-1') { return `<article class="message user-message" id="${id}">${messageMeta('user',time)}<div class="message-body">${esc(text)}</div></article>`; }
function clarificationHTML(readonly = false) {
  return `<div class="clarification"><h3>${readonly?'已确认的分析条件':'确认两个条件，我会继续整理计划'}</h3><div class="clarify-field"><label>1. 分析覆盖的仓库范围</label><div class="choices">${['华东中心仓','全部仓库','自定义'].map((label,i)=>`<label class="choice"><input type="radio" name="warehouse" value="${label}" ${i===0?'checked':''} ${readonly?'disabled':''}/><span>${label}</span></label>`).join('')}</div></div><div class="clarify-field"><label>2. 用于参考的销售时间范围</label><div class="choices">${['近30天','近15天','自定义'].map((label,i)=>`<label class="choice"><input type="radio" name="period" value="${label}" ${i===0?'checked':''} ${readonly?'disabled':''}/><span>${label}</span></label>`).join('')}</div></div>${readonly?'<p class="message-note">本轮条件已提交，历史回答保持只读。</p>':`<div class="clarify-field"><label>补充说明 <small>选填</small></label><textarea placeholder="例如：优先考虑现有采购在途量，不纳入停用产品"></textarea></div><div class="clarify-footer"><small>也可以选择“自定义”填写范围</small><button class="btn primary" data-action="submit-clarification">确认并生成计划 ${icon('ArrowRight')}</button></div>`}</div>`;
}
function welcomeHTML() {
  return `<div class="welcome-state"><div class="welcome-icon">${icon('MessageSquare')}</div><h2>今天，你想分析什么？</h2><p>说说你的目标，我会帮你确认范围和条件，<br/>整理成清晰的分析计划。</p><div class="example-list">${[['Boxes','库存与补货','看看未来两周有哪些产品需要补货'],['TrendingUp','销售趋势','分析一下最近30天的销售趋势'],['ShoppingCart','采购分析','帮我比较主要供应商的采购情况']].map(([i,label,text])=>`<button class="example" data-action="example" data-query="${text}">${icon(i)}<span>${label}</span>${icon('ArrowRight')}</button>`).join('')}</div></div>`;
}
function messagesHTML() {
  if (state.ai === 'welcome') return welcomeHTML();
  const lead = `<div class="older-messages"><button data-action="older">加载较早消息</button></div>${userMessage(state.userQuery || queryText)}`;
  if (state.ai === 'error') return lead+`<div class="request-error">${icon('TriangleAlert')}<div><strong>暂时未能完成请求</strong><p>当前会话暂时无法读取，请稍后重试。</p><button class="link" data-action="reload-conversation">重新读取会话 ${icon('RefreshCw')}</button></div></div>`;
  if (state.ai === 'clarify') return lead+`<article class="message assistant-message" id="message-2">${messageMeta('assistant','09:24')}<div class="message-body"><p>我会先确认仓库和时间范围，再整理库存、销量与在途数量之间的分析步骤。</p>${clarificationHTML()}</div></article>`;
  if (state.ai === 'running') return lead+`<article class="message assistant-message" id="message-2">${messageMeta('assistant','09:24')}<div class="message-body"><p>已收到你的分析目标，正在整理范围与计划。</p><div class="running-indicator"><i class="spinner"></i><span>正在生成分析计划…</span></div><div class="progress-inline"><span>${icon('Check')}读取需求</span><span>${icon('Check')}确认分析范围</span><span><i class="spinner"></i>整理分析步骤</span></div></div></article>`;
  return `${lead}<article class="message assistant-message" id="message-2">${messageMeta('assistant','09:24')}<div class="message-body"><p>这次分析将以<strong>华东中心仓</strong>为范围，结合<strong>近30天销量</strong>，为未来两周的补货判断整理数据与分析步骤。</p><div class="progress-inline"><span>${icon('Check')}明确分析目标</span><span>${icon('Check')}确认范围</span><span>${icon('Check')}计划已生成</span><details><summary>查看计划生成过程 · 12秒</summary><ol><li>识别库存与补货分析需求</li><li>确认仓库、销量窗口和预测时间范围</li><li>整理数据查询与Python分析步骤</li></ol></details></div><h3>分析计划已准备好</h3><p>计划分为三步，先整理库存现状与销售参考，再生成补货建议的计算方案。你可以在右侧查看每一步的具体说明。</p><div class="plan-summary">${icon('ClipboardList')}<div><strong>库存补货分析计划</strong><small>3 个步骤 · 2 次数据查询 · 1 次 Python 分析</small></div><button class="btn" data-action="open-plan">查看计划 ${icon('ArrowRight')}</button></div><p class="message-note">当前阶段只生成分析计划，尚未执行数据查询或 Python 代码。</p></div></article><div class="message-divider">继续对话</div><article class="message assistant-message" id="message-3">${messageMeta('assistant','09:25')}<div class="message-body"><p>如果需要调整仓库范围、销售时间窗口或补货约束，可以继续补充，我会据此更新计划。</p></div></article>`;
}
function planPanel() {
  if (!state.plan) return `<aside class="plan-panel closed"><button class="icon-button" data-action="open-plan" aria-label="展开任务计划">${icon('PanelRightOpen')}</button></aside>`;
  return `<aside class="plan-panel" aria-label="任务计划"><header class="plan-head"><strong>${icon('ClipboardList')}任务计划</strong><button class="icon-button" data-action="toggle-plan" aria-label="关闭任务计划">${icon('PanelRightClose')}</button></header>${state.ai==='plan'?`<div class="plan-scroll"><span class="pill good"><i class="dot"></i>计划已生成</span><h2>库存补货分析计划</h2><p class="plan-objective">梳理华东中心仓未来两周的补货需求，结合当前可用库存、近30天销量与采购在途数量。</p><div class="plan-section-label">分析步骤</div>${planSteps.map(([i,type,text],index)=>`<section class="plan-step"><span class="step-number">${index+1}</span><div class="step-type">${icon(i)}${type}</div><p>${text}</p></section>`).join('')}<div class="plan-disclaimer">${icon('Info')}当前阶段只生成分析计划，尚未执行数据查询或 Python 代码。</div></div>`:`<div class="plan-empty">${icon('ClipboardList')}${state.ai==='clarify'?'补充范围和条件后，将继续生成完整分析步骤。':state.ai==='running'?'正在整理分析步骤，生成后会显示在这里。':'生成计划后，可在这里查看目标与分析步骤。'}</div>`}</aside>`;
}
function assistantHTML() {
  return `<div class="view ai-workspace ${state.collapsed?'conversations-collapsed':''} ${!state.plan?'plan-closed':''} ${state.drawer?'conversation-drawer':''}" aria-label="智能助手设计预览">${conversationSidebar()}<div class="panel-resizer conversation-resizer" tabindex="0" role="separator" aria-label="调整会话栏宽度" aria-orientation="vertical" data-resize="conversation"></div><main class="conversation-panel"><header class="conversation-header"><div class="conversation-title"><button class="icon-button mobile-conversations" data-action="mobile-conversations" aria-label="打开会话列表">${icon('Menu')}</button>${icon('MessageSquare')}<h1>${state.ai==='welcome'?'智能经营助手':esc(state.conversation)}</h1></div><div class="conversation-actions"><span class="task-status">${state.ai==='running'?'<i class="spinner"></i>':'<i class="dot"></i>'}${{welcome:'准备就绪',clarify:'等待补充条件',plan:'计划已生成',running:'正在生成计划',error:'请求未完成'}[state.ai]}</span><button class="btn" data-action="toggle-plan" aria-expanded="${state.plan}">${icon('ClipboardList')}计划</button></div></header><nav class="message-nav" aria-label="消息轮次导航">${state.ai==='welcome'?'':[1,2,...(state.ai==='plan'?[3]:[])].map(n=>`<button data-action="message-anchor" data-anchor="message-${n}" aria-label="跳到第${n}条消息" title="第${n}条消息"></button>`).join('')}</nav><div class="conversation-scroll"><div class="conversation-stream">${messagesHTML()}</div></div><footer class="composer-dock"><div class="composer-box"><textarea id="composer" maxlength="10000" rows="2" placeholder="${state.ai==='clarify'?'补充你的条件，或回答上方的问题…':'描述你的问题或分析目标…'}" aria-label="分析目标">${esc(state.draft)}</textarea><div class="composer-actions"><small>Enter 发送 · Shift + Enter 换行</small><button class="send-button" data-action="send" aria-label="发送分析目标" ${!state.draft.trim()||state.ai==='running'?'disabled':''}>${icon('Send')}</button></div></div><div class="composer-help">支持多轮澄清，生成清晰的分析计划</div></footer></main><div class="panel-resizer plan-resizer" tabindex="0" role="separator" aria-label="调整计划栏宽度" aria-orientation="vertical" data-resize="plan"></div>${planPanel()}<button class="backdrop" data-action="close-mobile-conversations" aria-label="关闭会话列表"></button></div>`;
}
function render(preserveScroll = false) {
  const scroll = preserveScroll ? $('.conversation-scroll')?.scrollTop || 0 : 0;
  renderNav(); $('#view-stage').innerHTML = state.view==='dashboard'?dashboardHTML():assistantHTML(); fillIcons();
  if (state.view==='assistant') { restoreWidths(); const area = $('.conversation-scroll'); if (area) area.scrollTop = scroll; }
  if(state.view==='assistant'&&state.ai==='clarify'){
    $('.clarify-footer button')?.remove();
    $('.clarification textarea')?.closest('.clarify-field').remove();
    const button=$('.send-button');button.disabled=false;button.textContent='确认并继续';button.setAttribute('aria-label','确认并继续');button.classList.add('clarification-submit');
    $('#composer').placeholder='补充说明（选填），确认上方范围后点击“确认并继续”…';
  }
  const warehouse = $('.warehouse-select'); if (warehouse) warehouse.value=state.warehouse;
}
let pageTimer;
function navigate(view) {
  if (view===state.view) return;
  clearTimeout(pageTimer); state.draft=$('#composer')?.value??state.draft; $('#view-stage').classList.add('leaving');
  pageTimer=setTimeout(()=>{state.view=view;state.drawer=false;location.hash=view==='assistant'?`assistant-${state.ai}`:'dashboard';render();$('#view-stage').classList.remove('leaving');},120);
}
let aiTimer;
function setAI(mode) { clearTimeout(aiTimer);state.ai=mode;state.plan=['plan','running'].includes(mode);state.drawer=false;if(state.view==='assistant'){location.hash=`assistant-${mode}`;render();} }
function scheduleAI(mode,delay) {clearTimeout(aiTimer);aiTimer=setTimeout(()=>setAI(mode),delay);}
function table(headers, rows) { return `<div style="overflow:auto"><table class="data-table"><thead><tr>${headers.map(h=>`<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.map(row=>`<tr>${row.map(cell=>`<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`; }
function openModal(title,description,body,footer='<button class="btn" data-action="close-modal">关闭</button>',width='1020px') { const dialog=$('#detail-dialog');dialog.style.maxWidth=width;$('#modal-title').textContent=title;$('#modal-description').textContent=description;$('#modal-body').innerHTML=body;$('#modal-footer').innerHTML=footer;fillIcons(dialog);if(!dialog.open)dialog.showModal(); }
function showTodo(index = 0) {
  state.modalTodo=index;const todo=todos[index];
  const waiting=todo.kind==='system'?'等待排查':todo.kind==='stock'?'待复核':todo.business==='仓储'?'待确认':'待审核';
  const field=(label,value,cls='')=>`<div class="record-field ${cls}"><dt>${label}</dt><dd>${esc(value)}</dd></div>`;
  let records;
  if(todo.kind==='stock')records=risks.slice(0,2).map(row=>`<article class="todo-record stock-record"><header><div><strong>${row[1]}</strong><small>${row[0]} · ${row[2]}</small></div><span class="pill ${row[6]}">${row[5]}</span></header><dl>${field('可用库存',`${row[3]} ${unitFor(row)}`,'attention')}${field('安全库存',`${row[4]} ${unitFor(row)}`)}${field('建议补货',`${Math.max(0,row[4]-row[3])} ${unitFor(row)}`)}</dl><div class="stock-compare"><span>可用 / 安全库存</span><div><i style="width:${Math.min(100,row[3]/row[4]*100)}%"></i></div><b>${row[3]} / ${row[4]}</b></div></article>`).join('');
  else records=todo.items.map((row,i)=>`<article class="todo-record ${todo.kind==='system'?'incident-record':''}"><header><div><strong>${row[0]}</strong><small>${todo.kind==='system'?'异常记录':'业务单据'}</small></div><span class="pill ${todo.kind==='system'?(i?'warn':'danger'):'blue'}">${todo.kind==='system'?row[2]:waiting}</span></header><dl>${todo.kind==='system'?`${field('业务来源',row[1])}${field('异常类型',i?'执行超时':'服务处理失败')}${field('发生时间',i?'2026-10-19 09:10':'2026-10-19 09:00')}${field('当前状态',row[3],'wide')}`:`${field(todo.business==='仓储'?'入出库仓库':'业务对象',row[1])}${field('来源单号',todo.business==='仓储'?(row[0].startsWith('IB')?'PO202610190001':'SO202610190001'):'—')}${field(todo.business==='仓储'?'单据状态':'订单金额',row[2])}${field('等待时长',row[3].replace('等待 ',''))}`}</dl></article>`).join('');
  const body=`<div class="todo-modal-list"><aside><div class="todo-sidebar-label">待办分类 <span>8</span></div>${todoList(true)}</aside><section class="todo-detail tone-${todoTone(todo)}" aria-label="待办详情"><div class="todo-detail-heading"><span class="detail-category-icon">${icon(todoSymbol(todo))}</span><div><div class="todo-category">${todo.business}业务 <i></i> ${todo.priority==='HIGH'?'高':'中'}优先级</div><h3>${todo.title}</h3><p>${todo.description}</p></div></div><div class="todo-overview"><div><small>待处理数量</small><strong>${todo.count}<em>${todo.kind==='stock'?'个 SKU':todo.kind==='system'?'条记录':'张单据'}</em></strong></div><div><small>当前状态</small><strong class="overview-state">${waiting}</strong></div><div><small>处理方式</small><strong class="overview-state">${todo.path?'业务处理':'系统追踪'}</strong></div></div><div class="records-heading"><h4>${todo.kind==='stock'?'风险库存明细':todo.kind==='system'?'异常记录':'相关业务单据'}</h4><small>共 ${todo.count} ${todo.kind==='stock'?'项':'条'}</small></div><div class="todo-records">${records}</div><div class="resolve-hint">${icon(todo.kind==='system'?'ShieldCheck':'Info')}<div><strong>${todo.kind==='system'?'排查指引':'处理指引'}</strong><p>${todo.hint}</p></div></div></section></div>`;
  const footer=`<span class="todo-footer-note">${icon(todo.path?'Info':'ShieldCheck')}${todo.path?'业务状态更新后，待办同步更新':'系统追踪记录 · 仅供查看'}</span><button class="btn" data-action="close-modal">关闭</button>${todo.path?`<a class="btn primary" href="http://127.0.0.1:5192${todo.path}" target="_blank" rel="noopener">前往完成 ${icon('ArrowRight')}</a>`:''}`;
  openModal('业务待办','集中查看业务进展，优先处理影响交付与库存的事项',body,footer,'1100px');
}
function showDetail(action) {
  if(action==='products')openModal('销售商品排行','近30日净销售额、净销量与当前可用库存',table(['排名','产品','净销售额','净销量','可用库存'],products.map((r,i)=>[i+1,`${r[1]}<small>${r[0]}</small>`,money(r[2]),r[3],r[4]])));
  if(action==='suppliers')openModal('供应商履约明细','交付、质量和平均到货周期保持原字段',table(['供应商','交付评分','质量评分','平均到货'],suppliers.map(r=>[`${r[1]}<small>${r[0]}</small>`,r[2].toFixed(1),r[3].toFixed(1),`${r[4]} 天`])));
  if(action==='risk-all')openModal('风险库存明细',`${state.warehouse} · 数量按产品单位显示`,table(['产品','仓库','单位','可用库存','安全库存','风险'],filteredRisks().map(r=>[`${r[1]}<small>${r[0]}</small>`,r[2],unitFor(r),r[3],r[4],`<span class="pill ${r[6]}">${r[5]}</span>`])),`<button class="btn" data-action="close-modal">关闭</button><a class="btn primary" href="http://127.0.0.1:5192/warehouse/stocks?riskOnly=true&from=dashboard${state.warehouse==='全部仓库'?'':'&warehouseId='+({'华东中心仓':'1930000000000000001','华南中心仓':'1930000000000000002','南京备货仓':'1930000000000000008'})[state.warehouse]}" target="_blank" rel="noopener">查看库存余额 ${icon('ArrowRight')}</a>`);
  if(action==='stages')openModal('订单阶段','当前统计期间：2026-10-01 00:00 至 2026-11-01 00:00，不含结束时刻',table(['业务','待审核','待入库 / 待发货','部分完成','已完成'],[['采购订单',2,3,1,6],['销售订单',2,3,2,7]]));
}
function conversationMenu(title) { openModal('会话操作','重命名或删除当前会话，画布仅演示本地状态',`<label for="rename-conversation" style="font-size:12px;color:#8c9aae;display:block;margin-bottom:10px">会话名称</label><input class="rename-input" id="rename-conversation" value="${esc(title)}" maxlength="100" data-original="${esc(title)}"/>`,`<button class="btn" data-action="delete-conversation" data-title="${esc(title)}">删除会话</button><button class="btn primary" data-action="rename-conversation">保存名称</button>`,'480px'); }
function submitClarification() {
  for(const radio of $$('.clarification input[type="radio"]:checked')) {
    if(radio.value!=='自定义')continue;
    const input=radio.closest('.clarify-field').querySelector('.custom-answer');
    if(!input?.value.trim()){input?.setCustomValidity('请填写具体范围');input?.reportValidity();input?.focus();return;}
    input.setCustomValidity('');
  }
  state.draft='';setAI('running');scheduleAI('plan',1200);
}
function send() {
  const input=$('#composer');if(!input||state.ai==='running')return;
  if(state.ai==='clarify'){submitClarification();return;}
  if(!input.value.trim())return;state.draft='';
  state.userQuery=input.value.trim(); const title=input.value.trim().slice(0,18);if(state.ai==='welcome'){state.conversation=title;state.conversations.unshift(title);}
  setAI('running');scheduleAI('clarify',1000);
}
function restoreWidths(){try{const values=JSON.parse(localStorage.getItem('workspace-preview-widths')||'{}');if(values.conversation)document.documentElement.style.setProperty('--conv',`${values.conversation}px`);if(values.plan)document.documentElement.style.setProperty('--plan',`${values.plan}px`);}catch{}}
function saveWidth(kind,value){const key=kind==='conversation'?'--conv':'--plan';document.documentElement.style.setProperty(key,`${value}px`);try{const values=JSON.parse(localStorage.getItem('workspace-preview-widths')||'{}');values[kind]=value;localStorage.setItem('workspace-preview-widths',JSON.stringify(values));}catch{}}
document.addEventListener('click',event=>{
  const target=event.target.closest('[data-action],[data-view],[data-link]');if(!target)return;
  if(target.dataset.view){navigate(target.dataset.view);return;}
  if(target.dataset.link){window.open(`http://127.0.0.1:5192${target.dataset.link}`,'_blank','noopener');return;}
  const action=target.dataset.action;
  if(action==='close-modal'){$('#detail-dialog').close();return;}
  if(action==='todo'||action==='select-todo'){showTodo(Number(target.dataset.index));return;}
  if(['products','suppliers','risk-all','stages'].includes(action)){showDetail(action);return;}
  if(action==='notifications'){showTodo(0);return;}
  if(action==='range'){if(state.days===Number(target.dataset.days))return;state.days=Number(target.dataset.days);const panel=$('.trend-panel');$('.chart-wrap').classList.add('switching');setTimeout(()=>{if(panel.isConnected)panel.innerHTML=trendPanel();},180);return;}
  if(action==='trend-table'){state.table=!state.table;$('.trend-panel').innerHTML=trendPanel();return;}
  if(action==='refresh'){const view=$('.dashboard');view.classList.add('reloading');target.disabled=true;setTimeout(()=>{view.classList.remove('reloading');target.disabled=false;($('#refreshed-at')||{}).textContent='更新于 2026-10-19 09:31';toast('示例数据已刷新');},500);return;}
  if(action==='dashboard-states'){openModal('数据状态预览','正式页面继续沿用现有权限、空态与失败回退分支',`<div class="demo-state-row"><button class="btn" data-action="state-empty">暂无数据</button><button class="btn" data-action="state-denied">暂无权限</button><button class="btn" data-action="state-stale">刷新失败</button></div><div id="demo-state-content" class="small-empty">请选择上方状态查看表现</div>`);return;}
  if(action==='state-empty'){$('#demo-state-content').innerHTML=`${icon('ChartNoAxesCombined')}<p style="margin-top:12px">当前统计期间暂无数据</p><small>调整统计范围后再查看</small>`;return;}
  if(action==='state-denied'){$('#demo-state-content').innerHTML=`${icon('ShieldCheck')}<p style="margin-top:12px">您暂无查看权限</p><small>按原模块权限控制展示内容</small>`;return;}
  if(action==='state-stale'){$('#demo-state-content').innerHTML='<span class="pill warn">刷新失败，当前显示上次数据；请重试。</span>';return;}
  if(action==='compare'){const key=state.view==='dashboard'?'dashboard':'assistant';openModal('与现有页面对照','左侧为当前页面真实截图；右侧为独立设计画布截图',`<div class="compare-images"><section><h3>当前页面</h3><img src="${key}-current.png" alt="当前${key}页面"/></section><section><h3>新设计预览</h3><img src="${key}-preview.png" alt="新设计预览" onerror="this.parentElement.innerHTML='<p>请直接查看当前画布</p>'"/></section></div>`);return;}
  if(action==='collapse-conversations'){state.collapsed=!state.collapsed;render(true);return;}
  if(action==='toggle-plan'||action==='open-plan'){state.plan=action==='open-plan'?true:!state.plan;render(true);return;}
  if(action==='new-conversation'){state.conversation='新会话';state.draft='';setAI('welcome');return;}
  if(action==='conversation'){state.conversation=target.dataset.title;state.drawer=false;state.ai='plan';state.plan=innerWidth>=1260;render();return;}
  if(action==='conversation-menu'){conversationMenu(target.dataset.title);return;}
  if(action==='rename-conversation'){const input=$('#rename-conversation'),value=input.value.trim();if(!value){input.focus();return;}state.conversations=state.conversations.map(t=>t===input.dataset.original?value:t);if(state.conversation===input.dataset.original)state.conversation=value;$('#detail-dialog').close();render(true);return;}
  if(action==='delete-conversation'){const title=target.dataset.title;openModal('删除会话', '此操作在画布中仅删除本地示例',`<p class="delete-description">确定删除“${esc(title)}”吗？删除后将不再显示在会话列表中。</p>`,`<button class="btn" data-action="close-modal">取消</button><button class="btn primary" data-action="confirm-delete-conversation" data-title="${esc(title)}">确认删除</button>`,'440px');return;}
  if(action==='confirm-delete-conversation'){state.conversations=state.conversations.filter(t=>t!==target.dataset.title);$('#detail-dialog').close();if(state.conversation===target.dataset.title)setAI('welcome');else render(true);return;}
  if(action==='toggle-group'){const group=target.dataset.group;if(state.groupClosed.has(group))state.groupClosed.delete(group);else state.groupClosed.add(group);render(true);return;}
  if(action==='mobile-conversations'){state.drawer=true;render(true);return;}
  if(action==='close-mobile-conversations'){state.drawer=false;render(true);return;}
  if(action==='example'){state.draft=target.dataset.query;render();$('#composer').focus();return;}
  if(action==='send'){send();return;}
  if(action==='submit-clarification'){submitClarification();return;}
  if(action==='reload-conversation'){setAI('running');scheduleAI('plan',800);return;}
  if(action==='older'){target.parentElement.innerHTML='<small>已加载较早消息 · 共3轮历史对话</small>';$('.conversation-stream').insertAdjacentHTML('afterbegin',userMessage('先帮我梳理库存与销量需要确认的条件。','09:18','message-older'));return;}
  if(action==='message-anchor'){const message=document.getElementById(target.dataset.anchor);if(message){message.scrollIntoView({behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth',block:'center'});message.classList.add('pulse');setTimeout(()=>message.classList.remove('pulse'),850);}return;}
});
document.addEventListener('change',event=>{
  if(event.target.id==='state-picker'){state.view='assistant';setAI(event.target.value);}
  if(event.target.classList.contains('warehouse-select')){state.warehouse=event.target.value;$('.inventory-state').classList.add('reloading');setTimeout(()=>{if($('.risk-list')){$('.risk-list').innerHTML=riskRows();$('.inventory-state').classList.remove('reloading');}},220);}
  if(event.target.matches('.choice input')&&event.target.value==='自定义'){const field=event.target.closest('.clarify-field');if(!field.querySelector('.custom-answer'))field.insertAdjacentHTML('beforeend','<input class="rename-input custom-answer" placeholder="请填写具体范围" style="margin-top:9px"/>');}
});
document.addEventListener('input',event=>{if(event.target.classList.contains('custom-answer'))event.target.setCustomValidity('');if(event.target.id==='composer'){state.draft=event.target.value;$('.send-button').disabled=(state.ai!=='clarify'&&!state.draft.trim())||state.ai==='running';}});
document.addEventListener('keydown',event=>{
  if(event.target.id==='composer'&&event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();send();}
  if(event.key==='Escape'){state.drawer=false;if($('.conversation-drawer'))render(true);}
  if(event.target.matches('.panel-resizer')&&['ArrowLeft','ArrowRight'].includes(event.key)){event.preventDefault();const kind=event.target.dataset.resize,style=getComputedStyle(document.documentElement),initial=parseInt(style.getPropertyValue(kind==='conversation'?'--conv':'--plan'))|| (kind==='conversation'?204:310),delta=(event.key==='ArrowRight'?1:-1)*(kind==='plan'?-16:16);saveWidth(kind,Math.max(kind==='plan'?280:200,Math.min(kind==='plan'?416:320,initial+delta)));}
  if(event.target.matches('.chart-wrap')&&['ArrowLeft','ArrowRight'].includes(event.key)){event.preventDefault();state.chartIndex=Math.max(0,Math.min(state.days-1,(state.chartIndex??state.days-1)+(event.key==='ArrowRight'?1:-1)));showChartTooltip(event.target,state.chartIndex);}
});
function showChartTooltip(chart,index){const data=chartData(),tooltip=chart.querySelector('.chart-tooltip');tooltip.hidden=false;tooltip.innerHTML=`<strong>${chartDate(index)}日</strong>${[['销售额',data.sales[index]],['采购额',data.purchase[index]],['毛利额',data.profit[index]]].map(([label,value])=>`<span>${label}<b class="${value<0?'negative':''}" style="font-weight:500">${money(value)}</b></span>`).join('')}`;}
document.addEventListener('pointermove',event=>{const chart=event.target.closest('.chart-wrap');if(!chart)return;const rect=chart.getBoundingClientRect(),index=Math.max(0,Math.min(state.days-1,Math.round((event.clientX-rect.left)/rect.width*(state.days-1))));showChartTooltip(chart,index);});
document.addEventListener('pointerout',event=>{const chart=event.target.closest('.chart-wrap');if(chart&&!chart.contains(event.relatedTarget))chart.querySelector('.chart-tooltip').hidden=true;});
document.addEventListener('pointerdown',event=>{const handle=event.target.closest('[data-resize]');if(!handle)return;event.preventDefault();const kind=handle.dataset.resize,rect=(kind==='conversation'?$('.conversation-sidebar'):$('.plan-panel')).getBoundingClientRect(),initial=rect.width,start=event.clientX;document.body.classList.add('resizing');handle.classList.add('dragging');function move(e){const delta=(e.clientX-start)*(kind==='plan'?-1:1);saveWidth(kind,Math.max(kind==='plan'?280:200,Math.min(kind==='plan'?416:320,initial+delta)));}function up(){document.body.classList.remove('resizing');handle.classList.remove('dragging');window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',up);}window.addEventListener('pointermove',move);window.addEventListener('pointerup',up);});
$('#detail-dialog').addEventListener('click',event=>{if(event.target===$('#detail-dialog')){const r=event.target.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)event.target.close();}});
window.addEventListener('hashchange',()=>{const view=location.hash.includes('assistant')?'assistant':'dashboard';if(view!==state.view){state.view=view;render();}});
render();
