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
    private final ThreadPoolExecutor statusWorkers=(ThreadPoolExecutor)Executors.newFixedThreadPool(3);
    private final ArrayList<ComputerStatus> computerStatuses=new ArrayList<>();
    private int statusEpoch=0;
    private final Runnable statusPoll=new Runnable(){public void run(){
        if(active){checkComputerStatuses();timer.postDelayed(this,15000);}
    }};
    private static final class ComputerStatus {
        final JSONObject profile; final TextView label; boolean pending;
        ComputerStatus(JSONObject profile,TextView label){this.profile=profile;this.label=label;}
    }
    private void stopStatusChecks(){
        statusEpoch++;timer.removeCallbacks(statusPoll);statusWorkers.getQueue().clear();
        for(ComputerStatus item:computerStatuses)item.pending=false;
    }
    private void checkComputerStatuses(){
        if(!active || client!=null)return;
        final int epoch=statusEpoch;
        for(ComputerStatus item:computerStatuses){
            if(item.pending)continue;
            item.pending=true;
            statusWorkers.execute(()->{
                String result;
                try{
                    JSONObject p=item.profile;
                    new AgentClient(p.getString("host"),p.getString("port"),p.getString("token"),p.getString("fingerprint")).checkOnline();
                    result="● Online";
                }catch(Exception e){result=ConnectionStatus.message(e);}
                final String message=result;
                runOnUiThread(()->{
                    if(isDestroyed() || !active || epoch!=statusEpoch)return;
                    item.pending=false;
                    String checked=android.text.format.DateFormat.getTimeFormat(this).format(new Date());
                    item.label.setText(message+" · Checked "+checked);
                });
            });
        }
    }
    private final Handler timer=new Handler(Looper.getMainLooper());
    private AgentClient client;
    private ProfileStore profileStore;
    private static final int SCAN_COMPUTER = 0x5152;
    private boolean showingShareQr;
    private boolean computerActionBusy;
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
        stopStatusChecks();computerStatuses.clear();
        generation++;client=null;rows.clear();visible.clear();list=null;query=null;showingShareQr=false;computerActionBusy=false;
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,32,24,16);
        scroll.addView(box);setScreenContent(scroll);return box;
    }
    private void connectionScreen(){
        LinearLayout box=connectionLayout();text(box,"Your computers",30);
        button(box,"Agent setup guide",this::guideScreen);
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
                    computerStatuses.add(new ComputerStatus(profile,text(box,"○ Checking…",15)));
                    button(box,"Edit / forget "+profile.optString("name"),()->editProfile(profile));
                }
                text(box,"Status refreshes every 15 seconds while this screen is open. Unreachable can mean asleep, a stopped agent, a changed IP, or a network issue.",14);
                timer.removeCallbacks(statusPoll);if(active)timer.post(statusPoll);
                button(box,"Add computer",()->editProfile(null));
                button(box,"Scan computer QR",this::scanComputer);
            });
        });
    }
    private void connectProfile(JSONObject profile){
        try{
            client=new AgentClient(profile.getString("host"),profile.getString("port"),profile.getString("token"),profile.getString("fingerprint"));
            stopStatusChecks();computerStatuses.clear();generation++;processScreen();
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
                if(label.isEmpty() || label.length()>64)throw new IllegalArgumentException("Enter a computer name of 1–64 characters");
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
        if(existing!=null)button(box,"Share saved computer by QR",()->shareComputer(existing));
        if(existing!=null)button(box,"Forget computer",()->new AlertDialog.Builder(this)
            .setTitle("Forget "+existing.optString("name")+"?")
            .setMessage("This removes the saved details from this phone. It does not revoke the agent’s access key.")
            .setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->updateProfiles(()->profileStore.remove(existing.getString("id")))).show());
        button(box,"Back to computers",this::connectionScreen);
    }
    private void shareComputer(JSONObject profile){
        new AlertDialog.Builder(this).setTitle("Share access to "+profile.optString("name")+"?")
            .setMessage("This QR includes the computer’s access key. Anyone who scans it can manage processes and request log off, shutdown, or restart on an updated agent when the computer is reachable. Share only with someone you trust. This key does not expire. Revoking it on the agent and restarting the agent removes access for ALL phones using that key.")
            .setNegativeButton("Cancel",null).setPositiveButton("Show QR",(d,w)->{
                LinearLayout box=connectionLayout();showingShareQr=true;
                text(box,"Share "+profile.optString("name"),26);
                text(box,"On the other phone: open LocalTask → Scan computer QR. Both phones need version 0.3 or later. The receiving phone must be on the computer’s local network to connect.",15);
                ImageView qr=new ImageView(this);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int size=Math.min((int)(320*getResources().getDisplayMetrics().density),getResources().getDisplayMetrics().widthPixels-48);
                box.addView(qr,new LinearLayout.LayoutParams(-1,size));
                qr.setContentDescription("Private computer access QR code");
                TextView result=text(box,"Preparing QR…",14);
                text(box,"This shares the SAVED details. Unsaved edits are not included. The QR is hidden if you leave the app.",14);
                button(box,"Done",this::connectionScreen);
                final int epoch=generation;
                worker.execute(()->{
                    android.graphics.Bitmap bitmap=null;
                    try{
                        com.google.zxing.common.BitMatrix matrix=ProfileQr.encode(profile);
                        int width=matrix.getWidth();int[] pixels=new int[width*width];
                        for(int y=0;y<width;y++)for(int x=0;x<width;x++)pixels[y*width+x]=matrix.get(x,y)?0xff000000:0xffffffff;
                        bitmap=android.graphics.Bitmap.createBitmap(pixels,width,width,android.graphics.Bitmap.Config.ARGB_8888);
                    }catch(Exception ignored){}
                    final android.graphics.Bitmap image=bitmap;
                    runOnUiThread(()->{
                        if(isDestroyed() || epoch!=generation)return;
                        if(image==null)result.setText("Could not create this QR. Check the saved name (64 characters maximum) and connection details.");
                        else{qr.setImageBitmap(image);result.setText("Ready to scan. Keep this code private.");}
                    });
                });
            }).show();
    }
    @SuppressWarnings("deprecation")
    private void scanComputer(){
        com.journeyapps.barcodescanner.ScanOptions options=new com.journeyapps.barcodescanner.ScanOptions()
            .setCaptureActivity(QrScanActivity.class).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setPrompt("Scan a trusted LocalTask computer QR").setBeepEnabled(false)
            .setBarcodeImageEnabled(false).setOrientationLocked(false);
        try{startActivityForResult(options.createScanIntent(this),SCAN_COMPUTER);}
        catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"Scanner unavailable. You can still add a computer manually.",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,android.content.Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=SCAN_COMPUTER)return;
        if(resultCode!=RESULT_OK || data==null){
            if(data!=null && data.getBooleanExtra(com.google.zxing.client.android.Intents.Scan.MISSING_CAMERA_PERMISSION,false))
                Toast.makeText(this,"Camera access is needed to scan. Enable it in app permissions, or add the computer manually.",Toast.LENGTH_LONG).show();
            return;
        }
        try{
            final JSONObject profile=ProfileTransfer.decode(data.getStringExtra(com.google.zxing.client.android.Intents.Scan.RESULT));
            new AlertDialog.Builder(this).setTitle("Save this computer?")
                .setMessage(profile.getString("name")+"\n"+profile.getString("host")+":"+profile.getString("port")
                    +"\n\nCertificate fingerprint:\n"+profile.getString("fingerprint")
                    +"\n\nOnly save codes from a trusted person. Saving does not connect or run commands. Existing computer entries will not be overwritten.")
                .setNegativeButton("Cancel",null).setPositiveButton("Save computer",(d,w)->importComputer(profile)).show();
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Not a valid LocalTask computer QR")
            .setMessage("Use Share saved computer by QR in LocalTask 0.3 or later. This code was not saved or opened.").setPositiveButton("OK",null).show();}
    }
    private void importComputer(JSONObject profile){
        final int epoch=generation;
        worker.execute(()->{
            String message;
            try{
                JSONArray saved=profileStore.load();boolean duplicate=false;
                for(int i=0;i<saved.length();i++){
                    JSONObject p=saved.getJSONObject(i);
                    if(p.optString("host").equals(profile.getString("host")) && p.optString("port").equals(profile.getString("port"))
                        && p.optString("fingerprint").replace(":", "").equalsIgnoreCase(profile.getString("fingerprint"))
                        && p.optString("token").equals(profile.getString("token")))duplicate=true;
                }
                if(!duplicate)profileStore.save(profile);
                message=duplicate?"This computer is already saved.":"Computer saved. Tap its name when you want to connect.";
            }catch(Exception e){message="Could not save the computer. Your existing entries were not replaced.";}
            final String outcome=message;
            runOnUiThread(()->{if(!isDestroyed() && epoch==generation){connectionScreen();Toast.makeText(this,outcome,Toast.LENGTH_LONG).show();}});
        });
    }
    private void openGuideLink(String url){
        try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)));}
        catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"No browser available. Open "+url+" on your computer.",Toast.LENGTH_LONG).show();}
    }
    private void guideScreen(){
        LinearLayout box=connectionLayout();text(box,"Set up a computer",28);
        text(box,"Install the agent on each computer you want to manage. Your phone and computer must be on the same trusted local network.",16);
        text(box,"1. Download on your computer",21);text(box,AgentGuide.download(),15);
        button(box,"Open agent repository",()->openGuideLink(AgentGuide.REPOSITORY));
        button(box,"Open Python downloads",()->openGuideLink(AgentGuide.PYTHON));
        text(box,"Computer actions require LocalTask 0.4 or later on both the phone and computer. Update the entire agent folder, including computer_actions.py, and restart the agent. Keep your existing credentials; do not initialize again.",15);
        text(box,"2. Choose the computer’s operating system",21);
        for(String os:new String[]{"Mac","Linux","Windows"})button(box,os+" setup",()->platformGuide(os));
        button(box,"Read full setup and background-service instructions",()->openGuideLink(AgentGuide.README));
        button(box,"Back to computers",this::connectionScreen);
    }
    private void platformGuide(String os){
        LinearLayout box=connectionLayout();text(box,os+" agent setup",28);
        text(box,"1. Get the files",21);text(box,AgentGuide.download(),15);
        button(box,"Open repository",()->openGuideLink(AgentGuide.REPOSITORY));
        text(box,"2. Find the computer’s local IP",21);text(box,AgentGuide.address(os),15);
        text(box,"3. Start the agent",21);
        text(box,"Open "+("Windows".equals(os)?"PowerShell":"Terminal")+" in the extracted agent folder. Run these commands one at a time as your normal user, not as Administrator or root. Replace YOUR_LAN_IP with that computer’s private IPv4 address, such as 192.168.1.20.",15);
        TextView commands=text(box,AgentGuide.commands(os),14);commands.setTypeface(android.graphics.Typeface.MONOSPACE);commands.setTextIsSelectable(true);
        text(box,"Run init only for first-time setup. It refuses to overwrite existing credentials. Keep this terminal open while using the agent. If Python is not found, install Python 3.12 first; on some Linux distributions you also need its venv package.",15);
        text(box,"4. Allow local access",21);
        text(box,"Allow inbound TCP port 8765 through the computer’s firewall only on your trusted local network. Use private/home network settings where available. Do not forward this port on your router. Guest Wi-Fi can block connections between devices.",15);
        text(box,"5. Add it in LocalTask",21);
        text(box,"The pair command prints a fingerprint and token. On this phone choose Add computer, enter a name, the IP and port 8765, then copy those two values exactly. Tap Save and connect. If another phone already manages the computer, use its Share saved computer by QR option and scan it here instead.",15);
        text(box,"Next time",21);text(box,"From the same agent folder, start it with:",15);
        TextView restart=text(box,AgentGuide.restart(os),14);restart.setTypeface(android.graphics.Typeface.MONOSPACE);restart.setTextIsSelectable(true);
        text(box,"Use the same account and configuration folder. Restarting the agent does not change its fingerprint or token. Reserve its IP on your router, or edit the saved IP if it changes. Background startup is optional; the full README has the platform-specific steps.",15);
        if("Linux".equals(os)){
            text(box,"Start at boot (systemd)",21);
            TextView boot=text(box,AgentGuide.linuxBoot(),15);boot.setTextIsSelectable(true);
        }
        text(box,"Troubleshooting",21);
        text(box,"Cannot connect: check the agent is running, IP, port, firewall and network. HTTP 401: check the token or copy it again after revocation. Fingerprint mismatch: verify the certificate on the computer before editing the saved fingerprint. Windows termination is forceful and can lose unsaved work.",15);
        button(box,"Full background-service instructions",()->openGuideLink(AgentGuide.README));
        button(box,"Choose another operating system",this::guideScreen);
        button(box,"Back to computers",this::connectionScreen);
    }
    private void processScreen(){
        LinearLayout box=layout();text(box,"Processes",28);status=text(box,"Connecting…",14);
        query=input(box,"Search name, owner or PID","");
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){render();}public void afterTextChanged(Editable e){}});
        button(box,"Sort: memory / CPU / name",()->{sort=(sort+1)%3;render();});
        list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id)->confirm(visible.get(position)));
        button(box,"Computer actions",this::computerActionsMenu);
        button(box,"Disconnect / switch computer",this::connectionScreen);
        refresh();
    }
    private void refresh(){
        if(!active || client==null || busy || computerActionBusy)return;
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
    private boolean currentComputer(AgentClient target,int epoch){
        return !isDestroyed() && !isFinishing() && client==target && generation==epoch;
    }
    private void computerActionsMenu(){
        if(client==null || computerActionBusy)return;
        computerActionBusy=true;final AgentClient target=client;final int epoch=generation;
        Toast.makeText(this,"Checking computer actions…",Toast.LENGTH_SHORT).show();
        worker.execute(()->{
            JSONObject data=null;String error=null;
            try{data=target.computerActions();}
            catch(AgentClient.AgentException e){error=e.status==404?"Update and restart this computer’s agent to use computer actions (LocalTask 0.4 or later).":e.getMessage();}
            catch(Exception e){error="Could not contact the computer. No action was sent.";}
            final JSONObject result=data;final String failure=error;
            runOnUiThread(()->{
                if(!currentComputer(target,epoch))return;
                computerActionBusy=false;
                if(failure!=null){new AlertDialog.Builder(this).setTitle("Computer actions unavailable").setMessage(failure).setPositiveButton("OK",null).show();return;}
                JSONArray actions=result.optJSONArray("actions");
                if(actions==null)return;
                LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(32,16,32,16);
                text(box,"Choose an action. Nothing runs until you confirm it on the next screen.",15);
                ScrollView scroll=new ScrollView(this);scroll.addView(box);
                AlertDialog menu=new AlertDialog.Builder(this).setTitle(result.optString("computer")+" · Computer actions")
                    .setView(scroll).setNegativeButton("Cancel",null).create();
                for(int i=0;i<actions.length();i++){
                    JSONObject item=actions.optJSONObject(i);if(item==null)continue;
                    String action=item.optString("action");
                    if(!Arrays.asList("logout","shutdown","restart").contains(action))continue;
                    Button choice=new Button(this);choice.setText(item.optString("label"));box.addView(choice);
                    choice.setEnabled(item.optBoolean("available"));
                    if(!item.optBoolean("available"))text(box,item.optString("reason"),14);
                    choice.setOnClickListener(v->{menu.dismiss();prepareComputerAction(target,epoch,action);});
                }
                menu.show();
            });
        });
    }
    private void prepareComputerAction(AgentClient target,int epoch,String action){
        if(!currentComputer(target,epoch) || computerActionBusy)return;
        computerActionBusy=true;
        worker.execute(()->{
            JSONObject data=null;String error=null;
            try{data=target.prepareComputerAction(action);}
            catch(Exception e){error=e instanceof AgentClient.AgentException?e.getMessage():"Could not prepare the request. No action was sent.";}
            final JSONObject result=data;final String failure=error;
            runOnUiThread(()->{
                if(!currentComputer(target,epoch))return;
                if(failure!=null){computerActionBusy=false;new AlertDialog.Builder(this).setTitle("Action unavailable").setMessage(failure).setPositiveButton("OK",null).show();return;}
                new AlertDialog.Builder(this).setTitle(result.optString("label")+" "+result.optString("computer")+"?")
                    .setMessage(result.optString("target")+"\n\n"+result.optString("warning")+"\n\nThis confirmation expires in 60 seconds.")
                    .setNegativeButton("Cancel",(d,w)->computerActionBusy=false)
                    .setOnCancelListener(d->computerActionBusy=false)
                    .setPositiveButton(result.optString("label"),(d,w)->executeComputerAction(target,epoch,result.optString("confirmation")))
                    .show();
            });
        });
    }
    private void executeComputerAction(AgentClient target,int epoch,String confirmation){
        if(!currentComputer(target,epoch))return;
        // One attempt only. A lost connection may mean the computer is already logging off/shutting down.
        worker.execute(()->{
            String message;boolean leave=false;
            try{message=target.confirmComputerAction(confirmation).getString("message");leave=true;}
            catch(AgentClient.AgentException e){message=e.getMessage();leave=e.status==504;}
            catch(Exception e){message="The connection ended before the result could be confirmed. The action may already be underway. Check the computer before sending another request.";leave=true;}
            final String outcome=message;final boolean disconnect=leave;
            runOnUiThread(()->{
                if(!currentComputer(target,epoch))return;
                computerActionBusy=false;
                if(disconnect)connectionScreen();
                new AlertDialog.Builder(this).setTitle("Computer action status").setMessage(outcome).setPositiveButton("OK",null).show();
            });
        });
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
    @Override protected void onResume(){super.onResume();active=true;for(ComputerStatus item:computerStatuses)item.label.setText("○ Checking…");timer.post(poll);timer.post(statusPoll);}
    @Override protected void onPause(){active=false;stopStatusChecks();timer.removeCallbacks(poll);if(showingShareQr)connectionScreen();super.onPause();}
    @Override protected void onDestroy(){generation++;stopStatusChecks();statusWorkers.shutdownNow();worker.shutdownNow();super.onDestroy();}
}
