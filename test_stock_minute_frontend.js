const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html=fs.readFileSync('frontend/index.html','utf8');
for(const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g))new vm.Script(match[1]);
const minuteCode=html.slice(html.indexOf('async function renderStockMinute('),html.indexOf('function switchStockView('));
const tooltipCode=html.slice(html.indexOf('function stockMinuteKlineTooltip('),html.indexOf('async function showMinuteKline('));
const zoomCode=html.slice(html.indexOf('function getZoomRange('),html.indexOf('function applyRange('));
function sandbox(rows){
  const main={option:null,setOption(option){this.option=option;},getOption(){return this.option;},resize(){},off(){},on(){},dispatchAction(action){for(const zoom of this.option.dataZoom)Object.assign(zoom,{start:action.start,end:action.end});detail.option.dataZoom.forEach(zoom=>Object.assign(zoom,{start:action.start,end:action.end}));}};
  const detail={...main,dispatchAction(){}};
  const elements={stockMinuteNote:{},statusText:{}};
  const urls=[];
  const context=vm.createContext({state:{selected:{code:'600001',name:'test'},stockView:'minute',stockMinuteCode:'600001',stockMinuteChart:main,stockMinuteDetailChart:detail},
    $:id=>elements[id],document:{querySelectorAll:()=>[]},formatVol:String,formatNum:value=>value==null?'--':Number(value).toFixed(3),escapeHtml:String,echarts:{connect(){}},bindGridTooltip(){},
    fetchJSON:async url=>{urls.push(url);return {data:rows};},minuteChanLayers:async()=>({series:[],legend:[],available:false}),
    fiveMinuteMainForceSignals:()=>[],groupFiveMinuteMainForceSignals:()=>[]});
  vm.runInContext(tooltipCode+'\n'+minuteCode+'\n'+zoomCode,context);
  return {context,main,detail,urls,elements};
}
const rows=Array.from({length:6000},(_,index)=>({datetime:new Date(Date.UTC(2020,0,1+Math.floor(index/8))).toISOString().slice(0,10)+' '+String(10+Math.floor(index%8/4)).padStart(2,'0')+':'+String(index%4*15).padStart(2,'0')+':00',open:10,close:11,high:12,low:9,volume:100,amount:1000}));
for(const interval of [5,10,15,30]){
  test(`${interval}-minute loads all local history and starts at eight trading days`,async()=>{
    const {context,main,detail,urls,elements}=sandbox(rows);
    const focusDate=rows.at(-1).datetime.slice(0,10);
    await vm.runInContext(`renderStockMinute('${focusDate}',${interval})`,context);
    assert.equal(new URL(urls[0],'http://localhost').searchParams.get('limit'),'200000');
    assert.ok(!urls[0].includes('focus_date'));
    assert.equal(main.option.series[0].data.length,6000);
    const {start,end}=main.option.dataZoom[0];
    const first=Math.round(start/100*(rows.length-1)),last=Math.round(end/100*(rows.length-1));
    assert.equal(new Set(rows.slice(first,last+1).map(row=>row.datetime.slice(0,10))).size,8);
    assert.equal(detail.option.dataZoom[0].start,start);
    assert.equal(detail.option.dataZoom[0].end,end);
    assert.match(elements.stockMinuteNote.textContent,/750 个交易日/);
    vm.runInContext('zoomChart(.15)',context);
    assert.ok(main.option.dataZoom[0].end-main.option.dataZoom[0].start<end-start);
    assert.equal(main.option.dataZoom[0].start,detail.option.dataZoom[0].start);
    vm.runInContext('resetChartRange()',context);
    assert.equal(main.option.dataZoom[0].start,start);
    vm.runInContext('panChart(-.15)',context);
    assert.ok(main.option.dataZoom[0].end<end);
  });
}
test('minute zoom can narrow below one percent and stays bounded at the last bar',async()=>{
  const {context,main}=sandbox(rows);
  await vm.runInContext('renderStockMinute("",30)',context);
  for(let index=0;index<25;index++)vm.runInContext('zoomChart(.3)',context);
  const {start,end}=main.option.dataZoom[0];
  assert.ok(end-start<1);
  assert.ok(end-start>=5/(rows.length-1)*100-1e-9);
  vm.runInContext('setZoomRange(100,100)',context);
  assert.equal(main.option.dataZoom[0].end,100);
  assert.ok(main.option.dataZoom[0].end-main.option.dataZoom[0].start>=5/(rows.length-1)*100-1e-9);
});
test('historical focus retains surrounding history with the same eight-day default',async()=>{
  const {context,main}=sandbox(rows);
  const focusDate=rows[4000].datetime.slice(0,10);
  await vm.runInContext(`renderStockMinute('${focusDate}',15)`,context);
  const {start,end}=main.option.dataZoom[0];
  assert.equal(Math.round(end/100*(rows.length-1)),4007);
  assert.equal(Math.round(start/100*(rows.length-1)),3944);
  assert.equal(context.state.stockMinuteRows.length,6000);
});

test('minute price tooltip displays the daily return, previous close and OHLC without subpanel values',async()=>{
  const sample=[{datetime:'2026-09-29 15:00:00',open:10,close:10,high:10,low:10,volume:500},
    {datetime:'2026-09-30 09:31:00',open:10,close:10.5,high:10.6,low:10,volume:100,pre_close:10,pct_change:5},
    {datetime:'2026-09-30 09:32:00',open:10.5,close:10,high:10.5,low:10,volume:100,pre_close:10,pct_change:0}];
  const {context,main}=sandbox(sample);
  await vm.runInContext('renderStockMinute("",1)',context);
  const tip=main.option.tooltip.formatter([{dataIndex:1,seriesIndex:0}]);
  assert.match(tip,/当日涨幅 \+5\.00%/);
  assert.match(tip,/昨收 10\.000/);
  assert.doesNotMatch(tip,/成交量|DIF|DEA/);
  assert.match(main.option.tooltip.formatter([{dataIndex:2,seriesIndex:0}]),/当日涨幅 \+0\.00%/);
  assert.match(main.option.tooltip.formatter([{dataIndex:0,seriesIndex:0}]),/当日涨幅 --/);
  assert.match(html,/document\.querySelector\('#stockChartPanels \.stock-view-tabs'\)/);
});
