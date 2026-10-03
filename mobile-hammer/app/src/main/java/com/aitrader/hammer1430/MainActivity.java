package com.aitrader.hammer1430;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String[] TITLES={"","","自选股","市场总览","板块轮动","持仓股","自选延伸","交割单复盘"};
    private static final String[] LABELS={"","","自选","大盘","轮动","持仓","延伸","复盘"};
    private static final String[] ICONS={"","","star","market","sectors","holdings","gain","review"};
    private TextView status,snapshotAt,title;
    private LinearLayout groupsBox;
    private ScrollView scroll;
    private Ui.Icon scan;
    private LinearLayout stickySector;
    private final List<SectorAnchor> sectorAnchors=new ArrayList<>();
    private SectorAnchor pinnedSector;
    private static final class SectorAnchor {
        final String name,key;final LinearLayout card,header,body;final TextView arrow;final Runnable fill;
        SectorAnchor(String n,String k,LinearLayout c,LinearLayout h,LinearLayout b,TextView a,Runnable f){name=n;key=k;card=c;header=h;body=b;arrow=a;fill=f;}
    }
    private final List<LinearLayout> tabs=new ArrayList<>();
    private final Map<String,Boolean> industryOpen=new HashMap<>();
    private final Map<Integer,Integer> scrollPositions=new HashMap<>();
    private final Map<String,Boolean> starredCodes=new HashMap<>();
    private final Set<String> heldCodes=new HashSet<>();
    private int selectedModule=3;
    private IndustryCatalog industryCatalog;
    private ConceptCatalog conceptCatalog;
    private final Map<String,JSONObject> rotationByCode=new HashMap<>();
    private String watchlistImportError="";
    private JSONObject market=new JSONObject(),rotation=new JSONObject();
    private IndustryRanking industryRanking=new IndustryRanking(new JSONObject());
    private String lastMarketPayload,lastRotationPayload,lastWatchlistPayload,lastHoldingsPayload,lastQuotePayload,lastMarketError,lastConceptPayload,lastSignalPayload;
    private String lastNotesPayload,lastJournalPayload;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean visible;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key)->handler.post(()->{if(visible)update();});
    private int dp(float value){return Ui.dp(this,value);}
    private TextView text(String value,int size){return Ui.text(this,value,size,Ui.INK,size>=17);}
    private TextView secondary(String value,int size){return Ui.text(this,value,size,Ui.SECONDARY,false);}
    private void add(LinearLayout box,View view){Ui.add(box,view);}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        try{industryCatalog=new IndustryCatalog(this);}catch(Exception ignored){}
        try{conceptCatalog=new ConceptCatalog(this);}catch(Exception ignored){}
        try{WatchlistStore.importBundled(this);}catch(Exception error){watchlistImportError=String.valueOf(error.getMessage());}
        selectedModule=state!=null?state.getInt("module",3):getIntent().getIntExtra("module",3);
        if(selectedModule<2||selectedModule>7)selectedModule=3;
        LinearLayout root=Ui.column(this);setContentView(root);Ui.install(this,root);
        LinearLayout header=Ui.column(this);header.setPadding(dp(20),dp(18),dp(16),dp(12));add(root,header);
        LinearLayout top=Ui.row(this),branding=Ui.column(this);add(branding,secondary("AItrader",12));Ui.gap(branding,5);
        title=Ui.number(this,TITLES[selectedModule],32,Ui.INK);add(branding,title);top.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        Ui.Icon help=Ui.iconButton(this,"help","查看规则与刷新说明");help.setOnClickListener(v->showRules());top.addView(help,new LinearLayout.LayoutParams(dp(44),dp(44)));
        scan=Ui.iconButton(this,"refresh","刷新大盘");scan.setOnClickListener(v->refresh());
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(dp(44),dp(44));action.setMargins(dp(8),0,0,0);top.addView(scan,action);add(header,top);
        Ui.gap(header,10);status=secondary("正在准备行情",12);Ui.compact(status);add(header,status);
        Ui.gap(header,4);snapshotAt=secondary("自动更新 · 10分钟",11);add(header,snapshotAt);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        FrameLayout listHost=new FrameLayout(this);root.addView(listHost,new LinearLayout.LayoutParams(-1,0,1));listHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        stickySector=Ui.row(this);stickySector.setContentDescription("轮动板块悬浮标题");stickySector.setPadding(dp(16),dp(8),dp(12),dp(8));stickySector.setBackground(Ui.shape(this,Ui.WHITE,14));stickySector.setElevation(dp(4));stickySector.setVisibility(View.GONE);
        FrameLayout.LayoutParams stickyParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);stickyParams.setMargins(dp(16),0,dp(16),0);listHost.addView(stickySector,stickyParams);
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->updatePinnedSector());listHost.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updatePinnedSector());
        groupsBox=Ui.column(this);groupsBox.setPadding(dp(16),dp(4),dp(16),dp(24));scroll.addView(groupsBox);
        LinearLayout navigation=Ui.column(this);navigation.setBackgroundColor(Ui.WHITE);
        View border=new View(this);border.setBackgroundColor(Ui.LINE);navigation.addView(border,new LinearLayout.LayoutParams(-1,1));
        LinearLayout nav=Ui.row(this);nav.setPadding(dp(6),dp(6),dp(6),dp(5));add(navigation,nav);add(root,navigation);
        for(int index:new int[]{3,4,2,6,5,7}){
            LinearLayout tab=Ui.column(this);tab.setGravity(Gravity.CENTER);tab.setTag(index);tab.setContentDescription(TITLES[index]);tab.setClickable(true);tab.setFocusable(true);
            Ui.Icon icon=new Ui.Icon(this,ICONS[index],Ui.SECONDARY);tab.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(27)));Ui.gap(tab,4);
            TextView label=Ui.text(this,LABELS[index],11,Ui.SECONDARY,true);label.setGravity(Gravity.CENTER);tab.addView(label);nav.addView(tab,new LinearLayout.LayoutParams(0,dp(55),1));tabs.add(tab);
            tab.setOnClickListener(v->{if(selectedModule==index)return;scrollPositions.put(selectedModule,scroll.getScrollY());selectedModule=index;render();update();scroll.post(()->scroll.scrollTo(0,scrollPositions.getOrDefault(index,0)));});
        }
        render();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
    }
    private void refresh(){if(selectedModule==7){render();update();}else if(selectedModule==3)MarketDataRepository.refresh(this);
        else if(selectedModule==6){PersonalSignalService.refresh(this);PersonalQuotes.refresh(this);}
        else if(selectedModule==2||selectedModule==5){PersonalQuotes.refresh(this);PersonalSignalService.refresh(this);}
        else if(!ScreenService.isRunning()){scan.setEnabled(false);status.setText("正在同步行情…");startForegroundService(new Intent(this,ScreenService.class));}}
    @Override protected void onStart(){super.onStart();visible=true;
        getSharedPreferences("scan",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);
        getSharedPreferences("holdings",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);
        getSharedPreferences(NotesStore.PREFS,MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);
        getSharedPreferences("watchlist",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);PersonalSignalStore.prefs(this).registerOnSharedPreferenceChangeListener(changes);update();}
    @Override protected void onResume(){super.onResume();update();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("module",selectedModule);super.onSaveInstanceState(out);}
    @Override protected void onStop(){visible=false;
        getSharedPreferences("scan",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes);
        getSharedPreferences("holdings",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes);
        getSharedPreferences(NotesStore.PREFS,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes);
        getSharedPreferences("watchlist",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes);PersonalSignalStore.prefs(this).unregisterOnSharedPreferenceChangeListener(changes);super.onStop();}
    private void render(){if(groupsBox==null)return;reloadStockMarks();
        if(conceptCatalog!=null)conceptCatalog.update(this);industryRanking=new IndustryRanking(rotation);rotationByCode.clear();JSONArray rotationStocks=rotation.optJSONArray("stocks");if(rotationStocks!=null)for(int i=0;i<rotationStocks.length();i++){JSONObject r=rotationStocks.optJSONObject(i);if(r!=null)rotationByCode.put(r.optString("code"),r);}groupsBox.removeAllViews();sectorAnchors.clear();pinnedSector=null;stickySector.setVisibility(View.GONE);title.setText(TITLES[selectedModule]);
        scan.setContentDescription(selectedModule==7?"刷新复盘记录":selectedModule==3?"刷新大盘":selectedModule==4?"刷新板块轮动":selectedModule==6?"检查自选延伸与持仓减仓":"刷新自选与持仓行情");
        for(LinearLayout tab:tabs){boolean active=tab.getTag().equals(selectedModule);tab.setSelected(active);
            ((Ui.Icon)tab.getChildAt(0)).tint(active?Ui.BLUE:Ui.SECONDARY);((TextView)tab.getChildAt(2)).setTextColor(active?Ui.BLUE:Ui.SECONDARY);}
        if(selectedModule!=6&&selectedModule!=7)renderRiskBanner();if(selectedModule==3)renderMarket();else if(selectedModule==4)renderRotation();else if(selectedModule==2)renderWatchlist();else if(selectedModule==6)renderSignals();else if(selectedModule==7)renderReviews();else renderHoldings();scroll.post(this::updatePinnedSector);}
    private void showRules(){
        String rule=selectedModule==3?"大盘位置使用最近30个交易日最高点与最低点。下10%为低位，规则仓位为满仓；中间80%为中位，规则仓位为2/3仓；上10%为高位，规则仓位为1/3仓。\n\n飞行高度使用10、30、90日的最高点/最低点和MA5/10/20/30，共10个参考值。第一层取最近支撑与压力，第二层各向外一个不同点位。重合点合并，缺少边界时明确标出。当前价恰好触及参考位时，取它作为支撑。\n\n盘中按当前报价暂算，随刷新动态变化。"
                :selectedModule==4?"行业评分由5日和20日相对强度、5日跑赢市场比例、站上MA20比例四项分别转换为行业排名百分位后等权平均，强度与广度各占50%。四项齐全才评分；缺少交易日不补零。\n\n上涨成分股占比=当日上涨家数/具有当日和前一交易日有效报价的家数。\n\n板块内保留全部主板非ST成员，按最近30个交易日最低low至最新价涨幅降序；缺行情置后。粉色表示已加入自选或持仓，较深粉色表示已收藏；三个按钮显示名单状态。行业及概念分类随APK更新。概念标签按同样的强度50%+广度50%综合排名排序，排名分母为全部概念；缺项显示—。标签可左右滑动，点击进入概念日K、强度及广度。板块日K入口为带日期的内置快照，可选从电脑更新。"
                :selectedModule==6?"自选延伸分为两部分，只从当前自选股派生。两部分均要求前一交易日收阴（收盘低于开盘），或下跌（收盘低于再前一交易日收盘）。第一部分：最新交易日有效行为序列出现连续两个独立的15分钟进攻实线框。第二部分：已出现进攻实线框，但尚未形成两连进攻。形成两连后只进入第一部分，不重复显示；随后出现减仓不撤销当日已成立的组合。\n\n普通K线、黄色持平框、包含虚线框不打断有效序列，实线减仓框打断；同一个合并放量框只算一个形态。前日日期按市场交易日历匹配，停牌或缺失不使用更早日K替代。两部分按行业分组、按板块轮动综合排名排序，无排名行业置后，行业内收藏优先。\n\n持仓最新交易日出现一个有效实线减仓框即提醒，点击可定位15分钟K线。每个股票/交易日/框起点只提醒一次，框延长不重复，新的减仓框会再次提醒，可标记已读。\n\n与网页相同：20个完整交易日成交量中位数×2.5框选，先合并连续放量K线再按整框价格方向分类，只使用已结束K线。通知需系统授权；应用在前台按10分钟检查，离开后已开始的检查可完成，后台不持续轮询。"
                :selectedModule==5?"持仓名单独立保存在本机，可从自选和板块成员加入，也可输入代码添加。点击“已持仓”设置止损价，长按管理持仓。每只股票可设置或清除止损价，在日K及15分钟K线上显示；报价≤止损价时提示触及，并标明行情时间。设置不自动下单。"
                :"自选股保存在本机，按行业分组，行业顺序沿用板块轮动的强度50%+广度50%综合排名，无排名行业置后，待分类置于末尾。刷新轮动排名后自动调整顺序。点击股票查看日K或15分钟K线及指标，可加入持仓，亦可输入六位代码添加自选。每只自选股可额外收藏，点击名称行的☆添加收藏，★取消收藏；收藏股在各行业内优先排列并显示粉色；取消收藏恢复普通排序。";
        if(selectedModule==7)rule="记录曾加入并移出持仓、同一段持仓同时具备买入逻辑与卖出逻辑的股票。反复持仓分别保留，不跨段匹配笔记。未填写的逻辑可在个股笔记的买卖逻辑记录中补充，补齐后自动进入复盘。日期为北京时间的笔记保存日。";
        if(selectedModule==6)rule+="\n\n"+RiskRatio.FORMULA+"\n点击列表上的盈亏比可查看三个参考点与行情时间；参考点缺失或无上方止盈空间时显示—。";
        rule+="\n\n个股及行业/概念均可追加笔记，自动附上北京时间日期。每次追加独立保留，移出自选或持仓不删除笔记。加入/移出持仓时可选填买入/卖出逻辑，长按已持仓可继续追加。";
        if(selectedModule==3&&market.optBoolean("available"))rule+="\n\n当前低位界线："+points(market.optDouble("lower_threshold"))+"\n当前高位界线："+points(market.optDouble("upper_threshold"));
        new AlertDialog.Builder(this).setTitle(TITLES[selectedModule]+" · 说明").setMessage(rule+"\n\n自动刷新\n打开应用同步一次，此后北京时间9:00—15:30在前台每10分钟刷新；后台暂停，时段内返回后补刷新。右上角刷新按钮全天可用。").setPositiveButton("知道了",null).show();
    }
    private void empty(String title,String detail){LinearLayout box=Ui.card(groupsBox);box.setPadding(dp(24),dp(30),dp(24),dp(30));
        Ui.Icon icon=new Ui.Icon(this,ICONS[selectedModule],Ui.BLUE);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(48),dp(48));p.gravity=Gravity.CENTER_HORIZONTAL;box.addView(icon,p);
        Ui.gap(box,14);TextView heading=text(title,19);heading.setGravity(Gravity.CENTER);add(box,heading);Ui.gap(box,9);TextView note=secondary(detail,14);note.setGravity(Gravity.CENTER);add(box,note);Ui.gap(box,20);
        Button action=Ui.button(this,"刷新数据",true);action.setOnClickListener(v->refresh());add(box,action);}
    private void notice(String title,String detail){LinearLayout box=Ui.card(groupsBox);add(box,Ui.text(this,title,14,Ui.AMBER,true));Ui.gap(box,7);add(box,secondary(detail,13));}
    private String points(double value){return Double.isFinite(value)?String.format(Locale.CHINA,"%.3f",value):"—";}
    private String metric(JSONObject row,String key){double value=row.optDouble(key,Double.NaN);return Double.isFinite(value)?String.format(Locale.CHINA,"%.1f",value):"—";}
    private String shortDate(String raw){return raw.length()>=10?raw.substring(5):raw;}
    private void renderMarket(){String error=getSharedPreferences("scan",MODE_PRIVATE).getString("market_error","");if(!error.isEmpty())notice("行情更新未完成",error);
        if(!market.optBoolean("available")){empty("从大盘开始","刷新上证指数，查看大盘位置、规则仓位和当日参考震荡区间。");return;}
        LinearLayout hero=Ui.card(groupsBox),name=Ui.row(this);name.addView(text("上证指数",17),new LinearLayout.LayoutParams(0,-2,1));name.addView(Ui.pill(this,market.optBoolean("final_close")?"收盘":"盘中",Ui.SECONDARY,Ui.BG));add(hero,name);Ui.gap(hero,14);
        add(hero,Ui.number(this,points(market.optDouble("close")),42,Ui.INK));Ui.gap(hero,10);add(hero,secondary("SH000001  ·  "+market.optString("quote_time"),12));
        LinearLayout position=Ui.card(groupsBox),head=Ui.row(this);head.addView(secondary("规则仓位",13),new LinearLayout.LayoutParams(0,-2,1));head.addView(Ui.pill(this,market.optString("band_label"),Ui.BLUE,Ui.TINT));add(position,head);Ui.gap(position,10);
        add(position,Ui.number(this,market.optString("position_label"),32,Ui.INK));Ui.gap(position,10);add(position,secondary("30日区间位置  "+metric(market,"range_percent")+"%",13));position.addView(new Ui.RangeBar(this,market.optDouble("range_percent")),new LinearLayout.LayoutParams(-1,dp(30)));
        Ui.pair(position,Ui.stat(this,"30日最低",points(market.optDouble("low")),shortDate(market.optString("low_date")),Ui.INK),Ui.stat(this,"30日最高",points(market.optDouble("high")),shortDate(market.optString("high_date")),Ui.INK));
        Ui.section(groupsBox,"飞行高度","当日参考区间");JSONObject flight=market.optJSONObject("flight_height");if(flight==null||!flight.optBoolean("available")){notice("飞行高度暂不可用",flight==null?"日线数据尚未就绪":flight.optString("reason"));return;}
        addFlightLayer("第一层","就近支撑与压力",flight.optJSONObject("first"),true);addFlightLayer("第二层","两端各向外一层",flight.optJSONObject("second"),false);
        if(flight.optJSONObject("touching")!=null)notice("当前触及参考位","按该点位作为支撑，向上取当前区间。");
        LinearLayout refs=Ui.card(groupsBox);boolean open=industryOpen.getOrDefault("market:references",false);LinearLayout header=Ui.row(this),labels=Ui.column(this);add(labels,text("参考点位",16));Ui.gap(labels,5);add(labels,secondary("10个参考值 · "+flight.optInt("level_count")+"个不同点位",12));header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=Ui.text(this,open?"收起  ⌃":"展开  ⌄",13,Ui.BLUE,false);header.addView(arrow);header.setMinimumHeight(dp(44));header.setContentDescription("展开或收起参考点位");add(refs,header);LinearLayout body=Ui.column(this);body.setVisibility(open?View.VISIBLE:View.GONE);add(refs,body);
        JSONArray levels=flight.optJSONArray("levels");if(levels!=null)for(int i=0;i<levels.length();i++){JSONObject level=levels.optJSONObject(i);if(level==null)continue;Ui.line(body);LinearLayout row=Ui.row(this);row.addView(secondary(levelSources(level,true),12),new LinearLayout.LayoutParams(0,-2,1));TextView value=Ui.number(this,points(level.optDouble("price")),18,Ui.INK);value.setGravity(Gravity.END);row.addView(value,new LinearLayout.LayoutParams(dp(112),-2));add(body,row);}
        header.setOnClickListener(v->{boolean expand=body.getVisibility()!=View.VISIBLE;industryOpen.put("market:references",expand);body.setVisibility(expand?View.VISIBLE:View.GONE);arrow.setText(expand?"收起  ⌃":"展开  ⌄");});add(groupsBox,secondary("区间随价格、均线及高低点更新。点击右上角 ⓘ 查看计算规则。",12));
    }
    private String levelSources(JSONObject level,boolean dates){if(level==null)return "无参考位";JSONArray sources=level.optJSONArray("sources");List<String> labels=new ArrayList<>();if(sources!=null)for(int i=0;i<sources.length();i++){JSONObject s=sources.optJSONObject(i);if(s!=null)labels.add(s.optString("label")+(dates?" · "+shortDate(s.optString("date")):""));}return android.text.TextUtils.join(dates?"\n":" / ",labels);}
    private void addFlightLayer(String title,String description,JSONObject layer,boolean primary){LinearLayout card=Ui.card(groupsBox),header=Ui.row(this);header.addView(Ui.pill(this,title,primary?Ui.BLUE:Ui.SECONDARY,primary?Ui.TINT:Ui.BG));TextView note=secondary(description,12);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(10),0,0,0);header.addView(note,p);add(card,header);Ui.gap(card,18);
        if(layer==null){add(card,secondary("暂无区间数据",14));return;}JSONObject support=layer.optJSONObject("support"),resistance=layer.optJSONObject("resistance");
        Ui.pair(card,Ui.stat(this,"支撑位",support==null?"—":points(support.optDouble("price")),levelSources(support,false),Ui.GREEN),Ui.stat(this,"压力位",resistance==null?"—":points(resistance.optDouble("price")),levelSources(resistance,false),Ui.RED));
        if(!layer.optBoolean("complete")){Ui.gap(card,12);add(card,secondary("缺少边界，暂未形成完整区间",12));}}
    private void summary(String count,String label,String date,String note){LinearLayout box=Ui.card(groupsBox),row=Ui.row(this);row.addView(Ui.number(this,count,32,Ui.INK));TextView title=secondary(label,14);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(10),0,0,0);row.addView(title,p);if(!date.isEmpty())row.addView(Ui.pill(this,shortDate(date),Ui.SECONDARY,Ui.BG));add(box,row);if(!note.isEmpty()){Ui.gap(box,10);add(box,secondary(note,12));}}
    private void renderRotation(){JSONArray rows=rotation.optJSONArray("industries"),items=rotation.optJSONArray("stocks");if(rows==null||items==null){empty("发现行业轮动","手机独立计算行业强度与广度，首次刷新需同步主板日K。");return;}
        summary(String.valueOf(rows.length()),"个行业 · "+items.length()+"只股票",rotation.optString("trade_date"),rotation.optBoolean("final_close")?"强度50% + 广度50% · 收盘排名":"强度50% + 广度50% · 盘中暂算");Ui.section(groupsBox,"行业排名","点开查看成分股");Map<String,List<JSONObject>> members=new HashMap<>();for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null)members.computeIfAbsent(item.optString("industry"),key->new ArrayList<>()).add(item);}
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;String name=row.optString("name");List<JSONObject> stocks=members.getOrDefault(name,Collections.emptyList());LinearLayout card=Ui.card(groupsBox);String key="4:"+name;boolean open=industryOpen.getOrDefault(key,false);
            LinearLayout header=Ui.row(this);TextView rank=Ui.number(this,row.isNull("rank")?"—":String.format(Locale.CHINA,"%02d",row.optInt("rank")),19,Ui.BLUE);header.addView(rank,new LinearLayout.LayoutParams(dp(38),-2));LinearLayout names=Ui.column(this);add(names,text(name,18));Ui.gap(names,5);add(names,secondary(stocks.size()+"只主板成分股",12));header.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout score=Ui.column(this);TextView scoreValue=Ui.number(this,metric(row,"score"),25,Ui.INK);scoreValue.setGravity(Gravity.END);add(score,scoreValue);TextView caption=secondary(row.isNull("score")?"缺项未评分":"综合评分",11);caption.setGravity(Gravity.END);Ui.gap(score,4);add(score,caption);header.addView(score,new LinearLayout.LayoutParams(dp(68),-2));TextView arrow=secondary(open?"⌃":"⌄",18);arrow.setGravity(Gravity.END);header.addView(arrow,new LinearLayout.LayoutParams(dp(23),dp(32)));header.setMinimumHeight(dp(48));add(card,header);
            Ui.line(card);LinearLayout stats=Ui.row(this);String[] fields={"strength_score","breadth_score","up_pct"},labels={"强度","广度","今日上涨"};for(int j=0;j<fields.length;j++)stats.addView(Ui.stat(this,labels[j],metric(row,fields[j])+(j==2?"%":""),null,j==2?Ui.GREEN:Ui.INK),new LinearLayout.LayoutParams(0,-2,1));stats.addView(NotesDialogs.button(this,NotesStore.board(name,false),name,null,this::render),new LinearLayout.LayoutParams(dp(44),dp(44)));add(card,stats);
            LinearLayout body=Ui.column(this);body.setVisibility(open?View.VISIBLE:View.GONE);add(card,body);if(open)fillRotationSector(body,row,stocks);header.setContentDescription(name+"，行业排名"+(row.isNull("rank")?"未评分":row.optInt("rank"))+"，点击展开成分股");SectorAnchor anchor=new SectorAnchor(name,key,card,header,body,arrow,()->fillRotationSector(body,row,stocks));sectorAnchors.add(anchor);header.setOnClickListener(v->toggleSector(anchor,false));
        }
    }
    private void toggleSector(SectorAnchor a,boolean fromPinned){
        boolean expand=a.body.getVisibility()!=View.VISIBLE;if(expand)a.fill.run();industryOpen.put(a.key,expand);a.body.setVisibility(expand?View.VISIBLE:View.GONE);a.arrow.setText(expand?"⌃":"⌄");
        if(fromPinned&&!expand){stickySector.setVisibility(View.GONE);pinnedSector=null;scroll.post(()->{scroll.scrollTo(0,Math.max(0,a.card.getTop()));updatePinnedSector();});}
        else scroll.post(this::updatePinnedSector);
    }
    private void updatePinnedSector(){
        if(stickySector==null)return;SectorAnchor next=null;
        if(selectedModule==4)for(SectorAnchor a:sectorAnchors){int top=a.card.getTop()+a.header.getTop(),bottom=a.card.getBottom();
            if(a.body.getVisibility()==View.VISIBLE&&a.card.getHeight()>0&&top<scroll.getScrollY()&&bottom>scroll.getScrollY()){next=a;break;}}
        if(next==null){pinnedSector=null;stickySector.setVisibility(View.GONE);return;}
        if(next!=pinnedSector){pinnedSector=next;stickySector.removeAllViews();LinearLayout identity=Ui.column(this);add(identity,text(next.name,17));Ui.gap(identity,3);add(identity,secondary("正在浏览成分股",11));stickySector.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
            Button collapse=Ui.button(this,"收起 ⌃",false);collapse.setTextSize(13);collapse.setContentDescription(next.name+"悬浮收起");SectorAnchor target=next;collapse.setOnClickListener(v->toggleSector(target,true));stickySector.addView(collapse,new LinearLayout.LayoutParams(dp(88),dp(44)));}
        stickySector.setVisibility(View.VISIBLE);stickySector.setTranslationY(Math.min(0,next.card.getBottom()-scroll.getScrollY()-stickySector.getHeight()));
    }
    private void reloadStockMarks(){
        try{Map<String,Boolean> updated=new HashMap<>();for(JSONObject item:WatchlistStore.stocks(this))updated.put(item.optString("code"),item.optBoolean("starred",false));starredCodes.clear();starredCodes.putAll(updated);}catch(Exception ignored){}
        try{Set<String> updated=new HashSet<>();for(JSONObject item:HoldingsStore.stocks(this))updated.add(item.optString("code"));heldCodes.clear();heldCodes.addAll(updated);}catch(Exception ignored){}
    }
    private void applyStockMark(View box,String code){
        boolean starred=Boolean.TRUE.equals(starredCodes.get(code)),tracked=starredCodes.containsKey(code)||heldCodes.contains(code);
        int color=starred?Ui.PINK:(selectedModule==4||selectedModule==5)&&tracked?Ui.PINK_SOFT:Ui.STOCK_BG;
        box.setBackground(Ui.shape(this,color,12));
        if(Build.VERSION.SDK_INT>=30)box.setStateDescription(starred?"已收藏，优先关注":starredCodes.containsKey(code)?"已自选":heldCodes.contains(code)?"已持仓":"未加入自选或持仓");
    }
    private void rotationStockRow(LinearLayout parent,JSONObject item){compactStockRow(parent,item,null);}
    private void fillRotationSector(LinearLayout body,JSONObject row,List<JSONObject> stocks){if(body.getChildCount()>0)return;Ui.line(body);Ui.pair(body,Ui.stat(this,"5日相对强度",metric(row,"relative_5d"),"百分点",Ui.INK),Ui.stat(this,"20日相对强度",metric(row,"relative_20d"),"百分点",Ui.INK));Ui.gap(body,12);add(body,secondary("5日跑赢市场 "+metric(row,"outperform_5d_pct")+"% · 站上MA20 "+metric(row,"above_ma20_pct")+"%",12));Ui.gap(body,6);add(body,secondary("有效样本：5日 "+row.optInt("covered_5d")+" · 20日 "+row.optInt("covered_20d")+" · MA20 "+row.optInt("ma20_covered"),11));Ui.gap(body,12);boardButton(body,row.optString("name"));Ui.gap(body,5);for(JSONObject stock:stocks)stockRow(body,stock,null);}
    private void industryGroup(String key,String industry,List<JSONObject> stocks,JSONObject quotes,boolean defaultOpen){
        industryGroup(key,industry,stocks,defaultOpen,(body,item)->stockRow(body,item,quotes==null?null:quotes.optJSONObject(item.optString("code"))));
    }
    private void industryGroup(String key,String industry,List<JSONObject> stocks,boolean defaultOpen,java.util.function.BiConsumer<LinearLayout,JSONObject> rowRenderer){LinearLayout card=Ui.card(groupsBox);card.setTag("industry-group:"+key);boolean open=industryOpen.getOrDefault(key,defaultOpen);LinearLayout header=Ui.row(this);LinearLayout labels=Ui.column(this);add(labels,text(industry,18));if(selectedModule==2||selectedModule==6){Ui.gap(labels,4);add(labels,secondary(industryRanking.label(industry),11));}header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));header.addView(Ui.pill(this,stocks.size()+"只",Ui.SECONDARY,Ui.BG));TextView arrow=secondary(open?"⌃":"⌄",18);arrow.setGravity(Gravity.END);header.addView(arrow,new LinearLayout.LayoutParams(dp(28),dp(32)));header.setMinimumHeight(dp(44));header.setContentDescription(industry+"，"+stocks.size()+"只股票，点击展开或收起");add(card,header);
        LinearLayout body=Ui.column(this);body.setTag("industry-body:"+key);body.setVisibility(open?View.VISIBLE:View.GONE);add(card,body);Runnable fill=()->{if(body.getChildCount()>0)return;Ui.line(body);if(!industry.equals("待分类"))boardButton(body,industry);for(JSONObject item:stocks)rowRenderer.accept(body,item);};if(open)fill.run();header.setOnClickListener(v->{boolean expand=body.getVisibility()!=View.VISIBLE;if(expand)fill.run();industryOpen.put(key,expand);body.setVisibility(expand?View.VISIBLE:View.GONE);arrow.setText(expand?"⌃":"⌄");});}
    private void boardButton(LinearLayout parent,String industry){LinearLayout row=Ui.row(this);Button button=Ui.button(this,industry+" · 板块日K  ›",false);button.setContentDescription(industry+"板块日K，内置快照，可选联网更新");button.setOnClickListener(v->startActivity(new Intent(this,BoardChartActivity.class).putExtra(BoardChartActivity.EXTRA_INDUSTRY,industry)));row.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));row.addView(NotesDialogs.button(this,NotesStore.board(industry,false),industry,null,this::render),new LinearLayout.LayoutParams(dp(52),dp(44)));add(parent,row);}
    private void stockRow(LinearLayout parent,JSONObject item,JSONObject quote){compactStockRow(parent,item,quote);}
    private double lowGain(JSONObject item,JSONObject quote){
        if(quote!=null&&quote.has("return_30d_pct"))return quote.optDouble("return_30d_pct",Double.NaN);
        JSONObject source=rotationByCode.getOrDefault(item.optString("code"),item);
        if(quote!=null){if(!quote.optString("quote_time").startsWith(rotation.optString("trade_date","no-date")))return Double.NaN;double low=source.optDouble("return_low_price",Double.NaN),price=quote.optDouble("latest_price",Double.NaN);return low>0&&price>0?(price/low-1)*100:Double.NaN;}
        return source.optDouble("return_30d_pct",Double.NaN);
    }
    private void compactStockRow(LinearLayout parent,JSONObject item,JSONObject quote){
        compactStockRow(parent,item,quote,null);
    }
    private void compactStockRow(LinearLayout parent,JSONObject item,JSONObject quote,JSONObject signal){
        String code=item.optString("code"),name=item.optString("name");LinearLayout box=Ui.column(this);box.setTag((selectedModule==4?"rotation-stock:":"personal-stock:")+code);box.setPadding(dp(10),dp(7),dp(10),dp(7));applyStockMark(box,code);LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.setMargins(0,dp(8),0,0);parent.addView(box,margin);
        LinearLayout top=Ui.row(this);top.setTag("stock-identity:"+code);TextView title=text(name,14);Ui.compact(title);if(selectedModule==6)title.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);top.addView(title,new LinearLayout.LayoutParams(0,dp(27),1));title.setGravity(Gravity.CENTER_VERTICAL);
        if(selectedModule==6)addRiskRatio(top,item,quote,signal);
        TextView ticker=secondary(code,11);ticker.setSingleLine(true);ticker.setHorizontallyScrolling(false);ticker.setGravity(Gravity.CENTER);top.addView(ticker,new LinearLayout.LayoutParams(dp(selectedModule==6?44:53),dp(27)));
        double gain=lowGain(item,quote);TextView value=Ui.text(this,Double.isFinite(gain)?String.format(Locale.CHINA,"%+.2f%%",gain):"—",15,Double.isFinite(gain)?gain>=0?Ui.RED:Ui.GREEN:Ui.SECONDARY,true);value.setSingleLine(true);value.setHorizontallyScrolling(false);value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);value.setAutoSizeTextTypeUniformWithConfiguration(10,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);value.setContentDescription(name+"低点至今涨幅");top.addView(value,new LinearLayout.LayoutParams(dp(selectedModule==6?61:77),dp(27)));
        if(selectedModule==2){Button priority=Ui.button(this,Boolean.TRUE.equals(starredCodes.get(code))?"★":"☆",false);priority.setTextColor(Ui.PINK_INK);priority.setPadding(0,0,0,0);priority.setMinHeight(0);priority.setMinimumHeight(0);priority.setContentDescription(name+(Boolean.TRUE.equals(starredCodes.get(code))?"取消收藏":"添加收藏"));priority.setOnClickListener(v->{try{WatchlistStore.setStarred(this,code,!Boolean.TRUE.equals(starredCodes.get(code)));render();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});LinearLayout.LayoutParams star=new LinearLayout.LayoutParams(dp(26),dp(27));star.setMargins(dp(3),0,0,0);top.addView(priority,star);}
        add(box,top);Ui.gap(box,4);
        LinearLayout actions=Ui.row(this);actions.setTag("stock-actions:"+code);Button chart=Ui.button(this,"15分钟/日K",false),favorite=Ui.button(this,"＋自选",false),holding=Ui.button(this,"＋持仓",false);
        chart.setContentDescription(name+"日K与15分钟K线");chart.setOnClickListener(v->openStockChart(item));updateFavorite(favorite,item);favorite.setContentDescription(name+"自选操作");favorite.setOnClickListener(v->{toggleFavorite(favorite,item);favorite.setContentDescription(name+"自选操作");reloadStockMarks();applyStockMark(box,code);});
        boolean exists=heldCodes.contains(code);holding.setText(exists?"已持仓":"＋持仓");holding.setContentDescription(name+(exists?"设置止损线":"加入持仓"));holding.setOnClickListener(v->{try{if(HoldingsStore.find(this,code)==null)HoldingDialogs.add(this,code,name,WatchlistStore.industry(item,c->industryCatalog==null?"待分类":industryCatalog.industry(c)),this::render);else HoldingDialogs.editStop(this,code,this::render);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});
        holding.setOnLongClickListener(v->{if(!heldCodes.contains(code))return false;HoldingDialogs.manage(this,code,name,this::render);return true;});
        for(Button button:new Button[]{chart,favorite,holding}){button.setMaxLines(1);button.setHorizontallyScrolling(false);button.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);button.setMinHeight(dp(32));button.setMinimumHeight(dp(32));button.setPadding(dp(2),dp(4),dp(2),dp(4));LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,dp(32),button==chart?1.4f:1);cell.setMargins(0,0,dp(3),0);actions.addView(button,cell);}add(box,actions);Ui.gap(box,5);addConceptTags(box,item);
        if(selectedModule==5){addHoldingRisk(box,item);String stop=HoldingsStore.stopText(item,quote);boolean touched=quote!=null&&quote.optDouble("latest_price",Double.POSITIVE_INFINITY)<=item.optDouble("stop_price",Double.NEGATIVE_INFINITY);Ui.gap(box,5);add(box,Ui.text(this,stop,11,touched?Ui.RED:Ui.SECONDARY,touched));}
        box.setContentDescription(name+" "+code+"，涨幅口径：最近30交易日低点至今"+(quote==null?"":"，行情时间 "+quote.optString("quote_time")));box.setOnClickListener(v->openStockChart(item));
    }
    private void addConceptTags(LinearLayout parent,JSONObject item){
        LinearLayout line=Ui.row(this);HorizontalScrollView strip=new HorizontalScrollView(this);strip.setTag("concept-tags:"+item.optString("code"));strip.setHorizontalScrollBarEnabled(false);strip.setContentDescription("概念标签，按强度广度综合排名排列，可左右滑动");LinearLayout tags=Ui.row(this);strip.addView(tags);line.addView(strip,new LinearLayout.LayoutParams(0,-2,1));Button notes=NotesDialogs.button(this,NotesStore.stock(item.optString("code")),item.optString("name"),item.optString("code"),this::render);notes.setMinHeight(dp(30));notes.setMinimumHeight(dp(30));line.addView(notes,new LinearLayout.LayoutParams(dp(44),dp(30)));add(parent,line);
        List<JSONObject> rows=conceptCatalog==null?Collections.emptyList():conceptCatalog.tags(item.optString("code"));
        if(rows.isEmpty()){add(tags,secondary(conceptCatalog==null?"概念数据暂不可用":"暂无概念归属",11));return;}
        for(JSONObject row:rows){String name=row.optString("name");TextView tag=Ui.pill(this,conceptCatalog.tagText(row),Ui.BLUE,Ui.TINT);tag.setSingleLine(true);tag.setHorizontallyScrolling(false);tag.setGravity(Gravity.CENTER);tag.setMinHeight(dp(30));tag.setClickable(true);tag.setFocusable(true);tag.setContentDescription(name+"概念日K，排名"+conceptCatalog.tagText(row)+"，行情日"+conceptCatalog.tradeDate());tag.setOnClickListener(v->startActivity(new Intent(this,BoardChartActivity.class).putExtra(BoardChartActivity.EXTRA_INDUSTRY,name).putExtra(BoardChartActivity.EXTRA_TYPE,"concept")));LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(-2,dp(30));cell.setMargins(0,0,dp(6),0);tags.addView(tag,cell);}
    }
    private void openStockChart(JSONObject item){String code=item.optString("code");if(!code.matches("\\d{6}"))return;startActivity(new Intent(this,StockChartActivity.class).putExtra(StockChartActivity.EXTRA_CODE,code).putExtra(StockChartActivity.EXTRA_NAME,item.optString("name")));}
    private void updateFavorite(Button button,JSONObject item){try{button.setText(WatchlistStore.contains(this,item.optString("code"))?"移出自选":"＋ 自选");button.setContentDescription(item.optString("name")+button.getText());}catch(Exception error){button.setText("读取失败");button.setEnabled(false);}}
    private void toggleFavorite(Button button,JSONObject item){try{String code=item.optString("code");boolean exists=WatchlistStore.contains(this,code);if(exists)WatchlistStore.remove(this,code);else WatchlistStore.add(this,code,item.optString("name"),WatchlistStore.industry(item,c->industryCatalog==null?"待分类":industryCatalog.industry(c)));updateFavorite(button,item);lastWatchlistPayload=WatchlistStore.payload(this);Toast.makeText(this,exists?"已移出自选":"已加入自选",Toast.LENGTH_SHORT).show();if(selectedModule==2||selectedModule==6)render();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}
    private void renderWatchlist(){LinearLayout action=Ui.row(this);action.setPadding(dp(3),dp(8),dp(3),dp(16));action.addView(text("我的股票",20),new LinearLayout.LayoutParams(0,-2,1));Button manual=Ui.button(this,"＋ 添加",false);manual.setOnClickListener(v->showAddWatchlist());action.addView(manual,new LinearLayout.LayoutParams(dp(92),dp(44)));add(groupsBox,action);
        if(!watchlistImportError.isEmpty())notice("网页名单导入未完成",watchlistImportError+"；保留手机原有自选，下次打开重试。");
        try{List<JSONObject> stocks=WatchlistStore.stocks(this);JSONObject quotes=new JSONObject(getSharedPreferences("scan",MODE_PRIVATE).getString("favorite_quote_payload","{}"));String error=getSharedPreferences("scan",MODE_PRIVATE).getString("favorite_quote_error","");if(!error.isEmpty())notice("部分行情暂未更新",error+"；保留上次报价。");if(stocks.isEmpty()){LinearLayout card=Ui.card(groupsBox);Ui.gap(card,14);add(card,text("关注你的股票",20));Ui.gap(card,10);add(card,secondary("在板块列表中加自选，或点击上方“添加”输入六位股票代码。",14));Ui.gap(card,14);return;}
            Map<String,List<JSONObject>> industries=WatchlistStore.grouped(stocks,code->industryCatalog==null?"待分类":industryCatalog.industry(code));add(groupsBox,secondary(stocks.size()+"只股票 · "+industries.size()+"个行业 · 收藏优先",12));Ui.gap(groupsBox,5);add(groupsBox,secondary(industryRanking.note(),12));Ui.gap(groupsBox,12);for(Map.Entry<String,List<JSONObject>> sector:industryRanking.ordered(industries))industryGroup("2:"+sector.getKey(),sector.getKey(),WatchlistStore.priorityFirst(sector.getValue()),quotes,true);
        }catch(Exception error){notice("自选读取失败",String.valueOf(error.getMessage()));}}
    private void renderHoldings(){
        LinearLayout action=Ui.row(this);action.setPadding(dp(3),dp(8),dp(3),dp(16));action.addView(text("我的持仓",20),new LinearLayout.LayoutParams(0,-2,1));Button manual=Ui.button(this,"＋ 添加",false);manual.setOnClickListener(v->showAddWatchlist());action.addView(manual,new LinearLayout.LayoutParams(dp(92),dp(44)));add(groupsBox,action);
        try{List<JSONObject> stocks=HoldingsStore.stocks(this);JSONObject quotes=new JSONObject(getSharedPreferences("scan",0).getString("favorite_quote_payload","{}"));String error=getSharedPreferences("scan",0).getString("favorite_quote_error","");if(!error.isEmpty())notice("部分行情暂未更新",error+"；下方保留上次报价，请核对行情时间。");
            if(stocks.isEmpty()){LinearLayout card=Ui.card(groupsBox);add(card,text("添加你的持仓",20));Ui.gap(card,10);add(card,secondary("可从自选或板块成员加入，也可输入股票代码。加入后可设置止损线。",14));return;}
            Map<String,List<JSONObject>> industries=WatchlistStore.grouped(stocks,c->industryCatalog==null?"待分类":industryCatalog.industry(c));add(groupsBox,secondary(stocks.size()+"只持仓 · 本机记录",12));Ui.gap(groupsBox,12);
            for(Map.Entry<String,List<JSONObject>> sector:industries.entrySet())industryGroup("5:"+sector.getKey(),sector.getKey(),sector.getValue(),quotes,true);
        }catch(Exception e){notice("持仓读取失败",String.valueOf(e.getMessage()));}
    }
    private String frameTime(JSONObject frame){if(frame==null)return "—";String a=frame.optString("start_time"),b=frame.optString("end_time");return (a.length()>=16?a.substring(11):a)+(a.equals(b)?"":"—"+(b.length()>=16?b.substring(11):b));}
    private void renderReviews(){try{
        List<JSONObject> cycles=TradeJournal.reviews(this);Map<String,List<JSONObject>> stocks=new LinkedHashMap<>();for(JSONObject cycle:cycles)stocks.computeIfAbsent(cycle.optString("code"),k->new ArrayList<>()).add(cycle);
        add(groupsBox,secondary(stocks.size()+"只个股 · "+cycles.size()+"段已结束持仓 · 买卖逻辑齐全",12));Ui.gap(groupsBox,12);
        if(cycles.isEmpty()){LinearLayout card=Ui.card(groupsBox);add(card,text("等待完整的买卖记录",20));Ui.gap(card,10);add(card,secondary("加入持仓时选填买入逻辑，移出时选填卖出逻辑。同一段持仓结束且两种逻辑齐全后自动进入复盘；可在个股笔记的买卖逻辑记录中补填。",14));return;}
        for(Map.Entry<String,List<JSONObject>> entry:stocks.entrySet()){JSONObject item=entry.getValue().get(0);String code=entry.getKey(),name=item.optString("name",code);LinearLayout card=Ui.card(groupsBox);card.setTag("review-stock:"+code);LinearLayout head=Ui.row(this);TextView identity=text(name,19);Ui.compact(identity);head.addView(identity,new LinearLayout.LayoutParams(0,-2,1));head.addView(secondary(code,12));add(card,head);Ui.gap(card,5);add(card,secondary(item.optString("industry","待分类")+" · "+entry.getValue().size()+"段记录",12));Ui.gap(card,10);LinearLayout actions=Ui.row(this);Button chart=Ui.button(this,"15分钟/日K",false),logic=Ui.button(this,"买卖逻辑",false),notes=NotesDialogs.button(this,NotesStore.stock(code),name,code,this::render);chart.setOnClickListener(v->openStockChart(item));logic.setOnClickListener(v->NotesDialogs.journal(this,code,name,this::render));for(Button b:new Button[]{chart,logic,notes}){b.setTextSize(12);b.setPadding(dp(3),0,dp(3),0);actions.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));}add(card,actions);
            for(JSONObject cycle:entry.getValue()){Ui.line(card);add(card,Ui.text(this,cycle.optString("opened_date")+" → "+cycle.optString("closed_date"),13,Ui.BLUE,true));for(String kind:new String[]{"buy_notes","sell_notes"}){Ui.gap(card,10);add(card,text(kind.equals("buy_notes")?"买入逻辑":"卖出逻辑",14));Ui.gap(card,5);LinearLayout entries=Ui.column(this);NotesDialogs.history(entries,cycle.getJSONArray(kind),"");add(card,entries);}}
        }
    }catch(Exception e){notice("复盘记录读取失败",String.valueOf(e.getMessage()));}}
    private void addRiskRatio(LinearLayout top,JSONObject item,JSONObject quote,JSONObject signal){
        JSONObject risk=quote==null?(signal==null?null:signal.optJSONObject("risk_ratio")):quote.optJSONObject("risk_ratio");
        if(risk!=null&&quote!=null&&risk.optBoolean("available")&&!risk.optString("quote_time").equals(quote.optString("quote_time")))risk=null;
        final JSONObject data=risk;boolean available=data!=null&&data.optBoolean("available");String value=available?String.format(Locale.CHINA,"%.2f",data.optDouble("ratio")):"—";
        TextView label=Ui.text(this,"盈亏比"+value,11,Ui.BLUE,true);label.setTag("signal-ratio:"+item.optString("code"));label.setSingleLine(true);label.setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);label.setGravity(Gravity.CENTER);label.setContentDescription(item.optString("name")+"盈亏比"+value+"，点击查看参考点");label.setClickable(true);label.setFocusable(true);top.addView(label,new LinearLayout.LayoutParams(dp(68),dp(27)));
        label.setOnClickListener(v->{String detail=RiskRatio.FORMULA+"\n\n";if(data==null)detail+="参考点尚未更新，请刷新行情。";else {detail+=available?String.format(Locale.CHINA,"盈亏比 %.4f",data.optDouble("ratio")):data.optString("reason","参考点未就绪");if(data.has("entry"))detail+=String.format(Locale.CHINA,"\n上车点 %.2f\n止损点 %.2f\n止盈点 %.2f",data.optDouble("entry"),data.optDouble("stop"),data.optDouble("target"));if(!data.optString("quote_time").isEmpty())detail+="\n"+data.optString("phase")+" · "+data.optString("quote_time");}new AlertDialog.Builder(this).setTitle(item.optString("name")+" · 盈亏比").setMessage(detail).setPositiveButton("知道了",null).show();});
    }
    private void openSignal(JSONObject item,JSONObject frame,boolean acknowledge){
        if(acknowledge)try{PersonalSignalStore.acknowledge(this,item.optString("code"));}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
        startActivity(HoldingRiskNotifier.chartIntent(this,item.optString("code"),item.optString("name"),frame));
    }
    private void renderRiskBanner(){int unread=PersonalSignalStore.unreadHoldings(this);if(unread==0)return;
        LinearLayout card=Ui.card(groupsBox);card.setPadding(dp(14),dp(12),dp(14),dp(12));card.setBackground(Ui.shape(this,Ui.PINK_SOFT,16));TextView text=Ui.text(this,"持仓减仓提醒 · "+unread+"只未读   查看 ›",14,Ui.RED,true);add(card,text);card.setContentDescription("查看持仓减仓提醒");card.setOnClickListener(v->{selectedModule=6;render();update();scroll.scrollTo(0,0);});
    }
    private void addHoldingRisk(LinearLayout box,JSONObject item){String code=item.optString("code");JSONArray frames=PersonalSignalStore.reductions(this,code);if(frames.length()==0)return;
        JSONObject row=PersonalSignalStore.records(this).optJSONObject(code),frame=frames.optJSONObject(frames.length()-1);Ui.gap(box,5);TextView warning=Ui.pill(this,(row.optBoolean("stale")?"上次":"")+"减仓提醒 · "+shortDate(row.optString("trade_date"))+" "+frameTime(frame)+(PersonalSignalStore.unread(this,code)?" · 未读":" · 已读"),Ui.RED,Ui.PINK_SOFT);warning.setTag("holding-risk:"+code);warning.setOnClickListener(v->openSignal(item,frame,true));add(box,warning);
    }
    private void renderSignals(){
        JSONObject payload=PersonalSignalStore.payload(this),records=PersonalSignalStore.records(this);String date=payload.optString("trade_date"),error=PersonalSignalStore.prefs(this).getString("error","");
        try{List<JSONObject> candidates=new ArrayList<>(),singles=new ArrayList<>(),holdings=HoldingsStore.stocks(this);boolean oldRules=false;
            for(JSONObject s:WatchlistStore.priorityFirst(WatchlistStore.stocks(this))){JSONObject r=records.optJSONObject(s.optString("code"));if(r==null)continue;if(r.optInt("rule_version")<PersonalSignalRule.VERSION)oldRules=true;if(r.optBoolean("qualifies"))candidates.add(s);else if(r.optBoolean("qualifies_single"))singles.add(s);}
            int risks=0;for(JSONObject s:holdings)if(PersonalSignalStore.reductions(this,s.optString("code")).length()>0)risks++;
            summary(String.valueOf(candidates.size()+singles.size()),"只入选 · 两连 "+candidates.size()+" / 单进攻 "+singles.size(),date,"前日收阴或下跌 · 当日15分钟进攻实线框");
            if(oldRules)notice("筛选条件已更新","当前部分结果来自旧缓存，请点击右上角重新检查阴线或下跌及单进攻条件。");
            if(!error.isEmpty())notice("本次检查未完成",error+"；下方为上次结果，请核对交易日。");
            JSONArray errors=payload.optJSONArray("errors");if(errors!=null&&errors.length()>0){StringBuilder detail=new StringBuilder();for(int i=0;i<Math.min(3,errors.length());i++)detail.append(errors.optString(i)).append("\n");notice(errors.length()+"只股票数据暂不完整",detail.toString().trim()+"；缓存结果会标明，缺失股票不判定为无信号。");}
            if(!((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled()){LinearLayout card=Ui.card(groupsBox);add(card,secondary("系统通知未开启，减仓提醒仍显示在应用内。",12));Ui.gap(card,8);Button settings=Ui.button(this,"开启系统通知",false);settings.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));add(card,settings);}
            Ui.section(groupsBox,"持仓减仓提醒",risks+"只");
            if(risks==0){LinearLayout card=Ui.card(groupsBox);add(card,secondary(date.isEmpty()?"检查后显示最新交易日的实线减仓框。":"尚未记录 "+date+" 的持仓实线减仓框；请同时核对检查状态。",12));}
            for(JSONObject s:holdings){String code=s.optString("code");JSONArray frames=PersonalSignalStore.reductions(this,code);if(frames.length()==0)continue;JSONObject row=records.optJSONObject(code),frame=frames.getJSONObject(frames.length()-1);LinearLayout card=Ui.card(groupsBox);card.setTag("signal-risk:"+code);
                add(card,Ui.text(this,s.optString("name")+"  "+code+(PersonalSignalStore.unread(this,code)?" · 未读":" · 已读"),16,Ui.RED,true));Ui.gap(card,7);add(card,secondary(row.optString("trade_date")+" · "+frames.length()+"个实线减仓框 · 最新 "+frameTime(frame)+(row.optBoolean("stale")?" · 上次缓存":""),12));Ui.gap(card,10);
                LinearLayout actions=Ui.row(this);Button open=Ui.button(this,"查看15分钟减仓",false),ack=Ui.button(this,"标为已读",false);for(Button b:new Button[]{open,ack}){b.setMaxLines(1);b.setHorizontallyScrolling(false);b.setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);b.setPadding(dp(8),dp(4),dp(8),dp(4));}actions.addView(open,new LinearLayout.LayoutParams(0,dp(40),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(88),dp(40));p.setMargins(dp(8),0,0,0);actions.addView(ack,p);actions.addView(NotesDialogs.button(this,NotesStore.stock(code),s.optString("name"),code,this::render),new LinearLayout.LayoutParams(dp(44),dp(40)));add(card,actions);open.setOnClickListener(v->openSignal(s,frame,true));ack.setOnClickListener(v->{try{PersonalSignalStore.acknowledge(this,code);render();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}});
            }
            JSONObject quotes=new JSONObject(getSharedPreferences("scan",0).getString("favorite_quote_payload","{}"));
            add(groupsBox,secondary(industryRanking.note()+" · 行业内收藏优先",12));Ui.gap(groupsBox,6);
            signalPart(candidates,quotes,records,date,true);signalPart(singles,quotes,records,date,false);
        }catch(Exception e){notice("信号读取失败",String.valueOf(e.getMessage()));}
    }
    private void signalPart(List<JSONObject> stocks,JSONObject quotes,JSONObject records,String date,boolean pair){
        Map<String,List<JSONObject>> groups=WatchlistStore.grouped(stocks,code->industryCatalog==null?"待分类":industryCatalog.industry(code));
        TextView heading=Ui.section(groupsBox,pair?"第一部分 · 两连进攻":"第二部分 · 单进攻",stocks.size()+"只 · "+groups.size()+"个行业");heading.setTextSize(18);heading.setTag(pair?"signal-part:pair":"signal-part:single");
        add(groupsBox,secondary(pair?"前日收阴或下跌 · 连续两个进攻实线框":"前日收阴或下跌 · 已有进攻实线框，尚未两连",12));Ui.gap(groupsBox,10);
        if(stocks.isEmpty()){LinearLayout card=Ui.card(groupsBox);add(card,secondary(date.isEmpty()?"点击右上角检查，符合条件的自选股自动进入。":"尚未记录 "+date+" 的合格信号；只统计已结束K线。",12));}
        for(Map.Entry<String,List<JSONObject>> sector:industryRanking.ordered(groups))industryGroup((pair?"6:":"6:single:")+sector.getKey(),sector.getKey(),WatchlistStore.priorityFirst(sector.getValue()),true,(body,item)->signalCandidateRow(body,item,quotes.optJSONObject(item.optString("code")),records.optJSONObject(item.optString("code")),pair));
    }
    private void signalCandidateRow(LinearLayout parent,JSONObject item,JSONObject quote,JSONObject row,boolean pair){
        LinearLayout box=Ui.column(this);box.setTag((pair?"signal-candidate:":"signal-single:")+item.optString("code"));add(parent,box);compactStockRow(box,item,quote,row);Ui.gap(box,8);
        boolean bearish=row.optBoolean("previous_bearish",row.optDouble("previous_close")<row.optDouble("previous_open")),falling=row.optBoolean("previous_falling");
        String prior=(row.optBoolean("stale")?"上次缓存 · ":"")+row.optString("previous_date")+(bearish?falling?" 阴线且下跌":" 阴线":" 下跌");
        if(bearish)prior+=String.format(Locale.CHINA,"：%.2f → %.2f",row.optDouble("previous_open"),row.optDouble("previous_close"));
        if(falling){double before=row.optDouble("comparison_close"),close=row.optDouble("previous_close");if(before>0)prior+=String.format(Locale.CHINA," · 较前收 %+.2f%%",(close/before-1)*100);}
        add(box,secondary(prior,12));Ui.gap(box,4);
        JSONObject first=row.optJSONObject("first_attack"),target=row.optJSONObject(pair?"second_attack":"single_attack");
        if(target==null||(pair&&first==null)){add(box,secondary("进攻时段缺失，请重新检查。",12));return;}
        TextView proof=Ui.text(this,pair?"进攻① "+frameTime(first)+"   进攻② "+frameTime(target)+"   查看 ›":"进攻 "+frameTime(target)+"   查看15分钟 ›",12,Ui.BLUE,true);proof.setMinHeight(dp(32));proof.setContentDescription(item.optString("name")+(pair?"连续进攻组合":"单个进攻框")+"，定位15分钟");proof.setOnClickListener(v->openSignal(item,target,false));add(box,proof);Ui.gap(box,8);
    }
    private void showAddWatchlist(){boolean holding=selectedModule==5;EditText input=new EditText(this);input.setSingleLine(true);input.setHint("六位沪深代码，例如 600519");input.setTextSize(17);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);LinearLayout wrapper=Ui.column(this);wrapper.setPadding(dp(24),dp(12),dp(24),dp(12));add(wrapper,input);EditText buyLogic=new EditText(this);if(holding){buyLogic.setHint("买入逻辑（选填）");buyLogic.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);buyLogic.setMinLines(2);add(wrapper,buyLogic);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(holding?"添加持仓股":"添加自选股").setView(wrapper).setNegativeButton("取消",null).setPositiveButton("添加",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String code=input.getText().toString().trim();if(!code.matches("(000|001|002|003|300|301|600|601|603|605|688|689)\\d{3}")){input.setError("请输入有效的六位沪深股票代码");return;}Button button=dialog.getButton(AlertDialog.BUTTON_POSITIVE);button.setEnabled(false);button.setText("查询中…");
            new Thread(()->{try{TencentClient.Quote quote=TencentClient.quote(code);runOnUiThread(()->{if(isFinishing()||isDestroyed()||!dialog.isShowing())return;try{if(holding)HoldingsStore.add(this,code,quote.name,industryCatalog==null?"待分类":industryCatalog.industry(code),buyLogic.getText().toString());else WatchlistStore.add(this,code,quote.name,industryCatalog==null?"待分类":industryCatalog.industry(code));lastWatchlistPayload=WatchlistStore.payload(this);dialog.dismiss();render();PersonalQuotes.refresh(this);Toast.makeText(this,holding?"已加入持仓":"已加入自选",Toast.LENGTH_SHORT).show();}catch(Exception error){button.setEnabled(true);button.setText("添加");input.setError(error.getMessage());}});}catch(Exception error){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;button.setEnabled(true);button.setText("添加");input.setError("查询失败："+error.getMessage());});}},"watchlist-add").start();}));dialog.show();}
    private void update(){SharedPreferences prefs=getSharedPreferences("scan",MODE_PRIVATE);String marketPayload=prefs.getString("market_payload","{}"),rotationPayload=prefs.getString("rotation_payload","{}");String holdings=HoldingsStore.payload(this),favorites=WatchlistStore.payload(this),quotes=prefs.getString("favorite_quote_payload","{}")+prefs.getString("favorite_quote_error",""),marketError=prefs.getString("market_error","");
        String notes=NotesStore.payload(this),journal=TradeJournal.payload(this);if(!notes.equals(lastNotesPayload)||!journal.equals(lastJournalPayload)){lastNotesPayload=notes;lastJournalPayload=journal;render();}
        if(!holdings.equals(lastHoldingsPayload)||!marketPayload.equals(lastMarketPayload)||!rotationPayload.equals(lastRotationPayload)||!favorites.equals(lastWatchlistPayload)||!quotes.equals(lastQuotePayload)||!marketError.equals(lastMarketError)){lastHoldingsPayload=holdings;lastMarketPayload=marketPayload;lastRotationPayload=rotationPayload;lastWatchlistPayload=favorites;lastQuotePayload=quotes;lastMarketError=marketError;try{market=new JSONObject(marketPayload);rotation=new JSONObject(rotationPayload);}catch(JSONException ignored){}render();}
        String conceptPayload=prefs.getString("concept_payload","");if(!conceptPayload.equals(lastConceptPayload)){lastConceptPayload=conceptPayload;render();}
        String signalState=PersonalSignalStore.prefs(this).getString("payload","")+PersonalSignalStore.prefs(this).getString("ack","")+PersonalSignalStore.prefs(this).getString("error","");if(!signalState.equals(lastSignalPayload)){lastSignalPayload=signalState;render();}
        String state=prefs.getString("status","点击右上角刷新行情");boolean busy=selectedModule==6?PersonalSignalStore.prefs(this).getBoolean("busy",false):selectedModule==3?prefs.getBoolean("market_busy",false):(selectedModule==2||selectedModule==5)?prefs.getBoolean("personal_busy",false):state.startsWith("正在")||state.startsWith("补齐");scan.setEnabled(!busy);scan.setAlpha(busy?.4f:1f);if(selectedModule==3)status.setText(busy?"正在更新上证指数…":!marketError.isEmpty()?"更新未完成 · 显示上次成功数据":"上证指数 · 位置与仓位 · 两层震荡区间");else if(selectedModule==2||selectedModule==5)status.setText(busy?"正在更新自选与持仓…":"本机名单 · 行情时间以每只股票为准");else if(selectedModule==6)status.setText(busy?PersonalSignalStore.prefs(this).getString("status","正在检查…"):"自选派生 · 实线行为 · 持仓减仓提醒");else status.setText(state);
        if(selectedModule==3)snapshotAt.setText("更新于 "+market.optString("updated_at","尚无数据")+"  ·  每10分钟");else{long updated=selectedModule==6?PersonalSignalStore.prefs(this).getLong("updated",0):prefs.getLong(selectedModule==2||selectedModule==5?"personal_updated":"updated",0);SimpleDateFormat f=new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));snapshotAt.setText((updated>0?"更新于 "+f.format(new Date(updated)):"09:00—15:30 前台自动更新")+"  ·  每10分钟");}
        if(selectedModule==7){scan.setEnabled(true);scan.setAlpha(1);status.setText("已结束持仓 · 买卖逻辑成对留存");snapshotAt.setText("笔记按北京时间日期追加 · 本机保存");}
    }
}
