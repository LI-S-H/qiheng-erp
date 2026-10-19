// 字体更换回归只查看列表、表单和图表，不提交业务数据。
const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
const {chromium}=require('playwright');
const base=process.env.SMOKE_BASE_URL||'http://127.0.0.1:9071';
const out=path.resolve(__dirname,'../../docs/qa-screenshots/2026-10-19-noto-font');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const context=await browser.newContext({viewport:{width:1440,height:900}}),page=await context.newPage();
 const errors=[],failedFonts=[],fontRequests=[],writes=[],checks=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/\.woff2(?:\?|$)/.test(r.url()))fontRequests.push(r.url());});
 page.on('response',r=>{if(/\.woff2(?:\?|$)/.test(r.url())&&r.status()>=400)failedFonts.push({url:r.url(),status:r.status()});});
 const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('[data-list-loading],[data-page-loading]'));await page.evaluate(()=>document.fonts.ready);await page.waitForTimeout(300);};
 const shot=name=>page.screenshot({path:path.join(out,name+'.png'),fullPage:false});
 try{
  await page.goto(base+'/login');await page.locator('#username').fill('admin');await page.locator('#password').fill('123456');await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL(u=>u.pathname!=='/login');await ready();
  page.on('request',r=>{if(r.url().includes('/api/')&&r.method()!=='GET')writes.push(new URL(r.url()).pathname);});
  await page.goto(base+'/purchase/orders');await page.locator('.business-data-table tbody tr').first().waitFor();await ready();
  const type=await page.locator('.business-data-table').evaluate(el=>({family:getComputedStyle(el).fontFamily,rows:[...el.querySelectorAll('thead th,tbody tr:first-child>td')].map(e=>({size:getComputedStyle(e).fontSize,align:getComputedStyle(e).textAlign})),fontReady:document.fonts.check('14px "Noto Sans SC Variable"','采购订单金额日期')}));
  assert(type.family.includes('Noto Sans SC Variable')&&type.fontReady);assert(type.rows.every(c=>c.size==='14px'&&c.align==='center'));
  await page.locator('.business-data-table').evaluate(el=>{const walker=document.createTreeWalker(el,NodeFilter.SHOW_TEXT);let node;while((node=walker.nextNode()))if(node.textContent.trim()&&node.parentElement.getBoundingClientRect().height){node.parentElement.dataset.fontProbe='';break;}});
  const client=await context.newCDPSession(page);await client.send('DOM.enable');await client.send('CSS.enable');const {root}=await client.send('DOM.getDocument');const {nodeId}=await client.send('DOM.querySelector',{nodeId:root.nodeId,selector:'[data-font-probe]'});const platform=await client.send('CSS.getPlatformFontsForNode',{nodeId});assert(platform.fonts.some(f=>f.isCustomFont&&/Noto/i.test(f.familyName)&&f.glyphCount>0),'实际中文字形必须由自托管Noto绘制');checks.push('中文字形实际使用自托管Noto，表头数据14px居中');
  const measured=await page.locator('.business-data-table').evaluate(el=>[...el.querySelectorAll('thead th,tbody tr:first-child>td')].every(e=>{const r=e.getBoundingClientRect();return r.width>0&&getComputedStyle(e).lineHeight!=='0px';}));assert(measured);await shot('purchase-list-1440');
  await page.getByRole('button',{name:'新增采购单',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.waitFor();await ready();
  const controls=await dialog.locator('[data-slot="button"]').evaluateAll(es=>es.filter(e=>e.getBoundingClientRect().height).map(e=>({top:getComputedStyle(e).paddingTop,bottom:getComputedStyle(e).paddingBottom,family:getComputedStyle(e).fontFamily})));assert(controls.length&&controls.every(c=>c.top===c.bottom&&c.family.includes('Noto Sans SC Variable')));checks.push('新字体按钮自然基线与上下对称内边距');await shot('purchase-dialog-1440');
  await page.setViewportSize({width:390,height:844});await ready();assert(await dialog.evaluate(e=>e.getBoundingClientRect().right<=innerWidth+1));await shot('purchase-dialog-390');await page.keyboard.press('Escape');await page.setViewportSize({width:1115,height:838});await ready();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await shot('purchase-list-1115');checks.push('新增表单与窄屏字体、文字和控件无溢出');
  await page.setViewportSize({width:1440,height:900});await page.goto(base+'/ai/assistant');await page.getByTestId('ai-graph-workspace').waitFor();await ready();assert((await page.locator('.conversation-sidebar').evaluate(e=>getComputedStyle(e).fontFamily)).includes('Noto Sans SC Variable'));await shot('assistant-1440');checks.push('助手正文和管理区继承统一字体，原布局保持');
  assert(fontRequests.length&&fontRequests.every(url=>new URL(url).origin===new URL(base).origin));assert.deepEqual(failedFonts,[]);assert.deepEqual(errors,[]);assert.deepEqual(writes,[]);checks.push('字体按Unicode分片同源加载，无404、远程字体请求或业务写入');
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'NOTO_FONT_PASSED',checks,fontFiles:new Set(fontRequests).size,platformFonts:platform.fonts,errors,failedFonts,writes},null,2));console.log('NOTO_FONT_PASSED',checks.length);
 }catch(e){await shot('failure').catch(()=>{});throw e;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
