const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {test}=require('node:test');
const html=fs.readFileSync('frontend/index.html','utf8');
const code=html.slice(html.indexOf('function renderIndustryViewControls()'),html.indexOf('function renderWorkbenchIndustryIndicators('));
const normalization=html.slice(html.indexOf('function industryComparisonData('),html.indexOf('function renderIndustryComparisonControls('));
const format=html.slice(html.indexOf('function formatGridTooltip(params)'),html.indexOf('function bindGridTooltip('));
const rows=[{datetime:'2026-09-30 09:30:00',open:100,high:101,low:99,close:100,volume:0,pct_change:0,dif:0,dea:0,macd:0},
  {datetime:'2026-09-30 09:31:00',open:100,high:111,low:98,close:110,volume:10,pct_change:10,dif:1,dea:.5,macd:1}];
const fixture={name:'Board',date:'2026-09-30',interval:1,total:2,first_datetime:rows[0].datetime,latest_datetime:rows[1].datetime,
  history_total:2,available_dates:['2026-09-29','2026-09-30'],source:'THS',data:rows,
  benchmark:{code:'000001',name:'Market',data:rows.map(row=>({...row,open:row.open*10,high:row.high*10,low:row.low*10,close:row.close*10,pct_change:row.pct_change/2}))}};

function sandbox(){
  const elements={}, events={}, actions=[];
  const chart={option:null,setOption(option,replace){
    if(replace)this.option=option;
    else for(const patch of option.series||[]){const target=this.option.series.find(s=>patch.id?s.id===patch.id:s.name===patch.name);Object.assign(target,patch);}
  },getOption(){return this.option;},dispatchAction:action=>actions.push(action),resize(){},off(name){delete events[name];},on(name,callback){events[name]=callback;}};
  const context=vm.createContext({state:{selectedIndustry:{name:'Board',combined:true},industryView:'minute',industryMinuteInterval:1},URLSearchParams,
    industryComparisonSettings:{code:'000001'},$:(id)=>elements[id]??=( {style:{},insertAdjacentHTML(position,text){this.innerHTML=text+(this.innerHTML||'');}}),
    document:{querySelectorAll:()=>[]},echarts:{init:()=>chart},escapeHtml:String,escapeAttr:String,
    percentText:value=>value==null?'--':`${value>=0?'+':''}${Number(value).toFixed(2)}%`,formatNum:value=>value==null?'--':Number(value).toFixed(2),formatVol:String,
    latestBarsStart:()=>0,industryMetricNumber:value=>value==null?null:Number(value),
    renderIndustryComparisonControls(){},renderWorkbenchIndustrySummary(){},ensureWorkbenchIndustrySummaries(){},bindGridTooltip(){},
    fetchJSON:async url=>{context.url=url;return context.response?context.response(url):fixture;}});
  vm.runInContext(format+normalization+code,context);
  return {context,elements,chart,events,actions};
}

test('combined minutes keep two OHLC series, scoped percentage hover and raw indicator units',()=>{
  const {context}=sandbox();context.fixture=fixture;
  const result=vm.runInContext('buildIndustryMinuteOption(fixture,state.selectedIndustry)',context);
  assert.equal(result.option.series[0].type,'candlestick');assert.equal(result.option.series[1].type,'candlestick');
  assert.equal(result.option.series[0].data[0][1],0);assert.equal(result.option.series[1].data[0][1],0);
  assert.equal(result.option.tooltip.formatter([{seriesIndex:0,dataIndex:1}]),'2026-09-30 09:31:00<br>板块涨幅 +10.00%<br>大盘涨幅 +5.00%');
  assert.equal(result.option.series[2].data[0].value,0);
  assert.deepEqual(Array.from(result.option.series.slice(3),s=>s.xAxisIndex),[2,2,2]);
});

test('missing market minutes stay empty without forward fill or a fabricated candle',()=>{
  const {context}=sandbox();context.fixture={...fixture,benchmark:{name:'Market',data:[fixture.benchmark.data[0]]}};
  const result=vm.runInContext('buildIndustryMinuteOption(fixture,state.selectedIndustry)',context);
  assert.deepEqual(Array.from(result.option.series[1].data[1]),[null,null,null,null]);
  assert.match(result.option.tooltip.formatter([{seriesIndex:0,dataIndex:1}]),/大盘涨幅 --$/);
  context.fixture={...fixture,benchmark:{name:'Market',data:[]}};
  const fallback=vm.runInContext('buildIndustryMinuteOption(fixture,state.selectedIndustry)',context);
  assert.equal(fallback.comparison,null);assert.equal(fallback.option.series[0].data[1][1],110);
});

test('minute load offers actual trading dates, hides daily breadth, and rebases two Ks after zoom',async()=>{
  const {context,elements,chart,events}=sandbox();
  await vm.runInContext('loadWorkbenchIndustryMinuteChart(state.selectedIndustry)',context);
  assert.equal(new URL(context.url,'http://localhost').searchParams.get('interval'),'1');
  assert.equal(elements.workbenchIndustryMinuteDate.value,'2026-09-30');
  assert.match(elements.workbenchIndustryMinuteDate.innerHTML,/2026-09-29/);
  assert.equal(elements.workbenchIndustryIndicators.style.display,'none');
  assert.equal(elements.workbenchIndustryChart.style.display,'none');
  chart.option.dataZoom[0]={startValue:1,endValue:1,start:100,end:100};events.datazoom();
  assert.equal(chart.option.series[0].data[1][1],0);assert.equal(chart.option.series[1].data[1][1],0);
});

test('late minute responses cannot replace another sector or the daily view',async()=>{
  const {context,elements,chart}=sandbox();let resolve;
  context.response=()=>new Promise(done=>resolve=done);
  const pending=vm.runInContext('loadWorkbenchIndustryMinuteChart(state.selectedIndustry)',context);
  context.state.industryView='day';elements.workbenchIndustryStatus.textContent='Daily is active';
  resolve(fixture);await pending;
  assert.equal(chart.option,null);assert.equal(elements.workbenchIndustryStatus.textContent,'Daily is active');
});

test('minute failure keeps day controls and a retry without false zero data',async()=>{
  const {context,elements,chart}=sandbox();
  context.response=async()=>{throw new Error('Source unavailable');};
  await vm.runInContext('loadWorkbenchIndustryMinuteChart(state.selectedIndustry)',context);
  assert.match(elements.workbenchIndustryStatus.textContent,/Source unavailable/);
  assert.equal(elements.workbenchIndustryRetry.style.display,'');assert.equal(chart.option,null);
});

test('concept minute view requests concept identity instead of an industry with the same name',async()=>{
  const {context}=sandbox();context.state.selectedIndustry.boardType='concept';
  await vm.runInContext('loadWorkbenchIndustryMinuteChart(state.selectedIndustry)',context);
  assert.equal(new URL(context.url,'http://localhost').searchParams.get('type'),'concept');
});
