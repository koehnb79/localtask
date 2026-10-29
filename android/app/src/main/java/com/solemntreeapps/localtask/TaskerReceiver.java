package com.solemntreeapps.localtask;

import android.app.job.*;
import android.content.*;
import android.os.*;
import java.security.SecureRandom;
import java.util.concurrent.*;

public final class TaskerReceiver extends BroadcastReceiver {
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));
    @Override public void onReceive(Context context,Intent intent){
        if(!TaskerProtocol.FIRE.equals(intent.getAction()))return;
        setResultCode(TaskerProtocol.FAILED);
        final Bundle data;final String callback;
        try{
            data=intent.getBundleExtra(TaskerProtocol.BUNDLE);callback=intent.getStringExtra(TaskerProtocol.COMPLETION);
            if(data==null||data.getInt("version")!=1||!isOrderedBroadcast())return;
            TaskerProtocol.completion(callback);
        }catch(Exception e){return;}
        PendingResult pending=goAsync();Context app=context.getApplicationContext();
        try{WORKER.execute(()->{
            try{
                String grant=data.getString("grant"),cap=data.getString("capability"),action=data.getString("action");
                new AutomationStore(app).authorize(grant,cap,action);
                PersistableBundle extras=new PersistableBundle();extras.putString("grant",grant);extras.putString("capability",cap);extras.putString("action",action);extras.putString("callback",callback);
                extras.putLong("expires",System.currentTimeMillis()+45000);
                JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if(scheduler.getAllPendingJobs().size()>=8)throw new IllegalStateException("Automation queue is full");
                int id;do{id=1+new SecureRandom().nextInt(Integer.MAX_VALUE-1);}while(scheduler.getPendingJob(id)!=null);
                JobInfo.Builder job=new JobInfo.Builder(id,new ComponentName(app,TaskerJobService.class)).setExtras(extras);
                if(Build.VERSION.SDK_INT>=31)job.setExpedited(true);else job.setOverrideDeadline(0);
                if(scheduler.schedule(job.build())!=JobScheduler.RESULT_SUCCESS)throw new IllegalStateException("Android could not schedule this automation");
                pending.setResultCode(TaskerProtocol.PENDING);
            }catch(Exception e){
                pending.setResultCode(TaskerProtocol.FAILED);Bundle result=new Bundle(),vars=new Bundle();
                vars.putString("%lt_status","error");vars.putString("%lt_online","false");vars.putString("%errmsg",e instanceof SecurityException?e.getMessage():"Could not start automation; open LocalTask and reconfigure the Tasker action");result.putBundle(TaskerProtocol.PREFIX+".extras.VARIABLES",vars);pending.setResultExtras(result);
            }finally{pending.finish();}
        });}catch(RejectedExecutionException e){pending.finish();}
    }
}
