const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {test}=require('node:test');
const html=fs.readFileSync('frontend/index.html','utf8');
const gridCode=html.slice(html.indexOf('const gridTooltipContexts ='),html.indexOf('function buildOption(data)'));
const dailyCode=html.slice(html.indexOf('function stockDailyKlineTooltip('),html.indexOf('function renderChart('));
function fixture(){
  const context=vm.createContext({escapeHtml:String,formatNum:value=>value==null?'--':Number(value).toFixed(2),formatVol:String});
  vm.runInContext(gridCode+'\n'+dailyCode,context);
  const rows=[{date:'2026-09-29',open:10,close:10,low:9,high:11},
    {date:'2026-09-30',open:10,close:11,low:10,high:12}];
  context.rows=rows;
  context.params=[{dataIndex:1,seriesType:'candlestick',seriesName:'K线',data:[10,11,10,12]},
    {dataIndex:1,seriesName:'MA5',data:10.5}];
  return {context,rows};
}
test('daily price tooltip retains selected moving averages and adds return relative to the prior daily close',()=>{
  const {context}=fixture();
  const text=vm.runInContext('stockDailyKlineTooltip(params,rows)',context);
  assert.match(text,/2026-09-30<br>当日涨幅 \+10\.00%/);
  assert.match(text,/开 10\.00.*收 11\.00/);
  assert.match(text,/MA5：10\.5/);
  assert.doesNotMatch(text,/成交量|DIF/);
  assert.match(vm.runInContext("stockDailyKlineTooltip(params,rows,'week')",context),/区间涨幅 \+10\.00%/);
});
test('daily returns preserve zero, negative values and unknown baseline without interpreting null as zero',()=>{
  const {context,rows}=fixture();
  rows[1].close=10;
  assert.match(vm.runInContext('stockDailyKlineTooltip(params,rows)',context),/当日涨幅 \+0\.00%/);
  rows[1].close=9;
  assert.match(vm.runInContext('stockDailyKlineTooltip(params,rows)',context),/当日涨幅 -10\.00%/);
  rows[1].pct_change=0;
  assert.match(vm.runInContext('stockDailyKlineTooltip(params,rows)',context),/当日涨幅 \+0\.00%/);
  context.params[0].dataIndex=0;
  assert.match(vm.runInContext('stockDailyKlineTooltip(params,rows)',context),/当日涨幅 --/);
  rows[0].Pct_Change=.015;
  assert.match(vm.runInContext('stockDailyKlineTooltip(params,rows)',context),/当日涨幅 \+1\.50%/);
});
