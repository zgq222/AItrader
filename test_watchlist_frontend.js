const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { test } = require('node:test');

const html = fs.readFileSync('frontend/index.html', 'utf8');
for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) new vm.Script(match[1]);
const listCode = html.slice(html.indexOf('let watchlistPayload ='), html.indexOf('async function ensureHammerWorkbenchList'));
const addCode = html.slice(html.indexOf('async function addCurrentStockToWatchlist'), html.indexOf('function renderFinancialReports'));
const selectCode = html.slice(html.indexOf('function selectStock('), html.indexOf('async function fetchJSON('));

function sandbox() {
  const sidebar = { scrollTop: 77 };
  const target = {
    groups: [], details: [], content: '', closest: () => sidebar,
    querySelectorAll(selector) {
      if (selector === '.workbench-group') return this.groups;
      if (selector === '[data-industry-key]') return this.details;
      return [];
    },
    set innerHTML(value) {
      this.content = value;
      this.groups = [...value.matchAll(/data-workbench-group="([^"]+)"/g)].map(match => {
        const list = { scrollTop: 0 };
        return { list, querySelector: selector => selector === '.workbench-group-stocks'
          ? list : { dataset: { workbenchGroup: match[1] } } };
      });
      this.details = [...value.matchAll(/<details[^>]*data-industry-key="([^"]+)"([^>]*)>/g)].map(match => ({
        dataset: { industryKey: match[1] }, open: /\bopen\b/.test(match[2]), addEventListener() {},
      }));
    },
    get innerHTML() { return this.content; },
  };
  const elements = { hammerWorkbenchList: target, watchlistPickerModal: { style: { display: 'flex' } },
    statusText: {}, workbenchGroupStatus: {}, workbenchNote: {}, workbenchSaveNote: {},
    workbenchNoteStatus: {}, searchInput: {}, stockInfo: { style: {} }, stockChartPanels: { style: {} },
    workbenchIndustryPanel: { style: {}, closest: () => ({ scrollTop: 100 }) },
    workbenchIndustryTitle: {}, workbenchIndustrySource: {}, workbenchIndustryMeta: {}, workbenchIndustryStatus: {}, workbenchIndustrySummary: {style:{}}, workbenchIndustryIndicators: {style:{}},
    workbenchIndustryRetry: { style: {} }, workbenchIndustryChart: { style: {} },
    watchlistPickerGroups: { insertAdjacentHTML(position, text) { this.error = text; } } };
  const periodButtons = ['day', 'week', 'minute5'].map(period => ({ dataset: { period }, classList: { toggle() {} } }));
  const chart = { options: [], dispatchAction() {}, clear() {}, resize() {}, off() {}, on() {}, setOption(option) { this.options.push(option); } };
  const context = vm.createContext({ $, state: { selected: { code: '600001', name: 'Equity1' }, stocks: [] },
    document: { querySelectorAll: () => periodButtons }, escapeHtml: String, escapeAttr: String,
    signedClass: value => value >= 0 ? 'positive' : 'negative',
    percentText: value => value==null?'--':`${Number(value)>=0?'+':''}${Number(value).toFixed(2)}%`,
    formatNum: value => Number(value).toFixed(2), formatVol: String,
    latestBarsStart: length => Math.max(0, 100 - 60 / length * 100),
    hideLoading() {}, hideError() {}, bindGridTooltip() {},
    switchStockView(view) { context.state.stockView = view; },
    showWorkspace() { vm.runInContext('renderHammerWorkbenchList()', context); },
    async fetchJSON(url) {
      if(/\/api\/board\/(industry|concept)-flow-history\?/.test(url))return context.fetchHistory?context.fetchHistory(url):{data:[]};
      if(/\/api\/board\/(industry|concept)-strength-history\?/.test(url))return context.fetchStrength?context.fetchStrength(url):{data:[]};
      if(url.startsWith('/api/index/kline?'))return context.fetchIndex?context.fetchIndex(url):{data:[]};
      if(url==='/api/board/industry-summary')return context.fetchSummary?context.fetchSummary(url):{data:[]};
      return context.fetchKline(url);
    },
    loadKline() {}, echarts: { init: () => chart },
    async sendJSON(url, body) {
      context.request = { url, body };
      const payload = JSON.parse(vm.runInContext('JSON.stringify(watchlistPayload)', context));
      payload.groups.find(group => group.id === body.group_id).stocks.push({ ...context.state.selected });
      return payload;
    },
  });
  function $(id) { return elements[id]; }
  vm.runInContext(listCode + '\n' + addCode + '\n' + selectCode, context);
  const stocks = [
    { code: '600001', name: 'Equity1', industry: '板块A', return_rank: 1, return_30d_pct: 50 },
    { code: '600002', name: 'Equity2', industry: '板块B', return_rank: 2, return_30d_pct: 40 },
    { code: '600003', name: 'Equity3', industry: '板块A', return_rank: 3, return_30d_pct: 30 },
  ];
  context.fixture = { groups: [{ id: 'five_minute_selection', name: '5分钟选股', system: true, stocks },
    { id: 'holdings', name: '持仓股', stocks: [] }], notes: {} };
  vm.runInContext("watchlistPayload=fixture; activeWatchlistGroupId='five_minute_selection'; renderHammerWorkbenchList();", context);
  return { context, target, sidebar, elements, chart, periodButtons };
}

