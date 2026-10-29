package com.solemntreeapps.localtask;

import android.content.*;
import android.os.Bundle;

/** Tasker/Locale wire protocol; constants follow Tasker's published plugin specification. */
public final class TaskerProtocol {
    public static final String HOST="net.dinglisch.android.taskerm";
    public static final String BUNDLE="com.twofortyfouram.locale.intent.extra.BUNDLE";
    public static final String FIRE="com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    public static final String PREFIX="net.dinglisch.android.tasker";
    public static final String COMPLETION=PREFIX+".extras.COMPLETION_INTENT";
    public static final int OK=-1,FAILED=2,PENDING=3;
    private TaskerProtocol(){}
    public static Intent completion(String uri)throws Exception{
        if(uri==null||uri.length()>16384)throw new SecurityException("Missing Tasker completion callback");
        Intent result=Intent.parseUri(uri,Intent.URI_INTENT_SCHEME);
        if(result.getSelector()!=null)throw new SecurityException("Invalid callback selector");
        ComponentName component=result.getComponent();
        if(component!=null&&!HOST.equals(component.getPackageName()))throw new SecurityException("Invalid callback target");
        String servicePackage=result.getStringExtra(PREFIX+".EXTRA_CALL_SERVICE_PACKAGE");
        if(servicePackage!=null&&!HOST.equals(servicePackage))throw new SecurityException("Invalid callback service");
        result.setPackage(HOST);result.setClipData(null);result.setFlags(0);return result;
    }
    public static void finish(Context context,String uri,int code,String state,String message){
        try{
            Intent result=completion(uri);Bundle vars=new Bundle();vars.putString("%lt_status",state);vars.putString("%lt_message",message);
            vars.putString("%lt_online","online".equals(state)?"true":"false");
            if(code!=OK)vars.putString("%errmsg",message);
            result.putExtra(PREFIX+".extras.RESULT_CODE",code);result.putExtra(PREFIX+".extras.VARIABLES",vars);
            String service=result.getStringExtra(PREFIX+".EXTRA_CALL_SERVICE");
            String pkg=result.getStringExtra(PREFIX+".EXTRA_CALL_SERVICE_PACKAGE");
            if(service!=null&&HOST.equals(pkg)){
                result.setComponent(new ComponentName(HOST,service));
                if(result.getBooleanExtra(PREFIX+".EXTRA_CALL_SERVICE_FOREGROUND",false))context.startForegroundService(result);else context.startService(result);
            }else context.sendBroadcast(result);
        }catch(Exception ignored){/* Host timeout remains visible; never retry a computer command. */}
    }
}
