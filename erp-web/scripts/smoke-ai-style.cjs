const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium,expect}=require('playwright/test');
const base=process.env.AI_GRAPH_UI_URL||'http://127.0.0.1:9071';
const out=path.resolve(__dirname,'../../docs/qa-screenshots/2026-10-19-ai-style');
fs.mkdirSync(out,{recursive:true});
const result=[];
const pass=label=>{result.push(label);console.log('PASS',label);};
const ids=['1960000000000000101','1960000000000000102','1960000000000000103'];
const entries=ids.map((id,i)=>({conversationId:id,title:['库存与补货分析','多轮销售分析','会话读取恢复'][i],description:'',updatedAt:'2026-10-19 09:24:00'}));
const user={userId:'1900000000000000001',username:'style-qa',realName:'系统管理员',isAdmin:true,roleCodes:['SUPER_ADMIN'],permissionCodes:['ai:assistant:use']};
const plan={title:'库存补货分析计划',objective:'梳理华东中心仓的补货需求，结合当前可用库存、近30天销量与采购在途数量。',needs_clarification:false,clarification_questions:[],steps:[{step:1,type:'SQL_QUERY',instruction:'查询指定仓库的当前库存、锁定库存、可用库存与安全库存。'},{step:2,type:'SQL_QUERY',instruction:'查询近30日产品销量及已审核采购的在途数量，补齐分析范围。'},{step:3,type:'PYTHON_ANALYSIS',instruction:'结合销量、安全库存与在途数量，整理未来两周补货建议的计算步骤。'}]};
const message=(n,role,content)=>({messageId:String(1963000000000000000n+BigInt(n)),requestId:'style-qa',seq:String(n),role,kind:role==='user'?'USER_INPUT':'ASSISTANT_FINAL',createdAt:'2026-10-19 09:24:00',content});
const simple=[message(1,'user',{type:'TEXT',data:{text:'帮我看看华东中心仓未来两周有哪些产品需要补货，结合最近30天的销量来分析。'}}),message(2,'assistant',{type:'PLAN',data:{text:'已根据你的目标整理分析计划。你可以查看完整目标和分析步骤，继续补充条件。',plan,threadId:'style-thread'}})];
const long=Array.from({length:12},(_,i)=>[message(100+i*2,'user',{type:'TEXT',data:{text:`第${i+1}轮：核对华东仓最近30天销量与库存，保留在途采购和安全库存口径。`}}),message(101+i*2,'assistant',{type:'TEXT',data:{text:'将先确认商品和仓库范围，再梳理销量、可用库存与采购在途的关系。\n\n'.repeat(5)}})]).flat();
long.push(message(200,'assistant',{type:'PLAN',data:{text:'已整理本轮分析计划。',plan,threadId:'long-style-thread'}}));

