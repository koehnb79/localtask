package com.solemntreeapps.localtask;

import android.os.Bundle;
import android.content.Intent;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

public final class TaskerConfigActivity extends SafeActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private JSONArray profiles=new JSONArray();
    @Override public void onCreate(Bundle saved){super.onCreate(saved);setResult(RESULT_CANCELED);
        if(!TaskerProtocol.HOST.equals(getCallingPackage())){finish();return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,24,24,24);
        TextView title=new TextView(this);title.setText("LocalTask automation");title.setTextSize(24);box.addView(title);
        TextView note=new TextView(this);note.setText("Select a saved computer. Shutdown requires Allow Tasker shutdown in LocalTask’s Edit computer screen. Tasker stores a revocable automation grant, not the agent’s access key. Keep Tasker backups private.");box.addView(note);
        Spinner computer=new Spinner(this),action=new Spinner(this);box.addView(computer);box.addView(action);
        action.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Check computer status","Shut down computer"}));
        Button save=new Button(this);save.setText("Save Tasker action");save.setEnabled(false);box.addView(save);
        Button cancel=new Button(this);cancel.setText("Cancel");box.addView(cancel);cancel.setOnClickListener(v->finish());showSafe(box);
        worker.execute(()->{try{JSONArray loaded=new ProfileStore(this).load();runOnUiThread(()->{if(isDestroyed())return;profiles=loaded;String[] names=new String[profiles.length()];for(int i=0;i<names.length;i++)names[i]=profiles.optJSONObject(i).optString("name");computer.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));save.setEnabled(names.length>0);
            Bundle prior=getIntent().getBundleExtra(TaskerProtocol.BUNDLE);
            if(prior!=null){action.setSelection("shutdown".equals(prior.getString("action"))?1:0);for(int i=0;i<profiles.length();i++)if(profiles.optJSONObject(i).optString("id").equals(prior.getString("profile")))computer.setSelection(i);}
            if(names.length==0)note.setText("Add a computer in LocalTask first, then configure this action again.");});}catch(Exception e){runOnUiThread(()->note.setText("Could not read saved computers. Open LocalTask and retry."));}});
        save.setOnClickListener(v->{
            JSONObject p=profiles.optJSONObject(computer.getSelectedItemPosition());if(p==null)return;
            String operation=action.getSelectedItemPosition()==1?"shutdown":"check";save.setEnabled(false);
            worker.execute(()->{try{
                JSONObject grant=new AutomationStore(this).issue(p.getString("id"),operation);
                Bundle data=new Bundle();data.putInt("version",1);data.putString("grant",grant.getString("id"));data.putString("capability",grant.getString("capability"));data.putString("action",operation);data.putString("profile",p.getString("id"));
                Intent result=new Intent().putExtra(TaskerProtocol.BUNDLE,data)
                    .putExtra("com.twofortyfouram.locale.intent.extra.BLURB",operation+" · "+p.getString("name"))
                    .putExtra(TaskerProtocol.PREFIX+".extras.REQUESTED_TIMEOUT",60000)
                    .putExtra(TaskerProtocol.PREFIX+".RELEVANT_VARIABLES",new String[]{"%lt_status","%lt_online","%lt_message","%errmsg"});
                runOnUiThread(()->{if(!isDestroyed()){setResult(RESULT_OK,result);finish();}});
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){note.setText(e instanceof SecurityException?e.getMessage():"Could not save automation permission");save.setEnabled(true);}});}});
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
