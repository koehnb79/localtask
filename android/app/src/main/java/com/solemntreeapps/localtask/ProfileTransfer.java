package com.solemntreeapps.localtask;

import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;

/** A QR contains a credential, not a public link. Never log or open its contents as a URL. */
public final class ProfileTransfer {
    static final int MAX_LENGTH = 2048;
    private ProfileTransfer() {}

    private static String field(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
        return ((String) value).trim();
    }
    static JSONObject normalized(JSONObject input) throws Exception {
        String name = field(input, "name"), host = field(input, "host");
        String port = field(input, "port"), token = field(input, "token");
        String fingerprint = field(input, "fingerprint").replace(":", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Computer names must be 1–64 characters without control characters");
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Invalid local IP address");
        String[] parts = host.split("\\."); int[] ip = new int[4];
        for (int i = 0; i < 4; i++) {
            ip[i] = Integer.parseInt(parts[i]);
            if (ip[i] > 255) throw new IllegalArgumentException("Invalid local IP address");
        }
        if (!(ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
                || (ip[0] == 192 && ip[1] == 168))) throw new IllegalArgumentException("Only private LAN addresses are supported");
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
            throw new IllegalArgumentException("Invalid port");
        if (!token.matches("[A-Za-z0-9_-]{43}") || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid connection credentials");
        return new JSONObject().put("name", name)
                .put("host", ip[0]+"."+ip[1]+"."+ip[2]+"."+ip[3])
                .put("port", Integer.toString(Integer.parseInt(port)))
                .put("token", token).put("fingerprint", fingerprint);
    }
    public static String encode(JSONObject profile) throws Exception {
        String result = new JSONObject().put("type", "localtask-profile").put("version", 1)
                .put("computer", normalized(profile)).toString();
        if (result.length() > MAX_LENGTH) throw new IllegalArgumentException("Profile is too large for sharing");
        return result;
    }
    public static JSONObject decode(String payload) throws Exception {
        if (payload == null || payload.length() > MAX_LENGTH) throw new IllegalArgumentException("Invalid LocalTask QR code");
        JSONObject root = new JSONObject(payload);
        if (!"localtask-profile".equals(root.optString("type")) || !(root.opt("version") instanceof Integer)
                || root.getInt("version") != 1) throw new IllegalArgumentException("Unsupported LocalTask QR code");
        // Only known fields survive; sender-controlled IDs or commands cannot be imported.
        return normalized(root.getJSONObject("computer")).put("id", UUID.randomUUID().toString());
    }
}
