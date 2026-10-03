const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('frontend/index.html', 'utf8');
const code = html.slice(html.indexOf('const gridTooltipContexts ='), html.indexOf('function buildOption(data)'));

function fixture() {
  const context = vm.createContext({escapeHtml:String,
    formatNum:value=>value==null?'--':Number(value).toFixed(2), formatVol:String});
  vm.runInContext(code, context);
  const chart = (series, grids=[0,1,2], formatter=null) => {
    const listeners={}, actions=[], events={};
    const option={tooltip:[{formatter}],xAxis:grids.map(gridIndex=>({gridIndex,data:['2026-09-29','2026-09-30']})),series,
      axisPointer:{link:[{xAxisIndex:'all'}]},dataZoom:[{start:60,end:100}]};
    return {option,listeners,actions,events,disposed:false,
      isDisposed(){return this.disposed;},on(name,callback){events[name]=callback;},getOption(){return option;},getWidth(){return 200;},getHeight(){return 300;},
      getDom(){return {getBoundingClientRect:()=>({left:10,top:20,width:100,height:150}),
        addEventListener:(name,callback,options)=>{listeners[name]={callback,options};}};},
      convertToPixel(){return 120;},
      getModel(){return {getComponent:()=>({coordinateSystem:{getRect:()=>({y:10,height:80})}}),eachComponent:(type,callback)=>{
        assert.equal(type,'grid');
        [0,1,2].forEach(componentIndex=>callback({componentIndex,
          coordinateSystem:{containPoint:([x,y])=>x>=20&&x<=180&&y>=componentIndex*100+10&&y<=componentIndex*100+90}}));
      }};},setOption:update=>Object.assign(option.tooltip[0],update.tooltip),dispatchAction:action=>actions.push(action),
      move(grid){listeners.mousemove.callback({clientX:60,clientY:20+(grid*100+50)/2});},
      tooltip(params){return option.tooltip[0].formatter(params);}};
  };
  return {context,chart,bind:instance=>{context.chart=instance;vm.runInContext('bindGridTooltip(chart)',context);}};
}
const params = [
  {seriesIndex:0,seriesName:'K线',seriesType:'candlestick',data:[10,12,9,13],axisValue:'2026-09-30'},
  {seriesIndex:1,seriesName:'成交量',data:200,axisValue:'2026-09-30'},
  {seriesIndex:2,seriesName:'DIF',data:0,axisValue:'2026-09-30'},
  {seriesIndex:3,seriesName:'DEA',data:null,axisValue:'2026-09-30'},
  {seriesIndex:4,seriesName:'MACD柱',data:{value:-2},axisValue:'2026-09-30'},
  {seriesIndex:5,seriesName:'信号',data:1,axisValue:'2026-09-30'},
];
const series = [{},{xAxisIndex:1},{xAxisIndex:2},{xAxisIndex:2},{xAxisIndex:2},{xAxisIndex:2,tooltip:{show:false}}];

test('hover filters a linked column to all visible series in the hovered grid',()=>{
  const {chart,bind}=fixture(), instance=chart(series);bind(instance);
  assert.equal(instance.listeners.mousemove.options,true);
  assert.equal(instance.listeners.pointermove.options,true);
  instance.move(1);
  assert.match(instance.tooltip(params),/2026-09-30.*成交量：200/);
  assert.doesNotMatch(instance.tooltip(params),/K线|DIF|DEA|MACD/);
  instance.move(2);
  const text=instance.tooltip(params);
  assert.match(text,/DIF：0.*DEA：--.*MACD柱：-2/);
  assert.doesNotMatch(text,/K线|成交量|信号/);
  assert.equal(instance.option.dataZoom[0].start,60);
  assert.equal(instance.option.axisPointer.link[0].xAxisIndex,'all');
});

