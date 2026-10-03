package com.aitrader.hammer1430;

import org.json.JSONObject;
import java.util.List;
import java.util.Map;

/** Host checks use a real JSON runtime and the same repository as Android. */
public final class WatchlistStoreTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Memory implements WatchlistStore.Backend {
        String saved="[]";boolean writable=true;
        public String read(){return saved;}
        public boolean write(String value){if(!writable)return false;saved=value;return true;}
    }
    public static void main(String[] args)throws Exception{
        Memory memory=new Memory();WatchlistStore.Repository list=new WatchlistStore.Repository(memory);
        check(list.stocks().isEmpty(),"fresh install has an empty list");
        list.add("000001","平安银行","银行");list.add("600519","贵州茅台","白酒");
        list.add("600036","招商银行","银行");
        WatchlistStore.Repository reopened=new WatchlistStore.Repository(memory);
        check(reopened.contains("000001"),"saved stock survives repository recreation");
        check(reopened.stocks().size()==3,"all stocks survive recreation");
        reopened.add("000001","平安银行更新","银行");
        check(reopened.stocks().size()==3,"duplicate add does not create another card");
        check(reopened.stocks().get(0).optString("name").equals("平安银行更新"),"duplicate refreshes name without changing position");
        Map<String,List<JSONObject>> groups=WatchlistStore.grouped(reopened.stocks(),code->"待分类");
        check(groups.size()==2&&groups.get("银行").size()==2,"same industry forms one sublist");
        groups=WatchlistStore.grouped(reopened.stocks(),code->code.equals("600519")?"食品饮料":"待分类");
        check(groups.containsKey("食品饮料")&&!groups.containsKey("白酒"),"latest industry map takes precedence");
        list.add("300001","特锐德","");
        groups=WatchlistStore.grouped(list.stocks(),code->null);
        check(groups.get("待分类").size()==1,"unknown stock remains visible in unclassified list");
        reopened.remove("000001");
        check(!list.contains("000001")&&list.contains("600036"),"remove deletes only the requested stock");
        String previous=memory.saved;memory.writable=false;
        try{list.add("000002","万科A","房地产");throw new AssertionError("failed write must report failure");}
        catch(java.io.IOException expected){check(memory.saved.equals(previous),"failed save preserves existing list");}
        memory.writable=true;memory.saved="broken json";
        try{list.add("000002","万科A","房地产");throw new AssertionError("invalid saved data must not be overwritten");}
        catch(org.json.JSONException expected){check(memory.saved.equals("broken json"),"corrupt data is preserved for recovery");}
        memory.saved=previous;
        try{list.add("invalid","invalid","银行");throw new AssertionError("invalid code accepted");}
        catch(java.io.IOException expected){check(memory.saved.equals(previous),"invalid code leaves favorites intact");}
        System.out.println("WatchlistStoreTest: "+checks+" checks passed");
    }
}
