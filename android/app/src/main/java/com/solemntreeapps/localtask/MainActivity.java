package com.solemntreeapps.localtask;

import android.app.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler timer=new Handler(Looper.getMainLooper());
    private AgentClient client;
    private TextView status;
    private EditText query;
    private ListView list;
    private boolean busy=false, active=false;
    private int generation=0, sort=0;
    private String computerOS="";
    private final ArrayList<JSONObject> rows=new ArrayList<>(), visible=new ArrayList<>();
    private final Runnable poll=new Runnable(){public void run(){refresh();timer.postDelayed(this,3000);}};

    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        connectionScreen();
    }
    private LinearLayout layout(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,32,24,16);
        setContentView(box);return box;
    }
    private TextView text(LinearLayout box,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,8,0,8);box.addView(t);return t;}
    private EditText input(LinearLayout box,String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setSingleLine(true);box.addView(e);return e;}
    private void button(LinearLayout box,String label,Runnable action){Button b=new Button(this);b.setText(label);box.addView(b);b.setOnClickListener(v->action.run());}
    private void connectionScreen(){
        generation++;client=null;rows.clear();visible.clear();
        LinearLayout box=layout();text(box,"LocalTask",30);text(box,"Connect to your computer",20);
        text(box,"Run the agent’s pair command on your computer. Enter its local IP and copy the credentials below. Credentials stay in memory for this session.",15);
        EditText host=input(box,"Computer IP · 192.168.1.20","");
        EditText port=input(box,"Port","8765");port.setInputType(2);
        EditText fingerprint=input(box,"SHA-256 certificate fingerprint","");
        EditText token=input(box,"Access key","");token.setInputType(129);
        TextView error=text(box,"",14);
        button(box,"Connect",()->{
            try{client=new AgentClient(host.getText().toString().trim(),port.getText().toString().trim(),token.getText().toString().trim(),fingerprint.getText().toString().trim());processScreen();}
            catch(Exception e){error.setText(e.getMessage());}
        });
    }
    private void processScreen(){
        LinearLayout box=layout();text(box,"Processes",28);status=text(box,"Connecting…",14);
        query=input(box,"Search name, owner or PID","");
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable e){}});
        button(box,"Sort: memory / CPU / name",()->{sort=(sort+1)%3;render();});
        list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id)->confirm(visible.get(position)));
        button(box,"Disconnect / switch computer",this::connectionScreen);
        refresh();
    }
    private void refresh(){
        if(!active || client==null || busy)return;
        busy=true;final AgentClient target=client;final int epoch=generation;
        worker.execute(()->{
            JSONObject data=null;String error=null;
            try{data=target.processes();}catch(Exception e){error=e.getMessage();}
            final JSONObject result=data;final String failure=error;
            runOnUiThread(()->{
                busy=false;if(isDestroyed() || generation!=epoch || client!=target)return;
                if(failure!=null){rows.clear();render();status.setText("Disconnected · "+failure);return;}
                computerOS=result.optString("os");rows.clear();JSONArray array=result.optJSONArray("processes");
                if(array!=null)for(int i=0;i<array.length();i++)rows.add(array.optJSONObject(i));
                status.setText(result.optString("computer")+" · "+computerOS+" · "+rows.size()+" processes · updated "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
                render();
            });
        });
    }
    private void render(){
        if(list==null || query==null)return;
        String q=query.getText().toString().toLowerCase(Locale.ROOT);visible.clear();
        for(JSONObject p:rows)if((p.optString("name")+" "+p.optString("owner")+" "+p.optInt("pid")).toLowerCase(Locale.ROOT).contains(q))visible.add(p);
        if(sort==2)visible.sort(Comparator.comparing(p->p.optString("name").toLowerCase(Locale.ROOT)));
        else {final String field=sort==0?"memory":"cpu";visible.sort((a,b)->Double.compare(b.optDouble(field,0),a.optDouble(field,0)));}
        ArrayList<String> labels=new ArrayList<>();
        for(JSONObject p:visible){String cpu=p.isNull("cpu")?"sampling":String.format(Locale.US,"%.1f%%",p.optDouble("cpu"));
            labels.add(p.optString("name")+"  ·  PID "+p.optInt("pid")+"\n"+String.format(Locale.US,"%.1f MB",p.optLong("memory")/1048576.0)+"  ·  CPU "+cpu+"\n"+p.optString("owner")+(p.optBoolean("manageable")?"":" · view only"));}
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));
    }
    private void confirm(JSONObject p){
        if(!p.optBoolean("manageable")){new AlertDialog.Builder(this).setMessage("This process is view only.").setPositiveButton("OK",null).show();return;}
        final AgentClient target=client;final int epoch=generation;
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(p.optString("name")+" · "+p.optInt("pid"))
            .setMessage("Stopping a process can lose unsaved work. Force stop ends it immediately. Only this process is targeted; child processes are not included.")
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Force stop",(d,w)->new AlertDialog.Builder(this).setTitle("Force stop this process?").setMessage("Unsaved work may be lost.").setNegativeButton("Cancel",null).setPositiveButton("Force stop",(d2,w2)->send(target,epoch,p,"kill")).show());
        if(!"Windows".equals(computerOS))dialog.setPositiveButton("Request stop",(d,w)->send(target,epoch,p,"terminate"));
        dialog.show();
    }
    private void send(AgentClient target,int epoch,JSONObject p,String action){
        if(target==null || target!=client || epoch!=generation)return;
        worker.execute(()->{
            String message;
            try {JSONObject body=new JSONObject().put("pid",p.getInt("pid")).put("created",p.getDouble("created")).put("action",action);target.command(body);message="Stop signal sent. The next refresh shows whether it exited.";}
            catch(Exception e){message="Command failed: "+e.getMessage();}
            final String outcome=message;runOnUiThread(()->{if(!isDestroyed() && epoch==generation){Toast.makeText(this,outcome,Toast.LENGTH_LONG).show();refresh();}});
        });
    }
    @Override protected void onResume(){super.onResume();active=true;timer.post(poll);}
    @Override protected void onPause(){active=false;timer.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){generation++;worker.shutdownNow();super.onDestroy();}
}
