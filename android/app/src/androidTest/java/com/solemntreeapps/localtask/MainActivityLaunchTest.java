package com.solemntreeapps.localtask;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.view.ViewGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MainActivityLaunchTest {
    @Test public void launchesAndDispatchesInsetsWithoutCrashing() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                ViewGroup content = activity.findViewById(android.R.id.content);
                assertNotNull(content);
                assertEquals(1, content.getChildCount());
                if (Build.VERSION.SDK_INT >= 30) {
                    assertNotNull(activity.getWindow().getInsetsController());
                    android.view.WindowInsets insets = content.getRootWindowInsets();
                    assertNotNull(insets);
                    content.dispatchApplyWindowInsets(insets);
                }
            });
            instrumentation.waitForIdleSync();
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
