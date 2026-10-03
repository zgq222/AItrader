package com.aitrader.hammer1430;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;

final class HoldingDialogs {
    static void add(Activity a,String code,String name,String industry,Runnable changed){transition(a,code,name,industry,false,changed);}
    static void remove(Activity a,String code,String name,Runnable changed){transition(a,code,name,"",true,changed);}
    private static void transition(Activity a,String code,String name,String industry,boolean remove,Runnable changed){
        LinearLayout content=Ui.column(a);content.setPadding(Ui.dp(a,24),Ui.dp(a,8),Ui.dp(a,24),Ui.dp(a,8));Ui.add(content,Ui.text(a,remove?"移出后清除止损线，历史笔记和买卖逻辑继续保留。":"买入逻辑可留空，加入后也可以继续追加。",13,Ui.SECONDARY,false));
        EditText input=new EditText(a);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setMinLines(3);input.setMaxLines(6);input.setHint((remove?"卖出":"买入")+"逻辑（选填）");Ui.add(content,input);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(name+" · "+(remove?"移出持仓":"加入持仓")).setView(content).setNegativeButton("取消",null).setPositiveButton(remove?"确认移出":"确认加入",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{if(remove)HoldingsStore.remove(a,code,input.getText().toString());else HoldingsStore.add(a,code,name,industry,input.getText().toString());dialog.dismiss();changed.run();Toast.makeText(a,remove?"已移出持仓，记录已保留":"已加入持仓",Toast.LENGTH_SHORT).show();}catch(Exception e){input.setError(e.getMessage());}}));dialog.show();
    }
    static void manage(Activity a,String code,String name,Runnable changed){new AlertDialog.Builder(a).setTitle(name+" · 持仓管理").setItems(new String[]{"设置止损线","买入/卖出逻辑","移出持仓"},(d,which)->{if(which==0)editStop(a,code,changed);else if(which==1)NotesDialogs.journal(a,code,name,changed);else remove(a,code,name,changed);}).setNegativeButton("取消",null).show();}
    static void editStop(Activity a,String code,Runnable changed){try {
        JSONObject item=HoldingsStore.find(a,code);if(item==null)return;
        LinearLayout content=Ui.column(a);content.setPadding(Ui.dp(a,24),Ui.dp(a,8),Ui.dp(a,24),Ui.dp(a,8));
        Ui.add(content,Ui.text(a,"在日K和15分钟K线上显示，最新报价触及时提示。此设置不自动下单。",13,Ui.SECONDARY,false));Ui.gap(content,12);
        EditText input=new EditText(a);input.setSingleLine(true);input.setHint("止损价，例如 12.50");input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if(item.has("stop_price"))input.setText(String.format(Locale.CHINA,"%.2f",item.getDouble("stop_price")));Ui.add(content,input);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(item.optString("name")+" · 止损线").setView(content).setNegativeButton("取消",null).setNeutralButton("清除",(d,w)->{try{HoldingsStore.stop(a,code,null);changed.run();}catch(Exception e){Toast.makeText(a,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            String value=input.getText().toString().trim();if(!value.matches("\\d+(\\.\\d{1,2})?"))throw new Exception("请输入正数止损价，最多两位小数");
            HoldingsStore.stop(a,code,Double.parseDouble(value));dialog.dismiss();changed.run();
        }catch(Exception e){input.setError(e.getMessage());}}));dialog.show();
    }catch(Exception e){Toast.makeText(a,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}
}
