package com.solemntreeapps.localtask;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TaskerProtocolTest {
    @Test public void taskerCallbackIsExplicitlyRestricted()throws Exception{
        Intent host=new Intent("finished").putExtra("completion_id",123);
        Intent result=TaskerProtocol.completion(host.toUri(Intent.URI_INTENT_SCHEME));
        assertEquals(TaskerProtocol.HOST,result.getPackage());assertEquals(123,result.getIntExtra("completion_id",0));
    }
    @Test public void otherAppServiceAndComponentAreRejected()throws Exception{
        Intent[] bad={new Intent().setComponent(new ComponentName("other.app","other.app.Service")),
            new Intent().putExtra(TaskerProtocol.PREFIX+".EXTRA_CALL_SERVICE_PACKAGE","other.app")};
        for(Intent intent:bad){try{TaskerProtocol.completion(intent.toUri(Intent.URI_INTENT_SCHEME));fail();}catch(SecurityException expected){}}
    }
    @Test public void missingCallbackCannotExecuteAction()throws Exception{
        try{TaskerProtocol.completion(null);fail();}catch(SecurityException expected){}
    }
}
