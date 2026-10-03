package com.aitrader.hammer1430;

import android.app.*;
import android.text.InputType;
import android.widget.*;
import org.json.JSONObject;
import java.util.Locale;

final class HoldingDialogs {
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
