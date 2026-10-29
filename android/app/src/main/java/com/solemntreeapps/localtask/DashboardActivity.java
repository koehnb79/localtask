package com.solemntreeapps.localtask;

import android.os.*;
import android.widget.*;
import android.text.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class DashboardActivity extends SafeActivity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=(ThreadPoolExecutor)Executors.newFixedThreadPool(3);
    private final ArrayList<Computer> computers=new ArrayList<>();
    private final ArrayList<String> labels=new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private EditText search;
    private boolean active;
    private int epoch,sort;
    private LinearLayout cards;
    private final Runnable poll=new Runnable(){public void run(){if(active){refresh();handler.postDelayed(this,5000);}}};
    private static final class Computer {
        JSONObject profile;TextView state;HistoryGraph[] graphs;JSONArray processes=new JSONArray();boolean pending;
    }
    private TextView text(LinearLayout box,String value){TextView t=new TextView(this);t.setText(value);t.setPadding(8,12,8,8);box.addView(t);return t;}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(16,8,16,8);
        text(box,"All computers · live overview").setTextSize(24);
        Button back=new Button(this);back.setText("Back to computers");back.setOnClickListener(v->finish());box.addView(back);
        ScrollView scroll=new ScrollView(this);cards=new LinearLayout(this);cards.setOrientation(1);scroll.addView(cards);
        box.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min((int)(getResources().getDisplayMetrics().density*300),getResources().getDisplayMetrics().heightPixels/3)));
        Button graphs=new Button(this);graphs.setText("Hide system graphs");box.addView(graphs);
        graphs.setOnClickListener(v->{boolean shown=scroll.getVisibility()==android.view.View.VISIBLE;scroll.setVisibility(shown?android.view.View.GONE:android.view.View.VISIBLE);graphs.setText(shown?"Show system graphs":"Hide system graphs");});
        text(box,"Processes · visible to each agent · refresh every 5 seconds");
        search=new EditText(this);search.setSingleLine();search.setHint("Search computer, process, owner or PID");box.addView(search);
        Spinner order=new Spinner(this);order.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Memory — highest first","CPU — highest first","Computer / process name"}));box.addView(order);
        order.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int pos,long id){sort=pos;render();}});
        ListView list=new ListView(this);adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);list.setAdapter(adapter);box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render();}public void afterTextChanged(Editable e){}});
        showSafe(box);text(cards,"Loading saved computers…");
        workers.execute(()->{
            JSONArray profiles;try{profiles=new ProfileStore(this).load();}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){cards.removeAllViews();text(cards,"Could not load saved computers. Return to LocalTask and retry.");}});return;}
            runOnUiThread(()->{
                if(isDestroyed())return;cards.removeAllViews();
                if(profiles.length()==0)text(cards,"Add computers on the home screen first.");
                for(int i=0;i<profiles.length();i++){
                    Computer c=new Computer();c.profile=profiles.optJSONObject(i);if(c.profile==null)continue;
                    text(cards,c.profile.optString("name")+" · "+c.profile.optString("host")).setTextSize(20);
                    c.state=text(cards,"Checking…");c.graphs=new HistoryGraph[3];
                    for(int k=0;k<3;k++){c.graphs[k]=new HistoryGraph(this,k);cards.addView(c.graphs[k],new LinearLayout.LayoutParams(-1,(int)(110*getResources().getDisplayMetrics().density)));}
                    computers.add(c);
                }
                refresh();
            });
        });
    }
    private void refresh(){if(!active)return;final int current=epoch;
        for(Computer c:computers){if(c.pending)continue;c.pending=true;workers.execute(()->{
            JSONArray processes=new JSONArray(),samples=null;String result;
            try{
                JSONObject p=c.profile;AgentClient client=new AgentClient(p.getString("host"),p.getString("port"),p.getString("token"),p.getString("fingerprint"));
                processes=client.processes().getJSONArray("processes");
                try{samples=client.metrics().getJSONArray("samples");result="Online · "+processes.length()+" processes";}
                catch(AgentClient.AgentException e){result=e.status==404?"Online · update agent for system graphs":"Processes available · metrics error (HTTP "+e.status+")";}
                catch(Exception e){result="Processes available · metrics unavailable";}
            }catch(Exception e){result=ConnectionStatus.message(e)+" · previous graphs may be stale";}
            final JSONArray rows=processes,history=samples;final String message=result;
            runOnUiThread(()->{if(isDestroyed()||!active||current!=epoch)return;c.pending=false;c.processes=rows;c.state.setText(message+" · "+android.text.format.DateFormat.getTimeFormat(this).format(new Date()));if(history!=null)for(HistoryGraph g:c.graphs)g.update(history);render();});
        });}
    }
    private void render(){if(adapter==null)return;
        ArrayList<JSONObject> all=new ArrayList<>();String q=search.getText().toString().toLowerCase(Locale.ROOT);
        for(Computer c:computers)for(int i=0;i<c.processes.length();i++){
            JSONObject p=c.processes.optJSONObject(i);if(p==null)continue;
            try{JSONObject row=new JSONObject(p.toString()).put("computer",c.profile.optString("name"));
                String hay=row.optString("computer")+" "+row.optString("name")+" "+row.optString("owner")+" "+row.optInt("pid");
                if(hay.toLowerCase(Locale.ROOT).contains(q))all.add(row);
            }catch(Exception ignored){}
        }
        all.sort((a,b)->sort==2?(a.optString("computer")+a.optString("name")).compareToIgnoreCase(b.optString("computer")+b.optString("name")):Double.compare(b.optDouble(sort==0?"memory":"cpu",-1),a.optDouble(sort==0?"memory":"cpu",-1)));
        labels.clear();for(JSONObject p:all)labels.add(p.optString("computer")+" · "+p.optString("name")+"  ["+p.optInt("pid")+"]\nCPU "+(p.isNull("cpu")?"warming up":String.format(Locale.ROOT,"%.1f%%",p.optDouble("cpu")))+String.format(Locale.ROOT," · %.1f MiB",p.optDouble("memory")/1048576)+" · "+p.optString("owner"));adapter.notifyDataSetChanged();
    }
    @Override protected void onResume(){super.onResume();active=true;handler.post(poll);}
    @Override protected void onPause(){active=false;epoch++;handler.removeCallbacks(poll);if(!computers.isEmpty())workers.getQueue().clear();for(Computer c:computers)c.pending=false;super.onPause();}
    @Override protected void onDestroy(){epoch++;workers.shutdownNow();super.onDestroy();}
}
