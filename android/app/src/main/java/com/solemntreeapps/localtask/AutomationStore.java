package com.solemntreeapps.localtask;

import android.content.Context;
import org.json.*;
import java.security.SecureRandom;
import java.util.UUID;

public final class AutomationStore {
    private static final Object LOCK=new Object();
    private final ProfileStore profiles,grants;
    public AutomationStore(Context c){profiles=new ProfileStore(c);grants=new ProfileStore(c,"tasker_grants","localtask.tasker-grants.v1");}
    private JSONObject profile(String id)throws Exception{
        JSONArray values=profiles.load();for(int i=0;i<values.length();i++){JSONObject p=values.getJSONObject(i);if(id.equals(p.optString("id")))return p;}
        throw new SecurityException("Saved computer was removed");
    }
    public JSONObject issue(String id,String action)throws Exception{synchronized(LOCK){
        JSONObject p=profile(id);if("shutdown".equals(action)&&!p.optBoolean("taskerShutdown",false))throw new SecurityException("Enable Tasker shutdown in Edit computer first");
        if(!("check".equals(action)||"shutdown".equals(action)))throw new SecurityException("Unknown action");
        JSONArray list=grants.load();String binding=AutomationPolicy.binding(p);
        for(int i=0;i<list.length();i++){JSONObject g=list.getJSONObject(i);if(id.equals(g.optString("profile"))&&action.equals(g.optString("action"))&&binding.equals(g.optString("binding")))return g;}
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        JSONObject g=new JSONObject().put("id",UUID.randomUUID().toString()).put("profile",id).put("action",action)
            .put("binding",binding).put("capability",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        // Remove obsolete grants for this profile/action rather than growing indefinitely.
        for(int i=0;i<list.length();i++){JSONObject old=list.getJSONObject(i);if(id.equals(old.optString("profile"))&&action.equals(old.optString("action")))grants.remove(old.getString("id"));}
        grants.save(g);return g;
    }}
    public JSONObject authorize(String id,String capability,String action)throws Exception{synchronized(LOCK){
        JSONArray list=grants.load();for(int i=0;i<list.length();i++){JSONObject g=list.getJSONObject(i);if(id.equals(g.optString("id"))){JSONObject p=profile(g.getString("profile"));AutomationPolicy.validate(g,p,capability,action);return p;}}
        throw new SecurityException("Automation access was revoked; reconfigure this Tasker action");
    }}
    public void revokeAll()throws Exception{synchronized(LOCK){grants.clear();}}
}
