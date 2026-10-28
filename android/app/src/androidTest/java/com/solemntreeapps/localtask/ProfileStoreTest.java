package com.solemntreeapps.localtask;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.security.KeyStore;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProfileStoreTest {
    private static final String PREFS = "profile_store_device_test";
    private static final String ALIAS = "localtask.profile-store.device-test";
    private Context context;
    private ProfileStore store;
    private SharedPreferences prefs;
    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        store = fresh(); store.clear();
    }
    @After public void cleanup() throws Exception { store.clear(); }
    private ProfileStore fresh() { return new ProfileStore(context, PREFS, ALIAS); }
    private JSONObject profile(String id) throws Exception {
        return new JSONObject().put("id", id).put("name", "My Mac " + id)
                .put("host", "192.168.1.20").put("port", "8765")
                .put("token", "test-secret-not-for-real-use")
                .put("fingerprint", "test-fingerprint");
    }
    @Test public void survivesStoreRecreationAndSupportsEditAndRemove() throws Exception {
        store.save(profile("a")); store.save(profile("b"));
        assertEquals(2, fresh().load().length());
        fresh().save(profile("a").put("host", "192.168.1.30"));
        assertEquals(2, fresh().load().length());
        assertEquals("192.168.1.30", fresh().load().getJSONObject(0).getString("host"));
        assertEquals("test-secret-not-for-real-use", fresh().load().getJSONObject(0).getString("token"));
        fresh().remove("a");
        assertEquals(1, fresh().load().length());
        assertEquals("b", fresh().load().getJSONObject(0).getString("id"));
    }
    @Test public void encryptedAtRestWithFreshNonce() throws Exception {
        store.save(profile("a"));
        String first = prefs.getString("encrypted", "");
        assertFalse(first.contains("test-secret-not-for-real-use"));
        assertFalse(first.contains("192.168.1.20"));
        assertFalse(first.contains("test-fingerprint"));
        store.save(profile("a"));
        String second = prefs.getString("encrypted", "");
        assertNotEquals(new JSONObject(first).getString("iv"), new JSONObject(second).getString("iv"));
        assertEquals("a", fresh().load().getJSONObject(0).getString("id"));
    }
    @Test public void corruptDataIsNotSilentlyOverwritten() throws Exception {
        store.save(profile("a"));
        JSONObject envelope = new JSONObject(prefs.getString("encrypted", ""));
        byte[] data = android.util.Base64.decode(envelope.getString("data"), android.util.Base64.NO_WRAP);
        data[0] ^= 1;
        envelope.put("data", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
        String damaged = envelope.toString();
        assertTrue(prefs.edit().putString("encrypted", damaged).commit());
        try { fresh().load(); fail("Tampered ciphertext must be rejected"); } catch (Exception expected) {}
        try { fresh().save(profile("b")); fail("Must not overwrite unreadable profiles"); } catch (Exception expected) {}
        assertEquals(damaged, prefs.getString("encrypted", ""));
    }
    @Test public void missingKeyRequiresExplicitReset() throws Exception {
        store.save(profile("a"));
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(ALIAS);
        try { fresh().load(); fail("Missing key must be reported"); } catch (Exception expected) {}
        assertTrue(prefs.contains("encrypted"));
        fresh().clear();
        assertEquals(0, fresh().load().length());
        fresh().save(profile("b"));
        assertEquals("b", fresh().load().getJSONObject(0).getString("id"));
    }
}
