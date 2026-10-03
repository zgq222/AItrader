const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {test}=require('node:test');
const html=fs.readFileSync('frontend/index.html','utf8');
const code=html.slice(html.indexOf('function renderStockValuation('),html.indexOf('async function fetchJSON('));

function sandbox(){
  const target={};
  const context=vm.createContext({state:{selected:{code:'600519',name:'贵州茅台'}},$:()=>target,escapeHtml:String});
  vm.runInContext(code,context);
  return {context,target};
}

test('valuation header uses yuan to billion conversion and preserves unknown and negative PE',()=>{
  const {context,target}=sandbox();
  context.quote={pe:19.32,market_cap:1573378000000,quote_time:'2026-09-30 16:14:58'};
  vm.runInContext('renderStockValuation(quote)',context);
  assert.match(target.innerHTML,/市盈率 <b>19\.32倍/);
  assert.match(target.innerHTML,/总市值 <b>15733\.78亿元/);
  assert.match(target.innerHTML,/2026-09-30/);
  assert.match(target.title,/最新行情.*历史K线/);
  context.quote={pe:null,market_cap:null};
  vm.runInContext('renderStockValuation(quote)',context);
  assert.match(target.innerHTML,/市盈率 <b>--/);
  assert.match(target.innerHTML,/总市值 <b>--/);
  context.quote.pe=-8.5;
  vm.runInContext('renderStockValuation(quote)',context);
  assert.match(target.innerHTML,/-8\.50倍/);
});

test('valuation responses cannot carry old stock values into a newly selected stock',async()=>{
  const {context,target}=sandbox();
  let resolve;
  context.fetchJSON=()=>new Promise(done=>{resolve=done;});
  const old=vm.runInContext('loadStockValuation(state.selected)',context);
  context.state.selected={code:'000001',name:'平安银行'};
  context.fetchJSON=async()=>({pe:5.8,market_cap:200000000000,quote_time:'2026-09-30 15:00:00'});
  await vm.runInContext('loadStockValuation(state.selected)',context);
  const current=target.innerHTML;
  resolve({pe:19.32,market_cap:1573378000000});await old;
  assert.equal(target.innerHTML,current);
  assert.match(current,/平安银行/);
  assert.match(current,/5\.80倍/);
  context.fetchJSON=async()=>{throw new Error('offline');};
  await vm.runInContext('loadStockValuation(state.selected)',context);
  assert.match(target.innerHTML,/市盈率 <b>--/);
  assert.match(target.innerHTML,/offline/);
});
