package com.aitrader.hammer1430;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.json.*;

/** Storage Access Framework files survive uninstall when saved in Downloads or another user folder. */
public final class DataBackupActivity extends Activity {
    private static final int EXPORT=41,IMPORT=42;
    private Button exportButton,importButton;
    private TextView status,counts;
    private String pendingExport;
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null)pendingExport=state.getString("pending_export");
        LinearLayout outer=Ui.column(this);setContentView(outer);Ui.install(this,outer);LinearLayout root=Ui.column(this);outer.addView(root,new LinearLayout.LayoutParams(-1,0,1));root.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16));LinearLayout nav=Ui.row(this);Ui.Icon back=Ui.iconButton(this,"back","返回");back.setOnClickListener(v->finish());nav.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));TextView heading=Ui.text(this,"数据备份",22,Ui.INK,true);heading.setPadding(Ui.dp(this,10),0,0,0);nav.addView(heading);Ui.add(root,nav);Ui.gap(root,16);ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout page=Ui.column(this);scroll.addView(page);
        LinearLayout info=Ui.card(page);Ui.add(info,Ui.text(this,"日常更新：直接覆盖安装",19,Ui.INK,true));Ui.gap(info,10);Ui.add(info,Ui.text(this,"安装新版APK时选择更新/覆盖，不先卸载，不清除应用数据。笔记、自选增删、收藏、持仓和止损继续保留。",14,Ui.SECONDARY,false));Ui.gap(info,10);Ui.add(info,Ui.text(this,"如安装失败并要求卸载，请先返回检查，不要卸载。",13,Ui.SECONDARY,false));
        LinearLayout backup=Ui.card(page);Ui.add(backup,Ui.text(this,"保存一份个人数据",19,Ui.INK,true));Ui.gap(backup,10);counts=Ui.text(this,"",13,Ui.SECONDARY,false);Ui.add(backup,counts);Ui.gap(backup,12);Ui.add(backup,Ui.text(this,"导出文件包含笔记、买卖逻辑、自选与收藏、持仓与止损。保存到“下载”等公共文件夹，再复制一份到电脑；换手机或重新安装后可以恢复。",14,Ui.SECONDARY,false));Ui.gap(backup,16);exportButton=Ui.button(this,"导出备份",true);exportButton.setTag("backup-export");exportButton.setOnClickListener(v->export());Ui.add(backup,exportButton);Ui.gap(backup,10);importButton=Ui.button(this,"恢复备份",false);importButton.setTag("backup-import");importButton.setOnClickListener(v->chooseImport());Ui.add(backup,importButton);Ui.gap(backup,10);status=Ui.text(this,"备份由你选择保存位置，导出不会改变当前数据。",12,Ui.SECONDARY,false);Ui.add(backup,status);updateCounts();
    }
    private void updateCounts(){try{counts.setText(UserDataBackup.summary(UserDataBackup.capture(this)));}catch(Exception e){status.setText("个人数据读取失败："+e.getMessage());}}
    private void busy(boolean value){exportButton.setEnabled(!value);importButton.setEnabled(!value);}
    private void export(){try{
        if(pendingExport!=null)new File(getCacheDir(),pendingExport).delete();File snapshot=File.createTempFile("personal-export-",".json",getCacheDir());try(FileOutputStream out=new FileOutputStream(snapshot)){out.write(UserDataBackup.capture(this).toString(2).getBytes(StandardCharsets.UTF_8));}pendingExport=snapshot.getName();
        String stamp=DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.now());Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"AItrader-个人备份-"+stamp+".json");startActivityForResult(intent,EXPORT);
    }catch(Exception e){status.setText("导出未完成："+e.getMessage());}}
    private void chooseImport(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"}),IMPORT);}catch(Exception e){status.setText("无法打开文件选择器："+e.getMessage());}}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("pending_export",pendingExport);super.onSaveInstanceState(out);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=EXPORT&&request!=IMPORT)return;if(result!=RESULT_OK||data==null||data.getData()==null){if(request==EXPORT)clearExport();status.setText("已取消，当前数据保留。");return;}Uri uri=data.getData();busy(true);status.setText(request==EXPORT?"正在导出…":"正在校验备份…");
        new Thread(()->{try{if(request==EXPORT){if(pendingExport==null)throw new IOException("导出快照已失效，请重新导出");try(InputStream in=new FileInputStream(new File(getCacheDir(),pendingExport));OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法写入所选位置");byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}clearExport();runOnUiThread(()->{if(isDestroyed())return;busy(false);status.setText("备份已导出。请保留文件，并复制一份到电脑。");});}
            else {JSONObject backup;try(InputStream in=getContentResolver().openInputStream(uri)){backup=UserDataBackup.read(in,this);}runOnUiThread(()->{if(isDestroyed())return;busy(false);confirmRestore(backup);});}
        }catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy(false);status.setText("操作未完成："+e.getMessage()+"；当前数据保留。");});}},"personal-backup").start();
    }
    private void clearExport(){if(pendingExport!=null)new File(getCacheDir(),pendingExport).delete();pendingExport=null;}
    private void confirmRestore(JSONObject backup){try{new AlertDialog.Builder(this).setTitle("恢复这份备份？").setMessage("备份时间："+backup.optString("created_at")+"\n"+UserDataBackup.summary(backup)+"\n\n将个人数据恢复为备份当时的状态，覆盖当前笔记及名单；备份中已删除的自选也保持删除。建议先导出当前数据。").setNegativeButton("取消",(d,w)->status.setText("已取消恢复，当前数据保留。")).setPositiveButton("确认恢复",(d,w)->restore(backup)).show();}catch(Exception e){status.setText(e.getMessage());}}
    private void restore(JSONObject backup){busy(true);status.setText("正在恢复个人数据…");new Thread(()->{
        try{UserDataBackup.restore(this,backup);runOnUiThread(()->{if(isDestroyed())return;busy(false);updateCounts();status.setText("已恢复备份中的笔记和名单，返回各模块即可查看。");});}
        catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy(false);status.setText("操作未完成："+e.getMessage());});}
    },"personal-restore").start();}
}
