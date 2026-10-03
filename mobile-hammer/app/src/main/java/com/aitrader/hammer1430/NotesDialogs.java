package com.aitrader.hammer1430;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.*;

final class NotesDialogs {
    static Button button(Activity a,String key,String name,String code,Runnable changed){Button b=Ui.button(a,"笔记",false);b.setTag("notes:"+key);b.setTextSize(12);b.setPadding(Ui.dp(a,3),0,Ui.dp(a,3),0);b.setContentDescription(name+"添加或查看笔记");b.setOnClickListener(v->show(a,key,name,code,changed));return b;}
    private static EditText input(Activity a,String hint){EditText v=new EditText(a);v.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);v.setMinLines(3);v.setMaxLines(6);v.setHint(hint);v.setTextSize(15);return v;}
    private static LinearLayout content(Activity a){LinearLayout c=Ui.column(a);c.setPadding(Ui.dp(a,20),Ui.dp(a,8),Ui.dp(a,20),Ui.dp(a,12));return c;}
    static void history(LinearLayout body,JSONArray rows,String empty)throws Exception {body.removeAllViews();if(rows.length()==0)Ui.add(body,Ui.text(body.getContext(),empty,13,Ui.SECONDARY,false));for(int i=0;i<rows.length();i++){if(i>0)Ui.gap(body,10);TextView text=Ui.text(body.getContext(),NotesStore.line(rows.getJSONObject(i)),14,Ui.INK,false);text.setTextIsSelectable(true);Ui.add(body,text);}}
    static void show(Activity a,String key,String name,String code,Runnable changed){try{
        LinearLayout c=content(a),history=Ui.column(a);ScrollView scroll=new ScrollView(a);scroll.addView(history);c.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(a,210)));history(history,NotesStore.notes(a,key),"暂无笔记，追加后保留全部历史。");Ui.gap(c,10);Ui.add(c,Ui.text(a,"自动记录北京时间日期 · "+NotesStore.today(),12,Ui.SECONDARY,false));EditText input=input(a,"输入新的笔记正文");Ui.add(c,input);
        AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle(name+" · 笔记").setView(c).setNegativeButton("关闭",null).setPositiveButton("追加笔记",null);
        if(code!=null&&(!TradeJournal.forStock(a,code).isEmpty()||HoldingsStore.find(a,code)!=null))builder.setNeutralButton("买卖逻辑",(d,w)->journal(a,code,name,changed));
        AlertDialog dialog=builder.create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{NotesStore.append(a,key,input.getText().toString());input.setText("");history(history,NotesStore.notes(a,key),"");scroll.post(()->scroll.fullScroll(android.view.View.FOCUS_DOWN));changed.run();Toast.makeText(a,"笔记已追加",Toast.LENGTH_SHORT).show();}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();
    }catch(Exception e){error(a,e);}}
    static void journal(Activity a,String code,String name,Runnable changed){try{
        if(HoldingsStore.find(a,code)!=null)TradeJournal.current(a,code);
        List<JSONObject> cycles=TradeJournal.forStock(a,code);if(cycles.isEmpty()){Toast.makeText(a,"暂无持仓记录",Toast.LENGTH_SHORT).show();return;}
        LinearLayout c=content(a),body=Ui.column(a);ScrollView scroll=new ScrollView(a);scroll.addView(body);c.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(a,330)));
        for(JSONObject cycle:cycles){Ui.add(body,Ui.text(a,cycle.optString("opened_date")+" → "+(cycle.optBoolean("closed")?cycle.optString("closed_date"):"持仓中"),15,Ui.INK,true));
            for(String kind:new String[]{"buy_notes","sell_notes"}){Ui.gap(body,8);LinearLayout head=Ui.row(a);head.addView(Ui.text(a,kind.equals("buy_notes")?"买入逻辑":"卖出逻辑",13,Ui.BLUE,true),new LinearLayout.LayoutParams(0,-2,1));Button add=Ui.button(a,"＋ 追加",false);add.setTextSize(12);head.addView(add,new LinearLayout.LayoutParams(Ui.dp(a,80),Ui.dp(a,44)));Ui.add(body,head);LinearLayout entries=Ui.column(a);history(entries,cycle.getJSONArray(kind),"未填写（可选）");Ui.add(body,entries);add.setContentDescription(name+(kind.equals("buy_notes")?"追加买入逻辑":"追加卖出逻辑"));add.setOnClickListener(v->logic(a,cycle.optString("id"),kind,()->{try{JSONObject fresh=TradeJournal.find(TradeJournal.cycles(a),cycle.optString("id"));history(entries,fresh.getJSONArray(kind),"");changed.run();}catch(Exception e){error(a,e);}}));}Ui.line(body);}
        new AlertDialog.Builder(a).setTitle(name+" · 买卖逻辑记录").setView(c).setPositiveButton("关闭",null).show();
    }catch(Exception e){error(a,e);}}
    private static void logic(Activity a,String id,String kind,Runnable changed){LinearLayout c=content(a);Ui.add(c,Ui.text(a,"自动附上当日日期，追加后保留原有逻辑。",12,Ui.SECONDARY,false));EditText input=input(a,"输入逻辑正文");Ui.add(c,input);AlertDialog dialog=new AlertDialog.Builder(a).setTitle(kind.equals("buy_notes")?"追加买入逻辑":"追加卖出逻辑").setView(c).setNegativeButton("取消",null).setPositiveButton("追加",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{TradeJournal.addLogic(a,id,kind,input.getText().toString());dialog.dismiss();changed.run();}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();}
    static void error(Activity a,Exception e){Toast.makeText(a,e.getMessage(),Toast.LENGTH_LONG).show();}
}