(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_BROWSER_PATH||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const context=await browser.newContext({viewport:{width:1440,height:900},timezoneId:'Asia/Shanghai'});
 await context.addInitScript(()=>{localStorage.setItem('erp_auth_token','style-qa-token');localStorage.setItem('erp_auth_token_name','x-erp-session');});
 const page=await context.newPage(),errors=[],reads=[];let failNext=false,delay=0,overviewReads=0;
 page.on('pageerror',error=>errors.push(error.message));
 await page.route('**/api/**',async route=>{
   const request=route.request(),url=new URL(request.url()),pathname=url.pathname.replace(/^\/api/,'');
   if(!url.pathname.startsWith('/api/'))return route.continue();
   const json=data=>route.fulfill({contentType:'application/json',body:JSON.stringify({code:0,message:'成功',data})});
   if(pathname==='/auth/me')return json(user);
   if(pathname==='/ai/assistant/overview'){overviewReads+=1;return json({refreshedAt:'2026-10-19 09:24:00',conversations:entries,quickPrompts:[]});}
   const match=pathname.match(/^\/ai\/assistant\/conversations\/(\d+)(\/messages)?$/);
   if(match){
     reads.push(pathname);if(delay)await new Promise(resolve=>setTimeout(resolve,delay));
     if(failNext&&match[1]===ids[2]){if(!match[2]){failNext=false;return route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({code:500,message:'受控检查：会话读取失败'})});}}
     const entry=entries.find(item=>item.conversationId===match[1]);
     if(match[2]){const records=entry.conversationId===ids[1]?long:simple;return json({records,pageNum:1,pageSize:50,total:records.length});}
     return json({...entry,runStatus:'PLAN_READY',running:false,runNode:null,runLabel:null});
   }
   // 非本次检查的工作台请求保持权限失败，不伪造经营指标。
   return route.fulfill({status:403,contentType:'application/json',body:JSON.stringify({code:403,message:'该接口不在界面检查范围内'})});
 });
 const shot=async name=>{await page.waitForTimeout(300);await page.screenshot({path:path.join(out,name+'.png'),fullPage:true});};
 const settled=async()=>{await expect(page.locator('[data-page-loading]')).toHaveCount(0);await expect(page.locator('[data-ai-conversation-region]>[data-list-loading]')).toHaveCount(0);};
 const bottom=async()=>page.locator('.conversation-scroll').evaluate(el=>Math.abs(el.scrollHeight-el.scrollTop-el.clientHeight));
 try{
   await page.goto(base+'/ai/assistant');await expect(page.locator('.plan-artifact')).toBeVisible();await settled();
   await expect(page.locator('.app-shell')).toHaveClass(/ai-pilot-theme/);
   await expect(page.locator('.create-symbol svg')).toBeVisible();
   const fontSizes=await page.locator('.group-head,.conversation-title').evaluateAll(nodes=>nodes.map(n=>getComputedStyle(n).fontSize));assert(fontSizes.length>1&&fontSizes.every(size=>size==='13px'));
   const avatars=await page.locator('.message-avatar').evaluateAll(nodes=>nodes.map(n=>({user:n.closest('.message').classList.contains('is-user'),background:getComputedStyle(n).backgroundColor,color:getComputedStyle(n).color})));
   assert(avatars.some(a=>a.user&&a.background==='rgb(230, 243, 238)'&&a.color==='rgb(53, 120, 97)'));assert(avatars.some(a=>!a.user&&a.background==='rgb(232, 240, 255)'&&a.color==='rgb(65, 105, 173)'));
   assert.equal(await page.locator('.plan-artifact').evaluate(n=>getComputedStyle(n).backgroundColor),'rgb(251, 252, 255)','计划卡片保持原样');
   pass('用户绿/助手蓝头像对比明确，分组标题与会话名称同为13px，计划卡片不变');
   await page.locator('.plan-artifact').click();await page.waitForTimeout(300);
   await shot('assistant-1440');
   const widths=await page.locator('.desktop-plan').boundingBox();
   await page.evaluate(()=>{window.__aiStableNodes={shell:document.querySelector('.app-shell'),sidebar:document.querySelector('.graph-workspace>.conversation-sidebar'),composer:document.querySelector('[data-ai-composer-dock]')};});
   delay=700;
   const beforeOverviewReads=overviewReads;
   await page.evaluate(()=>{
     const button=document.querySelector('.sidebar-create .create-button');
     const sidebar=document.querySelector('.graph-workspace>.conversation-sidebar');
     const bounds=button.getBoundingClientRect();
     window.__aiSidebarStable={bounds:{x:bounds.x,y:bounds.y,width:bounds.width,height:bounds.height},scroll:sidebar.querySelector('.sidebar-list').scrollTop,background:getComputedStyle(button).backgroundColor,samples:[]};
     const started=performance.now();
     const sample=()=>{const r=button.getBoundingClientRect(),s=getComputedStyle(button);window.__aiSidebarStable.samples.push({x:r.x,y:r.y,width:r.width,height:r.height,opacity:s.opacity,background:s.backgroundColor});if(performance.now()-started<1600)requestAnimationFrame(sample);};sample();
   });
   await page.locator('.conversation-main').filter({hasText:'多轮销售分析'}).click();
   await expect(page.locator('[data-ai-conversation-region]>[data-list-loading]')).toBeVisible();
   await expect(page.locator('[data-page-loading]')).toHaveCount(0);
   assert.equal(await page.locator('.message-list>.message').count(),simple.length,'加载期间保留旧会话作为不可操作上下文');
   assert(await page.locator('.conversation-scroll').evaluate(el=>el.inert));
   assert(await page.locator('.desktop-plan .plan-scroll').evaluate(el=>el.inert));
   await expect(page.locator('.sidebar-create .create-button')).toBeDisabled();
   assert.equal(await page.locator('.sidebar-create .create-button').evaluate(el=>getComputedStyle(el).opacity),'1');
   assert(Math.abs((await page.locator('.desktop-plan').boundingBox()).width-widths.width)<1,'切换不改变用户的计划面板开合');
   await shot('conversation-local-loading');
   await expect(page.locator('.message-list>.message')).toHaveCount(long.length);await settled();
   assert(await bottom()<2,'短会话切到长会话必须滚到新消息末尾');
   assert(await page.evaluate(()=>window.__aiStableNodes.shell===document.querySelector('.app-shell')&&window.__aiStableNodes.sidebar===document.querySelector('.graph-workspace>.conversation-sidebar')&&window.__aiStableNodes.composer===document.querySelector('[data-ai-composer-dock]')));
   const stable=await page.evaluate(()=>window.__aiSidebarStable);
   assert(stable.samples.length>8&&stable.samples.every(s=>s.opacity==='1'&&s.background===stable.background&&['x','y','width','height'].every(k=>Math.abs(s[k]-stable.bounds[k])<.5)),'新建会话按钮逐帧位置、尺寸、透明度与底色稳定');
   assert.equal(overviewReads,beforeOverviewReads,'切换会话不重新读取会话管理列表');
   const sameReads=reads.length;await page.locator('.conversation-main').filter({hasText:'多轮销售分析'}).click();await page.waitForTimeout(100);assert.equal(reads.length,sameReads,'点击当前会话不重复读取');
   pass('会话切换保留管理区，新建按钮逐帧无明暗或几何抖动，当前会话不重复加载');
   pass('同页切换仅对话及计划局部加载，旧上下文inert，侧栏/应用壳/输入实例与计划宽度不变');
   delay=200;await page.locator('.conversation-main').filter({hasText:'库存与补货分析'}).click();await expect(page.locator('.message-list>.message')).toHaveCount(simple.length);await settled();assert(await bottom()<2);pass('短→长、长→短均按新内容高度定位最后消息');
   failNext=true;await page.locator('.conversation-main').filter({hasText:'会话读取恢复'}).click();await expect(page.getByTestId('graph-request-error')).toContainText('受控检查：会话读取失败');await settled();
   assert.equal(await page.locator('.message-list>.message').count(),0,'失败时不能继续显示上一会话快照');assert.equal(await page.locator('.welcome-state').count(),0);
   await page.getByRole('button',{name:'重新读取会话',exact:true}).click();await expect(page.locator('.plan-artifact')).toBeVisible();await settled();pass('读取失败清除旧快照并保留原重试入口');
   // 已有详情时发生规划错误，也必须能强制重读；不能被当前会话防重复守卫拦住。
   await page.locator('.ai-composer__input').fill('受控检查：请求失败后重新读取当前会话');
   await page.locator('.ai-composer__input').press('Enter');
   await expect(page.getByTestId('graph-request-error')).toBeVisible();
   const beforeRetry=reads.length;
   await page.getByRole('button',{name:'重新读取会话',exact:true}).click();
   await expect(page.getByTestId('graph-request-error')).toHaveCount(0);await settled();
   assert(reads.length>=beforeRetry+2,'已有detail时重试仍读取会话详情与消息');
   pass('已有会话详情的请求错误可强制重读，普通重复点击仍不刷新');
   delay=0;await page.locator('.conversation-main').filter({hasText:'多轮销售分析'}).click();await expect(page.locator('.message-list>.message')).toHaveCount(long.length);await settled();
   const beforeReads=reads.length,dockBefore=await page.locator('[data-ai-composer-dock]').boundingBox();
   const first=page.getByRole('button',{name:'跳转到第 1 条用户消息',exact:true});await first.click();await page.waitForTimeout(1100);
   assert.equal(reads.length,beforeReads);assert.equal((await page.locator('[data-ai-composer-dock]').boundingBox()).y,dockBefore.y);
   assert(await page.locator('.conversation-scroll').evaluate(el=>el.scrollTop<20));await expect(page.locator('[data-list-loading]')).toHaveCount(0);await expect(page.locator('[data-page-loading]')).toHaveCount(0);
   const nav=await page.locator('.message-navigator').boundingBox(),body=await page.locator('.conversation-body').boundingBox();assert(nav.x>=body.x&&nav.x<body.x+30);assert(Math.abs(nav.y+nav.height/2-body.y-body.height/2)<2);
   await page.mouse.move(800,80);await first.hover();await expect(page.locator('[data-slot="tooltip-content"]:not([data-state="closed"])')).toBeVisible();await shot('message-navigation-left');pass('消息定位只滚动、不读接口、不刷新页面；左侧中部提示与固定输入底座');
   await page.mouse.move(800,80);
   for(const [width,height]of[[1920,1080],[1115,838],[390,844]]){
     await page.setViewportSize({width,height});await page.waitForTimeout(300);
     if(width===1115){await expect(page.getByRole('dialog')).toBeVisible();await shot('plan-drawer-1115');await page.keyboard.press('Escape');}
     await shot('assistant-'+width);
     assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));assert(await page.locator('[data-ai-composer-dock]').evaluate(el=>el.getBoundingClientRect().bottom<=innerHeight+1));
   }
   await page.getByRole('button',{name:'打开会话列表',exact:true}).click();await expect(page.getByRole('dialog').getByRole('button',{name:'新建会话',exact:true})).toBeVisible();await shot('conversations-mobile');await page.keyboard.press('Escape');
   await page.getByRole('button',{name:'查看任务计划',exact:true}).click();await expect(page.getByRole('dialog').getByRole('heading',{name:plan.title,exact:true})).toBeVisible();await shot('plan-mobile');await page.keyboard.press('Escape');pass('1920/1440/1115/390、两侧手机抽屉、字体和输入底座边界');
   await page.emulateMedia({reducedMotion:'reduce'});await page.setViewportSize({width:1440,height:900});await page.getByRole('button',{name:'收起会话列表',exact:true}).click();assert(await page.locator('.graph-workspace>.conversation-sidebar').evaluate(el=>parseFloat(getComputedStyle(el).transitionDuration)<=.001&&Math.abs(el.getBoundingClientRect().width-52)<1));await page.getByRole('button',{name:'展开会话列表',exact:true}).click();pass('减少动态效果与既有面板收放能力保留');
   assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'AI_STYLE_PASSED',checks:result,errors},null,2));console.log('AI_STYLE_PASSED',result.length);
 }catch(error){console.error({errors,reads,url:page.url(),body:(await page.locator('body').innerText()).slice(0,1500)});await shot('failure').catch(()=>{});throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
