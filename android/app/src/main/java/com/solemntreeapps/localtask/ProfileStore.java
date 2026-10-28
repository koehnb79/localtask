package com.solemntreeapps.localtask;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** All profile fields are encrypted together; no token or pin is stored in plaintext. */
public final class ProfileStore {
    private static final String ALIAS = "localtask.saved-computers.v1";
    private static final byte[] AAD = "com.solemntreeapps.localtask/profiles/v1".getBytes(StandardCharsets.UTF_8);
    private final SharedPreferences prefs;
    private final String keyAlias;

    public ProfileStore(Context context) {
        this(context, "saved_computers", ALIAS);
    }
    // Separate storage/key namespace for device tests; production uses the constructor above.
    ProfileStore(Context context, String preferencesName, String keyAlias) {
        prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        this.keyAlias = keyAlias;
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(keyAlias)) return (SecretKey) store.getKey(keyAlias, null);
        if (!create) throw new IOException("Saved computer encryption key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
    public synchronized JSONArray load() throws Exception {
        String stored = prefs.getString("encrypted", null);
        if (stored == null) return new JSONArray();
        JSONObject envelope = new JSONObject(stored);
        if (envelope.getInt("version") != 1) throw new IOException("Unsupported profile format");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128,
                Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
        cipher.updateAAD(AAD);
        byte[] plain = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP));
        return new JSONArray(new String(plain, StandardCharsets.UTF_8));
    }
    private void write(JSONArray profiles) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); // Provider generates a fresh nonce for every write.
        cipher.updateAAD(AAD);
        byte[] data = cipher.doFinal(profiles.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject().put("version", 1)
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        if (!prefs.edit().putString("encrypted", envelope.toString()).commit())
            throw new IOException("Could not save computers on this device");
    }
    public synchronized void save(JSONObject profile) throws Exception {
        JSONArray profiles = load(); // A corrupt file must never be silently replaced.
        String id = profile.getString("id");
        for (int i = 0; i < profiles.length(); i++) {
            if (profiles.getJSONObject(i).getString("id").equals(id)) {
                profiles.put(i, profile); write(profiles); return;
            }
        }
        profiles.put(profile); write(profiles);
    }
    public synchronized void remove(String id) throws Exception {
        JSONArray old = load(), remaining = new JSONArray();
        for (int i = 0; i < old.length(); i++)
            if (!old.getJSONObject(i).getString("id").equals(id)) remaining.put(old.getJSONObject(i));
        write(remaining);
    }
    public synchronized void clear() throws Exception {
        if (!prefs.edit().clear().commit()) throw new IOException("Could not remove saved computers");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null); store.deleteEntry(keyAlias);
    }
}
