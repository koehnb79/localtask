package com.solemntreeapps.localtask;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutomationPolicyTest {
    private JSONObject profile()throws Exception{return new JSONObject().put("id","one").put("host","192.168.1.2").put("port","8765").put("token","agent-key").put("fingerprint","pin").put("automationRevision","rev");}
    private String cap(){return "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopq";}
    private JSONObject grant(JSONObject p,String action)throws Exception{return new JSONObject().put("profile",p.getString("id")).put("binding",AutomationPolicy.binding(p)).put("capability",cap()).put("action",action);}
    private void denied(JSONObject g,JSONObject p,String c,String a)throws Exception{try{AutomationPolicy.validate(g,p,c,a);fail("must reject");}catch(SecurityException expected){}}
    @Test public void checksDoNotAuthorizeShutdown()throws Exception{JSONObject p=profile(),g=grant(p,"check");AutomationPolicy.validate(g,p,cap(),"check");denied(g,p,cap(),"shutdown");}
    @Test public void shutdownRequiresOptInAtExecutionTime()throws Exception{JSONObject p=profile(),g=grant(p,"shutdown");denied(g,p,cap(),"shutdown");p.put("taskerShutdown",true);AutomationPolicy.validate(g,p,cap(),"shutdown");p.put("taskerShutdown",false);denied(g,p,cap(),"shutdown");}
    @Test public void changedIdentityAndRevisionInvalidateGrants()throws Exception{for(String field:new String[]{"id","host","port","token","fingerprint","automationRevision"}){JSONObject p=profile(),g=grant(p,"check");p.put(field,"changed");denied(g,p,cap(),"check");}}
    @Test public void forgedMissingAndWrongCapabilitiesAreDenied()throws Exception{JSONObject p=profile(),g=grant(p,"check");denied(g,p,null,"check");denied(g,p,"", "check");denied(g,p,cap().replace('a','x'),"check");denied(g,p,cap(),"restart");}
    @Test public void qrNeverExportsAutomationPermission()throws Exception{
        JSONObject p=new JSONObject().put("id","one").put("name","PC").put("host","192.168.1.2").put("port","8765")
            .put("token",cap()).put("fingerprint",String.format("%064d",0)).put("taskerShutdown",true).put("automationRevision","private");
        JSONObject restored=ProfileTransfer.decode(ProfileTransfer.encode(p));assertFalse(restored.has("taskerShutdown"));assertFalse(restored.has("automationRevision"));
    }
}
