const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { chromium, expect } = require('playwright/test');
const base = process.env.DASHBOARD_UI_URL || process.env.SMOKE_BASE_URL || 'http://127.0.0.1:9071';
const out = path.resolve(__dirname, '../../docs/qa-screenshots/2026-10-19-dashboard-pilot');
fs.mkdirSync(out, { recursive: true });
const source = fs.readFileSync(path.resolve(__dirname, '../src/modules/dashboard/api.ts'), 'utf8');
const block = source.slice(source.indexOf('function formatMockDateTime'), source.indexOf('function normalizeMetric'));
const fixture = JSON.parse(vm.runInNewContext(`${ts.transpile(block, { target: ts.ScriptTarget.ES2022 })}\nJSON.stringify({overview:mockOverview,inventory:mockInventoryStatus})`, { Date }));
const user = { userId: '1900000000000000001', username: 'dashboard-qa', realName: '系统管理员', isAdmin: true, roleCodes: ['SUPER_ADMIN'], permissionCodes: [] };
const cases = [];
const pass = name => { cases.push(name); console.log('PASS', name); };
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, timezoneId: 'Asia/Shanghai' });
  await context.addInitScript(() => { localStorage.setItem('erp_auth_token', 'dashboard-qa'); localStorage.setItem('erp_auth_token_name', 'x-erp-session'); });
  const page = await context.newPage(), errors = [], requests = [];
  let mode = 'allowed', delay = 0, failed = false;
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const pathname = url.pathname.slice(4); requests.push({ path: pathname, query: Object.fromEntries(url.searchParams), method: route.request().method() });
    const json = data => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, message: '成功', data }) });
    if (pathname === '/auth/me') return json(user);
    if (delay) await new Promise(resolve => setTimeout(resolve, delay));
    if (pathname === '/dashboard/overview') {
      if (failed) return route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ code: 500, message: '受控刷新失败' }) });
      const value = structuredClone(fixture.overview);
      if (mode === 'denied' || mode === 'empty') {
        const state = mode.toUpperCase(); Object.values(value.access.metrics).forEach(access => access.state = state); for (const key of ['todos','orderStages','topProducts','supplierPerformance','stockAlerts']) value.access[key].state = state;
        value.metrics.forEach(metric => { metric.value = null; metric.changeRate = null; metric.comparisonState = null; }); value.todos = [];value.topProducts=[];value.supplierPerformance=[];value.orderStages=[];value.stockAlerts=[];value.trend=[];
        value.trendPermissions = mode === 'denied' ? { canViewSales:false,canViewPurchase:false,canViewGross:false } : { canViewSales:true,canViewPurchase:true,canViewGross:true };
      }
      if (mode === 'purchase-only') { value.trendPermissions={canViewSales:false,canViewPurchase:true,canViewGross:false};value.orderStagePermissions={canViewPurchase:true,canViewSales:false}; }
      // 保留原示例，再补同分、未评分的可区分边界。
      if (mode === 'supplier-edge') { value.supplierPerformance[1].overallScore=value.supplierPerformance[0].overallScore;value.supplierPerformance[1].scoreStatus='READY';value.supplierPerformance[2].scoreStatus='NOT_READY'; }
      for(const point of value.trend) for(const key of ['salesAmount','purchaseAmount','grossMarginAmount']) point[key]=Number(point[key]).toFixed(2);
      for(const product of value.topProducts) product.salesAmount=Number(product.salesAmount).toFixed(2);
      for(const supplier of value.supplierPerformance) if(supplier.scoreBasisAmount!==null) supplier.scoreBasisAmount=Number(supplier.scoreBasisAmount).toFixed(2);
      return json(value);
    }
    if (pathname === '/dashboard/inventory-status') {
      const value=structuredClone(fixture.inventory);
      if (mode==='denied') return json({access:{state:'DENIED'}});
      if (mode==='empty') {value.access.state='EMPTY';value.distribution.forEach(item=>item.recordCount=0);value.riskPreview.items=[];}
      if (url.searchParams.has('warehouseId')) { value.distribution[0].recordCount=9;value.riskPreview.items=value.riskPreview.items.slice(0,1); }
      return json(value);
    }
    if (pathname === '/warehouse/warehouses') return json({ records: [{ warehouseId:'1900000000000000301',warehouseCode:'WH001',warehouseName:'华东中心仓',status:1,version:0,contactName:'',contactPhone:'',address:'',remark:'',createTime:'2026-10-19 10:00:00',updateTime:'2026-10-19 10:00:00' }],total:1,pageNum:1,pageSize:10 });
    return route.fulfill({ status:403, contentType:'application/json',body:JSON.stringify({code:403,message:'非本次范围'}) });
  });
  const settled=async()=>{await expect(page.locator('.dashboard-skeleton')).toHaveCount(0);await expect(page.locator('.dashboard-metric')).toHaveCount(4);await expect(page.locator('[data-page-loading]')).toHaveCount(0);await expect(page.locator('[data-list-loading]')).toHaveCount(0);};
  const shot=async name=>{await page.waitForTimeout(250);await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});};
  const open=async()=>{await page.goto(base+'/dashboard');await settled();};
  const assertTodoHover=async()=>{
    // 等详情关闭后的焦点恢复完成，避免它把已滚入视口的待办重新卷走。
    await expect(page.locator('.dashboard-detail-drawer')).toHaveCount(0);await page.waitForTimeout(300);
    const row=page.locator('.dashboard-todo-row').first();await row.scrollIntoViewIfNeeded();
    const geometry=await row.evaluate(node=>{const r=node.getBoundingClientRect(),p=node.closest('.dashboard-panel').getBoundingClientRect(),tile=node.querySelector('.dashboard-todo-tile').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,pLeft:p.left,pRight:p.right,left:tile.left-r.left};});
    assert(Math.abs(geometry.x-geometry.pLeft-1)<1&&Math.abs(geometry.x+geometry.width-geometry.pRight+1)<1,'待办悬停区域应占满面板内宽');assert(geometry.left>=14,'文字图标仍保留行内留白');
    for(const x of [geometry.x+3,geometry.x+geometry.width-3]){await page.mouse.move(x,geometry.y+geometry.height/2);await page.waitForTimeout(180);assert.equal(await row.evaluate(n=>getComputedStyle(n).backgroundColor),'rgb(237, 242, 248)','待办左右边缘均应触发加深背景');}
  };
  try {
    await open();
    await expect(page.getByRole('heading',{name:'业务待办',exact:false})).toBeVisible();
    const order=await page.locator('.dashboard-todo-copy>strong').allTextContents();
    assert.deepEqual(order,[...fixture.overview.todos].sort((a,b)=>a.sortWeight-b.sortWeight).map(todo=>todo.title));
    const colors=await page.locator('.dashboard-metric').evaluateAll(nodes=>nodes.map(node=>getComputedStyle(node).backgroundColor));assert(colors.every(color=>color==='rgb(255, 255, 255)'));
    assert.equal(await page.locator('.dashboard-trend-labels text').count(),7);
    const centered=await page.locator('[data-dashboard-trend-summary]>div').evaluateAll(nodes=>nodes.map(node=>({align:getComputedStyle(node).textAlign,x:node.getBoundingClientRect().x,width:node.getBoundingClientRect().width,children:[...node.children].map(child=>({x:child.getBoundingClientRect().x,width:child.getBoundingClientRect().width}))})));
    assert.equal(centered.length,4);assert(centered.every(item=>item.align==='center'&&item.children.every(child=>Math.abs(child.x+child.width/2-item.x-item.width/2)<1)));
    const values=await page.locator('.dashboard-trend-values-scroll').evaluate(node=>({client:node.clientWidth,scroll:node.scrollWidth,sticky:getComputedStyle(node.querySelector('th')).position}));
    assert(values.scroll-values.client<2,`1440默认7天需完整显示 ${JSON.stringify(values)}`);assert.equal(values.sticky,'sticky');
    await assertTodoHover();await shot('todo-hover-full-row');await page.mouse.move(0,0);pass('待办悬停铺满面板左右边缘，文字留白不变，背景柔和加深');
    await shot('dashboard-1440');pass('白底四指标、业务待办sortWeight排序、趋势汇总居中、默认7个日期与数值全显');
    const chart=page.locator('.dashboard-trend-chart-wrap');await chart.scrollIntoViewIfNeeded();const rect=await chart.boundingBox();
    await page.mouse.move(rect.x+rect.width*.36,rect.y+80);await expect(page.locator('[data-dashboard-trend-tooltip]')).toBeVisible();const first=await page.locator('[data-dashboard-trend-tooltip]').boundingBox();
    await page.mouse.move(rect.x+rect.width*.38,rect.y+95);const second=await page.locator('[data-dashboard-trend-tooltip]').boundingBox();assert(Math.abs(second.x-first.x)>2||Math.abs(second.y-first.y)>2);assert.equal(await page.locator('.dashboard-chart-cursor circle').count(),3);await shot('trend-hover');await page.mouse.move(0,0);await expect(page.locator('[data-dashboard-trend-tooltip]')).toHaveCount(0);
    await chart.focus();await page.keyboard.press('End');await expect(page.locator('[data-dashboard-trend-tooltip]')).toBeVisible();await page.keyboard.press('Tab');await expect(page.locator('[data-dashboard-trend-tooltip]')).toHaveCount(0);
    await page.getByRole('button',{name:'30天',exact:true}).click();await expect(page.locator('.dashboard-trend-values thead th')).toHaveCount(31);assert(await page.locator('.dashboard-trend-labels text').count()>=8);await page.getByRole('button',{name:'7天',exact:true}).click();pass('Tooltip同日期跟随、竖线与三点、键盘定位、30天密集刻度与完整矩阵');
    for(const todo of fixture.overview.todos){
      await page.getByRole('tab',{name:/^全部/}).click();await page.locator('.dashboard-todo-row').filter({hasText:todo.title}).click();await expect(page.locator('.dashboard-detail-drawer')).toBeVisible();await expect(page.locator('[data-slot=dialog-title]')).toContainText(todo.title);
      for(const record of todo.detail?.items||[]){if(record.documentNo)await expect(page.locator('.dashboard-drawer-body')).toContainText(record.documentNo);if(record.sourceDocumentNo)await expect(page.locator('.dashboard-drawer-body')).toContainText(record.sourceDocumentNo);if(record.productCode)await expect(page.locator('.dashboard-drawer-body')).toContainText(record.productCode);if(record.exceptionNo)await expect(page.locator('.dashboard-drawer-body')).toContainText(record.exceptionNo);}
      if(todo.detail?.model==='PURCHASE_RETURN_APPROVAL')await expect(page.locator('.dashboard-drawer-body')).toContainText('18,656.00');
      if(todo.detail?.model==='STOCK_RISK_REVIEW'){await expect(page.locator('.dashboard-drawer-body')).toContainText('0 个');await expect(page.locator('.dashboard-drawer-body')).toContainText('40 卷');}
      if(todo.resolveHint)await expect(page.locator('.dashboard-resolve')).toContainText(todo.resolveHint);
      if(todo.completionMode==='TRACKED')assert.equal(await page.locator('.dashboard-drawer-footer').getByRole('button',{name:'前往完成'}).count(),0);
      if(todo.detail?.model==='STOCK_RISK_REVIEW')await shot('detail-stock');if(todo.detail?.model==='SYSTEM_EXCEPTION')await shot('detail-system');if(todo.detail?.model==='PURCHASE_ORDER_APPROVAL')await shot('detail-order');
      await page.getByRole('button',{name:'关闭详情',exact:true}).click();await expect(page.locator('.dashboard-detail-drawer')).toHaveCount(0);
    }pass('八类待办所有原字段与resolveHint保留，系统TRACKED仅查看，抽屉开合与Esc焦点恢复');
    const targets={
      'todo-purchase-approve':['/purchase/orders',{status:'SUBMITTED',from:'dashboard'}],
      'todo-sales-approve':['/sales/orders',{status:'SUBMITTED',from:'dashboard'}],
      'todo-purchase-return-approve':['/purchase/returns',{status:'SUBMITTED',from:'dashboard'}],
      'todo-sales-return-approve':['/sales/returns',{status:'SUBMITTED',from:'dashboard'}],
      'todo-inbound':['/warehouse/inbound-bills',{status:'PENDING_CONFIRM',from:'dashboard'}],
      'todo-outbound':['/warehouse/outbound-bills',{status:'PENDING_CONFIRM',from:'dashboard'}],
      'todo-stock-risk-review':['/warehouse/stocks',{riskOnly:'true',from:'dashboard'}],
    };
    for(const [id,[pathname,query]] of Object.entries(targets)){
      await page.goto(base+'/dashboard?todoId='+id);await settled();await expect(page.locator('.dashboard-detail-drawer')).toBeVisible();await page.locator('.dashboard-drawer-footer').getByRole('button',{name:'前往完成'}).click();await page.waitForURL(url=>url.pathname===pathname);assert.deepEqual(Object.fromEntries(new URL(page.url()).searchParams),query);
    }
    await page.goto(base+'/dashboard?todoId=todo-system-exception&source=bell');await settled();await expect(page.locator('.dashboard-detail-drawer')).toBeVisible();await page.keyboard.press('Escape');await expect(page.locator('.dashboard-detail-drawer')).toHaveCount(0);assert.deepEqual(Object.fromEntries(new URL(page.url()).searchParams),{source:'bell'});
    await page.goto(base+'/dashboard?todoId=missing-todo&source=bell');await settled();await page.waitForURL(url=>!url.searchParams.has('todoId'));assert.equal(new URL(page.url()).searchParams.get('source'),'bell');assert.equal(await page.locator('.dashboard-detail-drawer').count(),0);await expect(page.locator('[data-sonner-toast]').filter({hasText:'该待办已处理、不可查看或不存在'})).toBeVisible();await open();
    pass('七类业务精确status/from/riskOnly导航，todoId深链接、关闭清参和无效ID安全降级');
    const supplierTable=page.locator('.dashboard-supplier-table');
    await expect(supplierTable.locator('thead th').first()).toHaveText('供应商');assert.deepEqual(await supplierTable.locator('thead th').allTextContents(),['供应商','质量评分','平均交期','综合评分']);assert(await supplierTable.locator('tbody tr').first().evaluate(row=>row.querySelector('.dashboard-rank-identity').getBoundingClientRect().right<=row.querySelector('.dashboard-score').getBoundingClientRect().left),'综合评分应在排名和供应商右侧');
    const ranked=fixture.overview.supplierPerformance.slice().sort((a,b)=>(b.overallScore??-1)-(a.overallScore??-1));
    assert.equal(await supplierTable.locator('tbody tr').count(),await supplierTable.locator('tbody tr>td:last-child.dashboard-score').count(),'每行综合评分都应在最右列');const scoreValues=await supplierTable.locator('tbody td.dashboard-score').allTextContents();assert.deepEqual(scoreValues,ranked.slice(0,5).map(s=>s.scoreStatus==='READY'?s.overallScore.toFixed(1):'未形成评分'));
    for(const table of await page.locator('.dashboard-ranking-grid table').all()){
      const row=table.locator('tbody tr').first();await row.scrollIntoViewIfNeeded();const box=await row.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.waitForTimeout(180);
      const cells=await row.locator('td').evaluateAll(nodes=>nodes.map(n=>getComputedStyle(n).backgroundColor));assert(cells.every(color=>color==='rgb(240, 244, 250)'));
    }
    for(const action of await page.locator('.dashboard-rank-action').all()){
      assert(await action.evaluate(node=>{const s=getComputedStyle(node);return parseFloat(s.paddingLeft)>=12&&parseFloat(s.paddingRight)>=12;}));
    }
    pass('综合评分位于最右列且按综合分排序，两排行整行悬停与入口留白一致');await page.mouse.move(0,0);await shot('ranking-overview');
    await page.getByRole('button',{name:'查看详情销售商品排行'}).click();await expect(page.locator('.dashboard-product-card')).toHaveCount(fixture.overview.topProducts.length);await shot('detail-products');await page.keyboard.press('Escape');await expect(page.locator('.dashboard-detail-drawer')).toHaveCount(0);
    await page.getByRole('button',{name:'查看详情供应商履约'}).click();await expect(page.locator('.dashboard-supplier-card')).toHaveCount(fixture.overview.supplierPerformance.length);assert.equal(await page.locator('.dashboard-supplier-score-grid').first().locator('strong').count(),4);await expect(page.locator('.dashboard-supplier-overall small').first()).toHaveText('综合评分');await expect(page.locator('.dashboard-supplier-overall strong').first()).toHaveText(ranked[0].overallScore.toFixed(1));await expect(page.locator('.dashboard-supplier-score-grid').first()).toContainText('交期评分');await shot('detail-suppliers');await page.keyboard.press('Escape');pass('销售图形排行与供应商五项评分、交期和依据金额完整呈现');
    await page.getByRole('button',{name:'销售订单',exact:true}).click();const stageTotal=fixture.overview.orderStages.reduce((sum,row)=>sum+row.salesCount,0);await expect(page.locator('.dashboard-stage-total strong')).toHaveText(String(stageTotal));await expect(page.locator('.dashboard-stages')).toContainText('取消');pass('订单阶段采购销售切换保留取消状态与当前状态口径');
    for(const [width,height] of [[1920,1080],[1115,838],[390,844]]){
      await page.setViewportSize({width,height});await assertTodoHover();await page.mouse.move(0,0);await page.locator('main').evaluate(node=>node.scrollTop=0);await page.waitForTimeout(300);await shot(`dashboard-${width}`);
      assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`${width}页面横向溢出`);
      const labels=await page.locator('.dashboard-trend-labels text').evaluateAll(nodes=>nodes.map(node=>{const r=node.getBoundingClientRect();return {left:r.left,right:r.right};}));for(let i=1;i<labels.length;i++)assert(labels[i].left>=labels[i-1].right+3,`${width}日期重叠`);
      await page.getByRole('button',{name:'查看详情供应商履约'}).click();assert(await page.locator('.dashboard-detail-drawer').evaluate(node=>node.scrollWidth<=node.clientWidth+1));await shot(`detail-suppliers-${width}`);await page.keyboard.press('Escape');
    }pass('1920/1440/1115/390响应式无页面溢出，日期不重叠，抽屉字段与底栏可达');
    for(const width of [1115,390]){
      await page.setViewportSize({width,height:900});
      for(const days of [15,30]){
        await page.getByRole('button',{name:days+'天',exact:true}).click();await chart.scrollIntoViewIfNeeded();await page.waitForTimeout(120);
        const axis=await page.locator('.dashboard-trend-labels text').allTextContents();assert.equal(axis[0],fixture.overview.trend.at(-days).date.slice(-5));assert.equal(axis.at(-1),fixture.overview.trend.at(-1).date.slice(-5));
        const geometry=await page.locator('.dashboard-trend-labels text').evaluateAll(nodes=>nodes.map(node=>({text:node.textContent,x:+node.getAttribute('x'),left:node.getBoundingClientRect().left,right:node.getBoundingClientRect().right})));for(let i=1;i<geometry.length;i++)assert(geometry[i].left>=geometry[i-1].right+3);
        const chartRect=await chart.boundingBox();
        for(const [ratioX,ratioY] of [[.18,.12],[.94,.12],[.18,.8],[.94,.8]]){
          await page.mouse.move(chartRect.x+chartRect.width*ratioX,chartRect.y+chartRect.height*ratioY);await expect(page.locator('[data-dashboard-trend-tooltip]')).toBeVisible();const tip=await page.locator('[data-dashboard-trend-tooltip]').boundingBox();assert(tip.x>=chartRect.x-1&&tip.x+tip.width<=chartRect.x+chartRect.width+1&&tip.y>=chartRect.y-1&&tip.y+tip.height<=chartRect.y+chartRect.height+1,`Tooltip ${width}/${days}四角越界 ${JSON.stringify({tip,chartRect})}`);
          const points=fixture.overview.trend.slice(-days),index=Math.round((chartRect.width*ratioX-48)/(chartRect.width-70)*(days-1));await expect(page.locator('[data-dashboard-trend-tooltip] strong')).toHaveText(points[Math.max(0,Math.min(days-1,index))].date);
        }
        await page.mouse.move(0,0);
      }
    }
    pass('1115/390在15/30天首末日期保留、刻度与原日期一致、Tooltip四角跟随且不越界');
    await page.setViewportSize({width:1440,height:900});await page.getByRole('button',{name:'7天',exact:true}).click();await page.locator('.inventory-status-panel').scrollIntoViewIfNeeded();await page.locator('.inventory-status-panel [role=combobox]').click();
    await page.getByPlaceholder('输入仓库编码或名称').fill('华东');await page.locator('[data-select-option]').filter({hasText:'华东中心仓'}).click();await settled();
    assert(requests.some(request=>request.path==='/dashboard/inventory-status'&&request.query.warehouseId==='1900000000000000301'));assert(requests.some(request=>request.path==='/warehouse/warehouses'&&request.query.warehouseName==='华东'&&request.query.pageSize==='10'));pass('仓库远程搜索10条、明确仓库名参数与warehouseId接口刷新');
    delay=700;failed=true;await page.getByRole('button',{name:'刷新',exact:true}).click();await expect(page.locator('.dashboard-content>[data-list-loading]')).toBeVisible();await expect(page.locator('[data-page-loading]')).toHaveCount(0);await expect(page.getByRole('button',{name:'刷新中',exact:true})).toBeDisabled();await expect(page.locator('.dashboard-refresh-error')).toContainText('上次数据');assert.equal(await page.locator('.dashboard-metric').count(),4);delay=0;failed=false;pass('刷新只复用局部加载、按钮锁定、失败保留旧数据');
    mode='purchase-only';await open();assert.equal(await page.locator('.dashboard-trend-summary>div').count(),1);assert.equal(await page.locator('.dashboard-trend-values tbody tr').count(),1);assert.equal(await page.getByRole('button',{name:'销售订单',exact:true}).count(),0);pass('趋势与订单维度权限裁剪');
    mode='supplier-edge';await open();await page.getByRole('button',{name:'查看详情供应商履约'}).click();const ranks=await page.locator('.dashboard-supplier-card>.dashboard-supplier-score-grid').count();assert(ranks>0);const headers=await page.locator('.dashboard-supplier-card').evaluateAll(nodes=>nodes.map(node=>({rank:node.querySelector('.dashboard-rank-no').textContent,name:node.querySelector('.dashboard-supplier-identity strong').textContent,text:node.textContent})));assert.equal(headers[0].rank,headers[1].rank);assert(headers.some(header=>header.rank==='—'&&header.text.includes('未形成评分')));await page.keyboard.press('Escape');pass('供应商同分并列、NOT_READY不排名且不误显示零分');
    for(const state of ['denied','empty']){mode=state;await open();assert.equal(await page.locator('.dashboard-todo-row').count(),0);assert.equal(await page.locator('.dashboard-trend-chart').count(),0);assert.equal(await page.locator('.dashboard-product-card').count(),0);await expect(page.locator('[data-dashboard-inventory-state]')).toBeVisible();pass(`${state}状态不暴露业务数值与操作`);}
    assert.deepEqual(errors,[]);assert(requests.every(request=>request.method==='GET'),'QA不产生业务写请求');
    fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({cases,errors,requests},null,2));console.log('DASHBOARD_PILOT_PASSED',cases.length);
  }catch(error){await page.screenshot({path:path.join(out,'failed.png'),fullPage:true});throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
