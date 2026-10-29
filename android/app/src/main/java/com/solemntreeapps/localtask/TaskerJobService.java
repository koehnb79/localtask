package com.solemntreeapps.localtask;

import android.app.job.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-persisted, expiring work: stopped jobs are never retried. */
public final class TaskerJobService extends JobService {
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<Integer,Run> runs=new ConcurrentHashMap<>();
    private static final class PreviouslyStarted extends Exception {private static final long serialVersionUID=1L;}
    private static final class Run {final AtomicBoolean done=new AtomicBoolean();volatile boolean stopped;volatile boolean confirming;}
    private void allowed(Run run,PersistableBundle b){if(run.stopped||System.currentTimeMillis()>b.getLong("expires"))throw new SecurityException("Automation expired or was interrupted; no new command sent");}
    private boolean claim(int jobId){
        synchronized(TaskerJobService.class){
            android.content.SharedPreferences p=getSharedPreferences("automation_execution",MODE_PRIVATE);
            String key="job_"+jobId;if(p.contains(key))return false;
            android.content.SharedPreferences.Editor edit=p.edit();long now=System.currentTimeMillis();
            for(java.util.Map.Entry<String,?> e:p.getAll().entrySet())if(e.getValue() instanceof Long && now-(Long)e.getValue()>86400000L)edit.remove(e.getKey());
            if(!edit.putLong(key,now).commit())throw new SecurityException("Could not record automation execution; no command sent");
            return true;
        }
    }
    @Override public boolean onStartJob(JobParameters params){
        Run run=new Run();runs.put(params.getJobId(),run);PersistableBundle b=params.getExtras();
        workers.execute(()->{
            String state="error",message="Automation failed";int code=TaskerProtocol.FAILED;
            try{
                allowed(run,b);
                if(!claim(params.getJobId()))throw new PreviouslyStarted();
                AutomationStore store=new AutomationStore(this);
                JSONObject p=store.authorize(b.getString("grant"),b.getString("capability"),b.getString("action"));
                AgentClient client=new AgentClient(p.getString("host"),p.getString("port"),p.getString("token"),p.getString("fingerprint"));
                if("check".equals(b.getString("action"))){
                    try{client.checkOnline();state="online";message="Agent reachable and authenticated";code=TaskerProtocol.OK;}
                    catch(java.net.SocketTimeoutException|java.net.ConnectException|java.net.NoRouteToHostException e){state="unreachable";message="Agent unreachable; this does not prove the computer is powered off";code=TaskerProtocol.OK;}
                }else{
                    allowed(run,b);JSONObject prepared=client.prepareComputerAction("shutdown");
                    store.authorize(b.getString("grant"),b.getString("capability"),"shutdown");allowed(run,b);
                    String confirmation=prepared.getString("confirmation");
                    synchronized(run){allowed(run,b);run.confirming=true;}
                    client.confirmComputerAction(confirmation);
                    state="requested";message="Shutdown request accepted; completion is not verified";code=TaskerProtocol.OK;
                }
            }catch(Exception e){
                if(run.confirming && e instanceof AgentClient.AgentException && ((AgentClient.AgentException)e).status>=400 && ((AgentClient.AgentException)e).status<500){state="error";message="Agent rejected shutdown (HTTP "+((AgentClient.AgentException)e).status+")";}
                else if(e instanceof PreviouslyStarted){state="uncertain";message="This job already started; its earlier outcome is unknown. No automatic retry.";}
                else if(run.confirming){state="uncertain";message="Shutdown may have been sent. Check the computer before retrying.";}
                else if(e instanceof SecurityException){state="denied";message=e.getMessage();}
                else {state="error";message=ConnectionStatus.message(e);}
            }
            if(run.done.compareAndSet(false,true))TaskerProtocol.finish(this,b.getString("callback"),code,state,message);
            runs.remove(params.getJobId());new Handler(Looper.getMainLooper()).post(()->jobFinished(params,false));
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        Run run=runs.remove(params.getJobId());if(run!=null){synchronized(run){run.stopped=true;if(run.done.compareAndSet(false,true))TaskerProtocol.finish(this,params.getExtras().getString("callback"),TaskerProtocol.FAILED,run.confirming?"uncertain":"interrupted",run.confirming?"Shutdown may have been sent. Check the computer before retrying.":"Android stopped this automation; no automatic retry.");}}return false;
    }
    @Override public void onDestroy(){for(Run run:runs.values())run.stopped=true;workers.shutdownNow();super.onDestroy();}
}
