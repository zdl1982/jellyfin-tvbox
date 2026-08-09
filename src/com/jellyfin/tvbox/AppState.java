package com.jellyfin.tvbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.util.UUID;

/**
 * Simple singleton to share the JellyfinClient across activities.
 */
public class AppState {
    private static JellyfinClient client;
    private static String deviceId;

    /**
     * Generate (and persist) a unique per-device ID.
     *
     * Jellyfin uses DeviceId to distinguish clients; a hardcoded shared value
     * makes multiple devices/users kick each other's sessions. We build a
     * stable unique ID from ANDROID_ID where possible, otherwise a random
     * UUID stored in SharedPreferences (survives app restarts).
     */
    public static synchronized String getDeviceId(Context ctx) {
        if (deviceId != null) return deviceId;
        SharedPreferences prefs = ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
        String saved = prefs.getString("device_id", null);
        if (saved != null && !saved.isEmpty()) {
            deviceId = saved;
            return deviceId;
        }
        String id = null;
        try {
            id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {}
        if (id == null || id.isEmpty() || "9774d56d682e549c".equals(id)) {
            // ANDROID_ID unavailable or a known-broken constant — fall back to random UUID
            id = "jf-" + UUID.randomUUID().toString();
        }
        // Persist so the ID is stable across restarts
        prefs.edit().putString("device_id", id).commit();
        deviceId = id;
        return deviceId;
    }

    public static void setClient(Context ctx, JellyfinClient c) {
        client = c;
        // persist server URL
        if (c != null) {
            ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
               .edit().putString("server", c.getServerUrl()).commit();
        }
    }

    public static JellyfinClient getClient(Context ctx) {
        if (client == null) {
            String server = ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
                               .getString("server", null);
            if (server != null) {
                client = new JellyfinClient(server, getDeviceId(ctx));
            }
        }
        return client;
    }

    public static void clear() {
        client = null;
    }
}