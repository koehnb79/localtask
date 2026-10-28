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
    private ProfileStore profileStore;
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
        if(Build.VERSION.SDK_INT>=30){
            // Own the safe-area padding instead of relying on legacy decor fitting.
            getWindow().setDecorFitsSystemWindows(false);
            if(Build.VERSION.SDK_INT<35){
                getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }
        }
        profileStore = new ProfileStore(this);
        connectionScreen();
    }
    private void setScreenContent(View content){
        // Keep safe-area padding outside the scrollable content on every screen.
        FrameLayout safeArea=new FrameLayout(this);
        safeArea.addView(content,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=30){
            safeArea.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets safe=insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
                // Assign absolute values: repeated dispatch must not accumulate padding.
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        // Android 8–10 retain the platform's decor fitting and keyboard resize behavior.
        setContentView(safeArea);
        // PhoneWindow.getInsetsController() dereferences its decor view internally.
        // It is only safe after setContentView has installed that decor.
        if(Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){
                int lightBars=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars,lightBars);
            }
        }
        safeArea.post(safeArea::requestApplyInsets);
    }
    private LinearLayout layout(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,32,24,16);
        setScreenContent(box);return box;
    }
    private TextView text(LinearLayout box,String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,8,0,8);box.addView(t);return t;}
    private EditText input(LinearLayout box,String hint,String value){EditText e=new EditText(this);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);e.setHint(hint);e.setText(value);e.setSingleLine(true);box.addView(e);return e;}
    private void button(LinearLayout box,String label,Runnable action){Button b=new Button(this);b.setText(label);box.addView(b);b.setOnClickListener(v->action.run());}
    private LinearLayout connectionLayout(){
        generation++;client=null;rows.clear();visible.clear();list=null;query=null;
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,32,24,16);
        scroll.addView(box);setScreenContent(scroll);return box;
    }
    private void connectionScreen(){
        LinearLayout box=connectionLayout();text(box,"Your computers",30);
        TextView note=text(box,"Loading saved computers…",15);
        final int epoch=generation;
        worker.execute(()->{
            JSONArray saved=null;
            try{saved=profileStore.load();}catch(Exception ignored){}
            final JSONArray profiles=saved;
            runOnUiThread(()->{
                if(isDestroyed() || epoch!=generation)return;
                if(profiles==null){
                    note.setText("Saved computers could not be decrypted. They have not been changed. Retry, or forget them and add your computers again.");
                    button(box,"Retry",this::connectionScreen);
                    button(box,"Forget all saved computers",()->new AlertDialog.Builder(this)
                        .setTitle("Forget all computers on this phone?")
                        .setMessage("You will need to enter their connection details again. The agents’ credentials will not change.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Forget all",(d,w)->updateProfiles(()->profileStore.clear())).show());
                    return;
                }
                note.setText(profiles.length()==0?"Add your first computer. Its connection details will be saved securely on this phone.":"Tap a computer to connect. Connection details are saved securely on this phone.");
                for(int i=0;i<profiles.length();i++){
                    JSONObject profile=profiles.optJSONObject(i);
                    if(profile==null)continue;
                    button(box,profile.optString("name")+" · "+profile.optString("host"),()->connectProfile(profile));
                    button(box,"Edit / forget "+profile.optString("name"),()->editProfile(profile));
                }
                button(box,"Add computer",()->editProfile(null));
            });
        });
    }
    private void connectProfile(JSONObject profile){
        try{
            client=new AgentClient(profile.getString("host"),profile.getString("port"),profile.getString("token"),profile.getString("fingerprint"));
            generation++;processScreen();
        }catch(Exception e){Toast.makeText(this,"Invalid connection details. Edit this saved computer.",Toast.LENGTH_LONG).show();}
    }
    private interface ProfileChange { void run() throws Exception; }
    private void updateProfiles(ProfileChange change){
        final int epoch=generation;
        worker.execute(()->{
            boolean success;
            try{change.run();success=true;}catch(Exception e){success=false;}
            final boolean ok=success;
            runOnUiThread(()->{
                if(isDestroyed() || epoch!=generation)return;
                if(ok)connectionScreen();
                else Toast.makeText(this,"Could not update saved computers. Please try again.",Toast.LENGTH_LONG).show();
            });
        });
    }
    private void editProfile(JSONObject existing){
        LinearLayout box=connectionLayout();text(box,existing==null?"Add computer":"Edit computer",28);
        text(box,"Enter the details shown by the agent’s pair command. Server restarts keep the same fingerprint and access key.",15);
        EditText name=input(box,"Computer name",existing==null?"":existing.optString("name"));
        EditText host=input(box,"Computer IP · 192.168.1.20",existing==null?"":existing.optString("host"));
        EditText port=input(box,"Port",existing==null?"8765":existing.optString("port"));port.setInputType(2);
        EditText fingerprint=input(box,"SHA-256 certificate fingerprint",existing==null?"":existing.optString("fingerprint"));
        EditText token=input(box,"Access key",existing==null?"":existing.optString("token"));token.setInputType(129);
        TextView error=text(box,"",14);
        Button save=new Button(this);save.setText("Save and connect");box.addView(save);
        save.setOnClickListener(v->{
            try{
                String label=name.getText().toString().trim();
                if(label.isEmpty())throw new IllegalArgumentException("Enter a name for this computer");
                JSONObject profile=new JSONObject().put("id",existing==null?UUID.randomUUID().toString():existing.getString("id"))
                    .put("name",label).put("host",host.getText().toString().trim()).put("port",port.getText().toString().trim())
                    .put("token",token.getText().toString().trim()).put("fingerprint",fingerprint.getText().toString().trim());
                new AgentClient(profile.getString("host"),profile.getString("port"),profile.getString("token"),profile.getString("fingerprint"));
                save.setEnabled(false);error.setText("Saving…");final int epoch=generation;
                worker.execute(()->{
                    boolean success;
                    try{profileStore.save(profile);success=true;}catch(Exception e){success=false;}
                    final boolean ok=success;
                    runOnUiThread(()->{
                        if(isDestroyed() || epoch!=generation)return;
                        if(ok)connectProfile(profile);
                        else{save.setEnabled(true);error.setText("Could not save this computer. Return to your computers and retry.");}
                    });
                });
            }catch(Exception e){error.setText(e.getMessage());}
        });
        if(existing!=null)button(box,"Forget computer",()->new AlertDialog.Builder(this)
            .setTitle("Forget "+existing.optString("name")+"?")
            .setMessage("This removes the saved details from this phone. It does not revoke the agent’s access key.")
            .setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->updateProfiles(()->profileStore.remove(existing.getString("id")))).show());
        button(box,"Back to computers",this::connectionScreen);
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
