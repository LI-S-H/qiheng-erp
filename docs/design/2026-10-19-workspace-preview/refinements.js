/* 预览表现层：只增强现有DOM，不改变数据、请求、业务按钮和流转。 */
(() => {
  const stage=document.getElementById('view-stage');
  const reduced=()=>matchMedia('(prefers-reduced-motion:reduce)').matches;
  const svgNS='http://www.w3.org/2000/svg';
  const svgNode=(name,attrs={})=>{const node=document.createElementNS(svgNS,name);Object.entries(attrs).forEach(([key,value])=>node.setAttribute(key,String(value)));return node;};
  let revealObserver;
  const messageScrollers=new WeakSet();
  const observedCharts=new Set();
  const chartResize=new ResizeObserver(entries=>entries.forEach(entry=>{const svg=entry.target.querySelector('.trend-svg');if(svg?.dataset.polished)fitChart(svg);}));

  function fitChart(svg){
    const box=svg.parentElement,sx=box.clientWidth/720,sy=box.clientHeight/214;
    svg.setAttribute('viewBox',`0 0 ${box.clientWidth} ${box.clientHeight}`);
    svg.querySelectorAll('path.trend-line,path.trend-area').forEach(path=>{
      if(!path.dataset.originalD)path.dataset.originalD=path.getAttribute('d');
      let coordinate=0;path.setAttribute('d',path.dataset.originalD.replace(/-?\d*\.?\d+(?:e[+-]?\d+)?/gi,value=>Number(value)*(coordinate++%2?sy:sx)));
      if(path.classList.contains('trend-line'))path.style.setProperty('--line-length',path.getTotalLength());
    });
    svg.querySelectorAll('text,line:not(.chart-guide),circle:not(.chart-inspect-dot)').forEach(node=>{
      for(const name of ['x','x1','x2','cx','y','y1','y2','cy']){
        if(!node.hasAttribute(name))continue;
        const base=`data-original-${name}`;if(!node.hasAttribute(base))node.setAttribute(base,node.getAttribute(name));
        node.setAttribute(name,Number(node.getAttribute(base))*(name.startsWith('x')||name==='cx'?sx:sy));
      }
    });
    const guide=svg.querySelector('.chart-guide');guide?.setAttribute('y1',9*sy);guide?.setAttribute('y2',185*sy);
  }

  function polishChart(svg){
    if(svg.dataset.polished)return;
    svg.dataset.polished='true';
    const lines=[...svg.querySelectorAll('polyline')];
    const defs=svgNode('defs');const gradient=svgNode('linearGradient',{id:'sales-volume-fill',x1:0,y1:0,x2:0,y2:1});
    gradient.append(svgNode('stop',{offset:'0%','stop-color':'#3975df','stop-opacity':'.14'}),svgNode('stop',{offset:'100%','stop-color':'#3975df','stop-opacity':'.012'}));defs.append(gradient);svg.prepend(defs);
    lines.forEach((line,index)=>{
      const points=line.getAttribute('points').trim().split(/\s+/).map(point=>point.split(',').map(Number));
      let d=`M ${points[0][0]} ${points[0][1]}`;
      for(let i=1;i<points.length;i++){const a=points[i-1],b=points[i],mid=(a[0]+b[0])/2;d+=` C ${mid} ${a[1]},${mid} ${b[1]},${b[0]} ${b[1]}`;}
      if(index===0){const area=svgNode('path',{d:`${d} L ${points.at(-1)[0]} 141 L ${points[0][0]} 141 Z`,fill:'url(#sales-volume-fill)',class:'trend-area'});line.before(area);}
      const path=svgNode('path',{d,fill:'none',stroke:line.getAttribute('stroke'),'stroke-width':2.2,'stroke-linecap':'round','stroke-linejoin':'round',class:'trend-line'});
      path.style.animationDelay=`${index*65}ms`;line.replaceWith(path);
    });
    svg.append(svgNode('line',{x1:0,x2:0,y1:9,y2:185,class:'chart-guide'}));
    lines.forEach((line,i)=>svg.append(svgNode('circle',{r:3.5,fill:line.getAttribute('stroke'),stroke:'#fff','stroke-width':2,class:'chart-inspect-dot','data-series':i})));
    fitChart(svg);chartResize.observe(svg.parentElement);observedCharts.add(svg.parentElement);
  }

  function polishDashboard(dashboard){
    if(!dashboard.dataset.polished){
      dashboard.dataset.polished='true';
      dashboard.querySelectorAll('.metric').forEach((card,index)=>{
        const symbol=document.createElement('span');symbol.className='metric-icon';symbol.innerHTML=icon(['TrendingUp','ChartNoAxesCombined','ClipboardList','Boxes'][index]);card.append(symbol);
        const rate=card.querySelector('.metric-meta>span');rate.insertAdjacentHTML('afterbegin',icon(index===2?'ArrowDownRight':'ArrowUpRight'));
      });
      const health=dashboard.querySelector('.health-key'),counts=[...health.querySelectorAll('b')].map(n=>Number(n.textContent)),total=counts.reduce((a,b)=>a+b,0),length=2*Math.PI*45;
      const ring=document.createElement('div');ring.className='stock-ring';ring.setAttribute('role','img');ring.setAttribute('aria-label',`库存分布：正常${counts[0]}、低库存${counts[1]}、无可用${counts[2]}、零库存${counts[3]}`);
      const chart=svgNode('svg',{viewBox:'0 0 110 110','aria-hidden':'true'});chart.append(svgNode('circle',{cx:55,cy:55,r:45,stroke:'#f1f4f8'}));
      const healthColors=['#49a995','#dfa94f','#d1768c','#d9e0e9'];
      health.querySelectorAll('.dot').forEach((dot,i)=>dot.style.color=healthColors[i]);
      let offset=0;counts.forEach((count,i)=>{if(!count)return;const arc=count/total*length,segment=svgNode('circle',{cx:55,cy:55,r:45,stroke:healthColors[i],'stroke-dasharray':`${Math.max(0,arc-3)} ${length}`,'stroke-dashoffset':-offset,class:'ring-segment'});segment.style.setProperty('--arc',`${Math.max(0,arc-3)} ${length}`);chart.append(segment);offset+=arc;});
      ring.append(chart);ring.insertAdjacentHTML('beforeend',`<div class="stock-ring-value"><strong>${total}</strong><small>库存项</small></div>`);
      const summary=document.createElement('div');summary.className='health-summary';health.before(summary);summary.append(ring,health);
      dashboard.querySelectorAll('.stage-track').forEach(track=>{const items=[...track.querySelectorAll('.stage-item')],sum=items.reduce((n,item)=>n+Number(item.querySelector('b').textContent),0);items.forEach(item=>item.style.setProperty('--stage-share',`${Number(item.querySelector('b').textContent)/sum*100}%`));});
      const productRows=dashboard.querySelectorAll('.dash-lower .panel:first-child .ranking-row');productRows.forEach((row,i)=>{const meter=document.createElement('span');meter.className='sales-meter';meter.setAttribute('aria-hidden','true');meter.style.setProperty('--share',`${products[i][2]/products[0][2]*100}%`);row.querySelector('.ranking-name').append(meter);});
      dashboard.querySelectorAll('.supplier-score>span').forEach(score=>score.style.setProperty('--score',`${parseFloat(score.textContent)}%`));
      revealObserver?.disconnect();
      revealObserver=new IntersectionObserver(entries=>entries.forEach(entry=>{if(entry.isIntersecting){entry.target.classList.remove('pending-reveal');entry.target.classList.add('polished-reveal');revealObserver.unobserve(entry.target);}}),{root:dashboard,threshold:.04});
      dashboard.querySelectorAll('.metric,.panel').forEach((card,index)=>{if(reduced())return;card.style.setProperty('--reveal-delay',`${Math.min(index,3)*45}ms`);card.classList.add('pending-reveal');revealObserver.observe(card);});
    }
    dashboard.querySelectorAll('.trend-svg').forEach(polishChart);
  }

  function syncMessageNav(panel){
    const nav=panel.querySelector('.message-nav'),messages=[...panel.querySelectorAll('.user-message')];
    const signature=messages.map(message=>`${message.id}:${message.querySelector('.message-body')?.textContent}`).join('|');
    if(nav.dataset.messages===signature)return;
    nav.dataset.messages=signature;nav.setAttribute('aria-label','用户消息跳转');
    nav.innerHTML=messages.map((message,i)=>`<button data-action="message-anchor" data-anchor="${esc(message.id)}" aria-label="跳到第${i+1}轮用户消息" ${i===messages.length-1?'aria-current="true"':''}><span class="anchor-preview"><small>第 ${i+1} 轮 · 你的消息</small><span>${esc(message.querySelector('.message-body')?.textContent||'')}</span></span></button>`).join('');
  }

  function polishAssistant(panel){
    const create=panel.querySelector('.conv-create');
    if(!create.dataset.polished){create.dataset.polished='true';create.innerHTML=`<span class="create-symbol">${icon('SquarePen')}</span><span>新建会话</span>${icon('ArrowUpRight')}`;}
    syncMessageNav(panel);
    const scroller=panel.querySelector('.conversation-scroll');
    if(messageScrollers.has(scroller))return;messageScrollers.add(scroller);
    scroller.addEventListener('scroll',()=>{
      const messages=[...scroller.querySelectorAll('.user-message')],top=scroller.getBoundingClientRect().top+scroller.clientHeight*.28;
      const current=messages.filter(message=>message.getBoundingClientRect().top<=top).at(-1)||messages[0];
      panel.querySelectorAll('.message-nav button').forEach(button=>button.setAttribute('aria-current',String(button.dataset.anchor===current?.id)));
    },{passive:true});
  }

  function polishView(){
    for(const chart of observedCharts){if(!chart.isConnected){chartResize.unobserve(chart);observedCharts.delete(chart);}}
    const dashboard=stage.querySelector('.dashboard'),assistant=stage.querySelector('.ai-workspace');
    if(dashboard)polishDashboard(dashboard);else revealObserver?.disconnect();
    if(assistant)polishAssistant(assistant);
  }
  const observer=new MutationObserver(polishView);observer.observe(stage,{childList:true,subtree:true});polishView();

  function inspectChart(chart){
    const guide=chart.querySelector('.chart-guide');if(!guide)return;
    const data=chartData(),index=state.chartIndex??state.days-1,x=35+index*(720-35-9)/(state.days-1),y=value=>9+(450000-value)/600000*(214-9-29);
    guide.setAttribute('x1',x/720*chart.clientWidth);guide.setAttribute('x2',x/720*chart.clientWidth);
    chart.querySelectorAll('.chart-inspect-dot').forEach((dot,i)=>{dot.setAttribute('cx',x/720*chart.clientWidth);dot.setAttribute('cy',y(data[['sales','purchase','profit'][i]][index])/214*chart.clientHeight);});
    const tooltip=chart.querySelector('.chart-tooltip'),px=x/720*chart.clientWidth;tooltip.style.left=`${Math.min(chart.clientWidth-tooltip.offsetWidth-5,Math.max(5,px+14))}px`;
  }
  document.addEventListener('pointermove',event=>{const chart=event.target.closest('.chart-wrap');if(!chart)return;const rect=chart.getBoundingClientRect();state.chartIndex=Math.max(0,Math.min(state.days-1,Math.round((event.clientX-rect.left)/rect.width*(state.days-1))));inspectChart(chart);});
  document.addEventListener('keydown',event=>{if(event.target.matches('.chart-wrap')&&['ArrowLeft','ArrowRight'].includes(event.key))inspectChart(event.target);});
})();
