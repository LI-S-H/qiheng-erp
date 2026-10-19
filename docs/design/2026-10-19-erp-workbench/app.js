/* 仅用于设计预览；数据来自已有 Mock，不调用业务接口。 */
const $ = s => document.querySelector(s);
const esc = x => String(x ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = n => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><use href="#${n}"/></svg>`;
const amount = n => n == null ? '—' : `¥${Number(n).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})}`;
const number = n => n == null ? '—' : Number(n).toLocaleString('zh-CN',{maximumFractionDigits:3});
const compact = n => n == null ? '—' : `${(n/10000).toFixed(1)}万`;
const app = {filter:'全部',stage:'purchaseCount',days:7,warehouse:'全部仓库',chartIndex:null,chartGeometry:null,lastTrigger:null};
const data = fixture.overview;
const groups = {
  PURCHASE:{icon:'ShoppingCart',tone:'#4d7ce0',tint:'#edf3ff'},
  SALES:{icon:'FileText',tone:'#269e8c',tint:'#eaf7f3'},
  WAREHOUSE:{icon:'Truck',tone:'#6889d2',tint:'#eff3fc'},
  INVENTORY:{icon:'Boxes',tone:'#c69541',tint:'#fff6e8'},
  SYSTEM:{icon:'ShieldCheck',tone:'#c76f80',tint:'#fceff3'},
};
const target = {
  'todo-purchase-approve':'/purchase/orders?status=SUBMITTED&from=dashboard',
  'todo-sales-approve':'/sales/orders?status=SUBMITTED&from=dashboard',
  'todo-purchase-return-approve':'/purchase/returns?status=SUBMITTED&from=dashboard',
  'todo-sales-return-approve':'/sales/returns?status=SUBMITTED&from=dashboard',
  'todo-inbound':'/warehouse/inbound-bills?status=PENDING_CONFIRM&from=dashboard',
  'todo-outbound':'/warehouse/outbound-bills?status=PENDING_CONFIRM&from=dashboard',
  'todo-stock-risk-review':'/warehouse/stocks?riskOnly=true&from=dashboard',
};
const realLink = (path,label,cls='shortcut') => `<a class="${cls}" href="http://127.0.0.1:9071${esc(path)}" target="_blank" rel="noopener">${label}</a>`;
const empty = state => `<div class="empty">${state === 'DENIED'?'当前账号暂无查看权限':'暂无数据'}</div>`;
const priority = p => `<span class="badge ${p==='HIGH'?'high':''}">${p==='HIGH'?'高优先级':p==='MEDIUM'?'中优先级':'低优先级'}</span>`;
const statuses = {SUBMITTED:'待审核',PENDING_CONFIRM:'待确认'};
function animate(el){el.classList.remove('swap');void el.offsetWidth;el.classList.add('swap');}
function toast(message){$('#toast').textContent=message;$('#toast').classList.add('show');clearTimeout(app.toast);app.toast=setTimeout(()=>$('#toast').classList.remove('show'),2300);}
function tabs(el,items,current,key){el.innerHTML=items.map(([value,label,count])=>`<button role="tab" aria-selected="${value===current}" tabindex="${value===current?0:-1}" data-${key}="${value}">${label}${count!=null?`<small>${count}</small>`:''}</button>`).join('');}
function setupShell(){
  $('#navigation').innerHTML=[['LayoutDashboard','工作台',null],['Settings','系统权限','/system/users'],['Package','产品中心','/product/products'],['Boxes','仓储库存','/warehouse/stocks'],['ShoppingCart','采购业务','/purchase/orders'],['FileText','销售业务','/sales/orders'],['MessageSquare','智能助手','/ai/assistant']].map(([i,label,path])=>path?realLink(path,`${icon(i)}<span>${label}</span>${i==='MessageSquare'?'':icon('ChevronRight').replace('class="icon"','class="icon chevron"')}`,'nav-item'):`<a href="#" class="nav-item active" aria-current="page">${icon(i)}<span>${label}</span></a>`).join('');
  $('#shortcuts').innerHTML=[['ShoppingCart','采购订单','/purchase/orders'],['FileText','销售订单','/sales/orders'],['Truck','入库确认','/warehouse/inbound-bills?status=PENDING_CONFIRM&from=dashboard'],['Boxes','出库确认','/warehouse/outbound-bills?status=PENDING_CONFIRM&from=dashboard'],['Package','库存余额','/warehouse/stocks'],['MessageSquare','智能助手','/ai/assistant']].map(([i,label,path])=>realLink(path,`${icon(i)}${label}`)).join('');
  $('#bell').innerHTML=`${icon('Bell')}<span class="bell-count">${data.pendingCount ?? data.todos.reduce((sum,t)=>sum+t.count,0)}</span>`;
  $('#updated').textContent=`数据更新于 ${data.refreshedAt}`;
  $('#refresh').innerHTML=`${icon('RefreshCw')}刷新`;
  $('#stage-icon').innerHTML=icon('ChartNoAxesCombined');
  $('#drawer-close').innerHTML=icon('X');
  $('#period').textContent=`${data.orderStagePeriod.startAt.slice(0,7)} 新建订单的当前状态`;
  const warehouses=['全部仓库',...new Set(fixture.inventory.riskPreview.items.map(i=>i.warehouseName))];
  $('#warehouse-options').innerHTML=warehouses.map(w=>`<button data-warehouse="${esc(w)}" aria-selected="${w===app.warehouse}">${esc(w)}</button>`).join('');
}
function renderMetrics(){
  const tones=['#356cdd','#179b85','#8390c5','#c79239'];
  const icons=['TrendingUp','ChartNoAxesCombined','ClipboardList','Boxes'];
  $('#metrics').innerHTML=data.metrics.map((m,i)=>{
    const visible=data.access.metrics[m.key]?.state==='ALLOWED';
    const change=m.comparisonState==='AVAILABLE' && m.changeRate!=null;
    return `<div class="metric" style="--tone:${tones[i]}"><div class="metric-label">${icon(icons[i])}${esc(m.label)}</div><div class="metric-value ${m.value<0?'negative':''}">${visible?(m.unit==='元'?`<small>¥</small> ${number(m.value)}`:`${number(m.value)}<small>${esc(m.unit)}</small>`):'—'}</div><div class="metric-change">${visible?(change?`<strong class="${m.status==='risk'?'risk':''}">${m.changeRate>=0?'↗':'↘'} ${m.changeRate>=0?'+':''}${number(m.changeRate)}%</strong><span>${esc(m.compareText)}</span>`:m.comparisonState==='NO_BASELINE'?'暂无可比基线':'暂无可比数据'):data.access.metrics[m.key]?.state==='DENIED'?'暂无查看权限':'暂无数据'}</div></div>`;
  }).join('');
}
function sortedTodos(){return [...data.todos].sort((a,b)=>a.sortWeight-b.sortWeight);}
function renderTodos(){
  const all=data.access.todos.state==='ALLOWED'?sortedTodos():[], visible=all.filter(t=>app.filter==='全部'||t.businessLabel===app.filter);
  $('#todo-count').textContent=all.length;
  const categories=['全部','采购','销售','仓储','库存','系统'];
  tabs($('#todo-tabs'),categories.map(c=>[c,c,all.filter(t=>c==='全部'||t.businessLabel===c).length]),app.filter,'filter');
  $('#todos').innerHTML=data.access.todos.state!=='ALLOWED'?empty(data.access.todos.state):`<div class="todo-list">${visible.map(t=>{const g=groups[t.businessType]||groups.SYSTEM;const first=t.detail?.items?.[0];const subtitle=first?.documentNo?`${first.documentNo} · ${first.counterpartyName||''}`:t.description;return `<button class="todo-row" data-todo="${t.todoId}" aria-label="${esc(t.title)}，${t.count}项，查看详情"><span class="tile" style="--tone:${g.tone};--tint:${g.tint}">${icon(g.icon)}</span><span class="todo-copy"><strong>${esc(t.title)}</strong><small title="${esc(subtitle)}">${esc(subtitle)}</small></span>${priority(t.priority)}<strong class="todo-number">${t.count}</strong>${icon('ChevronRight')}</button>`;}).join('')||empty('EMPTY')}</div>`;
  $('#todo-foot').textContent=`${visible.length} 类待办 · ${visible.reduce((s,t)=>s+t.count,0)} 项事项`;
}
function renderStages(){
  const perm=data.orderStagePermissions||{canViewPurchase:true,canViewSales:true};
  const allowed=[...(perm.canViewPurchase?[['purchaseCount','采购订单']]:[]),...(perm.canViewSales?[['salesCount','销售订单']]:[])];
  if(!allowed.some(([key])=>key===app.stage))app.stage=allowed[0]?.[0]||'purchaseCount';
  tabs($('#stage-tabs'),allowed,app.stage,'stage');
  const canView=data.access.orderStages.state==='ALLOWED'&&allowed.length;
  const stages=data.orderStages, total=stages.reduce((s,t)=>s+t[app.stage],0);
  $('#stage-total').textContent=canView?total:'—';
  const colors=['#b9c5d9','#e4b966','#80b1ee','#66bdbe','#70b69d','#d8dee7'];
  $('#stage-stack').innerHTML=canView?stages.map((t,i)=>`<span style="flex:${t[app.stage]};--tone:${colors[i]}"></span>`).join(''):'';
  $('#stages').innerHTML=canView?stages.map((t,i)=>`<div class="stage-row" style="--tone:${colors[i]}"><i></i><span>${esc(t.stage)}</span><strong>${t[app.stage]}</strong><small>单</small></div>`).join(''):empty(data.access.orderStages.state);
}
function trendSeries(){const perm=data.trendPermissions||{canViewSales:true,canViewPurchase:true,canViewGross:true};return [['salesAmount','销售额','#3977e6',perm.canViewSales],['purchaseAmount','采购额','#d89b39',perm.canViewPurchase],['grossMarginAmount','毛利额','#16a18c',perm.canViewGross]].filter(s=>s[3]);}
function renderTrend(){
  tabs($('#trend-tabs'),[7,15,30].map(d=>[String(d),`${d}天`]),String(app.days),'days');
  const series=trendSeries(), points=data.trend.slice(-app.days);
  $('#trend-summary').innerHTML=series.map(([key,label,color])=>`<div style="--tone:${color}"><small>${label.replace('额','合计')}</small><strong>${compact(points.reduce((s,p)=>s+p[key],0))}</strong></div>`).join('');
  if(series.some(s=>s[0]==='salesAmount')&&series.some(s=>s[0]==='grossMarginAmount')){
    const sales=points.reduce((sum,p)=>sum+p.salesAmount,0),gross=points.reduce((sum,p)=>sum+p.grossMarginAmount,0);
    $('#trend-summary').insertAdjacentHTML('beforeend',`<div style="--tone:#647c9c"><small>区间毛利率</small><strong>${sales>0?`${(gross/sales*100).toFixed(1)}%`:'—'}</strong></div>`);
  }
  $('.legend').innerHTML=series.map(([,label,color])=>`<span style="--tone:${color}">${label}</span>`).join('');
  if(!series.length||!points.length){$('#chart').innerHTML=empty(series.length?'EMPTY':'DENIED');$('#trend-summary').innerHTML='';$('#trend-data').innerHTML='';return;}
  const values=points.flatMap(p=>series.map(([key])=>p[key]));
  const min=Math.min(0,...values),max=Math.max(1,...values);
  const rawStep=(max-min)/4,base=10**Math.floor(Math.log10(rawStep)),fraction=rawStep/base;
  const step=(fraction<=1?1:fraction<=2?2:fraction<=5?5:10)*base;
  const lo=Math.floor(min/step)*step,hi=Math.ceil(max/step)*step;
  // 以实际像素尺寸绘图，避免固定 viewBox 在窄屏上拉伸坐标文字。
  const width=$('#chart').clientWidth,height=$('#chart').clientHeight,left=45,right=width-21,top=27,bottom=height-36;
  const x=i=>left+((right-left)*i/Math.max(points.length-1,1));const y=v=>bottom-((v-lo)/(hi-lo))*(bottom-top);
  app.chartGeometry={width,height,left,right,top,bottom,x,y};
  let grid='';for(let v=lo;v<=hi;v+=step)grid+=`<line x1="${left}" x2="${right}" y1="${y(v)}" y2="${y(v)}"/><text x="${left-12}" y="${y(v)+4}" text-anchor="end">${number(v/10000)}</text>`;
  const poly=series.map(([key,label,color],i)=>`${i===0?`<polygon points="${left},${y(0)} ${points.map((p,n)=>`${x(n)},${y(p[key])}`).join(' ')} ${right},${y(0)}" fill="#3977e6" opacity=".045"/>`:''}<polyline stroke="${color}" points="${points.map((p,n)=>`${x(n)},${y(p[key])}`).join(' ')}"/>`).join('');
  const tickIndexes=[...new Set([0,Math.floor((points.length-1)/3),Math.floor((points.length-1)*2/3),points.length-1])];
  $('#chart').innerHTML=`<svg viewBox="0 0 ${width} ${height}" role="img" aria-label="经营趋势，金额单位万元，支持负数"><text x="${left-12}" y="14" text-anchor="end">万元</text>${grid}${poly}${tickIndexes.map(n=>`<text x="${x(n)}" y="${height-9}" text-anchor="middle">${esc(points[n].date)}</text>`).join('')}<g class="chart-cursor" hidden><line class="chart-crosshair" y1="${top}" y2="${bottom}"/>${series.map(([key,,color])=>`<circle data-series="${key}" r="4.5" fill="${color}"/>`).join('')}</g></svg><div class="chart-tooltip" hidden></div>`;
  $('#trend-data').innerHTML=`<table aria-label="每日销售、采购与毛利金额，单位元"><thead><tr><th scope="col">日期 / 元</th>${points.map(p=>`<th scope="col">${esc(p.date)}</th>`).join('')}</tr></thead><tbody>${series.map(([key,label,color])=>`<tr><th scope="row" style="--tone:${color}"><i></i>${label}</th>${points.map(p=>`<td class="${p[key]<0?'negative':''}">${amount(p[key]).replace('¥','')}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
}
function chartTooltip(index,pointer){
  const points=data.trend.slice(-app.days),g=app.chartGeometry,tooltip=$('.chart-tooltip');if(!points.length||!g||!tooltip)return;
  app.chartIndex=Math.max(0,Math.min(points.length-1,index));const p=points[app.chartIndex],pointX=g.x(app.chartIndex);
  tooltip.hidden=false;tooltip.innerHTML=`<strong>${esc(p.date)}</strong>${trendSeries().map(([key,label,color])=>`<span><i style="background:${color}"></i>${label}<b>${amount(p[key])}</b></span>`).join('')}`;
  const cursor=$('.chart-cursor');cursor.removeAttribute('hidden');cursor.querySelector('line').setAttribute('x1',pointX);cursor.querySelector('line').setAttribute('x2',pointX);
  cursor.querySelectorAll('circle').forEach(c=>{c.setAttribute('cx',pointX);c.setAttribute('cy',g.y(p[c.dataset.series]));});
  const anchorX=pointer?.x??pointX,anchorY=pointer?.y??g.top+20;
  let left=anchorX+16,top=anchorY+16;
  if(left+tooltip.offsetWidth>g.width-4)left=anchorX-tooltip.offsetWidth-16;
  if(top+tooltip.offsetHeight>g.height-4)top=anchorY-tooltip.offsetHeight-16;
  tooltip.style.left=`${Math.max(4,Math.min(left,g.width-tooltip.offsetWidth-4))}px`;
  tooltip.style.top=`${Math.max(4,Math.min(top,g.height-tooltip.offsetHeight-4))}px`;
}
function clearChartCursor(){if($('.chart-tooltip'))$('.chart-tooltip').hidden=true;$('.chart-cursor')?.setAttribute('hidden','');}
function renderInventory(){
  const {inventory}=fixture;
  if(inventory.access.state!=='ALLOWED'){for(const sel of ['#inventory-distribution','#risks'])$(sel).innerHTML=empty(inventory.access.state);return;}
  const filtered=inventory.riskPreview.items.filter(i=>app.warehouse==='全部仓库'||i.warehouseName===app.warehouse);
  const specs=[['NORMAL','正常','#7cbcaf'],['LOW_STOCK','低库存','#e0b96d'],['NO_AVAILABLE','无可用','#e29191'],['OUT_OF_STOCK','零库存','#bd7e95']];
  // 单仓分布没有独立示例响应，不能从风险记录推导出全仓分布。
  const selected=app.warehouse==='全部仓库';
  $('#inventory-distribution').innerHTML=selected?`<div class="inventory-band">${specs.map(([key,,color])=>`<span style="flex:${inventory.distribution.find(d=>d.status===key)?.recordCount||0};--tone:${color}"></span>`).join('')}</div><div class="inventory-labels">${specs.map(([key,label,color])=>`<div style="--tone:${color}"><span>${label}</span><strong>${inventory.distribution.find(d=>d.status===key)?.recordCount??'—'}</strong></div>`).join('')}</div>`:'<div class="empty" style="padding:12px 0">仅预览该仓风险记录；单仓分布需读取对应接口</div>';
  $('#risks').innerHTML=filtered.map(i=>`<div class="risk-row"><div><strong>${esc(i.productName)}</strong><small>${esc(i.warehouseName)} · ${esc(i.productCode)}</small></div><div class="risk-qty">${number(i.availableQty)}<small> / ${number(i.safetyStockQty)} ${esc(i.unitName)}</small></div></div>`).join('')||empty('EMPTY');
  $('#warehouse-value').textContent=app.warehouse;
  document.querySelectorAll('[data-warehouse]').forEach(b=>b.setAttribute('aria-selected',b.dataset.warehouse===app.warehouse));
}
function productTable(full=false){const list=[...data.topProducts].sort((a,b)=>b.salesAmount-a.salesAmount).slice(0,full?undefined:5);return `<table class="rank-table"><thead><tr><th>商品</th><th>销售额</th><th>销量</th><th class="optional">可用库存</th></tr></thead><tbody>${list.map((p,i)=>`<tr><td><div class="product-label"><span class="rank-no ${i<3?'top':''}">${String(i+1).padStart(2,'0')}</span><div><strong>${esc(p.productName)}</strong><small>${esc(p.productCode)}</small></div></div></td><td>${amount(p.salesAmount)}</td><td>${number(p.salesQty)}</td><td class="optional">${number(p.availableQty)}</td></tr>`).join('')}</tbody></table>`;}
const rankedSuppliers=()=>[...data.supplierPerformance].sort((a,b)=>(b.scoreStatus==='READY'?(b.deliveryScore??-1):-1)-(a.scoreStatus==='READY'?(a.deliveryScore??-1):-1));
const supplierRank=p=>p.scoreStatus==='READY'&&p.deliveryScore!=null?data.supplierPerformance.filter(s=>s.scoreStatus==='READY'&&s.deliveryScore!=null&&s.deliveryScore>p.deliveryScore).length+1:null;
function supplierTable(full=false){const list=rankedSuppliers().slice(0,full?undefined:5);return `<table class="rank-table"><thead><tr><th>供应商</th><th>交期评分</th><th>质量评分</th><th class="optional">平均交期</th></tr></thead><tbody>${list.map(p=>{const rank=supplierRank(p);return `<tr><td><div class="product-label"><span class="rank-no ${rank!=null&&rank<4?'top':''}">${rank==null?'—':String(rank).padStart(2,'0')}</span><div><strong>${esc(p.supplierName)}</strong><small>${esc(p.supplierCode)}</small></div></div></td><td class="score">${p.scoreStatus==='READY'?number(p.deliveryScore):'未形成评分'}</td><td>${p.scoreStatus==='READY'?number(p.qualityScore):'—'}</td><td class="optional">${p.avgDeliveryDays==null?'—':`${number(p.avgDeliveryDays)}天`}</td></tr>`;}).join('')}</tbody></table>`;}
function renderRanks(){$('#products').innerHTML=data.access.topProducts.state==='ALLOWED'?productTable():empty(data.access.topProducts.state);$('#suppliers').innerHTML=data.access.supplierPerformance.state==='ALLOWED'?supplierTable():empty(data.access.supplierPerformance.state);}
function field(label,value,cls=''){return `<div><dt>${label}</dt><dd class="${cls}">${value}</dd></div>`;}
function showDrawer(title,description,eyebrow,content,footer){
  app.lastTrigger=document.activeElement;$('#drawer-title').textContent=title;$('#drawer-description').textContent=description;$('#drawer-eyebrow').textContent=eyebrow;
  $('#drawer-content').innerHTML=content;$('#drawer-footer').innerHTML=footer;$('#drawer').showModal();$('#drawer-content').scrollTop=0;
}
function closeDrawer(){
  const drawer=$('#drawer');if(drawer.classList.contains('closing'))return;
  drawer.classList.add('closing');
  setTimeout(()=>{drawer.close();drawer.classList.remove('closing');app.lastTrigger?.focus();},matchMedia('(prefers-reduced-motion:reduce)').matches?0:180);
}
function openTodo(id){
  const t=data.todos.find(x=>x.todoId===id);if(!t||data.access.todos.state!=='ALLOWED')return;
  const system=t.detail?.model==='SYSTEM_EXCEPTION', stock=t.detail?.model==='STOCK_RISK_REVIEW';
  const records=t.detail?.items||[];const total=t.count;
  const nav=t.completionMode==='AUTO'&&target[id];
  const facts=`<div class="detail-overview"><div><small>待办事项</small><strong>${total}</strong></div><div><small>处理优先级</small><strong>${priority(t.priority)}</strong></div><div><small>处理方式</small><strong>${system?'系统追踪':'业务完成后更新'}</strong></div></div>`;
  const documentLabels=t.detail?.model?.includes('RETURN')?['退货单号',t.businessType==='PURCHASE'?'原采购单号':'原销售单号']:['单据号','来源单号'];
  const recordsHtml=records.map(r=>{
    if(system)return `<article class="record"><header><strong>${esc(r.exceptionNo)}</strong><span class="badge high">${r.severity==='HIGH'?'高':r.severity==='MEDIUM'?'中':'低'}优先级</span></header><p>${esc(r.summary)}</p><dl>${field('异常类型',esc(r.exceptionType))}${field('来源模块',esc(r.sourceModule))}${field('发生时间',esc(r.occurredAt||'—'))}</dl></article>`;
    if(stock)return `<article class="record"><header><strong>${esc(r.productName)}</strong><span class="badge">${r.severity==='HIGH'?'高':'中'}风险</span></header><p>${esc(r.productCode)} · ${esc(r.warehouseName)}</p><dl>${field('可用库存',`${number(r.availableQty)} ${esc(r.unitName)}`,'warning')}${field('安全库存',`${number(r.safetyStockQty)} ${esc(r.unitName)}`)}${field('建议采购量',`${number(r.suggestedPurchaseQty)} ${esc(r.unitName)}`)}</dl></article>`;
    const wait=r.waitHours==null?'—':`${number(r.waitHours)} 小时`;
    return `<article class="record"><header><strong>${esc(r.documentNo)}</strong><span class="badge info">${esc(statuses[r.documentStatus]||r.documentStatus)}</span></header><p>${esc(r.counterpartyName||'—')}</p><dl>${field(documentLabels[0],esc(r.documentNo))}${field(documentLabels[1],esc(r.sourceDocumentNo||'—'))}${field('单据金额',amount(r.amountFen==null?null:r.amountFen/100),'money')}${field('已等待',`${wait}${r.waitLevel==='OVERDUE'?' · 已超时':r.waitLevel==='WARNING'?' · 需关注':''}`,r.waitLevel==='OVERDUE'?'danger':r.waitLevel==='WARNING'?'warning':'')}</dl></article>`;
  }).join('');
  const content=facts+`<div class="detail-heading"><h3>${system?'异常记录':stock?'风险商品':'单据明细'}</h3><small>预览 ${records.length} 条记录 · 共 ${total} 项</small></div>${recordsHtml||empty('EMPTY')}<div class="resolve">${icon('Info')}<div><h3>${system?'排查指引':'处理指引'}</h3><p>${esc(t.resolveHint||(system?'请根据异常类型、来源模块与发生时间定位原因；工作台仅展示记录。':'请前往对应业务页面核对完整单据。'))}</p></div></div>`;
  showDrawer(t.title,t.description,`${t.businessLabel} / 待办详情`,content,`<span>${system?'系统记录仅供查看':'处理仍在对应业务页面完成'}</span><button class="button" data-close>关闭</button>${nav?realLink(nav,`前往处理 ${icon('ArrowUpRight')}`,'button primary'):''}`);
}
function openRank(kind){
  if(kind==='products'){
    if(data.access.topProducts.state!=='ALLOWED')return;
    const list=[...data.topProducts].sort((a,b)=>b.salesAmount-a.salesAmount),max=Math.max(1,...list.map(p=>p.salesAmount));
    const heading=`<div class="ranking-intro"><span>${icon('TrendingUp')}按销售金额由高到低</span><small>${list.length} 件上榜商品 · 当前可见范围</small></div>`;
    const cards=list.map((p,i)=>`<article class="product-rank-card"><span class="rank-no ${i<3?'top':''}" aria-label="第${i+1}名">${String(i+1).padStart(2,'0')}</span><div class="product-rank-main"><header><div><h3>${esc(p.productName)}</h3><small>${esc(p.productCode)}</small></div><div class="rank-amount"><small>销售额</small><strong>${amount(p.salesAmount)}</strong></div></header><div class="rank-bar" aria-hidden="true"><i style="width:${Math.max(0,p.salesAmount)/max*100}%"></i></div><dl><div><dt>销量</dt><dd>${number(p.salesQty)}</dd></div><div><dt>可用库存</dt><dd>${number(p.availableQty)}</dd></div></dl></div></article>`).join('');
    showDrawer('销售商品排行','金额看贡献，销量与可用库存看供需；保留每件商品的完整信息。','经营 / 商品',heading+`<div class="product-ranking">${cards||empty('EMPTY')}</div>`,'<span>金额单位：元</span><button class="button" data-close>关闭</button>');
  }else{
    if(data.access.supplierPerformance.state!=='ALLOWED')return;
    const list=rankedSuppliers();
    const heading=`<div class="ranking-intro"><span>${icon('ChartNoAxesCombined')}按交期评分由高到低</span><small>同分并列 · 未形成交期评分不参与排名</small></div>`;
    const cards=list.map((p,i)=>{
      const ready=p.scoreStatus==='READY',rank=supplierRank(p);
      return `<article class="record supplier-rank-card"><header><span class="rank-no ${rank!=null&&rank<4?'top':''}" aria-label="${rank==null?'未排名':`第${rank}名`}">${rank==null?'—':String(rank).padStart(2,'0')}</span><div class="supplier-name"><strong>${esc(p.supplierName)}</strong><small>${esc(p.supplierCode)}</small></div><div class="delivery-lead"><small>交期评分</small><strong>${ready?number(p.deliveryScore):'—'}</strong></div></header><div class="supplier-score-grid">${[['overallScore','综合'],['qualityScore','质量'],['priceScore','价格'],['serviceScore','服务']].map(([key,label])=>`<div><span>${label}评分</span><strong>${ready?number(p[key]):'—'}</strong><i aria-hidden="true"><b style="width:${ready&&p[key]!=null?Math.max(0,Math.min(100,p[key])):0}%"></b></i></div>`).join('')}</div><dl>${field('平均交期',p.avgDeliveryDays==null?'—':`${number(p.avgDeliveryDays)} 天`)}${field('评分依据金额',amount(p.scoreBasisAmount))}</dl>${ready?'':'<p class="unscored">未形成评分</p>'}</article>`;
    }).join('');
    showDrawer('供应商履约排行','名次以交期评分为准；综合、质量、价格与服务评分作为独立比较依据。','采购 / 供应商',heading+cards,'<span>评分与平均交期均沿用现有数据</span><button class="button" data-close>关闭</button>');
  }
}
function openRisk(){
  if(fixture.inventory.access.state!=='ALLOWED')return;
  const rows=fixture.inventory.riskPreview.items.filter(r=>app.warehouse==='全部仓库'||r.warehouseName===app.warehouse);
  showDrawer('风险库存明细',`${app.warehouse} · 查看可用库存与安全库存依据`,'仓储 / 库存',rows.map(r=>`<article class="record"><header><strong>${esc(r.productName)}</strong><span class="badge">${({OUT_OF_STOCK:'零库存',NO_AVAILABLE:'无可用库存',LOW_STOCK:'低库存'})[r.severity]}</span></header><p>${esc(r.productCode)} · ${esc(r.warehouseName)}</p><dl>${field('可用库存',`${number(r.availableQty)} ${esc(r.unitName)}`,'warning')}${field('安全库存',`${number(r.safetyStockQty)} ${esc(r.unitName)}`)}</dl></article>`).join('')||empty('EMPTY'),`<button class="button" data-close>关闭</button>${realLink('/warehouse/stocks?riskOnly=true&from=dashboard','前往库存余额','button primary')}`);
}
document.addEventListener('click',event=>{
  const el=event.target.closest('button,a');if(!el)return;
  if(el.dataset.filter){app.filter=el.dataset.filter;renderTodos();animate($('#todos'));$(`[data-filter="${app.filter}"]`).focus();}
  if(el.dataset.stage){app.stage=el.dataset.stage;renderStages();animate($('#stages'));$(`[data-stage="${app.stage}"]`).focus();}
  if(el.dataset.days){app.days=Number(el.dataset.days);app.chartIndex=null;renderTrend();animate($('#chart'));$(`[data-days="${app.days}"]`).focus();}
  if(el.dataset.todo)openTodo(el.dataset.todo);
  if(el.dataset.rank)openRank(el.dataset.rank);
  if(el.hasAttribute('data-close'))closeDrawer();
  if(el.dataset.warehouse){app.warehouse=el.dataset.warehouse;$('#warehouse-select').open=false;renderInventory();animate($('#risks'));$('#warehouse-value').focus();}
});
document.addEventListener('click',e=>{if(!e.target.closest('#warehouse-select'))$('#warehouse-select').open=false;});
document.addEventListener('keydown',e=>{if(e.target.getAttribute('role')==='tab'&&['ArrowRight','ArrowLeft','Home','End'].includes(e.key)){e.preventDefault();const siblings=[...e.target.parentElement.querySelectorAll('[role=tab]')];const i=siblings.indexOf(e.target);const n=e.key==='Home'?0:e.key==='End'?siblings.length-1:(i+(e.key==='ArrowRight'?1:-1)+siblings.length)%siblings.length;siblings[n].click();}if(e.key==='Escape')$('#warehouse-select').open=false;});
$('#chart').addEventListener('pointermove',e=>{const g=app.chartGeometry;if(!g||!$('.chart-tooltip'))return;const rect=e.currentTarget.getBoundingClientRect(),x=e.clientX-rect.left,y=e.clientY-rect.top;if(x<g.left||x>g.right||y<g.top||y>g.bottom){clearChartCursor();return;}chartTooltip(Math.round((x-g.left)/(g.right-g.left)*(data.trend.slice(-app.days).length-1)),{x,y});});
$('#chart').addEventListener('pointerleave',clearChartCursor);
$('#chart').addEventListener('blur',clearChartCursor);
$('#chart').addEventListener('keydown',e=>{if(['ArrowRight','ArrowLeft'].includes(e.key)){e.preventDefault();chartTooltip((app.chartIndex??-1)+(e.key==='ArrowRight'?1:-1));}});
let chartResizeFrame;new ResizeObserver(()=>{cancelAnimationFrame(chartResizeFrame);chartResizeFrame=requestAnimationFrame(renderTrend);}).observe($('#chart'));
$('#drawer-close').onclick=closeDrawer;$('#drawer').addEventListener('cancel',e=>{e.preventDefault();closeDrawer();});$('#drawer').addEventListener('click',e=>{const r=$('#drawer').getBoundingClientRect();if(e.target===$('#drawer')&&(e.clientX<r.left||e.clientX>r.right))closeDrawer();});
$('#risk-detail').onclick=openRisk;
$('#bell').onclick=()=>{document.querySelector('#todo-title').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'instant':'smooth',block:'center'});toast('业务待办已展开在工作台，可点击查看明细');};
$('#refresh').onclick=async()=>{const el=$('#refresh');el.disabled=true;el.classList.add('spin');el.innerHTML=icon('RefreshCw')+'同步中';await new Promise(r=>setTimeout(r,380));el.disabled=false;el.classList.remove('spin');el.innerHTML=icon('RefreshCw')+'刷新';animate($('#metrics'));toast('演示刷新完成，保留原始示例数据');};
setupShell();renderMetrics();renderTodos();renderStages();renderTrend();renderInventory();renderRanks();