test('adding to holdings preserves the source group, sector expansion, scroll, and selected stock', async () => {
  const { context, target, sidebar, elements } = sandbox();
  target.groups[0].list.scrollTop = 210;
  target.details[1].open = false;
  await vm.runInContext("addCurrentStockToWatchlist('holdings')", context);
  assert.equal(vm.runInContext('activeWatchlistGroupId', context), 'five_minute_selection');
  assert.equal(target.groups[0].list.scrollTop, 210);
  assert.equal(sidebar.scrollTop, 77);
  assert.equal(target.details[0].open, true);
  assert.equal(target.details[1].open, false);
  assert.equal(context.state.selected.code, '600001');
  assert.match(target.content, /data-workbench-group="five_minute_selection" aria-expanded="true"/);
  assert.match(target.content, /data-workbench-group="holdings" aria-expanded="false"/);
  assert.equal(context.request.body.group_id, 'holdings');
  assert.equal(vm.runInContext("watchlistPayload.groups[1].stocks.length", context), 1);
  assert.equal(elements.watchlistPickerModal.style.display, 'none');
});

test('sector grouping preserves all stocks and their original global rankings', () => {
  const { context } = sandbox();
  const buckets = JSON.parse(vm.runInContext('JSON.stringify(selectionIndustryBuckets(fixture.groups[0]))', context));
  assert.deepEqual(buckets.map(item=>item.industry), ['板块A', '板块B']);
  assert.deepEqual(buckets[0].stocks.map(stock=>stock.return_rank), [1, 3]);
  assert.deepEqual(buckets[1].stocks.map(stock=>stock.return_rank), [2]);
  assert.equal(buckets.reduce((count, item)=>count+item.stocks.length, 0), 3);
});

test('five-minute stock rows show only the thirty-day gain without signal dates', () => {
  const { context, target, elements } = sandbox();
  context.fixture.groups[0].stocks[0].pullback_start_date = '2026-09-25';
  context.fixture.groups[0].stocks[0].pullback_end_date = '2026-09-29';
  context.fixture.groups[0].stocks[0].limit_up_date = '2026-09-22';
  vm.runInContext('renderHammerWorkbenchList()', context);
  const row = target.content.match(/<button[^>]*data-hammer-workbench-code="600001"[^>]*>[\s\S]*?<\/button>/)[0];
  assert.match(row, /30日涨幅 \+50\.00%/);
  assert.doesNotMatch(row, /2026-09|低点|最新收盘|连续三日/);
  assert.doesNotMatch(elements.workbenchGroupStatus.textContent, /2026-09/);
});

