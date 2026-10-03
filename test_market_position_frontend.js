const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {test} = require('node:test');
const html = fs.readFileSync('frontend/index.html', 'utf8');
const render = html.slice(html.indexOf('function renderMarketPosition('), html.indexOf('async function loadOverview()', html.indexOf('function renderMarketPosition(')));
const start = html.lastIndexOf('async function loadOverview()');
const load = html.slice(start, html.indexOf('async function loadCoverage()', start));
function setup() {
  const elements = Object.fromEntries(['marketPosition', 'marketFlightHeight', 'overviewMetrics', 'overviewDate', 'syncOverview'].map(id => [id, {}]));
  const context = vm.createContext({$: id => elements[id], escapeHtml: value => String(value).replaceAll('<', '&lt;'), renderFlowTable: () => {}});
  vm.runInContext(render + load, context);
  return {context, elements};
}
const position = {available: true, band: 'high', band_label: '高位', position: 1 / 3, position_label: '1/3仓', close: 3850,
  low: 3300, high: 3900, lower_threshold: 3360, upper_threshold: 3840, range_percent: 91.667,
  start_date: '2026-08-20', date: '2026-09-30', low_date: '2026-09-01', high_date: '2026-09-10', days: 30};
test('active overview renders position, thresholds, dates and highlighted band', async () => {
  const {context, elements} = setup();
  context.fetchJSON = async () => ({market_position: position});
  await vm.runInContext('loadOverview()', context);
  const content = elements.marketPosition.innerHTML;
  for (const text of ['3850.00', '3360.00', '3840.00', '1/3仓', '2026-08-20', '2026-09-30', '91.67%', '最新日线收盘', '底部10%', '中间80%', '顶部10%']) assert.ok(content.includes(text), text);
  assert.match(content, /market-position-band active"><b class="market-position-high/);
});
test('missing data and failed reload clear the previous position', async () => {
  const {context, elements} = setup();
  context.payload = position;
  vm.runInContext('renderMarketPosition(payload)', context);
  context.fetchJSON = async () => {throw new Error('offline');};
  await vm.runInContext('loadOverview()', context);
  assert.match(elements.marketPosition.innerHTML, /offline/);
  assert.ok(!elements.marketPosition.innerHTML.includes('3850.00'));
  context.fetchJSON = async () => ({});
  await vm.runInContext('loadOverview()', context);
  assert.match(elements.marketPosition.innerHTML, /等待上证指数日线数据/);
});
test('all inline scripts parse', () => {
  for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) if (match[1].trim()) new vm.Script(match[1]);
});

test('overview shows both flight layers and all boundary sources', async () => {
  const {context, elements} = setup();
  const levels = [3300, 3360, 3500, 3840, 3900, 4000].map((price, index) => ({price,
    sources: [{label: ['90日最低点', '30日最低点', 'MA30', 'MA5', '10日最高点', '90日最高点'][index], date: '2026-09-30'}]}));
  levels[0].sources.push({label: '10日最低点', date: '2026-09-30'});
  const flight = {available: true, date: '2026-09-30', close: 3850, level_count: 6, levels,
    first: {support: levels[3], resistance: levels[4], complete: true},
    second: {support: levels[2], resistance: levels[5], complete: true}};
  context.fetchJSON = async () => ({market_position: {...position, flight_height: flight}});
  await vm.runInContext('loadOverview()', context);
  const content = elements.marketFlightHeight.innerHTML;
  for (const text of ['飞行高度', '第一层', '第二层', '3840.000 — 3900.000', '3500.000 — 4000.000', 'MA5', 'MA30', '90日最低点 / 10日最低点', '6个价位']) assert.ok(content.includes(text), text);
  context.fetchJSON = async () => {throw new Error('offline');};
  await vm.runInContext('loadOverview()', context);
  assert.ok(!elements.marketFlightHeight.innerHTML.includes('3840.000'));
});

test('missing outer boundary is explicit and never replaced by the first layer', () => {
  const {context, elements} = setup();
  context.flight = {available: true, date: '2026-09-30', close: 3850, levels: [], level_count: 0,
    first: {support: {price: 3800}, resistance: {price: 3900}},
    second: {support: null, resistance: {price: 4000}}};
  vm.runInContext('renderMarketFlightHeight(flight)', context);
  const content = elements.marketFlightHeight.innerHTML;
  assert.match(content, /未形成完整区间/);
  assert.match(content, /下方暂无更多参考点位/);
  assert.ok(!content.includes('3800.000 — 4000.000'));
});
