// 只读已有开发数据，浏览器内延迟/扩充分页响应；不提交业务写请求。
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');
const {chromium}=require('playwright');
const base=process.env.SMOKE_BASE_URL||'http://127.0.0.1:9071';
const out=path.resolve(__dirname,'../../docs/qa-screenshots/2026-10-19-local-loading');fs.mkdirSync(out,{recursive:true});
const cases=[
 ['/purchase/orders','/purchase/orders','purchaseOrderId'],['/purchase/returns','/returns','returnOrderId'],
 ['/purchase/suppliers','/purchase/suppliers','supplierId'],['/purchase/supplier-products','/purchase/supplier-products','supplierProductId'],
 ['/sales/customers','/sales/customers','customerId'],['/sales/orders','/sales/orders','salesOrderId'],['/sales/returns','/returns','returnOrderId'],
 ['/warehouse/warehouses','/warehouse/warehouses','warehouseId'],['/warehouse/stocks','/warehouse/stocks','stockId'],
 ['/warehouse/inbound-bills','/warehouse/inbound-bills','workBillId'],['/warehouse/outbound-bills','/warehouse/outbound-bills','workBillId'],
 ['/warehouse/stock-bills','/warehouse/stock-bills','stockLedgerId'],['/product/products','/products','productId'],
 ['/system/users','/system/users','userId'],['/system/roles','/system/roles','roleId'],['/system/permissions','/system/permissions','permissionId'],
 ['/product/categories','/product/categories',null],['/system/depts','/system/depts',null],
];
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const context=await browser.newContext({viewport:{width:1440,height:900}}),page=await context.newPage();
 page.setDefaultTimeout(12000);
 const results=[],errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message));let current=null,failed=false;
 let navigations=0;page.on('framenavigated',frame=>{if(frame===page.mainFrame()){navigations+=1;console.log('NAV',frame.url());}});
 const settle=()=>page.waitForFunction(()=>!document.querySelector('[data-list-loading],[data-page-loading]'));
 try{
  await page.goto(base+'/login');await page.locator('#username').fill(process.env.SMOKE_USERNAME||'admin');await page.locator('#password').fill(process.env.SMOKE_PASSWORD||'123456');await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL(u=>u.pathname!=='/login');await settle();
  const cache=new Map();
  await page.route('**/api/**',async route=>{
   const req=route.request(),u=new URL(req.url());if(!u.pathname.startsWith('/api/'))return route.continue();
   if(req.method()!=='GET'){writes.push(u.pathname);return route.abort();}
   const endpoint=u.pathname.slice(4);if(!current||endpoint!==current[1])return route.continue();
   const key=endpoint+'?returnType='+(u.searchParams.get('returnType')||'');let response=cache.get(key);
   if(!response){const real=await route.fetch();assert(real.ok(),`只读样本接口${key}未就绪`);response=await real.json();assert.equal(response.code,0,`只读样本接口${key}返回失败`);cache.set(key,response);}
   await new Promise(r=>setTimeout(r,550));
   if(failed){failed=false;return route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({code:500,message:'受控验证：列表读取失败'})});}
   const body=JSON.parse(JSON.stringify(response));
   if(current[2]){
    assert(body.data.records?.length,`${key}需要至少一条只读样本`);
    const pageNum=Number(u.searchParams.get('pageNum')||1),size=Number(u.searchParams.get('pageSize')||10),original=body.data.records;
    body.data.records=Array.from({length:size},(_,i)=>({...original[i%original.length],[current[2]]:String(1959000000000000000n+BigInt((pageNum-1)*100+i))}));
    Object.assign(body.data,{pageNum,pageSize:size,total:60,hasNext:true,hasMore:true});
   }
   return route.fulfill({contentType:'application/json',body:JSON.stringify(body)});
  });
  async function feedback(label,act){
   console.log('CHECK',label);
   const beforeNavigations=navigations;
   await page.evaluate(()=>{window.__loadingSamples=[];window.__loadingStop=false;const sample=()=>{window.__loadingSamples.push({global:document.querySelectorAll('[data-page-loading]').length,local:document.querySelectorAll('[data-list-loading]').length});if(!window.__loadingStop)requestAnimationFrame(sample);};sample();});
   await act();await page.locator('[data-list-loading]').first().waitFor();await settle();
   const samples=await page.evaluate(()=>{window.__loadingStop=true;return window.__loadingSamples;});
   assert.equal(navigations,beforeNavigations,`${label}不能重新加载整个文档`);
   assert(samples.some(s=>s.local===1),`${label}必须有局部反馈`);assert(samples.every(s=>s.global===0),`${label}重复触发全页加载`);assert(samples.every(s=>s.local<=1),`${label}出现多个局部加载层`);
  }
  for(const entry of cases){
   console.log('OPEN',entry[0]);
   current=entry;await page.goto(base+entry[0]);await page.locator('.business-data-table tbody tr').first().waitFor();await settle();
   if(entry[2]){
    await feedback(entry[0]+'下一页',()=>page.locator('[data-table-pagination] [data-slot="pagination-next"]').click());
    await feedback(entry[0]+'切换条数',async()=>{await page.locator('[data-table-pagination] [role="combobox"]').click();await page.getByRole('option',{name:'20 条',exact:true}).click();});
   }
   for(const label of ['查询','重置','刷新'])await feedback(entry[0]+label,()=>page.getByRole('button',{name:label,exact:true}).click());
   results.push({route:entry[0],passed:true,actions:entry[2]?5:3});console.log('PASS',entry[0],'每次操作只有一层局部加载');
   if(entry[0]==='/purchase/orders'){
    failed=true;await feedback('列表读取失败',()=>page.getByRole('button',{name:'刷新',exact:true}).click());await feedback('列表失败后重试',()=>page.getByRole('button',{name:'刷新',exact:true}).click());
   }
  }
  current=['/purchase/suppliers','/purchase/score-change-logs',null];
  await page.goto(base+'/purchase/suppliers');await page.locator('.business-data-table tbody tr').first().waitFor();await settle();
  await feedback('评分记录弹窗打开',async()=>{await page.locator('.business-data-table tbody tr').first().locator('[data-row-actions-trigger]').click();await page.getByRole('menuitem',{name:'评分变更记录',exact:true}).click();});
  const dialog=page.locator('[data-score-change-log-dialog]');
  for(const label of ['查询','重置','刷新']){
   const button=dialog.getByRole('button',{name:label,exact:true});if(await button.count())await feedback('评分记录'+label,()=>button.click());
  }
  await dialog.locator('[data-slot="dialog-close"]').click();await dialog.waitFor({state:'hidden'});console.log('PASS 评分记录弹窗只使用局部加载');
  await page.getByRole('button',{name:'工作台',exact:true}).click();await page.locator('[data-page-loading]').waitFor();await page.waitForURL(u=>u.pathname==='/dashboard');await settle();console.log('PASS 路由跳转保留全页过渡');
  assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'LOCAL_LOADING_PASSED',routes:results,errors,writes},null,2));console.log('LOCAL_LOADING_PASSED',results.length);
 }catch(e){await page.screenshot({path:path.join(out,'failure.png'),fullPage:true});console.error('CURRENT',current,'URL',page.url());throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