test('gain-group stock rows show one gain without rankings, prices, or dates', () => {
  const { context, target, elements } = sandbox();
  context.fixture.groups[0].id = 'main_board_30d_top150';
  Object.assign(context.fixture.groups[0].stocks[0], {
    sector_rank: 1, return_low_price: 8.25, return_low_date: '2026-09-01',
    end_close: 12.38, return_end: '2026-09-29',
  });
  vm.runInContext("activeWatchlistGroupId='main_board_30d_top150'; renderHammerWorkbenchList();", context);
  const row = target.content.match(/<button[^>]*data-hammer-workbench-code="600001"[^>]*>[\s\S]*?<\/button>/)[0];
  assert.match(row, /30日涨幅 \+50\.00%/);
  assert.equal([...row.matchAll(/30日涨幅/g)].length, 1);
  assert.doesNotMatch(row, /板块#|全市场#|低点|高点|最高|最新收盘|2026-09|8\.25|12\.38/);
  assert.doesNotMatch(target.content, /最高 50\.00%|低点 8\.25|最新收盘 12\.38/);
  assert.doesNotMatch(elements.workbenchGroupStatus.textContent, /低点|高点|2026-09/);
  assert.equal(vm.runInContext('mainBoard30dReturnSummary(fixture.groups[0].stocks[0])', context), '30日涨幅 +50.00%');
});

test('a failed add keeps the list and picker available with an error', async () => {
  const { context, target, elements } = sandbox();
  const before = target.content;
  context.sendJSON = async () => { throw new Error('保存失败'); };
  await vm.runInContext("addCurrentStockToWatchlist('holdings')", context);
  assert.equal(target.content, before);
  assert.equal(vm.runInContext('activeWatchlistGroupId', context), 'five_minute_selection');
  assert.equal(elements.watchlistPickerModal.style.display, 'flex');
  assert.match(elements.watchlistPickerGroups.error, /保存失败/);
});

const boardRows = [
  { date: '2026-09-28', open: 100, close: 105, low: 98, high: 107, volume: 200, amount: 1000 },
  { date: '2026-09-29', open: 105, close: 103, low: 102, high: 106, volume: 300, amount: 2000 },
];

test('each sector sublist starts with its board chart entry without changing stock counts', () => {
  const { context, target } = sandbox();
  for (const industry of ['板块A', '板块B']) {
    assert.ok(target.content.indexOf(`data-workbench-industry="${industry}"`) < target.content.indexOf(`· ${industry}</small>`));
  }
  assert.equal([...target.content.matchAll(/data-workbench-industry="/g)].length, 2);
  context.fixture.groups[0].id = 'main_board_30d_top150';
  vm.runInContext("activeWatchlistGroupId='main_board_30d_top150'; renderHammerWorkbenchList();", context);
  assert.equal([...target.content.matchAll(/data-workbench-industry="/g)].length, 4);
  for(const industry of ['板块A','板块B']) {
    const sector=target.content.split(`data-industry-key="main_board_30d_top150:${industry}"`)[1].split('</details>')[0];
    assert.match(sector,/<div class="workbench-industry-stocks"><button[^>]+data-workbench-industry=/);
  }
  assert.equal([...target.content.matchAll(/data-hammer-workbench-code="/g)].length, 3);
});

test('gain group starts with a board-index sublist before collapsed stock sectors', async () => {
  const { context, target, elements } = sandbox();
  context.fixture.groups[0].id = 'main_board_30d_top150';
  context.fixture.groups[0].stocks[0].sector_rank = 1;
  vm.runInContext("activeWatchlistGroupId='main_board_30d_top150'; renderHammerWorkbenchList();", context);
  const html = target.content;
  assert.ok(html.indexOf('data-industry-key="main_board_30d_top150:board-indices"')
    < html.indexOf('data-industry-key="main_board_30d_top150:板块A"'));
  assert.match(html, /<summary>板块指数 <span class="muted">2个 · 先看板块<\/span>/);
  assert.match(html, /data-industry-key="main_board_30d_top150:板块A" ><summary>/);
  assert.equal([...html.matchAll(/<b>板块[AB]<\/b><\/button>/g)].length, 4);
  context.fetchKline = async url => {
    assert.match(url, /type=industry&name=.*&period=day$/);
    return { data: boardRows, source: '同花顺板块K线' };
  };
  await vm.runInContext("selectWorkbenchIndustry('板块A','main_board_30d_top150:板块A')", context);
  assert.match(elements.workbenchIndustryTitle.textContent, /板块A/);
});

test('board chart displays matching daily candles and volumes, preserves list position, and returns to equities', async () => {
  const { context, target, sidebar, elements, chart, periodButtons } = sandbox();
  target.groups[0].list.scrollTop = 210;
  target.details[1].open = false;
  context.fetchKline = async url => {
    assert.match(url, /\/api\/board\/kline\?type=industry&name=.*&period=day$/);
    return { data: boardRows, latest_date: '2026-09-29', source: '同花顺板块K线' };
  };
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')", context);
  assert.equal(context.state.selected, null);
  assert.equal(context.state.selectedIndustry.name, '板块A');
  assert.equal(elements.stockChartPanels.style.display, 'none');
  assert.equal(elements.workbenchNote.disabled, true);
  assert.equal(target.groups[0].list.scrollTop, 210);
  assert.equal(sidebar.scrollTop, 77);
  assert.equal(target.details[1].open, false);
  assert.equal(periodButtons[1].disabled, true);
  const option = JSON.parse(JSON.stringify(chart.options[0]));
  assert.deepEqual(option.series[0].data, [[100, 105, 98, 107], [105, 103, 102, 106]]);
  assert.deepEqual(option.series[1].data.map(item => item.value), [200, 300]);
  assert.notEqual(option.series[1].data[0].itemStyle.color, option.series[1].data[1].itemStyle.color);
  assert.deepEqual(option.dataZoom[0].xAxisIndex, [0, 1, 2, 3, 4, 5]);
  assert.match(elements.workbenchIndustryMeta.innerHTML, /2026-09-29.*成交量 300/);
  vm.runInContext("selectStock({code:'600002',name:'Equity2'})", context);
  assert.equal(context.state.selectedIndustry, null);
  assert.equal(context.state.selected.code, '600002');
  assert.equal(elements.workbenchIndustryPanel.style.display, 'none');
  assert.equal(elements.stockChartPanels.style.display, '');
  assert.equal(periodButtons[1].disabled, false);
});

test('late board responses cannot replace another board or a selected equity', async () => {
  const { context, elements, chart } = sandbox();
  const pending = [];
  context.fetchKline = () => new Promise(resolve => pending.push(resolve));
  const a = vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')", context);
  const b = vm.runInContext("selectWorkbenchIndustry('板块B','five_minute_selection:板块B')", context);
  pending[1]({ data: boardRows, source: '板块B行情' });
  await b;
  pending[0]({ data: boardRows, source: '板块A行情' });
  await a;
  assert.match(elements.workbenchIndustryTitle.textContent, /板块B/);
  assert.match(elements.workbenchIndustrySource.textContent, /板块B行情/);
  assert.equal(chart.options.length, 2);
  const c = vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')", context);
  vm.runInContext("selectStock({code:'600002',name:'Equity2'})", context);
  pending[2]({ data: boardRows });
  await c;
  assert.equal(chart.options.length, 2);
  assert.equal(elements.workbenchIndustryPanel.style.display, 'none');
});

test('board failure offers retry while the source group and stocks remain available', async () => {
  const { context, target, elements } = sandbox();
  context.fetchKline = async () => { throw new Error('未找到同花顺板块K线'); };
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')", context);
  assert.match(elements.workbenchIndustryStatus.textContent, /未找到同花顺板块K线/);
  assert.equal(elements.workbenchIndustryRetry.style.display, '');
  assert.equal(elements.workbenchIndustryChart.style.display, 'none');
  assert.match(target.content, /data-workbench-group="five_minute_selection" aria-expanded="true"/);
  assert.equal([...target.content.matchAll(/data-hammer-workbench-code="/g)].length, 3);
  context.fetchKline = async () => ({ data: boardRows });
  await vm.runInContext('loadWorkbenchIndustryChart(state.selectedIndustry)', context);
  assert.equal(elements.workbenchIndustryRetry.style.display, 'none');
  assert.equal(elements.workbenchIndustryChart.style.display, '');
});

const industryStats = name => ({name, quote_date:'2026-09-30', amount_date:'2026-09-29', flow_date:'2026-09-30',
  pct_change:2, amount:3e8, net_inflow:-1.25e8, inflow:2e8, outflow:3.25e8, net_rank:80, rank_total:90,
  leader:'领涨股', leader_pct:8, breadth_date:'2026-09-30', up_count:5, down_count:3, flat_count:1,
  breadth_covered:9, breadth_expected:12, company_count:12});

test('industry summaries show full-board funds and separate dates while preserving source lists', async () => {
  const { context, target, elements, sidebar } = sandbox();
  context.fixture.groups[0].id = 'main_board_30d_top150';
  vm.runInContext("activeWatchlistGroupId='main_board_30d_top150'; renderHammerWorkbenchList();", context);
  target.groups[0].list.scrollTop = 210;
  context.fetchSummary = async () => ({data:[industryStats('板块A'),industryStats('板块B')]});
  await vm.runInContext('ensureWorkbenchIndustrySummaries()', context);
  assert.equal([...target.content.matchAll(/<b>板块[AB]<\/b><\/button>/g)].length, 4);
  assert.doesNotMatch(target.content, /资金排名|净流入|涨跌统计|成交额|日K \/ 成交量|入选/);
  assert.equal(target.groups[0].list.scrollTop,210);
  assert.equal(sidebar.scrollTop,77);
  assert.equal([...target.content.matchAll(/data-hammer-workbench-code=/g)].length,3);
  context.fetchKline = async () => ({data:boardRows});
  await vm.runInContext("selectWorkbenchIndustry('板块A','main_board_30d_top150:板块A')",context);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/资金净额排名.*第80 \/ 90/);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/统计整个板块/);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/与上方鼠标选中的历史K线日期独立/);
});

test('missing funds stay unknown and a summary failure does not prevent loading candles', async () => {
  const { context, elements, chart } = sandbox();
  context.fetchSummary = async () => {throw new Error('统计接口不可用');};
  context.fetchKline = async () => ({data:boardRows});
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  await vm.runInContext('ensureWorkbenchIndustrySummaries()',context);
  assert.equal(chart.options.length,2);
  assert.equal(elements.workbenchIndustryChart.style.display,'');
  assert.match(elements.workbenchIndustrySummary.innerHTML,/统计接口不可用/);
  context.fetchSummary = async () => ({data:[{name:'板块A', net_inflow:null, pct_change:null, amount:null}]});
  await vm.runInContext('ensureWorkbenchIndustrySummaries(true)',context);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/暂无数据/);
  assert.doesNotMatch(elements.workbenchIndustrySummary.innerHTML,/0\.00亿元|0\.00%/);
});

test('late shared statistics render for the currently selected board and keep cached data on retry failure', async () => {
  const { context, elements } = sandbox();
  let resolve;
  context.fetchSummary=()=>new Promise(done=>{resolve=done;});
  context.fetchKline=async()=>({data:boardRows});
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  await vm.runInContext("selectWorkbenchIndustry('板块B','five_minute_selection:板块B')",context);
  resolve({data:[{...industryStats('板块A'),leader:'A领先'}, {...industryStats('板块B'),leader:'B领先'}]});
  await vm.runInContext('ensureWorkbenchIndustrySummaries()',context);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/B领先/);
  assert.doesNotMatch(elements.workbenchIndustrySummary.innerHTML,/A领先/);
  context.fetchSummary=async()=>{throw new Error('更新失败');};
  await vm.runInContext('ensureWorkbenchIndustrySummaries(true)',context);
  assert.match(elements.workbenchIndustrySummary.innerHTML,/B领先.*本次更新失败/);
});

test('historical net flows align by date with units, colors, zero and missing values', async () => {
  const {context,chart,elements}=sandbox();
  context.fetchKline=async()=>({data:[...boardRows,{...boardRows[1],date:'2026-09-30'},{...boardRows[1],date:'2026-10-01'}]});
  context.fetchHistory=async()=>({data:[{date:'2026-09-30',net_inflow:0},{date:'2026-09-28',net_inflow:2e8},{date:'2026-09-29',net_inflow:-1.5e8}]});
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  const option=chart.options.at(-1), flow=option.series.at(-1);
  assert.deepEqual(Array.from(flow.data,item=>item.value),[2,-1.5,0,null]);
  assert.equal(flow.data[0].itemStyle.color,'#f85149');
  assert.equal(flow.data[1].itemStyle.color,'#3fb950');
  assert.deepEqual(Array.from(option.dataZoom[1].xAxisIndex),[0,1,2,3,4,5]);
  assert.match(option.tooltip.formatter([{dataIndex:2,axisIndex:5}]),/0.00亿元/);
  assert.doesNotMatch(option.tooltip.formatter([{dataIndex:2,axisIndex:5}]),/暂无快照/);
  assert.match(option.tooltip.formatter([{dataIndex:3,axisIndex:5}]),/暂无快照/);
  assert.match(elements.workbenchIndustryStatus.textContent,/共 3 个日期/);
});

test('history failure keeps candles and delayed history cannot overwrite another board', async () => {
  const {context,chart,elements}=sandbox();
  context.fetchKline=async()=>({data:boardRows});
  context.fetchHistory=async()=>{throw new Error('资金接口失败');};
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  assert.equal(elements.workbenchIndustryChart.style.display,'');
  assert.match(elements.workbenchIndustryStatus.textContent,/历史资金加载失败/);
  let resolve;
  context.fetchHistory=()=>new Promise(done=>{resolve=done;});
  const a=vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  await new Promise(done=>setImmediate(done));
  context.fetchHistory=async()=>({data:[{date:'2026-09-29',net_inflow:5e8}]});
  await vm.runInContext("selectWorkbenchIndustry('板块B','five_minute_selection:板块B')",context);
  const count=chart.options.length;
  resolve({data:[{date:'2026-09-29',net_inflow:-9e8}]});
  await a;
  assert.equal(chart.options.length,count);
  assert.equal(chart.options.at(-1).series.at(-1).data[1].value,5);
  assert.match(elements.workbenchIndustryTitle.textContent,/板块B/);
});

const strengthRow = (date, changes={}) => ({date, relative_5d:0, relative_20d:null,
  outperform_5d_pct:0, above_ma20_pct:100, eligible_count:4, covered_5d:2, covered_20d:0,
  outperform_5d_count:0, above_ma20_count:3, ma20_covered:3,
  benchmark_expected:100, benchmark_covered_5d:90, benchmark_covered_20d:80, ...changes});

test('strength and breadth align dates, preserve zero/missing values and share zoom with candles', async () => {
  const {context,chart,elements}=sandbox();
  context.fetchKline=async()=>({data:boardRows});
  context.fetchStrength=async()=>({latest_date:'2026-09-29',total:1,data:[strengthRow('2026-09-29',{limit_up_count:0,limit_down_count:2,limit_covered:3})]});
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  const option=chart.options.at(-1);
  assert.equal(option.grid.length,6);
  assert.deepEqual(Array.from(option.series[2].data),[null,0]);
  assert.deepEqual(Array.from(option.series[3].data),[null,null]);
  assert.deepEqual(Array.from(option.series[4].data),[null,0]);
  assert.deepEqual(Array.from(option.series[5].data),[null,100]);
  assert.equal(option.series[2].connectNulls,false);
  assert.equal(option.series[2].markLine.data[0].yAxis,0);
  assert.equal(option.series[4].markLine.data[0].yAxis,50);
  assert.equal(option.yAxis[3].min,0);
  assert.equal(option.yAxis[3].max,100);
  assert.deepEqual(Array.from(option.series[6].data),[null,0]);
  assert.deepEqual(Array.from(option.series[7].data),[null,-2]);
  assert.equal(option.series[6].lineStyle.color,'#f85149');
  assert.equal(option.series[7].lineStyle.color,'#3fb950');
  assert.equal(option.yAxis[4].minInterval,1);
  assert.equal(option.yAxis[4].min({min:-2,max:0}),-2);
  assert.equal(option.yAxis[4].max({min:-2,max:0}),2);
  assert.equal(option.yAxis[4].min({min:0,max:0}),-1);
  assert.equal(option.yAxis[4].max({min:0,max:0}),1);
  assert.equal(option.yAxis[4].axisLabel.formatter(-2),'2家');
  assert.equal(option.series[6].markLine.data[0].yAxis,0);
  assert.match(option.tooltip.formatter([{dataIndex:1,axisIndex:4}]),/涨停 0 家.*跌停 2 家.*统计覆盖 3 \/ 4/);
  assert.match(option.tooltip.formatter([{dataIndex:1,axisIndex:2}]),/5日超额 0.00个百分点.*20日超额 --/);
  assert.match(option.tooltip.formatter([{dataIndex:1,axisIndex:3}]),/0.00%.*100.00%/);
  assert.doesNotMatch(option.tooltip.formatter([{dataIndex:1,axisIndex:2}]),/涨停|成交量|净流入|MA20/);
  assert.doesNotMatch(option.tooltip.formatter([{dataIndex:1,axisIndex:3}]),/超额|涨停|成交量|净流入/);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/覆盖 2\/4 只/);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/跑赢 0 \/ 有效 2/);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/MA20覆盖 3\/4/);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/本地不复权日K/);
});

test('strength API failure keeps candles, funds and an actionable error rather than false zero breadth', async () => {
  const {context,chart,elements}=sandbox();
  context.fetchKline=async()=>({data:boardRows});
  context.fetchHistory=async()=>({data:[{date:'2026-09-29',net_inflow:3e8}]});
  context.fetchStrength=async()=>{throw new Error('指标服务失败');};
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  assert.equal(elements.workbenchIndustryChart.style.display,'');
  assert.equal(chart.options.at(-1).series.at(-1).data[1].value,3);
  assert.deepEqual(Array.from(chart.options.at(-1).series[4].data),[null,null]);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/指标服务失败/);
  assert.match(elements.workbenchIndustryStatus.textContent,/日K仍可查看/);
});

test('late strength responses cannot overwrite the currently selected sector', async () => {
  const {context,chart,elements}=sandbox();
  let resolve;
  context.fetchKline=async()=>({data:boardRows});
  context.fetchStrength=()=>new Promise(done=>{resolve=done;});
  const a=vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  await new Promise(done=>setImmediate(done));
  context.fetchStrength=async()=>({latest_date:'2026-09-29',data:[strengthRow('2026-09-29',{relative_5d:7})]});
  await vm.runInContext("selectWorkbenchIndustry('板块B','five_minute_selection:板块B')",context);
  const count=chart.options.length;
  resolve({latest_date:'2026-09-29',data:[strengthRow('2026-09-29',{relative_5d:-9})]});
  await a;
  assert.equal(chart.options.length,count);
  assert.equal(chart.options.at(-1).series[2].data[1],7);
  assert.match(elements.workbenchIndustryIndicators.innerHTML,/\+7.00个百分点/);
  assert.doesNotMatch(elements.workbenchIndustryIndicators.innerHTML,/-9.00个百分点/);
});

test('funds are last and strength/breadth panels grow exactly 1.5 times without shrinking price or volume', async () => {
  const {context,chart}=sandbox();
  context.fetchKline=async()=>({data:boardRows});
  await vm.runInContext("selectWorkbenchIndustry('板块A','five_minute_selection:板块A')",context);
  const option=chart.options.at(-1);
  assert.deepEqual(Array.from(option.series,s=>s.xAxisIndex??0),[0,1,2,2,3,3,4,4,5]);
  assert.equal(option.series.at(-1).name,'资金净流入');
  assert.equal(option.legend.at(-1).data.at(-1),'资金净流入');
  const height=1237.5, oldHeight=900;
  const panelHeight=i=>parseFloat(option.grid[i].height)*height/100;
  assert.ok(Math.abs(panelHeight(0)-oldHeight*0.30)<1e-8);
  assert.ok(Math.abs(panelHeight(1)-oldHeight*0.07)<1e-8);
  assert.ok(Math.abs(panelHeight(2)-oldHeight*0.10*1.5)<1e-8);
  assert.ok(Math.abs(panelHeight(3)-oldHeight*0.09*1.5)<1e-8);
  assert.ok(Math.abs(panelHeight(5)-oldHeight*0.08)<1e-8);
  for(let i=1;i<6;i++) assert.ok(parseFloat(option.grid[i].top)>parseFloat(option.grid[i-1].top)+parseFloat(option.grid[i-1].height));
  assert.match(html,/#workbenchIndustryChart[^}]*min-height:1237\.5px/);
});

test('board center and obsolete handlers are removed, and industry overview links lead to the workbench', () => {
  assert.doesNotMatch(html,/data-view="boards"|id="boardsView"|板块中心|async function loadBoards|loadBoardDetail\(/);
  assert.doesNotMatch(html,/showWorkspace\('boards'\)|data-board-type/);
  assert.match(html,/selectWorkbenchIndustry\(item.dataset.flowBoardName, `overview:/);
  assert.match(html,/id="workbenchIndustryIndicators"/);
});

test('rotation starts with market indices, then every ranked industry including empty constituent sublists', () => {
  const {context,target,elements}=sandbox();
  context.fixture.groups[0].id='sector_rotation';
  context.fixture.sector_rotation_screen={trade_date:'2026-09-30',industries:[{name:'板块B'},{name:'Empty'},{name:'板块A'}]};
  vm.runInContext("activeWatchlistGroupId='sector_rotation';renderHammerWorkbenchList()",context);
  const entries=[...target.content.matchAll(/data-workbench-industry="([^"]+)"/g)].map(m=>m[1]);
  assert.deepEqual(entries,['板块B','Empty','板块A']);
  assert.deepEqual([...target.content.matchAll(/data-workbench-market-index="([^"]+)"/g)].map(m=>m[1]),['000001','399001','000300']);
  assert.match(target.content,/<summary>大盘列表<\/summary>/);
  assert.ok(target.content.indexOf('data-industry-key="sector_rotation:market-indices"')<target.content.indexOf('data-industry-key="sector_rotation:板块B"'));
  assert.doesNotMatch(target.content,/板块与大盘合并图|sector_rotation:board-indices/);
  assert.match(target.content,/暂无主板非ST成分股/);
  assert.equal([...target.content.matchAll(/data-hammer-workbench-code=/g)].length,3);
  assert.match(elements.workbenchGroupStatus.textContent,/强度50%.*广度50%.*30日低点/);
});

test('market index averages use full trailing trading-day windows and scoped tooltips', () => {
  const {context}=sandbox();
  context.rows=Array.from({length:40},(_,index)=>({date:`day-${index+1}`,open:index+1,close:index+1,high:index+2,low:index+.5,volume:100,amount:200}));
  const result=JSON.parse(vm.runInContext(`JSON.stringify((()=>{
    const option=buildWorkbenchMarketIndexOption(rows,'上证指数');
    return {lines:option.series.slice(1,5),
      price:option.tooltip.formatter(option.series.slice(0,5).map((s,i)=>({seriesIndex:i,dataIndex:39}))),
      volume:option.tooltip.formatter([{seriesIndex:5,dataIndex:39}]),
      axes:option.dataZoom.map(item=>item.xAxisIndex)};
  })())`,context));
  assert.deepEqual(result.lines.map(line=>line.name),['MA5','MA10','MA20','MA30']);
  for(const [i,period] of [5,10,20,30].entries()){
    assert.equal(result.lines[i].data[period-2],null);
    assert.equal(result.lines[i].data[period-1],(period+1)/2);
    assert.equal(result.lines[i].data[39],40-(period-1)/2);
  }
  assert.match(result.price,/MA5 38\.000/);
  assert.match(result.price,/MA30 25\.500/);
  assert.doesNotMatch(result.price,/成交量/);
  assert.match(result.volume,/成交量 100/);
  assert.doesNotMatch(result.volume,/MA|涨幅/);
  assert.deepEqual(result.axes,[[0,1],[0,1]]);
  context.rows[35].close=null;
  const missing=JSON.parse(vm.runInContext("JSON.stringify(buildWorkbenchMarketIndexOption(rows,'上证指数').series[1].data)",context));
  assert.equal(missing[35],null);
  assert.equal(missing[39],null);
});

test('selecting a market index exits minutes and loads an independent daily chart with visible averages', async () => {
  const {context,chart,elements}=sandbox();
  context.state.industryView='minute';
  context.fetchIndex=async url=>{context.indexUrl=url;return {data:Array.from({length:35},(_,i)=>({date:`day-${i}`,open:100+i,close:101+i,low:99+i,high:102+i,volume:10}))};};
  await vm.runInContext("selectWorkbenchMarketIndex('000001')",context);
  assert.equal(context.state.selectedIndustry.kind,'market-index');
  assert.equal(context.state.selectedIndustry.code,'000001');
  assert.equal(context.state.selected,null);
  assert.equal(context.state.industryView,'day');
  assert.equal(context.indexUrl,'/api/index/kline?code=000001&period=day');
  assert.equal(context.state.totalBars,35);
  assert.equal(chart.options.at(-1).series.length,6);
  assert.equal(chart.options.at(-1).legend[0].selected,undefined);
  assert.equal(elements.workbenchIndustryIndicators.style.display,'none');
  assert.equal(elements.workbenchIndustrySummary.innerHTML,'');
  assert.match(elements.workbenchIndustryTitle.textContent,/大盘日K与均线/);
  assert.match(elements.workbenchIndustryStatus.textContent,/MA5.*MA30/);
  vm.runInContext('renderWorkbenchIndustrySummary()',context);
  assert.equal(elements.workbenchIndustrySummary.innerHTML,'');
  assert.equal(await vm.runInContext("switchIndustryView('minute')",context),undefined);
  assert.equal(context.state.industryView,'day');
});

test('late market index responses cannot overwrite a newly selected index or board', async () => {
  const {context,chart}=sandbox();
  let resolveOld;
  context.fetchIndex=()=>new Promise(resolve=>{resolveOld=resolve;});
  const old=vm.runInContext("selectWorkbenchMarketIndex('000001')",context);
  context.fetchIndex=async()=>({data:boardRows});
  await vm.runInContext("selectWorkbenchMarketIndex('399001')",context);
  const count=chart.options.length;
  resolveOld({data:boardRows});await old;
  assert.equal(chart.options.length,count);
  assert.equal(context.state.selectedIndustry.code,'399001');
  let resolveMarket;
  context.fetchIndex=()=>new Promise(resolve=>{resolveMarket=resolve;});
  const pending=vm.runInContext("selectWorkbenchMarketIndex('000300')",context);
  context.fetchIndex=async()=>({data:boardRows});
  context.fetchKline=async()=>({data:boardRows});
  await vm.runInContext("selectWorkbenchIndustry('板块A','sector_rotation:板块A')",context);
  const boardCount=chart.options.length;
  resolveMarket({data:boardRows});await pending;
  assert.equal(chart.options.length,boardCount);
  assert.equal(context.state.selectedIndustry.name,'板块A');
  assert.equal(chart.options.at(-1).series[0].name,'板块日K');
});

test('two OHLC series share a date baseline, rebase after zoom and retain intraday shape and missing dates', () => {
  const {context}=sandbox();
  context.rows=[{date:'2026-09-28',open:90,close:100,low:80,high:110},
    {date:'2026-09-29',open:100,close:120,low:95,high:125},
    {date:'2026-09-30',open:120,close:150,low:110,high:160}];
  context.marketRows=[{date:'2026-09-28',open:900,close:1000,low:850,high:1100},
    {date:'2026-09-30',open:1000,close:1100,low:990,high:1150}];
  const result=JSON.parse(vm.runInContext(`JSON.stringify((()=>{
    const c={name:'上证指数',byDate:new Map(marketRows.map(r=>[r.date,r])),zoom:{startValue:0,endValue:2}};
    const original=buildIndustryKlineOption(rows,new Map(),new Map(),c);
    const before=industryComparisonData(rows,c,c.zoom);
    const after=industryComparisonData(rows,c,{startValue:1,endValue:2});
    const absent=industryComparisonData(rows,c,{startValue:1,endValue:1});
    const hover=original.tooltip.formatter([{dataIndex:2,seriesIndex:0}]);
    const missing=original.tooltip.formatter([{dataIndex:1,seriesIndex:0}]);
    const ordinary=buildIndustryKlineOption(rows,new Map(),new Map(),null,marketRows).tooltip.formatter([{dataIndex:2,seriesIndex:0}]);
    return {before,after,absent,hover,missing,ordinary,types:original.series.slice(0,2).map(s=>s.type),axis:original.yAxis[0].axisLabel.formatter};
  })())`,context));
  assert.deepEqual(result.types,['candlestick','candlestick']);
  assert.equal(result.axis,'{value}%');
  assert.equal(result.before.anchorDate,'2026-09-28');
  assert.equal(result.before.candles[0][1],0);
  assert.equal(result.before.marketCandles[0][1],0);
  assert.ok(Math.abs(result.before.candles[0][0]+10)<1e-8);
  assert.ok(Math.abs(result.before.candles[2][1]-50)<1e-8);
  assert.deepEqual(result.before.marketCandles[1],[null,null,null,null]);
  assert.equal(result.after.anchorDate,'2026-09-30');
  assert.equal(result.after.candles[2][1],0);
  assert.equal(result.after.marketCandles[2][1],0);
  assert.equal(result.absent.anchorDate,null);
  assert.deepEqual(result.absent.candles[1],[null,null,null,null]);
  assert.equal(result.hover,'2026-09-30<br>板块涨幅 +25.00%<br>大盘涨幅 +10.00%');
  assert.equal(result.ordinary,result.hover);
  assert.equal(result.missing,'2026-09-29<br>板块涨幅 +20.00%<br>大盘涨幅 --');
});

test('combined board chart loads market K candles and late market responses cannot replace another sector', async () => {
  const {context,chart}=sandbox();
  context.fetchKline=async()=>({data:boardRows});
  let resolve;
  context.fetchIndex=()=>new Promise(done=>{resolve=done;});
  const a=vm.runInContext("selectWorkbenchIndustry('板块A','sector_rotation:板块A')",context);
  await new Promise(done=>setImmediate(done));
  context.fetchIndex=async()=>({name:'上证指数',data:boardRows.map(r=>({...r,open:r.open*10,close:r.close*10,low:r.low*10,high:r.high*10}))});
  await vm.runInContext("selectWorkbenchIndustry('板块B','sector_rotation:板块B')",context);
  const count=chart.options.length;
  const option=chart.options.at(-1);
  assert.deepEqual(Array.from(option.series.slice(0,2),s=>s.type),['candlestick','candlestick']);
  assert.equal(option.series[0].data[0][1],0);
  assert.equal(option.series[1].data[0][1],0);
  resolve({name:'Old',data:boardRows});await a;
  assert.equal(chart.options.length,count);
  assert.equal(context.state.selectedIndustry.name,'板块B');
});

test('concept rotation groups by concept rather than industry and preserves overlapping memberships',()=>{
  const {context,target,elements}=sandbox();
  context.fixture.groups[0]={id:'concept_rotation',name:'概念轮动·主板非ST',system:true,stocks:[
    {code:'600001',name:'One',industry:'行业A',concept:'概念A',return_30d_pct:30},
    {code:'600001',name:'One',industry:'行业A',concept:'概念B',return_30d_pct:30},
    {code:'600002',name:'Two',industry:'行业A',concept:'概念B',return_30d_pct:10}]};
  context.fixture.concept_rotation_screen={trade_date:'2026-09-30',concepts:[{name:'概念B'},{name:'概念A'},{name:'Missing'}]};
  vm.runInContext("activeWatchlistGroupId='concept_rotation';renderHammerWorkbenchList()",context);
  assert.match(target.content,/data-concept-lazy="true"/);
  assert.doesNotMatch(target.content,/data-hammer-workbench-code="600001"/);
  target.details.filter(item=>!item.dataset.industryKey.endsWith(':market-indices')).forEach(item=>item.open=true);
  vm.runInContext("renderHammerWorkbenchList()",context);
  assert.deepEqual([...target.content.matchAll(/data-workbench-industry="([^"]+)"/g)].map(m=>m[1]),['概念B','概念A','Missing']);
  assert.equal([...target.content.matchAll(/data-hammer-workbench-code="600001"/g)].length,2);
  assert.match(target.content,/concept_rotation:market-indices/);assert.doesNotMatch(target.content,/data-workbench-industry="行业A"/);
  assert.match(elements.workbenchGroupStatus.textContent,/强度50%.*广度50%.*30日低点/);
});

test('concept chart uses concept candles, flows, breadth and market comparison',async()=>{
  const {context,elements,chart}=sandbox();const urls=[];
  context.fetchKline=async url=>{urls.push(url);assert.match(url,/type=concept&name=/);return {data:[{date:'2026-09-30',open:10,close:11,high:12,low:9,volume:100}]};};
  context.fetchHistory=async url=>{urls.push(url);return {data:[]};};
  context.fetchStrength=async url=>{urls.push(url);return {latest_date:'2026-09-30',data:[{date:'2026-09-30',relative_5d:2,relative_20d:3,outperform_5d_pct:60,above_ma20_pct:70}]};};
  context.fetchIndex=async url=>{urls.push(url);return {name:'Market',data:[{date:'2026-09-30',open:100,close:101,high:102,low:99,volume:100}]};};
  await vm.runInContext("selectWorkbenchIndustry('概念A','concept_rotation:概念A')",context);
  assert.ok(urls.some(url=>url.includes('/concept-strength-history?')));assert.ok(urls.some(url=>url.includes('/concept-flow-history?')));
  assert.equal(context.state.selectedIndustry.boardType,'concept');assert.equal(context.state.selectedIndustry.combined,true);
  assert.ok(chart.options.at(-1).series.some(series=>series.id==='industry-benchmark'));assert.match(elements.workbenchIndustryIndicators.innerHTML,/同花顺概念/);
});