test('tooltip show/hide events cannot propagate across connected charts, including internal hide events',()=>{
  const {chart,bind}=fixture(), instance=chart(series);bind(instance);
  for(const type of ['showTip','hideTip']){
    const event={type,from:'tooltip-view'};
    instance.events[type](event);
    assert.equal(event.escapeConnect,true);
  }
  assert.equal(typeof instance.events.updateAxisPointer,'function');
  assert.equal(instance.events.datazoom,undefined);
  instance.listeners.pointermove.callback({clientX:60,clientY:145});
  assert.match(instance.tooltip(params),/MACD/);
});

test('series on separate axes sharing a grid remain together; OHLC values stay correct',()=>{
  const {chart,bind}=fixture(), instance=chart([{}, {xAxisIndex:1}], [0,0]);bind(instance);
  instance.move(0);
  const text=instance.tooltip(params.slice(0,2));
  assert.match(text,/开 10.00 · 收 12.00 · 低 9.00 · 高 13.00/);
  assert.match(text,/成交量：200/);
});

test('linked chart tooltips hide remotely without changing date cursor links',()=>{
  const {chart,bind}=fixture(), main=chart(series), detail=chart(series);
  main.group=detail.group='linked';bind(main);bind(detail);
  main.move(0);assert.match(main.tooltip(params),/K线/);
  detail.move(2);
  assert.equal(main.tooltip(params),'');
  assert.equal(main.actions.at(-1).type,'hideTip');
  assert.equal(main.actions.at(-1).escapeConnect,true);
  assert.match(detail.tooltip(params),/MACD/);
});

test('linked cursor alignment uses the trading date across charts with different series and grids',()=>{
  const {chart,bind}=fixture(), main=chart([{}]), detail=chart(series);
  main.group=detail.group='linked';bind(main);bind(detail);
  detail.move(2);
  const event={axesInfo:[{axisDim:'x',axisIndex:2,value:1}]};
  detail.events.updateAxisPointer(event);
  assert.equal(event.escapeConnect,true);
  assert.equal(main.actions.at(-1).type,'updateAxisPointer');
  assert.equal(main.actions.at(-1).axesInfo[0].value,'2026-09-30');
  assert.equal(main.actions.at(-1).axesInfo[0].axisIndex,0);
  assert.equal(main.actions.at(-1).escapeConnect,true);
  assert.equal(main.tooltip(params),'');
});

test('leaving the plot, grid gaps and touch input use the same scope',()=>{
  const {chart,bind}=fixture(), instance=chart(series);bind(instance);
  instance.move(1);instance.listeners.mouseleave.callback();assert.equal(instance.tooltip(params),'');
  instance.move(1);instance.listeners.mousemove.callback({clientX:60,clientY:70});
  assert.equal(instance.tooltip(params),'');
  instance.listeners.touchstart.callback({touches:[{clientX:60,clientY:145}]});
  assert.match(instance.tooltip(params),/MACD/);
});

test('custom formatting and header callbacks receive only the local grid, including after rebind',()=>{
  const {context,chart}=fixture(), instance=chart(series,[0,1,2],items=>items.map(item=>item.seriesName).join(','));
  context.chart=instance;context.contents=[];
  vm.runInContext('bindGridTooltip(chart, content=>contents.push(content)); bindGridTooltip(chart, content=>contents.push(content));',context);
  instance.move(2);assert.equal(instance.tooltip(params),'DIF,DEA,MACD柱');
  assert.equal(context.contents.at(-1),'DIF,DEA,MACD柱');
  instance.option.tooltip[0].formatter=items=>'updated:'+items[0].seriesName;
  vm.runInContext('bindGridTooltip(chart)',context);
  instance.move(1);assert.equal(instance.tooltip(params),'updated:成交量');
});

test('disposed charts are removed before further binding',()=>{
  const {context,chart,bind}=fixture(), old=chart(series);bind(old);old.disposed=true;
  bind(chart(series));assert.equal(vm.runInContext('gridTooltipContexts.size',context),1);
});
