package com.solemntreeapps.localtask;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** A Tasker grant is bound to one action, profile and exact saved connection revision. */
public final class AutomationPolicy {
    private AutomationPolicy(){}
    public static String binding(JSONObject p)throws Exception{
        String value=new org.json.JSONArray().put(p.getString("id")).put(p.getString("host"))
            .put(p.getString("port")).put(p.getString("token")).put(p.getString("fingerprint"))
            .put(p.optString("automationRevision")).toString();
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public static void validate(JSONObject grant,JSONObject profile,String capability,String action)throws Exception{
        if(!("check".equals(action)||"shutdown".equals(action)))throw new SecurityException("Unknown action");
        if(capability==null || !capability.matches("[A-Za-z0-9_-]{43}") ||
            !MessageDigest.isEqual(capability.getBytes(StandardCharsets.UTF_8),grant.getString("capability").getBytes(StandardCharsets.UTF_8)) ||
            !action.equals(grant.getString("action")) || !profile.getString("id").equals(grant.getString("profile")) ||
            !binding(profile).equals(grant.getString("binding")))throw new SecurityException("Reconfigure this Tasker action");
        if("shutdown".equals(action)&&!profile.optBoolean("taskerShutdown",false))throw new SecurityException("Tasker shutdown is not enabled for this computer");
    }
}
