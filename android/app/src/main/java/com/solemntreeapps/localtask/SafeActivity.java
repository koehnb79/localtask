package com.solemntreeapps.localtask;

import android.app.Activity;
import android.os.Build;
import android.view.*;
import android.widget.FrameLayout;

/** Common safe area for additional native screens. */
public class SafeActivity extends Activity {
    protected void showSafe(View content){
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        FrameLayout frame=new FrameLayout(this);frame.addView(content);
        if(Build.VERSION.SDK_INT>=30){
            getWindow().setDecorFitsSystemWindows(false);
            frame.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
                v.setPadding(i.left,i.top,i.right,i.bottom);return WindowInsets.CONSUMED;
            });
        }
        setContentView(frame);frame.requestApplyInsets();
    }
}
